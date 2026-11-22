package dev.t1m3.qplayer.audio.vocal;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * SPIKE ExoPlayer AudioProcessor that bridges Media3 PCM into our platform-neutral
 * {@link VocalMixingProcessor}. Only PCM_16BIT is accepted (matching Transpose).
 *
 * <p>Spike scope: verifies ExoPlayer -> AudioProcessor -> JNI separator -> mixer
 * runs end to end. It is NOT wired into QPlayer's main playback UI yet.
 */
public final class VocalExoAudioProcessor implements AudioProcessor {

    private final VocalMixingProcessor mixer;

    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private AudioFormat outputFormat = AudioFormat.NOT_SET;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded;

    private short[] scratch = new short[0];
    private ByteBuffer outDirect = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());
    private boolean configuredSeparator;

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
        int maxSamples = in.sampleRate * 2 * in.channelCount; // 1 second
        scratch = new short[maxSamples];
        outDirect = ByteBuffer.allocateDirect(maxSamples * 2).order(ByteOrder.nativeOrder());
        if (!configuredSeparator) {
            mixer.setChannels(in.channelCount);
            configuredSeparator = true;
        }
        return outputFormat;
    }

    @Override public boolean isActive() { return outputFormat != AudioFormat.NOT_SET; }

    @Override
    public void queueInput(ByteBuffer in) {
        int bytes = in.remaining();
        if (bytes <= 0) { outputBuffer = EMPTY_BUFFER; return; }
        int frames = bytes / (inputFormat.channelCount * 2);
        int samples = frames * inputFormat.channelCount;
        if (samples > scratch.length) {
            scratch = new short[samples];
            outDirect = ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder());
        }
        for (int i = 0; i < samples; i++) {
            int lo = in.get() & 0xFF;
            int hi = in.get() & 0xFF;
            scratch[i] = (short) (lo | (hi << 8));
        }
        mixer.process(scratch, frames, inputFormat.sampleRate);

        outDirect.clear();
        for (int i = 0; i < samples; i++) {
            short s = scratch[i];
            outDirect.put((byte) (s & 0xFF));
            outDirect.put((byte) ((s >> 8) & 0xFF));
        }
        outDirect.flip();
        outputBuffer = outDirect;
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
    }

    @Override public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
        outputFormat = AudioFormat.NOT_SET;
    }
}
