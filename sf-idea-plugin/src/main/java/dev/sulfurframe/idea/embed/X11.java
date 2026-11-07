package dev.sulfurframe.idea.embed;

import java.awt.Component;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.*;

/**
 * libX11 through FFM, for the Linux side of the plugin (worked out in poc/embed/X11Host.java).
 *
 * One Display connection of our own, separate from AWT's. Xlib connections are not thread-safe without XInitThreads
 * (which would have to run before AWT's first Xlib call), so every call here holds one lock.
 */
final class X11 {
    private static final Linker L = Linker.nativeLinker();
    private static final SymbolLookup X = SymbolLookup.libraryLookup("libX11.so.6", Arena.global());

    private static MethodHandle fn(String name, MemoryLayout ret, MemoryLayout... args) {
        return L.downcallHandle(X.find(name).orElseThrow(), ret == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(ret, args));
    }

    private static final MethodHandle XOpenDisplay = fn("XOpenDisplay", ADDRESS, ADDRESS);
    private static final MethodHandle XDefaultRootWindow = fn("XDefaultRootWindow", JAVA_LONG, ADDRESS);
    private static final MethodHandle XDefaultScreen = fn("XDefaultScreen", JAVA_INT, ADDRESS);
    private static final MethodHandle XQueryTree = fn("XQueryTree", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle XFetchName = fn("XFetchName", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS);
    private static final MethodHandle XFree = fn("XFree", JAVA_INT, ADDRESS);
    private static final MethodHandle XReparentWindow = fn("XReparentWindow", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
    private static final MethodHandle XMoveResizeWindow = fn("XMoveResizeWindow", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    private static final MethodHandle XMapWindow = fn("XMapWindow", JAVA_INT, ADDRESS, JAVA_LONG);
    private static final MethodHandle XUnmapWindow = fn("XUnmapWindow", JAVA_INT, ADDRESS, JAVA_LONG);
    private static final MethodHandle XWithdrawWindow = fn("XWithdrawWindow", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT);
    private static final MethodHandle XAddToSaveSet = fn("XAddToSaveSet", JAVA_INT, ADDRESS, JAVA_LONG);
    private static final MethodHandle XSetInputFocus = fn("XSetInputFocus", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG);
    private static final MethodHandle XGetInputFocus = fn("XGetInputFocus", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle XGetGeometry = fn("XGetGeometry", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle XGetWindowAttributes = fn("XGetWindowAttributes", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS);
    private static final MethodHandle XChangeWindowAttributes = fn("XChangeWindowAttributes", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS);
    private static final MethodHandle XTranslateCoordinates = fn("XTranslateCoordinates", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle XInternAtom = fn("XInternAtom", JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT);
    private static final MethodHandle XGetWindowProperty = fn("XGetWindowProperty", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG,
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle XSync = fn("XSync", JAVA_INT, ADDRESS, JAVA_INT);

    private static MemorySegment dpy;
    private static long root, netWmPid;

    /** Our connection (opened on first use). */
    private static synchronized MemorySegment dpy() throws Throwable {
        if (dpy == null) {
            dpy = (MemorySegment) XOpenDisplay.invokeExact(MemorySegment.NULL);
            if (dpy.address() == 0) { dpy = null; throw new IllegalStateException("XOpenDisplay failed (DISPLAY=" + System.getenv("DISPLAY") + ")"); }
            root = (long) XDefaultRootWindow.invokeExact(dpy);
            try (var a = Arena.ofConfined()) { netWmPid = (long) XInternAtom.invokeExact(dpy, a.allocateFrom("_NET_WM_PID"), 0); }
        }
        return dpy;
    }

    // No XSetErrorHandler of our own: the handler is process-wide and AWT's (XErrorHandlerUtil) already logs and
    // ignores errors instead of calling Xlib's exit-on-error default; that covers our connection too.

    static synchronized long root() { try { dpy(); return root; } catch (Throwable t) { return 0; } }

    static synchronized void reparent(long w, long parent, int x, int y) { try { int r = (int) XReparentWindow.invokeExact(dpy(), w, parent, x, y); } catch (Throwable ignored) { } }
    static synchronized void moveResize(long w, int x, int y, int width, int height) {
        try { int r = (int) XMoveResizeWindow.invokeExact(dpy(), w, x, y, Math.max(1, width), Math.max(1, height)); } catch (Throwable ignored) { }
    }
    static synchronized void map(long w) { try { int r = (int) XMapWindow.invokeExact(dpy(), w); } catch (Throwable ignored) { } }
    static synchronized void unmap(long w) { try { int r = (int) XUnmapWindow.invokeExact(dpy(), w); } catch (Throwable ignored) { } }
    static synchronized void withdraw(long w) {
        try { var d = dpy(); int s = (int) XDefaultScreen.invokeExact(d); int r = (int) XWithdrawWindow.invokeExact(d, w, s); } catch (Throwable ignored) { }
    }
    static synchronized void focus(long w) { try { int r = (int) XSetInputFocus.invokeExact(dpy(), w, 1 /* RevertToPointerRoot */, 0L); } catch (Throwable ignored) { } }
    static synchronized void sync() { try { int r = (int) XSync.invokeExact(dpy(), 0); } catch (Throwable ignored) { } }

    /** XChangeWindowAttributes(CWOverrideRedirect); XSetWindowAttributes on LP64: override_redirect at 88, 112 bytes. */
    static synchronized void setOverrideRedirect(long w, boolean on) {
        try (var a = Arena.ofConfined()) {
            var attrs = a.allocate(112, 8);
            attrs.set(JAVA_INT, 88, on ? 1 : 0);
            int r = (int) XChangeWindowAttributes.invokeExact(dpy(), w, 1L << 9, attrs);
        } catch (Throwable ignored) { }
    }

    /** XWindowAttributes (LP64, 136 bytes): map_state at 92 (2 = IsViewable), override_redirect at 120. 0 = gone. */
    private static synchronized MemorySegment attributes(Arena a, long w) throws Throwable {
        var attrs = a.allocate(136, 8);
        return (int) XGetWindowAttributes.invokeExact(dpy(), w, attrs) == 0 ? null : attrs;
    }

    static synchronized boolean exists(long w) {
        if (w == 0) return false;
        try (var a = Arena.ofConfined()) { return attributes(a, w) != null; } catch (Throwable t) { return false; }
    }

    static synchronized boolean viewable(long w) {
        try (var a = Arena.ofConfined()) { var at = attributes(a, w); return at != null && at.get(JAVA_INT, 92) == 2; } catch (Throwable t) { return false; }
    }

    static synchronized long parentOf(long w) {
        try (var a = Arena.ofConfined()) {
            var r = a.allocate(JAVA_LONG); var p = a.allocate(JAVA_LONG); var kids = a.allocate(ADDRESS); var n = a.allocate(JAVA_INT);
            if ((int) XQueryTree.invokeExact(dpy(), w, r, p, kids, n) == 0) return 0;
            var k = kids.get(ADDRESS, 0);
            if (k.address() != 0) { int f = (int) XFree.invokeExact(k); }
            return p.get(JAVA_LONG, 0);
        } catch (Throwable t) { return 0; }
    }

    static synchronized List<Long> children(long w) {
        var out = new ArrayList<Long>();
        try (var a = Arena.ofConfined()) {
            var r = a.allocate(JAVA_LONG); var p = a.allocate(JAVA_LONG); var kids = a.allocate(ADDRESS); var n = a.allocate(JAVA_INT);
            if ((int) XQueryTree.invokeExact(dpy(), w, r, p, kids, n) == 0) return out;
            int count = n.get(JAVA_INT, 0);
            var k = kids.get(ADDRESS, 0);
            if (k.address() == 0) return out;
            for (long id : k.reinterpret(8L * count).toArray(JAVA_LONG)) out.add(id);
            int f = (int) XFree.invokeExact(k);
        } catch (Throwable ignored) { }
        return out;
    }

    /** WM_NAME as UTF-8 bytes whatever its type (XFetchName only returns STRING; SDL3 stores UTF8_STRING). */
    static synchronized String name(long w) {
        try (var a = Arena.ofConfined()) {
            var type = a.allocate(JAVA_LONG); var format = a.allocate(JAVA_INT); var n = a.allocate(JAVA_LONG);
            var after = a.allocate(JAVA_LONG); var data = a.allocate(ADDRESS);
            int r = (int) XGetWindowProperty.invokeExact(dpy(), w, 39L /* XA_WM_NAME */, 0L, 256L, 0, 0L, type, format, n, after, data);
            var p = data.get(ADDRESS, 0);
            String s = "";
            if (r == 0 && p.address() != 0) s = new String(p.reinterpret(n.get(JAVA_LONG, 0)).toArray(JAVA_BYTE), java.nio.charset.StandardCharsets.UTF_8);
            if (p.address() != 0) { int f = (int) XFree.invokeExact(p); }
            return s;
        } catch (Throwable t) { return ""; }
    }

    /** _NET_WM_PID of the window (set by GLFW/SDL), or 0. */
    static synchronized long pidOf(long w) {
        try (var a = Arena.ofConfined()) {
            var d = dpy();
            var type = a.allocate(JAVA_LONG); var format = a.allocate(JAVA_INT); var n = a.allocate(JAVA_LONG);
            var after = a.allocate(JAVA_LONG); var data = a.allocate(ADDRESS);
            int r = (int) XGetWindowProperty.invokeExact(d, w, netWmPid, 0L, 1L, 0, 0L /* AnyPropertyType */, type, format, n, after, data);
            var p = data.get(ADDRESS, 0);
            long pid = 0;
            if (r == 0 && p.address() != 0 && n.get(JAVA_LONG, 0) > 0) pid = p.reinterpret(8).get(JAVA_LONG, 0) & 0xFFFFFFFFL;
            if (p.address() != 0) { int f = (int) XFree.invokeExact(p); }
            return pid;
        } catch (Throwable t) { return 0; }
    }

    /** x, y (on the root), width, height; null if gone. */
    static synchronized int[] screenRect(long w) {
        try (var a = Arena.ofConfined()) {
            var d = dpy();
            var r = a.allocate(JAVA_LONG); var x = a.allocate(JAVA_INT); var y = a.allocate(JAVA_INT);
            var ww = a.allocate(JAVA_INT); var hh = a.allocate(JAVA_INT); var bw = a.allocate(JAVA_INT); var depth = a.allocate(JAVA_INT);
            if ((int) XGetGeometry.invokeExact(d, w, r, x, y, ww, hh, bw, depth) == 0) return null;
            var rx = a.allocate(JAVA_INT); var ry = a.allocate(JAVA_INT); var child = a.allocate(JAVA_LONG);
            int t = (int) XTranslateCoordinates.invokeExact(d, w, root, 0, 0, rx, ry, child);
            return new int[]{rx.get(JAVA_INT, 0), ry.get(JAVA_INT, 0), ww.get(JAVA_INT, 0), hh.get(JAVA_INT, 0)};
        } catch (Throwable t) { return null; }
    }

    static synchronized long inputFocus() {
        try (var a = Arena.ofConfined()) {
            var f = a.allocate(JAVA_LONG); var rev = a.allocate(JAVA_INT);
            int r = (int) XGetInputFocus.invokeExact(dpy(), f, rev);
            return f.get(JAVA_LONG, 0);
        } catch (Throwable t) { return 0; }
    }

    /** Depth-first: windows whose name starts with "Minecraft" (WSLg/other WMs wrap toplevels in frames). */
    static List<Long> findGameWindows() {
        var out = new ArrayList<Long>();
        walk(root(), out, 0);
        return out;
    }

    private static void walk(long w, List<Long> out, int depth) {
        if (w == 0 || depth > 4) return;
        if (depth > 0 && name(w).startsWith("Minecraft") && viewable(w)) { out.add(w); return; }
        for (long c : children(w)) walk(c, out, depth + 1);
    }

    /** The game window of a process: _NET_WM_PID match among the Minecraft windows (any if the pid is not set). */
    static long findWindowOfProcess(long pid) {
        for (long w : findGameWindows()) if (pidOf(w) == pid) return w;
        return 0;   // never guess: another Minecraft may be running
    }

    // ------------------------------------------------------------------ AWT side

    /** The X window of a heavyweight AWT component (XToolkit peer: sun.awt.X11.XBaseWindow.getWindow), or 0. */
    static long windowOf(Component c) {
        try {
            Object acc = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null);
            Object peer = Class.forName("sun.awt.AWTAccessor$ComponentAccessor").getMethod("getPeer", Component.class).invoke(acc, c);
            if (peer == null) return 0;
            for (Class<?> k = peer.getClass(); k != null; k = k.getSuperclass()) {
                try { Method m = k.getDeclaredMethod("getWindow"); m.setAccessible(true); return (long) m.invoke(peer); }
                catch (NoSuchMethodException ignored) { }
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    /**
     * XAddToSaveSet on AWT's own connection: the server only rescues save-set windows of the client that created the
     * enclosing windows (the canvas is AWT's). Helps with non-reparenting window managers; with reparenting ones the
     * window lands in the WM frame and the agent's lifeboat takes over (see poc/embed/README).
     */
    static void addToAwtSaveSet(long w) {
        try {
            Class<?> tk = Class.forName("sun.awt.X11.XToolkit"), sun = Class.forName("sun.awt.SunToolkit");
            Method lock = sun.getDeclaredMethod("awtLock"), unlock = sun.getDeclaredMethod("awtUnlock"), getDisplay = tk.getDeclaredMethod("getDisplay");
            lock.setAccessible(true); unlock.setAccessible(true); getDisplay.setAccessible(true);
            var awtDpy = MemorySegment.ofAddress((long) getDisplay.invoke(null));
            lock.invoke(null);
            try { int r = (int) XAddToSaveSet.invokeExact(awtDpy, w); r = (int) XSync.invokeExact(awtDpy, 0); }
            finally { unlock.invoke(null); }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ click-to-focus

    private static final MethodHandle XGrabButton = fn("XGrabButton", JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG);
    private static final MethodHandle XUngrabButton = fn("XUngrabButton", JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG);
    private static final MethodHandle XPending = fn("XPending", JAVA_INT, ADDRESS);
    private static final MethodHandle XNextEvent = fn("XNextEvent", JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle XAllowEvents = fn("XAllowEvents", JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG);

    /**
     * What a window manager does for click-to-focus: a passive grab of every button on the game window, in synchronous
     * pointer mode. On a press we give the game the keyboard, then replay the press to it (XAllowEvents ReplayPointer),
     * so the game still gets its click. Own connection and thread; the target changes through a volatile.
     */
    static final class ClickToFocus {
        private volatile long target;
        private final Runnable onFocus;

        ClickToFocus(Runnable onFocus) {
            this.onFocus = onFocus;
            Thread.ofPlatform().daemon().name("sf-x11-click").start(this::run);
        }

        void watch(long window) { target = window; }

        private void run() {
            try (var a = Arena.ofShared()) {
                MemorySegment d = (MemorySegment) XOpenDisplay.invokeExact(MemorySegment.NULL);
                if (d.address() == 0) return;
                var ev = a.allocate(192, 8);
                long grabbed = 0;
                while (true) {
                    long want = target;
                    if (want != grabbed) {
                        int r;
                        if (grabbed != 0) r = (int) XUngrabButton.invokeExact(d, 0 /* AnyButton */, 1 << 15 /* AnyModifier */, grabbed);
                        if (want != 0) r = (int) XGrabButton.invokeExact(d, 0, 1 << 15, want, 0, 4 /* ButtonPressMask */,
                                0 /* GrabModeSync */, 1 /* GrabModeAsync */, 0L, 0L);
                        r = (int) XSync.invokeExact(d, 0);
                        grabbed = want;
                    }
                    if ((int) XPending.invokeExact(d) == 0) { Thread.sleep(10); continue; }
                    int r = (int) XNextEvent.invokeExact(d, ev);
                    if (ev.get(JAVA_INT, 0) != 4 /* ButtonPress */) continue;
                    long w = ev.get(JAVA_LONG, 32);   // XButtonEvent.window
                    r = (int) XSetInputFocus.invokeExact(d, w, 1, 0L);
                    r = (int) XAllowEvents.invokeExact(d, 2 /* ReplayPointer */, 0L);
                    r = (int) XSync.invokeExact(d, 0);
                    onFocus.run();
                }
            } catch (Throwable ignored) { }
        }
    }

    static boolean isXToolkit() { return java.awt.Toolkit.getDefaultToolkit().getClass().getName().equals("sun.awt.X11.XToolkit"); }

    private X11() {}
}
