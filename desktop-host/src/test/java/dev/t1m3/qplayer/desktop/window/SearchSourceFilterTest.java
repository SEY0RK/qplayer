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
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SearchPage's source filter row.
 *
 * <p>qml4j gives QML no compile-time checking: a wrong property name or a binding
 * to a method that does not exist renders a silently dead control, and this page is
 * only reached through a {@code Loader} that stays inactive until the user opens
 * search — so neither the app's startup nor a normal smoke run ever parses it. That
 * is exactly how the row went missing once before, hence a scene test that drives
 * the host property it is bound to.
 */
public class SearchSourceFilterTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void theSourceFilterRowShowsOnlyWhenThereIsMoreThanOneSource() throws Exception {
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

            Item bar = view.findByObjectName("searchSourceBar");
            assertNotNull("SearchPage must carry the source-filter row", bar);

            // One source (here: none) means there is nothing to switch between, so
            // the row must not take up a line of the page.
            assertFalse("with no per-source buckets the row must stay hidden",
                    bar.visible.peek());

            // Two or more sources is exactly when it earns its space.
            player.searchScopeTabs.set(Arrays.asList(I18n.tr("search.scope.all"), "网易云"));
            settle(view);
            assertTrue("with two sources the row must appear", bar.visible.peek());

            // ...and collapse again when the search narrows back to a single source.
            player.searchScopeTabs.set(Collections.<String>emptyList());
            settle(view);
            assertFalse("the row must hide again when the sources collapse",
                    bar.visible.peek());
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

    /** {@code load(String)} parses against the resource root, not the file's own
     *  directory, so the page's relative imports have to be rebased — otherwise
     *  {@code import "."} resolves to the root and the sibling components (TabRow,
     *  SongRow, …) are not found. */
    private static String rebaseImports(String source) {
        return source.replace("import \".\"", "import \"pages\"")
                     .replace("import \"../components\"", "import \"components\"");
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
}
