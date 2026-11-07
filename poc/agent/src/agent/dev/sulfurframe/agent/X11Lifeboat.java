package dev.sulfurframe.agent;

import java.lang.reflect.Method;

/**
 * The X11 side of {@link Lifeboat} (GLFW games on Linux).
 *
 * An embedded game window is a child of the IDE's canvas. If the IDE dies, the X server moves the save-set windows to
 * the nearest ancestor the dead client did not create: with a reparenting window manager (GNOME, KDE, WSLg's Weston)
 * that is the WM's frame of the IDE window, which the WM then destroys, and the game window goes with it. Two things
 * then kill the game before it can save: GLFW keeps calling Xlib on the dead window, and Xlib's default error handler
 * exits the process on the first BadWindow/BadDrawable.
 *
 * So, while the game is embedded (the host marks the window override-redirect when it takes it from the WM):
 *   - X errors are made non-fatal: XSetErrorHandler gets a C function that ignores its arguments (libc getpid; Xlib
 *     ignores the handler's return value). GLFW installs its own handler around the calls whose errors it wants and
 *     restores ours afterwards. The previous handler comes back when the game is detached again.
 *   - a thread polls the window on its own Display connection; if it vanishes while embedded, the game is asked to quit
 *     normally (glfwSetWindowShouldClose), so the integrated server saves the world.
 * Native calls go through LWJGL's own JNI invokers and LinuxLibrary, by reflection. Java 8.
 */
final class X11Lifeboat {
    private static final int ATTR_SIZE = 136, ATTR_OVERRIDE_REDIRECT = 120;   // XWindowAttributes on LP64

    private static long window, xwin, dpy, attrs;
    private static long fnOpen, fnGetAttrs, fnSetErrorHandler, fnIgnore;
    private static Method invPP, invPPPI;
    private static boolean addressFirst;
    private static long previousHandler = -1;   // -1: ours is not installed

    static void glfw(long glfwWindow) {
        try {
            ClassLoader l = SfHooks.lwjglClassLoader();
            window = glfwWindow;
            xwin = (Long) Class.forName("org.lwjgl.glfw.GLFWNativeX11", false, l).getMethod("glfwGetX11Window", long.class).invoke(null, glfwWindow);
            if (xwin == 0) { SfHooks.log("lifeboat: not an X11 window (Wayland?), unavailable"); return; }
            Class<?> jni = Class.forName("org.lwjgl.system.JNI", false, l);
            invPP = SfHooks.jniMethod(jni, "PP", long.class, long.class);
            invPPPI = SfHooks.jniMethod(jni, "PPPI", long.class, long.class, long.class, long.class);
            invPPI = SfHooks.jniMethod(jni, "PPI", long.class, long.class, long.class);
            invPPPPPPI = SfHooks.jniMethod(jni, "PPPPPPI", long.class, long.class, long.class, long.class, long.class, long.class, long.class);
            Class<?> lib = Class.forName("org.lwjgl.system.linux.LinuxLibrary", false, l);
            Method addr = Class.forName("org.lwjgl.system.FunctionProvider", false, l).getMethod("getFunctionAddress", CharSequence.class);
            Object x11 = lib.getConstructor(String.class).newInstance("libX11.so.6");
            Object libc = lib.getConstructor(String.class).newInstance("libc.so.6");
            fnOpen = (Long) addr.invoke(x11, "XOpenDisplay");
            fnGetAttrs = (Long) addr.invoke(x11, "XGetWindowAttributes");
            fnSetErrorHandler = (Long) addr.invoke(x11, "XSetErrorHandler");
            fnIgnore = (Long) addr.invoke(libc, "getpid");
            fnSelectInput = (Long) addr.invoke(x11, "XSelectInput");
            fnNextEvent = (Long) addr.invoke(x11, "XNextEvent");
            fnQueryTree = (Long) addr.invoke(x11, "XQueryTree");
            fnFree = (Long) addr.invoke(x11, "XFree");
            fnReparent = (Long) addr.invoke(x11, "XReparentWindow");
            fnMap = (Long) addr.invoke(x11, "XMapWindow");
            fnUnmap = (Long) addr.invoke(x11, "XUnmapWindow");
            fnChangeAttrs = (Long) addr.invoke(x11, "XChangeWindowAttributes");
            fnFlush = (Long) addr.invoke(x11, "XFlush");
            Class<?> ver = Class.forName("org.lwjgl.Version", false, l);
            addressFirst = ver.getField("VERSION_MAJOR").getInt(null) == 3 && ver.getField("VERSION_MINOR").getInt(null) < 2;
            if (fnOpen == 0 || fnGetAttrs == 0 || fnSetErrorHandler == 0 || fnIgnore == 0) { SfHooks.log("lifeboat: libX11 functions missing"); return; }
            Class<?> mem = Class.forName("org.lwjgl.system.MemoryUtil", false, l);
            attrs = (Long) mem.getMethod("nmemCalloc", long.class, long.class).invoke(null, 1L, (long) ATTR_SIZE);
            memGetInt = mem.getMethod("memGetInt", long.class);
            Thread t = new Thread(X11Lifeboat::watch, "sulfurframe-lifeboat");
            t.setDaemon(true);
            t.start();
            Thread r = new Thread(X11Lifeboat::rescue, "sulfurframe-x11-rescue");
            r.setDaemon(true);
            r.start();
            SfHooks.log("lifeboat armed for X11 window 0x" + Long.toHexString(xwin));
        } catch (Throwable t) {
            SfHooks.log("lifeboat (X11) unavailable: " + t);
        }
    }

    private static Method memGetInt;

    private static void watch() {
        try {
            dpy = pp(0L, fnOpen);   // our own connection: GLFW's belongs to the render thread
            if (dpy == 0) { SfHooks.log("lifeboat: XOpenDisplay failed"); return; }
            boolean embedded = false;
            while (true) {
                Thread.sleep(250);
                boolean alive = pppi(dpy, xwin, attrs, fnGetAttrs) != 0;
                if (alive) {
                    boolean now = (Integer) memGetInt.invoke(null, attrs + ATTR_OVERRIDE_REDIRECT) != 0;
                    if (now != embedded) {
                        embedded = now;
                        if (embedded) { previousHandler = pp(fnIgnore, fnSetErrorHandler); SfHooks.log("embedded: X errors made non-fatal"); }
                        else if (previousHandler != -1) { pp(previousHandler, fnSetErrorHandler); previousHandler = -1; SfHooks.log("detached: X error handler restored"); }
                    }
                    continue;
                }
                if (embedded) {
                    windowGone = true;
                    SfHooks.log("game window destroyed while embedded (IDE gone?): asking the game to quit so the world is saved");
                    Lifeboat.requestQuit();
                }
                return;
            }
        } catch (Throwable t) {
            SfHooks.log("lifeboat stopped: " + t);
        }
    }

    // ------------------------------------------------------------------ rescue (before the WM destroys its frame)

    private static long fnSelectInput, fnNextEvent, fnQueryTree, fnFree, fnReparent, fnMap, fnUnmap, fnChangeAttrs, fnFlush;
    private static Method invPPI, invPPPPPPI, memGetLong, memPutInt;
    private static long rdpy, root, tree;
    static volatile boolean rescued;
    /** The embedded window is (probably) gone: stop presenting frames into it (see SfHooks.beforeGlfwSwap). */
    static volatile boolean windowGone;

    /**
     * When the host dies, the server handles its save-set first (our window lands in the WM's frame of the host window:
     * a ReparentNotify for us) and destroys the host's windows after that (the WM then destroys its frame). This
     * thread sits in XNextEvent and, on that ReparentNotify, moves the window to the root before the WM gets there.
     *
     * Any move of the embedded (override-redirect) window out of a canvas into something that is not the root is acted on
     * at once, without a query first. Afterwards: if the old parent (the canvas) still exists, it was the WM taking the
     * window back while the host embeds it, so it goes back into the canvas; if not, the host died (the server destroys
     * the dead client's windows in the same step as the save-set). Under WSLg's Weston the WM still wins that race; the
     * window is lost and the poll thread asks the game to quit, with swaps skipped (windowGone) so Mesa does not crash.
     */
    private static void rescue() {
        try {
            ClassLoader l = SfHooks.lwjglClassLoader();
            Class<?> mem = Class.forName("org.lwjgl.system.MemoryUtil", false, l);
            memGetLong = mem.getMethod("memGetLong", long.class);
            memPutInt = mem.getMethod("memPutInt", long.class, int.class);
            Method calloc = mem.getMethod("nmemCalloc", long.class, long.class);
            long ev = (Long) calloc.invoke(null, 1L, 192L);            // XEvent
            tree = (Long) calloc.invoke(null, 1L, 32L);                // root, parent, children*, count
            long setAttrs = (Long) calloc.invoke(null, 1L, 112L);      // XSetWindowAttributes
            rdpy = pp(0L, fnOpen);
            if (rdpy == 0) return;
            long[] rp = queryTree(xwin);
            if (rp == null) return;
            root = rp[0];
            long prevParent = rp[1];
            boolean prevDeep = deep(prevParent);
            pppi(rdpy, xwin, 1L << 17 /* StructureNotifyMask */, fnSelectInput);
            while (true) {
                ppi(rdpy, ev, fnNextEvent);   // blocks
                if ((Integer) memGetInt.invoke(null, ev) != 21 /* ReparentNotify */) continue;
                long w = (Long) memGetLong.invoke(null, ev + 40), parent = (Long) memGetLong.invoke(null, ev + 48);
                boolean override = (Integer) memGetInt.invoke(null, ev + 64) != 0;
                if (w != xwin) continue;
                long oldParent = prevParent;
                boolean oldDeep = prevDeep;
                if (!(override && oldDeep && parent != root)) {   // ordinary move (embed, park, detach)
                    prevParent = parent;
                    prevDeep = deep(parent);
                    continue;
                }
                // Pulled out of the canvas into something else. The WM destroys its frame one round trip after the
                // event, so act first and ask questions afterwards: no query before this request.
                windowGone = true;   // hold frames until we know
                p6(rdpy, xwin, root, 100, 100, 0, fnReparent);
                ppi(rdpy, xwin, fnMap);
                pi(rdpy, fnFlush);
                if (queryTree(oldParent) != null) {
                    // the canvas still exists: not a dead host but a WM taking the window back mid-embed; put it back
                    p6(rdpy, xwin, oldParent, 0, 0, 0, fnReparent);
                    ppi(rdpy, xwin, fnMap);
                    pi(rdpy, fnFlush);
                    windowGone = false;
                    SfHooks.log("x11: window manager took the embedded window (into 0x" + Long.toHexString(parent) + "); put it back");
                    continue;   // prevParent stays the canvas
                }
                if (queryTree(xwin) == null) { SfHooks.log("x11: host gone and the window manager was faster: window lost"); return; }
                windowGone = false;
                rescued = true;
                SfHooks.log("host window gone: game window rescued to the root before the window manager could destroy it");
                Thread.sleep(500);
                // hand it back to the window manager as a normal (framed) window
                ppi(rdpy, xwin, fnUnmap);
                memPutInt.invoke(null, setAttrs + 88, 0);
                p6(rdpy, xwin, 1L << 9 /* CWOverrideRedirect */, setAttrs, 0, 0, fnChangeAttrs);
                ppi(rdpy, xwin, fnMap);
                pi(rdpy, fnFlush);
                prevParent = root;
                prevDeep = false;
            }
        } catch (Throwable t) {
            SfHooks.log("x11 rescue stopped: " + t);
        }
    }

    /** Inside a window that is itself inside a toplevel (a canvas), rather than the root or a WM frame / WM window. */
    private static boolean deep(long parent) throws Exception {
        if (parent == 0 || parent == root) return false;
        long[] q = queryTree(parent);
        return q != null && q[1] != root;
    }

    /** {root, parent} of w, or null if w is gone. */
    private static long[] queryTree(long w) throws Exception {
        if (p6(rdpy, w, tree, tree + 8, tree + 16, tree + 24, fnQueryTree) == 0) return null;
        long kids = (Long) memGetLong.invoke(null, tree + 16);
        if (kids != 0) pi(kids, fnFree);
        return new long[]{(Long) memGetLong.invoke(null, tree), (Long) memGetLong.invoke(null, tree + 8)};
    }

    private static int ppi(long a, long b, long fn) throws Exception {
        return (Integer) (addressFirst ? invPPI.invoke(null, fn, a, b) : invPPI.invoke(null, a, b, fn));
    }

    private static int pi(long a, long fn) throws Exception {
        Method m = invPI != null ? invPI : (invPI = SfHooks.jniMethod(Class.forName("org.lwjgl.system.JNI", false, SfHooks.lwjglClassLoader()), "PI", long.class, long.class));
        return (Integer) (addressFirst ? m.invoke(null, fn, a) : m.invoke(null, a, fn));
    }
    private static Method invPI;

    /** Up to 6 pointer-sized arguments (ints passed as longs: same registers on LP64; extra trailing ones are ignored). */
    private static int p6(long a, long b, long c, long d, long e, long f, long fn) throws Exception {
        return (Integer) (addressFirst ? invPPPPPPI.invoke(null, fn, a, b, c, d, e, f) : invPPPPPPI.invoke(null, a, b, c, d, e, f, fn));
    }

    private static long pp(long a, long fn) throws Exception {
        return (Long) (addressFirst ? invPP.invoke(null, fn, a) : invPP.invoke(null, a, fn));
    }

    private static int pppi(long a, long b, long c, long fn) throws Exception {
        return (Integer) (addressFirst ? invPPPI.invoke(null, fn, a, b, c) : invPPPI.invoke(null, a, b, c, fn));
    }

    private X11Lifeboat() {}
}
