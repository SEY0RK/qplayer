package dev.t1m3.qplayer.audio.vocal;

import com.example.media.audio.VocalRemovalProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Spike implementation of {@link VocalSeparator} backed by the Transpose native
 * library (GPL-3.0), reused unmodified via the JNI symbol host.
 *
 * <p>Does STFT -> MDX ONNX inference -> ISTFT on int16 PCM. Owns all latency
 * buffering internally so callers always get frame-aligned vocals or a clean
 * {@code false} (never a partial block).
 */
public final class TransposeNative implements VocalSeparator {

    private static final int N_FFT = 6144;
    private static final int HOP = 1024;
    private static final int DIM_F = 2048;
    private static final int TARGET_T = 32;
    private static final int OUTPUT_T = 24;
    private static final int CHANNELS = 4;
    private static final int THREADS = 4;

    private static final int CHUNK_SAMPLES = (TARGET_T - 1) * HOP + N_FFT;   // 37888
    private static final int INTERVAL_SAMPLES = OUTPUT_T * HOP;              // 24576
    private static final int EXTRACT_OFFSET = (CHUNK_SAMPLES - INTERVAL_SAMPLES) / 2;

    private volatile boolean supported;
    private volatile long stftHandle;
    private volatile long modelHandle;
    private volatile boolean modelLoading;
    private int diagCount;

    private final String modelPath;
    private int sampleRate = 44100;
    private int inChannels = 2;
    private int bytesPerFrame = 4;

    private ByteBuffer stftReal, stftImag, istftReal, istftImag, modelIn, modelOut, pcmChunk;
    private byte[] intervalBytes, dryChunk;
    private Ring inputRing;
    private byte[] vocalRing;
    private int vocalRead, vocalWrite, vocalAvail;

    public TransposeNative(String modelPath) {
        this.modelPath = modelPath;
        this.supported = VocalRemovalProcessor.LIB_AVAILABLE;
    }

    @Override public boolean isSupported() { return supported; }
    @Override public boolean isReady() { return modelHandle != 0L && stftHandle != 0L; }

    @Override public void prewarm() {
        if (!supported || modelHandle != 0L || modelLoading) return;
        modelLoading = true;
        new Thread(() -> {
            try {
                modelHandle = VocalRemovalProcessor.initMdxModel(modelPath, THREADS, DIM_F, CHANNELS);
            } catch (Throwable t) {
                modelHandle = 0L;
            } finally {
                modelLoading = false;
            }
        }, "vocal-model-load").start();
    }

    /** Must be called once the audio format is known. Returns false for unsupported formats. */
    public boolean configure(int sampleRate, int channels) {
        if (sampleRate != 44100 || (channels != 1 && channels != 2)) return false;
        this.sampleRate = sampleRate;
        this.inChannels = channels;
        this.bytesPerFrame = channels * 2;
        if (stftHandle != 0L) VocalRemovalProcessor.releaseStft(stftHandle);
        stftHandle = VocalRemovalProcessor.initStft(sampleRate, N_FFT, HOP, DIM_F);
        if (stftHandle == 0L) return false;

        int stereoFrames = 2 * DIM_F * TARGET_T;
        int modelValues = CHANNELS * DIM_F * TARGET_T;
        stftReal = alloc(stereoFrames);
        stftImag = alloc(stereoFrames);
        istftReal = alloc(stereoFrames);
        istftImag = alloc(stereoFrames);
        modelIn = alloc(modelValues);
        modelOut = alloc(modelValues);
        pcmChunk = alloc(CHUNK_SAMPLES * channels);
        intervalBytes = new byte[INTERVAL_SAMPLES * bytesPerFrame];
        dryChunk = new byte[CHUNK_SAMPLES * bytesPerFrame];
        inputRing = new Ring(CHUNK_SAMPLES * bytesPerFrame * 8);
        vocalRing = new byte[INTERVAL_SAMPLES * bytesPerFrame * 8];
        vocalRead = vocalWrite = vocalAvail = 0;
        return true;
    }

    private static ByteBuffer alloc(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder());
    }

    private void logDiag(String msg) {
        if (diagCount++ < 30) android.util.Log.i("VocalDiag", msg);
    }

    @Override
    public boolean separate(short[] dry, int dryOff, short[] vocals, int vocOff,
                            int frames, int channels) {
        if (!isReady()) return false;
        int need = frames * channels;
        byte[] dryBytes = toBytes(dry, dryOff, need);
        inputRing.write(dryBytes, 0, dryBytes.length);
        produceIntervals();
        int wantBytes = need * 2;
        if (diagCount < 30) logDiag("separate frames=" + frames + " wantBytes=" + wantBytes
                + " vocalAvail=" + vocalAvail + " inputAvail=" + inputRing.available()
                + " stft=" + stftHandle + " model=" + modelHandle);
        if (vocalAvail < wantBytes) return false;
        byte[] out = readBytes(wantBytes);
        for (int i = 0; i < need; i++) {
            int lo = out[i * 2] & 0xFF;
            int hi = out[i * 2 + 1] & 0xFF;
            vocals[vocOff + i] = (short) (lo | (hi << 8));
        }
        return true;
    }

    private void produceIntervals() {
        int chunkBytes = CHUNK_SAMPLES * bytesPerFrame;
        while (inputRing.available() >= chunkBytes
                && vocalRing.length - vocalAvail >= intervalBytes.length) {
            byte[] pcm = inputRing.peek(chunkBytes);
            System.arraycopy(pcm, 0, dryChunk, 0, chunkBytes);
            if (!processChunk()) return;
            inputRing.discard(INTERVAL_SAMPLES * bytesPerFrame);
            writeVocal(intervalBytes);
        }
    }

    private boolean processChunk() {
        pcmChunk.clear();
        pcmChunk.put(dryChunk, 0, CHUNK_SAMPLES * bytesPerFrame).flip();
        int frames = VocalRemovalProcessor.computeStft(
                stftHandle, pcmChunk, CHUNK_SAMPLES, stftReal, stftImag, inChannels);
        logDiag("computeStft frames=" + frames);
        if (frames <= 0) return false;
        if (inChannels == 1) {
            int stride = DIM_F * frames;
            FloatBuffer r = stftReal.asFloatBuffer();
            FloatBuffer i = stftImag.asFloatBuffer();
            for (int k = 0; k < stride; k++) {
                r.put(stride + k, r.get(k));
                i.put(stride + k, i.get(k));
            }
        }
        boolean packed = VocalRemovalProcessor.packMdx(stftHandle, stftReal, stftImag, modelIn, frames);
        logDiag("packMdx=" + packed);
        if (!packed) return false;
        boolean ran = VocalRemovalProcessor.runMdx(modelHandle, modelIn, modelOut, frames);
        logDiag("runMdx=" + ran);
        if (!ran) return false;
        boolean unpacked = VocalRemovalProcessor.unpackMdx(stftHandle, modelOut, istftReal, istftImag, frames, inChannels);
        logDiag("unpackMdx=" + unpacked);
        if (!unpacked) return false;
        int got = VocalRemovalProcessor.computeIstftInterval(
                stftHandle, istftReal, istftImag, dryChunk, intervalBytes, frames, inChannels,
                EXTRACT_OFFSET, INTERVAL_SAMPLES, EXTRACT_OFFSET * bytesPerFrame,
                1f, 1f, true, false, false);
        logDiag("computeIstft got=" + got + " expect=" + INTERVAL_SAMPLES);
        return got == INTERVAL_SAMPLES;
    }

    private static byte[] toBytes(short[] s, int off, int n) {
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v = s[off + i];
            b[i * 2] = (byte) (v & 0xFF);
            b[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return b;
    }

    private void writeVocal(byte[] b) {
        if (vocalRing.length - vocalAvail < b.length) return;
        int tail = Math.min(b.length, vocalRing.length - vocalWrite);
        System.arraycopy(b, 0, vocalRing, vocalWrite, tail);
        System.arraycopy(b, tail, vocalRing, 0, b.length - tail);
        vocalWrite = (vocalWrite + b.length) % vocalRing.length;
        vocalAvail += b.length;
    }

    private byte[] readBytes(int n) {
        byte[] out = new byte[n];
        int tail = Math.min(n, vocalRing.length - vocalRead);
        System.arraycopy(vocalRing, vocalRead, out, 0, tail);
        System.arraycopy(vocalRing, 0, out, tail, n - tail);
        vocalRead = (vocalRead + n) % vocalRing.length;
        vocalAvail -= n;
        return out;
    }

    @Override public void flush() {
        if (inputRing != null) inputRing.clear();
        vocalRead = vocalWrite = vocalAvail = 0;
    }

    @Override public void close() {
        if (stftHandle != 0L) { VocalRemovalProcessor.releaseStft(stftHandle); stftHandle = 0L; }
        if (modelHandle != 0L) { VocalRemovalProcessor.releaseMdx(modelHandle); modelHandle = 0L; }
    }

    /** Simple growable-free byte ring sized at construction. */
    private static final class Ring {
        private final byte[] buf;
        private int read, write, size;
        Ring(int cap) { buf = new byte[Math.max(1, cap)]; }
        int available() { return size; }
        void write(byte[] src, int off, int len) {
            while (len > 0) {
                int w = Math.min(len, buf.length - write);
                System.arraycopy(src, off, buf, write, w);
                write = (write + w) % buf.length;
                off += w; len -= w; size += w;
            }
        }
        byte[] peek(int n) {
            byte[] out = new byte[n];
            int r = read;
            for (int k = 0; k < n; k++) { out[k] = buf[r]; r = (r + 1) % buf.length; }
            return out;
        }
        void discard(int n) {
            n = Math.min(n, size);
            read = (read + n) % buf.length;
            size -= n;
        }
        void clear() { read = write = size = 0; }
    }
}
