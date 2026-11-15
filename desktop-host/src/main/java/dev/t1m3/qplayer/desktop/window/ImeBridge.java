package dev.t1m3.qplayer.desktop.window;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWPreeditCallback;
import org.lwjgl.system.MemoryUtil;

import dev.t1m3.qplayer.util.Logger;

/**
 * Windows-only: routes input-method composition into the QML text editor.
 *
 * <p>GLFW 3.5 (the native library LWJGL 3.4 ships) implements the IME itself.
 * With {@code GLFW_IME} on it clears {@code ISC_SHOWUICOMPOSITIONWINDOW}, so the
 * input method stops drawing its own composition window; it reports the
 * composing run through the preedit callback and commits the final characters
 * through the ordinary char callback. The host's only job is to say where the
 * composing caret is, which GLFW turns into the composition rectangle and the
 * candidate window's exclusion rect.
 *
 * <p>The earlier implementation (WinImeHook) subclassed the window procedure to
 * move the system-drawn composition window onto the field. That can only ever
 * produce a floating window lying on top of the editor, never text inside it.
 */
final class ImeBridge {

    // Client-area pixels of the composing caret, pushed by the render thread once
    // per frame. Only the GLFW main thread may talk to imm32, so the numbers are
    // handed over and applied there.
    private volatile int rectX;
    private volatile int rectY;
    private volatile int rectWidth;
    private volatile int rectHeight;
    private volatile boolean rectDirty;
    private volatile boolean rectValid;

    private volatile boolean composing;

    // Invisible focus cycle (see ImePositioner.reconnect). On by default: two
    // rounds after the first focus is what makes the IME hand out a usable
    // candidate window on this machine, and a real focus change has side
    // effects (taskbar / IME indicator flash, QML focus callbacks, dropped
    // keys) that this avoids entirely. Set -Dqplayer.ime.reconnect=0 to turn
    // it off, or a different round count to experiment.
    private static final int RECONNECT_ROUNDS =
            Integer.getInteger("qplayer.ime.reconnect", 2);
    private static final long RECONNECT_DELAY_MS =
            Long.getLong("qplayer.ime.reconnect.delay", 600L);
    /** Minimum spacing between rounds. The event loop can wake far more often
     *  than its 50 ms timeout (any glfwPostEmptyEvent does it), and two detach/
     *  reattach cycles 5 ms apart are far too fast for the IME to finish
     *  re-initialising in between. */
    private static final long RECONNECT_GAP_MS = 150L;
    private static final int RECONNECT_MAX_ATTEMPTS = 200;

    private boolean reconnectArmed;
    private long focusAtMs;
    private long lastRoundAtMs;
    private int reconnectRound;
    private int reconnectAttempts;

    private DesktopWindow window;
    private GLFWPreeditCallback callback;   // strong ref: LWJGL callbacks must not be GC'd

    void install(DesktopWindow window) {
        this.window = window;
        long handle = window.window();
        // Before GLFW_IME is switched on: the positioner subclasses the window
        // procedure so it sees every WM_IME_* from the first composition on.
        ImePositioner.install(window.nativeHwnd());
        GLFW.glfwSetInputMode(handle, GLFW.GLFW_IME, GLFW.GLFW_TRUE);
        ImeDiagnostics.installMode(handle);
        callback = GLFW.glfwSetPreeditCallback(handle,
                (win, count, textAddress, blockCount, sizesAddress, focusedBlock, caret) ->
                        onPreedit(count, textAddress, caret));
        Logger.info("IME composition enabled (GLFW preedit callback installed)");
    }

    /** Runs on the GLFW main thread, inside the window procedure. */
    private void onPreedit(int count, long address, int caret) {
        String text = readCodePoints(address, count);
        boolean started = !composing && !text.isEmpty();
        composing = !text.isEmpty();
        int caretIndex = utf16Index(text, caret);
        ImeDiagnostics.logPreedit(text, caretIndex, started);
        DesktopWindow target = window;
        if (target != null) target.postRenderTask(() -> target.applyImePreedit(text, caretIndex, started));
    }

    /** Publish the composing caret rectangle (client-area pixels) and wake the
     *  event loop, which is the thread allowed to talk to imm32. */
    void setCursorRectangle(int x, int y, int width, int height) {
        if (rectValid && x == rectX && y == rectY && width == rectWidth && height == rectHeight) {
            ImeDiagnostics.logRectSuppressed(x, y, width, height);
            return;
        }
        ImeDiagnostics.logRectArrive(x, y, width, height);
        rectX = x;
        rectY = y;
        rectWidth = width;
        rectHeight = height;
        rectValid = true;
        rectDirty = true;
        GLFW.glfwPostEmptyEvent();
    }

    /** Called on the GLFW main thread: applies the latest rectangle the render
     *  thread published.
     *
     *  <p>Two steps on purpose. {@code glfwSetPreeditCursorRectangle} keeps
     *  GLFW's own preedit state in sync (it is also what re-applies the forms on
     *  focus regain), then {@link ImePositioner} writes the full positioning
     *  path -- {@code CFS_CANDIDATEPOS} plus the system caret -- immediately
     *  after it. Doing it here, in the same thread and message, makes the
     *  positioner the last writer and leaves exactly one authoritative
     *  candidate position. */
    void flushCursorRectangle() {
        if (!rectDirty) return;
        rectDirty = false;
        DesktopWindow target = window;
        if (target == null) return;
        int w = Math.max(1, rectWidth);
        int h = Math.max(1, rectHeight);
        ImePositioner.setRectangle(rectX, rectY, w, h);
        ImeDiagnostics.logRectPush(rectX, rectY, w, h);
        GLFW.glfwSetPreeditCursorRectangle(target.window(), rectX, rectY, w, h);
        ImePositioner.apply("rect-flush");
    }

    /** 窗口即将销毁/重建：放掉回调强引用，避免再往旧 HWND 投递组合串。 */
    void dispose() {
        window = null;
        callback = null;
    }

    boolean composing() { return composing; }

    /** Told by the window's focus callback (GLFW main thread). Arms the
     *  invisible focus cycle the first time the window is focused, then leaves
     *  it to {@link #tick()}. */
    void onWindowFocus(boolean focused) {
        if (!focused || RECONNECT_ROUNDS <= 0 || reconnectArmed) return;
        reconnectArmed = true;
        focusAtMs = System.currentTimeMillis();
        Logger.info("IME reconnect armed: {} round(s), {} ms after first focus",
                RECONNECT_ROUNDS, RECONNECT_DELAY_MS);
    }

    /** Runs once per event-loop turn on the GLFW main thread. Performs at most
     *  one reconnect round per turn so the message pump gets to process
     *  whatever the detach queued before the next one. */
    void tick() {
        if (!reconnectArmed || reconnectRound >= RECONNECT_ROUNDS) return;
        long now = System.currentTimeMillis();
        if (now - focusAtMs < RECONNECT_DELAY_MS) return;
        if (lastRoundAtMs != 0L && now - lastRoundAtMs < RECONNECT_GAP_MS) return;
        if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
            Logger.info("IME reconnect abandoned after {} attempts", reconnectAttempts);
            reconnectRound = RECONNECT_ROUNDS;
            return;
        }
        reconnectAttempts++;
        if (ImePositioner.reconnect(reconnectRound + 1, RECONNECT_ROUNDS)) {
            reconnectRound++;
            reconnectAttempts = 0;
            lastRoundAtMs = now;
            Logger.info("IME reconnect round {}/{} done", reconnectRound, RECONNECT_ROUNDS);
        }
    }

    /** The preedit callback hands over UTF-32 code points, not UTF-16 units. */
    private static String readCodePoints(long address, int count) {
        if (address == 0L || count <= 0) return "";
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.appendCodePoint(MemoryUtil.memGetInt(address + (long) i * 4));
        }
        return text.toString();
    }

    /** The callback's caret is a code-point index; the editor counts UTF-16 units. */
    private static int utf16Index(String text, int codePointIndex) {
        int codePoints = text.codePointCount(0, text.length());
        return text.offsetByCodePoints(0, Math.max(0, Math.min(codePointIndex, codePoints)));
    }
}
