package dev.sulfurframe.idea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The running game for coding agents (an LLM editing the mod from a terminal): `sf status`, `sf wait`, `sf cmd ...`,
 * `sf shot` and so on (scripts in ~/.sulfurframe/bin, written at startup). A request is a file: the script writes
 * `<session>/req/<id>.req` (one line, the command), this thread takes it (renamed `.work`), runs it and writes
 * `<id>.res` (first line `#exit <code>`, then the answer). A session folder per project, with `session.txt` (project
 * folder, IDE pid) so a script finds the IDE whose project holds its working directory.
 */
final class Bridge {
    /** ~/.sulfurframe, or SULFURFRAME_HOME (the scripts read the same variable). */
    static final Path HOME = home();
    static final Path ROOT = HOME.resolve("bridge");
    static final Path BIN = HOME.resolve("bin");

    private static Path home() {
        String h = System.getenv("SULFURFRAME_HOME");
        return h != null && !h.isBlank() ? Path.of(h) : Path.of(System.getProperty("user.home"), ".sulfurframe");
    }

    private final GameHost p;
    private final Path dir;
    private volatile boolean stopped;

    private Bridge(GameHost p, Path dir) { this.p = p; this.dir = dir; }

    /** Start the bridge of a project's panel; null when it cannot (no project folder, or turned off). */
    static Bridge start(GameHost p, Path projectDir) {
        if (projectDir == null || !Settings.agentBridge()) return null;
        try {
            sweepStale();
            installScripts(p::log);
            String id = ProcessHandle.current().pid() + "-" + Integer.toHexString(projectDir.toAbsolutePath().normalize().toString().hashCode());
            Path dir = ROOT.resolve(id);
            Files.createDirectories(dir.resolve("req"));
            Files.writeString(dir.resolve("session.txt"), projectDir.toAbsolutePath().normalize() + "\n" + ProcessHandle.current().pid() + "\n", StandardCharsets.UTF_8);
            var b = new Bridge(p, dir);
            Thread.ofPlatform().daemon().name("sf-bridge").start(b::loop);
            return b;
        } catch (Exception e) {
            p.log("agent bridge unavailable: " + e);
            return null;
        }
    }

    void stop() {
        stopped = true;
        deleteTree(dir);
    }

    /** Without IntelliJ's FileUtil: the bridge also runs in the terminal host. */
    private static void deleteTree(Path d) {
        try (var w = Files.walk(d)) {
            for (Path f : w.sorted(java.util.Comparator.reverseOrder()).toList()) try { Files.deleteIfExists(f); } catch (IOException ignored) { }
        } catch (IOException ignored) { }
    }

    /** Sessions of IDEs that are gone (crashed, killed): a script must not wait on them. */
    private static void sweepStale() {
        if (!Files.isDirectory(ROOT)) return;
        try (var s = Files.list(ROOT)) {
            for (Path d : s.toList()) {
                try {
                    var lines = Files.readAllLines(d.resolve("session.txt"));
                    long pid = lines.size() > 1 ? Long.parseLong(lines.get(1).strip()) : -1;
                    if (pid > 0 && ProcessHandle.of(pid).isPresent()) continue;
                } catch (Exception ignored) { }
                deleteTree(d);
            }
        } catch (IOException ignored) { }
    }

    /** What the terminal host (`sf run`) loads: this plugin, Gson and IntelliJ's ASM. */
    static final String[] HOST_CLASSES = {"dev.sulfurframe.idea.Bridge", "com.google.gson.JsonParser", "org.jetbrains.org.objectweb.asm.ClassReader"};

    /**
     * The jar (or class folder) a class is loaded from. IntelliJ's plugin class loader gives no code source, so the
     * class file's own URL is read too: {@code jar:file:/...!/a/B.class} or {@code file:/.../a/B.class}.
     */
    static Path locationOf(String className) {
        try {
            var c = Class.forName(className, false, Bridge.class.getClassLoader());
            var src = c.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                Path p = Path.of(src.getLocation().toURI());
                if (Files.exists(p)) return p;
            }
            String res = className.replace('.', '/') + ".class";
            var cl = c.getClassLoader() != null ? c.getClassLoader() : ClassLoader.getSystemClassLoader();
            var url = cl.getResource(res);
            if (url == null) return null;
            String s = url.toString();
            Path p = null;
            if (s.startsWith("jar:")) {
                int bang = s.indexOf("!/");
                if (bang > 0) p = Path.of(new java.net.URI(s.substring(4, bang)));
            } else if (s.startsWith("file:") && s.endsWith(res)) {
                p = Path.of(new java.net.URI(s.substring(0, s.length() - res.length())));
            }
            return p != null && Files.exists(p) ? p : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The `sf` scripts (POSIX shell, Windows cmd + PowerShell), rewritten when the plugin's copy differs. */
    static void installScripts(java.util.function.Consumer<String> log) throws IOException {
        Files.createDirectories(BIN);
        // for `sf run` (no IDE): this Java (Java 25, the IDE's runtime) and this plugin's jar
        try {
            // the plugin's jar and the two IDE libraries the host needs (Gson, IntelliJ's ASM), as a class path
            var cp = new java.util.LinkedHashSet<String>();
            var missing = new ArrayList<String>();
            for (String c : HOST_CLASSES) {
                Path p = locationOf(c);
                if (p != null) cp.add(p.toString()); else missing.add(c);
            }
            var javaCmd = ProcessHandle.current().info().command().orElse(null);
            Path cpFile = BIN.resolve("host.classpath.txt");
            if (missing.isEmpty()) Files.writeString(cpFile, String.join(java.io.File.pathSeparator, cp) + "\n", StandardCharsets.UTF_8);
            else {
                // a class path written by an older install (or a moved folder) would start the wrong jar, or none
                Files.deleteIfExists(cpFile);
                log.accept("sf run unavailable: could not find the jar of " + String.join(", ", missing) + " (set SF_HOST_JAR to the class path)");
            }
            if (javaCmd != null && !(System.getProperty("os.name").startsWith("Windows") && javaCmd.toLowerCase().endsWith("idea64.exe")))
                Files.writeString(BIN.resolve("host.java.txt"), javaCmd + "\n", StandardCharsets.UTF_8);
            else Files.writeString(BIN.resolve("host.java.txt"), Path.of(System.getProperty("java.home"), "bin", "java") + "\n", StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
        for (String name : new String[]{"sf", "sf.cmd", "sf.ps1"}) {
            try (var in = Bridge.class.getResourceAsStream("/bridge/" + name)) {
                if (in == null) continue;
                byte[] b = in.readAllBytes();
                Path f = BIN.resolve(name);
                if (Files.exists(f) && java.util.Arrays.equals(Files.readAllBytes(f), b)) continue;
                Files.write(f, b);
                if (name.equals("sf")) f.toFile().setExecutable(true, false);
            }
        }
    }

    private void loop() {
        Path req = dir.resolve("req");
        long beat = 0;
        while (!stopped) {
            try {
                // alive: the scripts skip a session whose file is older than a minute (an IDE that died keeps its folder)
                if (System.currentTimeMillis() - beat > 10_000) { Files.writeString(dir.resolve("alive"), String.valueOf(System.currentTimeMillis())); beat = System.currentTimeMillis(); }
                try (var s = Files.newDirectoryStream(req, "*.req")) {
                    for (Path f : s) {
                        String id = f.getFileName().toString().replaceFirst("\\.req$", "");
                        Path work = req.resolve(id + ".work");
                        try { Files.move(f, work, StandardCopyOption.ATOMIC_MOVE); } catch (IOException e) { continue; }   // another taker
                        String line = Files.readString(work, StandardCharsets.UTF_8).strip();
                        Thread.ofPlatform().daemon().name("sf-bridge-" + id).start(() -> answer(req, id, work, line));
                    }
                }
                Thread.sleep(150);
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void answer(Path req, String id, Path work, String line) {
        int code = 0;
        String out;
        try {
            out = run(line);
        } catch (Failure f) {
            code = 1;
            out = f.getMessage();
        } catch (Exception e) {
            code = 2;
            out = "error: " + e;
        }
        try {
            Path tmp = req.resolve(id + ".res.part");
            Files.writeString(tmp, "#exit " + code + "\n" + out + (out.endsWith("\n") ? "" : "\n"), StandardCharsets.UTF_8);
            Files.move(tmp, req.resolve(id + ".res"), StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(work);
        } catch (IOException ignored) { }
    }

    /** An answer that says the command did not do what was asked (exit code 1). */
    static final class Failure extends Exception { Failure(String m) { super(m); } }

    static final String HELP = """
            sf: the Minecraft game SulfurFrame runs for this project (IntelliJ with SulfurFrame, or sf run)
              sf run [--world W]     start the game without IntelliJ, in this terminal (see sf run --help)
              sf status              game, world, what the last swap / reload / run did, the panel log's last lines
              sf wait [seconds]      wait for the swap your last edit starts (or the one running) to finish; its log
              sf swap                compile and swap now (what a save does), then like wait
              sf cmd <command>       run a command in the open world (no leading /), print what it said
              sf look                what the crosshair is on (block or entity, class, NBT)
              sf stats               fps, tick time, memory
              sf shot                screenshot of the game, prints the PNG's path
              sf log [n] [text]      last n lines of the game log (default 40), only lines containing text if given
              sf restart             save, quit and start again into the same world (for what a swap cannot apply)
              sf quit                save and quit the game (sf run ends with it)
              sf test [names|all]    SulfurFrame's own runtime tests on this game (its demo mod only); sf test list
              sf feedback <text>     SulfurFrame did something wrong or confusing: saved with the current state for its
                                     developers (~/.sulfurframe/feedback/inbox; sf relay hands it on)
            Exit code: 0 done, 1 the game said no (or no game / no world), 2 error, 3 no IDE answered.
            """;

    String run(String line) throws Exception {
        String[] w = line.split("\\s+", 2);
        String cmd = w[0], arg = w.length > 1 ? w[1].strip() : "";
        return switch (cmd) {
            case "", "help" -> HELP;
            case "status" -> status();
            case "wait" -> waitForWork(arg.isEmpty() ? 120 : Integer.parseInt(arg), 15, null);
            case "swap" -> waitForWork(300, 15, p::hotswapAsync);
            case "cmd" -> command(arg);
            case "look" -> look();
            case "stats" -> stats();
            case "shot" -> shot();
            case "log" -> gameLog(arg);
            case "restart" -> restart();
            case "quit" -> quit();
            case "feedback" -> feedback(arg);
            case "test" -> test(arg);
            default -> throw new Failure("unknown command '" + cmd + "'\n" + HELP);
        };
    }

    private long livePid() throws Failure {
        long pid = p.gamePid();
        if (pid <= 0 || ProcessHandle.of(pid).isEmpty()) throw new Failure("no game running (start it with Run client in the IDE)");
        return pid;
    }

    private String status() {
        var out = new StringBuilder();
        var t = p.selectedTarget();
        long pid = p.gamePid();
        boolean alive = pid > 0 && ProcessHandle.of(pid).isPresent();
        out.append("project: ").append(t == null ? "-" : t.where() + " (" + t.profile() + ")").append('\n');
        String world = "";
        try { world = alive ? p.openWorld() : ""; } catch (Exception ignored) { }
        out.append("game: ").append(alive ? "running, pid " + pid + (world.isEmpty() ? ", no world open" : ", world '" + world + "'") : "not running").append('\n');
        out.append("busy: ").append(p.busy() > 0 ? "yes (a swap, reload or restart is running)" : "no").append('\n');
        var h = p.hotswapper();
        if (!h.lastResult.isEmpty()) out.append("last swap: ").append(String.join(" | ", h.lastResult)).append('\n');
        if (!h.lastRegistration.isEmpty()) out.append("last init run: ").append(String.join(" | ", h.lastRegistration)).append('\n');
        if (!h.lastStartupChanges.isEmpty()) out.append("restart needed for: ").append(String.join(", ", h.lastStartupChanges)).append('\n');
        out.append("log:\n");
        for (String l : p.journalSince(Math.max(0, p.journalSeq() - 15))) out.append("  ").append(l).append('\n');
        return out.toString();
    }

    /**
     * Wait for the work an edit starts (the source watcher waits a moment after the last save) or the work running now:
     * until something started and everything is quiet again (no work, no new log line for a second), then the log lines
     * written meanwhile. {@code kick} starts the work itself.
     */
    /** When the latest work an sf wait / sf swap reported ended. */
    private volatile long reportedEnd;

    private String waitForWork(int seconds, int startSeconds, Runnable kick) throws Exception {
        long from = p.journalSeq(), activity = p.activity();
        long end = System.currentTimeMillis() + seconds * 1000L, startBy = System.currentTimeMillis() + startSeconds * 1000L;
        boolean started = p.busy() > 0;
        // a swap faster than the shell that started sf wait: it ended just before the request came, so that is the one
        // (not one an earlier sf wait has already reported)
        long ended = p.lastWorkEndMillis();
        if (kick == null && !started && ended > reportedEnd && System.currentTimeMillis() - ended < 5000
                && !p.journalSince(p.lastWorkStartSeq()).stream().allMatch(l -> l.endsWith("hotswap: no class changed"))) { started = true; from = p.lastWorkStartSeq(); }
        if (kick != null) { livePid(); kick.run(); }
        long quietSince = System.currentTimeMillis(), lastSeq = from;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(200);
            long seq = p.journalSeq();
            if (seq != lastSeq) { lastSeq = seq; quietSince = System.currentTimeMillis(); }
            if (p.busy() > 0 || p.activity() != activity) started = true;
            if (!started && System.currentTimeMillis() > startBy) break;
            if (started && p.busy() == 0 && System.currentTimeMillis() - quietSince > 1000) {
                // a swap with nothing in it (the IDE noticing a change the watcher already swapped): not what is waited for
                if (kick == null && p.journalSince(from).stream().allMatch(l -> l.endsWith("hotswap: no class changed"))) {
                    reportedEnd = Math.max(reportedEnd, p.lastWorkEndMillis());
                    from = p.journalSeq(); activity = p.activity(); started = false;
                    continue;
                }
                break;
            }
        }
        var lines = p.journalSince(from);
        if (!started) return "nothing to wait for: no swap, reload or restart started in " + startSeconds + " s\n" + status();
        if (p.busy() > 0) throw new Failure("still running after " + seconds + " s\n" + String.join("\n", lines));
        String text = String.join("\n", lines);
        boolean failed = text.contains("restart needed") || text.contains("failed") || text.contains("error") || text.contains("compile errors");
        reportedEnd = Math.max(reportedEnd, p.lastWorkEndMillis());
        return (failed ? "done, with problems (see below)\n" : "done\n") + text;
    }

    private String command(String command) throws Exception {
        if (command.isEmpty()) throw new Failure("usage: sf cmd <command>");
        if (command.startsWith("/")) command = command.substring(1);
        long pid = livePid();
        Path done = tmp("cmd-" + pid + "-" + System.nanoTime() + ".txt");
        Hotswapper.loadAgent(pid, p.agentJar(), "cmd=" + WorldSnapshots.b64(command) + ",done=" + done);
        var lines = awaitFile(done, 15);
        String first = lines.isEmpty() ? "" : lines.get(0).strip(), said = lines.size() > 1 ? lines.get(1) : "";
        Files.deleteIfExists(done);
        if (!first.equals("done")) throw new Failure(first + (said.isEmpty() ? "" : ": " + said));
        // the server runs any line and answers a bad one with an error message, not a failure
        if (said.contains("Unknown or incomplete command") || said.contains("<--[HERE]") || said.startsWith("Incorrect argument"))
            throw new Failure(said.replace(" | ", "\n"));
        return said.isEmpty() ? "done (the command said nothing)" : said.replace(" | ", "\n");
    }

    private String look() throws Exception {
        livePid();
        if (!p.inspect()) throw new Failure("no answer from the game");
        var out = new StringBuilder();
        p.lastLook().forEach((k, v) -> out.append(k).append('=').append(v).append('\n'));
        return out.toString();
    }

    private String stats() throws Exception {
        long pid = livePid();
        var m = p.stats();
        if (m.isEmpty()) { p.startStats(pid); for (int i = 0; i < 30 && (m = p.stats()).isEmpty(); i++) Thread.sleep(100); }
        if (m.isEmpty()) throw new Failure("no numbers from the game");
        var out = new StringBuilder();
        m.forEach((k, v) -> out.append(k).append('=').append(v).append('\n'));
        return out.toString();
    }

    private String shot() throws Exception {
        long pid = livePid();
        Path png = dir.resolve("shot-" + System.currentTimeMillis() + ".png");
        Path done = tmp("shot-" + pid + "-" + System.nanoTime() + ".txt");
        Hotswapper.loadAgent(pid, p.agentJar(), "shot=" + png + ",done=" + done);
        var lines = awaitFile(done, 15);
        Files.deleteIfExists(done);
        String first = lines.isEmpty() ? "no answer from the game" : lines.get(0).strip();
        if (!first.equals("done") || !Files.exists(png)) throw new Failure("screenshot failed: " + first);
        try (var s = Files.newDirectoryStream(dir, "shot-*.png")) {   // the newest 5 are kept
            var old = new ArrayList<Path>();
            s.forEach(old::add);
            old.sort(java.util.Comparator.comparing(Path::toString));
            for (int i = 0; i < old.size() - 5; i++) Files.deleteIfExists(old.get(i));
        }
        return png.toString();
    }

    private String gameLog(String arg) throws Exception {
        var t = p.selectedTarget();
        if (t == null) throw new Failure("no client selected");
        String[] a = arg.isEmpty() ? new String[0] : arg.split("\\s+", 2);
        int n = 40;
        String filter = null;
        if (a.length > 0) {
            try { n = Integer.parseInt(a[0]); if (a.length > 1) filter = a[1]; }
            catch (NumberFormatException e) { filter = arg; }
        }
        Path log = SharedInstance.runDir(t).resolve("logs/latest.log");
        if (!Files.exists(log)) throw new Failure("no game log yet: " + log);
        List<String> all = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
        final String f = filter;
        var picked = f == null ? all : all.stream().filter(l -> l.contains(f)).toList();
        return String.join("\n", picked.subList(Math.max(0, picked.size() - n), picked.size()));
    }

    private String quit() throws Exception {
        long pid = livePid();
        Hotswapper.loadAgent(pid, p.agentJar(), "quit");
        var game = ProcessHandle.of(pid).orElse(null);
        if (game != null) try { game.onExit().get(90, java.util.concurrent.TimeUnit.SECONDS); } catch (java.util.concurrent.TimeoutException e) { throw new Failure("the game did not quit in 90 s"); }
        return "the game saved and quit";
    }

    private String restart() throws Exception {
        long before = livePid();
        p.restartAsync();
        long end = System.currentTimeMillis() + 5 * 60_000;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(500);
            long now = p.gamePid();
            if (now > 0 && now != before && p.gameAttached() && ProcessHandle.of(before).isEmpty()) {
                for (int i = 0; i < 240 && p.openWorld().isEmpty(); i++) Thread.sleep(500);   // back in the world
                return "restarted: game pid " + now + ", world '" + p.openWorld() + "'";
            }
        }
        throw new Failure("the game did not come back in 5 minutes\n" + status());
    }

    private String test(String arg) throws Exception {
        if (arg.equals("list")) {
            var out = new StringBuilder("tests (default: " + String.join(" ", AutoTest.SF_QUICK) + "; all = every one but hierarchy and restart):\n");
            AutoTest.SF_TESTS.forEach((k, v) -> out.append(String.format("  %-12s %s%n", k, v)));
            return out.toString();
        }
        livePid();
        List<String> names = arg.isEmpty() ? AutoTest.SF_QUICK
                : arg.equals("all") ? AutoTest.SF_TESTS.keySet().stream().filter(n -> !n.equals("hierarchy") && !n.equals("restart")).toList()
                : List.of(arg.split("[\\s,]+"));
        String out = p.runTests(names);
        if (!out.endsWith("failures: 0")) throw new Failure(out);
        return out;
    }

    static final Path FEEDBACK = HOME.resolve("feedback").resolve("inbox");

    /** What the agent says went wrong, with the state around it (status, swap results, both logs' last lines). */
    private String feedback(String text) throws Exception {
        if (text.isEmpty()) throw new Failure("usage: sf feedback <what went wrong or was confusing>");
        Files.createDirectories(FEEDBACK);
        var t = p.selectedTarget();
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String proj = t == null ? "unknown" : t.buildRoot().getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        Path f = FEEDBACK.resolve(stamp + "-" + proj + ".md");
        var out = new StringBuilder("# SulfurFrame feedback " + stamp + "\n\n");
        out.append("## What the agent reported\n\n").append(text).append("\n\n");
        String version = "?";
        try (var in = Bridge.class.getResourceAsStream("/META-INF/plugin.xml")) {   // this jar's own: works without the IDE too
            if (in != null) {
                var m = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (m.find()) version = m.group(1).strip();
            }
        } catch (Throwable ignored) { }
        out.append("## State\n\nplugin ").append(version).append(", IDE ").append(System.getProperty("idea.version", "?")).append(", ")
                .append(System.getProperty("os.name")).append("\n\n```\n").append(status()).append("```\n\n");
        try { out.append("## Game log (last 60 lines)\n\n```\n").append(gameLog("60")).append("\n```\n"); } catch (Exception ignored) { }
        Files.writeString(f, out.toString(), StandardCharsets.UTF_8);
        p.log("feedback saved for SulfurFrame's developers: " + f);
        return "saved: " + f;
    }

    private static Path tmp(String name) throws IOException {
        Path d = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe");
        Files.createDirectories(d);
        return d.resolve(name);
    }

    private static List<String> awaitFile(Path f, int seconds) throws Exception {
        for (int i = 0; i < seconds * 10 && !Files.exists(f); i++) Thread.sleep(100);
        if (!Files.exists(f)) return List.of();
        Thread.sleep(50);
        return Files.readAllLines(f, StandardCharsets.UTF_8);
    }
}
