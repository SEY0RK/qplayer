package com.example.media.audio;

import java.nio.ByteBuffer;

/**
 * JNI symbol host for the Transpose native vocal-removal library (GPL-3.0).
 *
 * <p>The native .so built from joh9911/Transpose exports symbols named after this
 * exact class, e.g. {@code Java_com_example_media_audio_VocalRemovalProcessor_nativeInitStft}.
 * Declaring the native methods here (with this FQCN) lets us reuse the C++
 * unchanged. The methods are package-private statics; the C++ ignores the
 * jobject/jclass second parameter, so static declarations link fine.
 *
 * <p>All high-level logic lives in {@code dev.t1m3.qplayer.audio.vocal.TransposeNative}.
 */
public final class VocalRemovalProcessor {

    private VocalRemovalProcessor() { }

    public static final boolean LIB_AVAILABLE = loadNative();

    private static boolean loadNative() {
        try {
            System.loadLibrary("vocal_removal_native");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static long initStft(int sr, int fft, int hop, int dim) { return nativeInitStft(sr, fft, hop, dim); }
    public static int computeStft(long h, ByteBuffer pcm, int n, ByteBuffer re, ByteBuffer im, int ch) {
        return nativeComputeStft(h, pcm, n, re, im, ch);
    }
    public static int computeIstftInterval(long h, ByteBuffer re, ByteBuffer im, byte[] dry, byte[] out,
                                           int frames, int ch, int off, int interval, int dryOffBytes,
                                           float mix, float subGain, boolean vocalOut,
                                           boolean convertedToInst, boolean vocalOnly) {
        return nativeComputeIstftInterval(h, re, im, dry, out, frames, ch, off, interval,
                dryOffBytes, mix, subGain, vocalOut, convertedToInst, vocalOnly);
    }
    public static boolean packMdx(long h, ByteBuffer re, ByteBuffer im, ByteBuffer in, int frames) {
        return nativePackMdxModelInput(h, re, im, in, frames);
    }
    public static boolean unpackMdx(long h, ByteBuffer out, ByteBuffer re, ByteBuffer im, int frames, int ch) {
        return nativeUnpackMdxModelOutput(h, out, re, im, frames, ch);
    }
    public static long initMdxModel(String path, int threads, int dim, int ch) {
        return nativeInitMdxModel(path, threads, dim, ch);
    }
    public static boolean runMdx(long h, ByteBuffer in, ByteBuffer out, int frames) {
        return nativeRunMdxModel(h, in, out, frames);
    }
    public static void releaseStft(long h) { if (h != 0L) nativeReleaseStft(h); }
    public static void releaseMdx(long h) { if (h != 0L) nativeReleaseMdxModel(h); }

    private static native long nativeInitStft(int sampleRate, int nFft, int hopLength, int dimF);
    private static native int nativeComputeStft(long handle, ByteBuffer pcmInput, int numSamples,
                                                ByteBuffer outputReal, ByteBuffer outputImag, int channelCount);
    private static native int nativeComputeIstftInterval(long handle, ByteBuffer inputReal, ByteBuffer inputImag,
                                                          byte[] dryPcmInput, byte[] pcmOutput, int numFrames,
                                                          int channelCount, int extractOffsetSamples,
                                                          int intervalSamples, int dryOffsetBytes, float mixRatio,
                                                          float vocalSubtractGain, boolean modelOutputsVocal,
                                                          boolean modelOutputConvertedToInstrumental,
                                                          boolean vocalOnlyMode);
    private static native boolean nativePackMdxModelInput(long handle, ByteBuffer stftReal, ByteBuffer stftImag,
                                                           ByteBuffer modelInput, int numFrames);
    private static native boolean nativeUnpackMdxModelOutput(long handle, ByteBuffer modelOutput,
                                                              ByteBuffer istftReal, ByteBuffer istftImag,
                                                              int numFrames, int channelCount);
    private static native long nativeInitMdxModel(String modelPath, int threads, int dimF, int channels);
    private static native boolean nativeRunMdxModel(long handle, ByteBuffer inputBuffer,
                                                     ByteBuffer outputBuffer, int frames);
    private static native void nativeReleaseStft(long handle);
    private static native void nativeReleaseMdxModel(long handle);
}
