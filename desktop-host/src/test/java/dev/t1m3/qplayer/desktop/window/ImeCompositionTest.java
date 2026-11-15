package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.bridge.WindowChromeStub;
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
import io.github.timer_err.qml4j.render.items.input.TextInput;
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
 * 本地改动（框内预编辑）：组合态必须对输入框自身的布局可见、对它的 text 不可见。
 *
 * <p>搜索框在拼音输入过程中要看起来「有内容」（标签上浮、清空按钮出现），但不能因为
 * 每个中间态触发一次查询。
 */
public final class ImeCompositionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void compositionCountsAsContentWithoutTouchingText() throws Exception {
        withScene((view, player) -> {
            TextInput input = searchInput(view);
            assertNotNull("search field input", input);
            assertEquals("", input.text());

            input.preeditText.set("nihao");
            input.preeditCursor.set(5);
            settle(view);

            assertEquals("composition must not reach text", "", input.text());
            assertEquals("nihao", input.preeditText.peek());
            assertTrue("the field must render as filled while composing",
                    fieldContentFlag(view));

            input.preeditText.set("");
            input.preeditCursor.set(0);
            settle(view);

            assertFalse("clearing the composition empties the field again",
                    fieldContentFlag(view));
        });
    }

    @Test
    public void committedCharactersLandAfterTheCompositionIsDropped() throws Exception {
        withScene((view, player) -> {
            TextInput input = searchInput(view);
            assertNotNull("search field input", input);

            input.setCursorPosition(0);
            view.setFocus(input);
            input.preeditText.set("nihao");
            input.preeditCursor.set(5);
            settle(view);
            // 宿主在 char 回调里做的事：先丢掉组合，再把提交的字符交给编辑器。
            input.preeditText.set("");
            view.dispatchKey(0, "你好", true);
            settle(view);

            assertEquals("你好", input.text());
            assertEquals("", input.preeditText.peek());
            assertEquals("caret must follow the committed run", 2, input.cursorPosition());
        });
    }

    @Test
    public void compositionReplacesTheSelectedText() throws Exception {
        withScene((view, player) -> {
            TextInput input = searchInput(view);
            assertNotNull("search field input", input);
            input.setText("abcdef");
            input.setCursorPosition(6);
            input.setSelectionRange(2, 5);

            DesktopWindow.beginComposition(input);

            assertEquals("the selected run is what the composition replaces",
                    "abf", input.text());
            assertEquals("the composing caret sits where the selection started",
                    2, input.cursorPosition());
            assertEquals(2, input.selectionStart());
            assertEquals(2, input.selectionEnd());
        });
    }

    private static boolean fieldContentFlag(QmlView view) throws Exception {
        Item field = view.findByObjectName("searchQuery");
        assertNotNull("searchQuery", field);
        return (Boolean) property(field, "hasContent").peek();
    }

    private static TextInput searchInput(QmlView view) {
        Item field = view.findByObjectName("searchQuery");
        if (field == null) return null;
        Item input = findIn(field, "miuixTextFieldInput");
        return input instanceof TextInput ? (TextInput) input : null;
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
            player.sourceSetupPending.set(false);
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            ClasspathResourceLoader resources = new ClasspathResourceLoader();
            view = QmlView.withStockTypes(new QmlEngine()).resources(resources)
                    .context("player", player).context("settings", settings)
                    .context("i18n", I18n.instance())
                    .context("hostWindow", new WindowChromeStub());
            view.load(new String(resources.load("Main.qml"), StandardCharsets.UTF_8));
            view.root().height.set(806);
            view.root().width.set(1100);
            settle(view);
            // 搜索框在惰性加载的页面上（page 1，由 searchLoaded 控制）。
            property(view.root(), "searchLoaded").set(true);
            property(view.root(), "page").set(1);
            settle(view);
            check.run(view, player);
        } finally {
            if (view != null) view.dispose();
            if (player != null) {
                try { player.shutdownAsync().join(); } catch (Throwable ignored) { }
            }
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Property<T> property(Item item, String name) throws Exception {
        return (Property<T>) item.getClass().getField(name).get(item);
    }

    private static Item findIn(Item root, String objectName) {
        if (objectName.equals(root.objectName.peek())) return root;
        for (Item child : root.children) {
            Item hit = findIn(child, objectName);
            if (hit != null) return hit;
        }
        return null;
    }

    private static void settle(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            long animationStart = System.nanoTime();
            view.tickAnimations(animationStart);
            view.tickAnimations(animationStart + 1_000_000_000L);
            for (int i = 0; i < 4; i++) {
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
