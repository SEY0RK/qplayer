package dev.t1m3.qplayer.audio.vocal;

/**
 * Pure, platform-neutral math for the continuous vocal/instrumental slider.
 *
 * <p>Definitions:
 * <ul>
 *   <li>M = original mix (dry PCM)</li>
 *   <li>V = separated vocals</li>
 *   <li>I = instrumental = M - V</li>
 *   <li>p = slider position in [0, 1]</li>
 * </ul>
 *
 * <p>Blending:
 * <pre>
 *   p &lt;= 0.5 :  Output = M - (1 - 2p) * V
 *   p &gt;  0.5 :  Output = (2 - 2p) * M + (2p - 1) * V
 * </pre>
 * Both reduce to {@code dryGain = 1, vocalGain = 2p - 1} below centre and
 * {@code dryGain = 2 - 2p, vocalGain = 2p - 1} above it. The vocal coefficient is
 * exactly 0 at p=0.5, which is why the centre is a true raw-PCM bypass.
 *
 * <p>IMPORTANT — latency: V is produced from a ~0.7 s STFT window, so it is
 * aligned with an EARLIER slice of M, not with the block currently arriving.
 * Callers that blend with a live dry buffer will therefore mis-align the two and
 * the subtraction cancels nothing. The native separator solves this internally
 * (it blends with the same time base); see VocalSeparator.mixesInternally().
 *
 * <p>No loudness compensation is applied, by design (plan section 2).
 */
public final class VocalMixMath {

    /** Slider centre: strict raw-PCM passthrough. */
    public static final float CENTRE = 0.5f;
    /** Tolerance for treating a slider value as the exact centre. */
    public static final float CENTRE_EPS = 1e-4f;

    private VocalMixMath() { }

    public static float clamp(float p) {
        if (Float.isNaN(p)) return CENTRE;
        if (p < 0f) return 0f;
        if (p > 1f) return 1f;
        return p;
    }

    /** True when p is close enough to centre to warrant a raw-PCM bypass. */
    public static boolean isPassthrough(float p) {
        return Math.abs(clamp(p) - CENTRE) <= CENTRE_EPS;
    }

    /** Coefficient applied to the original mix M. */
    public static float dryGain(float p) {
        float x = clamp(p);
        return x <= CENTRE ? 1f : 2f * (1f - x);
    }

    /**
     * Coefficient applied to the separated vocals V. Negative below centre, which
     * is what turns {@code M + vocalGain*V} into {@code M - V} (instrumental).
     */
    public static float vocalGain(float p) {
        return 2f * clamp(p) - 1f;
    }

    /**
     * Mix one interleaved stereo int16 pair. Sample values are treated as signed
     * 16-bit; the result is clamped back into int16 range — clipping is expected
     * near the extremes because no loudness normalisation is performed.
     */
    public static void mixStereo(short[] dry, int dryOff,
                                 short[] vocal, int vocalOff,
                                 short[] out, int outOff,
                                 float dryGain, float vocalGain) {
        for (int c = 0; c < 2; c++) {
            float v = dry[dryOff + c] * dryGain + vocal[vocalOff + c] * vocalGain;
            out[outOff + c] = (short) clampInt16(v);
        }
    }

    /** Mix one mono int16 sample. */
    public static short mixMono(short dry, short vocal, float dryGain, float vocalGain) {
        return (short) clampInt16(dry * dryGain + vocal * vocalGain);
    }

    static int clampInt16(float v) {
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        return Math.round(v);
    }
}
