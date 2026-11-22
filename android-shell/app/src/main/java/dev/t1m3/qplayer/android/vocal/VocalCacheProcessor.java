package dev.t1m3.qplayer.android.vocal;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * AudioProcessor that mixes against a PRE-SEPARATED instrumental.
 *
 * <p>Because the cached track is sample-aligned with the source file, there is no
 * algorithmic latency at all: seeking, toggling the switch and dragging the slider
 * are all instantaneous and seamless. This replaces the real-time separator in the
 * playback path; the offline pass is what produces the cache.
 *
 * <p>Vocals are derived as {@code dry - instrumental}, so only one side track is
 * stored. Outside the separated range (head/tail shorter than one STFT chunk) the
 * original PCM is emitted unchanged.
 */
public final class VocalCacheProcessor implements AudioProcessor {

    private static final String TAG = "VocalCache";

    private volatile float slider = 0.5f;
    private volatile boolean enabled;
    private volatile float smoothedDry = 1f;
    private volatile float smoothedVocal = 0f;

    // Mapped cache
    private volatile MappedByteBuffer inst;
    private volatile long startFrame;
    private volatile long endFrame;
    private volatile int cacheChannels = 2;
    private volatile int cacheRate = 44100;

    // Position tracking (audio thread + main thread handoff)
    private long baseFrame;
    private long framesSinceFlush;
    private volatile long pendingBaseMs;

    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private AudioFormat outputFormat = AudioFormat.NOT_SET;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded;
    private short[] work = new short[0];

    // ---- configuration ---------------------------------------------------

    /**
     * Attach (or detach with null) a separated instrumental track.
     * Safe to call from the main thread while audio is running.
     */
    public void setCache(File pcmFile, long start, long end, int channels, int sampleRate) {
        MappedByteBuffer old = inst;
        inst = null;
        startFrame = start;
        endFrame = end;
        cacheChannels = channels <= 0 ? 2 : channels;
        cacheRate = sampleRate <= 0 ? 44100 : sampleRate;
        if (pcmFile != null && pcmFile.isFile() && pcmFile.length() > 0) {
            try (RandomAccessFile raf = new RandomAccessFile(pcmFile, "r");
                 FileChannel ch = raf.getChannel()) {
                inst = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
                inst.order(ByteOrder.LITTLE_ENDIAN);
                android.util.Log.i(TAG, "cache attached frames=[" + start + "," + end
                        + ") bytes=" + ch.size());
            } catch (Throwable t) {
                android.util.Log.w(TAG, "cache map failed: " + t);
                inst = null;
            }
        } else {
            android.util.Log.i(TAG, "cache cleared");
        }
        if (old != null) {
            old = null;   // let GC unmap on the next collection
        }
    }

    public boolean hasCache() { return inst != null; }

    public void setEnabled(boolean on) { this.enabled = on; }

    public void setSlider(float p) {
        if (Float.isNaN(p)) p = 0.5f;
        this.slider = Math.max(0f, Math.min(1f, p));
    }

    /** Tell the processor where the (new) playback position starts, in milliseconds. */
    public void setBaseMs(long ms) { pendingBaseMs = Math.max(0L, ms); }

    /** Approximate playhead in frames, for UI/diagnostics. */
    public long positionFrames() { return baseFrame + framesSinceFlush; }

    // ---- AudioProcessor --------------------------------------------------

    @Override
    public AudioFormat configure(AudioFormat in) throws UnhandledAudioFormatException {
        if (in.encoding != C.ENCODING_PCM_16BIT) {
            inputFormat = AudioFormat.NOT_SET;
            outputFormat = AudioFormat.NOT_SET;
            return AudioFormat.NOT_SET;
        }
        inputFormat = in;
        outputFormat = in;
        int max = in.sampleRate * 2 * in.channelCount;
        if (work.length < max) work = new short[max];
        return outputFormat;
    }

    @Override public boolean isActive() { return outputFormat != AudioFormat.NOT_SET; }

    @Override
    public void queueInput(ByteBuffer in) {
        int bytes = in.remaining();
        if (bytes <= 0) { outputBuffer = EMPTY_BUFFER; return; }

        int ch = inputFormat.channelCount;
        int bytesPerFrame = ch * 2;
        int frames = bytes / bytesPerFrame;
        int samples = frames * ch;

        if (samples > work.length) work = new short[samples];
        for (int i = 0; i < samples; i++) {
            int lo = in.get() & 0xFF;
            int hi = in.get() & 0xFF;
            work[i] = (short) (lo | (hi << 8));
        }
        if (in.hasRemaining()) in.position(in.limit());

        MappedByteBuffer map = inst;
        boolean mixing = enabled && map != null && frames > 0 && ch == cacheChannels;
        // The offline pass always runs at 44.1 kHz (the model's rate), while the
        // source may be 48 kHz. Map playback frames onto cache frames so any rate
        // still works instead of silently disabling separation.
        double cacheRatio = inputFormat.sampleRate == cacheRate
                ? 1.0 : (double) cacheRate / (double) inputFormat.sampleRate;

        if (mixing) {
            float targetDry = slider <= 0.5f ? 1f : 2f * (1f - slider);
            float targetVoc = 2f * slider - 1f;
            smoothedDry = targetDry;
            smoothedVocal = targetVoc;
            float dg = smoothedDry;
            float vg = smoothedVocal;

            long pos = baseFrame + framesSinceFlush;
            for (int f = 0; f < frames; f++, pos++) {
                int base = f * ch;
                long cacheFrame = cacheRatio == 1.0 ? pos : (long) (pos * cacheRatio);
                if (cacheFrame < startFrame || cacheFrame >= endFrame) {
                    continue;   // outside the separated range: keep dry
                }
                long byteOff = cacheFrame * bytesPerFrame;
                for (int c = 0; c < ch; c++) {
                    long o = byteOff + c * 2L;
                    if (o + 1 >= map.limit()) { continue; }
                    short instSample = map.getShort((int) o);
                    float dry = work[base + c];
                    float voc = dry - instSample;
                    float mixed = dry * dg + voc * vg;
                    int v = (int) mixed;
                    if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                    if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                    work[base + c] = (short) v;
                }
            }
        } else {
            smoothedDry = 1f;
            smoothedVocal = 0f;
        }

        ByteBuffer out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        for (int i = 0; i < samples; i++) {
            short s = work[i];
            out.put((byte) (s & 0xFF));
            out.put((byte) ((s >> 8) & 0xFF));
        }
        out.flip();
        outputBuffer = out;
        framesSinceFlush += frames;
    }

    @Override public void queueEndOfStream() { inputEnded = true; }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer out = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return out;
    }

    @Override public boolean isEnded() { return inputEnded && !outputBuffer.hasRemaining(); }

    @Override
    public void flush() {
        inputEnded = false;
        outputBuffer = EMPTY_BUFFER;
        int rate = inputFormat == AudioFormat.NOT_SET ? 44100 : inputFormat.sampleRate;
        baseFrame = pendingBaseMs * rate / 1000L;
        framesSinceFlush = 0;
        smoothedDry = 1f;
        smoothedVocal = 0f;
    }

    @Override
    public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
        outputFormat = AudioFormat.NOT_SET;
    }
}
