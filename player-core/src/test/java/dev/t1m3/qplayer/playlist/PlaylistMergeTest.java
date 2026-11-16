package dev.t1m3.qplayer.playlist;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Device sync semantics: one-way push, merged content, sender-only deletions. */
public class PlaylistMergeTest {

    private static final long NOW = 1_700_000_000_000L;

    private static PlaylistTrack track(String mediaId, long addedAtMs) {
        PlaylistTrack track = new PlaylistTrack();
        track.mediaId = mediaId;
        track.provider = "netease";
        track.source = dev.t1m3.qplayer.model.Track.Source.PLUGIN.name();
        track.title = mediaId;
        track.addedAtMs = addedAtMs;
        return track;
    }

    private static LocalPlaylist playlist(String id, String name, long updatedAtMs,
                                          PlaylistTrack... tracks) {
        LocalPlaylist playlist = new LocalPlaylist();
        playlist.id = id;
        playlist.name = name;
        playlist.createdAtMs = 1L;
        playlist.updatedAtMs = updatedAtMs;
        playlist.tracks.addAll(Arrays.asList(tracks));
        return playlist;
    }

    private static List<LocalPlaylist> list(LocalPlaylist... items) {
        return new ArrayList<LocalPlaylist>(Arrays.asList(items));
    }

    private static PlaylistMerge.Result merge(List<LocalPlaylist> receiver,
                                              List<PlaylistTombstone> receiverDeleted,
                                              List<LocalPlaylist> sender,
                                              List<PlaylistTombstone> senderDeleted) {
        return PlaylistMerge.merge(receiver, receiverDeleted, sender, senderDeleted, NOW);
    }

    private static List<String> mediaIds(LocalPlaylist playlist) {
        List<String> out = new ArrayList<String>();
        for (PlaylistTrack track : playlist.tracks) out.add(track.mediaId);
        return out;
    }

    @Test
    public void addsSendersPlaylistAndKeepsReceiverOnlyPlaylist() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L, track("netease:song:1", 10L));
        LocalPlaylist theirs = playlist("local:playlist:b", "Theirs", 200L, track("netease:song:2", 20L));

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertEquals(2, result.playlists.size());
        assertTrue(result.changed);
        assertEquals(1, result.addedPlaylists);
        assertTrue(mediaIds(result.playlists.get(0)).contains("netease:song:1"));
        assertTrue(mediaIds(result.playlists.get(1)).contains("netease:song:2"));
    }

    @Test
    public void unionsTracksWithNewerSideAsSkeleton() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L,
                track("netease:song:1", 10L), track("netease:song:2", 11L),
                track("netease:song:3", 12L));
        LocalPlaylist theirs = playlist("local:playlist:a", "Theirs", 200L,
                track("netease:song:2", 11L), track("netease:song:4", 21L),
                track("netease:song:1", 10L));

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        LocalPlaylist merged = result.playlists.get(0);
        assertEquals(Arrays.asList("netease:song:2", "netease:song:4", "netease:song:1",
                "netease:song:3"), mediaIds(merged));
        assertEquals("Theirs", merged.name);
    }

    @Test
    public void olderSenderKeepsReceiverOrder() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 500L,
                track("netease:song:1", 10L), track("netease:song:2", 11L));
        LocalPlaylist theirs = playlist("local:playlist:a", "Theirs", 200L,
                track("netease:song:2", 11L), track("netease:song:3", 12L));

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertEquals(Arrays.asList("netease:song:1", "netease:song:2", "netease:song:3"),
                mediaIds(result.playlists.get(0)));
        assertEquals("Mine", result.playlists.get(0).name);
    }

    @Test
    public void senderTrackDeletionPropagates() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L,
                track("netease:song:1", 10L), track("netease:song:2", 11L));
        LocalPlaylist theirs = playlist("local:playlist:a", "Mine", 200L,
                track("netease:song:1", 10L));
        theirs.tombstones.add(new TrackTombstone("netease:song:2", NOW - 10L));

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertEquals(Collections.singletonList("netease:song:1"),
                mediaIds(result.playlists.get(0)));
        assertEquals(1, result.removedTracks);
    }

    @Test
    public void receiverTrackDeletionDoesNotPropagateToSenderButSurvivesLocally() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L,
                track("netease:song:1", 10L));
        mine.tombstones.add(new TrackTombstone("netease:song:2", NOW - 50L));
        // Sender still has song 2 — it never learned about the receiver's delete.
        LocalPlaylist theirs = playlist("local:playlist:a", "Mine", 200L,
                track("netease:song:1", 10L), track("netease:song:2", 11L));

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        LocalPlaylist merged = result.playlists.get(0);
        assertEquals(Collections.singletonList("netease:song:1"), mediaIds(merged));
        // The local deletion record is kept so it can be sent if this device
        // becomes the sender later.
        assertEquals(1, merged.tombstones.size());
    }

    @Test
    public void songAddedAfterDeletionIsRevived() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L);
        LocalPlaylist theirs = playlist("local:playlist:a", "Mine", 200L,
                track("netease:song:9", NOW));
        theirs.tombstones.add(new TrackTombstone("netease:song:9", NOW - 100L));

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertEquals(Collections.singletonList("netease:song:9"),
                mediaIds(result.playlists.get(0)));
    }

    @Test
    public void senderPlaylistDeletionPropagates() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L,
                track("netease:song:1", 10L));
        PlaylistTombstone deletion = new PlaylistTombstone("local:playlist:a", NOW - 10L);

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                Collections.<LocalPlaylist>emptyList(), Collections.singletonList(deletion));

        assertTrue(result.playlists.isEmpty());
        assertEquals(1, result.removedPlaylists);
        assertEquals(1, result.deletedPlaylists.size());
    }

    @Test
    public void receiverPlaylistDeletionDoesNotRemoveSendersCopy() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L);
        PlaylistTombstone localDeletion = new PlaylistTombstone("local:playlist:a", NOW - 50L);
        LocalPlaylist theirs = playlist("local:playlist:a", "Mine", 200L,
                track("netease:song:1", 10L));

        PlaylistMerge.Result result = merge(list(mine),
                Collections.singletonList(localDeletion),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertTrue(result.playlists.isEmpty());
    }

    @Test
    public void localDeletionShieldsOlderSendersCopy() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L);
        PlaylistTombstone localDeletion = new PlaylistTombstone("local:playlist:a", NOW + 400L);
        LocalPlaylist theirs = playlist("local:playlist:a", "Old", 200L,
                track("netease:song:1", 10L));

        PlaylistMerge.Result result = merge(list(mine),
                Collections.singletonList(localDeletion),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertTrue(result.playlists.isEmpty());
    }

    @Test
    public void applyingTwiceIsIdempotent() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L,
                track("netease:song:1", 10L));
        LocalPlaylist theirs = playlist("local:playlist:a", "Theirs", 200L,
                track("netease:song:2", 20L));

        PlaylistMerge.Result first = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());
        PlaylistMerge.Result second = merge(first.playlists, first.deletedPlaylists,
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertFalse("second apply must be a no-op", second.changed);
        assertEquals(mediaIds(first.playlists.get(0)), mediaIds(second.playlists.get(0)));
    }

    @Test
    public void subscriptionsAreUnionedAndMarkedStale() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L);
        PlaylistSubscription mineSub = new PlaylistSubscription("netease:playlist:1", "A", "网易云", "");
        mineSub.lastSyncedAtMs = 999L;
        mine.subscriptions.add(mineSub);
        LocalPlaylist theirs = playlist("local:playlist:a", "Mine", 200L);
        PlaylistSubscription theirSub = new PlaylistSubscription("netease:playlist:2", "B", "网易云", "");
        theirSub.lastSyncedAtMs = 999L;
        theirs.subscriptions.add(theirSub);

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        LocalPlaylist merged = result.playlists.get(0);
        assertEquals(2, merged.subscriptions.size());
        for (PlaylistSubscription subscription : merged.subscriptions) {
            assertEquals(0L, subscription.lastSyncedAtMs);
        }
    }

    @Test
    public void localCoverPathIsNeverOverwritten() throws Exception {
        java.io.File cover = java.io.File.createTempFile("qplayer-cover", ".png");
        cover.deleteOnExit();
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L);
        mine.coverPath = cover.getAbsolutePath();
        LocalPlaylist theirs = playlist("local:playlist:a", "Theirs", 200L);
        theirs.coverPath = "C:\\other\\cover.png";

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertEquals(cover.getAbsolutePath(), result.playlists.get(0).coverPath);
    }

    @Test
    public void clockSkewToleranceKeepsRecentReAdd() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L);
        LocalPlaylist theirs = playlist("local:playlist:a", "Mine", 200L,
                track("netease:song:1", NOW + 60_000L));
        // Addition is newer than the sender's deletion record.
        theirs.tombstones.add(new TrackTombstone("netease:song:1", NOW - 50L));

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertEquals(Collections.singletonList("netease:song:1"),
                mediaIds(result.playlists.get(0)));
    }

    @Test
    public void expiredTombstonesAreDropped() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L);
        LocalPlaylist theirs = playlist("local:playlist:a", "Mine", 200L);
        theirs.tombstones.add(new TrackTombstone("netease:song:old",
                NOW - PlaylistMerge.TOMBSTONE_TTL_MS - 1L));

        PlaylistMerge.Result result = merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertTrue(result.playlists.get(0).tombstones.isEmpty());
    }

    @Test
    public void inputsAreNotMutated() {
        LocalPlaylist mine = playlist("local:playlist:a", "Mine", 100L,
                track("netease:song:1", 10L));
        LocalPlaylist theirs = playlist("local:playlist:a", "Theirs", 200L,
                track("netease:song:2", 20L));

        merge(list(mine), Collections.<PlaylistTombstone>emptyList(),
                list(theirs), Collections.<PlaylistTombstone>emptyList());

        assertEquals(Collections.singletonList("netease:song:1"), mediaIds(mine));
        assertEquals(Collections.singletonList("netease:song:2"), mediaIds(theirs));
        assertEquals("Mine", mine.name);
    }
}
