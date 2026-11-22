package dev.t1m3.qplayer.audio.vocal;

/**
 * Platform implementation of the real-time vocal separator.
 *
 * <p>This is the only piece that is allowed to touch ONNX Runtime / native code.
 * {@link VocalMixingProcessor} owns the slider, the raw-PCM bypass and the gain
 * smoothing, and delegates only the "give me the vocals for this PCM block" step
 * here. That keeps the mixing policy identical on Android and Windows.
 *
 * <p>Contract: {@link #separate(short[], int, short[], int, int, int)} must return
 * exactly {@code frames} separated frames for each {@code frames} input frames —
 * i.e. the implementation owns all latency hiding / ring buffering internally
 * (as Transpose's VocalRemovalProcessor does). If it cannot yet produce output
 * (model still loading), it must return {@code false} and the caller falls back
 * to raw PCM; it must never emit partial blocks.
 */
public interface VocalSeparator extends AutoCloseable {

    /** True when this ABI/build can run the native model at all. */
    boolean isSupported();

    /** True once the model is loaded and inference is available. */
    boolean isReady();

    /** Kick off asynchronous model load + warmup. Safe to call repeatedly. */
    void prewarm();

    /**
     * @param dry     interleaved int16 PCM input
     * @param dryOff  offset into {@code dry}
     * @param vocals  destination for separated vocals (same layout/length)
     * @param vocOff  offset into {@code vocals}
     * @param frames  number of frames to process
     * @param channels 1 or 2
     * @return true if {@code vocals} now holds {@code frames * channels} valid samples
     */
    boolean separate(short[] dry, int dryOff, short[] vocals, int vocOff,
                     int frames, int channels);

    /**
     * Prepare format-dependent state (e.g. STFT buffers) once the audio format is
     * known. Default no-op for separators that do not need it. Implementations
     * that own a native STFT should create it here.
     */
    default boolean configure(int sampleRate, int channels) { return true; }

    /**
     * True when {@link #separate} returned false only because the internal latency
     * buffer is not full yet — NOT because inference failed. The caller must keep
     * feeding audio and fall back to dry PCM without entering DEGRADED.
     */
    default boolean isPending() { return false; }

    /**
     * True when this separator performs the dry/vocal blend itself, using an
     * internal time base that keeps the two aligned.
     *
     * <p>This matters: separation runs on a ~0.7 s STFT window, so the vocals it
     * emits correspond to an EARLIER slice of the dry stream. A caller that blends
     * them against the live dry block would subtract mis-aligned signals (the
     * cancellation produces the original mix instead of vocals). Separators that
     * mix internally report true and the caller must NOT blend again.
     */
    default boolean mixesInternally() { return false; }

    /**
     * Current slider position in [0,1] for separators that {@link #mixesInternally}.
     * Cheap and thread-safe: implementations only store the value.
     */
    default void setSlider(float p) { }

    /** Drop buffered state on seek / track change. */
    void flush();

    /** Release native sessions and buffers. Idempotent. */
    @Override
    void close();
}
