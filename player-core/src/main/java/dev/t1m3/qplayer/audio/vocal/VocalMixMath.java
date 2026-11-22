package dev.t1m3.qplayer.audio.vocal;

/**
 * Pure, platform-neutral math for the continuous vocal/instrumental slider.
 *
 * <p>Definitions:
 * <ul>
 *   <li>M = original mix (dry PCM)</li>
 *   <li>V = separated vocals</li>
 *   <li>I = separated instrumental = M - V (Transpose emits vocals only)</li>
 *   <li>p = slider position in [0, 1]</li>
 * </ul>
 *
 * <p>Design formulas from the implementation plan:
 * <pre>
 *   p &lt;= 0.5 :  Output = I + 2p * V
 *   p &gt;  0.5 :  Output = V + 2(1-p) * I
 * </pre>
 * Substituting I = M - V gives a single affine form that is what we actually
 * evaluate on int16 samples:
 * <pre>
 *   Output = M + (2p - 1) * V          (p &lt;= 0.5)
 *   Output = (2 - 2p) * M + (2p - 1) * V   (p &gt; 0.5)
 * </pre>
 * Note the vocal coefficient is {@code 2p - 1} on BOTH sides — at p=0.5 it is
 * exactly 0, which is why the centre can be a true raw-PCM bypass with no
 * audible discontinuity.
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

    /** Coefficient applied to the separated vocals V. */
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
