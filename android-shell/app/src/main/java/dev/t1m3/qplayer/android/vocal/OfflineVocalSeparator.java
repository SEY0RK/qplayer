package dev.t1m3.qplayer.android.vocal;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import com.example.media.audio.VocalRemovalProcessor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Offline, whole-track vocal separation.
 *
 * <p>Real-time separation cannot escape its 0.7 s STFT window: the output always
 * describes audio from earlier than the decoder, which forces a choice between a
 * delayed stream, a jump on toggle, or a silent gap. Pre-separating the whole
 * track removes the problem entirely — playback then mixes against a cached
 * instrumental that is sample-aligned with the file, so seeking, toggling and
 * slider moves are all seamless.
 *
 * <p>Output: a raw PCM16 stereo 44.1 kHz file holding the INSTRUMENTAL, plus the
 * [startFrame, endFrame) range that was actually separated (the head/tail shorter
 * than one chunk cannot be covered). Vocals are derived at playback time as
 * {@code dry - instrumental}, so only one track is stored.
 */
public final class OfflineVocalSeparator {

    private static final String TAG = "VocalOffline";

    // Must match the native/STFT parameters used by the real-time path.
    public static final int SAMPLE_RATE = 44100;
    public static final int CHANNELS = 2;
    public static final int CHUNK_SAMPLES = 37888;
    public static final int INTERVAL_SAMPLES = 24576;
    public static final int EXTRACT_OFFSET = 6656;
    private static final int DIM_F = 2048;
    private static final int MODEL_CHANNELS = 4;
    private static final int TARGET_T = 32;
    private static final int N_FFT = 6144;
    private static final int HOP = 1024;
    private static final int BYTES_PER_FRAME = CHANNELS * 2;

    /** Progress callback; {@code fraction} is 0..1. */
    public interface Listener {
        /** @return false to cancel. */
        boolean onProgress(float fraction);
    }

    public static final class Result {
        public final File pcmFile;
        public final int sampleRate;
        public final int channels;
        public final long startFrame;
        public final long endFrame;
        public final long totalFrames;

        Result(File f, int sr, int ch, long s, long e, long total) {
            pcmFile = f; sampleRate = sr; channels = ch;
            startFrame = s; endFrame = e; totalFrames = total;
        }
    }

    private OfflineVocalSeparator() { }

    /**
     * Decode {@code source}, separate it, and write the instrumental to
     * {@code outFile}. Returns null when the source cannot be decoded.
     */
    public static Result separate(String source, String modelPath, File outFile,
                                  Listener listener) throws Exception {
        long stft = 0L, model = 0L;
        FileOutputStream out = null;
        try {
            stft = VocalRemovalProcessor.initStft(SAMPLE_RATE, N_FFT, HOP, DIM_F, CHANNELS);
            model = VocalRemovalProcessor.initMdxModel(modelPath, 4, DIM_F, MODEL_CHANNELS);
            if (stft == 0L || model == 0L) {
                Log.w(TAG, "native init failed stft=" + stft + " model=" + model);
                return null;
            }

            Decoded d = decodeAll(source);
            if (d == null || d.frames <= 0) {
                Log.w(TAG, "decode failed");
                return null;
            }
            final long total = d.frames;
            out = new FileOutputStream(outFile);
            out.getChannel().truncate(0);

            ByteBuffer modelIn = alloc(MODEL_CHANNELS * DIM_F * TARGET_T);
            ByteBuffer modelOut = alloc(MODEL_CHANNELS * DIM_F * TARGET_T);
            ByteBuffer stftReal = alloc(CHANNELS * DIM_F * TARGET_T);
            ByteBuffer stftImag = alloc(CHANNELS * DIM_F * TARGET_T);
            ByteBuffer istftReal = alloc(CHANNELS * DIM_F * TARGET_T);
            ByteBuffer istftImag = alloc(CHANNELS * DIM_F * TARGET_T);
            ByteBuffer pcmChunk = ByteBuffer.allocateDirect(CHUNK_SAMPLES * BYTES_PER_FRAME)
                    .order(ByteOrder.nativeOrder());
            byte[] dryChunk = new byte[CHUNK_SAMPLES * BYTES_PER_FRAME];
            byte[] interval = new byte[INTERVAL_SAMPLES * BYTES_PER_FRAME];

            long startFrame = -1, endFrame = -1;
            int sinceProgress = 0;
            for (long pos = 0; pos + CHUNK_SAMPLES <= total; pos += INTERVAL_SAMPLES) {
                readPcm(d, pos, CHUNK_SAMPLES, dryChunk);
                pcmChunk.clear();
                pcmChunk.put(dryChunk, 0, dryChunk.length).flip();

                int frames = VocalRemovalProcessor.computeStft(
                        stft, pcmChunk, CHUNK_SAMPLES, stftReal, stftImag, CHANNELS);
                if (frames <= 0) { Log.w(TAG, "stft returned " + frames); break; }
                if (!VocalRemovalProcessor.packMdx(stft, stftReal, stftImag, modelIn, frames)) break;
                if (!VocalRemovalProcessor.runMdx(model, modelIn, modelOut, frames)) break;
                if (!VocalRemovalProcessor.unpackMdx(
                        stft, modelOut, istftReal, istftImag, frames, CHANNELS)) break;

                // vocalOnly=false + mix=1 -> pure instrumental for this interval.
                int got = VocalRemovalProcessor.computeIstftInterval(
                        stft, istftReal, istftImag, dryChunk, interval, frames, CHANNELS,
                        EXTRACT_OFFSET, INTERVAL_SAMPLES, EXTRACT_OFFSET * BYTES_PER_FRAME,
                        1f, 1f, true, false, false);
                if (got != INTERVAL_SAMPLES) { Log.w(TAG, "istft got " + got); break; }

                long at = pos + EXTRACT_OFFSET;
                writeAt(out, at * BYTES_PER_FRAME, interval, interval.length);
                if (startFrame < 0) startFrame = at;
                endFrame = at + INTERVAL_SAMPLES;

                if (listener != null && ++sinceProgress >= 4) {
                    sinceProgress = 0;
                    if (!listener.onProgress(Math.min(1f, (float) endFrame / (float) total))) {
                        Log.i(TAG, "cancelled at frame " + endFrame);
                        return null;
                    }
                }
            }

            if (startFrame < 0) {
                Log.w(TAG, "nothing separated");
                return null;
            }
            Log.i(TAG, "done frames=" + total + " separated=[" + startFrame + "," + endFrame + ")");
            return new Result(outFile, SAMPLE_RATE, CHANNELS, startFrame, endFrame, total);
        } finally {
            if (out != null) { try { out.close(); } catch (Throwable ignored) { } }
            if (stft != 0L) VocalRemovalProcessor.releaseStft(stft);
            if (model != 0L) VocalRemovalProcessor.releaseMdx(model);
        }
    }

    private static ByteBuffer alloc(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder());
    }

    private static void writeAt(FileOutputStream out, long bytePos, byte[] data, int len)
            throws Exception {
        FileChannel ch = out.getChannel();
        ch.position(bytePos);
        ByteBuffer buf = ByteBuffer.wrap(data, 0, len);
        while (buf.hasRemaining()) ch.write(buf);
    }

    // ---- decoding ---------------------------------------------------------

    private static final class Decoded {
        short[] pcm;      // interleaved stereo at SAMPLE_RATE
        long frames;
    }

    /** Decode the whole file to interleaved stereo 44.1 kHz PCM16, resampling if needed. */
    private static Decoded decodeAll(String source) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(source);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) return null;
            ex.selectTrack(track);

            int srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            // First pass collects mono-ish stereo at the SOURCE rate into a temp array,
            // then we resample. Whole-track buffers are large but this runs in the
            // background and avoids a second decode pass.
            short[] raw = new short[1 << 20];
            int rawLen = 0;
            int outCh = Math.min(2, Math.max(1, srcCh));

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int idx = codec.dequeueInputBuffer(10_000);
                    if (idx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(idx);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(idx, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        if (ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            int samples = info.size / 2;
                            if (rawLen + samples > raw.length) {
                                int cap = Math.max(raw.length * 2, rawLen + samples);
                                short[] n = new short[cap];
                                System.arraycopy(raw, 0, n, 0, rawLen);
                                raw = n;
                            }
                            java.nio.ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                            int n = Math.min(samples, sb.remaining());
                            sb.get(raw, rawLen, n);
                            rawLen += n;
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outCh = Math.min(2, Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)));
                    }
                }
            }
            codec.stop();
            codec.release();

            // raw holds interleaved `outCh`-channel PCM at srcRate.
            long srcFrames = rawLen / outCh;
            if (srcFrames <= 0) return null;

            // Normalise to stereo.
            short[] stereo;
            if (outCh == 2) {
                stereo = raw;
            } else {
                stereo = new short[(int) srcFrames * 2];
                for (int i = 0; i < srcFrames; i++) {
                    short v = raw[i];
                    stereo[i * 2] = v;
                    stereo[i * 2 + 1] = v;
                }
            }

            // Resample to SAMPLE_RATE when required (linear; adequate for a
            // separation side chain and far cheaper than a polyphase stage here).
            short[] outPcm;
            long outFrames;
            if (srcRate == SAMPLE_RATE) {
                outPcm = stereo;
                outFrames = srcFrames;
            } else {
                outFrames = Math.max(1, Math.round(srcFrames * (double) SAMPLE_RATE / srcRate));
                outPcm = new short[(int) outFrames * 2];
                for (long i = 0; i < outFrames; i++) {
                    double sp = i * (double) srcRate / SAMPLE_RATE;
                    int i0 = (int) sp;
                    int i1 = Math.min(i0 + 1, (int) srcFrames - 1);
                    float frac = (float) (sp - i0);
                    for (int c = 0; c < 2; c++) {
                        float a = stereo[i0 * 2 + c];
                        float b = stereo[i1 * 2 + c];
                        outPcm[(int) i * 2 + c] = (short) (a + (b - a) * frac);
                    }
                }
            }

            Decoded d = new Decoded();
            d.pcm = outPcm;
            d.frames = outFrames;
            return d;
        } finally {
            try { ex.release(); } catch (Throwable ignored) { }
        }
    }

    private static void readPcm(Decoded d, long framePos, int frames, byte[] dst) {
        int n = frames * BYTES_PER_FRAME;
        long startSample = framePos * CHANNELS;
        int available = (int) Math.max(0, Math.min(frames, d.frames - framePos) * CHANNELS);
        for (int i = 0; i < available; i++) {
            short v = d.pcm[(int) startSample + i];
            dst[i * 2] = (byte) (v & 0xFF);
            dst[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        for (int i = available * 2; i < n; i++) dst[i] = 0;
    }
}
