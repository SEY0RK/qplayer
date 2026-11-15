package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.desktop.settings.JsonSettingsStore;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.store.AppDirs;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 歌单搜索的空状态（本地改动）。
 *
 * <p>歌单搜索只靠插件声明 {@code searchPlaylists} 能力，宿主没有内置实现。旧插件都不提供时，
 * 上游页面是一片什么都不画的空白 —— 用户看到的是"白屏"，而不是"没有结果"。这个测试锁住
 * 两种情况各自的提示：没有音源支持 vs 有音源但没命中。
 */
public final class SearchPlaylistEmptyStateTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void unsupportedSourcesExplainWhyThePageIsEmpty() throws Exception {
        withScene((view, player) -> {
            player.playlistSearchSupported.set(false);
            setQuery(view, "周杰伦");
            settle(view);

            Item empty = view.findByObjectName("searchPlaylistEmpty");
            assertNotNull("SearchPage must carry the playlist empty state", empty);
            assertTrue("with no supporting source the empty state must show",
                    empty.visible.peek());
            assertEquals("当前音源不支持歌单搜索", property(empty, "title").peek());
        });
    }

    @Test
    public void supportedSourcesShowNoResultsCopy() throws Exception {
        withScene((view, player) -> {
            player.playlistSearchSupported.set(true);
            setQuery(view, "周杰伦");
            settle(view);

            Item empty = view.findByObjectName("searchPlaylistEmpty");
            assertNotNull(empty);
            assertTrue(empty.visible.peek());
            assertEquals("没有找到歌单", property(empty, "title").peek());
        });
    }

    @Test
    public void nothingIsShownOutsidePlaylistMode() throws Exception {
        withScene((view, player) -> {
            player.playlistSearchSupported.set(false);
            player.setSearchMode("song");
            setQuery(view, "周杰伦");
            settle(view);

            Item empty = view.findByObjectName("searchPlaylistEmpty");
            assertNotNull(empty);
            assertFalse("song mode must not show the playlist empty state",
                    empty.visible.peek());
        });
    }

    private static void setQuery(QmlView view, String text) throws Exception {
        Item field = view.findByObjectName("searchQuery");
        assertNotNull("searchQuery", field);
        property(field, "text").set(text);
    }

    private void withScene(SceneCheck check) throws Exception {
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        PlayerController player = null;
        QmlView view = null;
        try {
            Path base = temporary.newFolder().toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            AudioBackend backend = (AudioBackend) Proxy.newProxyInstance(
                    AudioBackend.class.getClassLoader(), new Class<?>[]{AudioBackend.class},
                    (proxy, method, args) -> {
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                    });
            player = new PlayerController(backend, track -> { });
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            ClasspathResourceLoader resources = new ClasspathResourceLoader();
            view = QmlView.withStockTypes(new QmlEngine()).resources(resources)
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance());
            view.load(rebaseImports(new String(
                    resources.load("pages/SearchPage.qml"), StandardCharsets.UTF_8)));
            settle(view);
            player.setSearchMode("playlist");
            settle(view);
            check.run(view, player);
        } finally {
            if (view != null) {
                try { view.dispose(); } catch (Throwable ignored) { }
            }
            if (player != null) {
                try { player.shutdownAsync().join(); } catch (Throwable ignored) { }
            }
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private static String rebaseImports(String source) {
        return source.replace("import \".\"", "import \"pages\"")
                     .replace("import \"../components\"", "import \"components\"");
    }

    @SuppressWarnings("unchecked")
    private static <T> Property<T> property(Item item, String name) throws Exception {
        return (Property<T>) item.getClass().getField(name).get(item);
    }

    private static void settle(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            for (int i = 0; i < 3; i++) {
                queue.flush();
                view.renderer().layoutOnly(view.root());
            }
            queue.flush();
        } finally {
            queue.uninstall();
        }
    }

    @FunctionalInterface
    private interface SceneCheck {
        void run(QmlView view, PlayerController player) throws Exception;
    }
}
