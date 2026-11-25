package dev.sulfurframe.idea;

import dev.sulfurframe.idea.profile.Workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SulfurFrame without IntelliJ (`sf run` in a terminal): starts the project's client like the IDE does (JetBrains
 * Runtime, the agent, Mixin's hotswap agent, quick play into a world), watches the source folders and swaps what
 * changed, and answers the `sf` commands (Bridge). The game gets its own window (no embedding). Uses only the parts
 * of the plugin that do not need the IDE: Hotswapper, GameLauncher, QuickRelaunch, SourceWatcher and the agent.
 *
 *   java -cp <plugin jar> dev.sulfurframe.idea.HeadlessHost run [--target <where>] [--world <save>] [project dir]
 */
public final class HeadlessHost implements GameHost {
    private final Path projectDir;
    private final Workspace.Target target;
    private final Hotswapper hotswapper;
    private final GameLauncher launcher;
    private final Path agentJar;
    private volatile long pid;
    private volatile boolean restarting, stopping;
    private final ArrayDeque<String> journal = new ArrayDeque<>();
    private long journalSeq;
    private final AtomicInteger busy = new AtomicInteger();
    private final AtomicLong activity = new AtomicLong();
    private volatile Map<String, String> lastLook = Map.of();
    private volatile Path statsFile;
    private final Set<Path> pending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("sf-debounce").unstarted(r));
    private volatile ScheduledFuture<?> scheduled;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile int exitCode;

    private HeadlessHost(Path projectDir, Workspace.Target target) {
        this.projectDir = projectDir;
        this.target = target;
        this.hotswapper = new Hotswapper(this::log, Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe"));
        this.launcher = new GameLauncher(this::log);
        this.launcher.headless = true;
        this.agentJar = extractAgent();
        launcher.setAgentJar(agentJar);
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("sulfurframe.mute") == null) System.setProperty("sulfurframe.mute", String.valueOf("1".equals(System.getenv("SF_MUTE"))));
        var a = new ArrayList<>(List.of(args));
        if (!a.isEmpty() && a.get(0).equals("relay")) System.exit(Relay.main(a.subList(1, a.size())));
        if (a.isEmpty() || !a.get(0).equals("run")) { System.out.println(usage()); System.exit(a.isEmpty() ? 2 : 0); }
        a.remove(0);
        String want = null, world = "";
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < a.size(); i++) {
            switch (a.get(i)) {
                case "--target" -> want = a.get(++i);
                case "--world" -> world = a.get(++i);
                case "-h", "--help" -> { System.out.println(usage()); return; }
                default -> dir = Path.of(a.get(i)).toAbsolutePath();
            }
        }
        dir = dir.normalize();
        var found = Workspace.scan(dir);
        if (found.isEmpty()) { System.err.println("sf run: no Minecraft mod project found in " + dir); System.exit(1); }
        Workspace.Target t = found.get(0);
        if (want != null) {
            final String w = want;
            t = found.stream().filter(x -> x.where().equals(w) || x.where().endsWith(w) || x.projectPath().equals(w)).findFirst().orElse(null);
            if (t == null) {
                System.err.println("sf run: no client '" + want + "'; found:");
                for (var x : found) System.err.println("  " + x.where() + "  [" + x.runTask() + "]");
                System.exit(1);
            }
        }
        var host = new HeadlessHost(dir, t);
        System.exit(host.run(world));
    }

    static String usage() {
        return """
                sf run [--target <where>] [--world <save>] [project dir]
                  Starts the mod's client without IntelliJ (JetBrains Runtime from ~/.jdks when there is one), swaps
                  saved changes into it and answers the other sf commands. Ctrl+C or closing the game ends it.
                  --target  which client when the folder has several (as listed when it is wrong)
                  --world   save folder to start in (quick play, 1.20+)""";
    }

    private int run(String world) throws Exception {
        log("sf run: " + target.where() + " (" + target.profile() + "), project " + projectDir);
        var bridge = Bridge.start(this, projectDir);
        if (bridge == null) log("the sf commands are not available (agent bridge turned off)");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopping = true;
            if (bridge != null) bridge.stop();
            long p = pid;
            if (p > 0 && ProcessHandle.of(p).isPresent()) {   // Ctrl+C: the game saves and quits too
                try { Hotswapper.loadAgent(p, agentJar, "quit"); } catch (Exception ignored) { }
            }
        }));
        if (!world.isEmpty()) {   // quick play does not create a missing world: a flat test world, as the IDE's self-test makes
            Path saves = SharedInstance.runDir(target).resolve("saves");
            String mc = target.profile().minecraftVersion();
            if (!Files.isDirectory(saves.resolve(world)) && TestWorld.supported(mc)) {
                Files.createDirectories(saves);
                TestWorld.create(saves, world, mc);
                log("world '" + world + "' created (flat test world)");
            }
        }
        var watcher = SourceWatcher.start(target.buildRoot(), this::changed);
        log(watcher == null ? "no src folders to watch: use sf swap after an edit" : "watching " + watcher.rootCount() + " src folder(s) for changes");
        launch(world);
        done.await();
        if (watcher != null) watcher.close();
        return exitCode;
    }

    private void launch(String world) {
        var args = !world.isEmpty() && quickPlay(target.profile().minecraftVersion()) ? List.of("--quickPlaySingleplayer", world) : List.<String>of();
        java.util.function.BiConsumer<Long, Long> onWindow = (p, hwnd) -> {
            pid = p;
            hotswapper.snapshot(target);
            log("game window up (pid " + p + ")");
        };
        Runnable onEnd = () -> {
            exitCode = launcher.exitCode();
            if (!restarting) {
                log("the game ended (exit " + exitCode + ")");
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }   // answers in flight (sf quit) are written
                done.countDown();
            }
        };
        var spec = launcher.cached(target);
        String why = spec == null ? "" : QuickRelaunch.whyNot(spec, target);
        if (why == null) why = hotswapper.prepareRelaunch(target, spec.capturedAt());
        if (spec != null && why == null && launcher.relaunch(spec, target, args, onWindow, onEnd)) return;
        if (spec != null) { log("starting through Gradle: " + (why != null ? why : "the direct start failed")); launcher.forget(target); }
        hotswapper.clearAwOverlay(target);
        launcher.runClient(target, args, onWindow, onEnd);
    }

    // ------------------------------------------------------------------ changes on disk

    private void changed(Path f) {
        pending.add(f);
        var s = scheduled;
        if (s != null) s.cancel(false);
        scheduled = debounce.schedule(this::flush, 400, TimeUnit.MILLISECONDS);
    }

    private void flush() {
        var files = new ArrayList<>(pending);
        pending.removeAll(files);
        if (files.isEmpty() || pid <= 0) return;
        var t = target;
        var mixinConfigs = files.stream().filter(f -> f.getFileName().toString().endsWith("mixins.json")).toList();
        var wideners = files.stream().filter(f -> Hotswapper.isWidener(f.getFileName().toString())).toList();
        var startup = files.stream().filter(f -> needsRestart(f) && !mixinConfigs.contains(f) && !wideners.contains(f)).toList();
        if (!startup.isEmpty()) restartNeeded(String.join(", ", startup.stream().map(f -> f.getFileName().toString()).distinct().toList()) + " changed (read only when the game starts)");
        var code = files.stream().filter(f -> !needsRestart(f) && (f.toString().endsWith(".java") || f.toString().endsWith(".kt"))).toList();
        Path dir = t.dir().toAbsolutePath().normalize();
        var res = files.stream().filter(f -> f.toAbsolutePath().normalize().startsWith(dir) && !needsRestart(f) && !code.contains(f)
                && f.toString().replace('\\', '/').contains("/resources/")).toList();
        long p = pid;
        if (!mixinConfigs.isEmpty()) work("sf-mixins", () -> { for (Path f : mixinConfigs) { String why = hotswapper.addMixins(t, p, agentJar, f); if (why != null) restartNeeded(why); } });
        if (!wideners.isEmpty()) work("sf-widen", () -> {
            for (Path f : wideners) { String why = hotswapper.widenLive(t, p, agentJar, f); if (why != null) restartNeeded(why); }
            if (!code.isEmpty()) hotswapAsync();
        });
        else if (!code.isEmpty()) hotswapAsync();
        if (!res.isEmpty()) work("sf-resources", () -> hotswapper.reloadResources(t, p, agentJar, res));
    }

    private void restartNeeded(String why) { log("restart needed: " + why + " (sf restart)"); }

    public void hotswapAsync() {
        long p = pid;
        work("sf-hotswap", () -> {
            hotswapper.swap(target, p, agentJar);
            var restart = hotswapper.lastResult.stream().filter(s -> s.startsWith("restart needed")).toList();
            if (!restart.isEmpty()) restartNeeded(restart.size() + " class(es) changed in a way hotswap cannot apply");
            else if (!hotswapper.lastStartupChanges.isEmpty()) restartNeeded("startup code changed (runs only once, at launch): " + String.join(", ", hotswapper.lastStartupChanges));
        });
    }

    public void restartAsync() { work("sf-restart", this::restart); }

    private void restart() {
        if (restarting) return;
        restarting = true;
        try {
            long p = pid;
            String world = "";
            var game = p > 0 ? ProcessHandle.of(p).orElse(null) : null;
            if (game != null && game.isAlive()) {
                Path wf = tmp("world-" + p + ".txt");
                Files.deleteIfExists(wf);
                Hotswapper.loadAgent(p, agentJar, "world=" + wf + ",quit");
                for (int i = 0; i < 50 && !Files.exists(wf); i++) Thread.sleep(100);
                if (Files.exists(wf)) world = Files.readString(wf, StandardCharsets.UTF_8).strip();
                log("restart: saving and quitting" + (world.isEmpty() ? "" : " (world '" + world + "')"));
                try { game.onExit().get(90, TimeUnit.SECONDS); }
                catch (TimeoutException e) { log("restart: the game did not exit in 90 s, stopping it"); game.destroyForcibly(); }
            }
            for (int i = 0; i < 300 && launcher.running(); i++) Thread.sleep(100);
            pid = 0;
            launch(world);
        } catch (Exception e) {
            log("restart failed: " + e);
        } finally {
            restarting = false;
        }
    }

    // ------------------------------------------------------------------ GameHost

    public Workspace.Target selectedTarget() { return target; }
    public long gamePid() { return pid; }
    public boolean gameAttached() { return pid > 0 && ProcessHandle.of(pid).isPresent(); }
    public Hotswapper hotswapper() { return hotswapper; }
    public Path agentJar() { return agentJar; }
    public int busy() { return busy.get(); }
    public long activity() { return activity.get(); }
    private volatile long lastWorkStartSeq, lastWorkEndMillis;
    public long lastWorkStartSeq() { return lastWorkStartSeq; }
    public long lastWorkEndMillis() { return lastWorkEndMillis; }
    public Map<String, String> lastLook() { return lastLook; }

    public String openWorld() throws Exception {
        long p = pid;
        if (p <= 0 || ProcessHandle.of(p).isEmpty()) return "";
        Path wf = tmp("world-" + p + "-q.txt");
        Files.deleteIfExists(wf);
        Hotswapper.loadAgent(p, agentJar, "world=" + wf);
        for (int i = 0; i < 30 && !Files.exists(wf); i++) Thread.sleep(100);
        return Files.exists(wf) ? Files.readString(wf, StandardCharsets.UTF_8).strip() : "";
    }

    public boolean inspect() {
        long p = pid;
        if (p <= 0) return false;
        try {
            Path f = tmp("look-" + p + ".txt");
            Files.deleteIfExists(f);
            Hotswapper.loadAgent(p, agentJar, "look=" + f);
            for (int i = 0; i < 50 && !Files.exists(f); i++) Thread.sleep(100);
            if (!Files.exists(f)) return false;
            var m = new LinkedHashMap<String, String>();
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) { int eq = l.indexOf('='); if (eq > 0) m.put(l.substring(0, eq), l.substring(eq + 1)); }
            lastLook = m;
            return true;
        } catch (Exception e) { return false; }
    }

    public void startStats(long p) {
        try {
            Path f = tmp("stats-" + p + ".txt");
            Files.deleteIfExists(f);
            Hotswapper.loadAgent(p, agentJar, "stats=" + f);
            statsFile = f;
        } catch (Exception e) { log("numbers unavailable: " + e.getMessage()); }
    }

    public Map<String, String> stats() {
        Path f = statsFile;
        var m = new LinkedHashMap<String, String>();
        try {
            if (f == null || !Files.exists(f) || System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() > 3000) return m;
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) { int eq = l.indexOf('='); if (eq > 0) m.put(l.substring(0, eq), l.substring(eq + 1)); }
        } catch (Exception ignored) { }
        return m;
    }

    public long journalSeq() { synchronized (journal) { return journalSeq; } }

    public List<String> journalSince(long seq) {
        synchronized (journal) {
            int skip = (int) Math.max(0, journal.size() - (journalSeq - seq));
            return journal.stream().skip(skip).toList();
        }
    }

    public void log(String s) {
        String line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + s;
        System.out.println("[SulfurFrame] " + line);
        synchronized (journal) {
            journal.addLast(line);
            if (journal.size() > 500) journal.removeFirst();
            journalSeq++;
        }
    }

    private void work(String name, Runnable r) {
        busy.incrementAndGet();
        activity.incrementAndGet();
        lastWorkStartSeq = journalSeq();
        Thread.ofPlatform().daemon().name(name).start(() -> { try { r.run(); } finally { lastWorkEndMillis = System.currentTimeMillis(); busy.decrementAndGet(); } });
    }

    // ------------------------------------------------------------------ helpers (the panel's, without loading it)

    static boolean needsRestart(Path f) {
        String n = f.getFileName().toString();
        return n.equals("fabric.mod.json") || n.equals("quilt.mod.json") || n.endsWith("mods.toml") || n.endsWith(".accesswidener")
                || n.equals("accesstransformer.cfg") || n.endsWith(".mixins.json") || n.equals("mixins.json")
                || n.startsWith("build.gradle") || n.startsWith("settings.gradle") || n.equals("gradle.properties") || n.equals("libs.versions.toml");
    }

    static boolean quickPlay(String mc) {
        if (mc == null) return false;
        String[] v = mc.split("\\.");
        try {
            int a = Integer.parseInt(v[0]), b = v.length > 1 ? Integer.parseInt(v[1].replaceAll("\\D.*", "")) : 0;
            return a > 1 || b >= 20;
        } catch (NumberFormatException e) { return false; }
    }

    private static Path tmp(String name) throws IOException {
        Path d = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe");
        Files.createDirectories(d);
        return d.resolve(name);
    }

    /** The agent jar from this jar's resources into an ASCII-only folder (Java 8 games cannot load it otherwise). */
    private Path extractAgent() {
        try (InputStream in = HeadlessHost.class.getResourceAsStream("/sf-agent.jar")) {
            if (in == null) { log("sf-agent.jar not found next to the host: no hotswap"); return null; }
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe");
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(dir.toString()))
                dir = Path.of(System.getenv().getOrDefault("PUBLIC", "C:\\Users\\Public"), "sulfurframe");
            Files.createDirectories(dir);
            byte[] bytes = in.readAllBytes();
            var crc = new java.util.zip.CRC32();
            crc.update(bytes);
            Path jar = dir.resolve(String.format("sf-agent-%08x.jar", crc.getValue()));
            if (!Files.exists(jar) || Files.size(jar) != bytes.length) {
                Path part = dir.resolve(jar.getFileName() + "." + ProcessHandle.current().pid() + ".part");
                Files.write(part, bytes);
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            return jar;
        } catch (Exception e) {
            log("could not extract the agent: " + e);
            return null;
        }
    }
}
