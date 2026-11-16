package dev.t1m3.qplayer.playlist;

/**
 * One song removed from a local playlist.
 *
 * <p>A removal must outlive the row that was removed: without a record of it,
 * the next device sync would merge the song straight back in. The timestamp is
 * compared against {@link PlaylistTrack#addedAtMs} so a song added again after
 * the removal wins over an older tombstone.
 */
public final class TrackTombstone {

    public String mediaId = "";
    public long removedAtMs;

    public TrackTombstone() {
    }

    public TrackTombstone(String mediaId, long removedAtMs) {
        this.mediaId = mediaId == null ? "" : mediaId;
        this.removedAtMs = removedAtMs;
    }
}
