import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

import static java.lang.foreign.ValueLayout.*;

/**
 * Linux/X11 embedding PoC: the X11 counterpart of EmbedHost's REPARENT mode.
 *
 * A Swing frame (XToolkit) with a heavyweight Canvas; the game's X window is moved under the canvas's X window with
 * XReparentWindow and kept at the canvas size. libX11 is called through FFM (JDK 22+), on our own Display connection.
 *
 * The game window goes into our save-set (XAddToSaveSet): if this host dies, however it dies, the X server moves the
 * game window back to the root and keeps it. That is what Windows lacks (a killed parent takes its child windows with
 * it, hence the agent's lifeboat there).
 *
 * Usage (DISPLAY must be an X server; under WSLg unset WAYLAND_DISPLAY for the game so GLFW picks X11):
 *   java --add-opens java.desktop/sun.awt=ALL-UNNAMED --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED \
 *        --enable-native-access=ALL-UNNAMED X11Host.java [--hold <s>] [--halt]
 *   --hold <s>   keep the game embedded for s seconds before ending (default 20)
 *   --halt       end with Runtime.halt (a crash, no cleanup) instead of a normal detach
 *   --focus-test <game pid>  compare the game's CPU with keyboard focus vs focus on the host (agent FPS cap)
 * Prints "RESULT <PASS|FAIL> <check> [detail]" lines.
 */
public class X11Host {
    static final Linker L = Linker.nativeLinker();
    static final SymbolLookup X = SymbolLookup.libraryLookup("libX11.so.6", Arena.global());
    static MethodHandle fn(String name, MemoryLayout ret, MemoryLayout... args) {
        return L.downcallHandle(X.find(name).orElseThrow(), ret == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(ret, args));
    }
    static final MethodHandle XInitThreads = fn("XInitThreads", JAVA_INT);
    static final MethodHandle XOpenDisplay = fn("XOpenDisplay", ADDRESS, ADDRESS);
    static final MethodHandle XDefaultRootWindow = fn("XDefaultRootWindow", JAVA_LONG, ADDRESS);
    static final MethodHandle XQueryTree = fn("XQueryTree", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    static final MethodHandle XFetchName = fn("XFetchName", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS);
    static final MethodHandle XFree = fn("XFree", JAVA_INT, ADDRESS);
    static final MethodHandle XReparentWindow = fn("XReparentWindow", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
    static final MethodHandle XMoveResizeWindow = fn("XMoveResizeWindow", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    static final MethodHandle XMapWindow = fn("XMapWindow", JAVA_INT, ADDRESS, JAVA_LONG);
    static final MethodHandle XAddToSaveSet = fn("XAddToSaveSet", JAVA_INT, ADDRESS, JAVA_LONG);
    static final MethodHandle XSetInputFocus = fn("XSetInputFocus", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG);
    static final MethodHandle XGetGeometry = fn("XGetGeometry", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    static final MethodHandle XSync = fn("XSync", JAVA_INT, ADDRESS, JAVA_INT);
    static final MethodHandle XGetWindowProperty = fn("XGetWindowProperty", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG,
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    static final MethodHandle XGetWindowAttributes = fn("XGetWindowAttributes", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS);
    static final MethodHandle XWithdrawWindow = fn("XWithdrawWindow", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT);
    static final MethodHandle XDefaultScreen = fn("XDefaultScreen", JAVA_INT, ADDRESS);
    static final MethodHandle XChangeWindowAttributes = fn("XChangeWindowAttributes", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS);

    static MemorySegment dpy;
    static long root;
    static volatile long game, canvasWin;
    static int failures;
    static long gamePid;

    /** CPU use of a process over a few seconds, in % of one core (utime + stime from /proc, 100 ticks/s). */
    static double cpu(long pid, int seconds) throws Exception {
        long a = ticks(pid), t0 = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        long b = ticks(pid);
        return (b - a) / 100.0 / ((System.nanoTime() - t0) / 1e9) * 100;
    }

    /**
     * Only the threads that draw: the render thread and Mesa's llvmpipe workers. The integrated server and chunk
     * workers are busy right after joining a world whatever the frame rate, and would drown the difference.
     */
    static long ticks(long pid) throws Exception {
        long sum = 0;
        try (var tasks = java.nio.file.Files.list(java.nio.file.Path.of("/proc/" + pid + "/task"))) {
            for (var t : tasks.toList()) {
                String s;
                try { s = java.nio.file.Files.readString(t.resolve("stat")); } catch (Exception e) { continue; }
                String comm = s.substring(s.indexOf('(') + 1, s.lastIndexOf(')'));
                if (!comm.startsWith("Render thread") && !comm.startsWith("llvmpipe")) continue;
                String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
                sum += Long.parseLong(f[11]) + Long.parseLong(f[12]);   // utime, stime (fields 14, 15)
            }
        }
        return sum;
    }

    public static void main(String[] a) throws Throwable {
        int hold = 20; boolean halt = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals("--hold")) hold = Integer.parseInt(a[++i]);
            if (a[i].equals("--halt")) halt = true;
            if (a[i].equals("--focus-test")) gamePid = Long.parseLong(a[++i]);
        }
        // AWT first: XToolkit installs a process-wide X error handler that logs instead of exiting (Xlib's default),
        // and walking the tree races windows that disappear (BadWindow would otherwise kill the host)
        java.awt.Toolkit.getDefaultToolkit();
        int unused = (int) XInitThreads.invokeExact();
        dpy = (MemorySegment) XOpenDisplay.invokeExact(MemorySegment.NULL);
        if (dpy.address() == 0) { System.out.println("RESULT FAIL open display (DISPLAY=" + System.getenv("DISPLAY") + ")"); System.exit(1); }
        root = (long) XDefaultRootWindow.invokeExact(dpy);

        for (int i = 0; i < 240 && game == 0; i++) { game = find(root, "Minecraft"); if (game == 0) Thread.sleep(500); }
        check("game window found", game != 0, hex(game) + " parent " + hex(parentOf(game)));
        if (game == 0) System.exit(1);

        var frame = new JFrame("SulfurFrame X11 PoC");
        var canvas = new Canvas() {
            @Override public void paint(Graphics g) { g.setColor(Color.DARK_GRAY); g.fillRect(0, 0, getWidth(), getHeight()); }
        };
        var editor = new JTextArea("// pretend this is the code editor\n");
        SwingUtilities.invokeAndWait(() -> {
            frame.setLayout(new BorderLayout());
            frame.add(new JScrollPane(editor), BorderLayout.WEST);
            editor.setColumns(30);
            frame.add(canvas, BorderLayout.CENTER);
            frame.setSize(1100, 650);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setVisible(true);
        });
        for (int i = 0; i < 40 && canvasWin == 0; i++) { Thread.sleep(250); canvasWin = xWindowOf(canvas); }
        check("canvas X window", canvasWin != 0, hex(canvasWin));
        if (canvasWin == 0) System.exit(1);

        // embed
        int r;
        // The window manager owns a mapped toplevel (WSLg's Weston WM frames it, and on unmap parks it under its own
        // hidden "Weston WM" window). So: withdraw it, mark it override-redirect (a WM leaves those alone), let the WM
        // finish with it, then move it under the canvas and map it there.
        int screen = (int) XDefaultScreen.invokeExact(dpy);
        long frameParent = parentOf(game);
        r = (int) XWithdrawWindow.invokeExact(dpy, game, screen);
        setOverrideRedirect(game, true);
        r = (int) XSync.invokeExact(dpy, 0);
        long t0 = System.currentTimeMillis();   // the WM unframes asynchronously: wait until it has moved the window
        while (frameParent != root && parentOf(game) == frameParent && System.currentTimeMillis() - t0 < 2000) Thread.sleep(20);
        Thread.sleep(100);
        addToAwtSaveSet(game);
        boolean stays = false;
        int attempts = 0;
        while (!stays && attempts++ < 5) {   // if the WM still grabs it back, take it again
            r = (int) XReparentWindow.invokeExact(dpy, game, canvasWin, 0, 0);
            r = (int) XMapWindow.invokeExact(dpy, game);
            fit(canvas);
            r = (int) XSync.invokeExact(dpy, 0);
            stays = true;
            for (int i = 0; i < 10; i++) { Thread.sleep(100); stays &= parentOf(game) == canvasWin; }
        }
        check("reparented under the canvas (and stays)", stays, "parent " + hex(parentOf(game)) + ", unframed after "
                + (System.currentTimeMillis() - t0) + " ms, " + attempts + " attempt(s)");
        Thread.sleep(1000);
        check("game sized to the canvas", sizeMatches(canvas), size(game) + " vs canvas " + canvas.getWidth() + "x" + canvas.getHeight());

        canvas.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { try { fit(canvas); } catch (Throwable ignored) { } }
        });
        canvas.addMouseListener(new MouseAdapter() {   // click-to-focus: keyboard to the game
            @Override public void mousePressed(MouseEvent e) { try { focusGame(); } catch (Throwable ignored) { } }
        });
        SwingUtilities.invokeAndWait(() -> frame.setSize(1300, 780));
        Thread.sleep(1500);
        check("game follows a host resize", sizeMatches(canvas), size(game) + " vs canvas " + canvas.getWidth() + "x" + canvas.getHeight());

        focusGame();
        if (gamePid > 0) {   // --focus-test: the agent caps the frame rate while the "editor" (host) has the keyboard
            Thread.sleep(3000);
            double busy = cpu(gamePid, 6);
            int rr = (int) XSetInputFocus.invokeExact(dpy, canvasWin, 1, 0L);
            rr = (int) XSync.invokeExact(dpy, 0);
            Thread.sleep(3000);
            double coding = cpu(gamePid, 6);
            focusGame();
            check("agent caps the game while the editor has focus", coding < busy * 0.6,
                    String.format("render + llvmpipe CPU %.0f%% with focus, %.0f%% while coding", busy, coding));
        }
        System.out.println("[host] embedded; holding " + hold + " s" + (halt ? ", then halting (crash)" : ", then detaching"));
        Thread.sleep(hold * 1000L);

        if (halt) { System.out.println("[host] halt"); System.out.flush(); Runtime.getRuntime().halt(3); }
        r = (int) XWithdrawWindow.invokeExact(dpy, game, screen);
        setOverrideRedirect(game, false);
        r = (int) XReparentWindow.invokeExact(dpy, game, root, 100, 100);
        r = (int) XMapWindow.invokeExact(dpy, game);   // map request goes to the WM: framed as a normal window again
        r = (int) XSync.invokeExact(dpy, 0);
        Thread.sleep(300);
        check("detached back to the root", parentOf(game) != canvasWin, "parent " + hex(parentOf(game)));
        System.out.println("failures: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * The save-set must belong to the connection that created the canvas: when a client goes away, the server only
     * rescues windows in that client's save-set from the windows that client created. The canvas is AWT's, made on
     * XToolkit's own Display connection, so the call goes through that connection (under the AWT lock).
     */
    static void addToAwtSaveSet(long w) throws Throwable {
        Class<?> tk = Class.forName("sun.awt.X11.XToolkit");
        Class<?> sun = Class.forName("sun.awt.SunToolkit");
        Method lock = sun.getDeclaredMethod("awtLock"), unlock = sun.getDeclaredMethod("awtUnlock"), getDisplay = tk.getDeclaredMethod("getDisplay");
        lock.setAccessible(true); unlock.setAccessible(true); getDisplay.setAccessible(true);
        var awtDpy = MemorySegment.ofAddress((long) getDisplay.invoke(null));
        lock.invoke(null);
        try {
            int r = (int) XAddToSaveSet.invokeExact(awtDpy, w);
            r = (int) XSync.invokeExact(awtDpy, 0);
        } finally {
            unlock.invoke(null);
        }
    }

    /** XChangeWindowAttributes(CWOverrideRedirect). XSetWindowAttributes on LP64: override_redirect at offset 88, 112 bytes. */
    static void setOverrideRedirect(long w, boolean on) throws Throwable {
        try (var ar = Arena.ofConfined()) {
            var attrs = ar.allocate(112, 8);
            attrs.set(JAVA_INT, 88, on ? 1 : 0);
            int r = (int) XChangeWindowAttributes.invokeExact(dpy, w, 1L << 9, attrs);
        }
    }

    static void focusGame() throws Throwable {
        int r = (int) XSetInputFocus.invokeExact(dpy, game, 1 /* RevertToPointerRoot */, 0L /* CurrentTime */);
        r = (int) XSync.invokeExact(dpy, 0);
    }

    static void fit(Canvas c) throws Throwable {
        int w = Math.max(1, c.getWidth()), h = Math.max(1, c.getHeight());
        int r = (int) XMoveResizeWindow.invokeExact(dpy, game, 0, 0, w, h);
        r = (int) XSync.invokeExact(dpy, 0);
    }

    static boolean sizeMatches(Canvas c) throws Throwable {
        int[] s = geometry(game);
        return s != null && s[2] == c.getWidth() && s[3] == c.getHeight();
    }

    static String size(long w) throws Throwable { int[] s = geometry(w); return s == null ? "?" : s[2] + "x" + s[3]; }

    /** x, y, width, height */
    static int[] geometry(long w) throws Throwable {
        try (var ar = Arena.ofConfined()) {
            var rootOut = ar.allocate(JAVA_LONG);
            var x = ar.allocate(JAVA_INT); var y = ar.allocate(JAVA_INT);
            var ww = ar.allocate(JAVA_INT); var hh = ar.allocate(JAVA_INT);
            var bw = ar.allocate(JAVA_INT); var depth = ar.allocate(JAVA_INT);
            int ok = (int) XGetGeometry.invokeExact(dpy, w, rootOut, x, y, ww, hh, bw, depth);
            if (ok == 0) return null;
            return new int[]{x.get(JAVA_INT, 0), y.get(JAVA_INT, 0), ww.get(JAVA_INT, 0), hh.get(JAVA_INT, 0)};
        }
    }

    static long parentOf(long w) throws Throwable {
        try (var ar = Arena.ofConfined()) {
            var rootOut = ar.allocate(JAVA_LONG); var parent = ar.allocate(JAVA_LONG);
            var kids = ar.allocate(ADDRESS); var n = ar.allocate(JAVA_INT);
            int ok = (int) XQueryTree.invokeExact(dpy, w, rootOut, parent, kids, n);
            if (ok == 0) return 0;
            var k = kids.get(ADDRESS, 0);
            if (k.address() != 0) { int f = (int) XFree.invokeExact(k); }
            return parent.get(JAVA_LONG, 0);
        }
    }

    /**
     * WM_NAME as raw bytes in UTF-8, whatever its type: XFetchName only returns STRING-typed names, and SDL3 stores
     * UTF8_STRING.
     */
    static String wmName(long w) throws Throwable {
        try (var ar = Arena.ofConfined()) {
            var type = ar.allocate(JAVA_LONG); var format = ar.allocate(JAVA_INT); var n = ar.allocate(JAVA_LONG);
            var after = ar.allocate(JAVA_LONG); var data = ar.allocate(ADDRESS);
            int r = (int) XGetWindowProperty.invokeExact(dpy, w, 39L /* XA_WM_NAME */, 0L, 256L, 0, 0L /* AnyPropertyType */, type, format, n, after, data);
            var p = data.get(ADDRESS, 0);
            String s = "";
            if (r == 0 && p.address() != 0) s = new String(p.reinterpret(n.get(JAVA_LONG, 0)).toArray(JAVA_BYTE), java.nio.charset.StandardCharsets.UTF_8);
            if (p.address() != 0) { int f = (int) XFree.invokeExact(p); }
            return s;
        }
    }

    /** map_state == IsViewable (XWindowAttributes on LP64: map_state at 92). */
    static boolean viewable(long w) throws Throwable {
        try (var ar = Arena.ofConfined()) {
            var attrs = ar.allocate(136, 8);
            return (int) XGetWindowAttributes.invokeExact(dpy, w, attrs) != 0 && attrs.get(JAVA_INT, 92) == 2;
        }
    }

    /** Depth-first search for a window whose WM_NAME starts with prefix (WSLg's window manager frames toplevels). */
    static long find(long w, String prefix) throws Throwable {
        try (var ar = Arena.ofConfined()) {
            if (wmName(w).startsWith(prefix) && viewable(w)) return w;   // SDL3 also has a hidden "Minecraft - ..." helper window
            var rootOut = ar.allocate(JAVA_LONG); var parent = ar.allocate(JAVA_LONG);
            var kids = ar.allocate(ADDRESS); var n = ar.allocate(JAVA_INT);
            if ((int) XQueryTree.invokeExact(dpy, w, rootOut, parent, kids, n) == 0) return 0;
            int count = n.get(JAVA_INT, 0);
            var k = kids.get(ADDRESS, 0);
            if (k.address() == 0) return 0;
            long[] ids = k.reinterpret(8L * count).toArray(JAVA_LONG);
            int f = (int) XFree.invokeExact(k);
            for (long id : ids) { long hit = find(id, prefix); if (hit != 0) return hit; }
            return 0;
        }
    }

    /** The canvas's own X window, from the XToolkit peer (sun.awt.X11.XBaseWindow.getWindow). */
    static long xWindowOf(Component c) {
        try {
            Object acc = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null);
            Method gp = Class.forName("sun.awt.AWTAccessor$ComponentAccessor").getMethod("getPeer", Component.class);
            Object peer = gp.invoke(acc, c);
            if (peer == null) return 0;
            for (Class<?> k = peer.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    Method m = k.getDeclaredMethod("getWindow");
                    m.setAccessible(true);
                    return (long) m.invoke(peer);
                } catch (NoSuchMethodException ignored) { }
            }
        } catch (Exception e) {
            System.out.println("[host] canvas window: " + e);
        }
        return 0;
    }

    static void check(String what, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println("RESULT " + (ok ? "PASS" : "FAIL") + " " + what + "  [" + detail + "]");
    }

    static String hex(long v) { return "0x" + Long.toHexString(v); }
}
