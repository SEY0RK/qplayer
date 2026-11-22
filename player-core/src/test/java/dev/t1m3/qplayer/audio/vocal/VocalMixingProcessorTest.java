package dev.t1m3.qplayer.audio.vocal;

import org.junit.Test;
import static org.junit.Assert.*;

/** Tests bypass / fallback / ramp policy with a fake separator. */
public class VocalMixingProcessorTest {

    /** Fake separator: returns vocals = dry/2, ready on demand. */
    static final class FakeSeparator implements VocalSeparator {
        boolean ready;
        boolean supported = true;
        boolean throwOnSeparate = false;
        int separateCalls = 0;

        public boolean isSupported() { return supported; }
        public boolean isReady() { return ready; }
        public void prewarm() { ready = true; }
        public boolean separate(short[] dry, int d, short[] v, int vo, int frames, int ch) {
            separateCalls++;
            if (throwOnSeparate) throw new RuntimeException("boom");
            for (int i = 0; i < frames * ch; i++) v[vo + i] = (short) (dry[d + i] / 2);
            return true;
        }
        public void flush() { }
        public void close() { }
    }

    static short[] block(int frames, short value) {
        short[] a = new short[frames * 2];
        for (int i = 0; i < a.length; i++) a[i] = value;
        return a;
    }

    @Test public void disabledIsRawPassthrough() {
        FakeSeparator sep = new FakeSeparator();
        VocalMixingProcessor p = new VocalMixingProcessor(sep);
        short[] d = block(10, (short) 1000);
        p.process(d, 10, 48000);
        assertEquals(1000, d[0]);
        assertEquals(1000, d[9]);
        assertEquals(0, sep.separateCalls);
    }

    @Test public void centreIsStrictBypassEvenWhenReady() {
        FakeSeparator sep = new FakeSeparator();
        VocalMixingProcessor p = new VocalMixingProcessor(sep);
        p.setEnabled(true);
        assertEquals(VocalProcessorState.WARMUP, p.state());
        short[] d = block(10, (short) 1000);
        p.process(d, 10, 48000);
        assertEquals(1000, d[0]);
        assertEquals(1000, d[5]);
        assertEquals(0, sep.separateCalls);
    }

    @Test public void notReadyFallsBackToRaw() {
        FakeSeparator sep = new FakeSeparator();
        VocalMixingProcessor p = new VocalMixingProcessor(sep);
        p.setEnabled(true);
        sep.ready = false;
        p.setSlider(0f);
        short[] d = block(10, (short) 1000);
        p.process(d, 10, 48000);
        assertEquals(1000, d[0]);
        assertEquals(VocalProcessorState.WARMUP, p.state());
    }

    @Test public void p1ReachesVocalsAfterRamp() {
        FakeSeparator sep = new FakeSeparator();
        VocalMixingProcessor p = new VocalMixingProcessor(sep);
        p.setEnabled(true);
        p.setSlider(1f);
        short[] d = null;
        for (int i = 0; i < 5; i++) {
            d = block(1000, (short) 1000);
            p.process(d, 1000, 48000);
        }
        assertEquals(500, d[0]);
        assertEquals(500, d[1999]);
        assertEquals(VocalProcessorState.PROCESSING, p.state());
        assertTrue(p.processedFrames() > 0);
    }

    @Test public void sliderChangeNeverReinitialisesModel() {
        FakeSeparator sep = new FakeSeparator();
        VocalMixingProcessor p = new VocalMixingProcessor(sep);
        p.setEnabled(true);
        p.setSlider(1f);
        block(1000, (short) 1000);
        short[] warm = block(1000, (short) 1000);
        p.process(warm, 1000, 48000);
        int before = sep.separateCalls;
        p.setSlider(0f);
        assertTrue(sep.isReady());
        assertEquals(before, sep.separateCalls);  // no call from the setter itself
    }

    @Test public void inferenceThrowFallsBackAndDegrades() {
        FakeSeparator sep = new FakeSeparator();
        VocalMixingProcessor p = new VocalMixingProcessor(sep);
        p.setEnabled(true);
        sep.ready = true;
        p.setSlider(1f);
        sep.throwOnSeparate = true;
        short[] d = block(10, (short) 777);
        p.process(d, 10, 48000);
        assertEquals(777, d[0]);
        assertEquals(VocalProcessorState.DEGRADED, p.state());
    }

    @Test public void unsupportedAbiNeverTouchesNative() {
        FakeSeparator sep = new FakeSeparator();
        sep.supported = false;
        VocalMixingProcessor p = new VocalMixingProcessor(sep);
        p.setEnabled(true);
        p.setSlider(0f);
        short[] d = block(10, (short) 333);
        p.process(d, 10, 48000);
        assertEquals(333, d[0]);
        assertEquals(VocalProcessorState.DEGRADED, p.state());
        assertEquals(0, sep.separateCalls);
    }
}
