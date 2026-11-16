package dev.t1m3.qplayer.playlist;

import java.util.ArrayList;
import java.util.List;

/** The payload a sending device hands to a receiver: every local playlist plus
 *  the playlists it has deleted. Detached copies; safe to serialize off-thread. */
public final class PlaylistSyncSnapshot {

    public final List<LocalPlaylist> playlists;
    public final List<PlaylistTombstone> deletedPlaylists;

    public PlaylistSyncSnapshot(List<LocalPlaylist> playlists,
                                List<PlaylistTombstone> deletedPlaylists) {
        this.playlists = playlists == null ? new ArrayList<LocalPlaylist>() : playlists;
        this.deletedPlaylists = deletedPlaylists == null
                ? new ArrayList<PlaylistTombstone>() : deletedPlaylists;
    }
}
