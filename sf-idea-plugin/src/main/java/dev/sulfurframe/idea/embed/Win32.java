package dev.sulfurframe.idea.embed;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.*;

/**
 * Minimal user32 bindings via the FFM API (final since JDK 22).
 * HWND / LONG_PTR are passed as JAVA_LONG (both are 64-bit integers on Win64).
 */
final class Win32 {
    static final int GWL_STYLE = -16, GWL_EXSTYLE = -20, GWLP_HWNDPARENT = -8;
    static final long WS_CHILD = 0x40000000L, WS_POPUP = 0x80000000L, WS_VISIBLE = 0x10000000L,
            WS_CAPTION = 0x00C00000L, WS_THICKFRAME = 0x00040000L, WS_SYSMENU = 0x00080000L,
            WS_MINIMIZEBOX = 0x00020000L, WS_MAXIMIZEBOX = 0x00010000L,
            WS_CLIPCHILDREN = 0x02000000L, WS_CLIPSIBLINGS = 0x04000000L;
    static final long WS_EX_APPWINDOW = 0x00040000L, WS_EX_TOOLWINDOW = 0x00000080L;
    static final int SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10,
            SWP_FRAMECHANGED = 0x20, SWP_SHOWWINDOW = 0x40, SWP_ASYNCWINDOWPOS = 0x4000;
    static final int GW_OWNER = 4, GA_PARENT = 1, GA_ROOT = 2;

    // lazy holder: the linker fields below are not initialized yet at this point of the class body
    private static final class Wfp { static final MethodHandle H = fn("WindowFromPoint", JAVA_LONG, JAVA_LONG); }
    private static final class Sfw { static final MethodHandle H = fn("SetForegroundWindow", JAVA_INT, JAVA_LONG); }

    static boolean setForeground(long h) { try { return (int) Sfw.H.invokeExact(h) != 0; } catch (Throwable t) { throw rethrow(t); } }

    /** POINT is passed by value as one 64-bit integer: x in the low half, y in the high half. Physical pixels. */
    static long windowFromPoint(int x, int y) {
        try { return (long) Wfp.H.invokeExact(((long) y << 32) | (x & 0xFFFFFFFFL)); } catch (Throwable t) { throw rethrow(t); }
    }

    /** Visible top-level windows of all processes whose class is a known game window class and title starts with Minecraft. */
    static java.util.List<Long> findGameWindows() {
        var out = new java.util.ArrayList<Long>();
        long h = 0;
        while ((h = findWindowEx(0, h, null, null)) != 0) {
            if (!isVisible(h)) continue;
            String cls = className(h);
            if ((cls.equals("GLFW30") || cls.equals("SDL_app") || cls.equals("LWJGL")) && windowText(h).startsWith("Minecraft"))
                out.add(h);
        }
        return out;
    }

    private static final Linker L = Linker.nativeLinker();
    private static final SymbolLookup U32 = SymbolLookup.libraryLookup("user32", Arena.global());

    private static MethodHandle fn(String name, MemoryLayout ret, MemoryLayout... args) {
        var addr = U32.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
        return L.downcallHandle(addr, ret == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(ret, args));
    }

    private static final MethodHandle
            FindWindowW = fn("FindWindowW", JAVA_LONG, ADDRESS, ADDRESS),
            FindWindowExW = fn("FindWindowExW", JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS),
            GetWindowThreadProcessId = fn("GetWindowThreadProcessId", JAVA_INT, JAVA_LONG, ADDRESS),
            IsWindowVisible = fn("IsWindowVisible", JAVA_INT, JAVA_LONG),
            IsWindow = fn("IsWindow", JAVA_INT, JAVA_LONG),
            IsHungAppWindow = fn("IsHungAppWindow", JAVA_INT, JAVA_LONG),
            GetWindow = fn("GetWindow", JAVA_LONG, JAVA_LONG, JAVA_INT),
            GetAncestor = fn("GetAncestor", JAVA_LONG, JAVA_LONG, JAVA_INT),
            GetWindowLongPtrW = fn("GetWindowLongPtrW", JAVA_LONG, JAVA_LONG, JAVA_INT),
            SetWindowLongPtrW = fn("SetWindowLongPtrW", JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG),
            SetParent = fn("SetParent", JAVA_LONG, JAVA_LONG, JAVA_LONG),
            SetWindowPos = fn("SetWindowPos", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
            GetClientRect = fn("GetClientRect", JAVA_INT, JAVA_LONG, ADDRESS),
            GetWindowRect = fn("GetWindowRect", JAVA_INT, JAVA_LONG, ADDRESS),
            GetClassNameW = fn("GetClassNameW", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT),
            GetWindowTextW = fn("GetWindowTextW", JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT),
            GetForegroundWindow = fn("GetForegroundWindow", JAVA_LONG),
            SetFocus = fn("SetFocus", JAVA_LONG, JAVA_LONG),
            GetFocus = fn("GetFocus", JAVA_LONG);

    private static final MethodHandle
            AttachThreadInput = fn("AttachThreadInput", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
            SendMessageTimeoutW = fn("SendMessageTimeoutW", JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS);

    static int threadIdOf(long hwnd) {
        try { return (int) GetWindowThreadProcessId.invokeExact(hwnd, MemorySegment.NULL); } catch (Throwable t) { throw rethrow(t); }
    }

    static boolean attachThreadInput(int from, int to, boolean attach) {
        try { return (int) AttachThreadInput.invokeExact(from, to, attach ? 1 : 0) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    /** WM_NULL round trip with a timeout: false if the window's thread is not pumping messages. */
    static boolean isResponsive(long h, int timeoutMs) {
        final int WM_NULL = 0, SMTO_ABORTIFHUNG = 0x2;
        try (var a = Arena.ofConfined()) {
            var res = a.allocate(JAVA_LONG);
            return (long) SendMessageTimeoutW.invokeExact(h, WM_NULL, 0L, 0L, SMTO_ABORTIFHUNG, timeoutMs, res) != 0;
        } catch (Throwable t) { throw rethrow(t); }
    }

    // ---- thread suspend/resume (simulates a frozen game: world load, long frame, breakpoint)
    private static final SymbolLookup K32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final MethodHandle
            CloseHandle = L.downcallHandle(K32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
            GetGUIThreadInfo = fn("GetGUIThreadInfo", JAVA_INT, JAVA_INT, ADDRESS),
            OpenThread = L.downcallHandle(K32.find("OpenThread").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT)),
            SuspendThread = L.downcallHandle(K32.find("SuspendThread").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
            ResumeThread = L.downcallHandle(K32.find("ResumeThread").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    /**
     * Freeze only the thread that owns {@code hwnd} (for Minecraft: the render thread) for {@code ms}.
     * Closer to a real stall (world load, long frame) than freezing the whole process, and it also works while
     * the window is embedded (NtSuspendProcess returned status 1 in that state).
     */
    private static final MethodHandle
            OpenProcess = L.downcallHandle(K32.find("OpenProcess").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT)),
            EmptyWorkingSet = L.downcallHandle(K32.find("K32EmptyWorkingSet").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    /** Move all of a process's pages out of its working set (to standby/pagefile): physical RAM is free immediately. */
    static boolean emptyWorkingSet(long pid) {
        final int PROCESS_QUERY_INFORMATION = 0x0400, PROCESS_SET_QUOTA = 0x0100;
        try {
            long h = (long) OpenProcess.invokeExact(PROCESS_QUERY_INFORMATION | PROCESS_SET_QUOTA, 0, (int) pid);
            if (h == 0) return false;
            try { return (int) EmptyWorkingSet.invokeExact(h) != 0; }
            finally { int c = (int) CloseHandle.invokeExact(h); }
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void suspendWindowThreadFor(long hwnd, long ms) {
        final int THREAD_SUSPEND_RESUME = 0x0002;
        int tid = threadIdOf(hwnd);
        try {
            long h = (long) OpenThread.invokeExact(THREAD_SUSPEND_RESUME, 0, tid);
            if (h == 0) throw new IllegalStateException("OpenThread failed for tid " + tid);
            try {
                int prev = (int) SuspendThread.invokeExact(h);
                if (prev == -1) throw new IllegalStateException("SuspendThread failed for tid " + tid);
                try { Thread.sleep(ms); } finally { int r = (int) ResumeThread.invokeExact(h); }
            } finally { int c = (int) CloseHandle.invokeExact(h); }
        } catch (Throwable t) { throw rethrow(t); }
    }

    // ---- window subclassing (WNDPROC upcall)
    static final int GWLP_WNDPROC = -4;
    static final int WM_PARENTNOTIFY = 0x0210, WM_LBUTTONDOWN = 0x0201, WM_RBUTTONDOWN = 0x0204,
            WM_MBUTTONDOWN = 0x0207, WM_XBUTTONDOWN = 0x020B;
    static final long WS_EX_NOPARENTNOTIFY = 0x00000004L;
    static final FunctionDescriptor WNDPROC = FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
    private static final MethodHandle CallWindowProcW =
            fn("CallWindowProcW", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);

    static final int SW_HIDE = 0, SW_SHOWNOACTIVATE = 4, SW_MINIMIZE = 6, SW_SHOWMINNOACTIVE = 7, SW_RESTORE = 9;
    private static final MethodHandle ShowWindow = fn("ShowWindowAsync", JAVA_INT, JAVA_LONG, JAVA_INT),   // async: never wait on the game thread
            IsIconic = fn("IsIconic", JAVA_INT, JAVA_LONG);

    static boolean showWindow(long h, int cmd) { try { return (int) ShowWindow.invokeExact(h, cmd) != 0; } catch (Throwable t) { throw rethrow(t); } }
    static boolean isIconic(long h) { try { return (int) IsIconic.invokeExact(h) != 0; } catch (Throwable t) { throw rethrow(t); } }

    private static final MethodHandle GetClipCursor = fn("GetClipCursor", JAVA_INT, ADDRESS);

    /** Current cursor clip rectangle (the whole virtual screen when nothing has grabbed the mouse). */
    static Rect clipCursor() {
        try (var a = Arena.ofConfined()) {
            var r = a.allocate(16);
            int ok = (int) GetClipCursor.invokeExact(r);
            return ok == 0 ? null : new Rect(r.get(JAVA_INT, 0), r.get(JAVA_INT, 4), r.get(JAVA_INT, 8), r.get(JAVA_INT, 12));
        } catch (Throwable t) { throw rethrow(t); }
    }

    private static final MethodHandle ClipCursorFn = fn("ClipCursor", JAVA_INT, ADDRESS);

    /** Lift any cursor clip (ClipCursor(NULL)): the mouse may go anywhere on the screen again. */
    static void releaseClip() {
        try { int ignored = (int) ClipCursorFn.invokeExact(MemorySegment.NULL); } catch (Throwable t) { throw rethrow(t); }
    }

    static final int WM_APP = 0x8000;
    private static final MethodHandle PostMessageW = fn("PostMessageW", JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);

    private static final MethodHandle
            GetCurrentThreadId = L.downcallHandle(K32.find("GetCurrentThreadId").orElseThrow(), FunctionDescriptor.of(JAVA_INT)),
            IsGUIThread = fn("IsGUIThread", JAVA_INT, JAVA_INT);

    static int currentThreadId() { try { return (int) GetCurrentThreadId.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }

    /** Give the calling thread a message queue (required by AttachThreadInput). */
    static void ensureGuiThread() { try { int ignored = (int) IsGUIThread.invokeExact(1); } catch (Throwable t) { throw rethrow(t); } }

    static boolean postMessage(long h, int msg, long w, long l) {
        try { return (int) PostMessageW.invokeExact(h, msg, w, l) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    interface WndProc { long call(long hwnd, int msg, long wParam, long lParam); }

    /** Native function pointer for a Java WNDPROC. Lives forever (global arena) — fine for a handful of windows. */
    static long wndProcStub(WndProc p) {
        try {
            var mh = java.lang.invoke.MethodHandles.lookup().findVirtual(WndProc.class, "call",
                    java.lang.invoke.MethodType.methodType(long.class, long.class, int.class, long.class, long.class)).bindTo(p);
            return L.upcallStub(mh, WNDPROC, Arena.global()).address();
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    static long callWindowProc(long prev, long h, int msg, long w, long l) {
        try { return (long) CallWindowProcW.invokeExact(prev, h, msg, w, l); } catch (Throwable t) { throw rethrow(t); }
    }

    /** Window with keyboard focus in the foreground thread's input context (works across attached queues). */
    static long foregroundFocus() {
        try (var a = Arena.ofConfined()) {
            var gti = a.allocate(72);
            gti.set(JAVA_INT, 0, 72);
            int ok = (int) GetGUIThreadInfo.invokeExact(0, gti);
            return ok == 0 ? 0 : gti.get(JAVA_LONG, 16);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static long setFocus(long h) { try { return (long) SetFocus.invokeExact(h); } catch (Throwable t) { throw rethrow(t); } }
    static long getFocus() { try { return (long) GetFocus.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }

    static long foregroundWindow() { try { return (long) GetForegroundWindow.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }

    record Rect(int left, int top, int right, int bottom) {
        int width() { return right - left; }
        int height() { return bottom - top; }
    }

    private static MemorySegment wstr(Arena a, String s) {
        return s == null ? MemorySegment.NULL : a.allocateFrom(s, StandardCharsets.UTF_16LE);
    }

    private static RuntimeException rethrow(Throwable t) {
        return t instanceof RuntimeException r ? r : new RuntimeException(t);
    }

    static long findWindow(String cls, String title) {
        try (var a = Arena.ofConfined()) {
            return (long) FindWindowW.invokeExact(wstr(a, cls), wstr(a, title));
        } catch (Throwable t) { throw rethrow(t); }
    }

    static long findWindowEx(long parent, long after, String cls, String title) {
        try (var a = Arena.ofConfined()) {
            return (long) FindWindowExW.invokeExact(parent, after, wstr(a, cls), wstr(a, title));
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int processIdOf(long hwnd) {
        try (var a = Arena.ofConfined()) {
            var pid = a.allocate(JAVA_INT);
            int unused = (int) GetWindowThreadProcessId.invokeExact(hwnd, pid);
            return pid.get(JAVA_INT, 0);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** First visible, unowned top-level window belonging to {@code pid}, or 0. No upcalls needed. */
    static long findMainWindowOfProcess(long pid) {
        long h = 0;
        while ((h = findWindowEx(0, h, null, null)) != 0) {
            if (processIdOf(h) == pid && isVisible(h) && getWindow(h, GW_OWNER) == 0) return h;
        }
        return 0;
    }

    static boolean isVisible(long h) { try { return (int) IsWindowVisible.invokeExact(h) != 0; } catch (Throwable t) { throw rethrow(t); } }
    static boolean isWindow(long h) { try { return (int) IsWindow.invokeExact(h) != 0; } catch (Throwable t) { throw rethrow(t); } }
    static boolean isHung(long h) { try { return (int) IsHungAppWindow.invokeExact(h) != 0; } catch (Throwable t) { throw rethrow(t); } }
    static long getWindow(long h, int cmd) { try { return (long) GetWindow.invokeExact(h, cmd); } catch (Throwable t) { throw rethrow(t); } }
    static long getAncestor(long h, int flags) { try { return (long) GetAncestor.invokeExact(h, flags); } catch (Throwable t) { throw rethrow(t); } }
    static long getLongPtr(long h, int idx) { try { return (long) GetWindowLongPtrW.invokeExact(h, idx); } catch (Throwable t) { throw rethrow(t); } }
    static long setLongPtr(long h, int idx, long v) { try { return (long) SetWindowLongPtrW.invokeExact(h, idx, v); } catch (Throwable t) { throw rethrow(t); } }
    static long setParent(long child, long parent) { try { return (long) SetParent.invokeExact(child, parent); } catch (Throwable t) { throw rethrow(t); } }

    static boolean setWindowPos(long h, long after, int x, int y, int w, int hh, int flags) {
        try { return (int) SetWindowPos.invokeExact(h, after, x, y, w, hh, flags) != 0; } catch (Throwable t) { throw rethrow(t); }
    }

    private static Rect rect(MethodHandle mh, long h) {
        try (var a = Arena.ofConfined()) {
            var r = a.allocate(16);
            int ok = (int) mh.invokeExact(h, r);
            return ok == 0 ? null : new Rect(r.get(JAVA_INT, 0), r.get(JAVA_INT, 4), r.get(JAVA_INT, 8), r.get(JAVA_INT, 12));
        } catch (Throwable t) { throw rethrow(t); }
    }

    static Rect clientRect(long h) { return rect(GetClientRect, h); }
    static Rect windowRect(long h) { return rect(GetWindowRect, h); }

    static String className(long h) {
        try (var a = Arena.ofConfined()) {
            var buf = a.allocate(512);
            int n = (int) GetClassNameW.invokeExact(h, buf, 256);
            return buf.getString(0, StandardCharsets.UTF_16LE).substring(0, Math.max(0, n));
        } catch (Throwable t) { throw rethrow(t); }
    }

    static String windowText(long h) {
        try (var a = Arena.ofConfined()) {
            var buf = a.allocate(1024);
            int n = (int) GetWindowTextW.invokeExact(h, buf, 512);
            return buf.getString(0, StandardCharsets.UTF_16LE).substring(0, Math.max(0, n));
        } catch (Throwable t) { throw rethrow(t); }
    }

    private static final MethodHandle
            GetLastInputInfo = fn("GetLastInputInfo", JAVA_INT, ADDRESS),
            GetTickCount = L.downcallHandle(K32.find("GetTickCount").orElseThrow(), FunctionDescriptor.of(JAVA_INT));

    /** Milliseconds since the user's last key press, click or mouse move anywhere in the session; -1 if unknown. */
    static long idleMillis() {
        try (var a = Arena.ofConfined()) {
            var lii = a.allocate(8);
            lii.set(JAVA_INT, 0, 8);
            if ((int) GetLastInputInfo.invokeExact(lii) == 0) return -1;
            return Integer.toUnsignedLong((int) GetTickCount.invokeExact() - lii.get(JAVA_INT, 4));
        } catch (Throwable t) { return -1; }
    }

    /** "class 'title' (pid N)" for a log line. */
    static String describe(long h) {
        if (h == 0) return "none";
        try {
            int pid = processIdOf(h);
            String t = windowText(h);
            return className(h) + (t.isEmpty() ? "" : " '" + (t.length() > 60 ? t.substring(0, 60) + "…" : t) + "'")
                    + " (" + (pid == ProcessHandle.current().pid() ? "IDE" : "pid " + pid) + ")";
        } catch (Throwable t) { return hex(h); }
    }

    static String hex(long h) { return "0x" + Long.toHexString(h); }

    private Win32() {}
}
