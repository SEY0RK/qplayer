package dev.t1m3.qplayer.android.vocal;

import android.util.Log;

import java.io.File;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the pre-separated instrumental cache.
 *
 * <p>Keyed by source (query string stripped so expiring CDN tokens still hit the
 * same entry). One background worker, one job at a time — separation is CPU heavy
 * and there is no benefit in running several. Results are published through
 * {@link Callback} on a worker thread; callers marshal to their own thread.
 */
public final class VocalCacheStore {

    private static final String TAG = "VocalCacheStore";
    private static final int VERSION = 1;

    public static final class Track {
        public final File pcm;
        public final long startFrame;
        public final long endFrame;
        public final int channels;
        public final int sampleRate;

        Track(File pcm, long s, long e, int ch, int sr) {
            this.pcm = pcm; startFrame = s; endFrame = e;
            channels = ch; sampleRate = sr;
        }
    }

    public interface Callback {
        /** Progress 0..1 while a build runs. */
        void onProgress(String key, float fraction);
        /** Called once when the track becomes usable. */
        void onReady(String key, Track track);
        void onFailed(String key, String reason);
    }

    private final File dir;
    private final String modelPath;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-vocal-offline");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public VocalCacheStore(File filesDir, String modelPath) {
        this.dir = new File(filesDir, "vocal-cache");
        this.modelPath = modelPath;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create cache dir " + dir);
        }
    }

    public static String keyOf(String source) {
        if (source == null) return "";
        String s = source;
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        if (s.startsWith("file://")) s = s.substring(7);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((VERSION + "|" + s).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private File pcmFile(String key) { return new File(dir, key + ".inst.pcm"); }
    private File metaFile(String key) { return new File(dir, key + ".meta"); }

    public boolean isFailed(String key) { return failed.contains(key); }

    /** Load an existing entry, or null. */
    public Track lookup(String source) {
        String key = keyOf(source);
        File meta = metaFile(key);
        File pcm = pcmFile(key);
        if (!meta.isFile() || !pcm.isFile() || pcm.length() == 0) return null;
        try {
            String[] parts = new String(java.nio.file.Files.readAllBytes(meta.toPath()),
                    "UTF-8").trim().split(",");
            if (parts.length < 4) return null;
            long start = Long.parseLong(parts[0]);
            long end = Long.parseLong(parts[1]);
            int ch = Integer.parseInt(parts[2]);
            int sr = Integer.parseInt(parts[3]);
            return new Track(pcm, start, end, ch, sr);
        } catch (Throwable t) {
            Log.w(TAG, "bad meta for " + key + ": " + t);
            return null;
        }
    }

    /**
     * Build the cache for {@code source} in the background. Safe to call repeatedly;
     * duplicate/parallel requests for the same key are ignored.
     */
    public void requestBuild(final String source, final Callback cb) {
        if (source == null || source.isEmpty()) return;
        // Only local files can be decoded offline; remote streams are handled once
        // their download completes (caller passes the finished cache path).
        if (source.startsWith("http://") || source.startsWith("https://")) return;
        final String key = keyOf(source);
        if (lookup(source) != null) return;
        if (failed.contains(key)) return;
        if (!inFlight.add(key)) return;

        worker.execute(() -> {
            File tmp = new File(dir, key + ".inst.tmp");
            try {
                Log.i(TAG, "building " + key);
                String path = source.startsWith("file://") ? source.substring(7) : source;
                OfflineVocalSeparator.Result r = OfflineVocalSeparator.separate(
                        path, modelPath, tmp,
                        fraction -> {
                            cb.onProgress(key, fraction);
                            return !Thread.currentThread().isInterrupted();
                        });
                if (r == null) {
                    failed.add(key);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    cb.onFailed(key, "separate failed");
                    return;
                }
                File pcm = pcmFile(key);
                if (pcm.exists()) //noinspection ResultOfMethodCallIgnored
                    pcm.delete();
                if (!tmp.renameTo(pcm)) {
                    failed.add(key);
                    cb.onFailed(key, "rename failed");
                    return;
                }
                String meta = r.startFrame + "," + r.endFrame + ","
                        + r.channels + "," + r.sampleRate;
                java.nio.file.Files.write(metaFile(key).toPath(),
                        meta.getBytes("UTF-8"));
                cb.onReady(key, new Track(pcm, r.startFrame, r.endFrame,
                        r.channels, r.sampleRate));
                Log.i(TAG, "ready " + key);
            } catch (Throwable t) {
                failed.add(key);
                Log.w(TAG, "build failed " + key + ": " + t);
                cb.onFailed(key, String.valueOf(t));
            } finally {
                inFlight.remove(key);
            }
        });
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}
