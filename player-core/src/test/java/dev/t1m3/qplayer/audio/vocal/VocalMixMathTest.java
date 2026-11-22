package dev.t1m3.qplayer.audio.vocal;

import org.junit.Test;
import static org.junit.Assert.*;

/** Unit tests for the pure mixing math (plan section 2 formulas). */
public class VocalMixMathTest {

    @Test public void gainsMatchDesignTable() {
        // vocal coefficient = 2p-1 on BOTH sides; dry differs.
        assertEquals(1f, VocalMixMath.dryGain(0f), 1e-4);
        assertEquals(-1f, VocalMixMath.vocalGain(0f), 1e-4);   // M - V = instrumental
        assertEquals(1f, VocalMixMath.dryGain(0.25f), 1e-4);
        assertEquals(-0.5f, VocalMixMath.vocalGain(0.25f), 1e-4);
        assertEquals(1f, VocalMixMath.dryGain(0.5f), 1e-4);
        assertEquals(0f, VocalMixMath.vocalGain(0.5f), 1e-4);   // pure M
        assertEquals(0.5f, VocalMixMath.dryGain(0.75f), 1e-4);
        assertEquals(0.5f, VocalMixMath.vocalGain(0.75f), 1e-4);
        assertEquals(0f, VocalMixMath.dryGain(1f), 1e-4);
        assertEquals(1f, VocalMixMath.vocalGain(1f), 1e-4);     // pure V
    }

    @Test public void passthroughDetection() {
        assertTrue(VocalMixMath.isPassthrough(0.5f));
        assertTrue(VocalMixMath.isPassthrough(0.50001f));
        assertFalse(VocalMixMath.isPassthrough(0.51f));
        assertFalse(VocalMixMath.isPassthrough(0.49f));
    }

    @Test public void clamping() {
        assertEquals(0f, VocalMixMath.clamp(-2f), 1e-4);
        assertEquals(1f, VocalMixMath.clamp(2f), 1e-4);
        assertEquals(0.5f, VocalMixMath.clamp(Float.NaN), 1e-4);
    }

    @Test public void mixEndpoints() {
        // p=0 => I = M - V. M=1000, V=300 -> 700
        assertEquals(700, VocalMixMath.mixMono((short) 1000, (short) 300,
                VocalMixMath.dryGain(0f), VocalMixMath.vocalGain(0f)));
        // p=1 => V
        assertEquals(300, VocalMixMath.mixMono((short) 1000, (short) 300,
                VocalMixMath.dryGain(1f), VocalMixMath.vocalGain(1f)));
        // p=0.5 => M
        assertEquals(1000, VocalMixMath.mixMono((short) 1000, (short) 300,
                VocalMixMath.dryGain(0.5f), VocalMixMath.vocalGain(0.5f)));
    }

    @Test public void mixClampsToInt16() {
        assertEquals(Short.MAX_VALUE, VocalMixMath.mixMono((short) 32767, (short) -32768,
                VocalMixMath.dryGain(0f), VocalMixMath.vocalGain(0f)));
        assertEquals(Short.MIN_VALUE, VocalMixMath.mixMono((short) -32768, (short) 32767,
                VocalMixMath.dryGain(0f), VocalMixMath.vocalGain(0f)));
    }

    @Test public void stereoMix() {
        short[] dry = {1000, 2000};
        short[] vocal = {300, 400};
        short[] out = new short[2];
        VocalMixMath.mixStereo(dry, 0, vocal, 0, out, 0,
                VocalMixMath.dryGain(0f), VocalMixMath.vocalGain(0f));
        assertEquals(700, out[0]);
        assertEquals(1600, out[1]);
    }
}
