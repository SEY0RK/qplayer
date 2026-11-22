package dev.t1m3.qplayer.audio.vocal;

/**
 * Pure, platform-neutral math for the continuous vocal/instrumental slider.
 *
 * <p>Definitions:
 * <ul>
 *   <li>M = original mix (dry PCM)</li>
 *   <li>I = separated instrumental (what Transpose's native layer emits when
 *       its {@code mixRatio} is 1 — its scale is 1 = instrumental, 0 = original)</li>
 *   <li>V = vocals = M - I (derived here, not by the model)</li>
 *   <li>p = slider position in [0, 1]</li>
 * </ul>
 *
 * <p>We feed the separator a fixed {@code mixRatio = 1} (instrumental) and do all
 * blending on int16 samples:
 * <pre>
 *   p &lt;= 0.5 :  Output = (1 - 2p) * I + 2p * M
 *   p &gt;  0.5 :  Output = M + (1 - 2p) * I
 * </pre>
 * At p=0 this is pure instrumental, at p=0.5 pure original, at p=1 {@code M - I}
 * (vocals). The instrumental coefficient is exactly 0 at the centre, which is why
 * 50% can be a true raw-PCM bypass with no discontinuity.
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
        return x <= CENTRE ? 2f * x : 1f;
    }

    /**
     * Coefficient applied to the separator's instrumental track (I).
     *
     * <p>IMPORTANT: the value we mix here is the INSTRUMENTAL, not the vocals.
     * Transpose's native mixRatio uses 1 = instrumental (see its
     * {@code mixed = orig*(1-mix) + instrumental*mix}), so we ask it for the
     * instrumental and derive vocals as {@code M - I} ourselves.
     *
     * <pre>
     *   p = 0.0  ->  I                  (pure instrumental)
     *   p = 0.5  ->  M                  (original, exact passthrough)
     *   p = 1.0  ->  M - I              (vocals only)
     * </pre>
     */
    public static float vocalGain(float p) {
        return 1f - 2f * clamp(p);
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
