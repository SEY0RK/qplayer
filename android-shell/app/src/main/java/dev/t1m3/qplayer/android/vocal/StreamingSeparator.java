package dev.t1m3.qplayer.android.vocal;

import android.util.Log;

import com.example.media.audio.VocalRemovalProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Streaming separation engine that reports WHERE each produced block belongs.
 *
 * <p>This deliberately does NOT reuse Transpose's time management (it flushes both
 * rings on toggle and emits silence during WARMUP). Instead the engine is a pure
 * producer: it is fed source frames, and every finished block carries the absolute
 * source frame range it represents, so the playback layer can decide what to emit
 * and can diagnose starvation precisely.
 *
 * <p>Window geometry (44.1 kHz): input 37888 frames (~859 ms), output interval
 * 24576 frames (~557 ms), extraction offset 6656 frames (~151 ms). A block emitted
 * at input position P therefore covers source frames
 * {@code [P + 6656, P + 6656 + 24576)}.
 */
public final class StreamingSeparator {

    private static final String TAG = "VocalStream";

    public static final int SAMPLE_RATE = 44100;
    public static final int CHUNK_FRAMES = 37888;
    public static final int INTERVAL_FRAMES = 24576;
    public static final int EXTRACT_OFFSET = 6656;
    private static final int DIM_F = 2048;
    private static final int MODEL_CHANNELS = 4;
    private static final int TARGET_T = 32;
    private static final int N_FFT = 6144;
    private static final int HOP = 1024;

    /** One separated block, positioned in the source stream. */
    public static final class Segment {
        public final long startFrame;
        public final int frames;
        public final short[] pcm;     // interleaved, owned by the caller after handoff

        Segment(long startFrame, int frames, short[] pcm) {
            this.startFrame = startFrame;
            this.frames = frames;
            this.pcm = pcm;
        }

        public long endFrame() { return startFrame + frames; }
    }

    private final int channels;
    private final int bytesPerFrame;

    private long stftHandle;
    private long modelHandle;

    // Input ring (source frames not yet consumed by the window).
    private final short[] inRing;
    private int inRead, inWrite, inSize;

    /** Source frame index of the oldest frame still in the input ring. */
    private long inBaseFrame;

    private final ByteBuffer modelIn;
    private final ByteBuffer modelOut;
    private final ByteBuffer stftReal;
    private final ByteBuffer stftImag;
    private final ByteBuffer istftReal;
    private final ByteBuffer istftImag;
    private final ByteBuffer pcmChunk;
    private final byte[] dryChunk;
    private final byte[] intervalBytes;
    private final short[] intervalShorts;

    // Diagnostics
    private long blocksProduced;
    private long totalInferenceNanos;
    private long maxInferenceNanos;
    private long lastInferenceNanos;

    public StreamingSeparator(String modelPath, int channels) {
        this.channels = channels;
        this.bytesPerFrame = channels * 2;
        this.inRing = new short[CHUNK_FRAMES * channels * 3];
        this.modelIn = alloc(MODEL_CHANNELS * DIM_F * TARGET_T);
        this.modelOut = alloc(MODEL_CHANNELS * DIM_F * TARGET_T);
        this.stftReal = alloc(channels * DIM_F * TARGET_T);
        this.stftImag = alloc(channels * DIM_F * TARGET_T);
        this.istftReal = alloc(channels * DIM_F * TARGET_T);
        this.istftImag = alloc(channels * DIM_F * TARGET_T);
        this.pcmChunk = ByteBuffer.allocateDirect(CHUNK_FRAMES * bytesPerFrame)
                .order(ByteOrder.nativeOrder());
        this.dryChunk = new byte[CHUNK_FRAMES * bytesPerFrame];
        this.intervalBytes = new byte[INTERVAL_FRAMES * bytesPerFrame];
        this.intervalShorts = new short[INTERVAL_FRAMES * channels];

        stftHandle = VocalRemovalProcessor.initStft(SAMPLE_RATE, N_FFT, HOP, DIM_F);
        modelHandle = VocalRemovalProcessor.initMdxModel(modelPath, 4, DIM_F, MODEL_CHANNELS);
        Log.i(TAG, "init stft=" + stftHandle + " model=" + modelHandle + " ch=" + channels);
    }

    private static ByteBuffer alloc(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder());
    }

    public boolean isReady() { return stftHandle != 0L && modelHandle != 0L; }

    /** Source frame index of the next frame that will be written. */
    public long inputFrame() { return inBaseFrame + inSize / channels; }

    /** Append source frames; bounded ring, oldest frames are dropped if overrun. */
    public void feed(short[] pcm, int offset, int frames) {
        int cap = inRing.length;
        int need = frames * channels;
        if (need > cap) {   // pathological block: keep the newest window
            offset += (need - cap);
            need = cap;
        }
        int free = cap - inSize;
        if (need > free) {
            int drop = need - free;
            drop -= drop % channels;
            inRead = (inRead + drop) % cap;
            inSize -= drop;
            inBaseFrame += drop / channels;
            Log.w(TAG, "input overrun, dropped " + (drop / channels) + " frames");
        }
        int contiguous = Math.min(need, cap - inWrite);
        System.arraycopy(pcm, offset, inRing, inWrite, contiguous);
        if (contiguous < need) {
            System.arraycopy(pcm, offset + contiguous, inRing, 0, need - contiguous);
        }
        inWrite = (inWrite + need) % cap;
        inSize += need;
    }

    /**
     * Run one window if enough input is buffered.
     *
     * @return a positioned block, or null when more input (or a failed run) applies.
     */
    public Segment poll() {
        int chunkSamples = CHUNK_FRAMES * channels;
        if (inSize < chunkSamples) return null;

        // Copy the window out of the ring (contiguous order).
        int si = inRead;
        for (int i = 0; i < chunkSamples; i++) {
            short v = inRing[si];
            si = (si + 1) % inRing.length;
            dryChunk[i * 2] = (byte) (v & 0xFF);
            dryChunk[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }

        long startNanos = System.nanoTime();
        pcmChunk.clear();
        pcmChunk.put(dryChunk, 0, CHUNK_FRAMES * bytesPerFrame).flip();

        int frames = VocalRemovalProcessor.computeStft(
                stftHandle, pcmChunk, CHUNK_FRAMES, stftReal, stftImag, channels);
        if (frames <= 0) { Log.w(TAG, "stft " + frames); return null; }
        if (!VocalRemovalProcessor.packMdx(stftHandle, stftReal, stftImag, modelIn, frames)) {
            Log.w(TAG, "pack failed"); return null;
        }
        if (!VocalRemovalProcessor.runMdx(modelHandle, modelIn, modelOut, frames)) {
            Log.w(TAG, "run failed"); return null;
        }
        if (!VocalRemovalProcessor.unpackMdx(
                stftHandle, modelOut, istftReal, istftImag, frames, channels)) {
            Log.w(TAG, "unpack failed"); return null;
        }

        // Ask for the pure vocal side; the playback layer derives the mix so it can
        // cross-fade against dry without another pass through native.
        int got = VocalRemovalProcessor.computeIstftInterval(
                stftHandle, istftReal, istftImag, dryChunk, intervalBytes, frames, channels,
                EXTRACT_OFFSET, INTERVAL_FRAMES, EXTRACT_OFFSET * bytesPerFrame,
                1f, 1f, true, false, true);
        long elapsed = System.nanoTime() - startNanos;
        if (got != INTERVAL_FRAMES) { Log.w(TAG, "istft " + got); return null; }

        long segStart = inBaseFrame + EXTRACT_OFFSET;
        // Consume one interval worth of input (overlap is retained by design).
        int consume = INTERVAL_FRAMES * channels;
        inRead = (inRead + consume) % inRing.length;
        inSize -= consume;
        inBaseFrame += INTERVAL_FRAMES;

        // Bytes -> shorts
        for (int i = 0; i < intervalShorts.length; i++) {
            int lo = intervalBytes[i * 2] & 0xFF;
            int hi = intervalBytes[i * 2 + 1] & 0xFF;
            intervalShorts[i] = (short) (lo | (hi << 8));
        }
        short[] out = new short[intervalShorts.length];
        System.arraycopy(intervalShorts, 0, out, 0, intervalShorts.length);

        blocksProduced++;
        totalInferenceNanos += elapsed;
        lastInferenceNanos = elapsed;
        if (elapsed > maxInferenceNanos) maxInferenceNanos = elapsed;
        Log.i(TAG, "SEG start=" + segStart + " frames=" + INTERVAL_FRAMES
                + " inferMs=" + (elapsed / 1_000_000)
                + " avgMs=" + (totalInferenceNanos / blocksProduced / 1_000_000)
                + " maxMs=" + (maxInferenceNanos / 1_000_000));
        return new Segment(segStart, INTERVAL_FRAMES, out);
    }

    /** Real-time factor of the last block (1.0 = exactly real time). */
    public double lastRealtimeFactor() {
        if (lastInferenceNanos <= 0) return 0;
        double blockSeconds = (double) INTERVAL_FRAMES / SAMPLE_RATE;
        return (lastInferenceNanos / 1e9) / blockSeconds;
    }

    public long blocksProduced() { return blocksProduced; }
    public long averageInferenceMs() {
        return blocksProduced == 0 ? 0 : totalInferenceNanos / blocksProduced / 1_000_000;
    }
    public long maxInferenceMs() { return maxInferenceNanos / 1_000_000; }

    public void reset() {
        inRead = inWrite = inSize = 0;
        inBaseFrame = 0;
        Log.i(TAG, "reset");
    }

    public void close() {
        if (stftHandle != 0L) { VocalRemovalProcessor.releaseStft(stftHandle); stftHandle = 0L; }
        if (modelHandle != 0L) { VocalRemovalProcessor.releaseMdx(modelHandle); modelHandle = 0L; }
    }
}
