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
    /** Queue depth guard: keep at most this many blocks ahead. */
    private static final int MAX_QUEUE = 6;

    // ---- configuration ---------------------------------------------------
    private volatile int delayFrames = 66_150;        // 1.5 s @ 44.1 kHz, refined at runtime
    private volatile boolean enabled;
    private volatile float slider = 0.5f;

    private final String modelPath;
    private volatile StreamingSeparator engine;

    // ---- dry ring (frames [outputCursor, inputCursor)) -------------------
    private short[] dryRing = new short[0];
    private int dryRead, dryWrite, drySize;           // in samples
    private int ringChannels = 2;

    // ---- separated blocks, ordered by startFrame -------------------------
    private final ArrayDeque<StreamingSeparator.Segment> queue = new ArrayDeque<>();

    // ---- cursors (frames, relative to the current seek base) -------------
    private long outputCursor;
    private long inputCursor;

    private boolean primed;
    private boolean usingSep;
    private int xfadeRemaining;

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
        if (!on) {
            // Keep the queue: re-enabling should resume at the current cursor rather
            // than re-priming. Using cursor-matched dry is what makes the toggle
            // seamless in both directions.
            usingSep = false;
            xfadeRemaining = XFADE_FRAMES;
        }
        Log.i(TAG, "enabled=" + on + " at outputCursor=" + outputCursor);
    }

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

        // 1. Append dry to the delay ring.
        writeDry(work, samples, ch);
        inputCursor += frames;

        // 2. Feed the separator and harvest finished blocks.
        StreamingSeparator eng = engine;
        if (eng != null && eng.isReady()) {
            eng.feed(work, 0, frames);
            while (queue.size() < MAX_QUEUE) {
                StreamingSeparator.Segment seg = eng.poll();
                if (seg == null) break;
                queue.addLast(seg);
            }
            // Drop blocks that are entirely behind the cursor.
            while (!queue.isEmpty() && queue.peekFirst().endFrame() <= outputCursor) {
                queue.pollFirst();
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

    private void emitFrames(ByteBuffer out, int frames, int ch) {
        int samples = frames * ch;
        if (mixScratch.length < samples) mixScratch = new short[samples];

        // Read the dry block at the OUTPUT cursor, not the newest input.
        readDry(mixScratch, samples);

        StreamingSeparator.Segment cover = findCovering(outputCursor, frames);
        boolean wantSep = enabled && cover != null;

        if (wantSep != usingSep) {
            // Short cross-fade either way; never a hard cut.
            usingSep = wantSep;
            xfadeRemaining = XFADE_FRAMES;
        }
        if (enabled && cover == null && primed && engine != null && engine.isReady()) {
            starvedBlocks++;
            if (starvedBlocks <= 20) {
                long first = queue.isEmpty() ? -1 : queue.peekFirst().startFrame;
                Log.w(TAG, "STARVED at out=" + outputCursor + " frames=" + frames
                        + " queue=" + queue.size() + " firstSeg=" + first
                        + " in=" + inputCursor + " lead=" + (inputCursor - outputCursor));
            }
        }

        if (usingSep && cover != null) {
            float s = slider;
            float dg = s <= 0.5f ? 1f : 2f * (1f - s);
            float vg = 2f * s - 1f;
            int segOff = (int) (outputCursor - cover.startFrame) * ch;
            for (int f = 0; f < frames; f++) {
                int base = f * ch;
                float w = 1f;
                if (xfadeRemaining > 0) {
                    w = 1f - (float) xfadeRemaining / (float) XFADE_FRAMES;
                    xfadeRemaining--;
                }
                for (int c = 0; c < ch; c++) {
                    short dry = mixScratch[base + c];
                    int so = segOff + f * ch + c;
                    short voc = so >= 0 && so < cover.pcm.length ? cover.pcm[so] : dry;
                    float mixed = dry * dg + voc * vg;
                    float blended = dry + (mixed - dry) * w;
                    int v = (int) blended;
                    if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                    if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                    mixScratch[base + c] = (short) v;
                }
            }
        } else if (xfadeRemaining > 0) {
            // Fading back to dry: keep the last separated block for the fade tail.
            xfadeRemaining = 0;
        }

        for (int i = 0; i < samples; i++) {
            short v = mixScratch[i];
            out.put((byte) (v & 0xFF)).put((byte) ((v >> 8) & 0xFF));
        }
    }

    private StreamingSeparator.Segment findCovering(long startFrame, int frames) {
        long end = startFrame + frames;
        for (StreamingSeparator.Segment s : queue) {
            if (s.startFrame <= startFrame && s.endFrame() >= end) return s;
            if (s.startFrame > startFrame) break;
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
        usingSep = false;
        xfadeRemaining = 0;
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
