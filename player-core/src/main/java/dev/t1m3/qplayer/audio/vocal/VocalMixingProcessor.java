package dev.t1m3.qplayer.audio.vocal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Platform-neutral, real-time vocal/instrumental mixing stage.
 *
 * <p>Responsibilities (plan section 4):
 * <ul>
 *   <li>enable / disable vocal separation</li>
 *   <li>set the continuous slider position (gain-only, never re-inits the model)</li>
 *   <li>accept raw PCM, emit processed PCM</li>
 *   <li>expose ready / state queries</li>
 *   <li>release native resources</li>
 * </ul>
 *
 * <p>Two hard rules from the plan:
 * <ol>
 *   <li>At exactly 50% the ORIGINAL PCM is copied straight to output — separation
 *       results are never used to synthesize the centre. This is implemented as a
 *       genuine bypass with zero calls into {@link VocalSeparator}.</li>
 *   <li>Slider changes only retarget the gain ramps. The separator model is never
 *       recreated or flushed on a position change.</li>
 * </ol>
 *
 * <p>Gain smoothing: a fixed 20 ms linear ramp (at 48 kHz) removes zipper noise
 * and click sounds when the user drags quickly. It is deliberately simple — no
 * loudness analysis or dynamic compensation.
 *
 * <p>Not thread-hot-path synchronized: one instance belongs to one audio thread,
 * while setters are called from the UI thread via volatile fields / atomics.
 */
public final class VocalMixingProcessor {

    private static final int RAMP_FRAMES = 960; // ~20 ms @ 48 kHz

    private final VocalSeparator separator;

    private volatile boolean enabled = false;
    private volatile float slider = VocalMixMath.CENTRE;
    private volatile VocalProcessorState state = VocalProcessorState.DISABLED;
    private final AtomicLong processedFrames = new AtomicLong();
    private final AtomicLong fallbackFrames = new AtomicLong();

    // Smoothed gains, advanced per processing block on the audio thread only.
    private float smoothDry = 1f;
    private float smoothVocal = 0f;
    private short[] vocalScratch = new short[0];
    private int channels = 2;

    public VocalMixingProcessor(VocalSeparator separator) {
        if (separator == null) throw new IllegalArgumentException("separator");
        this.separator = separator;
    }

    // --- control surface ---------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public float slider() { return slider; }
    public VocalProcessorState state() { return state; }
    public boolean isReady() { return separator.isReady(); }
    public boolean isSupported() { return separator.isSupported(); }
    public long processedFrames() { return processedFrames.get(); }
    public long fallbackFrames() { return fallbackFrames.get(); }

    /** Enable/disable. Enabling prewarms the model asynchronously but does NOT block. */
    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (value) {
            if (!separator.isSupported()) {
                state = VocalProcessorState.DEGRADED;
                return;
            }
            separator.prewarm();
            state = VocalProcessorState.WARMUP;
        } else {
            separator.flush();
            state = VocalProcessorState.DISABLED;
            resetRamp();
        }
    }

    /** Slider position in [0,1]. Gain-only — never touches the separator. */
    public void setSlider(float p) {
        slider = VocalMixMath.clamp(p);
        // Intentionally no flush(), no prewarm(), no state change.
    }

    /** Prepare the separator for a concrete PCM format. Called once by the host. */
    public boolean configureSeparator(int sampleRate, int channels) {
        setChannels(channels);
        return separator.configure(sampleRate, channels);
    }

    public void flush() {
        separator.flush();
        resetRamp();
    }

    public void close() {
        setEnabled(false);
        separator.close();
        state = VocalProcessorState.RELEASED;
    }

    // --- audio path --------------------------------------------------------

    /**
     * Process one interleaved PCM16 block in place.
     *
     * @param pcm   interleaved int16 samples (modified in place)
     * @param frames number of FRAMES (each frame has {@code channels} samples)
     * @param sampleRate used only for the gain ramp length
     */
    public void process(short[] pcm, int frames, int sampleRate) {
        if (frames <= 0) return;
        int n = frames * channels;

        boolean bypass = !enabled
                || VocalMixMath.isPassthrough(slider)
                || state == VocalProcessorState.DEGRADED
                || state == VocalProcessorState.RELEASED
                || !separator.isSupported();

        if (bypass) {
            // True raw-PCM passthrough: nothing reads or writes pcm[] here.
            fallbackFrames.addAndGet(frames);
            return;
        }

        if (!separator.isReady()) {
            // Model still loading — keep audible with dry audio, mark the state so
            // the UI can tell the user separation is not in effect yet.
            state = VocalProcessorState.WARMUP;
            fallbackFrames.addAndGet(frames);
            return;
        }

        if (vocalScratch.length < n) vocalScratch = new short[n];
        boolean ok;
        try {
            ok = separator.separate(pcm, 0, vocalScratch, 0, frames, channels);
        } catch (Throwable t) {
            // Inference failure must never kill playback.
            ok = false;
        }
        if (!ok) {
            // A stream separator needs to fill its latency window before it can
            // emit the first aligned block. That is NOT a failure: keep feeding it,
            // pass dry PCM through, and stay in WARMUP so we recover by itself.
            if (separator.isPending()) {
                state = VocalProcessorState.WARMUP;
                fallbackFrames.addAndGet(frames);
                return;
            }
            state = VocalProcessorState.DEGRADED;
            fallbackFrames.addAndGet(frames);
            return;
        }

        state = VocalProcessorState.PROCESSING;
        advanceRamp(frames, sampleRate);
        float dg = smoothDry;
        float vg = smoothVocal;
        if (channels == 1) {
            for (int i = 0; i < frames; i++) {
                pcm[i] = VocalMixMath.mixMono(pcm[i], vocalScratch[i], dg, vg);
            }
        } else {
            for (int f = 0; f < frames; f++) {
                int idx = f * 2;
                float l = pcm[idx] * dg + vocalScratch[idx] * vg;
                float r = pcm[idx + 1] * dg + vocalScratch[idx + 1] * vg;
                pcm[idx] = (short) VocalMixMath.clampInt16(l);
                pcm[idx + 1] = (short) VocalMixMath.clampInt16(r);
            }
        }
        processedFrames.addAndGet(frames);
    }

    public void setChannels(int value) {
        if (value != 1 && value != 2) throw new IllegalArgumentException("channels must be 1 or 2");
        if (this.channels != value) {
            this.channels = value;
            vocalScratch = new short[0];
            resetRamp();
        }
    }

    // --- ramp --------------------------------------------------------------

    private void resetRamp() {
        smoothDry = VocalMixMath.dryGain(slider);
        smoothVocal = VocalMixMath.vocalGain(slider);
    }

    private void advanceRamp(int blockFrames, int sampleRate) {
        float targetDry = VocalMixMath.dryGain(slider);
        float targetVocal = VocalMixMath.vocalGain(slider);
        int rampFrames = Math.max(1, RAMP_FRAMES * sampleRate / 48000);
        // Advance proportionally to how many frames this block carries. A block
        // longer than the ramp reaches the target in one step; a short block creeps.
        float step = (float) blockFrames / (float) rampFrames;
        smoothDry = approach(smoothDry, targetDry, step);
        smoothVocal = approach(smoothVocal, targetVocal, step);
    }

    private static float approach(float current, float target, float step) {
        float d = target - current;
        if (Math.abs(d) <= step) return target;
        return current + Math.signum(d) * step;
    }
}
