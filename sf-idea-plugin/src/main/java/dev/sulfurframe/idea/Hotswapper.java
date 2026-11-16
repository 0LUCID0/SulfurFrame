package dev.sulfurframe.idea;

import dev.sulfurframe.idea.profile.Workspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Save -> running game, without a debugger:
 *   1. compile the selected target with its own Gradle wrapper ({@code <path>:classes}, plus {@code clientClasses}
 *      for Loom's split client source set)
 *   2. find .class files under build/classes that changed since the last swap
 *   3. hand the list to the SulfurFrame agent in the game (Attach API, "redefine=<list>"), which redefines the loaded
 *      classes and writes a result file
 * Method-body changes apply live; added/removed methods or fields are reported as "restart needed".
 */
final class Hotswapper {
    private final Consumer<String> log;
    private final AtomicBoolean busy = new AtomicBoolean();
    private Map<Path, Long> snapshot;
    private long sourcesSince;   // .java files modified after this (millis) are compiled by the fast path
    private int counter;
    private final FastCompile fast;

    Hotswapper(Consumer<String> log, Path cacheDir) { this.log = log; this.fast = new FastCompile(log, cacheDir); }

    /** "javac" or "gradle": how the last swap compiled (self-test). */
    volatile String lastCompiler = "";

    /** Wait (up to {@code seconds}) until the fast path has its class path; false if it never got one. */
    /** Attach the SulfurFrame agent with these arguments (each request is a fresh attach). */
    static void loadAgent(long pid, Path agentJar, String args) throws Exception {
        try {
            var vm = com.sun.tools.attach.VirtualMachine.attach(Long.toString(pid));
            try { vm.loadAgent(agentJar.toString(), args); } finally { vm.detach(); }
        } catch (com.sun.tools.attach.AgentLoadException e) {
            if (!String.valueOf(e.getMessage()).endsWith(": 0")) throw e;   // Java 8 game: reply-format quirk
        }
    }

    /** Startup-only code that changed in the last swap ("Class.method"), see {@link StartupCode}. */
    volatile List<String> lastStartupChanges = List.of();

    /** What the last resource reload did (self-test). */
    volatile String lastResources = "";

    /**
     * Saved resource files: copy them where the dev game reads resources (the source set's resource output, what
     * processResources fills), then ask the agent to reload: assets/ -> client reload (F3+T), data/ -> /reload.
     * Files processResources expands (mod metadata) are left alone: they need a restart anyway.
     */
    boolean reloadResources(Workspace.Target t, long gamePid, Path agentJar, List<Path> files) {
        if (t == null || gamePid <= 0 || agentJar == null) return false;
        var sets = fast.sourceSets(t);
        if (sets == null) { log.accept("resources: class path not read yet (Run client first)"); return false; }
        boolean assets = false, data = false;
        int copied = 0;
        try {
            for (Path f : files) {
                Path abs = f.toAbsolutePath().normalize();
                String name = abs.getFileName().toString();
                if (name.equals("fabric.mod.json") || name.equals("quilt.mod.json") || name.endsWith("mods.toml")) {
                    log.accept("resources: " + name + " changed: restart the game to apply"); continue;
                }
                for (var s : sets) {
                    if (s.resOut() == null) continue;
                    for (Path srcDir : s.resSrcDirs()) {
                        Path root = srcDir.toAbsolutePath().normalize();
                        if (!abs.startsWith(root)) continue;
                        Path rel = root.relativize(abs);
                        Path to = s.resOut().resolve(rel);
                        Files.createDirectories(to.getParent());
                        if (Files.exists(abs)) Files.copy(abs, to, StandardCopyOption.REPLACE_EXISTING); else Files.deleteIfExists(to);
                        copied++;
                        String top = rel.getName(0).toString();
                        if (top.equals("assets")) assets = true;
                        else if (top.equals("data")) data = true;
                        else assets = true;   // pack.mcmeta and friends: a client reload picks them up
                    }
                }
            }
            if (copied == 0) return false;
            String what = (assets ? "assets" : "") + (assets && data ? "+" : "") + (data ? "data" : "");
            try {
                var vm = com.sun.tools.attach.VirtualMachine.attach(Long.toString(gamePid));
                try { vm.loadAgent(agentJar.toString(), "codingFps=" + Settings.codingFps() + ",reload=" + what); } finally { vm.detach(); }
            } catch (com.sun.tools.attach.AgentLoadException e) {
                if (!String.valueOf(e.getMessage()).endsWith(": 0")) throw e;
            }
            lastResources = what + " (" + copied + " file(s))";
            log.accept("resources: " + copied + " file(s) copied, reload " + what);
            return true;
        } catch (Exception e) {
            log.accept("resources failed: " + e);
            return false;
        }
    }

    boolean awaitFastCompile(Workspace.Target t, int seconds) throws InterruptedException {
        fast.prepareAsync(t);
        for (int i = 0; i < seconds * 4 && !fast.ready(t); i++) {
            if (!fast.preparing(t)) return fast.ready(t);
            Thread.sleep(250);
        }
        return fast.ready(t);
    }

    /** What the last swap did, for callers that want to check (self-test). */
    volatile List<String> lastResult = List.of();

    /** Remember the current class files (call when the game starts, so the first swap only sends what changed since). */
    void snapshot(Workspace.Target t) {
        try {
            snapshot = scan(t);
            sourcesSince = System.currentTimeMillis();
            classBytes.clear();
            for (Path p : snapshot.keySet()) classBytes.put(p, Files.readAllBytes(p));   // a mod's classes: small
        } catch (IOException e) { snapshot = null; }
        fast.prepareAsync(t);   // ask Gradle for the class path once, in the background
    }

    /** Last swapped bytes of every class file, to see what a change touched (startup code). */
    private final Map<Path, byte[]> classBytes = new HashMap<>();

    /** Compile, diff, redefine. Blocking; call off the EDT. Returns false if it could not run. */
    boolean swap(Workspace.Target t, long gamePid, Path agentJar) {
        if (t == null || gamePid <= 0 || agentJar == null) { log.accept("hotswap: no running game / agent"); return false; }
        if (!busy.compareAndSet(false, true)) { log.accept("hotswap: already running"); return false; }
        try {
            long t0 = System.currentTimeMillis();
            boolean fresh = snapshot == null;   // attached without a snapshot: don't know which sources changed
            if (fresh) snapshot = scan(t);
            adoptNewClassFiles(t);
            long started = System.currentTimeMillis();
            FastCompile.Result r = FastCompile.Result.UNAVAILABLE;
            int[] n = {0};
            if (!fresh) r = fast.compile(t, sourcesSince - 1000, n);   // 1 s slack for coarse file times
            if (r == FastCompile.Result.FAILED) return false;           // real compile errors: keep sourcesSince, retry them next time
            if (r == FastCompile.Result.OK) lastCompiler = fast.lastMode;
            else {
                fast.prepareAsync(t);
                if (!compile(t)) return false;
                lastCompiler = "gradle";
            }
            sourcesSince = started;
            var changed = new ArrayList<Path>();
            var startup = new ArrayList<String>();
            var constantOwners = new LinkedHashSet<String>();
            collectChanged(changed, startup, constantOwners, t);
            // A changed compile-time constant (static final int/float/String...) is copied into every class that reads
            // it; fast compile only rebuilt the edited file, so the readers would go on with the old value. Recompile
            // the sources that mention the class and send what that changes too. (Gradle does this itself.)
            if (!constantOwners.isEmpty() && r == FastCompile.Result.OK) {
                int users = fast.compileUsersOf(t, constantOwners);
                if (users < 0) log.accept("hotswap: constant changed in " + constantOwners + " but its users could not be recompiled: restart to apply it everywhere");
                else {
                    collectChanged(changed, startup, new LinkedHashSet<>(), t);
                    log.accept("hotswap: constant changed in " + String.join(", ", constantOwners) + ": recompiled " + users + " file(s) that use it");
                }
            }
            lastStartupChanges = List.copyOf(startup);
            if (changed.isEmpty()) { log.accept("hotswap: no class changed"); lastResult = List.of("redefined 0"); return true; }

            Path dir = agentJar.getParent();
            Path list = dir.resolve("hotswap-" + ProcessHandle.current().pid() + "-" + (++counter) + ".txt");
            var lines = new ArrayList<String>();
            for (Path c : changed) lines.add(binaryName(t, c) + "\t" + c);
            Files.write(list, lines, StandardCharsets.UTF_8);
            Path result = Path.of(list + ".result");
            Files.deleteIfExists(result);

            try {
                var vm = com.sun.tools.attach.VirtualMachine.attach(Long.toString(gamePid));
                try { vm.loadAgent(agentJar.toString(), "codingFps=" + Settings.codingFps() + ",redefine=" + list); } finally { vm.detach(); }
            } catch (com.sun.tools.attach.AgentLoadException e) {
                if (!String.valueOf(e.getMessage()).endsWith(": 0")) throw e;   // Java 8 game: reply-format quirk
            }
            for (int i = 0; i < 100 && !Files.exists(result); i++) Thread.sleep(100);
            if (!Files.exists(result)) { log.accept("hotswap: no answer from the game"); return false; }
            lastResult = Files.readAllLines(result, StandardCharsets.UTF_8);
            Files.deleteIfExists(list); Files.deleteIfExists(result);
            String summary = String.join(", ", lastResult.stream().filter(s -> !s.startsWith("ms ")).toList());
            log.accept("hotswap (" + lastCompiler + ", " + changed.size() + " class file(s), " + (System.currentTimeMillis() - t0) + " ms): " + summary);
            return true;
        } catch (Exception e) {
            log.accept("hotswap failed: " + e);
            return false;
        } finally {
            busy.set(false);
        }
    }

    private boolean compile(Workspace.Target t) throws IOException, InterruptedException {
        Path wrapper = GameLauncher.findWrapper(t.buildRoot());
        if (wrapper == null) { log.accept("hotswap: no gradlew.bat for " + t.where()); return false; }
        String prefix = t.projectPath().equals(":") ? "" : t.projectPath() + ":";
        var cmd = new ArrayList<>(List.of(wrapper.toString(), prefix + "classes"));
        if (Files.isDirectory(t.dir().resolve("src/client"))) cmd.add(prefix + "clientClasses");
        cmd.addAll(List.of("--console=plain", "-q"));
        var p = GradleJava.apply(new ProcessBuilder(cmd).directory(t.buildRoot().toFile()).redirectErrorStream(true), t).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            log.accept("hotswap: compile failed");
            out.lines().filter(l -> l.contains("error:") || l.startsWith("> ") || l.contains("What went wrong")).limit(8).forEach(l -> log.accept("  " + l.strip()));
            return false;
        }
        return true;
    }

    /**
     * Class output folders of the game's code: every source set fast compile knows (with a run config such as
     * runAllModsClient, other modules' too), else the target's build/classes/<lang>/<sourceSet>.
     */
    private List<Path> classRoots(Workspace.Target t) throws IOException {
        var roots = new ArrayList<Path>();
        var sets = fast.sourceSets(t);
        if (sets != null)
            for (var s : sets) {
                if (s.name().equals("test") || s.name().endsWith("Test") || s.name().endsWith(":test")) continue;
                roots.add(s.out());
                if (s.kotlin() != null) roots.add(s.kotlin().out());
            }
        if (roots.isEmpty()) {
            Path classes = t.dir().resolve("build/classes");
            if (Files.isDirectory(classes))
                try (Stream<Path> langs = Files.list(classes)) {
                    for (Path lang : langs.filter(Files::isDirectory).toList())
                        try (Stream<Path> ss = Files.list(lang)) { ss.filter(Files::isDirectory).forEach(roots::add); }
                }
        }
        return roots;
    }

    /**
     * Class files whose bytes differ from what the game was last given, added to {@code changed}; the snapshot moves on.
     * A newer file is not yet a changed class: a Gradle build rewrites every class file of a module, and sending them
     * all (751 for a one-line edit) crashed the game inside the JVM's class redefinition.
     */
    private void collectChanged(List<Path> changed, List<String> startup, Set<String> constantOwners, Workspace.Target t) throws IOException {
        Map<Path, Long> now = scan(t);
        for (var e : now.entrySet()) {
            Path c = e.getKey();
            Long before = snapshot.get(c);
            if (before != null && before >= e.getValue()) continue;
            byte[] after = Files.readAllBytes(c), old = classBytes.get(c);
            if (old != null && Arrays.equals(old, after)) continue;
            try { startup.addAll(StartupCode.changed(old, after)); } catch (Throwable ignored) { }
            try { constantOwners.addAll(StartupCode.constantOwnerNames(old, after)); } catch (Throwable ignored) { }
            classBytes.put(c, after);
            if (!changed.contains(c)) changed.add(c);
        }
        snapshot = now;
    }

    /**
     * Class files the snapshot does not know yet, from output folders it did not cover (fast compile read the class
     * path after the game started, and a run config brings other modules): what they hold now is what the game runs,
     * so they become the baseline instead of being sent as changes. Before compiling.
     */
    private void adoptNewClassFiles(Workspace.Target t) throws IOException {
        for (var e : scan(t).entrySet()) {
            if (snapshot.containsKey(e.getKey())) continue;
            snapshot.put(e.getKey(), e.getValue());
            classBytes.put(e.getKey(), Files.readAllBytes(e.getKey()));
        }
    }

    /** All .class files under the class roots, with their modification times. */
    private Map<Path, Long> scan(Workspace.Target t) throws IOException {
        var m = new HashMap<Path, Long>();
        for (Path root : classRoots(t)) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".class")).toList()) m.put(p, Files.getLastModifiedTime(p).toMillis());
            }
        }
        return m;
    }

    /** <class root>/a/b/C$D.class -> a.b.C$D */
    private String binaryName(Workspace.Target t, Path classFile) throws IOException {
        Path root = null;
        for (Path r : classRoots(t)) if (classFile.startsWith(r) && (root == null || r.getNameCount() > root.getNameCount())) root = r;
        String s = (root != null ? root.relativize(classFile) : classFile.getFileName()).toString().replace('\\', '/');
        return s.substring(0, s.length() - ".class".length()).replace('/', '.');
    }
}
