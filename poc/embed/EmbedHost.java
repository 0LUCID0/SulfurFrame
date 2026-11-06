import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * SulfurFrame embedding PoC host — stands in for the IntelliJ ToolWindow.
 *
 * Left: a text area that plays the role of the code editor.
 * Right: a heavyweight AWT Canvas whose native HWND becomes the parent of the game window.
 *
 * Two embedding strategies:
 *   REPARENT — SetParent + WS_CHILD (true child window; input queues of both processes get attached)
 *   OVERLAY  — target stays a top-level popup, owned by the host frame, kept on top of the canvas
 *
 * Usage:
 *   java EmbedHost.java                      interactive
 *   java EmbedHost.java --selftest [--robot] automated checks, prints RESULT lines, exits
 *   java EmbedHost.java --attach <pid>       embed an already running process (e.g. Minecraft)
 */
public class EmbedHost {
    enum Mode { REPARENT, OVERLAY }

    static final String TITLE = "SulfurFrame Embed PoC #" + ProcessHandle.current().pid();

    final JFrame frame = new JFrame(TITLE);
    final JTextArea editor = new JTextArea("// pretend this is the code editor\n");
    final JTextArea log = new JTextArea(8, 80);
    final Canvas viewport = new Canvas() {
        @Override public void update(Graphics g) { /* never clear over the child */ }
        @Override public void paint(Graphics g) { if (target == 0) { g.setColor(Color.DARK_GRAY); g.fillRect(0, 0, getWidth(), getHeight()); } }
    };
    final JLabel status = new JLabel("idle");

    volatile Process targetProc;
    volatile long targetPid;
    volatile long target;           // game HWND
    volatile long savedStyle, savedExStyle;
    volatile Mode mode;
    final List<String> targetLog = new CopyOnWriteArrayList<>();
    final EdtWatch edtWatch = new EdtWatch();
    final Throttler throttler = new Throttler(this::log, 5000);
    javax.swing.Timer overlayTimer;

    public static void main(String[] args) throws Exception {
        var a = List.of(args);
        var host = new EmbedHost();
        host.hostClickToFocus = !a.contains("--no-host-focus");
        if (a.contains("--agent")) host.agentJar = Path.of(a.get(a.indexOf("--agent") + 1)).toAbsolutePath();
        SwingUtilities.invokeAndWait(host::buildUi);
        host.edtWatch.start();
        host.startHangWatchdog();
        if (a.contains("--selftest")) {
            long pid = a.contains("--attach") ? Long.parseLong(a.get(a.indexOf("--attach") + 1)) : 0;
            int code = new SelfTest(host, a.contains("--robot"), pid).run();
            System.exit(code);
        } else if (a.contains("--throttle-probe")) {
            long pid = Long.parseLong(a.get(a.indexOf("--attach") + 1));
            System.exit(new ThrottleProbe(host, pid).run());
        } else if (a.contains("--snapshot")) {
            // embed, let it render, save a screenshot of the whole host window, detach, exit
            long pid = Long.parseLong(a.get(a.indexOf("--attach") + 1));
            var out = Path.of(a.get(a.indexOf("--snapshot") + 1));
            host.attachPid(pid, Mode.REPARENT);
            onEdt(host.frame::toFront);
            sleep(3000);
            var bounds = onEdt(() -> host.frame.getBounds());
            var img = new Robot().createScreenCapture(bounds);
            javax.imageio.ImageIO.write(img, "png", out.toFile());
            host.log("[host] snapshot -> " + out.toAbsolutePath());
            onEdt(host::detach);
            System.exit(0);
        } else if (a.contains("--attach")) {
            long pid = Long.parseLong(a.get(a.indexOf("--attach") + 1));
            host.attachPid(pid, a.contains("--overlay") ? Mode.OVERLAY : Mode.REPARENT);
        }
    }

    // ---------------------------------------------------------------- UI

    void buildUi() {
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        viewport.setBackground(Color.DARK_GRAY);
        viewport.setPreferredSize(new Dimension(854, 480));

        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(editor), viewport);
        split.setResizeWeight(0.35);

        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(button("Launch SDL target (reparent)", () -> launchTarget(Mode.REPARENT)));
        buttons.add(button("Launch SDL target (overlay)", () -> launchTarget(Mode.OVERLAY)));
        buttons.add(button("Attach PID…", () -> {
            var s = JOptionPane.showInputDialog(frame, "PID of the process to embed (e.g. Minecraft):");
            if (s != null && !s.isBlank()) attachPid(Long.parseLong(s.trim()), Mode.REPARENT);
        }));
        buttons.add(button("Detach", this::detach));
        buttons.add(button("Hide/show viewport", () -> setViewportHidden(throttler.state() != Throttler.State.HIDDEN)));
        buttons.add(button("Stall target 3s", () -> sendToTarget("stall 3000")));
        buttons.add(button("Sync resize (blocking)", () -> timedResize(false)));
        buttons.add(status);

        var bottom = new JPanel(new BorderLayout());
        bottom.add(buttons, BorderLayout.NORTH);
        bottom.add(new JScrollPane(log), BorderLayout.CENTER);

        frame.setLayout(new BorderLayout());
        frame.add(split, BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { shutdown(); System.exit(0); }
            // IDE minimized = viewport not visible
            @Override public void windowIconified(WindowEvent e) { setViewportHidden(true); }
            @Override public void windowDeiconified(WindowEvent e) { setViewportHidden(false); }
        });
        viewport.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { syncBounds(); }
        });
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e) { syncBounds(); }
        });
        installFocusReclaim();
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        new javax.swing.Timer(250, e -> status.setText(String.format(
                "EDT max stall (last 2s): %d ms%s", edtWatch.recentMaxMs(),
                target != 0 && Win32.isWindow(target) && Win32.isHung(target) ? "  | TARGET NOT RESPONDING" : ""))).start();
    }

    // ---------------------------------------------------------------- focus + hang handling (REPARENT)

    volatile boolean inputDetached;
    volatile boolean hangGuard = true, focusReclaim = true, hostClickToFocus = true;

    // ---- click-to-focus without a mod: subclass the canvas and watch WM_PARENTNOTIFY
    private long subclassedCanvas, originalCanvasProc, clickToFocusStub;

    /**
     * A WS_CHILD window does not take keyboard focus when clicked, and vanilla Minecraft does nothing about it.
     * But a child's mouse-down is reported to its parent as WM_PARENTNOTIFY. The canvas is ours, so we subclass
     * its WNDPROC and call SetFocus(game). That runs on the AWT toolkit thread, whose input queue SetParent has
     * attached to the game's, so the cross-process SetFocus is allowed.
     */
    void installClickToFocus(long canvas) {
        if (subclassedCanvas == canvas) return;
        removeClickToFocus();
        if (clickToFocusStub == 0) clickToFocusStub = Win32.wndProcStub(this::canvasProc);
        originalCanvasProc = Win32.setLongPtr(canvas, Win32.GWLP_WNDPROC, clickToFocusStub);
        subclassedCanvas = canvas;
        log("[host] canvas subclassed for click-to-focus (orig proc " + Win32.hex(originalCanvasProc) + ")");
    }

    void removeClickToFocus() {
        if (subclassedCanvas == 0) return;
        if (Win32.isWindow(subclassedCanvas)) Win32.setLongPtr(subclassedCanvas, Win32.GWLP_WNDPROC, originalCanvasProc);
        subclassedCanvas = 0;
    }

    /** Runs on the AWT toolkit thread. Must never throw (an exception in an upcall kills the JVM). */
    static final int WM_SF_FOCUS_GAME = Win32.WM_APP + 0x51;

    /**
     * SetFocus(game) issued from the AWT toolkit thread "succeeds" but AWT immediately takes focus back.
     * When the game's own thread takes focus, AWT leaves it alone. So: do it from a helper thread that is not AWT's,
     * temporarily attached to the game's input queue.
     */
    private final ExecutorService focusHelper = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().name("sf-focus").unstarted(r));

    void focusGameFromHelper(long g) {
        Win32.ensureGuiThread();
        int me = Win32.currentThreadId(), gameTid = Win32.threadIdOf(g);
        boolean attached = Win32.attachThreadInput(me, gameTid, true);
        try {
            long prev = Win32.setFocus(g);
            log("[host] click in game -> SetFocus(game) attached=" + attached + " prev=" + Win32.hex(prev) + " now=" + Win32.hex(Win32.getFocus()));
            if (Win32.getFocus() == g) throttler.onGameFocused();
        } finally {
            if (attached) Win32.attachThreadInput(me, gameTid, false);
        }
    }

    private long canvasProc(long hwnd, int msg, long wParam, long lParam) {
        try {
            if (msg == Win32.WM_PARENTNOTIFY) {
                int ev = (int) (wParam & 0xFFFF);
                if (ev == Win32.WM_LBUTTONDOWN || ev == Win32.WM_RBUTTONDOWN || ev == Win32.WM_MBUTTONDOWN || ev == Win32.WM_XBUTTONDOWN) {
                    // AWT reacts to the same click (activation, focus proxy) after us; do our SetFocus once it is done
                    Win32.postMessage(hwnd, WM_SF_FOCUS_GAME, 0, 0);
                }
            } else if (msg == WM_SF_FOCUS_GAME) {
                long g = target;
                // SetFocus sends WM_SETFOCUS to the game synchronously: never do it while the game is hung,
                // or the toolkit thread (and with it the whole IDE UI) would wait for it
                if (g != 0 && mode == Mode.REPARENT && !inputDetached && Win32.isResponsive(g, 100)) focusHelper.execute(() -> focusGameFromHelper(g));
                return 0;
            }
        } catch (Throwable ignored) {
        }
        return Win32.callWindowProc(originalCanvasProc, hwnd, msg, wParam, lParam);
    }

    /**
     * Clicking a lightweight Swing component does not move native focus away from a foreign child window,
     * so keys keep going to the game. On any press outside the viewport, ask AWT for a real (native) focus request.
     */
    void installFocusReclaim() {
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (!focusReclaim || e.getID() != MouseEvent.MOUSE_PRESSED || target == 0 || mode != Mode.REPARENT) return;
            if (!(e.getSource() instanceof Component c) || c == viewport) return;
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
            c.requestFocus(FocusEvent.Cause.MOUSE_EVENT);
            throttler.onEditorFocused();
        }, AWTEvent.MOUSE_EVENT_MASK);
    }

    /**
     * SetParent attaches the game's input queue to ours. While the game is not pumping messages, focus changes
     * wait on it. Watchdog: probe with WM_NULL; if it stops answering, detach the queues; re-attach on recovery.
     */
    void startHangWatchdog() {
        Thread.ofPlatform().daemon().name("hang-watchdog").start(() -> {
            int misses = 0;
            while (true) {
                sleep(100);
                long h = target;
                if (!hangGuard || h == 0 || mode != Mode.REPARENT || !Win32.isWindow(h)) { misses = 0; continue; }
                // focus hand-offs make the game briefly wait on us, so one slow answer is not a hang
                misses = Win32.isResponsive(h, 200) ? 0 : misses + 1;
                boolean ok = misses == 0;
                int hostTid = Win32.threadIdOf(frameHwnd()), gameTid = Win32.threadIdOf(h);
                if (misses >= 2 && !inputDetached) {
                    boolean r = Win32.attachThreadInput(gameTid, hostTid, false);
                    inputDetached = true;
                    log("[host] game not responding -> input queues detached (" + r + ")");
                } else if (ok && inputDetached) {
                    boolean r = Win32.attachThreadInput(gameTid, hostTid, true);
                    inputDetached = false;
                    log("[host] game responsive again -> input queues re-attached (" + r + ")");
                }
            }
        });
    }

    JButton button(String text, Runnable r) {
        var b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    void log(String s) {
        System.out.println(s);
        SwingUtilities.invokeLater(() -> { log.append(s + "\n"); log.setCaretPosition(log.getDocument().getLength()); });
    }

    // ---------------------------------------------------------------- target process

    static String javaExe() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Classpath for the SDL target: LWJGL 3.4.3 (same version as Minecraft 26.3) from the local Gradle cache. */
    static String lwjglClasspath() throws IOException {
        var root = Path.of(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1", "org.lwjgl");
        var wanted = List.of("lwjgl", "lwjgl-sdl", "lwjgl-opengl");
        var jars = new ArrayList<String>();
        for (var mod : wanted) {
            try (var s = Files.walk(root.resolve(mod).resolve("3.4.3"))) {
                s.map(Path::toString)
                 .filter(p -> p.endsWith(".jar") && !p.contains("sources") && !p.contains("arm64") && !p.contains("x86")
                         && !p.contains("linux") && !p.contains("macos"))
                 .forEach(jars::add);
            }
        }
        if (jars.size() < 6) throw new IOException("LWJGL 3.4.3 jars not found in " + root + " -> " + jars);
        return String.join(File.pathSeparator, jars);
    }

    void launchTarget(Mode m) {
        if (targetProc != null && targetProc.isAlive()) { log("[host] target already running"); return; }
        try {
            var src = locateSource("SdlTarget.java");
            var pb = new ProcessBuilder(javaExe(), "--enable-native-access=ALL-UNNAMED",
                    "-cp", lwjglClasspath(), src.toString());
            pb.directory(src.getParent().toFile());   // multi-file source launch also needs Win32.java
            pb.redirectErrorStream(true);
            targetProc = pb.start();
            targetPid = targetProc.pid();
            log("[host] launched target pid=" + targetPid);
            Thread.ofPlatform().daemon().name("target-stdout").start(() -> pumpTargetOutput(m));
        } catch (IOException e) {
            log("[host] launch failed: " + e);
        }
    }

    static Path locateSource(String name) {
        for (var p : List.of(Path.of(name), Path.of("poc", "embed", name))) if (Files.exists(p)) return p.toAbsolutePath();
        throw new IllegalStateException(name + " not found; run from poc/embed");
    }

    void pumpTargetOutput(Mode m) {
        try (var r = new BufferedReader(new InputStreamReader(targetProc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                targetLog.add(line);
                log("  [target] " + line);
                if (line.startsWith("READY")) attachPid(targetPid, m);
            }
        } catch (IOException ignored) {
        }
        log("[host] target exited");
        SwingUtilities.invokeLater(() -> { target = 0; viewport.repaint(); });
    }

    void sendToTarget(String cmd) {
        try {
            var out = targetProc.getOutputStream();
            out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            log("[host] send failed: " + e);
        }
    }

    /** Freeze the game for {@code ms}: our SDL target sleeps on request; any other game gets its window thread suspended. */
    void stallTarget(long ms) {
        if (targetProc != null && targetProc.isAlive()) { sendToTarget("stall " + ms); return; }
        long h = target;
        Thread.ofPlatform().daemon().name("suspend").start(() -> {
            targetLog.add("STALL begin " + ms + "ms (window thread suspended)");
            Win32.suspendWindowThreadFor(h, ms);
            targetLog.add("STALL end");
        });
    }

    volatile Path agentJar;   // --agent: SulfurFrame javaagent to load into the game (fixes SDL keyboard focus)
    String agentArgs = "codingFps=15";

    /** Load the javaagent into a running game JVM via the Attach API (no restart; the agent retransforms). */
    void injectAgent(long pid) {
        if (agentJar == null) return;
        try {
            var vm = com.sun.tools.attach.VirtualMachine.attach(Long.toString(pid));
            try { vm.loadAgent(agentJar.toString(), agentArgs); } finally { vm.detach(); }
            log("[host] agent loaded into " + pid + " (" + agentJar.getFileName() + " " + agentArgs + ")");
        } catch (Exception e) {
            // already loaded is fine (premain or an earlier attach); anything else: log and carry on unpatched
            log("[host] agent load into " + pid + " failed: " + e);
        }
    }

    void attachPid(long pid, Mode m) {
        injectAgent(pid);
        long h = 0;
        for (int i = 0; i < 100 && h == 0; i++) {
            h = Win32.findMainWindowOfProcess(pid);
            if (h == 0) sleep(100);
        }
        if (h == 0) { log("[host] no visible window for pid " + pid); return; }
        long hwnd = h;
        targetPid = pid;
        log("[host] found window " + Win32.hex(hwnd) + " class=" + Win32.className(hwnd) + " title=\"" + Win32.windowText(hwnd) + "\"");
        onEdt(() -> embed(hwnd, m));
    }

    // ---------------------------------------------------------------- embedding

    long frameHwnd() { return Win32.findWindow("SunAwtFrame", TITLE); }
    long viewportHwnd() { return Win32.findWindowEx(frameHwnd(), 0, "SunAwtCanvas", null); }

    void embed(long hwnd, Mode m) {
        if (target != 0) detach();
        long canvas = viewportHwnd();
        if (canvas == 0) { log("[host] could not find canvas HWND"); return; }
        target = hwnd;
        mode = m;
        savedStyle = Win32.getLongPtr(hwnd, Win32.GWL_STYLE);
        savedExStyle = Win32.getLongPtr(hwnd, Win32.GWL_EXSTYLE);
        long chrome = Win32.WS_POPUP | Win32.WS_CAPTION | Win32.WS_THICKFRAME | Win32.WS_SYSMENU
                | Win32.WS_MINIMIZEBOX | Win32.WS_MAXIMIZEBOX;

        if (m == Mode.REPARENT) {
            // keep the canvas from painting over its new child
            Win32.setLongPtr(canvas, Win32.GWL_STYLE, Win32.getLongPtr(canvas, Win32.GWL_STYLE) | Win32.WS_CLIPCHILDREN);
            Win32.setLongPtr(hwnd, Win32.GWL_STYLE, (savedStyle & ~chrome) | Win32.WS_CHILD | Win32.WS_VISIBLE | Win32.WS_CLIPSIBLINGS);
            // the game's clicks must reach us as WM_PARENTNOTIFY (see installClickToFocus)
            Win32.setLongPtr(hwnd, Win32.GWL_EXSTYLE, savedExStyle & ~Win32.WS_EX_NOPARENTNOTIFY);
            long prev = Win32.setParent(hwnd, canvas);
            log("[host] REPARENT: SetParent -> prev parent " + Win32.hex(prev) + ", now " + Win32.hex(Win32.getAncestor(hwnd, Win32.GA_PARENT))
                    + " (canvas " + Win32.hex(canvas) + ")");
            throttler.bind(hwnd, targetPid);
            if (hostClickToFocus) installClickToFocus(canvas);
        } else {
            // borderless popup, owned by the host frame (owned windows stay above their owner and minimize with it)
            Win32.setLongPtr(hwnd, Win32.GWL_STYLE, (savedStyle & ~chrome) | Win32.WS_POPUP | Win32.WS_VISIBLE);
            Win32.setLongPtr(hwnd, Win32.GWL_EXSTYLE, (savedExStyle & ~Win32.WS_EX_APPWINDOW) | Win32.WS_EX_TOOLWINDOW);
            Win32.setLongPtr(hwnd, Win32.GWLP_HWNDPARENT, frameHwnd());
            log("[host] OVERLAY: owner now " + Win32.hex(Win32.getWindow(hwnd, Win32.GW_OWNER)) + " (frame " + Win32.hex(frameHwnd()) + ")");
            overlayTimer = new javax.swing.Timer(50, e -> syncBounds());   // catches moves the listeners miss
            overlayTimer.start();
        }
        syncBounds(Win32.SWP_FRAMECHANGED | Win32.SWP_SHOWWINDOW);
    }

    void syncBounds() { syncBounds(0); }

    /** Always async: a synchronous cross-process SetWindowPos blocks the EDT while the game is not pumping messages. */
    void syncBounds(int extra) {
        long h = target;
        if (h == 0 || !Win32.isWindow(h)) return;
        long canvas = viewportHwnd();
        var cr = Win32.clientRect(canvas);
        if (cr == null) return;
        int flags = Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE | Win32.SWP_ASYNCWINDOWPOS | extra;
        if (mode == Mode.REPARENT) {
            Win32.setWindowPos(h, 0, 0, 0, cr.width(), cr.height(), flags);
        } else {
            var wr = Win32.windowRect(canvas);            // screen coordinates, physical pixels
            Win32.setWindowPos(h, 0, wr.left(), wr.top(), wr.width(), wr.height(), flags);
        }
    }

    /** Deliberately synchronous resize, to demonstrate the hang hazard. Returns elapsed ms. */
    long timedResize(boolean async) {
        long h = target;
        if (h == 0) return -1;
        var cr = Win32.clientRect(viewportHwnd());
        int flags = Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE | Win32.SWP_NOMOVE | (async ? Win32.SWP_ASYNCWINDOWPOS : 0);
        long t0 = System.nanoTime();
        Win32.setWindowPos(h, 0, 0, 0, cr.width() - 1, cr.height() - 1, flags);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log("[host] " + (async ? "async" : "SYNC") + " SetWindowPos took " + ms + " ms");
        syncBounds();
        return ms;
    }

    void detach() {
        long h = target;
        if (h == 0) return;
        if (overlayTimer != null) { overlayTimer.stop(); overlayTimer = null; }
        removeClickToFocus();
        throttler.onViewportShown();   // never hand back a minimized game with tightened GC flags
        throttler.bind(0, 0);
        if (Win32.isWindow(h)) {
            if (mode == Mode.REPARENT) Win32.setParent(h, 0);
            else Win32.setLongPtr(h, Win32.GWLP_HWNDPARENT, 0);
            Win32.setLongPtr(h, Win32.GWL_STYLE, savedStyle);
            Win32.setLongPtr(h, Win32.GWL_EXSTYLE, savedExStyle);
            Win32.setWindowPos(h, 0, 100, 100, 854, 480,
                    Win32.SWP_NOZORDER | Win32.SWP_FRAMECHANGED | Win32.SWP_SHOWWINDOW | Win32.SWP_ASYNCWINDOWPOS);
        }
        log("[host] detached " + Win32.hex(h));
        target = 0;
        viewport.repaint();
    }

    /** Stand-in for the IntelliJ tool window being hidden/shown (ToolWindowManagerListener in the real plugin). */
    void setViewportHidden(boolean hidden) {
        if (target == 0 || mode != Mode.REPARENT) return;
        if (hidden) throttler.onViewportHidden();
        else { throttler.onViewportShown(); syncBounds(Win32.SWP_SHOWWINDOW); }
    }

    void shutdown() {
        throttler.shutdown();
        detach();
        if (targetProc != null && targetProc.isAlive()) {
            sendToTarget("quit");
            try { if (!targetProc.waitFor(2, TimeUnit.SECONDS)) targetProc.destroyForcibly(); } catch (InterruptedException ignored) {}
        }
    }

    // ---------------------------------------------------------------- utils

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) { r.run(); return; }
        try { SwingUtilities.invokeAndWait(r); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static <T> T onEdt(Callable<T> c) {
        var ref = new AtomicReference<T>();
        onEdt(() -> { try { ref.set(c.call()); } catch (Exception e) { throw new RuntimeException(e); } });
        return ref.get();
    }

    /** Measures EDT responsiveness: posts a probe every 10 ms and records how long it takes to run. */
    static final class EdtWatch {
        private final AtomicLong maxSinceReset = new AtomicLong();
        private final long[] window = new long[200];     // 2 s of samples
        private int idx;

        void start() {
            Thread.ofPlatform().daemon().name("edt-watch").start(() -> {
                while (true) {
                    long t0 = System.nanoTime();
                    var done = new CountDownLatch(1);
                    SwingUtilities.invokeLater(done::countDown);
                    try { done.await(); } catch (InterruptedException e) { return; }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    maxSinceReset.accumulateAndGet(ms, Math::max);
                    synchronized (this) { window[idx++ % window.length] = ms; }
                    sleep(10);
                }
            });
        }

        void reset() { maxSinceReset.set(0); }
        long maxSinceReset() { return maxSinceReset.get(); }
        synchronized long recentMaxMs() { long m = 0; for (long v : window) m = Math.max(m, v); return m; }
    }
}
