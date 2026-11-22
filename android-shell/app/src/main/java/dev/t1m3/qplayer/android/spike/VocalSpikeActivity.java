package dev.t1m3.qplayer.android.spike;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessorChain;
import androidx.media3.common.PlaybackParameters;

import dev.t1m3.qplayer.audio.vocal.TransposeNative;
import dev.t1m3.qplayer.audio.vocal.VocalExoAudioProcessor;
import dev.t1m3.qplayer.audio.vocal.VocalMixingProcessor;

import java.io.File;

/**
 * SPIKE-only Activity: ExoPlayer -> AudioProcessor -> JNI vocal separator.
 *
 * <p>Not part of the main QML playback UI. Launched via adb:
 * {@code adb shell am start -n dev.t1m3.qplayer/dev.t1m3.qplayer.android.spike.VocalSpikeActivity}
 *
 * <p>Reads model from {@code filesDir/vocal/vocal_separation_core.bin} (placed there
 * by the CI-download step / first-run asset copy). Plays a test source passed via
 * intent extra {@code src} (URI or path); if absent, uses a bundled sine asset.
 * The status TextView + logcat prefix {@code VocalSpike} are the verification surface.
 */
public final class VocalSpikeActivity extends Activity {

    private static final String TAG = "VocalSpike";
    private ExoPlayer player;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setTextSize(15f);
        status.setPadding(32, 32, 32, 32);
        setContentView(status);

        File model = new File(getFilesDir(), "vocal/vocal_separation_core.bin");
        TransposeNative sep = new TransposeNative(model.getAbsolutePath());
        append("native library: " + (sep.isSupported() ? "OK" : "MISSING"));
        append("model file: " + (model.isFile() ? model.length() + " bytes" : "MISSING"));

        VocalMixingProcessor mixer = new VocalMixingProcessor(sep);
        VocalExoAudioProcessor fx = new VocalExoAudioProcessor(mixer);
        mixer.setEnabled(true);
        mixer.setSlider(0f); // instrumental side exercises separation

        DefaultRenderersFactory rf = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(android.content.Context context,
                                               boolean enableFloatOutput,
                                               boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setAudioProcessorChain(new AudioProcessorChain() {
                            private final AudioProcessor[] processors = { fx };
                            @Override public AudioProcessor[] getAudioProcessors() { return processors; }
                            @Override public PlaybackParameters applyPlaybackParameters(PlaybackParameters p) {
                                return PlaybackParameters.DEFAULT;
                            }
                            @Override public boolean applySkipSilenceEnabled(boolean b) { return false; }
                            @Override public long getMediaDuration(long playoutDurationUs) { return playoutDurationUs; }
                            @Override public long getSkippedOutputFrameCount() { return 0L; }
                        })
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(false)
                        .build();
            }
        };

        player = new ExoPlayer.Builder(this, rf).build();
        String src = getIntent().getStringExtra("src");
        if (src == null || src.isEmpty()) src = "asset:///spike_test.wav";
        append("source: " + src);
        player.setMediaItem(MediaItem.fromUri(src));
        player.prepare();
        player.play();

        // Periodic status so adb logcat / screenshot proves the chain is alive.
        status.postDelayed(new Runnable() {
            @Override public void run() {
                append("state=" + mixer.state()
                        + " ready=" + mixer.isReady()
                        + " processed=" + mixer.processedFrames()
                        + " fallback=" + mixer.fallbackFrames()
                        + " playing=" + player.isPlaying());
                if (!isFinishing() && !isDestroyed()) status.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void append(String s) {
        Log.i(TAG, s);
        status.append(s + "\n");
    }

    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
