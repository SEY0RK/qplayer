package dev.t1m3.qplayer.audio.vocal;

/**
 * Lifecycle of the vocal-separation + mixing stage.
 *
 * <p>The mixer always has a safe output: when it is not in {@link #PROCESSING}
 * it copies the raw PCM straight through, so a model that is still loading or
 * that failed never silences or corrupts playback.
 */
public enum VocalProcessorState {
    /** Feature switched off — pure bypass, no native work at all. */
    DISABLED,
    /** Model loading / warming up — raw PCM is passed through. */
    WARMUP,
    /** Model ready, waiting for the first processing block. */
    READY,
    /** Separating and mixing in real time. */
    PROCESSING,
    /** Separation threw or returned short output — raw PCM fallback. */
    DEGRADED,
    /** Native resources released; the mixer can no longer process. */
    RELEASED
}
