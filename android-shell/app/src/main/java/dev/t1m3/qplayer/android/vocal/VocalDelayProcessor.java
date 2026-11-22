package dev.t1m3.qplayer.android.vocal;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * Fixed-delay playback stage: decouples the decoder from the speaker by a known
 * number of frames, and swaps between dry PCM and separated audio AT THE SAME
 * OUTPUT POSITION.
 *
 * <p>Why the delay exists: the separation window is 37888 frames (~859 ms) plus
 * inference time, so a separated block describing source frame N is only available
 * well after the decoder has produced N. Emitting it immediately would jump the
 * timeline. Holding dry PCM for {@code D} frames means that when a block for the
 * output cursor arrives it is already "old news", and the two can be swapped with
 * a short cross-fade instead of a jump.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code dryRing} holds source frames {@code [outputCursor, inputCursor)};</li>
 *   <li>output only begins once {@code inputCursor - outputCursor >= delayFrames};</li>
 *   <li>a separated block is used only if it fully covers the frames being emitted;</li>
 *   <li>turning separation off returns to the SAME cursor position in dryRing, never
 *       to the decoder's newest input (that is what caused the audible jump).</li>
 * </ul>
 */
public final class VocalDelayProcessor implements AudioProcessor {

    private static final String TAG = "VocalDelay";

    /** Cross-fade length when swapping dry <-> separated. */
    private static final int XFADE_FRAMES = 882;      // 20 ms @ 44.1 kHz
    /** Queue depth guard: 16 blocks ≈ 8.8 s of headroom against short excursions. */
    private static final int MAX_QUEUE = 16;
    /** Applied at the top of queueInput (audio thread) to avoid cross-thread ring edits. */
    private volatile long pendingSkipTo = -1;

    // ---- configuration ---------------------------------------------------
    private volatile int delayFrames = 66_150;        // 1.5 s @ 44.1 kHz, refined at runtime
    private volatile boolean enabled;
    private volatile float slider = 0.5f;
    /**
     * Delay becomes permanent once separation has been switched on: tearing it down
     * on every toggle would reintroduce the jump the delay exists to prevent.
     * Until then the processor is a zero-latency pass-through, so listeners who
     * never touch separation are unaffected.
     */
    private volatile boolean engaged;

    // ---- per-device delay calibration -------------------------------------
    /** Number of timed passes used to size the delay on first use. */
    private static final int CALIBRATION_RUNS = 3;
    /** Fixed part: the STFT window the separator needs before it can emit a block. */
    private static final int ALGO_WINDOW_MS =
            StreamingSeparator.CHUNK_FRAMES * 1000 / StreamingSeparator.SAMPLE_RATE;
    /** Provisional delay used before this device has been measured. */
    private static final int DEFAULT_DELAY_MS = 1500;
    private volatile boolean calibrating;
    private volatile int presetDelayMs;
    private volatile java.util.function.IntConsumer onCalibrated;

    private final String modelPath;
    private volatile StreamingSeparator engine;

    // ---- dry ring (frames [outputCursor, inputCursor)) -------------------
    private short[] dryRing = new short[0];
    private int dryRead, dryWrite, drySize;           // in samples
    private int ringChannels = 2;

    // ---- separated blocks, ordered by startFrame -------------------------
    private final ArrayDeque<StreamingSeparator.Segment> queue = new ArrayDeque<>();

    // ---- cursors (frames, relative to the current seek base) -------------
    /** Written on the audio thread, read from the UI thread when re-aligning. */
    private volatile long outputCursor;
    private long inputCursor;

    private boolean primed;
    /** 0 = emitting dry, 1 = emitting separated; ramps over XFADE_FRAMES. */
    private float sepBlend;

    // ---- diagnostics ------------------------------------------------------
    private long starvedBlocks;
    private long emittedSilenceFrames;
    private final StringBuilder pendingDiag = new StringBuilder();

    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private AudioFormat outputFormat = AudioFormat.NOT_SET;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded;
    private short[] work = new short[0];
    private short[] mixScratch = new short[0];

    public VocalDelayProcessor(String modelPath) {
        this.modelPath = modelPath;
    }

    // ---- configuration API ------------------------------------------------

    public void setEnabled(boolean on) {
        if (this.enabled == on) return;
        this.enabled = on;
        if (on && !engaged) {
            engaged = true;
            if (presetDelayMs > 0) {
                // A previous run already measured this device: use it directly.
                setDelayMs(presetDelayMs);
                calibrating = false;
                Log.i(TAG, "using calibrated delay " + presetDelayMs + "ms");
            } else {
                // Never measured. Start from the conservative default so the window
                // can begin filling immediately, and measure in the background; the
                // result is persisted for the NEXT session because changing the delay
                // mid-playback would shift the output cursor.
                setDelayMs(DEFAULT_DELAY_MS);
                startCalibration();
            }
        } else if (on) {
            // Re-enabling while the delay is already running. History piled up in the
            // engine unconsumed, so ask it to jump to the window that covers the
            // CURRENT output cursor. Without this the catch-up runs 4+ inferences on
            // the audio thread (~1.4 s) — the stall users hear when toggling back on.
            long needBase = outputCursor - StreamingSeparator.EXTRACT_OFFSET
                    - StreamingSeparator.INTERVAL_FRAMES / 2;
            if (needBase > 0) pendingSkipTo = needBase;
            Log.i(TAG, "re-enable: align engine to out=" + outputCursor
                    + " skipTo=" + Math.max(0, needBase));
        }
        // No buffer flush either way. Dry history is kept, the separated queue is
        // kept, and the blend simply ramps to the other side at the CURRENT cursor
        // — which is what makes the toggle seamless in both directions.
        Log.i(TAG, "enabled=" + on + " at outputCursor=" + outputCursor
                + " blend=" + sepBlend + " engaged=" + engaged);
    }

    /**
     * Delay measured on this device by an earlier session; 0 = unmeasured.
     *
     * <p>When a measurement exists the delay is engaged from the FIRST FRAME of
     * playback, not when the switch is flipped. That is what makes the toggle
     * instantaneous: the output cursor is already running behind the decoder, so
     * separated audio for the current position is available (or arrives within one
     * window) and the cross-fade has something to fade to. Introducing the delay at
     * toggle time would instead force a fresh buffer fill right when the user is
     * listening for the effect.
     */
    public void setPresetDelayMs(int ms) {
        presetDelayMs = Math.max(0, ms);
        if (presetDelayMs > 0) {
            engaged = true;
            delayFrames = Math.max(4096, presetDelayMs * StreamingSeparator.SAMPLE_RATE / 1000);
            Log.i(TAG, "delay engaged from playback start: " + presetDelayMs + "ms");
        }
    }

    /** Called once the delay has been measured, to persist it. */
    public void setOnCalibrated(java.util.function.IntConsumer cb) { onCalibrated = cb; }

    public boolean isCalibrating() { return calibrating; }

    /**
     * Derive the delay from measured inference cost.
     *
     * <p>{@code D = algoWindow + maxInference + margin}, rounded up to a 250 ms step.
     * Using the MAX of the probe runs (not the mean) is deliberate: undersizing the
     * delay is what produces starvation, and starvation leaks the other stem — far
     * more audible than a slightly longer seek wait.
     */
    /**
     * Measure inference cost on a PRIVATE engine, on a background thread.
     *
     * <p>Calibration originally ran inside {@code queueInput}, which blocked the audio
     * thread for about a second and drained the hardware buffer — audible as a stutter
     * followed by silence right when the switch was flipped. Inference cost does not
     * depend on the audio CONTENT, so a silent chunk measures just as accurately as
     * music, and a private engine leaves the playback engine's buffers uncontended.
     */
    private void startCalibration() {
        if (calibrating) return;
        calibrating = true;
        Thread t = new Thread(() -> {
            StreamingSeparator probe = null;
            long maxNanos = 0;
            try {
                probe = new StreamingSeparator(modelPath, 2);
                if (!probe.isReady()) {
                    Log.w(TAG, "calibration probe not ready");
                    calibrating = false;
                    return;
                }
                short[] silence = new short[StreamingSeparator.CHUNK_FRAMES * 2];
                probe.feed(silence, 0, StreamingSeparator.CHUNK_FRAMES);
                for (int i = 0; i < CALIBRATION_RUNS; i++) {
                    long dt = probe.calibrateOnce();
                    if (dt > 0) maxNanos = Math.max(maxNanos, dt);
                }
                Log.i(TAG, "calibration probe max=" + (maxNanos / 1_000_000) + "ms");
                applyCalibration(maxNanos);
            } catch (Throwable e) {
                Log.w(TAG, "calibration failed: " + e);
                calibrating = false;
            } finally {
                if (probe != null) probe.close();
            }
        }, "qplayer-vocal-calib");
        t.setDaemon(true);
        t.start();
    }

    private void applyCalibration(long maxNanos) {
        if (maxNanos <= 0) { calibrating = false; return; }
        int inferMs = (int) (maxNanos / 1_000_000L);
        int margin = Math.max(150, inferMs / 6);
        int raw = ALGO_WINDOW_MS + inferMs + margin;
        int stepped = ((raw + 249) / 250) * 250;
        int clamped = Math.max(1000, Math.min(4000, stepped));
        calibrating = false;
        int current = delayMs();
        if (clamped > current) {
            // Under-provisioned: raise NOW. Too small a delay starves the queue and
            // starves leak the other stem, which is far worse than a brief pause
            // while the larger window fills.
            setDelayMs(clamped);
            Log.i(TAG, "CALIBRATED inferMaxMs=" + inferMs + " -> " + clamped
                    + "ms raised now (was " + current + "ms)");
        } else if (!primed) {
            // Not emitting yet, so shrinking the window cannot move the output cursor —
            // it only shortens how long the user waits. Take the win.
            setDelayMs(clamped);
            Log.i(TAG, "CALIBRATED inferMaxMs=" + inferMs + " -> " + clamped
                    + "ms applied before priming (was " + current + "ms)");
        } else {
            Log.i(TAG, "CALIBRATED inferMaxMs=" + inferMs + " algoMs=" + ALGO_WINDOW_MS
                    + " raw=" + raw + " -> " + clamped + "ms (applies next session, current "
                    + current + "ms)");
        }
        java.util.function.IntConsumer cb = onCalibrated;
        if (cb != null) cb.accept(clamped);
    }

    /** Latency the UI must subtract from the player position; 0 before first use. */
    public int activeDelayMs() { return engaged ? delayMs() : 0; }

    public boolean isEngaged() { return engaged; }

    public void setSlider(float p) {
        if (Float.isNaN(p)) p = 0.5f;
        this.slider = Math.max(0f, Math.min(1f, p));
    }

    /** Fixed delay in milliseconds; callers size this from measured inference cost. */
    public void setDelayMs(int ms) {
        int frames = Math.max(4096, ms * StreamingSeparator.SAMPLE_RATE / 1000);
        if (frames != delayFrames) {
            delayFrames = frames;
            Log.i(TAG, "delay=" + ms + "ms (" + frames + " frames)");
        }
    }

    public int delayMs() { return delayFrames * 1000 / StreamingSeparator.SAMPLE_RATE; }

    /** False while the delay window is still filling — the UI should show buffering. */
    public boolean isPrimed() { return primed; }

    public long outputPositionFrames() { return outputCursor; }
    public long queuedBlocks() { return queue.size(); }
    public long starvedBlocks() { return starvedBlocks; }

    /** Health snapshot for logs/UI. */
    public String diagnostics() {
        StreamingSeparator eng = engine;
        return "primed=" + primed + " queue=" + queue.size()
                + " out=" + outputCursor + " in=" + inputCursor
                + " lead=" + (inputCursor - outputCursor)
                + " starved=" + starvedBlocks + " silence=" + emittedSilenceFrames
                + " rtFactor=" + (eng == null ? "n/a" : String.format("%.2f", eng.lastRealtimeFactor()))
                + " inferAvgMs=" + (eng == null ? -1 : eng.averageInferenceMs());
    }

    // ---- AudioProcessor ---------------------------------------------------

    @Override
    public AudioFormat configure(AudioFormat in) throws UnhandledAudioFormatException {
        if (in.encoding != C.ENCODING_PCM_16BIT) {
            inputFormat = AudioFormat.NOT_SET;
            outputFormat = AudioFormat.NOT_SET;
            return AudioFormat.NOT_SET;
        }
        inputFormat = in;
        outputFormat = in;
        ringChannels = in.channelCount;
        // The separator only defines its buffers once the channel count is known.
        if (engine == null) {
            try {
                engine = new StreamingSeparator(modelPath, in.channelCount);
            } catch (Throwable t) {
                Log.w(TAG, "separator init failed: " + t);
            }
        }
        // Delay window + one full window of slack so a late block never underruns.
        int capFrames = delayFrames + StreamingSeparator.CHUNK_FRAMES * 2;
        dryRing = new short[capFrames * ringChannels];
        dryRead = dryWrite = drySize = 0;
        int max = in.sampleRate * 2 * in.channelCount;
        if (work.length < max) work = new short[max];
        if (mixScratch.length < max) mixScratch = new short[max];
        return outputFormat;
    }

    @Override public boolean isActive() { return outputFormat != AudioFormat.NOT_SET; }

    @Override
    public void queueInput(ByteBuffer in) {
        int bytes = in.remaining();
        if (bytes <= 0) { outputBuffer = EMPTY_BUFFER; return; }
        int ch = inputFormat.channelCount;
        int bytesPerFrame = ch * 2;
        int frames = bytes / bytesPerFrame;
        int samples = frames * ch;

        if (samples > work.length) work = new short[samples];
        for (int i = 0; i < samples; i++) {
            int lo = in.get() & 0xFF;
            int hi = in.get() & 0xFF;
            work[i] = (short) (lo | (hi << 8));
        }
        if (in.hasRemaining()) in.position(in.limit());

        // Zero-latency path: separation has never been used this session, so there is
        // nothing to align and no reason to delay or run inference.
        if (!engaged) {
            ByteBuffer direct = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            for (int i = 0; i < samples; i++) {
                short v = work[i];
                direct.put((byte) (v & 0xFF)).put((byte) ((v >> 8) & 0xFF));
            }
            direct.flip();
            outputBuffer = direct;
            outputCursor += frames;
            inputCursor += frames;
            return;
        }

        // 1. Append dry to the delay ring.
        writeDry(work, samples, ch);
        inputCursor += frames;

        // 2. Feed the separator and harvest finished blocks.
        StreamingSeparator eng = engine;
        if (eng != null && eng.isReady()) {
            // Re-align before feeding: while separation was off, history piled up
            // unconsumed, and draining it all here would run several inferences on the
            // audio thread (audible stall). Jumping the base means one window suffices.
            long skip = pendingSkipTo;
            if (skip >= 0) {
                eng.skipTo(skip);
                pendingSkipTo = -1;
            }
            // Keep the ring current even while separation is OFF. On re-enable this
            // history is what lets the queue catch up to the output cursor at once
            // instead of re-priming for another full window — the difference between
            // an instant toggle and a second of silence.
            eng.feed(work, 0, frames);

            if (enabled) {
                // Calibration runs on its own thread/engine (startCalibration) so the
                // audio path never blocks here.
                //
                // Drain COMPLETELY, every block. Capping the count (an earlier attempt)
                // made the engine fall permanently behind: any block that needed more
                // than the cap left a residue, the residue grew, the input ring
                // overran, positions developed holes and playback stuttered forever.
                // Steady state only ever has one window ready, so this does not
                // lengthen the average call — it just refuses to fall behind.
                StreamingSeparator.Segment seg;
                while ((seg = eng.poll()) != null) {
                    if (seg.endFrame() <= outputCursor) continue;   // already played past
                    while (queue.size() >= MAX_QUEUE) queue.pollFirst();
                    queue.addLast(seg);
                }
            }
        }

        // 3. Decide what to emit right now.
        ByteBuffer out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        if (!primed) {
            if (inputCursor >= delayFrames) {
                primed = true;
                Log.i(TAG, "PRIMED in=" + inputCursor + " delay=" + delayFrames);
            } else {
                // Fill the delay window. Emit silence; the caller shows buffering.
                for (int i = 0; i < samples; i++) {
                    out.put((byte) 0).put((byte) 0);
                }
                out.flip();
                outputBuffer = out;
                emittedSilenceFrames += frames;
                return;
            }
        }

        emitFrames(out, frames, ch);
        out.flip();
        outputBuffer = out;
        outputCursor += frames;

        if ((outputCursor % (StreamingSeparator.SAMPLE_RATE * 5L)) < frames) {
            Log.i(TAG, diagnostics());
        }
    }

    /**
     * Emit one block by walking it in SEGMENT SLICES.
     *
     * <p>A separated block is 24576 frames while an output block is ~1152, so their
     * boundaries rarely line up. Requiring a single block to cover the whole output
     * window (the previous approach) failed on every boundary crossing and dropped
     * back to dry PCM — audibly leaking the OTHER stem in bursts ("在伴奏档听到人声").
     * Here each frame takes its own segment and the blend factor ramps smoothly.
     */
    private void emitFrames(ByteBuffer out, int frames, int ch) {
        int samples = frames * ch;
        if (mixScratch.length < samples) mixScratch = new short[samples];

        // Dry PCM at the OUTPUT cursor is the base and the fallback.
        readDry(mixScratch, samples);

        StreamingSeparator eng = engine;
        boolean sepPossible = enabled && eng != null && eng.isReady();

        float s = slider;
        float dg = s <= 0.5f ? 1f : 2f * (1f - s);
        float vg = 2f * s - 1f;
        float step = 1f / XFADE_FRAMES;

        StreamingSeparator.Segment cur = null;
        int starvedFrames = 0;
        long starvedAt = -1;

        for (int f = 0; f < frames; f++) {
            long pos = outputCursor + f;
            if (cur == null || pos < cur.startFrame || pos >= cur.endFrame()) {
                cur = findSegmentAt(pos);
            }
            boolean haveSep = sepPossible && cur != null;
            if (sepPossible && !haveSep) {
                if (starvedAt < 0) starvedAt = pos;
                starvedFrames++;
            }

            float target = haveSep ? 1f : 0f;
            if (sepBlend < target) sepBlend = Math.min(target, sepBlend + step);
            else if (sepBlend > target) sepBlend = Math.max(target, sepBlend - step);

            // Starved: no separated data covers this frame, so emit dry and let the
            // blend decay on its own. Substituting dry for `voc` would NOT work —
            // dg+vg equals 2s below centre, which fades the audio out entirely.
            if (sepBlend <= 0f || !haveSep) continue;

            int base = f * ch;
            int segOff = (int) (pos - cur.startFrame) * ch;
            for (int c = 0; c < ch; c++) {
                short dry = mixScratch[base + c];
                int so = segOff + c;
                short voc = so >= 0 && so < cur.pcm.length ? cur.pcm[so] : dry;
                float mixed = dry * dg + voc * vg;
                float blended = dry + (mixed - dry) * sepBlend;
                int v = (int) blended;
                if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                mixScratch[base + c] = (short) v;
            }
        }

        if (sepPossible && starvedFrames > 0) {
            starvedBlocks++;
            if (starvedBlocks <= 30) {
                long first = queue.isEmpty() ? -1 : queue.peekFirst().startFrame;
                Log.w(TAG, "STARVED out=" + outputCursor + " starvedFrames=" + starvedFrames
                        + "/" + frames + " firstAt=" + starvedAt
                        + " queue=" + queue.size() + " firstSeg=" + first
                        + " in=" + inputCursor + " lead=" + (inputCursor - outputCursor));
            }
        }

        for (int i = 0; i < samples; i++) {
            short v = mixScratch[i];
            out.put((byte) (v & 0xFF)).put((byte) ((v >> 8) & 0xFF));
        }
    }

    /** First queued block whose range contains {@code pos}, or null. */
    private StreamingSeparator.Segment findSegmentAt(long pos) {
        for (StreamingSeparator.Segment s : queue) {
            if (pos >= s.startFrame && pos < s.endFrame()) return s;
            if (s.startFrame > pos) break;
        }
        return null;
    }

    // ---- dry ring ---------------------------------------------------------

    private void writeDry(short[] src, int samples, int ch) {
        int cap = dryRing.length;
        if (cap == 0) return;
        int free = cap - drySize;
        if (samples > free) {
            int drop = samples - free;
            drop -= drop % ch;
            dryRead = (dryRead + drop) % cap;
            drySize -= drop;
            Log.w(TAG, "dry ring overrun, dropped " + (drop / ch) + " frames");
        }
        int contiguous = Math.min(samples, cap - dryWrite);
        System.arraycopy(src, 0, dryRing, dryWrite, contiguous);
        if (contiguous < samples) {
            System.arraycopy(src, contiguous, dryRing, 0, samples - contiguous);
        }
        dryWrite = (dryWrite + samples) % cap;
        drySize += samples;
    }

    private void readDry(short[] dst, int samples) {
        int cap = dryRing.length;
        int n = Math.min(samples, drySize);
        int contiguous = Math.min(n, cap - dryRead);
        System.arraycopy(dryRing, dryRead, dst, 0, contiguous);
        if (contiguous < n) System.arraycopy(dryRing, 0, dst, contiguous, n - contiguous);
        for (int i = n; i < samples; i++) dst[i] = 0;
        int advance = Math.min(samples, drySize);
        dryRead = (dryRead + advance) % cap;
        drySize -= advance;
    }

    // ---- AudioProcessor tail ----------------------------------------------

    @Override public void queueEndOfStream() { inputEnded = true; }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer out = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return out;
    }

    @Override public boolean isEnded() { return inputEnded && !outputBuffer.hasRemaining(); }

    @Override
    public void flush() {
        // Seek: drop everything tied to the old position, re-base the cursors, and
        // re-fill the delay window. No separated data may survive across a seek.
        inputEnded = false;
        outputBuffer = EMPTY_BUFFER;
        dryRead = dryWrite = drySize = 0;
        queue.clear();
        outputCursor = 0;
        inputCursor = 0;
        primed = false;
        sepBlend = 0f;
        StreamingSeparator eng = engine;
        if (eng != null) eng.reset();
        Log.i(TAG, "FLUSH");
    }

    @Override
    public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
        outputFormat = AudioFormat.NOT_SET;
    }
}
