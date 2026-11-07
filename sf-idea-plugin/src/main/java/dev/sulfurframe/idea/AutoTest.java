package dev.sulfurframe.idea;

import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowType;
import dev.sulfurframe.idea.embed.NativeWindows;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-test inside a real IDE, enabled only by -Dsulfurframe.autotest.pid=<pid of a running Minecraft>.
 * Attaches that game to the tool window, then floats and re-docks the tool window (its canvas' native window is
 * destroyed and re-created: the game must survive and come back), hides and shows it (throttler), and writes
 * %TEMP%/sulfurframe-autotest.txt plus screenshots of the tool window.
 */
final class AutoTest {
    private static final List<String> lines = new ArrayList<>();
    private static int failures;

    static boolean enabled() {
        return System.getProperty("sulfurframe.autotest.pid") != null || Boolean.getBoolean("sulfurframe.autotest.runclient");
    }

    private static com.intellij.openapi.project.Project project;

    static void maybeRun(SulfurPanel panel, ToolWindow tw, com.intellij.openapi.project.Project prj) {
        project = prj;
        if (!enabled()) return;
        String pid = System.getProperty("sulfurframe.autotest.pid");
        Thread.ofPlatform().daemon().name("sf-autotest").start(() -> {
            try {
                if (pid == null) runClientFlow(panel, tw); else run(panel, tw, Long.parseLong(pid));
            } catch (Throwable t) {
                check("exception", false, t.toString());
            } finally {
                write();
            }
        });
    }

    private static void run(SulfurPanel p, ToolWindow tw, long pid) throws Exception {
        onEdt(tw::show);
        sleep(3000);
        check("profile detected", p.profile != null && p.profile.isComplete(), String.valueOf(p.profile));
        onEdt(() -> p.embedder.attach(pid));
        sleep(4000);
        var e = p.embedder;
        long game = e.gameHwnd(), canvas = e.canvasNativeHwnd();
        check("embedded in the tool window", game != 0 && NativeWindows.parentOf(game) == canvas
                && NativeWindows.isOwnCanvas(canvas), "game parent " + hex(NativeWindows.parentOf(game)) + ", canvas " + hex(canvas));
        check("game sized to the canvas (16:9 fit)", fits(game, canvas),
                "game " + NativeWindows.clientSize(game) + ", canvas " + NativeWindows.clientSize(canvas));
        snapshot(p, "sulfurframe-autotest-1-docked");

        // float + re-dock: the canvas' HWND is destroyed and re-created; the game must be parked and come back
        onEdt(() -> tw.setType(ToolWindowType.FLOATING, null));
        sleep(4000);
        long g2 = e.gameHwnd(), c2 = e.canvasNativeHwnd();
        check("survives floating the tool window", NativeWindows.isWindow(game) && g2 == game && NativeWindows.parentOf(game) == c2,
                "game alive " + NativeWindows.isWindow(game) + ", parent " + hex(NativeWindows.parentOf(game)) + ", canvas " + hex(c2) + " (was " + hex(canvas) + ")");
        snapshot(p, "sulfurframe-autotest-2-floating");
        onEdt(() -> tw.setType(ToolWindowType.DOCKED, null));
        sleep(4000);
        long c3 = e.canvasNativeHwnd();
        check("survives re-docking", NativeWindows.isWindow(game) && e.gameHwnd() == game && NativeWindows.parentOf(game) == c3,
                "parent " + hex(NativeWindows.parentOf(game)) + ", canvas " + hex(c3));

        // hide / show: throttler minimizes the game while the tool window is hidden
        onEdt(tw::hide);
        sleep(2000);
        check("hidden -> game minimized", NativeWindows.isIconic(game) && e.throttleState().equals("HIDDEN"),
                "iconic " + NativeWindows.isIconic(game) + ", state " + e.throttleState());
        onEdt(tw::show);
        sleep(3000);
        check("shown -> game restored", !NativeWindows.isIconic(game) && !e.throttleState().equals("HIDDEN"),
                "iconic " + NativeWindows.isIconic(game) + ", state " + e.throttleState());
        snapshot(p, "sulfurframe-autotest-3-shown");

        layoutAndFullscreen(p, game);

        if (!Boolean.getBoolean("sulfurframe.autotest.keep")) { onEdt(e::detach); sleep(1000); }
    }

    /** 16:9 letterbox, F11 out to fullscreen and back, editor tab and back. */
    private static void layoutAndFullscreen(SulfurPanel p, long game) throws Exception {
        var e = p.embedder;
        // 16:9 letterbox inside the (tall) tool window
        int[] g = NativeWindows.screenRect(game), c = NativeWindows.screenRect(e.canvasNativeHwnd());
        double ratio = (double) g[2] / g[3];
        check("16:9 letterbox", Math.abs(ratio - 16.0 / 9.0) < 0.02 && g[2] <= c[2] && g[3] <= c[3],
                "game " + g[2] + "x" + g[3] + " (" + String.format("%.3f", ratio) + ") in canvas " + c[2] + "x" + c[3]);

        if (dev.sulfurframe.idea.embed.GameView.linux()) { lines.add("F11 fullscreen: not handled on Linux yet (skipped)"); editorTab(p, game); return; }
        // F11: out of the IDE, covering the monitor
        var robot = new Robot();
        onEdt(() -> javax.swing.SwingUtilities.getWindowAncestor(p).toFront());
        sleep(500);
        e.focusGame();
        sleep(800);
        // never send a real key to another application
        if (NativeWindows.keyboardFocus() != game) { check("F11 -> fullscreen", false, "game does not have keyboard focus, key not sent"); return; }
        robot.keyPress(java.awt.event.KeyEvent.VK_F11); robot.keyRelease(java.awt.event.KeyEvent.VK_F11);
        sleep(3000);
        int[] fs = NativeWindows.screenRect(game);
        var mon = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
        check("F11 -> fullscreen", e.isFullscreen() && !NativeWindows.isChild(game) && fs[2] >= mon.getWidth() && fs[3] >= mon.getHeight(),
                "fullscreen " + e.isFullscreen() + ", child " + NativeWindows.isChild(game) + ", rect " + fs[2] + "x" + fs[3] + ", monitor " + mon.getWidth() + "x" + mon.getHeight());
        screen("sulfurframe-autotest-4-fullscreen");
        // F11 again: back into the panel, without the caption GLFW adds when leaving fullscreen
        if (NativeWindows.keyboardFocus() != game) { check("F11 again -> embedded, no title bar", false, "game lost keyboard focus while fullscreen, key not sent"); return; }
        robot.keyPress(java.awt.event.KeyEvent.VK_F11); robot.keyRelease(java.awt.event.KeyEvent.VK_F11);
        sleep(3000);
        check("F11 again -> embedded, no title bar", !e.isFullscreen() && e.gameHwnd() == game
                        && NativeWindows.parentOf(game) == e.canvasNativeHwnd() && !NativeWindows.hasCaption(game),
                "fullscreen " + e.isFullscreen() + ", parent " + hex(NativeWindows.parentOf(game)) + ", canvas " + hex(e.canvasNativeHwnd()) + ", caption " + NativeWindows.hasCaption(game));
        snapshot(p, "sulfurframe-autotest-5-after-f11");
        editorTab(p, game);
    }

    private static void editorTab(SulfurPanel p, long game) {
        var e = p.embedder;

        // editor tab: wider area, then back to the tool window when the tab closes
        int toolWidth = NativeWindows.screenRect(e.canvasNativeHwnd())[2];
        onEdt(() -> GameTab.open(project));
        sleep(4000);
        int[] tabCanvas = NativeWindows.screenRect(e.canvasNativeHwnd());
        check("open as editor tab", PanelHost.of(project).inEditor() && e.gameHwnd() == game && NativeWindows.parentOf(game) == e.canvasNativeHwnd() && fits(game, e.canvasNativeHwnd()),
                "canvas " + tabCanvas[2] + "x" + tabCanvas[3] + " (tool window was " + toolWidth + " wide)");
        snapshot(p, "sulfurframe-autotest-6-tab");
        onEdt(() -> {
            var fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            for (var f : fem.getOpenFiles()) if (f instanceof GameTab.GameFile) fem.closeFile(f);
        });
        sleep(4000);
        check("tab closed -> back in the tool window", e.gameHwnd() == game && NativeWindows.parentOf(game) == e.canvasNativeHwnd()
                        && NativeWindows.screenRect(e.canvasNativeHwnd())[2] == toolWidth,
                "canvas width " + NativeWindows.screenRect(e.canvasNativeHwnd())[2]);
    }

    /** The game is the largest 16:9 rectangle that fits the canvas (one side equal, within a pixel or two). */
    static boolean fits(long game, long canvas) {
        int[] g = NativeWindows.screenRect(game), c = NativeWindows.screenRect(canvas);
        if (g == null || c == null) return false;
        boolean inside = g[2] <= c[2] + 1 && g[3] <= c[3] + 1;
        boolean touches = Math.abs(g[2] - c[2]) <= 2 || Math.abs(g[3] - c[3]) <= 2;
        return inside && touches && Math.abs((double) g[2] / g[3] - 16.0 / 9.0) < 0.02;
    }

    private static void screen(String name) {
        try {
            ImageIO.write(new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())), "png",
                    new File(System.getProperty("java.io.tmpdir"), name + ".png"));
        } catch (Exception ex) { lines.add("screen " + name + " failed: " + ex); }
    }

    /** -Dsulfurframe.autotest.runclient=true: press "Run client" and expect the project's game to end up embedded. */
    private static void runClientFlow(SulfurPanel p, ToolWindow tw) {
        onEdt(tw::show);
        sleep(3000);
        // every runnable client found in the opened folder; -Dsulfurframe.autotest.target=<where> picks one
        for (int i = 0; i < p.targetCount(); i++) lines.add("target " + i + ": " + p.targetAt(i).label() + "  [" + p.targetAt(i).runTask() + "]");
        String want = System.getProperty("sulfurframe.autotest.target");
        if (want != null) {
            int idx = -1;
            for (int i = 0; i < p.targetCount(); i++) if (p.targetAt(i).where().equals(want)) idx = i;
            check("target '" + want + "' found", idx >= 0, p.targetCount() + " targets");
            final int sel = idx;
            if (sel >= 0) onEdt(() -> p.selectTarget(sel));
        }
        check("profile detected", p.profile != null && p.profile.isComplete(), String.valueOf(p.profile));
        long t0 = System.currentTimeMillis();
        onEdt(p::runClient);
        var e = p.embedder;
        // attached() turns true just before SetParent: wait for the reparent itself, not only the flag
        while (!(e.attached() && NativeWindows.parentOf(e.gameHwnd()) == e.canvasNativeHwnd())
                && System.currentTimeMillis() - t0 < 10 * 60_000) sleep(500);
        long secs = (System.currentTimeMillis() - t0) / 1000;
        long game = e.gameHwnd(), canvas = e.canvasNativeHwnd();
        check("Run client -> game embedded", game != 0 && NativeWindows.parentOf(game) == canvas,
                "after " + secs + " s, game pid " + e.gamePid() + ", parent " + hex(NativeWindows.parentOf(game)) + ", canvas " + hex(canvas));
        sleep(15000);   // let the dev client finish loading resources
        check("game sized to the canvas (16:9 fit)", game != 0 && fits(game, canvas),
                "game " + NativeWindows.clientSize(game) + ", canvas " + NativeWindows.clientSize(canvas));
        snapshot(p, "sulfurframe-autotest-runclient");
        String probe = System.getProperty("sulfurframe.autotest.hotswap");
        if (probe != null) {
            try { hotswapFlow(p, Path.of(probe)); } catch (Exception ex) { check("hotswap flow", false, ex.toString()); }
        }
        lines.add("game pid " + e.gamePid() + " left running for inspection");
    }

    /**
     * -Dsulfurframe.autotest.hotswap=<source file>: a class whose message() returns "v1" and is logged every 100 client
     * ticks as "[SF-PROBE] v1". Changes it to v2 (Hotswap button path), v3 (save path: file change seen by the VFS),
     * then adds a method (must be reported as needing a restart).
     */
    private static void hotswapFlow(SulfurPanel p, Path src) throws Exception {
        var t = p.selectedTarget();
        Path gameLog = t.dir().resolve("run/logs/latest.log");
        check("probe running (v1 in game log)", waitForLog(gameLog, "[SF-PROBE] v1", 120), String.valueOf(gameLog));

        check("fast compile: class path read from Gradle", p.hotswapper.awaitFastCompile(t, 180), "cached under " + agentJarOf(p).getParent());

        String original = Files.readString(src);
        Files.writeString(src, original.replace("return \"v1\";", "return \"v2\";"));
        long t0 = System.currentTimeMillis();
        boolean ran = p.hotswapper.swap(t, p.embedder.gamePid(), agentJarOf(p));
        long ms = System.currentTimeMillis() - t0;
        check("hotswap: method body change applied", ran && redefinedAny(p.hotswapper.lastResult) && waitForLog(gameLog, "[SF-PROBE] v2", 15),
                "result " + p.hotswapper.lastResult + ", " + ms + " ms incl. compile (" + p.hotswapper.lastCompiler + ")");
        check("hotswap compiled with javac, not Gradle", "javac".equals(p.hotswapper.lastCompiler) && ms < 8000,
                p.hotswapper.lastCompiler + ", " + ms + " ms");

        // save path: change on disk -> VFS refresh -> BulkFileListener -> debounce -> hotswap
        Files.writeString(src, original.replace("return \"v1\";", "return \"v3\";"));
        var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(src);
        if (vf != null) vf.refresh(true, false);   // async refresh: the VFS reports the change like a save
        check("hotswap on save (VFS change -> swap)", waitForLog(gameLog, "[SF-PROBE] v3", 60), "game log shows v3");

        // structural change: a new method cannot be redefined on a standard JVM
        Files.writeString(src, original.replace("return \"v1\";", "return \"v3\";")
                .replace("private SfHotswapProbe() {}", "static String extra() { return \"x\"; }\n\n    private SfHotswapProbe() {}"));
        sleep(1500);   // let a save-triggered swap (from the VFS) finish first
        while (!p.hotswapper.swap(t, p.embedder.gamePid(), agentJarOf(p))) sleep(500);
        check("hotswap: added method -> restart needed", p.hotswapper.lastResult.stream().anyMatch(s -> s.startsWith("restart needed")),
                String.valueOf(p.hotswapper.lastResult));
        Files.writeString(src, original);
    }

    /** Gradle recompiles dependents too, so more than the edited class may be swapped. */
    private static boolean redefinedAny(java.util.List<String> result) {
        return result.stream().anyMatch(s -> s.matches("redefined [1-9][0-9]*"));
    }

    private static Path agentJarOf(SulfurPanel p) {
        return Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "sf-agent.jar");
    }

    private static boolean waitForLog(Path log, String text, int seconds) {
        for (int i = 0; i < seconds * 2; i++) {
            try { if (Files.exists(log) && Files.readString(log).contains(text)) return true; } catch (Exception ignored) { }
            sleep(500);
        }
        return false;
    }

    private static void snapshot(SulfurPanel p, String name) {
        try {
            var r = new Rectangle[1];
            onEdt(() -> r[0] = new Rectangle(p.getLocationOnScreen(), p.getSize()));
            ImageIO.write(new Robot().createScreenCapture(r[0]), "png", new File(System.getProperty("java.io.tmpdir"), name + ".png"));
        } catch (Exception ex) {
            lines.add("snapshot " + name + " failed: " + ex);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        lines.add((ok ? "PASS " : "FAIL ") + name + "  [" + detail + "]");
    }

    private static void write() {
        lines.add("failures: " + failures);
        try { Files.write(Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe-autotest.txt"), lines); } catch (Exception ignored) { }
    }

    private static void onEdt(Runnable r) {
        try { SwingUtilities.invokeAndWait(r); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    private static String hex(long h) { return "0x" + Long.toHexString(h); }
}
