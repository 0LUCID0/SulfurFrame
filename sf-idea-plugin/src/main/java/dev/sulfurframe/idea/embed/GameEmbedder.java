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
public final class GameEmbedder {
    private final Consumer<String> log;
    final Canvas canvas = new Canvas() {
        @Override public void update(Graphics g) { }
        @Override public void paint(Graphics g) {
            if (game == 0) { g.setColor(new Color(0x2B2D30)); g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.GRAY); g.drawString("no game attached", 12, 20); }
        }
        @Override public void removeNotify() { park(); super.removeNotify(); }
        @Override public void addNotify() { super.addNotify(); SwingUtilities.invokeLater(GameEmbedder.this::unpark); }
    };

    private volatile long game, gamePid, canvasHwnd, parked;
    private volatile long savedStyle, savedExStyle;
    private volatile boolean inputDetached;
    final Throttler throttler;
    private final ExecutorService focusHelper = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().name("sf-focus").unstarted(r));
    private long subclassed, origProc, stub;
    private final AWTEventListener reclaim = this::onAwtMouse;
    private volatile Path agentJar;
    private volatile boolean disposed;

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
        return true;
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

    /** Canvas native window is about to be destroyed (tool window moved/floated): take the game out first. */
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
        Win32.setWindowPos(h, 0, 0, 0, cr.width(), cr.height(), Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE | Win32.SWP_ASYNCWINDOWPOS | extra);
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
    }

    private void unsubclass() {
        if (subclassed != 0 && Win32.isWindow(subclassed)) Win32.setLongPtr(subclassed, Win32.GWLP_WNDPROC, origProc);
        subclassed = 0;
    }

    /** AWT toolkit thread; must never throw. */
    private long canvasProc(long hwnd, int msg, long wParam, long lParam) {
        try {
            if (msg == Win32.WM_PARENTNOTIFY) {
                int ev = (int) (wParam & 0xFFFF);
                if (ev == Win32.WM_LBUTTONDOWN || ev == Win32.WM_RBUTTONDOWN || ev == Win32.WM_MBUTTONDOWN || ev == Win32.WM_XBUTTONDOWN)
                    Win32.postMessage(hwnd, WM_SF_FOCUS_GAME, 0, 0);
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
                long h = game, c = canvasHwnd;
                if (h == 0 || c == 0 || !Win32.isWindow(h)) { misses = 0; continue; }
                misses = Win32.isResponsive(h, 200) ? 0 : misses + 1;
                int ideTid = Win32.threadIdOf(Win32.getAncestor(c, Win32.GA_ROOT)), gameTid = Win32.threadIdOf(h);
                if (misses >= 2 && !inputDetached) { Win32.attachThreadInput(gameTid, ideTid, false); inputDetached = true; log.accept("game not responding: input queues detached"); }
                else if (misses == 0 && inputDetached) { Win32.attachThreadInput(gameTid, ideTid, true); inputDetached = false; log.accept("game responsive again"); }
            }
        });
    }

    // ------------------------------------------------------------------ agent

    private void injectAgent(long pid) {
        Path jar = agentJar;
        if (jar == null) return;
        try {
            var vm = com.sun.tools.attach.VirtualMachine.attach(Long.toString(pid));
            try { vm.loadAgent(jar.toString(), "codingFps=15"); } finally { vm.detach(); }
            log.accept("agent loaded");
        } catch (com.sun.tools.attach.AgentLoadException e) {
            log.accept(String.valueOf(e.getMessage()).endsWith(": 0") ? "agent loaded (Java 8 game)" : "agent load failed: " + e.getMessage());
        } catch (Exception e) {
            log.accept("agent load failed: " + e);
        }
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
