package dev.t1m3.qplayer.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.t1m3.qplayer.playlist.LocalPlaylist;
import dev.t1m3.qplayer.playlist.PlaylistMerge;
import dev.t1m3.qplayer.playlist.PlaylistSyncSnapshot;
import dev.t1m3.qplayer.playlist.PlaylistTombstone;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire format for local-playlist device sync.
 *
 * <p>Deliberately a small, explicit envelope rather than shipping internal
 * objects: a receiver on another version must be able to reject a document it
 * cannot read instead of half-applying it. Every field here is durable JSON,
 * never a live player object.
 */
public final class PlaylistSyncProtocol {

    public static final String FORMAT = "qplayer.playlist.sync";
    public static final int FORMAT_VERSION = 1;
    public static final int DEFAULT_PORT = 39890;
    /** How many consecutive ports to try when the default is taken. */
    public static final int PORT_PROBES = 20;

    public static final String PATH_STATUS = "/status";
    public static final String PATH_PLAN = "/sync/plan";
    public static final String PATH_APPLY = "/sync/apply";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PlaylistSyncProtocol() {
    }

    /** Lightweight device identity returned by GET /status. */
    public static final class Status {
        public String format = FORMAT;
        public int formatVersion = FORMAT_VERSION;
        public String deviceId = "";
        public String deviceName = "";
        public String platform = "";
        public int port;
    }

    /** Summary sent before the payload so the receiver can ask the user. */
    public static final class Plan {
        public String format = FORMAT;
        public String deviceId = "";
        public String deviceName = "";
        public int playlistCount;
        public int trackCount;
        public int deletionCount;
        public long createdAtMs;
    }

    public static final class PlanResult {
        public boolean approved;
        public String reason = "";
    }

    /** The actual transfer. Carries the full snapshot so one POST is enough. */
    public static final class Payload {
        public String format = FORMAT;
        public String deviceId = "";
        public String deviceName = "";
        public List<LocalPlaylist> playlists = new ArrayList<LocalPlaylist>();
        public List<PlaylistTombstone> deletedPlaylists = new ArrayList<PlaylistTombstone>();
    }

    public static final class ApplyResult {
        public boolean ok;
        public int addedPlaylists;
        public int removedPlaylists;
        public int addedTracks;
        public int removedTracks;
        public String error = "";
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    /** Reject foreign documents and future versions before touching local data. */
    public static void validateFormat(String format, int version) throws SyncException {
        if (!FORMAT.equals(format)) throw new SyncException("not a QPlayer sync document");
        if (version > FORMAT_VERSION) {
            throw new SyncException("sender uses a newer sync format (" + version + ")");
        }
    }

    public static Plan planFor(String deviceId, String deviceName, PlaylistSyncSnapshot snapshot) {
        Plan plan = new Plan();
        plan.deviceId = deviceId;
        plan.deviceName = deviceName;
        for (LocalPlaylist playlist : snapshot.playlists) {
            plan.playlistCount++;
            plan.trackCount += playlist.tracks == null ? 0 : playlist.tracks.size();
        }
        plan.deletionCount = snapshot.deletedPlaylists.size();
        plan.createdAtMs = System.currentTimeMillis();
        return plan;
    }

    public static Payload payloadFor(String deviceId, String deviceName,
                                     PlaylistSyncSnapshot snapshot) {
        Payload payload = new Payload();
        payload.deviceId = deviceId;
        payload.deviceName = deviceName;
        payload.playlists.addAll(snapshot.playlists);
        payload.deletedPlaylists.addAll(snapshot.deletedPlaylists);
        return payload;
    }

    public static ApplyResult resultOf(PlaylistMerge.Result merge) {
        ApplyResult result = new ApplyResult();
        result.ok = true;
        result.addedPlaylists = merge.addedPlaylists;
        result.removedPlaylists = merge.removedPlaylists;
        result.addedTracks = merge.addedTracks;
        result.removedTracks = merge.removedTracks;
        return result;
    }
}
