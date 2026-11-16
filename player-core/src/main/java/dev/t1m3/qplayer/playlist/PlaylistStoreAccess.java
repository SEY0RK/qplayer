package dev.t1m3.qplayer.playlist;

/** The slice of playlist persistence device sync needs. Kept as an interface
 *  so the transport and merge can be tested with an in-memory store. */
public interface PlaylistStoreAccess {

    PlaylistSyncSnapshot snapshot();

    PlaylistMerge.Result applySyncSnapshotAndSave(PlaylistSyncSnapshot snapshot);
}
