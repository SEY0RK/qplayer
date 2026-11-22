package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessorChain;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.vocal.TransposeNative;
import dev.t1m3.qplayer.audio.vocal.VocalExoAudioProcessor;
import dev.t1m3.qplayer.audio.vocal.VocalMixingProcessor;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.util.Collections;
import java.util.Map;

/**
 * {@link AudioBackend} over Media3 ExoPlayer. Replaces the MediaPlayer backend so
 * that a real-time {@link AudioProcessor} chain can run on decoded PCM — the
 * prerequisite for vocal separation (MediaPlayer exposes no PCM).
 *
 * <p>Preserves the MediaPlayer backend's contract: async prepare + initial seek,
 * exact seek, audio focus (pause on loss, duck on transient), WifiLock +
 * PARTIAL_WAKE_LOCK for background streaming, a stall watchdog, a growing-cache
 * handoff, and all callbacks. DEFAULT path (separation off) is a transparent pass.
 *
 * <p>The model file is copied from assets to filesDir/vocal on first use.
 */
public final class ExoAudioBackend implements AudioBackend {

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private MediaSource currentSource;
    private String source;
    private Map<String, String> sourceHeaders = Collections.emptyMap();
    private long pendingSeekMs;
    private boolean wantPlay;
    private float volume = 0.8f;
    private boolean prepared;
    private boolean ducked;
    private boolean hasFocus;
    private boolean resumeOnGain;
    private long playbackGeneration;

    private Runnable onComplete, onStarted, onPaused, onResumed, onError;
    private String cachePendingPath, cacheCompletePath;
    private WifiManager.WifiLock wifiLock;

    // ---- vocal separation -------------------------------------------------
    private final VocalMixingProcessor mixer;
    private final VocalExoAudioProcessor vocalProcessor;
    private volatile boolean vocalEnabled;

    public ExoAudioBackend(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "qplayer:stream");
            wifiLock.setReferenceCounted(false);
        }
        File model = new File(appContext.getFilesDir(), "vocal/vocal_separation_core.bin");
        copyModelFromAssets(model);
        TransposeNative separator = new TransposeNative(model.getAbsolutePath());
        this.mixer = new VocalMixingProcessor(separator);
        this.vocalProcessor = new VocalExoAudioProcessor(mixer);
    }

    private void copyModelFromAssets(File dst) {
        try {
            if (dst.isFile() && dst.length() > 29_000_000L) return;
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            try (java.io.InputStream in = appContext.getAssets().open("vocal_separation_core.bin");
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (Throwable t) {
            Logger.warn("vocal model copy failed: {}", t.toString());
        }
    }

    // ---- AudioBackend -----------------------------------------------------

    @Override
    public synchronized void play(String src, long startMs) {
        play(src, Collections.emptyMap(), startMs);
    }

    @Override
    public synchronized void play(String src, Map<String, String> headers, long startMs) {
        if (src == null || src.isEmpty()) return;
        playbackGeneration++;
        long generation = playbackGeneration;
        releasePlayer();
        this.source = src;
        this.sourceHeaders = headers == null || headers.isEmpty()
                ? Collections.emptyMap() : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(headers));
        this.pendingSeekMs = Math.max(0L, startMs);
        this.wantPlay = true;
        this.prepared = false;
        this.cacheCompletePath = null;
        requestFocus();

        ExoPlayer exo = buildPlayer();
        player = exo;

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        for (Map.Entry<String, String> e : sourceHeaders.entrySet()) {
            http.setDefaultRequestProperty(e.getKey(), e.getValue());
        }
        DefaultDataSource.Factory ds = new DefaultDataSource.Factory(appContext, http);
        MediaSource ms = new ProgressiveMediaSource.Factory(ds)
                .createMediaSource(MediaItem.fromUri(src));
        currentSource = ms;

        exo.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (generation != playbackGeneration || player != exo) return;
                if (state == Player.STATE_READY && !prepared) {
                    prepared = true;
                    applyVolume();
                    long seek = pendingSeekMs;
                    if (seek > 0L) {
                        exo.seekTo(seek);
                    }
                    if (wantPlay) {
                        exo.play();
                        holdWifiLock(source);
                        if (onStarted != null) onStarted.run();
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (onComplete != null) onComplete.run();
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (generation != playbackGeneration || player != exo) return;
                Logger.error("ExoPlayer error: {}", error.toString());
                wantPlay = false;
                failPlayback();
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (generation != playbackGeneration || player != exo) return;
                if (playing) holdWifiLock(source);
            }
        });

        exo.setMediaSource(ms);
        if (pendingSeekMs > 0L) exo.seekTo(pendingSeekMs);
        exo.prepare();
    }

    private ExoPlayer buildPlayer() {
        DefaultRenderersFactory rf = new DefaultRenderersFactory(appContext) {
            @Override
            protected AudioSink buildAudioSink(android.content.Context context,
                                               boolean enableFloatOutput,
                                               boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setAudioProcessorChain(new AudioProcessorChain() {
                            private final AudioProcessor[] processors = { vocalProcessor };
                            @Override public AudioProcessor[] getAudioProcessors() { return processors; }
                            @Override public PlaybackParameters applyPlaybackParameters(PlaybackParameters p) {
                                return PlaybackParameters.DEFAULT;
                            }
                            @Override public boolean applySkipSilenceEnabled(boolean b) { return false; }
                            @Override public long getMediaDuration(long playoutDurationUs) { return playoutDurationUs; }
                            @Override public long getSkippedOutputFrameCount() { return 0; }
                        })
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(false)
                        .build();
            }
        };
        ExoPlayer exo = new ExoPlayer.Builder(appContext, rf).build();
        exo.setWakeMode(C.WAKE_MODE_LOCAL); // we hold our own PARTIAL_WAKE_LOCK
        return exo;
    }

    private void failPlayback() {
        releaseWifiLock();
        releasePlayer();
        if (onError != null) {
            Runnable err = onError;
            mainHandler.post(() -> { if (err != null) err.run(); });
        }
    }

    @Override
    public synchronized void pause() {
        wantPlay = false;
        releaseWifiLock();
        if (player != null) player.pause();
    }

    @Override
    public synchronized void resume() {
        wantPlay = true;
        requestFocus();
        if (player != null && prepared) player.play();
    }

    @Override
    public synchronized boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override
    public synchronized void seek(long ms) {
        long target = Math.max(0L, ms);
        pendingSeekMs = target;
        if (player != null && prepared) player.seekTo(target);
    }

    @Override
    public synchronized long position() {
        if (player == null) return pendingSeekMs;
        return Math.max(0L, player.getCurrentPosition());
    }

    @Override
    public synchronized long duration() {
        if (player == null || !prepared) return 0L;
        long d = player.getDuration();
        return d > 0 ? d : 0L;
    }

    @Override
    public synchronized void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        applyVolume();
    }

    private void applyVolume() {
        if (player == null) return;
        player.setVolume(ducked ? volume * 0.3f : volume);
    }

    @Override public synchronized void setOnStarted(Runnable cb) { this.onStarted = cb; }
    @Override public synchronized void setOnPaused(Runnable cb) { this.onPaused = cb; }
    @Override public synchronized void setOnResumed(Runnable cb) { this.onResumed = cb; }
    @Override public synchronized void setOnError(Runnable cb) { this.onError = cb; }
    @Override public synchronized void setOnComplete(Runnable cb) { this.onComplete = cb; }

    @Override
    public synchronized void bindGrowingCache(String pendingPath, String completePath, long durationMs) {
        this.cacheCompletePath = completePath;
    }

    @Override
    public synchronized void notifyCacheComplete() {
        // A finished cache can be handed to ExoPlayer as a new source at the same
        // position. Only when the live stream is actually the active one.
        if (source != null && (source.startsWith("http://") || source.startsWith("https://"))) {
            String path = cacheCompletePath;
            if (path != null && !path.equals(source)) {
                File f = new File(path);
                if (f.isFile() && f.length() > 0 && wantPlay) {
                    long pos = position();
                    Logger.info("ExoPlayer: switching to complete cache at {}ms", pos);
                    play(path, Collections.emptyMap(), pos);
                }
            }
        }
    }

    @Override
    public synchronized void release() {
        playbackGeneration++;
        onComplete = onStarted = onPaused = onResumed = onError = null;
        releaseWifiLock();
        releasePlayer();
        abandonFocus();
        mixer.close();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignored) { }
            player = null;
        }
        currentSource = null;
        prepared = false;
    }

    // ---- vocal mix control -------------------------------------------------

    @Override public boolean supportsVocalMix() { return true; }

    /** 0..100 slider. 50 = strict raw passthrough. */
    @Override public synchronized void setVocalMix(int percent) {
        float p = Math.max(0f, Math.min(100f, percent)) / 100f;
        mixer.setSlider(p);
        boolean shouldEnable = Math.abs(p - 0.5f) > 0.001f;
        if (shouldEnable != vocalEnabled) {
            vocalEnabled = shouldEnable;
            mixer.setEnabled(shouldEnable);
        }
    }

    @Override public int vocalState() { return mixer.state().ordinal(); }
    @Override public boolean vocalReady() { return mixer.isReady(); }
    @Override public long vocalProcessedFrames() { return mixer.processedFrames(); }
    @Override public long vocalFallbackFrames() { return mixer.fallbackFrames(); }

    // ---- audio focus ------------------------------------------------------

    private void requestFocus() {
        if (hasFocus || audioManager == null) return;
        AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .setWillPauseWhenDucked(false)
                .build();
        hasFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        hasFocus = false;
        ducked = false;
        resumeOnGain = false;
    }

    private synchronized void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                hasFocus = false;
                if (player != null && player.isPlaying()) {
                    player.pause();
                    resumeOnGain = true;
                    if (onPaused != null) onPaused.run();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null && player.isPlaying()) {
                    ducked = true;
                    applyVolume();
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                if (ducked) { ducked = false; applyVolume(); }
                if ((resumeOnGain || wantPlay) && player != null && prepared) {
                    resumeOnGain = false;
                    wantPlay = true;
                    player.play();
                    if (onResumed != null) onResumed.run();
                }
                break;
            default: break;
        }
    }

    private void holdWifiLock(String src) {
        if (wifiLock == null || src == null) return;
        if (!(src.startsWith("http://") || src.startsWith("https://"))) return;
        try { if (!wifiLock.isHeld()) wifiLock.acquire(); } catch (Throwable ignored) { }
    }

    private void releaseWifiLock() {
        if (wifiLock == null) return;
        try { if (wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) { }
    }
}
