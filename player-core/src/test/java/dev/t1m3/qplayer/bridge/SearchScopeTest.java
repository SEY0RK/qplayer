package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.media.Song;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SearchPage's source filter, at the level that decides what is shown.
 *
 * <p>Two things here are easy to get wrong and invisible from the UI: the row
 * indices must stay relative to each provider's own bucket (playback resolves
 * {@code pluginSearchByProvider.get(providerId).get(row.index)}, so filtering must
 * not renumber), and a scope naming a provider that has since been disabled has to
 * fall back to "all" rather than render an empty page for a query that matched.
 */
public class SearchScopeTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
    @Rule public final ControllerTestLifecycle lifecycle = new ControllerTestLifecycle();

    @Test
    public void narrowingToASourceKeepsOnlyItsRowsAndDoesNotRenumberThem() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            controller = newController();
            setBuckets(controller,
                    bucket("netease", "网易云", "n1", "n2"),
                    bucket("bilibili", "哔哩哔哩", "b1", "b2"));
            controller.rebuildSearchRows();

            // Chips name exactly the buckets that exist, "all" first.
            assertEquals(Collections.singletonList(PlayerController.SEARCH_SCOPE_ALL),
                    controller.searchScopeIds.peek().subList(0, 1));
            assertEquals(Arrays.asList(PlayerController.SEARCH_SCOPE_ALL, "netease", "bilibili"),
                    controller.searchScopeIds.peek());
            assertEquals(Arrays.asList("网易云", "哔哩哔哩"),
                    controller.searchScopeTabs.peek().subList(1, 3));
            assertEquals(Integer.valueOf(0), controller.searchScopeIndex.peek());
            assertEquals(4, controller.searchRows.peek().size());

            // Narrowing keeps only that source's rows...
            controller.setSearchScope("bilibili");
            List<SearchRow> only = controller.searchRows.peek();
            assertEquals(2, only.size());
            for (SearchRow row : only) {
                assertEquals("bilibili", row.providerId);
            }
            // ...and must NOT renumber them: index stays the position inside the
            // provider's own bucket.
            assertEquals(0, only.get(0).index);
            assertEquals(1, only.get(1).index);
            assertEquals(Integer.valueOf(2), controller.searchScopeIndex.peek());

            // Switching back restores the aggregated list without re-querying.
            controller.setSearchScope(PlayerController.SEARCH_SCOPE_ALL);
            assertEquals(4, controller.searchRows.peek().size());
            assertEquals(Integer.valueOf(0), controller.searchScopeIndex.peek());

            // A provider that is not active falls back to "all" instead of showing
            // an empty page.
            controller.setSearchScope("no-such-provider");
            assertEquals(4, controller.searchRows.peek().size());
            assertEquals(PlayerController.SEARCH_SCOPE_ALL, controller.searchScope.peek());
            assertEquals(Integer.valueOf(0), controller.searchScopeIndex.peek());
        } finally {
            lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /** One source (or none) leaves nothing to switch between, so the chips collapse
     *  and SearchPage hides the row entirely. */
    @Test
    public void aSingleSourceOffersNoChips() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            controller = newController();
            setBuckets(controller, bucket("bilibili", "哔哩哔哩", "b1"));
            controller.rebuildSearchRows();
            assertTrue("chips must collapse when there is only one source",
                    controller.searchScopeTabs.peek().isEmpty());
            assertTrue(controller.searchScopeIds.peek().isEmpty());
            assertEquals(1, controller.searchRows.peek().size());

            // Adding a second source brings them back: the "all" chip plus one per source.
            setBuckets(controller,
                    bucket("netease", "网易云", "n1"),
                    bucket("bilibili", "哔哩哔哩", "b1"));
            controller.rebuildSearchRows();
            assertEquals(3, controller.searchScopeTabs.peek().size());
        } finally {
            lifecycle.close(controller);
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    private PlayerController newController() throws Exception {
        Path base = temporaryFolder.newFolder().toPath();
        AppDirs.setBase(base.toString());
        AppDirs.setCacheBase(base.resolve("cache").toString());
        dev.t1m3.qplayer.audio.AudioBackend backend =
                (dev.t1m3.qplayer.audio.AudioBackend) java.lang.reflect.Proxy.newProxyInstance(
                        dev.t1m3.qplayer.audio.AudioBackend.class.getClassLoader(),
                        new Class<?>[]{dev.t1m3.qplayer.audio.AudioBackend.class},
                        (proxy, method, args) -> {
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                        });
        return new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
    }

    /** A provider id -> (display name, song ids) pair, in the order they should appear. */
    private static Map.Entry<String, Object[]> bucket(String id, String name, String... songIds) {
        return new java.util.AbstractMap.SimpleEntry<>(id, new Object[]{name, songIds});
    }

    /** Replace the render-thread provider buckets the search pipeline fills. */
    private static void setBuckets(PlayerController controller,
                                   Map.Entry<String, Object[]>... buckets) throws Exception {
        Map<String, List<Song>> byProvider = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, Object[]> entry : buckets) {
            names.put(entry.getKey(), (String) entry.getValue()[0]);
            List<Song> songs = new ArrayList<>();
            for (String songId : (String[]) entry.getValue()[1]) {
                Song song = new Song();
                song.id = songId;
                song.title = songId;
                songs.add(song);
            }
            byProvider.put(entry.getKey(), songs);
        }
        field(controller, "pluginSearchByProvider").set(controller, byProvider);
        field(controller, "pluginProviderNames").set(controller, names);
    }

    private static Field field(PlayerController controller, String name) throws Exception {
        Field f = PlayerController.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
