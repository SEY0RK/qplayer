package dev.t1m3.qplayer.audio.vocal;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ExoPlayer {@link AudioProcessor} bridging Media3 PCM into the platform-neutral
 * {@link VocalMixingProcessor}. PCM_16BIT only (matching the native separator).
 *
 * <p>Contract correctness matters here: Media3 requires that
 * <ul>
 *   <li>{@code queueInput} consumes the WHOLE input buffer between position and limit;</li>
 *   <li>the buffer returned by {@code getOutput()} stays valid until the sink has
 *       drained it — so each block gets its own direct buffer instead of reusing one.</li>
 * </ul>
 */
public final class VocalExoAudioProcessor implements AudioProcessor {

    private static final String TAG = "VocalExo";
    private static final boolean DIAG = true;

    private final VocalMixingProcessor mixer;

    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private AudioFormat outputFormat = AudioFormat.NOT_SET;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded;

    private short[] scratch = new short[0];
    private int configuredChannels = -1;
    private int configuredRate = -1;
    private int diagCount;

    public VocalExoAudioProcessor(VocalMixingProcessor mixer) {
        this.mixer = mixer;
    }

    public VocalMixingProcessor mixer() { return mixer; }

    @Override
    public AudioFormat configure(AudioFormat in) throws UnhandledAudioFormatException {
        if (in.encoding != C.ENCODING_PCM_16BIT) {
            inputFormat = AudioFormat.NOT_SET;
            outputFormat = AudioFormat.NOT_SET;
            return AudioFormat.NOT_SET;
        }
        inputFormat = in;
        outputFormat = in;
        if (in.channelCount != configuredChannels || in.sampleRate != configuredRate) {
            int maxSamples = in.sampleRate * 2 * in.channelCount; // ~1s headroom
            scratch = new short[maxSamples];
            configuredChannels = in.channelCount;
            configuredRate = in.sampleRate;
            mixer.configureSeparator(in.sampleRate, in.channelCount);
            diag("configure rate=" + in.sampleRate + " ch=" + in.channelCount
                    + " sepReady=" + mixer.isReady() + " supported=" + mixer.isSupported());
        }
        return outputFormat;
    }

    @Override public boolean isActive() { return outputFormat != AudioFormat.NOT_SET; }

    @Override
    public void queueInput(ByteBuffer in) {
        int bytes = in.remaining();
        if (bytes <= 0) { outputBuffer = EMPTY_BUFFER; return; }

        int bytesPerFrame = inputFormat.channelCount * 2;
        int frames = bytes / bytesPerFrame;
        int samples = frames * inputFormat.channelCount;
        int consumedBytes = frames * bytesPerFrame;   // may be < bytes if a partial frame trails

        if (samples > scratch.length) scratch = new short[samples];

        for (int i = 0; i < samples; i++) {
            int lo = in.get() & 0xFF;
            int hi = in.get() & 0xFF;
            scratch[i] = (short) (lo | (hi << 8));
        }
        // Consume any trailing partial frame so the buffer is fully drained.
        if (in.hasRemaining()) in.position(in.limit());

        long beforeRms = rms(scratch, samples);
        mixer.process(scratch, frames, inputFormat.sampleRate);
        long afterRms = rms(scratch, samples);

        // One buffer per block: never overwrite data the sink may still hold.
        ByteBuffer out = ByteBuffer.allocateDirect(consumedBytes + (bytes - consumedBytes))
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < samples; i++) {
            short s = scratch[i];
            out.put((byte) (s & 0xFF));
            out.put((byte) ((s >> 8) & 0xFF));
        }
        out.flip();
        outputBuffer = out;

        if (DIAG && diagCount < 60) {
            diagCount++;
            diag("queue bytes=" + bytes + " frames=" + frames
                    + " state=" + mixer.state() + " slider=" + mixer.slider()
                    + " rmsIn=" + beforeRms + " rmsOut=" + afterRms
                    + " outBytes=" + out.remaining());
        }
    }

    private static long rms(short[] buf, int n) {
        if (n <= 0) return 0;
        long sum = 0;
        int step = Math.max(1, n / 512);   // sample ~512 points, cheap
        int count = 0;
        for (int i = 0; i < n; i += step) {
            long v = buf[i];
            sum += v * v;
            count++;
        }
        return count == 0 ? 0 : (long) Math.sqrt((double) sum / count);
    }

    @Override public void queueEndOfStream() { inputEnded = true; }

    @Override public ByteBuffer getOutput() {
        ByteBuffer out = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return out;
    }

    @Override public boolean isEnded() {
        return inputEnded && !outputBuffer.hasRemaining();
    }

    @Override public void flush() {
        inputEnded = false;
        outputBuffer = EMPTY_BUFFER;
        mixer.flush();
        diag("flush");
    }

    @Override public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
        outputFormat = AudioFormat.NOT_SET;
        configuredChannels = -1;
        configuredRate = -1;
        diag("reset");
    }

    private void diag(String msg) {
        if (!DIAG) return;
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) { }
    }
}
