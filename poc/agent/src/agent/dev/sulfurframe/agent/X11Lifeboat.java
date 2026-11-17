package dev.sulfurframe.agent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The X11 side of {@link Lifeboat}: GLFW (1.13+), SDL3 (26.x) and LWJGL 2 (1.12.2 and older) games on Linux.
 *
 * An embedded game window is a child of the IDE's canvas. If the IDE dies, the X server moves the save-set windows to
 * the nearest ancestor the dead client did not create: with a reparenting window manager (GNOME, KDE, WSLg's Weston)
 * that is the WM's frame of the IDE window, which the WM then destroys, and the game window goes with it. Two things
 * then kill the game before it can save: the toolkit keeps calling Xlib on the dead window, and Xlib's default error
 * handler exits the process on the first BadWindow/BadDrawable.
 *
 * So, while the game is embedded (the host marks the window override-redirect when it takes it from the WM):
 *   - X errors are made non-fatal: XSetErrorHandler gets a C function that ignores its arguments (libc getpid; Xlib
 *     ignores the handler's return value). GLFW installs its own handler around the calls whose errors it wants and
 *     restores ours afterwards. The previous handler comes back when the game is detached again.
 *   - an event thread tries to rescue the window when the host dies (see {@link #rescue}), and puts it back when the WM
 *     grabs it while the host embeds it
 *   - a thread polls the window on its own Display connection; if it vanishes while embedded, frames stop being
 *     presented ({@link #windowGone}) and the game is asked to quit normally, so the integrated server saves the world.
 *
 * Native calls: LWJGL 3's JNI invokers and LinuxLibrary when there is LWJGL 3, else JNA (LWJGL 2 games: Minecraft
 * ships JNA since 1.8). Everything by reflection. Java 8.
 */
final class X11Lifeboat {
    private static final int ATTR_SIZE = 136, ATTR_OVERRIDE_REDIRECT = 120;   // XWindowAttributes on LP64

    private static volatile long xwin;
    private static long dpy, attrs;
    private static long fnOpen, fnGetAttrs, fnSetErrorHandler, fnIgnore, fnGetInputFocus;
    private static long fnSelectInput, fnNextEvent, fnQueryTree, fnFree, fnReparent, fnMap, fnUnmap, fnChangeAttrs, fnFlush;
    private static long previousHandler = -1;   // -1: ours is not installed
    static volatile boolean rescued;
    /** The embedded window is (probably) gone: stop presenting frames into it (SfHooks.before*Swap / LWJGL 2 update). */
    static volatile boolean windowGone;

    static void glfw(long glfwWindow) {
        try {
            ClassLoader l = SfHooks.lwjglClassLoader();
            arm((Long) Class.forName("org.lwjgl.glfw.GLFWNativeX11", false, l).getMethod("glfwGetX11Window", long.class).invoke(null, glfwWindow));
        } catch (Throwable t) { SfHooks.log("lifeboat (X11) unavailable: " + t); }
    }

    /** SDL3 (26.x): the X window id is a number property of the SDL window. */
    static void sdl(long sdlWindow) {
        try {
            ClassLoader l = SfHooks.lwjglClassLoader();
            Class<?> video = Class.forName("org.lwjgl.sdl.SDLVideo", false, l), props = Class.forName("org.lwjgl.sdl.SDLProperties", false, l);
            int id = (Integer) video.getMethod("SDL_GetWindowProperties", long.class).invoke(null, sdlWindow);
            arm((Long) props.getMethod("SDL_GetNumberProperty", int.class, CharSequence.class, long.class).invoke(null, id, "SDL.window.x11.window", 0L));
        } catch (Throwable t) { SfHooks.log("lifeboat (X11) unavailable: " + t); }
    }

    /** LWJGL 2: LinuxDisplay.getWindow() (static, package-private). */
    static void lwjgl2() {
        try {
            Method m = Class.forName("org.lwjgl.opengl.LinuxDisplay", false, SfHooks.lwjglClassLoader()).getDeclaredMethod("getWindow");
            m.setAccessible(true);
            arm((Long) m.invoke(null));
        } catch (Throwable t) { SfHooks.log("lifeboat (X11) unavailable: " + t); }
    }

    static long xwin() { return xwin; }

    private static long fdpy, fbuf;

    /**
     * Does the game window have the X keyboard focus? For SDL3 and LWJGL 2, which do not notice a focus the host gives
     * with XSetInputFocus. Render thread only (own connection). True when unknown.
     */
    static boolean hasFocus() {
        try {
            if (xwin == 0 || fnGetInputFocus == 0) return true;
            if (fdpy == 0) { fdpy = N.pp(0L, fnOpen); fbuf = N.calloc(16); }
            if (fdpy == 0) return true;
            N.pppi(fdpy, fbuf, fbuf + 8, fnGetInputFocus);
            return N.getLong(fbuf) == xwin;
        } catch (Throwable t) { return true; }
    }

    private static synchronized void arm(long x11Window) {
        if (xwin != 0) return;
        try {
            if (x11Window == 0) { SfHooks.log("lifeboat: not an X11 window (Wayland?), unavailable"); return; }
            N.init();
            fnOpen = N.addr("X11", "XOpenDisplay");
            fnGetAttrs = N.addr("X11", "XGetWindowAttributes");
            fnSetErrorHandler = N.addr("X11", "XSetErrorHandler");
            fnIgnore = N.addr("c", "getpid");
            fnSelectInput = N.addr("X11", "XSelectInput");
            fnNextEvent = N.addr("X11", "XNextEvent");
            fnQueryTree = N.addr("X11", "XQueryTree");
            fnFree = N.addr("X11", "XFree");
            fnReparent = N.addr("X11", "XReparentWindow");
            fnMap = N.addr("X11", "XMapWindow");
            fnUnmap = N.addr("X11", "XUnmapWindow");
            fnChangeAttrs = N.addr("X11", "XChangeWindowAttributes");
            fnFlush = N.addr("X11", "XFlush");
            fnGetInputFocus = N.addr("X11", "XGetInputFocus");
            if (fnOpen == 0 || fnGetAttrs == 0 || fnSetErrorHandler == 0 || fnIgnore == 0) { SfHooks.log("lifeboat: libX11 functions missing"); return; }
            attrs = N.calloc(ATTR_SIZE);
            xwin = x11Window;
            Thread t = new Thread(X11Lifeboat::watch, "sulfurframe-lifeboat");
            t.setDaemon(true);
            t.start();
            Thread r = new Thread(X11Lifeboat::rescue, "sulfurframe-x11-rescue");
            r.setDaemon(true);
            r.start();
            SfHooks.log("lifeboat armed for X11 window 0x" + Long.toHexString(xwin) + " (" + (N.jna ? "JNA" : "LWJGL JNI") + ")");
        } catch (Throwable t) {
            SfHooks.log("lifeboat (X11) unavailable: " + t);
        }
    }

    private static void watch() {
        try {
            dpy = N.pp(0L, fnOpen);   // our own connection: the toolkit's belongs to the render thread
            if (dpy == 0) { SfHooks.log("lifeboat: XOpenDisplay failed"); return; }
            boolean embedded = false;
            while (true) {
                Thread.sleep(250);
                boolean alive = N.pppi(dpy, xwin, attrs, fnGetAttrs) != 0;
                if (alive) {
                    boolean now = N.getInt(attrs + ATTR_OVERRIDE_REDIRECT) != 0;
                    if (now != embedded) {
                        embedded = now;
                        if (embedded) { previousHandler = N.pp(fnIgnore, fnSetErrorHandler); SfHooks.log("embedded: X errors made non-fatal"); }
                        else if (previousHandler != -1) { N.pp(previousHandler, fnSetErrorHandler); previousHandler = -1; SfHooks.log("detached: X error handler restored"); }
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

    private static long rdpy, root, tree;

    /**
     * When the host dies, the server handles its save-set first (our window lands in the WM's frame of the host window:
     * a ReparentNotify for us) and destroys the host's windows after that (the WM then destroys its frame). This
     * thread sits in XNextEvent and, on that ReparentNotify, moves the window to the root before the WM gets there.
     *
     * Any move of the embedded (override-redirect) window out of a canvas into something that is not the root is acted on
     * at once, without a query first. Afterwards: if the old parent (the canvas) still exists, it was the WM taking the
     * window back while the host embeds it, so it goes back into the canvas; if not, the host died (the server destroys
     * the dead client's windows in the same step as the save-set). It is a race with the WM: under WSLg it was lost with
     * GLFW 1.21.1 and won with SDL3 26.3. When lost, the poll thread asks the game to quit, with frames held.
     */
    private static void rescue() {
        try {
            long ev = N.calloc(192);            // XEvent
            tree = N.calloc(32);                // root, parent, children*, count
            long setAttrs = N.calloc(112);      // XSetWindowAttributes
            rdpy = N.pp(0L, fnOpen);
            if (rdpy == 0) return;
            long[] rp = queryTree(xwin);
            if (rp == null) return;
            root = rp[0];
            long prevParent = rp[1];
            boolean prevDeep = deep(prevParent);
            N.pppi(rdpy, xwin, 1L << 17 /* StructureNotifyMask */, fnSelectInput);
            while (true) {
                N.ppi(rdpy, ev, fnNextEvent);   // blocks
                if (N.getInt(ev) != 21 /* ReparentNotify */) continue;
                long w = N.getLong(ev + 40), parent = N.getLong(ev + 48);
                boolean override = N.getInt(ev + 64) != 0;
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
                N.p6(rdpy, xwin, root, 100, 100, 0, fnReparent);
                N.ppi(rdpy, xwin, fnMap);
                N.pi(rdpy, fnFlush);
                if (queryTree(oldParent) != null) {
                    // the canvas still exists: not a dead host but a WM taking the window back mid-embed; put it back
                    N.p6(rdpy, xwin, oldParent, 0, 0, 0, fnReparent);
                    N.ppi(rdpy, xwin, fnMap);
                    N.pi(rdpy, fnFlush);
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
                N.ppi(rdpy, xwin, fnUnmap);
                N.putInt(setAttrs + 88, 0);
                N.p6(rdpy, xwin, 1L << 9 /* CWOverrideRedirect */, setAttrs, 0, 0, fnChangeAttrs);
                N.ppi(rdpy, xwin, fnMap);
                N.pi(rdpy, fnFlush);
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
        if (N.p6(rdpy, w, tree, tree + 8, tree + 16, tree + 24, fnQueryTree) == 0) return null;
        long kids = N.getLong(tree + 16);
        if (kids != 0) N.pi(kids, fnFree);
        return new long[]{N.getLong(tree), N.getLong(tree + 8)};
    }

    // ------------------------------------------------------------------ native layer: LWJGL 3 JNI, or JNA

    /**
     * Calls a C function by address with pointer-sized arguments (ints passed as longs: same registers on LP64;
     * trailing extra arguments are ignored by the callee). Memory is plain malloc'd addresses either way.
     */
    static final class N {
        static boolean jna, ready;
        // LWJGL 3
        private static Method invPP, invPI, invPPI, invPPPI, invPPPPPPI, nmemCalloc, memGetInt, memGetLong, memPutInt, getFunctionAddress;
        private static Object libX11, libC;
        private static boolean addressFirst;
        // JNA
        private static Method nlGetInstance, nlGetFunction, ptrNativeValue, fnGetFunction, fnInvokeLong, fnInvokeInt, ptrGetInt, ptrGetLong, ptrSetInt, memClear;
        private static java.lang.reflect.Constructor<?> ptrNew, memNew;
        private static final List<Object> keep = new ArrayList<Object>();   // JNA Memory blocks must stay reachable

        static synchronized void init() throws Exception {
            if (ready) return;
            ClassLoader l = SfHooks.lwjglClassLoader();
            try {
                Class<?> jni = Class.forName("org.lwjgl.system.JNI", false, l);
                invPP = SfHooks.jniMethod(jni, "PP", long.class, long.class);
                invPI = SfHooks.jniMethod(jni, "PI", long.class, long.class);
                invPPI = SfHooks.jniMethod(jni, "PPI", long.class, long.class, long.class);
                invPPPI = SfHooks.jniMethod(jni, "PPPI", long.class, long.class, long.class, long.class);
                invPPPPPPI = SfHooks.jniMethod(jni, "PPPPPPI", long.class, long.class, long.class, long.class, long.class, long.class, long.class);
                Class<?> lib = Class.forName("org.lwjgl.system.linux.LinuxLibrary", false, l);
                getFunctionAddress = Class.forName("org.lwjgl.system.FunctionProvider", false, l).getMethod("getFunctionAddress", CharSequence.class);
                libX11 = lib.getConstructor(String.class).newInstance("libX11.so.6");
                libC = lib.getConstructor(String.class).newInstance("libc.so.6");
                Class<?> mem = Class.forName("org.lwjgl.system.MemoryUtil", false, l);
                nmemCalloc = mem.getMethod("nmemCalloc", long.class, long.class);
                memGetInt = mem.getMethod("memGetInt", long.class);
                memGetLong = mem.getMethod("memGetLong", long.class);
                memPutInt = mem.getMethod("memPutInt", long.class, int.class);
                Class<?> ver = Class.forName("org.lwjgl.Version", false, l);
                addressFirst = ver.getField("VERSION_MAJOR").getInt(null) == 3 && ver.getField("VERSION_MINOR").getInt(null) < 2;
                jna = false;
            } catch (ClassNotFoundException noLwjgl3) {
                Class<?> nl = Class.forName("com.sun.jna.NativeLibrary", false, l), fn = Class.forName("com.sun.jna.Function", false, l);
                Class<?> ptr = Class.forName("com.sun.jna.Pointer", false, l), mem = Class.forName("com.sun.jna.Memory", false, l);
                nlGetInstance = nl.getMethod("getInstance", String.class);
                nlGetFunction = nl.getMethod("getFunction", String.class);
                ptrNativeValue = ptr.getMethod("nativeValue", ptr);
                ptrNew = ptr.getConstructor(long.class);
                fnGetFunction = fn.getMethod("getFunction", ptr);
                fnInvokeLong = fn.getMethod("invokeLong", Object[].class);
                fnInvokeInt = fn.getMethod("invokeInt", Object[].class);
                ptrGetInt = ptr.getMethod("getInt", long.class);
                ptrGetLong = ptr.getMethod("getLong", long.class);
                ptrSetInt = ptr.getMethod("setInt", long.class, int.class);
                memNew = mem.getConstructor(long.class);
                memClear = mem.getMethod("clear");
                jna = true;
            }
            ready = true;
        }

        static long addr(String lib, String name) throws Exception {
            if (!jna) return (Long) getFunctionAddress.invoke(lib.equals("c") ? libC : libX11, name);
            Object f = nlGetFunction.invoke(nlGetInstance.invoke(null, lib), name);
            return (Long) ptrNativeValue.invoke(null, f);
        }

        static synchronized long calloc(long n) throws Exception {
            if (!jna) return (Long) nmemCalloc.invoke(null, 1L, n);
            Object m = memNew.newInstance(n);
            memClear.invoke(m);
            keep.add(m);
            return (Long) ptrNativeValue.invoke(null, m);
        }

        static int getInt(long a) throws Exception { return jna ? (Integer) ptrGetInt.invoke(ptrNew.newInstance(a), 0L) : (Integer) memGetInt.invoke(null, a); }
        static long getLong(long a) throws Exception { return jna ? (Long) ptrGetLong.invoke(ptrNew.newInstance(a), 0L) : (Long) memGetLong.invoke(null, a); }
        static void putInt(long a, int v) throws Exception { if (jna) ptrSetInt.invoke(ptrNew.newInstance(a), 0L, v); else memPutInt.invoke(null, a, v); }

        private static Object jfn(long fn) throws Exception { return fnGetFunction.invoke(null, ptrNew.newInstance(fn)); }
        private static Object[] args(long... a) { Object[] o = new Object[a.length]; for (int i = 0; i < a.length; i++) o[i] = a[i]; return o; }

        static long pp(long a, long fn) throws Exception {
            if (jna) return (Long) fnInvokeLong.invoke(jfn(fn), (Object) args(a));
            return (Long) (addressFirst ? invPP.invoke(null, fn, a) : invPP.invoke(null, a, fn));
        }
        static int pi(long a, long fn) throws Exception {
            if (jna) return (Integer) fnInvokeInt.invoke(jfn(fn), (Object) args(a));
            return (Integer) (addressFirst ? invPI.invoke(null, fn, a) : invPI.invoke(null, a, fn));
        }
        static int ppi(long a, long b, long fn) throws Exception {
            if (jna) return (Integer) fnInvokeInt.invoke(jfn(fn), (Object) args(a, b));
            return (Integer) (addressFirst ? invPPI.invoke(null, fn, a, b) : invPPI.invoke(null, a, b, fn));
        }
        static int pppi(long a, long b, long c, long fn) throws Exception {
            if (jna) return (Integer) fnInvokeInt.invoke(jfn(fn), (Object) args(a, b, c));
            return (Integer) (addressFirst ? invPPPI.invoke(null, fn, a, b, c) : invPPPI.invoke(null, a, b, c, fn));
        }
        static int p6(long a, long b, long c, long d, long e, long f, long fn) throws Exception {
            if (jna) return (Integer) fnInvokeInt.invoke(jfn(fn), (Object) args(a, b, c, d, e, f));
            return (Integer) (addressFirst ? invPPPPPPI.invoke(null, fn, a, b, c, d, e, f) : invPPPPPPI.invoke(null, a, b, c, d, e, f, fn));
        }

        // LWJGL 3 only (a GLFW window): invokers whose parameters are all long, three and eight arguments. LWJGL's
        // eight-pointer "...I" invoker mixes ints into its parameters (long, int, int, long...), which does not fit
        // XChangeProperty; an int slot would leave the upper half of a 64-bit argument undefined. On x86-64 every
        // int argument travels in a 64-bit register or stack slot, so passing ints as longs is safe; a returned int
        // is read as a long and ignored.
        private static Method invPPPP, invP8I, memPutByte;

        private static synchronized void initMore() throws Exception {
            if (invPPPP != null) return;
            if (jna) throw new IllegalStateException("needs LWJGL 3");
            ClassLoader l = SfHooks.lwjglClassLoader();
            Class<?> jni = Class.forName("org.lwjgl.system.JNI", false, l);
            invPPPP = SfHooks.jniMethod(jni, "PPPP", long.class, long.class, long.class, long.class);
            invP8I = SfHooks.jniMethod(jni, "PPPPPPPPP", long.class, long.class, long.class, long.class, long.class, long.class, long.class, long.class, long.class);
            memPutByte = Class.forName("org.lwjgl.system.MemoryUtil", false, l).getMethod("memPutByte", long.class, byte.class);
        }

        static long ppp(long a, long b, long c, long fn) throws Exception {
            initMore();
            return (Long) (addressFirst ? invPPPP.invoke(null, fn, a, b, c) : invPPPP.invoke(null, a, b, c, fn));
        }

        static long p8(long a, long b, long c, long d, long e, long f, long g, long h, long fn) throws Exception {
            initMore();
            return (Long) (addressFirst ? invP8I.invoke(null, fn, a, b, c, d, e, f, g, h) : invP8I.invoke(null, a, b, c, d, e, f, g, h, fn));
        }

        /** A NUL-terminated ASCII copy of {@code s} in native memory (never freed: a few bytes, once). */
        static long cstring(String s) throws Exception {
            initMore();
            long p = calloc(s.length() + 1);
            for (int i = 0; i < s.length(); i++) memPutByte.invoke(null, p + i, (byte) s.charAt(i));
            return p;
        }
    }

    /**
     * EWMH "do not focus this window when it is mapped": _NET_WM_USER_TIME = 0 (CARDINAL, format 32). Set on a new game
     * window before it is first shown, so the window manager leaves the focus where it is (openbox focused every new
     * top-level window otherwise, whatever GLFW asked). {@code dpy} is GLFW's own connection, so the property reaches
     * the server before GLFW's map request.
     */
    static void markNoFocusOnMap(long dpy, long win) throws Exception {
        N.init();
        long atom = N.ppp(dpy, N.cstring("_NET_WM_USER_TIME"), 0, N.addr("X11", "XInternAtom"));
        if (atom == 0) throw new IllegalStateException("no _NET_WM_USER_TIME atom");
        long zero = N.calloc(8);   // format 32 data is an array of C long in Xlib: one 0
        N.p8(dpy, win, atom, 6 /* XA_CARDINAL */, 32, 0 /* PropModeReplace */, zero, 1, N.addr("X11", "XChangeProperty"));
    }

    private X11Lifeboat() {}
}
