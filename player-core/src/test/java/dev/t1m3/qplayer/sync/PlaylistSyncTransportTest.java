package dev.t1m3.qplayer.sync;

import dev.t1m3.qplayer.playlist.LocalPlaylist;
import dev.t1m3.qplayer.playlist.PlaylistMerge;
import dev.t1m3.qplayer.playlist.PlaylistSyncSnapshot;
import dev.t1m3.qplayer.playlist.PlaylistTombstone;
import dev.t1m3.qplayer.playlist.PlaylistTrack;
import dev.t1m3.qplayer.playlist.PlaylistStoreAccess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.*;

/** Real socket round-trip on loopback: server + client + merge, no mocks. */
public class PlaylistSyncTransportTest {

    private static final class MemoryStore implements PlaylistStoreAccess {
        List<LocalPlaylist> playlists = new ArrayList<LocalPlaylist>();
        List<PlaylistTombstone> deleted = new ArrayList<PlaylistTombstone>();
        int saves;

        @Override
        public PlaylistSyncSnapshot snapshot() {
            return new PlaylistSyncSnapshot(new ArrayList<LocalPlaylist>(playlists),
                    new ArrayList<PlaylistTombstone>(deleted));
        }

        @Override
        public PlaylistMerge.Result applySyncSnapshotAndSave(PlaylistSyncSnapshot incoming) {
            PlaylistMerge.Result result = PlaylistMerge.merge(playlists, deleted,
                    incoming.playlists, incoming.deletedPlaylists, System.currentTimeMillis());
            if (result.changed) {
                playlists = new ArrayList<LocalPlaylist>(result.playlists);
                deleted = new ArrayList<PlaylistTombstone>(result.deletedPlaylists);
                saves++;
            }
            return result;
        }
    }

    private static PlaylistTrack track(String id) {
        PlaylistTrack track = new PlaylistTrack();
        track.mediaId = id;
        track.provider = "netease";
        track.source = dev.t1m3.qplayer.model.Track.Source.PLUGIN.name();
        track.addedAtMs = 10L;
        return track;
    }

    private static LocalPlaylist playlist(String id, String name, String... trackIds) {
        LocalPlaylist playlist = new LocalPlaylist();
        playlist.id = id;
        playlist.name = name;
        playlist.updatedAtMs = 100L;
        for (String trackId : trackIds) playlist.tracks.add(track(trackId));
        return playlist;
    }

    @Test
    public void statusReportsReceiverIdentity() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicInteger approvals = new AtomicInteger();
        PlaylistSyncServer server = new PlaylistSyncServer(store, "dev-recv", "Receiver",
                "test", (action, remote, plan) -> {
                    approvals.incrementAndGet();
                    return true;
                });
        assertTrue(server.start());
        try {
            PlaylistSyncClient client = new PlaylistSyncClient("dev-send", "Sender");
            PlaylistSyncClient.Address address =
                    PlaylistSyncClient.parseAddress("127.0.0.1:" + server.port(), 39890);
            PlaylistSyncProtocol.Status status = client.status(address);
            assertEquals("dev-recv", status.deviceId);
            assertEquals("Receiver", status.deviceName);
            assertEquals(0, approvals.get());
        } finally {
            server.stop();
        }
    }

    @Test
    public void fullSyncFlowWithApproval() throws Exception {
        MemoryStore store = new MemoryStore();
        store.playlists.add(playlist("local:playlist:mine", "Mine", "netease:song:1"));
        List<String> seenActions = new ArrayList<String>();
        PlaylistSyncServer server = new PlaylistSyncServer(store, "dev-recv", "Receiver",
                "test", (action, remote, plan) -> {
                    seenActions.add(action);
                    assertEquals("dev-send", plan.deviceId);
                    return true;
                });
        assertTrue(server.start());
        try {
            MemoryStore senderStore = new MemoryStore();
            senderStore.playlists.add(playlist("local:playlist:mine", "Mine",
                    "netease:song:1", "netease:song:2"));
            PlaylistSyncClient client = new PlaylistSyncClient("dev-send", "Sender");
            PlaylistSyncClient.Address address =
                    PlaylistSyncClient.parseAddress("127.0.0.1:" + server.port(), 39890);

            assertTrue(client.requestApproval(address, senderStore.snapshot()));
            PlaylistSyncProtocol.ApplyResult result = client.send(address, senderStore.snapshot());
            assertTrue(result.ok);
            assertTrue(result.ok);
            assertEquals(1, store.playlists.size());
            assertEquals(Arrays.asList("netease:song:1", "netease:song:2"),
                    ids(store.playlists.get(0)));
            assertEquals(Arrays.asList("import", "apply"), seenActions);
            assertEquals(1, store.saves);
        } finally {
            server.stop();
        }
    }

    @Test
    public void rejectionPreventsApply() throws Exception {
        MemoryStore store = new MemoryStore();
        PlaylistSyncServer server = new PlaylistSyncServer(store, "dev-recv", "Receiver",
                "test", (action, remote, plan) -> false);
        assertTrue(server.start());
        try {
            MemoryStore sender = new MemoryStore();
            sender.playlists.add(playlist("local:playlist:a", "A", "netease:song:1"));
            PlaylistSyncClient client = new PlaylistSyncClient("dev-send", "Sender");
            PlaylistSyncClient.Address address =
                    PlaylistSyncClient.parseAddress("127.0.0.1:" + server.port(), 39890);
            assertFalse(client.requestApproval(address, sender.snapshot()));
            try {
                client.send(address, sender.snapshot());
                fail("apply must be refused");
            } catch (SyncException expected) {
                assertTrue(expected.getMessage().contains("403"));
            }
            assertTrue(store.playlists.isEmpty());
            assertEquals(0, store.saves);
        } finally {
            server.stop();
        }
    }

    @Test
    public void senderDeletionPropagatesOverTheWire() throws Exception {
        MemoryStore store = new MemoryStore();
        store.playlists.add(playlist("local:playlist:a", "A",
                "netease:song:1", "netease:song:2"));
        PlaylistSyncServer server = new PlaylistSyncServer(store, "dev-recv", "Receiver",
                "test", (action, remote, plan) -> true);
        assertTrue(server.start());
        try {
            MemoryStore sender = new MemoryStore();
            LocalPlaylist kept = playlist("local:playlist:a", "A", "netease:song:1");
            long now = System.currentTimeMillis();
            kept.tombstones.add(new dev.t1m3.qplayer.playlist.TrackTombstone("netease:song:2", now - 5_000L));
            sender.playlists.add(kept);
            PlaylistSyncClient client = new PlaylistSyncClient("dev-send", "Sender");
            PlaylistSyncClient.Address address =
                    PlaylistSyncClient.parseAddress("127.0.0.1:" + server.port(), 39890);
            PlaylistSyncProtocol.ApplyResult result = client.send(address, sender.snapshot());
            assertTrue(result.ok);
            assertEquals(Arrays.asList("netease:song:1"), ids(store.playlists.get(0)));
        } finally {
            server.stop();
        }
    }

    @Test
    public void addressParsing() throws Exception {
        assertEquals(39890, PlaylistSyncClient.parseAddress("192.168.1.5", 39890).port);
        PlaylistSyncClient.Address withPort =
                PlaylistSyncClient.parseAddress("http://192.168.1.5:40000/sync", 39890);
        assertEquals("192.168.1.5", withPort.host);
        assertEquals(40000, withPort.port);
        try {
            PlaylistSyncClient.parseAddress("not a host", 39890);
            fail("invalid address must be rejected");
        } catch (SyncException expected) {
        }
    }

    private static List<String> ids(LocalPlaylist playlist) {
        List<String> out = new ArrayList<String>();
        for (PlaylistTrack track : playlist.tracks) out.add(track.mediaId);
        return out;
    }

    public static void main(String[] args) {
        int passed = 0;
        StringBuilder failures = new StringBuilder();
        for (java.lang.reflect.Method method : PlaylistSyncTransportTest.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.junit.Test.class) == null) continue;
            try {
                new PlaylistSyncTransportTest().invoke(method);
                passed++;
                System.out.println("PASS " + method.getName());
            } catch (Exception error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                failures.append("FAIL ").append(method.getName()).append(": ").append(cause).append('\n');
                System.out.println("FAIL " + method.getName() + " - " + cause);
            }
        }
        System.out.println("PlaylistSyncTransportTest: " + passed + " passed, failures="
                + (failures.length() > 0));
        if (failures.length() > 0) throw new AssertionError(failures.toString());
    }

    private void invoke(java.lang.reflect.Method method) throws Exception {
        method.invoke(this);
    }
}
