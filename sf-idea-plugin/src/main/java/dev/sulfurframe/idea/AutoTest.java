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
        p.startStats(pid);   // the agent's reports carry the game's fullscreen state, which the F11 check relies on
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

        // F11: out of the IDE, covering the monitor
        var robot = new Robot();
        var ide = javax.swing.SwingUtilities.getWindowAncestor(p);
        onEdt(ide::toFront);
        sleep(500);
        e.focusGame();
        sleep(800);
        // Windows' foreground lock: while another application is in front (the terminal that started the test),
        // toFront() only flashes the taskbar button. Wait up to a minute for the IDE to be brought forward (a click
        // on its title bar) instead of failing; then hand the focus to the game again.
        long until = System.currentTimeMillis() + 60_000;
        boolean waited = false;
        while (NativeWindows.keyboardFocus() != game && System.currentTimeMillis() < until) {
            if (!waited) { waited = true; System.err.println("SulfurFrame autotest: bring the IDE window to the front for the F11 test"); }
            sleep(500);
            // X11 hands the keyboard to any window (XSetInputFocus), front or not; Windows needs the IDE in front
            if (ide.isActive() || dev.sulfurframe.idea.embed.GameView.linux()) { e.focusGame(); sleep(500); }
        }
        if (waited) lines.add("F11: waited for the IDE to come to the front (Windows foreground lock)");
        // WSLg's window manager moves the X focus to the window active on the Windows side, a moment after the
        // activation: the focus must have stayed on the game for a second before the key goes out
        for (int stable = 0; stable < 4 && System.currentTimeMillis() < until + 10_000; ) {
            sleep(250);
            if (NativeWindows.keyboardFocus() == game) stable++; else { stable = 0; e.focusGame(); }
        }
        // never send a real key to another application
        if (NativeWindows.keyboardFocus() != game) { check("F11 -> fullscreen", false, "game does not have keyboard focus, key not sent"); return; }
        robot.keyPress(java.awt.event.KeyEvent.VK_F11); robot.keyRelease(java.awt.event.KeyEvent.VK_F11);
        sleep(3000);
        int[] fs = NativeWindows.screenRect(game);
        var mon = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
        // out of the canvas (Windows: a top-level window; X11: the window manager's, maybe inside its frame)
        boolean inCanvas = NativeWindows.parentOf(game) == e.canvasNativeHwnd();
        check("F11 -> fullscreen", e.isFullscreen() && !inCanvas && fs[2] >= mon.getWidth() && fs[3] >= mon.getHeight(),
                "fullscreen " + e.isFullscreen() + ", in canvas " + inCanvas + ", rect " + fs[2] + "x" + fs[3] + ", monitor " + mon.getWidth() + "x" + mon.getHeight());
        screen("sulfurframe-autotest-4-fullscreen");
        // F11 again: back into the panel, without the caption GLFW adds when leaving fullscreen
        for (int stable = 0, tries = 0; stable < 4 && tries < 40; tries++) {
            sleep(250);
            if (NativeWindows.keyboardFocus() == game) stable++; else { stable = 0; e.focusGame(); }
        }
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
        // -Dsulfurframe.autotest.world=<save folder>: start in that world (the restart test goes back into it)
        if (Boolean.getBoolean("sulfurframe.autotest.worlds")) onEdt(() -> p.shareWorlds.setSelected(true));   // shared instance from the first launch
        if (Boolean.getBoolean("sulfurframe.autotest.debug")) onEdt(() -> p.debug.setSelected(true));
        String world = System.getProperty("sulfurframe.autotest.world");
        // quick play does not create a missing world (1.21.1 stays on the title screen): make it with the test world writer
        if (world != null) {
            var tgt = p.selectedTarget();
            Path saves = SharedInstance.runDir(tgt).resolve("saves");
            String mcv = tgt.profile().minecraftVersion();
            // a player who died or fell out of the world (a click in the game breaks blocks in creative, bedrock too)
            // would start every later run in the void: keep that world aside and start from a new one
            String lost = Files.isDirectory(saves.resolve(world)) ? TestWorld.playerLost(saves.resolve(world)) : null;
            if (lost != null && TestWorld.supported(mcv)) {
                Path aside = saves.resolve(world + "-broken-" + System.currentTimeMillis());
                try { Files.move(saves.resolve(world), aside); lines.add("world '" + world + "': " + lost + "; moved to " + aside.getFileName() + ", making a new one"); }
                catch (Exception ex) { lines.add("world '" + world + "': " + lost + ", could not move it aside: " + ex); }
            }
            if (!Files.isDirectory(saves.resolve(world)) && TestWorld.supported(mcv)) {
                try { Files.createDirectories(saves); TestWorld.create(saves, world, mcv); lines.add("world '" + world + "' created (test world writer)"); }
                catch (Exception ex) { lines.add("world '" + world + "' could not be created: " + ex); }
            }
        }
        if (world != null) onEdt(() -> p.runClient(java.util.List.of("--quickPlaySingleplayer", world)));
        else onEdt(p::runClient);
        // -Dsulfurframe.autotest.decoy=<.cmd printing "pid=N">: another Minecraft started meanwhile must not be embedded
        long decoy = 0;
        String decoyCmd = System.getProperty("sulfurframe.autotest.decoy");
        if (decoyCmd != null) {
            try {
                var dp = new ProcessBuilder("cmd", "/c", decoyCmd).redirectErrorStream(true).start();
                var m = java.util.regex.Pattern.compile("pid=(\\d+)").matcher(new String(dp.getInputStream().readAllBytes()));
                if (m.find()) decoy = Long.parseLong(m.group(1));
                lines.add("decoy Minecraft started, pid " + decoy);
            } catch (Exception ex) { lines.add("decoy failed: " + ex); }
        }
        var e = p.embedder;
        // attached() turns true just before SetParent: wait for the reparent itself, not only the flag
        while (!(e.attached() && NativeWindows.parentOf(e.gameHwnd()) == e.canvasNativeHwnd())
                && System.currentTimeMillis() - t0 < 10 * 60_000) sleep(500);
        long secs = (System.currentTimeMillis() - t0) / 1000;
        long game = e.gameHwnd(), canvas = e.canvasNativeHwnd();
        check("Run client -> game embedded", game != 0 && NativeWindows.parentOf(game) == canvas,
                "after " + secs + " s, game pid " + e.gamePid() + ", parent " + hex(NativeWindows.parentOf(game)) + ", canvas " + hex(canvas));
        if (decoy != 0) {
            check("another Minecraft (decoy) not embedded", e.gamePid() != decoy && ProcessHandle.of(decoy).isPresent(),
                    "embedded pid " + e.gamePid() + ", decoy pid " + decoy);
            ProcessHandle.of(decoy).ifPresent(ProcessHandle::destroyForcibly);
        }
        sleep(15000);   // let the dev client finish loading resources
        check("game sized to the canvas (16:9 fit)", game != 0 && fits(game, canvas),
                "game " + NativeWindows.clientSize(game) + ", canvas " + NativeWindows.clientSize(canvas));
        snapshot(p, "sulfurframe-autotest-runclient");
        // a new user without a JetBrains Runtime (-Dsulfurframe.noJbr=true): the game still runs, and the download is offered
        int major = p.profile == null ? 0 : p.profile.javaMajor();
        if (Jbr.find(major) == null && JbrDownloader.available(major) && !Boolean.getBoolean("sulfurframe.noJbrOffer"))
            check("no JetBrains Runtime -> download offered", JbrDownloader.wasOffered(major) && p.logText().contains("a download is offered"),
                    "Java " + major + ", " + firstLine(p.logText(), "no JetBrains Runtime"));
        String probe = System.getProperty("sulfurframe.autotest.hotswap");
        if (probe != null) {
            try { hotswapFlow(p, Path.of(probe)); } catch (Exception ex) { check("hotswap flow", false, ex.toString()); }
            try { restartFlow(p, Path.of(probe), world); } catch (Exception ex) { check("restart flow", false, ex.toString()); }
            if (Boolean.getBoolean("sulfurframe.autotest.worlds"))
                try { worldsFlow(p, Path.of(probe)); } catch (Exception ex) { check("worlds flow", false, ex.toString()); }
        }
        uiChecks(p);
        lines.add("game pid " + e.gamePid() + " left running for inspection");
    }

    /**
     * A changed onInitialize runs again in the game (its event listeners replaced, not added twice); mod metadata changes
     * offer a restart; the restart must save, quit, start again and land back in the same world, embedded.
     */
    private static void restartFlow(SulfurPanel p, Path probe, String world) throws Exception {
        var t = p.selectedTarget();
        Path main = modInitializerNextTo(probe);
        if (main != null) entryRerunFlow(p, main, t.dir().resolve("run/logs/latest.log"));
        // mod metadata: read only at launch
        Path meta = t.dir().resolve("src/main/resources/fabric.mod.json");
        if (Files.exists(meta)) {
            onEdt(() -> p.setSwapOnSave(true));   // saves are watched again (the metadata check goes through them)
            p.onSourcesSaved(java.util.List.of(meta));
            for (int i = 0; i < 20 && !p.logText().contains("fabric.mod.json changed"); i++) sleep(250);   // the log is appended on the EDT
            check("fabric.mod.json change -> restart offered", p.logText().contains("fabric.mod.json changed"), "panel log");
        }
        // the restart itself: the build is unchanged and the edited source was compiled, so no Gradle
        Path gameLog = t.dir().resolve("run/logs/latest.log");
        int mark = p.logText().length();
        restartOnce(p, world, gameLog, "restart");
        String log = p.logText().substring(Math.min(mark, p.logText().length()));
        check("restart -> started without Gradle", log.contains("started the game without Gradle"),
                firstLine(log, "starting through Gradle", "started ") + ", window after " + p.launcherWindowMillis() / 1000 + " s");
        // mod metadata edited: filled in from the last Gradle output (MetadataTemplate), still no Gradle
        if (Files.exists(meta) && QuickRelaunch.enabled()) {
            String original = Files.readString(meta);
            String edited = original.replaceFirst("\"name\"\\s*:\\s*\"", "\"name\": \"SF-edited ");
            Path out = t.dir().resolve("build/resources/main/fabric.mod.json");
            if (!edited.equals(original)) {
                Files.writeString(meta, edited);
                mark = p.logText().length();
                restartOnce(p, world, gameLog, "restart after fabric.mod.json edited");
                log = p.logText().substring(Math.min(mark, p.logText().length()));
                String written = Files.exists(out) ? Files.readString(out) : "";
                check("restart after fabric.mod.json edited -> without Gradle, filled in like processResources",
                        log.contains("started the game without Gradle") && log.contains("fabric.mod.json filled in without Gradle")
                                && written.contains("SF-edited ") && !written.contains("${"),
                        firstLine(log, "filled in", "starting through Gradle", "started ") + ", window after " + p.launcherWindowMillis() / 1000 + " s");
                Files.writeString(meta, original);
            }
            accessWidenerRestart(p, probe, world, gameLog);
            // a build file changed: Gradle has to configure again, so this one goes through Gradle
            Path props = t.dir().resolve("gradle.properties");
            if (Files.exists(props)) {
                Files.setLastModifiedTime(props, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                mark = p.logText().length();
                restartOnce(p, world, gameLog, "restart after gradle.properties changed");
                log = p.logText().substring(Math.min(mark, p.logText().length()));
                check("restart after gradle.properties changed -> through Gradle", log.contains("starting through Gradle: build file changed") && log.contains("gradlew"),
                        firstLine(log, "starting through Gradle", "started ") + ", window after " + p.launcherWindowMillis() / 1000 + " s");
            }
        }
    }

    /**
     * onInitialize gets a server tick listener (every 40 ticks), then the listener changes (every 20), then goes again:
     * each time the entry point runs again in the game (RuntimeRegister / EntryRerun), no restart is offered, a tick is
     * never logged twice (the old listener was taken out), and after the revert no tick is logged at all.
     * The same for onInitializeClient with a client tick listener, next to the demo's own reload listener (an anonymous
     * class, which Fabric refuses to take twice).
     */
    private static void entryRerunFlow(SulfurPanel p, Path main, Path gameLog) throws Exception {
        entryRerun(p, main, gameLog, "onInitialize", "LOGGER", "SF-INIT",
                "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(s -> {"
                        + " if (s.getTicks() % EVERY == 0) LOGGER.info(\"[SF-INIT] tick \" + s.getTicks()); });");
        Path client = main.getParent().resolve("client/" + main.getFileName().toString().replace(".java", "Client.java"));
        String logger = probeClassOf(main) + ".LOGGER";
        if (Files.exists(client))
            entryRerun(p, client, gameLog, "onInitializeClient", logger, "SF-CLIENT",
                    // its own counter (client world time is corrected by the server and can repeat): two listeners -> each number twice
                    "java.util.concurrent.atomic.AtomicInteger sfTicks = new java.util.concurrent.atomic.AtomicInteger();"
                            + " net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> {"
                            + " int n = sfTicks.incrementAndGet(); if (n % EVERY == 0) " + logger + ".info(\"[SF-CLIENT] tick \" + n); });");
    }

    private static void entryRerun(SulfurPanel p, Path file, Path gameLog, String method, String logger, String tag, String listener) throws Exception {
        String cls = file.getFileName().toString().replace(".java", "");
        String src = Files.readString(file);
        String head = "public void " + method + "() {";
        if (!src.contains(head)) return;
        java.util.function.IntFunction<String> withListener = every -> src.replace(head, head
                + "\n\t\t" + logger + ".info(\"[SF] " + method + " edited " + every + "\");"
                + "\n\t\t" + listener.replace("EVERY", String.valueOf(every)));
        try {
            for (int every : new int[]{40, 20}) {
                Files.writeString(file, withListener.apply(every));
                String ran = rerunOnce(p);
                String log = Files.exists(gameLog) ? Files.readString(gameLog) : "";
                boolean edited = waitForLog(gameLog, "[SF] " + method + " edited " + every, 10);
                sleep(10000);   // 200 ticks: a few listener runs, even after a slow reload
                var ticks = ticksLogged(gameLog, log.length(), tag);
                boolean once = ticks.stream().distinct().count() == ticks.size() && !ticks.isEmpty() && ticks.stream().allMatch(n -> n % every == 0);
                check(method + " changed (listener every " + every + " ticks) -> ran again in the game, listener once, no restart",
                        ran.contains(cls + "." + method + " again") && p.hotswapper.lastStartupChanges.isEmpty() && edited && once,
                        ran + ", ticks " + ticks);
            }
        } finally {
            Files.writeString(file, src);
        }
        String ran = rerunOnce(p);
        sleep(1000);
        int from = Files.exists(gameLog) ? Files.readString(gameLog).length() : 0;
        sleep(4000);
        var ticks = ticksLogged(gameLog, from, tag);
        check(method + " reverted -> listener gone", ran.contains(cls + "." + method + " again") && ticks.isEmpty(), ran + ", ticks " + ticks);
    }

    /** Save-path hotswap (as when the editor saves); the registration answer, or why there is none. */
    private static String rerunOnce(SulfurPanel p) {
        sleep(1500);
        p.hotswapper.lastRegistration = java.util.List.of();
        onEdt(p::hotswapAsync);
        for (int i = 0; i < 80 && p.hotswapper.lastRegistration.isEmpty(); i++) sleep(250);
        return String.join(" | ", p.hotswapper.lastRegistration);
    }

    /** "[tag] tick N" numbers in the game log after {@code from}. */
    private static java.util.List<Integer> ticksLogged(Path gameLog, int from, String tag) throws Exception {
        var out = new java.util.ArrayList<Integer>();
        String log = Files.readString(gameLog);
        var m = java.util.regex.Pattern.compile("\\[" + tag + "] tick (\\d+)").matcher(log.substring(Math.min(from, log.length())));
        while (m.find()) out.add(Integer.parseInt(m.group(1)));
        return out;
    }

    /**
     * The mod's access widener widens a Minecraft field and the probe reads it directly: that compiles only with the
     * widened class (AwOverlay), and runs only because Fabric Loader applies the new widener at start. No Gradle.
     */
    private static void accessWidenerRestart(SulfurPanel p, Path probe, String world, Path gameLog) throws Exception {
        var t = p.selectedTarget();
        Path aw;
        try (var s = Files.list(t.dir().resolve("src/main/resources"))) {
            aw = s.filter(f -> Hotswapper.isWidener(f.getFileName().toString())).findFirst().orElse(null);
        }
        if (aw == null) return;
        String awOriginal = Files.readString(aw), probeOriginal = Files.readString(probe);
        String probeEdited = probeOriginal.replaceFirst("static String message\\(\\) \\{[^}]*}",
                "static String message() { return \"aw\" + (net.minecraft.client.MinecraftClient.currentFps >= 0 ? \"-ok\" : \"\"); }");
        if (probeEdited.equals(probeOriginal)) { check("access widener restart", false, "probe has no message()"); return; }
        onEdt(() -> p.setSwapOnSave(false));   // a hotswap of the probe alone cannot compile: the widener is not applied yet
        try {
            Files.writeString(aw, awOriginal.stripTrailing() + "\naccessible field net/minecraft/client/MinecraftClient currentFps I\n");
            Files.writeString(probe, probeEdited);
            int mark = p.logText().length();
            restartOnce(p, world, gameLog, "restart after access widener edited");
            String log = p.logText().substring(Math.min(mark, p.logText().length()));
            check("restart after access widener edited -> without Gradle, widened field used by the mod",
                    log.contains("started the game without Gradle") && log.contains("applied by Fabric Loader at start")
                            && waitForLog(gameLog, "[SF-PROBE] aw-ok", 30),
                    firstLine(log, "applied by Fabric Loader", "could not apply", "starting through Gradle", "started ")
                            + ", window after " + p.launcherWindowMillis() / 1000 + " s");
        } finally {
            Files.writeString(aw, awOriginal);
            Files.writeString(probe, probeOriginal);
            onEdt(() -> p.setSwapOnSave(true));
        }
    }

    private static void restartOnce(SulfurPanel p, String world, Path gameLog, String name) throws Exception {
        long before = p.embedder.gamePid();
        long t0 = System.currentTimeMillis();
        p.restart();
        var e = p.embedder;
        while (!(e.attached() && e.gamePid() != before && NativeWindows.parentOf(e.gameHwnd()) == e.canvasNativeHwnd())
                && System.currentTimeMillis() - t0 < 5 * 60_000) sleep(500);
        check(name + " -> new game embedded", e.attached() && e.gamePid() != before && !ProcessHandle.of(before).isPresent(),
                "old pid " + before + " (gone " + !ProcessHandle.of(before).isPresent() + "), new pid " + e.gamePid() + ", " + (System.currentTimeMillis() - t0) / 1000 + " s");
        if (world != null) {
            boolean in = waitForLog(gameLog, "logged in with entity id", 120);
            check(name + " -> back in the same world", world.equals(p.lastRestartWorld) && in,
                    "world '" + p.lastRestartWorld + "', " + (System.currentTimeMillis() - t0) / 1000 + " s from restart to world");
        }
    }

    /** The first panel log line containing one of the needles (for a check's detail). */
    private static String firstLine(String log, String... needles) {
        for (String l : log.split("\n")) for (String n : needles) if (l.contains(n)) return l.strip();
        return "(no such line)";
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
        check("hotswap compiled with javac, not Gradle", p.hotswapper.lastCompiler.startsWith("javac") && ms < 8000,
                p.hotswapper.lastCompiler + ", " + ms + " ms");

        // save path: change on disk -> VFS refresh -> BulkFileListener -> debounce -> hotswap
        Files.writeString(src, original.replace("return \"v1\";", "return \"v3\";"));
        var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(src);
        if (vf != null) vf.refresh(true, false);   // async refresh: the VFS reports the change like a save
        check("hotswap on save (VFS change -> swap)", waitForLog(gameLog, "[SF-PROBE] v3", 60), "game log shows v3");

        // a change made outside the IDE (external editor, coding agent): written to disk with no VFS refresh, which the
        // IDE alone would only notice when its window is activated; the source folder watcher must pick it up
        sleep(1500);
        Files.writeString(src, original.replace("return \"v1\";", "return \"v3x\";"));
        check("hotswap on an outside edit (file watcher, no IDE refresh)", waitForLog(gameLog, "[SF-PROBE] v3x", 60), "game log shows v3x");
        onEdt(() -> p.setSwapOnSave(false));   // from here on only the test's own swaps: no race with save-triggered ones
        sleep(1500);

        // structural change: a new field + method. On JBR (enhanced redefinition) it applies and the new method runs;
        // on a standard JVM it is refused and reported as needing a restart.
        boolean enhanced = Jbr.find(t.profile().javaMajor()) != null;
        Files.writeString(src, original.replace("return \"v1\";", "return \"v4 \" + extra();")
                .replace("private SfHotswapProbe() {}", "private static int calls;\n\n    static String extra() { return \"extra\" + (++calls); }\n\n    private SfHotswapProbe() {}"));
        sleep(1500);   // let a save-triggered swap (from the VFS) finish first
        swapRetry(p, t);
        if (enhanced) {
            check("hotswap (JBR): added field + method applied and running", redefinedAny(p.hotswapper.lastResult)
                            && p.hotswapper.lastResult.stream().noneMatch(s -> s.startsWith("restart needed")) && waitForLog(gameLog, "[SF-PROBE] v4 extra", 20),
                    String.valueOf(p.hotswapper.lastResult));
            // and take them out again
            Files.writeString(src, original.replace("return \"v1\";", "return \"v5\";"));
            sleep(1500);
            swapRetry(p, t);
            check("hotswap (JBR): removed field + method applied", redefinedAny(p.hotswapper.lastResult)
                            && p.hotswapper.lastResult.stream().noneMatch(s -> s.startsWith("restart needed")) && waitForLog(gameLog, "[SF-PROBE] v5", 20),
                    String.valueOf(p.hotswapper.lastResult));
        } else {
            check("hotswap: added method -> restart needed (no JBR)", p.hotswapper.lastResult.stream().anyMatch(s -> s.startsWith("restart needed")),
                    String.valueOf(p.hotswapper.lastResult));
        }

        // warm compile in the IDE: the whole swap (compile + attach + redefine) well under the old 2.4 s
        Files.writeString(src, original.replace("return \"v1\";", "return \"v6\";"));
        sleep(1500);
        long t1 = System.currentTimeMillis();
        swapRetry(p, t);
        long ms6 = System.currentTimeMillis() - t1;
        check("hotswap compiled in the IDE (warm), whole swap fast", "javac in the IDE".equals(p.hotswapper.lastCompiler) && ms6 < 2500
                        && waitForLog(gameLog, "[SF-PROBE] v6", 20), p.hotswapper.lastCompiler + ", " + ms6 + " ms");
        // shown on the game's next tick: with the frame rate capped while the editor has focus that can take a while
        check("in-game message after a swap", waitForLog(gameLog, "in-game message shown: Hotswap", 30), "agent log line");
        Files.writeString(src, original);

        // a compile-time constant: javac copied it into the probe, which fast compile did not touch; its users must be
        // recompiled too or the game keeps printing the old value
        Path konst = src.getParent().resolve("SfProbeConst.java");
        if (Files.exists(konst) && waitForLog(gameLog, " k1", 20)) {
            String k = Files.readString(konst);
            Files.writeString(konst, k.replace("\"k1\"", "\"k2\""));
            sleep(1500);
            swapRetry(p, t);
            check("constant change reaches the classes that copied it", waitForLog(gameLog, " k2", 20)
                    && p.logText().contains("constant changed in SfProbeConst"), String.valueOf(p.hotswapper.lastResult));
            Files.writeString(konst, k);
            sleep(1500);
            swapRetry(p, t);
        }

        // a Gradle build rewrites every class file with the same bytes: nothing may be sent (751 classes crashed a game)
        try (var w = Files.walk(t.dir().resolve("build/classes"))) {
            var now = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis());
            for (Path c : w.filter(f -> f.toString().endsWith(".class")).toList()) Files.setLastModifiedTime(c, now);
        }
        swapRetry(p, t);
        check("rebuilt class files with the same bytes are not sent", p.hotswapper.lastResult.contains("redefined 0"),
                String.valueOf(p.hotswapper.lastResult));

        // a mixin: Mixin's own hotswap agent (put on the game JVM by Run client) re-applies it to its target
        Path mixin = src.getParent().resolve("mixin/SfMixinProbe.java");
        if (Files.exists(mixin) && waitForLog(gameLog, "[SF-MIXIN] m1", 30)) {
            String m = Files.readString(mixin);
            Files.writeString(mixin, m.replace("\"m1\"", "\"m2\""));
            sleep(1500);
            swapRetry(p, t);
            check("hotswap: mixin change re-applied to its target", waitForLog(gameLog, "Redefining mixin", 10) && waitForLog(gameLog, "[SF-MIXIN] m2", 20),
                    String.valueOf(p.hotswapper.lastResult));
            Files.writeString(mixin, m);
        }

        // annotation processors: getTag() only exists if Lombok ran during the fast compile
        Path lombok = src.getParent().resolve("SfLombokProbe.java");
        if (Files.exists(lombok) && waitForLog(gameLog, "[SF-LOMBOK] L1", 30)) {
            String l = Files.readString(lombok);
            Files.writeString(lombok, l.replace("\"L1\"", "\"L2\""));
            sleep(1500);
            swapRetry(p, t);
            check("hotswap with an annotation processor (Lombok)", p.hotswapper.lastCompiler.startsWith("javac") && waitForLog(gameLog, "[SF-LOMBOK] L2", 20),
                    p.hotswapper.lastCompiler + ", " + p.hotswapper.lastResult);
            Files.writeString(lombok, l);
        }

        // Kotlin: the build's own Kotlin compiler, run in the IDE (not Gradle)
        Path kt = t.dir().resolve("src/main/kotlin").resolve(probeClassOf(src).replaceFirst("\\.[^.]+$", "").replace('.', '/')).resolve("SfKotlinProbe.kt");
        if (Files.exists(kt) && waitForLog(gameLog, "[SF-KOTLIN] k1", 30)) {
            String k = Files.readString(kt);
            Files.writeString(kt, k.replace("\"k1\"", "\"k2\""));
            sleep(1500);
            long k0 = System.currentTimeMillis();
            swapRetry(p, t);
            long kms = System.currentTimeMillis() - k0;
            check("hotswap of a Kotlin file, compiled by the warm Kotlin server", p.hotswapper.lastCompiler.contains("kotlinc server") && kms < 4000
                            && waitForLog(gameLog, "[SF-KOTLIN] k2", 20), p.hotswapper.lastCompiler + ", " + kms + " ms, " + p.hotswapper.lastResult);
            Files.writeString(kt, k);
        }

        // resources: copy into the dev resource dir + client reload (F3+T)
        Path lang = langFileOf(t.dir());
        if (lang != null) {
            String l = Files.readString(lang);
            Files.writeString(lang, l + "\n");
            boolean ok = p.hotswapper.reloadResources(t, p.embedder.gamePid(), agentJarOf(p), java.util.List.of(lang));
            check("resource change -> client reload", ok && p.hotswapper.lastResources.startsWith("assets")
                            && waitForLog(gameLog, "resources: client reload started", 15), p.hotswapper.lastResources);
            Files.writeString(lang, l);
        }
        hierarchyFlow(p, src, gameLog);
        newBlockFlow(p, src, gameLog);
    }

    /**
     * The probe gets an interface, then a superclass, while the game runs (JetBrains Runtime's enhanced redefinition):
     * each must apply without a restart and show in the game (the probe reports what it is assignable to).
     */
    private static void hierarchyFlow(SulfurPanel p, Path probe, Path gameLog) throws Exception {
        var t = p.selectedTarget();
        Path base = probe.getParent().resolve("SfProbeBase.java");
        if (!Files.exists(base) || Jbr.find(t.profile().javaMajor()) == null) return;
        String original = Files.readString(probe);
        String decl = "public final class SfHotswapProbe {";
        if (!original.contains(decl) || !original.contains("static String message() {")) { check("hierarchy change", false, "probe changed shape"); return; }
        String report = "static String message() { return \"h\" + (SfProbeFace.class.isAssignableFrom(SfHotswapProbe.class) ? \"-iface\" : \"\")"
                + " + (SfProbeBase.class.isAssignableFrom(SfHotswapProbe.class) ? \"-super\" : \"\"); }";
        String body = original.replaceFirst("static String message\\(\\) \\{[^}]*}", java.util.regex.Matcher.quoteReplacement(report));
        try {
            // a project interface: a JDK one (Supplier) made JBR null unrelated Supplier fields in Minecraft, and the game crashed
            Files.writeString(probe, body.replace(decl, "public final class SfHotswapProbe implements SfProbeFace {"));
            sleep(1500);
            swapRetry(p, t);
            check("hierarchy (JBR): interface added, applied without restart", redefinedAny(p.hotswapper.lastResult)
                    && p.hotswapper.lastResult.stream().noneMatch(s -> s.startsWith("restart needed")) && waitForLog(gameLog, "[SF-PROBE] h-iface ", 15),
                    String.valueOf(p.hotswapper.lastResult));
            Files.writeString(probe, body.replace(decl, "public final class SfHotswapProbe extends SfProbeBase {"));
            sleep(1500);
            swapRetry(p, t);
            check("hierarchy (JBR): superclass changed, interface removed, applied without restart", redefinedAny(p.hotswapper.lastResult)
                    && p.hotswapper.lastResult.stream().noneMatch(s -> s.startsWith("restart needed")) && waitForLog(gameLog, "[SF-PROBE] h-super ", 15),
                    String.valueOf(p.hotswapper.lastResult));
            // a JDK interface: held back (restart needed) instead of crashing the game
            Files.writeString(probe, body.replace(decl, "public final class SfHotswapProbe extends SfProbeBase implements java.util.function.Supplier<String> {"
                    + "\n    public String get() { return \"s\"; }"));
            sleep(1500);
            swapRetry(p, t);
            long pid = p.embedder.gamePid();
            sleep(3000);
            check("hierarchy: a JDK interface added -> not swapped, restart needed, game still running",
                    p.hotswapper.lastResult.stream().anyMatch(s -> s.startsWith("restart needed") && s.contains("java.util.function.Supplier"))
                            && ProcessHandle.of(pid).isPresent() && p.embedder.attached(), String.valueOf(p.hotswapper.lastResult));
        } finally {
            Files.writeString(probe, original);
            sleep(1500);
            swapRetry(p, t);
        }
    }

    /**
     * A new block in the running game (RuntimeRegister): the block class gets a new field and two register lines, and
     * after the hotswap the block exists on the server (setblock, give) without a restart. Needs JBR (a new field).
     */
    private static void newBlockFlow(SulfurPanel p, Path probe, Path gameLog) throws Exception {
        Path blocks = probe.getParent().resolve("ShowcaseBlocks.java");
        if (!Files.exists(blocks) || p.embedder.gamePid() <= 0 || Jbr.find(p.selectedTarget().profile().javaMajor()) == null) return;
        var t = p.selectedTarget();
        String original = Files.readString(blocks);
        String anchor = "Registry.register(Registries.ITEM, probe, new BlockItem(PROBE, new Item.Settings()));";
        String fieldAnchor = "public static final Block PROBE = new Block(AbstractBlock.Settings.create());";
        if (!original.contains(anchor) || !original.contains(fieldAnchor)) { check("new block registered in the running game", false, "ShowcaseBlocks changed shape"); return; }
        Path res = t.dir().resolve("src/main/resources/assets/showcase");
        Path state = res.resolve("blockstates/sf_runtime.json"), item = res.resolve("models/item/sf_runtime.json");
        try {
            Files.writeString(state, "{\"variants\": {\"\": {\"model\": \"showcase:block/tile\"}}}\n");
            Files.writeString(item, "{\"parent\": \"showcase:block/tile\"}\n");
            p.hotswapper.reloadResources(t, p.embedder.gamePid(), agentJarOf(p), java.util.List.of(state, item));
            p.hotswapper.lastRegistration = java.util.List.of();
            int mark = p.logText().length();   // a save-triggered swap may get there first: both count
            Files.writeString(blocks, original
                    .replace(fieldAnchor, fieldAnchor + "\n    public static final Block SF_RUNTIME = new Block(AbstractBlock.Settings.create().strength(1.0f));")
                    .replace(anchor, anchor + "\n        Identifier runtime = Identifier.of(Showcase.ID, \"sf_runtime\");"
                            + "\n        Registry.register(Registries.BLOCK, runtime, SF_RUNTIME);"
                            + "\n        Registry.register(Registries.ITEM, runtime, new BlockItem(SF_RUNTIME, new Item.Settings()));"));
            sleep(1500);
            swapRetry(p, t);
            String log = p.logText().substring(Math.min(mark, p.logText().length()));
            var reg = p.hotswapper.lastRegistration;
            boolean registered = reg.stream().anyMatch(s -> s.startsWith("registered") && s.contains("block showcase:sf_runtime"))
                    && reg.stream().anyMatch(s -> s.startsWith("registered") && s.contains("item showcase:sf_runtime"));
            check("new block registered in the running game, no restart offered", registered && p.hotswapper.lastStartupChanges.isEmpty()
                    && !log.contains("restart needed"), firstLine(log, "registration", "restart needed") + " " + reg);
            String set = command(p, "execute as @p at @s run setblock ~2 ~ ~3 showcase:sf_runtime");
            String give = command(p, "give @p showcase:sf_runtime");
            check("new block usable on the server (setblock, give)", "done".equals(set) && "done".equals(give)
                    && waitForLog(gameLog, "Changed the block at", 10) && waitForLog(gameLog, "Gave 1 [", 10), set + ", " + give);
            command(p, "execute as @p at @s run setblock ~2 ~ ~3 air");
            command(p, "clear @p showcase:sf_runtime");
        } finally {
            Files.writeString(blocks, original);
            Files.deleteIfExists(state); Files.deleteIfExists(item);
        }
        // back to the old code: run again, nothing new, still no restart
        p.hotswapper.lastRegistration = java.util.List.of();
        sleep(1500);
        swapRetry(p, t);
        check("registration code reverted -> nothing new registered, no restart offered", p.hotswapper.lastStartupChanges.isEmpty()
                && !p.hotswapper.lastRegistration.isEmpty()
                && p.hotswapper.lastRegistration.stream().noneMatch(s -> s.startsWith("registered") || s.startsWith("error")), String.valueOf(p.hotswapper.lastRegistration));
    }

    /** Run a command on the integrated server; "done" or why not. */
    private static String command(SulfurPanel p, String cmd) throws Exception {
        long pid = p.embedder.gamePid();
        Path done = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "autotest-cmd-" + pid + ".txt");
        Files.deleteIfExists(done);
        Hotswapper.loadAgent(pid, agentJarOf(p), "cmd=" + WorldSnapshots.b64(cmd) + ",done=" + done);
        for (int i = 0; i < 100 && !Files.exists(done); i++) sleep(100);
        return Files.exists(done) ? Files.readString(done).strip() : "no answer";
    }

    /** The probe's fully qualified class name, from its package line and file name. */
    private static String probeClassOf(Path probe) {
        String name = probe.getFileName().toString().replaceFirst("\\.(java|kt)$", "");
        try {
            var m = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)").matcher(Files.readString(probe));
            if (m.find()) return m.group(1) + "." + name;
        } catch (Exception ignored) { }
        return name;
    }

    /** The mod's ModInitializer source in the probe's folder (its onInitialize is the startup code the test edits). */
    private static Path modInitializerNextTo(Path probe) {
        try (var s = Files.list(probe.getParent())) {
            for (Path f : s.filter(f -> f.toString().endsWith(".java")).sorted().toList())
                if (Files.readString(f).contains("implements ModInitializer")) return f;
        } catch (Exception ignored) { }
        return null;
    }

    /** A translation file of the mod (any assets/<id>/lang/*.json), the resource the reload test touches. */
    private static Path langFileOf(Path projectDir) {
        Path assets = projectDir.resolve("src/main/resources/assets");
        if (!Files.isDirectory(assets)) return null;
        try (var w = Files.walk(assets, 3)) {
            return w.filter(f -> f.getParent() != null && f.getParent().getFileName().toString().equals("lang") && f.toString().endsWith(".json"))
                    .sorted().findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    /** Wait until a game other than {@code before} is embedded and its log says it joined a world. */
    private static boolean waitForNewGame(SulfurPanel p, long before, Path gameLog, int seconds) {
        long t0 = System.currentTimeMillis();
        var e = p.embedder;
        while (System.currentTimeMillis() - t0 < seconds * 1000L) {
            if (e.attached() && e.gamePid() != before && NativeWindows.parentOf(e.gameHwnd()) == e.canvasNativeHwnd()) break;
            sleep(500);
        }
        if (!(e.attached() && e.gamePid() != before)) return false;
        for (int i = 0; i < seconds * 2; i++) {   // the new game writes a fresh latest.log
            try { if (Files.exists(gameLog) && Files.readString(gameLog).contains("logged in with entity id")) return true; } catch (Exception ignored) { }
            sleep(500);
        }
        return false;
    }

    /**
     * Shared instance, problems/translations, snapshot + restore, temporary world, crash handling.
     * Runs with -Dsulfurframe.autotest.worlds=true and -Dsulfurframe.home=<scratch dir> (keeps test worlds out of ~).
     */
    private static void worldsFlow(SulfurPanel p, Path probe) throws Exception {
        var t = p.selectedTarget();
        Path run = SharedInstance.runDir(t), gameLog = run.resolve("logs/latest.log");
        String mc = t.profile().minecraftVersion();
        // shared instance (enabled before Run client by runClientFlow)
        Path shared = SharedInstance.forVersion(mc).resolve("saves");
        check("shared instance: run/saves linked into " + SharedInstance.root(), SharedInstance.linksTo(run.resolve("saves"), shared)
                && Files.isDirectory(shared.resolve(testWorld())), shared.toString());

        // problems the game reported (the lucid mod has a block without models) and translations
        var g = p.gameLogPanel;
        for (int i = 0; i < 20 && g.problemCount() == 0; i++) sleep(500);
        check("problems: missing model reported by the game", g.problemList().stream().anyMatch(x -> x.kind().contains("model")), String.valueOf(g.problemList()));
        int missing = g.missingTranslations().size();
        int added = g.addMissingTranslations();
        check("translations: missing en_us keys found and added", missing > 0 && added == missing && g.missingTranslations().isEmpty(),
                missing + " missing, " + added + " added");

        // snapshot, then restore it (quick restart in between)
        p.snapshot();
        Path snap = p.lastSnapshot;
        check("snapshot of the open world (after save-all flush)", snap != null && Files.exists(snap.resolve("level.dat")), String.valueOf(snap));
        long before = p.embedder.gamePid();
        if (snap != null) {
            p.restoreSnapshot(testWorld(), snap);
            boolean back = waitForNewGame(p, before, gameLog, 300);
            boolean kept = WorldSnapshots.list(mc, testWorld()).stream().anyMatch(s -> s.getFileName().toString().endsWith("before-restore"));
            check("restore: game restarted into the restored world, replaced world kept as a snapshot", back && kept, "back " + back + ", kept " + kept);
        }

        // temporary world: in, then out again; it must be gone afterwards
        before = p.embedder.gamePid();
        p.testWorld(true);
        String temp = p.lastTestWorld;
        boolean inTemp = temp != null && waitForNewGame(p, before, gameLog, 300);
        check("temporary test world created and joined", inTemp, String.valueOf(temp));
        before = p.embedder.gamePid();
        p.restart(testWorld(), null);
        boolean out = waitForNewGame(p, before, gameLog, 300);
        Path tempDir = shared.resolve(String.valueOf(temp));
        for (int i = 0; i < 20 && Files.exists(tempDir); i++) sleep(500);
        check("temporary world removed after its game exited", out && !Files.exists(tempDir), tempDir + " exists " + Files.exists(tempDir));

        // what the player looks at (block or entity, or nothing in reach)
        boolean looked = p.inspect();
        var look = p.lastLook;
        check("inspect: the game says what the player looks at", looked && look.containsKey("kind") && !look.containsKey("error"), String.valueOf(look));

        // mouse look while embedded: the grab made after the focusing click must reach GLFW (agent GlfwCursorFix)
        mouseLookCheck(p, gameLog);

        // datagen: a generated file is missing -> Datagen brings it back and the running game reloads it
        Path gen = t.dir().resolve("src/main/generated/data/c/tags/item/ingots.json");
        if (Files.exists(gen)) {
            Files.delete(gen);
            p.hotswapper.lastResources = "";
            p.lastDatagen = null;
            p.datagenAsync();
            for (int i = 0; i < 400 && p.lastDatagen == null; i++) sleep(500);
            for (int i = 0; i < 20 && p.hotswapper.lastResources.isEmpty(); i++) sleep(500);
            var r = p.lastDatagen;
            check("datagen: task run, regenerated file found, game reloaded it", r != null && r.ok() && r.added() == 1 && Files.exists(gen)
                    && p.hotswapper.lastResources.startsWith("data"), (r == null ? "no result" : r.changed() + " in " + r.ms() + " ms") + ", reload " + p.hotswapper.lastResources);
        }

        // debugger attached by Run client (-Dsulfurframe.autotest.debug=true turns the checkbox on before launching)
        if (Boolean.getBoolean("sulfurframe.autotest.debug") && project != null) {
            var sessions = com.intellij.xdebugger.XDebuggerManager.getInstance(project).getDebugSessions();
            boolean attached = java.util.Arrays.stream(sessions).anyMatch(s -> s.getSessionName().equals(DebugAttach.NAME));
            check("debugger attached to the game", attached, sessions.length + " debug session(s)");
        }

        // crash: a tick handler that throws -> notification with the cause and our own frame
        String src = Files.readString(probe);
        Files.writeString(probe, src.replace("return \"v1\";", "if (ticks > 0) throw new IllegalStateException(\"SulfurFrame crash test\");\n        return \"v1\";"));
        sleep(1500);
        swapRetry(p, t);
        for (int i = 0; i < 60 && g.lastCrash == null; i++) sleep(500);
        check("crash: report read, cause and own code frame found", g.lastCrash != null && p.logText().contains("game crashed:") && p.logText().contains("at " + probeClassOf(probe)),
                String.valueOf(g.lastCrash));
        Files.writeString(probe, src);
        before = p.embedder.gamePid();
        p.restart(testWorld(), null);
        waitForNewGame(p, before, gameLog, 300);
    }

    /** Actions and toolbar, the status bar numbers and the settings page (UI phase 1). */
    private static void uiChecks(SulfurPanel p) {
        var am = com.intellij.openapi.actionSystem.ActionManager.getInstance();
        boolean actions = java.util.stream.Stream.of("SulfurFrame.RunClient", "SulfurFrame.Restart", "SulfurFrame.Hotswap", "SulfurFrame.Datagen",
                "SulfurFrame.Inspect", "SulfurFrame.Worlds", "SulfurFrame.Options", "SulfurFrame.Toolbar", "SulfurFrame.Menu").allMatch(id -> am.getAction(id) != null);
        check("actions registered (toolbar, Tools | SulfurFrame)", actions, "SulfurFrame.* actions");
        if (project == null) return;
        String text = "";
        for (int i = 0; i < 30 && !(text = new SulfurStatusWidget(project).getText()).contains("fps"); i++) sleep(500);
        check("status bar shows the game's numbers", text.contains("fps") && text.contains("GB"), text);
        var page = new SulfurConfigurable(project);
        final boolean[] ok = new boolean[1];
        onEdt(() -> ok[0] = page.createComponent() != null && !page.isModified());
        check("settings page builds, nothing modified", ok[0], "Settings | Tools | SulfurFrame");
        if (Boolean.getBoolean("sulfurframe.autotest.jbrdownload")) jbrDownloadCheck();
        runConfigChecks(p);
    }

    /**
     * -Dsulfurframe.autotest.jbrdownload=true: the one-click JetBrains Runtime download, for real (about 230 MB) but into
     * a temporary folder instead of ~/.jdks, then deleted again.
     */
    private static void jbrDownloadCheck() {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("sf-jbr-test");
            long t0 = System.currentTimeMillis();
            var pkg = JbrDownloader.find(21);
            check("JBR download: feed has JetBrains Runtime 21 for " + JbrDownloader.os() + "/" + JbrDownloader.arch(), pkg != null, String.valueOf(pkg));
            if (pkg == null) return;
            Path home = JbrDownloader.install(pkg, dir, new com.intellij.openapi.progress.EmptyProgressIndicator());
            Path java = home.resolve("bin").resolve(dev.sulfurframe.idea.embed.GameView.linux() ? "java" : "java.exe");
            check("JBR download: downloaded, checksum ok, unpacked as a JetBrains Runtime 21", Jbr.isJbr(home, 21) && Files.isRegularFile(java)
                            && !Files.exists(dir.resolve(home.getFileName() + ".part")),
                    home.getFileName() + ", " + (System.currentTimeMillis() - t0) / 1000 + " s");
        } catch (Exception e) {
            check("JBR download", false, e.toString());
        } finally {
            if (dir != null) com.intellij.openapi.util.io.FileUtil.delete(dir.toFile());
        }
    }

    /** The save folder the test plays in: -Dsulfurframe.autotest.world, SFWorld when not given. */
    private static String testWorld() { return System.getProperty("sulfurframe.autotest.world", "SFWorld"); }

    /** UI phase 2: run configurations, the Run window, Rerun = save, quit and back into the same world. */
    private static void runConfigChecks(SulfurPanel p) {
        var rm = com.intellij.execution.RunManager.getInstance(project);
        int configs = rm.getConfigurationSettingsList(SulfurRunConfigurationType.get()).size();
        check("run configurations created for the clients", configs >= p.targetCount(), configs + " for " + p.targetCount() + " client(s)");
        com.intellij.execution.ui.RunContentDescriptor d = null;
        for (var x : com.intellij.execution.ui.RunContentManager.getInstance(project).getAllDescriptors())
            if (x.getProcessHandler() instanceof SulfurRunState.Session s && !s.isProcessTerminated()) d = x;
        check("the running game has a Run window tab", d != null, d == null ? "none" : d.getDisplayName());
        if (d == null || !p.embedder.attached()) return;
        Path gameLog = SharedInstance.runDir(p.selectedTarget()).resolve("logs/latest.log");
        long before = p.embedder.gamePid();
        final var desc = d;
        onEdt(() -> com.intellij.execution.runners.ExecutionUtil.restart(desc));   // the IDE's Rerun
        boolean back = waitForNewGame(p, before, gameLog, 300) && waitForLog(gameLog, "logged in with entity id", 120);
        check("Rerun: saved and quit, then back in the same world", back && p.logText().contains("stop: saving and quitting (world '" + testWorld() + "')"),
                "new game " + back + ", " + (p.logText().contains("stop: saving and quitting") ? "stopped gracefully" : "no graceful stop"));
    }

    /** Focus the game, move the real mouse over it, and see the player turn (yaw from the agent's look report). */
    private static void mouseLookCheck(SulfurPanel p, Path gameLog) throws Exception {
        var e = p.embedder;
        long game = e.gameHwnd();
        onEdt(() -> javax.swing.SwingUtilities.getWindowAncestor(p).toFront());
        sleep(500);
        e.focusGame();
        sleep(1500);
        int[] r = NativeWindows.screenRect(game);
        var robot = new Robot();
        // physical -> AWT coordinates (Robot uses the default transform of the main screen)
        double s = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform().getScaleX();
        int cx = (int) ((r[0] + r[2] / 2) / s), cy = (int) ((r[1] + r[3] / 2) / s);
        if (NativeWindows.keyboardFocus() != game) {
            // Windows may refuse to bring the IDE forward on its own; a real click is what a user does anyway.
            // Left edge, mid height: no button of the pause menu there (they are centered).
            int px = (int) (r[0] + r[2] * 0.04), py = r[1] + r[3] / 2;
            if (NativeWindows.windowAt(px, py) != game) { check("mouse look in the embedded game", false, "game not under the click point (covered?), no click sent"); return; }
            robot.mouseMove((int) ((r[0] + r[2] * 0.04) / s), cy);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            sleep(1500);
        }
        if (NativeWindows.keyboardFocus() != game) { check("mouse look in the embedded game", false, "game does not have keyboard focus"); return; }
        p.inspect();
        if (!"none".equals(p.lastLook.getOrDefault("screen", "none"))) {   // pause menu (opened on focus loss): close it
            robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE); robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
            sleep(1000);
            p.inspect();
        }
        String yaw0 = p.lastLook.get("yaw");
        robot.mouseMove(cx, cy);
        // GLFW re-centers a grabbed cursor every frame, so each move to the right is a fresh delta
        for (int i = 0; i < 20; i++) { robot.mouseMove(cx + 40, cy); sleep(60); }
        sleep(500);
        p.inspect();
        String yaw1 = p.lastLook.get("yaw");
        check("mouse look in the embedded game (player turns)", yaw0 != null && yaw1 != null && !yaw0.equals(yaw1),
                "yaw " + yaw0 + " -> " + yaw1 + (waitForLog(gameLog, "mouse grabbed while embedded", 1) ? " (grab applied by the agent)" : ""));
    }

    /** Swap, retrying while another swap (save-triggered) is busy; gives up after ~10 s (a compile error would loop). */
    private static void swapRetry(SulfurPanel p, dev.sulfurframe.idea.profile.Workspace.Target t) {
        for (int i = 0; i < 20 && !p.hotswapper.swap(t, p.embedder.gamePid(), agentJarOf(p)); i++) sleep(500);
    }

    /** Gradle recompiles dependents too, so more than the edited class may be swapped. */
    private static boolean redefinedAny(java.util.List<String> result) {
        return result.stream().anyMatch(s -> s.matches("redefined [1-9][0-9]*"));
    }

    private static Path agentJarOf(SulfurPanel p) {
        return p.agentJar();
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
