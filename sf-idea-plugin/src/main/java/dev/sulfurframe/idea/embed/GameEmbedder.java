package dev.sulfurframe.idea.embed;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Puts a running Minecraft window inside an AWT Canvas of the IDE (Win32 SetParent) and keeps it usable.
 * Everything here was worked out in poc/embed (see its README for the measurements behind each rule):
 *   - every cross-process size/position/show call is asynchronous (a sync call blocks the EDT while the game stalls)
 *   - click-to-focus without a mod: subclass the canvas, catch WM_PARENTNOTIFY, SetFocus from a non-AWT thread
 *   - keep WM_MOUSEACTIVATE from AWT (GLFW forwards it; AWT would take focus back right after the click)
 *   - clicking anywhere else in the IDE asks AWT for real native focus again
 *   - hang watchdog: detach the shared input queues while the game does not answer, re-attach when it does
 *   - nudge the size once after embedding (a game may keep a stale framebuffer if no WM_SIZE arrives)
 *   - the canvas' native window must never die with the game inside: park the game before removeNotify
 *   - SulfurFrame javaagent (FPS cap while editing, SDL3 focus fix) loaded through the Attach API
 */
public final class GameEmbedder implements GameView {
    private final Consumer<String> log;
    final Canvas canvas = new Canvas() {
        @Override public void update(Graphics g) { }
        @Override public void paint(Graphics g) {
            // with a game attached this only shows in the letterbox bars (WS_CLIPCHILDREN keeps it off the game)
            g.setColor(game == 0 ? new Color(0x2B2D30) : Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (game == 0) { g.setColor(Color.GRAY); g.drawString(fullscreen ? "game is fullscreen (F11 to come back)" : "no game attached", 12, 20); }
        }
        @Override public void removeNotify() { park(); super.removeNotify(); }
        @Override public void addNotify() { super.addNotify(); SwingUtilities.invokeLater(GameEmbedder.this::unpark); }
    };

    private volatile long game, gamePid, canvasHwnd, parked;
    private volatile long savedStyle, savedExStyle;
    private volatile boolean inputDetached;
    private volatile long detachedAt;
    final Throttler throttler;
    private final ExecutorService focusHelper = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().name("sf-focus").unstarted(r));
    private long subclassed, origProc, stub;
    private final AWTEventListener reclaim = this::onAwtMouse;
    private volatile Path agentJar;
    private volatile boolean disposed;
    /** Keep the game at this width/height ratio inside the canvas (letterbox); 0 = fill the canvas. */
    private volatile double aspect = 16.0 / 9.0;
    /** The game asked for fullscreen (F11): it is out of the IDE, covering the IDE's monitor. */
    private volatile boolean fullscreen;
    private volatile long fullscreenHwnd;

    public void setAspect(double a) { aspect = a; syncBounds(0); canvas.repaint(); }
    public double aspect() { return aspect; }
    public boolean isFullscreen() { return fullscreen; }

    public GameEmbedder(Consumer<String> log) {
        this.log = log;
        this.throttler = new Throttler(log, 5000);
        canvas.setBackground(new Color(0x2B2D30));
        canvas.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { syncBounds(0); }
            @Override public void componentMoved(ComponentEvent e) { syncBounds(0); }
        });
        // tool window hidden/shown -> throttle. While the canvas is being re-created (park/unpark) game == 0, so the
        // transient "not showing" of a move/float is ignored; unpark re-syncs with the real visibility afterwards.
        canvas.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && game != 0) syncVisibility();
        });
        Toolkit.getDefaultToolkit().addAWTEventListener(reclaim, AWTEvent.MOUSE_EVENT_MASK);
        startWatchdog();
        // The IDE going away takes a child window with it. Orderly exits (IDE close/restart, taskkill without /F,
        // log-off) run JVM shutdown hooks: hand the game back as a normal window first. Crashes and forced kills do
        // not; for those the agent's lifeboat makes the game save its world and quit.
        Runtime.getRuntime().addShutdownHook(new Thread(this::releaseForShutdown, "sulfurframe-release"));
    }

    /** Shutdown hook: plain Win32 only (no Swing, no logging through the IDE). */
    private void releaseForShutdown() {
        long h = game != 0 ? game : fullscreen ? 0 : parked;   // fullscreen windows are top-level already
        if (h == 0 || !Win32.isWindow(h)) return;
        try {
            unsubclass();
            Win32.setParent(h, 0);
            Win32.setLongPtr(h, Win32.GWL_STYLE, savedStyle | Win32.WS_VISIBLE);
            Win32.setLongPtr(h, Win32.GWL_EXSTYLE, savedExStyle);
            Win32.setWindowPos(h, 0, 100, 100, 854, 480, Win32.SWP_NOZORDER | Win32.SWP_FRAMECHANGED | Win32.SWP_SHOWWINDOW | Win32.SWP_ASYNCWINDOWPOS);
            Win32.showWindow(h, Win32.SW_RESTORE);
        } catch (Throwable ignored) { }
    }

    public Component component() { return canvas; }
    public boolean attached() { return game != 0; }
    public long gamePid() { return gamePid; }
    public void setAgentJar(Path jar) { this.agentJar = jar; }
    public long gameHwnd() { return game; }
    public long parkedHwnd() { return parked; }
    public long canvasNativeHwnd() { return canvasHwnd(); }
    public String throttleState() { return throttler.state().name(); }

    // ------------------------------------------------------------------ attach / detach

    /** Embed the visible top-level window of {@code pid}. Call on the EDT once the canvas is showing. */
    public boolean attach(long pid) {
        long h = 0;
        for (int i = 0; i < 50 && h == 0; i++) { h = Win32.findMainWindowOfProcess(pid); if (h == 0) sleep(100); }
        if (h == 0) { log.accept("no visible window for pid " + pid); return false; }
        injectAgent(pid);
        return attachWindow(h, pid);
    }

    boolean attachWindow(long hwnd, long pid) {
        if (game != 0) detach();
        long c = canvasHwnd();
        if (c == 0) { log.accept("canvas has no native window yet"); return false; }
        game = hwnd; gamePid = pid;
        savedStyle = Win32.getLongPtr(hwnd, Win32.GWL_STYLE);
        savedExStyle = Win32.getLongPtr(hwnd, Win32.GWL_EXSTYLE);
        long chrome = Win32.WS_POPUP | Win32.WS_CAPTION | Win32.WS_THICKFRAME | Win32.WS_SYSMENU | Win32.WS_MINIMIZEBOX | Win32.WS_MAXIMIZEBOX;
        Win32.setLongPtr(c, Win32.GWL_STYLE, Win32.getLongPtr(c, Win32.GWL_STYLE) | Win32.WS_CLIPCHILDREN);
        Win32.setLongPtr(hwnd, Win32.GWL_STYLE, (savedStyle & ~chrome) | Win32.WS_CHILD | Win32.WS_VISIBLE | Win32.WS_CLIPSIBLINGS);
        Win32.setLongPtr(hwnd, Win32.GWL_EXSTYLE, savedExStyle & ~Win32.WS_EX_NOPARENTNOTIFY);
        Win32.setParent(hwnd, c);
        subclass(c);
        throttler.bind(hwnd, pid);
        var cr = Win32.clientRect(c);
        if (cr != null) Win32.setWindowPos(hwnd, 0, 0, 0, Math.max(1, cr.width() - 1), Math.max(1, cr.height() - 1),
                Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE | Win32.SWP_ASYNCWINDOWPOS);
        syncBounds(Win32.SWP_FRAMECHANGED | Win32.SWP_SHOWWINDOW);
        log.accept("embedded " + Win32.className(hwnd) + " \"" + Win32.windowText(hwnd) + "\" (pid " + pid + ")");
        canvas.repaint();
        releaseStaleClip(hwnd);
        return true;
    }

    /**
     * A new game window starts top-level and takes the foreground; quick play then joins the world and the mouse grab
     * clips the cursor (ClipCursor, system-wide) to that window. GLFW lifts the clip on WM_KILLFOCUS, which never comes
     * once the window is a child of the canvas, so the mouse stayed boxed into the old window rectangle, over every
     * other app too. For the first seconds after embedding (the world may still be joining), lift any clip while the
     * game does not have the keyboard; a later grab inside the IDE is handled by the agent (GlfwCursorFix).
     */
    private void releaseStaleClip(long hwnd) {
        Thread.ofPlatform().daemon().name("sf-unclip").start(() -> {
            boolean said = false, unfocused = false;
            for (int i = 0; i < 100 && game == hwnd; i++) {   // ~20 s
                try {
                    if (!unfocused && Win32.foregroundFocus() != hwnd) {
                        // the same missing WM_KILLFOCUS also leaves Minecraft "active" with the mouse grabbed: no pause
                        // menu, and the first click into the game attacks (in creative it broke bedrock under the
                        // test player). GLFW's handler releases the cursor and reports focus false, so the game pauses.
                        Win32.postMessage(hwnd, 0x0008 /* WM_KILLFOCUS */, 0, 0);
                        unfocused = true;
                    }
                    if (Win32.foregroundFocus() != hwnd && clipped()) {
                        Win32.releaseClip();
                        if (!said) { log.accept("released the cursor clip the game set before it was embedded"); said = true; }
                    }
                    Thread.sleep(200);
                } catch (Throwable t) { return; }
            }
        });
    }

    /** Is the cursor clipped to less than the whole virtual screen? */
    private static boolean clipped() {
        var c = Win32.clipCursor();
        if (c == null) return false;
        var vs = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.awt.Rectangle all = new java.awt.Rectangle();
        for (var d : vs.getScreenDevices()) {
            var b = d.getDefaultConfiguration().getBounds();
            double s = d.getDefaultConfiguration().getDefaultTransform().getScaleX();
            all = all.union(new java.awt.Rectangle((int) (b.x * s), (int) (b.y * s), (int) Math.ceil(b.width * s), (int) Math.ceil(b.height * s)));
        }
        return c.width() < all.width || c.height() < all.height;
    }

    /** Give the window back as a normal top-level window. */
    public void detach() {
        long h = game;
        if (h == 0) return;
        throttler.onViewportShown();
        throttler.bind(0, 0);
        unsubclass();
        if (Win32.isWindow(h)) {
            Win32.setParent(h, 0);
            Win32.setLongPtr(h, Win32.GWL_STYLE, savedStyle);
            Win32.setLongPtr(h, Win32.GWL_EXSTYLE, savedExStyle);
            Win32.setWindowPos(h, 0, 100, 100, 854, 480,
                    Win32.SWP_NOZORDER | Win32.SWP_FRAMECHANGED | Win32.SWP_SHOWWINDOW | Win32.SWP_ASYNCWINDOWPOS);
        }
        game = 0; canvasHwnd = 0;
        log.accept("detached");
        canvas.repaint();
    }

    /**
     * IntelliJ removes a tool window's component when it is hidden, moved or floated, which destroys the canvas'
     * native window, and a child window dies with its parent. Before that happens, turn the game back into an
     * ordinary top-level window (parent = desktop: it cannot go away; parking it under the IDE frame is not safe
     * either, since that frame may be a floating tool window that is about to close), minimize it (the game sees
     * "iconified": vanilla caps it at 10 fps) and hide it. For the throttler this counts as hidden, so memory is
     * reclaimed if it stays parked.
     */
    private void park() {
        long h = game;
        if (h == 0 || disposed) return;
        unsubclass();
        if (Win32.isWindow(h)) {
            Win32.showWindow(h, Win32.SW_HIDE);
            Win32.setParent(h, 0);
            Win32.setLongPtr(h, Win32.GWL_STYLE, savedStyle & ~Win32.WS_VISIBLE);
            Win32.setLongPtr(h, Win32.GWL_EXSTYLE, (savedExStyle & ~Win32.WS_EX_APPWINDOW) | Win32.WS_EX_TOOLWINDOW);  // no taskbar button
            throttler.onViewportHidden();                    // minimizes (async) and schedules the memory reclaim
            Win32.showWindow(h, Win32.SW_HIDE);              // queued after the minimize: stays iconic, not visible
        }
        parked = h; game = 0; canvasHwnd = 0;
        log.accept("tool window hidden/moved: game parked (minimized)");
    }

    private void unpark() {
        long h = parked;
        if (h == 0 || disposed || !Win32.isWindow(h)) return;
        parked = 0;
        long s = savedStyle, x = savedExStyle;           // the parked window still has our child style:
        boolean ok = attachWindow(h, gamePid);          // keep the original styles for a later detach
        savedStyle = s; savedExStyle = x;
        if (ok) { Win32.showWindow(h, Win32.SW_SHOWNOACTIVATE); syncVisibility(); }
    }

    /** Throttler state follows whether the canvas is actually on screen. */
    private void syncVisibility() {
        if (canvas.isShowing()) { throttler.onViewportShown(); syncBounds(Win32.SWP_SHOWWINDOW); }
        else throttler.onViewportHidden();
    }

    public void dispose() {
        disposed = true;
        Toolkit.getDefaultToolkit().removeAWTEventListener(reclaim);
        try { detach(); } catch (Throwable ignored) { }
        throttler.shutdown();
        focusHelper.shutdownNow();
    }

    // ------------------------------------------------------------------ geometry

    long canvasHwnd() {
        if (canvasHwnd != 0 && Win32.isWindow(canvasHwnd)) return canvasHwnd;
        if (!canvas.isShowing()) return 0;
        // IntelliJ starts with --add-opens java.desktop/sun.awt(.windows)=ALL-UNNAMED, so ask the AWT peer directly
        try {
            Object accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null);
            var getPeer = Class.forName("sun.awt.AWTAccessor$ComponentAccessor").getMethod("getPeer", Component.class);
            Object peer = getPeer.invoke(accessor, canvas);
            if (peer != null) {
                long h = (Long) Class.forName("sun.awt.windows.WComponentPeer").getMethod("getHWnd").invoke(peer);
                if (h != 0) return canvasHwnd = h;
            }
        } catch (Throwable t) {
            log.accept("AWT peer lookup failed (" + t + "), falling back to the window under the canvas");
        }
        // fallback: the canvas' own HWND is whatever window is at its centre (before the game covers it); physical pixels
        var p = canvas.getLocationOnScreen();
        double s = canvas.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        long h = Win32.windowFromPoint((int) ((p.x + canvas.getWidth() / 2.0) * s), (int) ((p.y + canvas.getHeight() / 2.0) * s));
        if (h != 0 && Win32.processIdOf(h) == ProcessHandle.current().pid() && Win32.className(h).equals("SunAwtCanvas")) canvasHwnd = h;
        else if (h != 0 && game != 0 && h == game) canvasHwnd = Win32.getAncestor(h, Win32.GA_PARENT);
        return canvasHwnd;
    }

    void syncBounds(int extra) {
        long h = game;
        if (h == 0 || !Win32.isWindow(h)) return;
        var cr = Win32.clientRect(canvasHwnd());
        if (cr == null) return;
        int w = cr.width(), ht = cr.height(), x = 0, y = 0;
        if (aspect > 0 && w > 0 && ht > 0) {          // letterbox: largest aspect-correct rectangle, centred
            if ((double) w / ht > aspect) { int nw = (int) Math.round(ht * aspect); x = (w - nw) / 2; w = nw; }
            else { int nh = (int) Math.round(w / aspect); y = (ht - nh) / 2; ht = nh; }
        }
        Win32.setWindowPos(h, 0, x, y, w, ht, Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE | Win32.SWP_ASYNCWINDOWPOS | extra);
    }

    // ------------------------------------------------------------------ fullscreen (F11)

    /**
     * Minecraft's F11 makes GLFW/SDL add WS_POPUP to the window and try to cover the monitor, which a child window
     * cannot do; F11 again removes WS_POPUP and adds a caption, leaving a title bar inside the panel. So: when the
     * embedded game turns WS_POPUP, take it out of the IDE and cover the IDE's monitor; when it drops WS_POPUP, put it
     * back as a clean child. Polled from the watchdog thread.
     */
    private long popupSince;   // when the embedded game turned WS_POPUP (0: it is not)
    private volatile java.util.function.Supplier<Boolean> fullscreenHint = () -> null;
    @Override public void setFullscreenHint(java.util.function.Supplier<Boolean> hint) { fullscreenHint = hint == null ? () -> null : hint; }
    private int borderlessFixes;

    /**
     * WS_POPUP alone is not F11: GLFW also gives an undecorated window that style when something recomputes its
     * styles (seen with a mod-heavy build: the game "went fullscreen" right after embedding, every launch). The size
     * cannot tell either (a child is never resized to the monitor). So the game is asked: the agent reports GLFW's
     * own fullscreen state with its stats. Fullscreen -> out of the IDE; reported windowed (a fresh report, after a
     * grace period) -> back to a plain child, stays embedded; no report (SDL, no agent) -> treated as F11 as before.
     */
    private void watchFullscreen() {
        long h = fullscreen ? fullscreenHwnd : game;
        if (h == 0 || !Win32.isWindow(h)) return;
        long style = Win32.getLongPtr(h, Win32.GWL_STYLE);
        boolean popup = (style & Win32.WS_POPUP) != 0;
        if (fullscreen) { if (!popup) SwingUtilities.invokeLater(() -> exitFullscreen(h)); return; }
        if (!popup) { popupSince = 0; return; }
        long now = System.currentTimeMillis();
        if (popupSince == 0) popupSince = now;
        Boolean says = fullscreenHint.get();
        if (Boolean.TRUE.equals(says) || says == null && now - popupSince >= 400) {
            popupSince = 0;
            SwingUtilities.invokeLater(() -> enterFullscreen(h));
            return;
        }
        if (now - popupSince < 2500) return;   // the stats report comes once a second: wait for one written after F11
        popupSince = 0;
        Win32.setLongPtr(h, Win32.GWL_STYLE, (style & ~(Win32.WS_POPUP | Win32.WS_CAPTION | Win32.WS_THICKFRAME)) | Win32.WS_CHILD | Win32.WS_VISIBLE);
        SwingUtilities.invokeLater(() -> syncBounds(Win32.SWP_FRAMECHANGED | Win32.SWP_SHOWWINDOW));
        if (borderlessFixes++ == 0) log.accept("game window turned borderless but is not fullscreen (the game says so): kept in the IDE");
    }

    private void enterFullscreen(long h) {
        if (fullscreen || game != h) return;
        Rectangle mon = monitorOfCanvas();
        unsubclass();
        throttler.bind(0, 0);
        Win32.setParent(h, 0);
        long st = Win32.getLongPtr(h, Win32.GWL_STYLE);
        Win32.setLongPtr(h, Win32.GWL_STYLE, (st & ~(Win32.WS_CHILD | Win32.WS_CAPTION | Win32.WS_THICKFRAME)) | Win32.WS_POPUP | Win32.WS_VISIBLE);
        Win32.setWindowPos(h, 0, mon.x, mon.y, mon.width, mon.height, Win32.SWP_FRAMECHANGED | Win32.SWP_SHOWWINDOW | Win32.SWP_ASYNCWINDOWPOS);
        fullscreen = true; fullscreenHwnd = h; game = 0; canvasHwnd = 0;
        focusHelper.execute(() -> { Win32.ensureGuiThread(); Win32.setForeground(h); });
        log.accept("game went fullscreen (F11): out of the IDE, covering " + mon.width + "x" + mon.height);
        canvas.repaint();
    }

    private void exitFullscreen(long h) {
        if (!fullscreen) return;
        fullscreen = false; fullscreenHwnd = 0;
        if (!Win32.isWindow(h)) { log.accept("game window closed while fullscreen"); canvas.repaint(); return; }
        long s = savedStyle, x = savedExStyle;      // keep the pre-embed styles for a later detach
        boolean ok = attachWindow(h, gamePid);       // strips the caption GLFW just added, WS_CHILD, reparents
        savedStyle = s; savedExStyle = x;
        log.accept(ok ? "game left fullscreen: embedded again" : "could not re-embed after fullscreen");
    }

    /** Physical-pixel bounds of the monitor showing the canvas. */
    private Rectangle monitorOfCanvas() {
        var gc = canvas.getGraphicsConfiguration();
        Rectangle b = gc.getBounds();
        double sx = gc.getDefaultTransform().getScaleX(), sy = gc.getDefaultTransform().getScaleY();
        return new Rectangle((int) Math.round(b.x * sx), (int) Math.round(b.y * sy), (int) Math.round(b.width * sx), (int) Math.round(b.height * sy));
    }

    public void setHidden(boolean hidden) {
        if (game == 0) return;
        if (hidden) throttler.onViewportHidden(); else { throttler.onViewportShown(); syncBounds(Win32.SWP_SHOWWINDOW); }
    }

    // ------------------------------------------------------------------ focus

    static final int WM_SF_FOCUS_GAME = Win32.WM_APP + 0x51, WM_MOUSEACTIVATE = 0x0021, MA_ACTIVATE = 1;

    private void subclass(long c) {
        if (subclassed == c) return;
        unsubclass();
        if (stub == 0) stub = Win32.wndProcStub(this::canvasProc);
        origProc = Win32.setLongPtr(c, Win32.GWLP_WNDPROC, stub);
        subclassed = c;
        long root = Win32.getAncestor(c, Win32.GA_ROOT);
        if (root != 0 && root != c) {
            if (frameStub == 0) frameStub = Win32.wndProcStub(this::frameProc);
            origFrameProc = Win32.setLongPtr(root, Win32.GWLP_WNDPROC, frameStub);
            frameSubclassed = root;
        }
    }

    private void unsubclass() {
        if (subclassed != 0 && Win32.isWindow(subclassed)) Win32.setLongPtr(subclassed, Win32.GWLP_WNDPROC, origProc);
        subclassed = 0;
        if (frameSubclassed != 0 && Win32.isWindow(frameSubclassed)) Win32.setLongPtr(frameSubclassed, Win32.GWLP_WNDPROC, origFrameProc);
        frameSubclassed = 0;
    }

    private long frameSubclassed, origFrameProc, frameStub;
    private long keyMenuLoggedAt;

    /**
     * IDE frame (AWT toolkit thread); must never throw. Alt pressed and released alone in the game makes Windows send
     * SC_KEYMENU to the top-level window: in a normal window that is the game, and GLFW ignores it; embedded it is the
     * IDE frame, which then went into an invisible menu mode that took the next keys and clicks (an overlay held
     * open by Alt did not close until a click, and that click's release never reached the game). Dropped while the
     * game has the keyboard.
     */
    private long frameProc(long hwnd, int msg, long wParam, long lParam) {
        try {
            final int WM_SYSCOMMAND = 0x0112, SC_KEYMENU = 0xF100;
            if (msg == WM_SYSCOMMAND && (wParam & 0xFFF0) == SC_KEYMENU) {
                long g = game;
                if (g != 0 && Win32.foregroundFocus() == g) {
                    long now = System.currentTimeMillis();
                    if (now - keyMenuLoggedAt > 60_000) { keyMenuLoggedAt = now; log.accept("the game has the keys: Alt menu activation of the IDE window dropped"); }
                    return 0;
                }
            }
        } catch (Throwable ignored) { }
        return Win32.callWindowProc(origFrameProc, hwnd, msg, wParam, lParam);
    }

    /** AWT toolkit thread; must never throw. */
    private long canvasProc(long hwnd, int msg, long wParam, long lParam) {
        try {
            if (msg == Win32.WM_PARENTNOTIFY) {
                int ev = (int) (wParam & 0xFFFF);
                if (ev == Win32.WM_LBUTTONDOWN || ev == Win32.WM_RBUTTONDOWN || ev == Win32.WM_MBUTTONDOWN || ev == Win32.WM_XBUTTONDOWN) {
                    long g = game;
                    // The game's thread waits for this message before it handles the click: give it the focus right
                    // here, so the game sees the click with its window focused. Minecraft then grabs the mouse with
                    // that first click (and does not attack with it), as in a normal window. The canvas shares its
                    // input state with the game window (parent and child), so no AttachThreadInput is needed here.
                    if (g != 0 && Win32.foregroundFocus() != g) {
                        if (!inputDetached) Win32.setFocus(g);
                        if (Win32.getFocus() == g) throttler.onGameFocused();
                        else Win32.postMessage(hwnd, WM_SF_FOCUS_GAME, 0, 0);
                    }
                }
            } else if (msg == WM_MOUSEACTIVATE && game != 0) {
                return MA_ACTIVATE;
            } else if (msg == WM_SF_FOCUS_GAME) {
                long g = game;
                if (g != 0 && !inputDetached && Win32.isResponsive(g, 100)) focusHelper.execute(() -> focusGame(g));
                return 0;
            }
        } catch (Throwable ignored) { }
        return Win32.callWindowProc(origProc, hwnd, msg, wParam, lParam);
    }

    public void focusGame() { long g = game; if (g != 0) focusHelper.execute(() -> focusGame(g)); }

    private void focusGame(long g) {
        Win32.ensureGuiThread();
        int me = Win32.currentThreadId(), gameTid = Win32.threadIdOf(g);
        boolean attached = Win32.attachThreadInput(me, gameTid, true);
        try {
            Win32.setFocus(g);
            if (Win32.getFocus() == g) throttler.onGameFocused();
        } finally {
            if (attached) Win32.attachThreadInput(me, gameTid, false);
        }
    }

    private void onAwtMouse(AWTEvent e) {
        if (game == 0 || e.getID() != MouseEvent.MOUSE_PRESSED || !(e.getSource() instanceof Component c) || c == canvas) return;
        if (SwingUtilities.getWindowAncestor(c) != SwingUtilities.getWindowAncestor(canvas)) return;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
        c.requestFocus(FocusEvent.Cause.MOUSE_EVENT);
        throttler.onEditorFocused();
    }

    private void startWatchdog() {
        Thread.ofPlatform().daemon().name("sf-hang-watchdog").start(() -> {
            int misses = 0;
            while (!disposed) {
                sleep(100);
                try { watchFullscreen(); } catch (Throwable ignored) { }
                long h = game, c = canvasHwnd;
                if (h == 0 || c == 0 || !Win32.isWindow(h)) { misses = 0; continue; }
                misses = Win32.isResponsive(h, 200) ? 0 : misses + 1;
                int ideTid = Win32.threadIdOf(Win32.getAncestor(c, Win32.GA_ROOT)), gameTid = Win32.threadIdOf(h);
                // a short stall (a swap, a resource reload, a world loading) is normal: only one that lasted is logged
                if (misses >= 2 && !inputDetached) { Win32.attachThreadInput(gameTid, ideTid, false); inputDetached = true; detachedAt = System.currentTimeMillis(); }
                else if (misses == 0 && inputDetached) {
                    Win32.attachThreadInput(gameTid, ideTid, true); inputDetached = false;
                    long ms = System.currentTimeMillis() - detachedAt;
                    if (ms >= 3000) log.accept(String.format("game was not responding for %.1f s (its input was kept apart from the IDE's meanwhile)", ms / 1000.0));
                    reattachedAt = System.currentTimeMillis(); lastStallMs = ms;
                }
                try { watchFocus(h); } catch (Throwable ignored) { }
            }
        });
    }

    private volatile long reattachedAt, lastStallMs;
    private boolean gameHadKeyboard;

    /**
     * Logs who takes the keyboard from the embedded game (Windows): the window and process, the IDE's own focus owner,
     * how long ago the user last pressed a key or clicked (a long time = the focus moved by itself), and when the
     * game's input was last joined to the IDE's again after a stall.
     */
    private void watchFocus(long g) {
        long f = Win32.foregroundFocus();
        boolean has = f == g;
        if (gameHadKeyboard && !has && f != 0) {
            long now = System.currentTimeMillis(), idle = Win32.idleMillis();
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            String awt = owner == null ? "none" : owner.getClass().getName() + (owner.getName() == null ? "" : " '" + owner.getName() + "'");
            log.accept("focus: the game lost the keyboard to " + Win32.describe(f) + ", foreground " + Win32.describe(Win32.foregroundWindow())
                    + "; IDE focus owner " + awt + "; last user input " + (idle < 0 ? "?" : idle + " ms") + " ago"
                    + (reattachedAt == 0 ? "" : "; input rejoined " + (now - reattachedAt) + " ms ago after a " + lastStallMs + " ms stall"));
        }
        gameHadKeyboard = has;
    }

    // ------------------------------------------------------------------ agent

    private void injectAgent(long pid) {
        Path jar = agentJar;
        if (jar == null) return;
        try {
            var vm = com.sun.tools.attach.VirtualMachine.attach(Long.toString(pid));
            try { vm.loadAgent(jar.toString(), "codingFps=" + dev.sulfurframe.idea.Settings.codingFps()); } finally { vm.detach(); }
            log.accept("agent loaded");
        } catch (com.sun.tools.attach.AgentLoadException e) {
            log.accept(String.valueOf(e.getMessage()).endsWith(": 0") ? "agent loaded (Java 8 game)" : "agent load failed: " + e.getMessage());
        } catch (Exception e) {
            log.accept("agent load failed: " + e);
        }
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
