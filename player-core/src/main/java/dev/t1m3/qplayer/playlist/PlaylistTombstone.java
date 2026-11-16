package dev.t1m3.qplayer.playlist;

/**
 * A whole local playlist removed on this device.
 *
 * <p>Like {@link TrackTombstone}, this exists so a sender's deletion can be
 * propagated to the receiver instead of the receiver merging the playlist back
 * from its own older state.
 */
public final class PlaylistTombstone {

    public String id = "";
    public long deletedAtMs;

    public PlaylistTombstone() {
    }

    public PlaylistTombstone(String id, long deletedAtMs) {
        this.id = id == null ? "" : id;
        this.deletedAtMs = deletedAtMs;
    }
}
