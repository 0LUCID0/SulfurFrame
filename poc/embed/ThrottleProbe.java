import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What does it cost to keep an embedded Minecraft around, per state, and what can the host reduce WITHOUT a mod?
 *
 * Measures the game process: CPU (% of one core), GPU 3D engine utilisation (perf counters), working set,
 * and Java heap committed/used (jcmd GC.heap_info).
 *
 * States:
 *   PLAYING  game focused, in-world, menu closed                       (baseline)
 *   CODING   editor focused; vanilla pauseOnLostFocus opens the pause menu (integrated server stops ticking)
 *   HIDDEN   game window minimized by the host (vanilla caps iconified windows at 10 fps)
 *   +GC      HIDDEN, after the host asks the game JVM for a GC via jcmd (G1 then returns free heap to the OS)
 *
 * usage: java EmbedHost.java --throttle-probe --attach <pid>     (game should already be in a world)
 * Uses the keyboard once (ESC to close the pause menu); the game grabs the mouse while PLAYING is measured.
 */
final class ThrottleProbe {
    record Sample(String state, double cpuPct, double gpuPct, long workingSetMb, long heapCommittedMb, long heapUsedMb) {
        @Override public String toString() {
            return String.format("%-14s CPU %6.1f%%   GPU3D %5.1f%%   WS %5d MB   heap %5d/%5d MB (used/committed)",
                    state, cpuPct, gpuPct, workingSetMb, heapUsedMb, heapCommittedMb);
        }
    }

    private final EmbedHost host;
    private final long pid;
    private final List<Sample> samples = new ArrayList<>();
    static final int MEASURE_S = 8, SETTLE_MS = 4000;

    ThrottleProbe(EmbedHost host, long pid) { this.host = host; this.pid = pid; }

    int run() throws Exception {
        host.attachPid(pid, EmbedHost.Mode.REPARENT);
        if (host.target == 0) { System.out.println("no window"); return 1; }
        EmbedHost.onEdt(host.frame::toFront);
        EmbedHost.sleep(3000);

        // start from the editor: vanilla opens the pause menu on focus loss, so nothing grabs the mouse yet
        focusEditor();
        EmbedHost.sleep(SETTLE_MS);

        // PLAYING: click "Back to Game" in the pause menu. The click both focuses the game (host click-to-focus)
        // and closes the menu; ESC alone did not close it reliably. The game then grabs the mouse.
        // SDL drops the click that gives a window focus (SDL_HINT_MOUSE_FOCUS_CLICKTHROUGH defaults to off),
        // so the first click only focuses the game and the second one presses the button
        var r = new Robot();
        host.log("[probe] before clicks: focus=" + Win32.hex(Win32.foregroundFocus()) + " clip=" + Win32.clipCursor());
        for (int i = 0; i < 3 && !grabbed(); i++) {
            clickViewport(r, 0.5, 155.0 / 480);
            EmbedHost.sleep(700);
            host.log("[probe] click " + (i + 1) + ": focus=" + Win32.hex(Win32.foregroundFocus()) + " (game " + Win32.hex(host.target)
                    + ") clip=" + Win32.clipCursor() + " grabbed=" + grabbed());
        }
        EmbedHost.sleep(SETTLE_MS);
        snapshot("playing");
        measure("PLAYING");

        // keyboard reaches the game? E opens the inventory, ESC closes it (SDL sends these with windowID 0)
        requireGameFocus();
        r.keyPress(KeyEvent.VK_E); r.keyRelease(KeyEvent.VK_E);
        EmbedHost.sleep(800);
        snapshot("inventory");
        requireGameFocus();
        r.keyPress(KeyEvent.VK_ESCAPE); r.keyRelease(KeyEvent.VK_ESCAPE);
        EmbedHost.sleep(800);

        // CODING: editor focused -> game loses focus -> vanilla pause menu (mouse released)
        focusEditor();
        host.throttler.onEditorFocused();
        EmbedHost.sleep(SETTLE_MS);
        snapshot("coding");
        measure("CODING");

        // HIDDEN through the throttler: minimize now, reclaim memory after the delay
        host.throttler.reclaimDelayOverrideMs = 14_000;   // leave room to measure "hidden, not yet reclaimed"
        EmbedHost.onEdt(() -> host.setViewportHidden(true));
        EmbedHost.sleep(SETTLE_MS);
        host.log("[probe] iconic=" + Win32.isIconic(host.target) + " state=" + host.throttler.state());
        measure("HIDDEN");
        SelfTest.waitFor(() -> host.throttler.reclaimedAtNanos != 0, 30_000);
        EmbedHost.sleep(2000);
        measure("HIDDEN+RECLAIM");

        long r0 = System.nanoTime();
        EmbedHost.onEdt(() -> host.setViewportHidden(false));
        boolean back = SelfTest.waitFor(() -> Win32.isResponsive(host.target, 100) && !Win32.isIconic(host.target), 10_000);
        host.log("[probe] restored, responsive=" + back + " after " + (System.nanoTime() - r0) / 1_000_000 + " ms, flags: "
                + jcmd("VM.flags -all").lines().filter(l -> l.contains("HeapFreeRatio")).map(l -> l.replaceAll("\\s+", " ").strip())
                        .reduce((x, y) -> x + " | " + y).orElse("?"));
        EmbedHost.sleep(SETTLE_MS);
        snapshot("restored");
        measure("RESTORED");

        System.out.println("\n===== SulfurFrame throttle probe (pid " + pid + ") =====");
        samples.forEach(System.out::println);
        EmbedHost.onEdt(host::detach);
        return 0;
    }

    /** Screenshot of the viewport, to check which state the game is really in. Dir: SF_SNAP_DIR or temp. */
    void snapshot(String name) { snapshotAs("sf-throttle-" + name); }

    void snapshotAs(String file) {
        try {
            var dir = Path.of(System.getenv().getOrDefault("SF_SNAP_DIR", System.getProperty("java.io.tmpdir")));
            var b = EmbedHost.onEdt(() -> { var l = host.viewport.getLocationOnScreen(); return new Rectangle(l.x, l.y, host.viewport.getWidth(), host.viewport.getHeight()); });
            var f = dir.resolve(file + ".png").toFile();
            javax.imageio.ImageIO.write(new Robot().createScreenCapture(b), "png", f);
            host.log("[probe] snapshot " + f);
        } catch (Exception e) {
            host.log("[probe] snapshot failed: " + e);
        }
    }

    // ------------------------------------------------------------------ focus

    void focusEditor() {
        EmbedHost.onEdt(() -> {
            host.frame.toFront();
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
            host.editor.requestFocus(FocusEvent.Cause.MOUSE_EVENT);
        });
    }

    /** In-game the mouse is grabbed: the cursor gets clipped to (a part of) the game window. */
    boolean grabbed() {
        var c = Win32.clipCursor();
        var g = Win32.windowRect(host.target);
        return c != null && g != null && c.left() >= g.left() && c.right() <= g.right() && c.top() >= g.top() && c.bottom() <= g.bottom();
    }

    /** Left click at a point given as fractions of the viewport. Aborts if the foreground is another app. */
    void clickViewport(Robot r, double fx, double fy) {
        long fg = Win32.foregroundWindow();
        // toFront() when we are already in front would pull keyboard focus back from the game to the frame
        if (fg != host.frameHwnd()) {
            EmbedHost.onEdt(host.frame::toFront);
            EmbedHost.sleep(200);
            fg = Win32.foregroundWindow();
        }
        long fgPid = fg == 0 ? 0 : Win32.processIdOf(fg);
        if (fgPid != ProcessHandle.current().pid() && fgPid != pid) throw new IllegalStateException("foreground is another app, not clicking");
        var p = EmbedHost.onEdt(() -> { var l = host.viewport.getLocationOnScreen();
            return new Point(l.x + (int) (host.viewport.getWidth() * fx), l.y + (int) (host.viewport.getHeight() * fy)); });
        r.mouseMove(p.x, p.y);
        r.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        r.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
    }

    void requireGameFocus() {
        long f = Win32.foregroundFocus();
        if (f != host.target) throw new IllegalStateException("game does not have keyboard focus (" + Win32.hex(f) + "), not sending ESC");
    }

    // ------------------------------------------------------------------ measurement

    void measure(String state) throws Exception {
        var proc = ProcessHandle.of(pid).orElseThrow();
        // GPU sampling runs in PowerShell for the same window as the CPU delta
        var gpu = startGpuSampling();
        Duration c0 = proc.info().totalCpuDuration().orElse(Duration.ZERO);
        long t0 = System.nanoTime();
        String gpuOut = new String(gpu.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        gpu.waitFor();
        Duration c1 = proc.info().totalCpuDuration().orElse(Duration.ZERO);
        double wallMs = (System.nanoTime() - t0) / 1e6;
        double cpuPct = (c1.toNanos() - c0.toNanos()) / 1e6 / wallMs * 100.0;
        double gpuPct = parseDouble(gpuOut, "GPU=");
        long ws = (long) parseDouble(gpuOut, "WS=");
        long[] heap = heapInfo();
        var s = new Sample(state, cpuPct, gpuPct, ws, heap[0], heap[1]);
        samples.add(s);
        host.log("[probe] " + s);
    }

    Process startGpuSampling() throws IOException {
        String ps = String.join("; ",
                "$ErrorActionPreference='SilentlyContinue'",
                "$s = Get-Counter -Counter '\\GPU Engine(pid_" + pid + "_*engtype_3D)\\Utilization Percentage' -SampleInterval 1 -MaxSamples " + MEASURE_S,
                "$per = $s | ForEach-Object { ($_.CounterSamples | Measure-Object -Property CookedValue -Sum).Sum }",
                "$avg = ($per | Measure-Object -Average).Average",
                "if ($null -eq $avg) { $avg = -1 }",
                "$ws = [math]::Round((Get-Process -Id " + pid + ").WorkingSet64 / 1MB)",
                "Write-Output ('GPU=' + [math]::Round($avg, 1) + ' WS=' + $ws)");
        return new ProcessBuilder("powershell", "-NoProfile", "-Command", ps).redirectErrorStream(true).start();
    }

    /** {committedMb, usedMb} from "garbage-first heap   total reserved ..., committed X K, used Y K". */
    long[] heapInfo() throws Exception {
        String out = jcmd("GC.heap_info");
        var m = Pattern.compile("(?:committed|total) (\\d+)K, used (\\d+)K").matcher(out);   // JDK 25 / JDK 21 wording
        if (m.find()) return new long[]{Long.parseLong(m.group(1)) / 1024, Long.parseLong(m.group(2)) / 1024};
        // ZGC style: "ZHeap used 123M, capacity 456M"
        var z = Pattern.compile("used (\\d+)M, capacity (\\d+)M").matcher(out);
        if (z.find()) return new long[]{Long.parseLong(z.group(2)), Long.parseLong(z.group(1))};
        return new long[]{-1, -1};
    }

    String jcmd(String cmd) throws Exception {
        var java = Path.of(EmbedHost.javaExe());
        var jcmd = java.resolveSibling(java.getFileName().toString().replace("java", "jcmd")).toString();
        var p = new ProcessBuilder(jcmd, Long.toString(pid), cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    static double parseDouble(String s, String key) {
        var m = Pattern.compile(Pattern.quote(key) + "(-?[0-9.,]+)").matcher(s);
        return m.find() ? Double.parseDouble(m.group(1).replace(',', '.')) : -1;
    }
}
