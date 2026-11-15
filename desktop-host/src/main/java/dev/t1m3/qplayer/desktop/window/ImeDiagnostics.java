package dev.t1m3.qplayer.desktop.window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * IME 现场诊断（精简版，2026-11-05 随 {@code ImePositioner} 一起从 v1.8.5 搬回）。
 *
 * <p>原版是 1132 行的完整消息网抓取器，绑在旧的消息钩子与 qml4j 0.2.33 的
 * {@code Flickable}/{@code TextInput} 上；本版只保留 {@link ImePositioner} /
 * {@link ImeBridge} / {@code DesktopWindow} 实际调用到的入口，签名逐一对齐，
 * 因此那两个类可以按 v1.8.5 原文使用，不需要改动一行逻辑。
 *
 * <p>默认完全关闭：只有 {@code -Dqplayer.ime.debug=true} 时才写
 * {@code <data.dir>/logs/ime-debug.log}。真机上候选窗位置再出问题时，加上这个
 * 参数打一次中文，日志里会看到每一次 {@code apply}/{@code skip} 的理由、
 * 写入的矩形、caret 的建毁，足够定位。
 */
final class ImeDiagnostics {

    private static final boolean ENABLED = Boolean.getBoolean("qplayer.ime.debug");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static boolean failed;

    private ImeDiagnostics() {}

    // ------------------------------------------------------------------
    // Lifecycle

    /** 原版在这里装消息钩子。精简版不装：{@code ImePositioner} 自己就是那个钩子。 */
    static void install(com.sun.jna.Pointer hwnd) {
        if (!ENABLED) return;
        line("DIAG install hwnd=" + hex(hwnd));
    }

    /** 原版在这里钩 GLFW 的模式切换。精简版只记一行。 */
    static void installMode(long glfwHandle) {
        if (!ENABLED) return;
        line("DIAG installMode glfw=0x" + Long.toHexString(glfwHandle));
    }

    /** 事件循环每轮调用；原版在这里做心跳与窗口枚举。 */
    static void tick() {
        // 精简版没有周期性内容可写，保留入口以免调用方分叉。
    }

    // ------------------------------------------------------------------
    // Positioner

    static void logPosition(long seq, String reason, String detail) {
        if (!ENABLED) return;
        line("POSITION #" + seq + " reason=" + reason + " " + detail);
    }

    static void logCaret(String action, boolean ok, int x, int y, String extra) {
        if (!ENABLED) return;
        line("CARET " + action + " ok=" + (ok ? 1 : 0) + " at " + x + "," + y
                + (extra == null || extra.isEmpty() ? "" : " " + extra));
    }

    static void logReconnect(int round, int total, boolean ok, String detail) {
        if (!ENABLED) return;
        line("RECONNECT " + round + "/" + total + " ok=" + (ok ? 1 : 0) + " " + detail);
    }

    // ------------------------------------------------------------------
    // Bridge / window

    static void logFocus(boolean focused) {
        if (!ENABLED) return;
        line("FOCUS " + focused);
    }

    static void logPreedit(String text, int caret, boolean started) {
        if (!ENABLED) return;
        line("PREEDIT started=" + started + " caret=" + caret + " text=\"" + text + "\"");
    }

    static void logRectArrive(int x, int y, int w, int h) {
        if (!ENABLED) return;
        line("RECT arrive " + x + "," + y + " " + w + "x" + h);
    }

    static void logRectSuppressed(int x, int y, int w, int h) {
        if (!ENABLED) return;
        line("RECT suppressed " + x + "," + y + " " + w + "x" + h);
    }

    static void logRectPush(int x, int y, int w, int h) {
        if (!ENABLED) return;
        line("RECT push " + x + "," + y + " " + w + "x" + h);
    }

    static void logCoordinateTransform(float logicalX, float logicalY, float logicalW, float logicalH,
                                       float scale, int px, int py, int pw, int ph) {
        if (!ENABLED) return;
        line("SCALE " + scale + " logical=" + logicalX + "," + logicalY + " "
                + logicalW + "x" + logicalH + " px=" + px + "," + py + " " + pw + "x" + ph);
    }

    // ------------------------------------------------------------------

    private static synchronized void line(String text) {
        if (failed) return;
        try {
            Path path = logPath();
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, LocalDateTime.now().format(TIME) + " " + text + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException error) {
            // 诊断失败绝不能影响界面：写一次就闭嘴。
            failed = true;
        }
    }

    private static Path logPath() {
        String dir = System.getProperty("qplayer.data.dir");
        if (dir == null || dir.isEmpty()) dir = "data";
        return Paths.get(dir, "logs", "ime-debug.log");
    }

    private static String hex(com.sun.jna.Pointer p) {
        return p == null ? "null" : "0x" + Long.toHexString(com.sun.jna.Pointer.nativeValue(p));
    }
}
