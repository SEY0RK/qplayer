package dev.t1m3.qplayer.android.playback;

/**
 * THREADING NOTE — this is why the class is shaped the way it is.
 *
 * <p>Media3's ExoPlayer throws {@code IllegalStateException: Player is accessed on
 * the wrong thread} for ANY access from a thread other than its application looper.
 * QPlayer's {@code PlayerController.pump()} runs on the QML GL render thread and
 * calls {@code isPlaying()} / {@code position()} on every frame. A direct
 * pass-through backend therefore killed the render thread (QML error page) and
 * audio never started.
 *
 * <p>Rules enforced here:
 * <ul>
 *   <li>the player is created on, and only ever touched from, the main looper;</li>
 *   <li>{@code isPlaying()} / {@code position()} / {@code duration()} answer from
 *       volatile caches and never touch the player.</li>
 * </ul>
 */

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessorChain;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import dev.t1m3.qplayer.android.vocal.VocalCacheProcessor;
import dev.t1m3.qplayer.android.vocal.VocalCacheStore;
import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.util.Collections;
import java.util.Map;

/** {@link AudioBackend} over Media3 ExoPlayer, with main-thread confinement. */
public final class ExoAudioBackend implements AudioBackend {

    private static final long POSITION_POLL_MS = 50L;

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // --- main-thread-only player state -------------------------------------
    private ExoPlayer player;
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
    private String cacheCompletePath;
    private WifiManager.WifiLock wifiLock;
    private AudioFocusRequest focusRequest;

    // --- caches readable from any thread -----------------------------------
    private volatile boolean cIsPlaying;
    private volatile long cPositionMs;
    private volatile long cDurationMs;

    private volatile Runnable onComplete, onStarted, onPaused, onResumed, onError;

    // --- vocal separation (pre-separated cache) ----------------------------
    private final VocalCacheProcessor vocalProcessor;
    private final VocalCacheStore cacheStore;
    private volatile boolean vocalEnabled;
    private volatile boolean cachePreparing;
    private volatile boolean cacheReady;

    private final VocalCacheStore.Callback cacheCallback = new VocalCacheStore.Callback() {
        @Override public void onProgress(String key, float fraction) { }
        @Override public void onReady(String key, VocalCacheStore.Track t) {
            mainHandler.post(() -> {
                cachePreparing = false;
                cacheReady = true;
                if (source != null && key.equals(VocalCacheStore.keyOf(source))) {
                    vocalProcessor.setCache(t.pcm, t.startFrame, t.endFrame,
                            t.channels, t.sampleRate);
                }
            });
        }
        @Override public void onFailed(String key, String reason) {
            mainHandler.post(() -> { cachePreparing = false; cacheReady = false; });
        }
    };

    private final Runnable positionPoll = new Runnable() {
        @Override public void run() {
            ExoPlayer p = player;
            if (p != null) {
                cPositionMs = Math.max(0L, p.getCurrentPosition());
                long d = p.getDuration();
                cDurationMs = d > 0 ? d : 0L;
            }
            mainHandler.postDelayed(this, POSITION_POLL_MS);
        }
    };

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
        this.cacheStore = new VocalCacheStore(appContext.getFilesDir(), model.getAbsolutePath());
        this.vocalProcessor = new VocalCacheProcessor();
        mainHandler.postDelayed(positionPoll, POSITION_POLL_MS);
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

    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    // ---- playback ---------------------------------------------------------

    @Override
    public void play(String src, long startMs) {
        play(src, Collections.emptyMap(), startMs);
    }

    @Override
    public void play(String src, Map<String, String> headers, final long startMs) {
        if (src == null || src.isEmpty()) return;
        final String fSrc = src;
        final Map<String, String> fHeaders = headers == null || headers.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(headers));
        runOnMain(() -> playOnMain(fSrc, fHeaders, startMs));
    }

    private void playOnMain(String src, Map<String, String> headers, long startMs) {
        playbackGeneration++;
        final long generation = playbackGeneration;
        releasePlayerOnMain();
        source = src;
        sourceHeaders = headers;
        attachVocalCacheFor(src);
        pendingSeekMs = Math.max(0L, startMs);
        wantPlay = true;
        prepared = false;
        cacheCompletePath = null;
        cIsPlaying = false;
        cPositionMs = pendingSeekMs;
        cDurationMs = 0L;
        requestFocus();

        final ExoPlayer exo = buildPlayer();
        player = exo;

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(sourceHeaders);
        DefaultDataSource.Factory ds = new DefaultDataSource.Factory(appContext, http);
        MediaSource ms = new ProgressiveMediaSource.Factory(ds)
                .createMediaSource(MediaItem.fromUri(src));

        exo.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (generation != playbackGeneration || player != exo) return;
                if (state == Player.STATE_READY && !prepared) {
                    prepared = true;
                    applyVolume();
                    if (pendingSeekMs > 0L) exo.seekTo(pendingSeekMs);
                    if (wantPlay) {
                        exo.play();
                        holdWifiLock(source);
                        Runnable cb = onStarted;
                        if (cb != null) cb.run();
                    }
                } else if (state == Player.STATE_ENDED) {
                    cIsPlaying = false;
                    Runnable cb = onComplete;
                    if (cb != null) cb.run();
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (generation != playbackGeneration || player != exo) return;
                Logger.error("ExoPlayer error: {}", error.toString());
                wantPlay = false;
                failPlaybackOnMain();
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (generation != playbackGeneration || player != exo) return;
                cIsPlaying = playing;
                if (playing) holdWifiLock(source);
            }
        });

        exo.setMediaSource(ms);
        if (pendingSeekMs > 0L) exo.seekTo(pendingSeekMs);
        exo.prepare();
    }

    /** Attach an existing separated track, or queue an offline build for local files. */
    private void attachVocalCacheFor(String src) {
        VocalCacheStore.Track t = cacheStore.lookup(src);
        if (t != null) {
            vocalProcessor.setCache(t.pcm, t.startFrame, t.endFrame, t.channels, t.sampleRate);
            cacheReady = true;
            cachePreparing = false;
            return;
        }
        vocalProcessor.setCache(null, 0, 0, 2, OFFLINE_SAMPLE_RATE);
        cacheReady = false;
        if (vocalEnabled && isLocalSource(src)) {
            cachePreparing = true;
            cacheStore.requestBuild(src, cacheCallback);
        }
    }

    private static final int OFFLINE_SAMPLE_RATE = 44100;

    private static boolean isLocalSource(String src) {
        if (src == null || src.isEmpty()) return false;
        return !(src.startsWith("http://") || src.startsWith("https://"));
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
        // Built on the main looper; every later call is marshalled there too.
        return new ExoPlayer.Builder(appContext, rf).build();
    }

    private void failPlaybackOnMain() {
        releaseWifiLock();
        releasePlayerOnMain();
        cIsPlaying = false;
        final Runnable cb = onError;
        if (cb != null) mainHandler.post(cb);
    }

    @Override public void pause() {
        runOnMain(() -> {
            wantPlay = false;
            releaseWifiLock();
            if (player != null) player.pause();
            cIsPlaying = false;
        });
    }

    @Override public void resume() {
        runOnMain(() -> {
            wantPlay = true;
            requestFocus();
            if (player != null && prepared) player.play();
        });
    }

    /** Cache-backed: safe to call from the QML render thread every frame. */
    @Override public boolean isPlaying() { return cIsPlaying; }

    @Override public void seek(final long ms) {
        final long target = Math.max(0L, ms);
        runOnMain(() -> {
            pendingSeekMs = target;
            cPositionMs = target;
            // Cache mixing is position-based; tell the processor where playback will
            // resume so the very first block after the seek reads the right offset.
            vocalProcessor.setBaseMs(target);
            if (player != null && prepared) player.seekTo(target);
        });
    }

    @Override public long position() { return cPositionMs; }

    @Override public long duration() { return cDurationMs; }

    @Override public void setVolume(final float v) {
        final float clamped = Math.max(0f, Math.min(1f, v));
        runOnMain(() -> {
            volume = clamped;
            applyVolume();
        });
    }

    private void applyVolume() {
        if (player == null) return;
        player.setVolume(ducked ? volume * 0.3f : volume);
    }

    @Override public void setOnStarted(Runnable cb) { this.onStarted = cb; }
    @Override public void setOnPaused(Runnable cb) { this.onPaused = cb; }
    @Override public void setOnResumed(Runnable cb) { this.onResumed = cb; }
    @Override public void setOnError(Runnable cb) { this.onError = cb; }
    @Override public void setOnComplete(Runnable cb) { this.onComplete = cb; }

    @Override
    public void bindGrowingCache(String pendingPath, final String completePath, long durationMs) {
        runOnMain(() -> cacheCompletePath = completePath);
    }

    @Override
    public void notifyCacheComplete() {
        runOnMain(() -> {
            if (source == null) return;
            if (!(source.startsWith("http://") || source.startsWith("https://"))) return;
            String path = cacheCompletePath;
            if (path == null || path.equals(source)) return;
            File f = new File(path);
            if (!f.isFile() || f.length() == 0 || !wantPlay) return;
            // The finished download is a local file: start the offline pass now so a
            // later play of this track can mix seamlessly, then hand playback over to
            // the file (keeps the rest of the track off the network).
            if (vocalEnabled) cacheStore.requestBuild(path, cacheCallback);
            long pos = position();
            Logger.info("ExoPlayer: switching to complete cache at {}ms", pos);
            playOnMain(path, Collections.<String, String>emptyMap(), pos);
        });
    }

    @Override
    public void release() {
        mainHandler.removeCallbacks(positionPoll);
        runOnMain(() -> {
            playbackGeneration++;
            onComplete = onStarted = onPaused = onResumed = onError = null;
            releaseWifiLock();
            releasePlayerOnMain();
            abandonFocus();
            cacheStore.shutdown();
            cIsPlaying = false;
        });
    }

    private void releasePlayerOnMain() {
        if (player != null) {
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        prepared = false;
        cIsPlaying = false;
    }

    // ---- vocal mix control ------------------------------------------------

    @Override public boolean supportsVocalMix() { return true; }

    /** Master switch: off = raw passthrough, on = mix against the cached track. */
    @Override
    public void setVocalOn(final boolean on) {
        vocalEnabled = on;
        vocalProcessor.setEnabled(on);
        if (on && !cacheReady && source != null) {
            // Turning the switch on is the natural moment to start the offline pass.
            runOnMain(() -> attachVocalCacheFor(source));
        }
    }

    @Override
    public void setVocalMix(final int percent) {
        vocalProcessor.setSlider(Math.max(0, Math.min(100, percent)) / 100f);
    }

    @Override public int vocalState() {
        if (!vocalEnabled) return 0;        // DISABLED
        if (cacheReady) return 2;           // READY (seamless cache attached)
        if (cachePreparing) return 1;       // WARMUP (offline pass running)
        return 0;
    }
    @Override public boolean vocalReady() { return cacheReady; }
    @Override public long vocalProcessedFrames() { return vocalProcessor.positionFrames(); }
    @Override public long vocalFallbackFrames() { return 0L; }

    // ---- audio focus (main thread) ----------------------------------------

    private void requestFocus() {
        if (hasFocus || audioManager == null) return;
        AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(this::onFocusChange, mainHandler)
                .setWillPauseWhenDucked(false)
                .build();
        focusRequest = req;
        hasFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
        focusRequest = null;
        hasFocus = false;
        ducked = false;
        resumeOnGain = false;
    }

    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                hasFocus = false;
                if (player != null && player.isPlaying()) {
                    player.pause();
                    cIsPlaying = false;
                    resumeOnGain = true;
                    Runnable cb = onPaused;
                    if (cb != null) cb.run();
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
                    Runnable cb = onResumed;
                    if (cb != null) cb.run();
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
