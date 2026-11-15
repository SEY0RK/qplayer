package dev.t1m3.qplayer.desktop.window;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import dev.t1m3.qplayer.util.Logger;
import org.lwjgl.system.Platform;

import java.util.Arrays;
import java.util.List;

/**
 * Windows-only: the single place that tells the input method where the
 * composing caret is.
 *
 * <p>GLFW 3.5.1 (the IME build LWJGL 3.4.3 ships) only writes
 * {@code ImmSetCompositionWindow(CFS_RECT)} and
 * {@code ImmSetCandidateWindow(CFS_EXCLUDE)} -- see
 * {@code _glfwUpdatePreeditCursorRectangleWin32} in the clear-code IME branch.
 * It never sets {@code CFS_CANDIDATEPOS} and never owns a system caret. Chinese
 * IMEs driven through imm32 (CUAS mode, which is what a non-TSF app gets) do
 * not always honour the candidate form: Microsoft Pinyin falls back to
 * {@code GetCaretPos()} when it decides to. With no caret in the thread that
 * call yields the default position, so the candidate list is created off the
 * caret or not shown at all -- while the composition still works, which is
 * exactly the reported symptom (digits select, candidate bar missing).
 *
 * <p>Chromium solves this in {@code IMM32Manager}: for Chinese/Japanese it
 * creates a 1x1 caret that is never shown, sets {@code CFS_CANDIDATEPOS} and
 * {@code SetCaretPos()} next to the caret rectangle, then keeps the
 * {@code CFS_EXCLUDE} form for IMEs that use it. This class ports only that
 * positioning mechanism.
 *
 * <p>Ordering: the window procedure subclass chains to the previous procedure
 * (GLFW, plus the diagnostics hook when enabled) <em>first</em> and applies the
 * forms <em>after</em> it returns. GLFW therefore cannot overwrite what we
 * write in the same message, which keeps this the last writer on the thread.
 *
 * <p>Threading: every entry point that touches imm32 or the caret runs on the
 * thread that owns the window -- the window procedure or the GLFW event loop.
 * The render thread only publishes the rectangle through
 * {@link #setRectangle(int, int, int, int)}.
 *
 * <p>Coordinates: client-area pixels of the window, top-left origin. QML scene
 * coordinates are logical units and are scaled by the caller
 * ({@code DesktopWindow.setImeCursorRectangle}) before they arrive here.
 */
final class ImePositioner {

    private static final int GWL_WNDPROC = -4;

    private static final int WM_DESTROY = 0x0002;
    private static final int WM_MOVE = 0x0003;
    private static final int WM_SIZE = 0x0005;
    private static final int WM_SETFOCUS = 0x0007;
    private static final int WM_KILLFOCUS = 0x0008;
    private static final int WM_NCDESTROY = 0x0082;
    private static final int WM_IME_STARTCOMPOSITION = 0x010D;
    private static final int WM_IME_SETCONTEXT = 0x0281;
    private static final int WM_IME_NOTIFY = 0x0282;
    private static final int WM_DPICHANGED = 0x02E0;

    private static final int SIZE_MINIMIZED = 1;

    // WM_IME_NOTIFY commands we care about.
    private static final int IMN_OPENCANDIDATE = 0x0005;
    private static final int IMN_CHANGECANDIDATE = 0x0006;
    private static final int IMN_SETCANDIDATEPOS = 0x0009;

    // CANDIDATEFORM.dwStyle
    private static final int CFS_RECT = 0x0001;
    private static final int CFS_CANDIDATEPOS = 0x0040;
    private static final int CFS_EXCLUDE = 0x0080;

    // PRIMARYLANGID values that get a system caret (Chromium's rule).
    private static final int LANG_CHINESE = 0x04;
    private static final int LANG_JAPANESE = 0x11;

    private static Pointer hwnd;
    private static long prevWndProc;
    private static WndProc hook;
    private static volatile boolean installed;

    // Client-area pixels of the composing caret, published by the event loop.
    private static volatile int rectX;
    private static volatile int rectY;
    private static volatile int rectW = 1;
    private static volatile int rectH = 1;
    private static volatile boolean rectValid;

    // The system caret is a per-thread resource; only touch it when this window
    // created it and still has focus.
    private static boolean caretCreated;
    private static boolean applying;

    private static long lastSeq;
    private static volatile String lastSummary = "none";

    private ImePositioner() {
    }

    // ------------------------------------------------------------------
    // Lifecycle

    /** Installs the window-procedure subclass. Call on the window's thread,
     *  after the HWND exists and before GLFW_IME is switched on. */
    static void install(Pointer hWnd) {
        if (hWnd == null) return;
        if (Platform.get() != Platform.WINDOWS) return;
        // A rebuilt window (OpenGL fallback) brings a new HWND; drop the old hook.
        if (installed && hwnd != null
                && Pointer.nativeValue(hwnd) != Pointer.nativeValue(hWnd)) {
            uninstall();
        }
        if (installed) return;
        hwnd = hWnd;
        hook = (h, msg, wParam, lParam) -> onMessage(h, msg, wParam, lParam);
        try {
            prevWndProc = U32.I.SetWindowLongPtrW(hWnd, GWL_WNDPROC, hook);
            installed = true;
            Logger.info("IME positioning hook installed (previous wndproc 0x{})",
                    Long.toHexString(prevWndProc));
        } catch (Throwable t) {
            Logger.warn("IME positioning hook not installed: {}", t.toString());
        }
    }

    /** Destroys the caret and restores the previous window procedure. */
    static void uninstall() {
        if (!installed) return;
        destroyCaret("uninstall");
        installed = false;
        try {
            if (hwnd != null && prevWndProc != 0L) {
                U32.I.SetWindowLongPtrW(hwnd, GWL_WNDPROC, Pointer.createConstant(prevWndProc));
            }
        } catch (Throwable ignored) {
        }
        hwnd = null;
        prevWndProc = 0L;
    }

    // ------------------------------------------------------------------
    // Rectangle

    /** Publishes the composing caret in client-area pixels. Runs on the GLFW
     *  main thread; the values are applied by the next {@link #apply}. */
    static void setRectangle(int x, int y, int width, int height) {
        rectX = x;
        rectY = y;
        rectW = Math.max(1, width);
        rectH = Math.max(1, height);
        rectValid = true;
    }

    // ------------------------------------------------------------------
    // Window procedure

    private static long onMessage(Pointer h, int msg, long wParam, long lParam) {
        long result;
        try {
            result = prevWndProc != 0L
                    ? U32.I.CallWindowProcW(prevWndProc, h, msg, wParam, lParam)
                    : U32.I.DefWindowProcW(h, msg, wParam, lParam);
        } catch (Throwable t) {
            Logger.warn("IME wndproc chain failed (msg=0x{}): {}",
                    Integer.toHexString(msg), t.toString());
            result = 0L;
        }
        try {
            react(h, msg, wParam, lParam);
        } catch (Throwable t) {
            Logger.warn("IME positioning failed (msg=0x{}): {}",
                    Integer.toHexString(msg), t.toString());
        }
        return result;
    }

    private static void react(Pointer h, int msg, long wParam, long lParam) {
        switch (msg) {
            case WM_IME_SETCONTEXT:
                // wParam tells whether the IME is active for this window; this is
                // where Chromium creates/destroys its caret too.
                if (wParam != 0) {
                    ensureCaret();
                    apply("ime-setcontext-on");
                } else {
                    destroyCaret("ime-setcontext-off");
                }
                break;
            case WM_SETFOCUS:
                ensureCaret();
                apply("setfocus");
                break;
            case WM_KILLFOCUS:
                destroyCaret("killfocus");
                break;
            case WM_SIZE:
                if ((wParam & 0xFFFFL) == SIZE_MINIMIZED) destroyCaret("minimize");
                else apply("resize");
                break;
            case WM_MOVE:
            case WM_DPICHANGED:
                apply("move");
                break;
            case WM_IME_STARTCOMPOSITION:
                // The IME is about to open its composition/candidate UI: this is
                // the last moment a position can be handed over before it reads
                // one. Chromium's OnImeStartComposition does the same.
                ensureCaret();
                apply("ime-startcomposition");
                break;
            case WM_IME_NOTIFY: {
                int cmd = (int) (wParam & 0xFFFFL);
                if (cmd == IMN_OPENCANDIDATE) {
                    ensureCaret();
                    apply("imn-opencandidate");
                } else if (cmd == IMN_SETCANDIDATEPOS) {
                    apply("imn-setcandidatepos");
                } else if (cmd == IMN_CHANGECANDIDATE) {
                    apply("imn-changecandidate");
                }
                break;
            }
            case WM_DESTROY:
            case WM_NCDESTROY:
                destroyCaret("destroy");
                installed = false;
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // System caret

    /** Creates the hidden 1x1 caret Chromium uses for Chinese/Japanese IMEs.
     *  Never calls {@code ShowCaret}, so nothing is drawn on screen. */
    private static void ensureCaret() {
        if (!installed || hwnd == null) return;
        if (caretCreated) return;
        // Every early return below is a silent one, and they are the whole
        // difference between "the caret exists when the candidate window opens"
        // and "it does not". Log them: the focus-mismatch case is the one that
        // fires on WM_IME_SETCONTEXT, which the system delivers *before*
        // WM_SETFOCUS, so the caret cannot be created on that message.
        Pointer focus = U32.I.GetFocus();
        if (focus == null || Pointer.nativeValue(focus) != Pointer.nativeValue(hwnd)) {
            ImeDiagnostics.logCaret("skip", false, 0, 0,
                    "reason=focus-mismatch focus=" + hex(focus) + " hwnd=" + hex(hwnd));
            return;
        }
        if (!isCjkLayout()) {
            ImeDiagnostics.logCaret("skip", false, 0, 0,
                    "reason=non-cjk-layout lang=0x" + Integer.toHexString(langId()));
            return;
        }
        boolean ok = U32.I.CreateCaret(hwnd, null, 1, 1);
        caretCreated = ok;
        ImeDiagnostics.logCaret(ok ? "create" : "create-failed", ok, 0, 0, "lang=" + langId());
    }

    private static void destroyCaret(String reason) {
        if (!caretCreated) {
            ImeDiagnostics.logCaret("skip", false, 0, 0,
                    "reason=no-caret-to-destroy why=" + reason);
            return;
        }
        boolean ok = U32.I.DestroyCaret();
        caretCreated = false;
        ImeDiagnostics.logCaret("destroy", ok, 0, 0, "reason=" + reason);
    }

    // ------------------------------------------------------------------
    // Positioning

    /** Writes the caret position into imm32 for the current composition.
     *
     *  <p>Mirrors Chromium's {@code IMM32Manager::MoveImeWindow}: for Chinese
     *  the candidate form is set to {@code CFS_CANDIDATEPOS} first, the system
     *  caret (when present) is moved onto the caret, and the
     *  {@code CFS_EXCLUDE} form is written last so IMEs that position from the
     *  exclusion rectangle get the same point. The composition rectangle stays
     *  {@code CFS_RECT} so the IME keeps knowing the caret box even though its
     *  composition window is suppressed.
     *
     *  <p>Runs on the window thread. The read-back is logged, not trusted: a
     *  TRUE return only means imm32 stored the form. */
    static void apply(String reason) {
        if (!installed || hwnd == null) return;
        // A rectangle is published only once the render thread has painted a
        // focused text field. Every IME message that arrives before that --
        // including the WM_IME_SETCONTEXT/WM_SETFOCUS pair at startup -- is
        // dropped here, which is why "no POSITION line" in the log means
        // "nothing to position yet", not "apply was never called".
        if (!rectValid) {
            ImeDiagnostics.logPosition(++lastSeq, reason,
                    "skipped: no rect published yet (render thread has not seen a focused text field)");
            return;
        }
        // imm32 can deliver a notification back into this window procedure
        // while the forms are being written; one pass is enough and the
        // recursive one would only fight the first.
        if (applying) return;
        applying = true;
        Pointer imc = null;
        try {
            if (U32.I.IsIconic(hwnd)) {
                ImeDiagnostics.logPosition(++lastSeq, reason, "skipped minimized");
                return;
            }
            imc = Imm32.I.ImmGetContext(hwnd);
            if (imc == null) {
                lastSummary = "#" + (++lastSeq) + " " + reason + " no-himc";
                ImeDiagnostics.logPosition(lastSeq, reason, "skipped: ImmGetContext=null");
                return;
            }

            // JNA hands out a fresh Pointer wrapper per call, so "==" compares
            // object identity and is false even when both point at the same
            // HWND. That made every single apply() bail out as "no focus"
            // (72/72 in the 2026-10-26 field log) and the candidate form was
            // never written. Compare native addresses instead.
            Pointer focus = U32.I.GetFocus();
            boolean focused = focus != null
                    && Pointer.nativeValue(focus) == Pointer.nativeValue(hwnd);
            if (!focused) {
                // Chromium returns early here too: a stale rectangle from a
                // renderer frame must not move an IME owned by another window.
                lastSummary = "#" + (++lastSeq) + " " + reason + " no-focus";
                ImeDiagnostics.logPosition(lastSeq, reason, "skipped: GetFocus()!=hwnd"
                        + " hwnd=" + hex(hwnd) + " focus=" + hex(focus));
                return;
            }

            long seq = ++lastSeq;
            int clientW = 0;
            int clientH = 0;
            RECT client = new RECT();
            if (U32.I.GetClientRect(hwnd, client)) {
                clientW = client.right - client.left;
                clientH = client.bottom - client.top;
            }
            int x = rectX;
            int y = rectY;
            int w = Math.max(1, rectW);
            int h = Math.max(1, rectH);
            String clamp = "";
            if (clientW > 0 && clientH > 0) {
                int cx = Math.max(0, Math.min(x, clientW - 1));
                int cy = Math.max(0, Math.min(y, clientH - 1));
                if (cx != x || cy != y) {
                    clamp = " clampedFrom=" + x + "," + y + " client=" + clientW + "x" + clientH;
                    x = cx;
                    y = cy;
                }
            }

            // What imm32 held before this call: if the candidate window opened
            // with a default position, this is where it shows up.
            CANDIDATEFORM pre = new CANDIDATEFORM();
            boolean preOk = Imm32.I.ImmGetCandidateWindow(imc, 0, pre);

            int lang = langId();
            int primary = lang & 0x3FF;

            boolean candPosOk = false;
            if (primary == LANG_CHINESE) {
                CANDIDATEFORM candPos = new CANDIDATEFORM();
                candPos.dwIndex = 0;
                candPos.dwStyle = CFS_CANDIDATEPOS;
                candPos.ptCurrentPos.x = x;
                candPos.ptCurrentPos.y = y;
                candPos.rcArea.left = 0;
                candPos.rcArea.top = 0;
                candPos.rcArea.right = 0;
                candPos.rcArea.bottom = 0;
                candPosOk = Imm32.I.ImmSetCandidateWindow(imc, candPos);
            }

            // The caret is what CUAS Chinese IMEs fall back to.
            boolean caretOk = false;
            if (caretCreated) {
                int caretY = primary == LANG_JAPANESE ? y + h : y;
                caretOk = U32.I.SetCaretPos(x, caretY);
            }

            COMPOSITIONFORM composition = new COMPOSITIONFORM();
            composition.dwStyle = CFS_RECT;
            composition.ptCurrentPos.x = x;
            composition.ptCurrentPos.y = y;
            composition.rcArea.left = x;
            composition.rcArea.top = y;
            composition.rcArea.right = x + w;
            composition.rcArea.bottom = y + h;
            boolean compositionOk = Imm32.I.ImmSetCompositionWindow(imc, composition);

            // Keep the exclusion rectangle: GLFW sets it and some IMEs (Japanese,
            // Korean) position from it. Written last, so it is what imm32 stores.
            CANDIDATEFORM exclude = new CANDIDATEFORM();
            exclude.dwIndex = 0;
            exclude.dwStyle = CFS_EXCLUDE;
            exclude.ptCurrentPos.x = x;
            exclude.ptCurrentPos.y = y;
            exclude.rcArea.left = x;
            exclude.rcArea.top = y;
            exclude.rcArea.right = x + w;
            exclude.rcArea.bottom = y + h;
            boolean excludeOk = Imm32.I.ImmSetCandidateWindow(imc, exclude);

            CANDIDATEFORM post = new CANDIDATEFORM();
            boolean postOk = Imm32.I.ImmGetCandidateWindow(imc, 0, post);

            POINT caretPos = new POINT();
            boolean caretReadOk = caretCreated && U32.I.GetCaretPos(caretPos);

            lastSummary = "#" + seq + " " + reason
                    + " style=" + (postOk ? styleName(post.dwStyle) : "?")
                    + " pos=" + x + "," + y
                    + " caret=" + (caretCreated ? (caretReadOk
                    ? caretPos.x + "," + caretPos.y : "unreadable") : "none");

            ImeDiagnostics.logPosition(seq, reason, "hwnd=0x"
                    + Long.toHexString(Pointer.nativeValue(hwnd))
                    + " focus=0x" + hex(U32.I.GetFocus())
                    + " fg=0x" + hex(U32.I.GetForegroundWindow())
                    + " enabled=" + (U32.I.IsWindowEnabled(hwnd) ? 1 : 0)
                    + " lang=0x" + Integer.toHexString(lang)
                    + " open=" + (Imm32.I.ImmGetOpenStatus(imc) ? 1 : 0)
                    + " client=" + clientW + "x" + clientH
                    + " rect=" + rectX + "," + rectY + "," + rectW + "," + rectH
                    + clamp
                    + " pre=" + (preOk ? form(pre) : "none")
                    + " candPos=" + (primary == LANG_CHINESE
                    ? "(" + x + "," + y + ")" + (candPosOk ? " ok" : " fail") : "n/a")
                    + " exclude=(" + x + "," + y + "," + (x + w) + "," + (y + h) + ")"
                    + (excludeOk ? " ok" : " fail")
                    + " composition=" + (compositionOk ? "ok" : "fail")
                    + " caret=" + (caretCreated ? "created" : "none")
                    + (caretCreated ? (caretOk ? " moved" : " move-failed") : "")
                    + (caretReadOk ? " at " + caretPos.x + "," + caretPos.y : "")
                    + " post=" + (postOk ? form(post) : "none"));
        } catch (Throwable t) {
            ImeDiagnostics.logPosition(++lastSeq, reason, "error: " + t);
        } finally {
            applying = false;
            if (imc != null) {
                try {
                    Imm32.I.ImmReleaseContext(hwnd, imc);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Input-context reconnect

    /** Detaches and re-attaches the window's input context.
     *
     *  <p>This is the first thing GLFW's {@code _glfwSetTextInputFocusWin32}
     *  does on blur, and what a real focus cycle ends up doing to the IME: the
     *  context is taken away, its forms are dropped, and it is handed back in a
     *  freshly initialised state. Doing it directly avoids the side effects of
     *  a real focus change -- taskbar flash, IME status indicator, QML focus
     *  callbacks, dropped keystrokes -- because the window keeps its focus the
     *  whole time.
     *
     *  <p>Opt-in via {@code -Dqplayer.ime.reconnect=N}. Runs on the window
     *  thread.
     *
     *  @return true when a round was actually performed. */
    static boolean reconnect(int round, int total) {
        if (!installed || hwnd == null) return false;
        // Only the window's own keyboard focus matters here: ImmAssociateContext
        // touches this HWND's input context and nothing else, so being the
        // foreground window is irrelevant (a window can hold the thread's focus
        // without being foreground, which is exactly what happens at startup).
        // Same Pointer trap as in apply(): compare native addresses.
        Pointer focus = U32.I.GetFocus();
        if (focus == null || Pointer.nativeValue(focus) != Pointer.nativeValue(hwnd)) {
            ImeDiagnostics.logReconnect(round, total, false,
                    "skipped: no keyboard focus (focus=" + hex(focus) + ")");
            return false;
        }
        if (applying) {
            ImeDiagnostics.logReconnect(round, total, false, "skipped: applying");
            return false;
        }
        try {
            Pointer saved = Imm32.I.ImmAssociateContext(hwnd, null);
            if (saved == null) {
                // Nothing was attached: there is no state to reset.
                ImeDiagnostics.logReconnect(round, total, false, "no context attached");
                return false;
            }
            Imm32.I.ImmAssociateContext(hwnd, saved);
            ImeDiagnostics.logReconnect(round, total, true, "detached+reattached");
        } catch (Throwable t) {
            ImeDiagnostics.logReconnect(round, total, false, "error: " + t);
            return false;
        }
        // The forms went away with the context; put the candidate position back
        // immediately when the caret rectangle is already known.
        apply("reconnect");
        return true;
    }

    /** Summary of the last positioning call, for the event log. */
    static String lastApplySummary() {
        return lastSummary;
    }

    private static String form(CANDIDATEFORM f) {
        return styleName(f.dwStyle) + " pt=" + f.ptCurrentPos.x + "," + f.ptCurrentPos.y
                + " rc=" + f.rcArea.left + "," + f.rcArea.top + ","
                + f.rcArea.right + "," + f.rcArea.bottom;
    }

    private static String styleName(int style) {
        if (style == CFS_CANDIDATEPOS) return "CFS_CANDIDATEPOS";
        if (style == CFS_EXCLUDE) return "CFS_EXCLUDE";
        if (style == CFS_RECT) return "CFS_RECT";
        if (style == 0) return "CFS_DEFAULT";
        return "0x" + Integer.toHexString(style);
    }

    private static String hex(Pointer p) {
        return p == null ? "0" : Long.toHexString(Pointer.nativeValue(p));
    }

    private static int langId() {
        try {
            Pointer hkl = U32.I.GetKeyboardLayout(0);
            return hkl == null ? 0 : (int) (Pointer.nativeValue(hkl) & 0xFFFFL);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isCjkLayout() {
        int primary = langId() & 0x3FF;
        return primary == LANG_CHINESE || primary == LANG_JAPANESE;
    }

    // ------------------------------------------------------------------
    // JNA surface

    private interface WndProc extends StdCallLibrary.StdCallCallback {
        long callback(Pointer hWnd, int uMsg, long wParam, long lParam);
    }

    private interface U32 extends StdCallLibrary {
        U32 I = Native.load("user32", U32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer GetFocus();
        Pointer GetForegroundWindow();
        boolean IsWindowEnabled(Pointer hWnd);
        boolean IsIconic(Pointer hWnd);
        boolean GetClientRect(Pointer hWnd, RECT rect);
        long SetWindowLongPtrW(Pointer hWnd, int nIndex, WndProc newProc);
        long SetWindowLongPtrW(Pointer hWnd, int nIndex, Pointer newProc);
        long CallWindowProcW(long prevWndFunc, Pointer hWnd, int uMsg, long wParam, long lParam);
        long DefWindowProcW(Pointer hWnd, int uMsg, long wParam, long lParam);
        Pointer GetKeyboardLayout(int idThread);
        boolean CreateCaret(Pointer hWnd, Pointer hBitmap, int width, int height);
        boolean DestroyCaret();
        boolean SetCaretPos(int x, int y);
        boolean GetCaretPos(POINT point);
    }

    private interface Imm32 extends StdCallLibrary {
        Imm32 I = Native.load("imm32", Imm32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer ImmGetContext(Pointer hWnd);
        Pointer ImmAssociateContext(Pointer hWnd, Pointer hIMC);
        boolean ImmReleaseContext(Pointer hWnd, Pointer hIMC);
        boolean ImmGetOpenStatus(Pointer hIMC);
        boolean ImmSetCandidateWindow(Pointer hIMC, CANDIDATEFORM form);
        boolean ImmGetCandidateWindow(Pointer hIMC, int index, CANDIDATEFORM form);
        boolean ImmSetCompositionWindow(Pointer hIMC, COMPOSITIONFORM form);
    }

    // All members are 4-byte aligned in the Win32 headers; ALIGN_NONE keeps
    // JNA from inserting 8-byte padding for the POINT on 64-bit hosts.
    public static class POINT extends Structure {
        public int x;
        public int y;

        public POINT() {
            setAlignType(ALIGN_NONE);
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("x", "y");
        }
    }

    public static class RECT extends Structure {
        public int left;
        public int top;
        public int right;
        public int bottom;

        public RECT() {
            setAlignType(ALIGN_NONE);
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("left", "top", "right", "bottom");
        }
    }

    public static class CANDIDATEFORM extends Structure {
        public int dwIndex;
        public int dwStyle;
        public POINT ptCurrentPos;
        public RECT rcArea;

        public CANDIDATEFORM() {
            setAlignType(ALIGN_NONE);
            ptCurrentPos = new POINT();
            rcArea = new RECT();
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("dwIndex", "dwStyle", "ptCurrentPos", "rcArea");
        }
    }

    public static class COMPOSITIONFORM extends Structure {
        public int dwStyle;
        public POINT ptCurrentPos;
        public RECT rcArea;

        public COMPOSITIONFORM() {
            setAlignType(ALIGN_NONE);
            ptCurrentPos = new POINT();
            rcArea = new RECT();
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("dwStyle", "ptCurrentPos", "rcArea");
        }
    }
}
