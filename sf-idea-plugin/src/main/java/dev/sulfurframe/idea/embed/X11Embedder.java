package dev.sulfurframe.idea.embed;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Linux/X11 version of {@link GameEmbedder}: the game's X window goes under the canvas's X window.
 * Rules from poc/embed (X11Host and its README):
 *   - the window manager owns a mapped toplevel: withdraw it, mark it override-redirect (a WM leaves those alone),
 *     then reparent it into the canvas and map it there; retry if the WM still takes it back
 *   - save-set on AWT's own connection (rescues the window under non-reparenting WMs if the IDE dies; with
 *     reparenting WMs the agent's X11 lifeboat saves the world instead)
 *   - mouse needs nothing (the pointer's deepest window is the game); keyboard: click-to-focus the way window
 *     managers do it (passive button grab, XSetInputFocus, replay the click); a click elsewhere in the IDE gives the
 *     keyboard back to AWT
 *   - park before the canvas's X window is destroyed (tool window hidden/floated/moved): back to the root, unmapped
 *   - detach: clear override-redirect, back to the root, map: the WM frames it again as a normal window
 * The IDE must run on XToolkit (IntelliJ on a Wayland session defaults to its Wayland toolkit: start it with
 * -Dawt.toolkit.name=XToolkit). F11 fullscreen is not handled here yet.
 */
public final class X11Embedder implements GameView {
    private final Consumer<String> log;
    final Canvas canvas = new Canvas() {
        @Override public void update(Graphics g) { }
        @Override public void paint(Graphics g) {
            g.setColor(game == 0 ? new Color(0x2B2D30) : Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (game == 0) { g.setColor(Color.GRAY); g.drawString(X11.isXToolkit() ? "no game attached" : "embedding needs the IDE on X11 (-Dawt.toolkit.name=XToolkit)", 12, 20); }
        }
        @Override public void removeNotify() { park(); super.removeNotify(); }
        @Override public void addNotify() { super.addNotify(); SwingUtilities.invokeLater(X11Embedder.this::unpark); }
    };

    private volatile long game, gamePid, canvasWin, parked;
    private volatile Path agentJar;
    private volatile boolean disposed;
    private volatile double aspect = 16.0 / 9.0;
    final Throttler throttler;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().name("sf-x11").unstarted(r));
    private final AWTEventListener reclaim = this::onAwtMouse;
    private final X11.ClickToFocus clicks;

    public X11Embedder(Consumer<String> log) {
        this.log = log;
        this.throttler = new Throttler(log, 5000, new Throttler.WindowOps() {
            // no "iconified" for a child window: unmap it (nothing to draw into; the agent caps the frame rate)
            public void hide(long w) { X11.unmap(w); X11.sync(); }
            public void show(long w) { X11.map(w); X11.sync(); }
            public boolean trim(long pid) { return false; }   // Linux returns freed heap pages itself after the GC
        });
        canvas.setBackground(new Color(0x2B2D30));
        canvas.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { syncBounds(); }
        });
        canvas.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && game != 0) syncVisibility();
        });
        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { focusGame(); }   // a click on the letterbox bars
        });
        Toolkit.getDefaultToolkit().addAWTEventListener(reclaim, AWTEvent.MOUSE_EVENT_MASK);
        clicks = new X11.ClickToFocus(throttler::onGameFocused);
        Runtime.getRuntime().addShutdownHook(new Thread(this::releaseForShutdown, "sulfurframe-release"));
    }

    @Override public Component component() { return canvas; }
    @Override public boolean attached() { return game != 0; }
    @Override public long gamePid() { return gamePid; }
    @Override public void setAgentJar(Path jar) { agentJar = jar; }
    @Override public void setAspect(double a) { aspect = a; syncBounds(); canvas.repaint(); }
    @Override public double aspect() { return aspect; }
    @Override public long gameHwnd() { return game; }
    @Override public long canvasNativeHwnd() { return canvasWin(); }
    @Override public long parkedHwnd() { return parked; }
    @Override public String throttleState() { return throttler.state().name(); }
    @Override public boolean isFullscreen() { return false; }

    // ------------------------------------------------------------------ attach / detach

    @Override public boolean attach(long pid) {
        if (!X11.isXToolkit()) { log.accept("the IDE runs on " + Toolkit.getDefaultToolkit().getClass().getSimpleName() + ": X11 embedding needs -Dawt.toolkit.name=XToolkit"); return false; }
        long w = 0;
        for (int i = 0; i < 50 && w == 0; i++) { w = X11.findWindowOfProcess(pid); if (w == 0) sleep(100); }
        if (w == 0) { log.accept("no game window for pid " + pid); return false; }
        injectAgent(pid);
        return attachWindow(w, pid);
    }

    private boolean attachWindow(long w, long pid) {
        if (game != 0) detach();
        long c = canvasWin();
        if (c == 0) { log.accept("canvas has no X window yet"); return false; }
        long frameParent = X11.parentOf(w);
        X11.withdraw(w);
        X11.setOverrideRedirect(w, true);
        X11.sync();
        long t0 = System.currentTimeMillis();   // the WM unframes asynchronously
        long root = X11.root();
        while (frameParent != root && X11.parentOf(w) == frameParent && System.currentTimeMillis() - t0 < 2000) sleep(20);
        X11.addToAwtSaveSet(w);
        boolean stays = false;
        for (int attempt = 0; attempt < 5 && !stays; attempt++) {
            X11.reparent(w, c, 0, 0);
            X11.map(w);
            game = w; gamePid = pid;
            syncBounds();
            X11.sync();
            stays = true;
            for (int i = 0; i < 5; i++) { sleep(100); stays &= X11.parentOf(w) == c; }
        }
        if (!stays) { log.accept("the window manager keeps taking the game window back"); game = 0; return false; }
        throttler.bind(w, pid);
        clicks.watch(w);
        log.accept("embedded \"" + X11.name(w) + "\" (pid " + pid + ", X11)");
        canvas.repaint();
        return true;
    }

    @Override public void detach() {
        long w = game;
        if (w == 0) return;
        throttler.onViewportShown();
        throttler.bind(0, 0);
        clicks.watch(0);
        release(w);
        game = 0; canvasWin = 0;
        log.accept("detached");
        canvas.repaint();
    }

    /** Back to the window manager as a normal framed window. */
    private static void release(long w) {
        if (!X11.exists(w)) return;
        X11.withdraw(w);
        X11.setOverrideRedirect(w, false);
        X11.reparent(w, X11.root(), 100, 100);
        X11.moveResize(w, 100, 100, 854, 480);
        X11.map(w);
        X11.sync();
    }

    private void releaseForShutdown() {
        long w = game != 0 ? game : parked;
        if (w != 0) try { release(w); } catch (Throwable ignored) { }
    }

    /** The canvas's X window is about to be destroyed: move the game to the root, unmapped, until it can come back. */
    private void park() {
        long w = game;
        if (w == 0 || disposed) return;
        clicks.watch(0);
        if (X11.exists(w)) {
            X11.unmap(w);
            X11.reparent(w, X11.root(), 0, 0);
            X11.sync();
            throttler.onViewportHidden();
        }
        parked = w; game = 0; canvasWin = 0;
        log.accept("tool window hidden/moved: game parked");
    }

    private void unpark() {
        long w = parked;
        if (w == 0 || disposed || !X11.exists(w)) return;
        parked = 0;
        if (attachWindow(w, gamePid)) syncVisibility();
    }

    private void syncVisibility() {
        if (canvas.isShowing()) { throttler.onViewportShown(); syncBounds(); }
        else throttler.onViewportHidden();
    }

    @Override public void setHidden(boolean hidden) {
        if (game == 0) return;
        if (hidden) throttler.onViewportHidden(); else { throttler.onViewportShown(); syncBounds(); }
    }

    @Override public void dispose() {
        disposed = true;
        Toolkit.getDefaultToolkit().removeAWTEventListener(reclaim);
        try { detach(); } catch (Throwable ignored) { }
        throttler.shutdown();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ geometry

    long canvasWin() {
        if (canvasWin != 0 && X11.exists(canvasWin)) return canvasWin;
        if (!canvas.isShowing()) return 0;
        return canvasWin = X11.windowOf(canvas);
    }

    void syncBounds() {
        long w = game;
        if (w == 0) return;
        int cw = canvas.getWidth(), ch = canvas.getHeight();
        double s = canvas.getGraphicsConfiguration() == null ? 1 : canvas.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        int width = (int) Math.round(cw * s), height = (int) Math.round(ch * s), x = 0, y = 0;
        if (aspect > 0 && width > 0 && height > 0) {
            if ((double) width / height > aspect) { int nw = (int) Math.round(height * aspect); x = (width - nw) / 2; width = nw; }
            else { int nh = (int) Math.round(width / aspect); y = (height - nh) / 2; height = nh; }
        }
        final int fx = x, fy = y, fw = width, fh = height;
        worker.execute(() -> { X11.moveResize(w, fx, fy, fw, fh); X11.sync(); });
    }

    // ------------------------------------------------------------------ focus

    @Override public void focusGame() {
        long w = game;
        if (w == 0) return;
        worker.execute(() -> { X11.focus(w); X11.sync(); throttler.onGameFocused(); });
    }

    /** A click elsewhere in the IDE window: give the keyboard back to AWT. */
    private void onAwtMouse(AWTEvent e) {
        if (game == 0 || e.getID() != MouseEvent.MOUSE_PRESSED || !(e.getSource() instanceof Component c) || c == canvas) return;
        if (SwingUtilities.getWindowAncestor(c) != SwingUtilities.getWindowAncestor(canvas)) return;
        long top = X11.windowOf(SwingUtilities.getWindowAncestor(canvas));
        worker.execute(() -> { if (top != 0) { X11.focus(top); X11.sync(); } });
        c.requestFocus(FocusEvent.Cause.MOUSE_EVENT);
        throttler.onEditorFocused();
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
