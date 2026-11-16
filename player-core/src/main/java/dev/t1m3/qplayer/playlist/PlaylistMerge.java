package dev.t1m3.qplayer.playlist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges one device's playlist snapshot into another's local state.
 *
 * <p>The sync is deliberately one-way: the sender's state is authoritative, but
 * it is <em>merged</em>, not copied. Playlists and songs that only exist on the
 * receiver survive. Only deletions made on the sender propagate — a deletion
 * made on the receiver stays there and shields that item from being merged back
 * in, but is never sent to the other device.
 *
 * <p>Pure function: no I/O, no clock, no mutation of the arguments. Every list
 * it returns is freshly allocated. That makes the ordering / tombstone rules
 * unit-testable without a device, a server, or a scheduler.
 */
public final class PlaylistMerge {

    /** Two clocks on two devices never agree exactly. A removal older than the
     *  addition by less than this is treated as "the addition came later". */
    public static final long CLOCK_TOLERANCE_MS = 5L * 60L * 1000L;
    /** Deletion records older than this are dropped so the file cannot grow
     *  forever. A song deleted 90 days ago and never re-added is gone for good. */
    public static final long TOMBSTONE_TTL_MS = 90L * 24L * 60L * 60L * 1000L;

    private PlaylistMerge() {
    }

    /** Outcome of a merge. {@code changed} is false when the receiver already
     *  held exactly the same state, so callers can skip a disk write. */
    public static final class Result {
        public final List<LocalPlaylist> playlists = new ArrayList<LocalPlaylist>();
        public final List<PlaylistTombstone> deletedPlaylists = new ArrayList<PlaylistTombstone>();
        public int addedPlaylists;
        public int removedPlaylists;
        public int updatedPlaylists;
        public int addedTracks;
        public int removedTracks;
        public boolean changed;
    }

    public static Result merge(List<LocalPlaylist> receiverPlaylists,
                               List<PlaylistTombstone> receiverDeleted,
                               List<LocalPlaylist> senderPlaylists,
                               List<PlaylistTombstone> senderDeleted,
                               long nowMs) {
        Result result = new Result();
        List<LocalPlaylist> mine = copyPlaylists(receiverPlaylists);
        List<LocalPlaylist> theirs = copyPlaylists(senderPlaylists);
        Map<String, LocalPlaylist> mineById = byId(mine);
        Map<String, LocalPlaylist> theirsById = byId(theirs);
        Map<String, Long> mineDeleted = tombstoneTimes(receiverDeleted, nowMs);
        Map<String, Long> theirsDeleted = tombstoneTimes(senderDeleted, nowMs);

        String before = signature(mine, receiverDeleted);

        // Sender's playlist deletions propagate. A receiver that edited the
        // playlist after the sender deleted it keeps its copy.
        for (Map.Entry<String, Long> deletion : theirsDeleted.entrySet()) {
            String id = deletion.getKey();
            LocalPlaylist local = mineById.get(id);
            if (local == null || theirsById.containsKey(id)) continue;
            if (local.updatedAtMs > deletion.getValue() + CLOCK_TOLERANCE_MS) continue;
            mineById.remove(id);
            mine.remove(local);
            result.removedPlaylists++;
        }

        // Merge every playlist the sender sent. Receiver-only ones are left as
        // they are, which is what makes this a merge rather than an overwrite.
        for (LocalPlaylist remote : theirs) {
            LocalPlaylist local = mineById.get(remote.id);
            if (local == null) {
                Long localDeletion = mineDeleted.get(remote.id);
                // A playlist deleted here stays deleted even if the sender's
                // older copy comes back around.
                if (localDeletion != null
                        && remote.updatedAtMs <= localDeletion + CLOCK_TOLERANCE_MS) {
                    continue;
                }
                local = copy(remote);
                local.coverPath = ""; // absolute path on another device is meaningless
                local.sanitize();
                mine.add(local);
                mineById.put(local.id, local);
                result.addedPlaylists++;
                result.addedTracks += local.tracks.size();
                continue;
            }
            mergeOne(local, remote, mineDeleted, theirsDeleted, nowMs, result);
            result.updatedPlaylists++;
        }

        result.playlists.addAll(mine);
        // Both sides' deletion records are kept: the receiver may become the
        // sender next time and needs to propagate what it deleted earlier.
        Map<String, Long> allDeleted = new LinkedHashMap<String, Long>(mineDeleted);
        for (Map.Entry<String, Long> entry : theirsDeleted.entrySet()) {
            Long previous = allDeleted.get(entry.getKey());
            if (previous == null || entry.getValue() > previous) {
                allDeleted.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, Long> entry : allDeleted.entrySet()) {
            if (entry.getValue() >= nowMs - TOMBSTONE_TTL_MS) {
                result.deletedPlaylists.add(
                        new PlaylistTombstone(entry.getKey(), entry.getValue()));
            }
        }
        result.changed = !before.equals(signature(result.playlists, result.deletedPlaylists));
        return result;
    }

    /** Merge two versions of the same playlist into {@code local}. */
    private static void mergeOne(LocalPlaylist local, LocalPlaylist remote,
                                 Map<String, Long> mineDeleted, Map<String, Long> theirsDeleted,
                                 long nowMs, Result result) {
        boolean remoteIsNewer = remote.updatedAtMs >= local.updatedAtMs;
        LocalPlaylist skeleton = remoteIsNewer ? remote : local;
        LocalPlaylist other = remoteIsNewer ? local : remote;

        // A track deleted on the sender is removed from the receiver. A track
        // deleted on the receiver is likewise kept out even if the sender still
        // has it — but that deletion is not sent back to the sender.
        Map<String, PlaylistTrack> mergedTracks = new LinkedHashMap<String, PlaylistTrack>();
        for (PlaylistTrack track : skeleton.tracks) {
            if (track == null || !track.valid()) continue;
            if (isDeleted(track, theirsDeleted) || isDeleted(track, mineDeleted)) {
                result.removedTracks++;
                continue;
            }
            if (!mergedTracks.containsKey(track.mediaId)) {
                mergedTracks.put(track.mediaId, copyTrack(track));
            }
        }
        for (PlaylistTrack track : other.tracks) {
            if (track == null || !track.valid()) continue;
            if (mergedTracks.containsKey(track.mediaId)) continue;
            if (isDeleted(track, theirsDeleted) || isDeleted(track, mineDeleted)) continue;
            mergedTracks.put(track.mediaId, copyTrack(track));
            result.addedTracks++;
        }

        // Subscriptions are a set keyed by source playlist. Reset the sync
        // timestamp so the receiver refreshes from the source on its own.
        Map<String, PlaylistSubscription> subscriptions =
                new LinkedHashMap<String, PlaylistSubscription>();
        for (PlaylistSubscription subscription : skeleton.subscriptions) {
            if (subscription == null || subscription.sourcePlaylistId.isEmpty()) continue;
            PlaylistSubscription copy = copySubscription(subscription);
            copy.lastSyncedAtMs = 0L;
            copy.lastError = "";
            subscriptions.put(copy.sourcePlaylistId, copy);
        }
        for (PlaylistSubscription subscription : other.subscriptions) {
            if (subscription == null || subscription.sourcePlaylistId.isEmpty()) continue;
            if (subscriptions.containsKey(subscription.sourcePlaylistId)) continue;
            PlaylistSubscription copy = copySubscription(subscription);
            copy.lastSyncedAtMs = 0L;
            copy.lastError = "";
            subscriptions.put(copy.sourcePlaylistId, copy);
        }

        // A cover chosen on this device is local file state; never overwrite it
        // with an absolute path from the other machine.
        String coverPath = local.coverPath;
        local.tracks = new ArrayList<PlaylistTrack>(mergedTracks.values());
        local.subscriptions = new ArrayList<PlaylistSubscription>(subscriptions.values());
        local.name = skeleton.name;
        local.description = skeleton.description;
        local.sortField = skeleton.sortField;
        local.sortDescending = skeleton.sortDescending;
        local.createdAtMs = Math.min(local.createdAtMs, remote.createdAtMs);
        local.updatedAtMs = nowMs;
        local.coverPath = coverPath == null ? "" : coverPath;

        // Merge track tombstones, dropping records that are no longer needed
        // because the song is back in the list.
        Map<String, Long> trackDeletions = new LinkedHashMap<String, Long>();
        for (TrackTombstone tombstone : local.tombstones) {
            if (tombstone != null && tombstone.mediaId != null) {
                trackDeletions.put(tombstone.mediaId, tombstone.removedAtMs);
            }
        }
        for (TrackTombstone tombstone : remote.tombstones) {
            if (tombstone == null || tombstone.mediaId == null) continue;
            Long previous = trackDeletions.get(tombstone.mediaId);
            if (previous == null || tombstone.removedAtMs > previous) {
                trackDeletions.put(tombstone.mediaId, tombstone.removedAtMs);
            }
        }
        List<TrackTombstone> keptTombstones = new ArrayList<TrackTombstone>();
        for (Map.Entry<String, Long> entry : trackDeletions.entrySet()) {
            if (mergedTracks.containsKey(entry.getKey())) continue; // re-added afterwards
            if (entry.getValue() < nowMs - TOMBSTONE_TTL_MS) continue;
            keptTombstones.add(new TrackTombstone(entry.getKey(), entry.getValue()));
        }
        local.tombstones = keptTombstones;
        local.sanitize();
    }

    private static boolean isDeleted(PlaylistTrack track, Map<String, Long> deletions) {
        Long removedAt = deletions.get(track.mediaId);
        if (removedAt == null) return false;
        return removedAt + CLOCK_TOLERANCE_MS >= track.addedAtMs;
    }

    private static Map<String, Long> tombstoneTimes(List<PlaylistTombstone> tombstones,
                                                     long nowMs) {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        if (tombstones == null) return out;
        for (PlaylistTombstone tombstone : tombstones) {
            if (tombstone == null || tombstone.id == null || tombstone.id.isEmpty()) continue;
            if (tombstone.deletedAtMs < nowMs - TOMBSTONE_TTL_MS) continue;
            Long previous = out.get(tombstone.id);
            if (previous == null || tombstone.deletedAtMs > previous) {
                out.put(tombstone.id, tombstone.deletedAtMs);
            }
        }
        return out;
    }

    private static Map<String, LocalPlaylist> byId(List<LocalPlaylist> playlists) {
        Map<String, LocalPlaylist> out = new HashMap<String, LocalPlaylist>();
        for (LocalPlaylist playlist : playlists) {
            if (playlist != null && playlist.id != null) out.put(playlist.id, playlist);
        }
        return out;
    }

    private static List<LocalPlaylist> copyPlaylists(List<LocalPlaylist> source) {
        List<LocalPlaylist> out = new ArrayList<LocalPlaylist>();
        if (source == null) return out;
        for (LocalPlaylist playlist : source) {
            if (playlist != null) out.add(copy(playlist));
        }
        return out;
    }

    private static LocalPlaylist copy(LocalPlaylist source) {
        LocalPlaylist out = new LocalPlaylist();
        out.id = source.id;
        out.name = source.name;
        out.description = source.description;
        out.coverPath = source.coverPath;
        out.createdAtMs = source.createdAtMs;
        out.updatedAtMs = source.updatedAtMs;
        out.sortField = source.sortField;
        out.sortDescending = source.sortDescending;
        for (PlaylistTrack track : source.tracks) out.tracks.add(copyTrack(track));
        for (PlaylistSubscription subscription : source.subscriptions) {
            out.subscriptions.add(copySubscription(subscription));
        }
        for (TrackTombstone tombstone : source.tombstones) {
            if (tombstone != null) {
                out.tombstones.add(new TrackTombstone(tombstone.mediaId, tombstone.removedAtMs));
            }
        }
        return out;
    }

    private static PlaylistTrack copyTrack(PlaylistTrack source) {
        PlaylistTrack out = new PlaylistTrack();
        out.mediaId = source.mediaId;
        out.provider = source.provider;
        out.providerName = source.providerName;
        out.source = source.source;
        out.title = source.title;
        out.artist = source.artist;
        out.artistMediaId = source.artistMediaId;
        out.artistIdsCsv = source.artistIdsCsv;
        out.artistNamesCsv = source.artistNamesCsv;
        out.album = source.album;
        out.durationMs = source.durationMs;
        out.coverUrl = source.coverUrl;
        out.filePath = source.filePath;
        out.contentUri = source.contentUri;
        out.neteaseId = source.neteaseId;
        out.origin = source.origin;
        out.addedAtMs = source.addedAtMs;
        return out;
    }

    private static PlaylistSubscription copySubscription(PlaylistSubscription source) {
        PlaylistSubscription out = new PlaylistSubscription();
        out.sourcePlaylistId = source.sourcePlaylistId;
        out.provider = source.provider;
        out.name = source.name;
        out.providerName = source.providerName;
        out.artworkUrl = source.artworkUrl;
        out.lastSyncedAtMs = source.lastSyncedAtMs;
        out.trackCount = source.trackCount;
        out.lastError = source.lastError;
        return out;
    }

    /** Stable description of receiver state, used to decide whether a disk
     *  write is actually needed. Order matters: it is part of the state. */
    private static String signature(List<LocalPlaylist> playlists,
                                    List<PlaylistTombstone> deleted) {
        StringBuilder out = new StringBuilder();
        for (LocalPlaylist playlist : playlists) {
            out.append(playlist.id).append('|').append(playlist.name).append('|')
                    .append(playlist.sortField).append('|').append(playlist.sortDescending)
                    .append('|').append(playlist.updatedAtMs).append('|');
            for (PlaylistTrack track : playlist.tracks) out.append(track.mediaId).append(',');
            out.append('|');
            for (PlaylistSubscription subscription : playlist.subscriptions) {
                out.append(subscription.sourcePlaylistId).append(',');
            }
            out.append('|');
            for (TrackTombstone tombstone : playlist.tombstones) {
                out.append(tombstone.mediaId).append(':').append(tombstone.removedAtMs).append(',');
            }
            out.append(';');
        }
        out.append('#');
        for (PlaylistTombstone tombstone : deleted) {
            out.append(tombstone.id).append(':').append(tombstone.deletedAtMs).append(',');
        }
        return out.toString();
    }
}
