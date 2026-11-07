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
    private int counter;

    Hotswapper(Consumer<String> log) { this.log = log; }

    /** What the last swap did, for callers that want to check (self-test). */
    volatile List<String> lastResult = List.of();

    /** Remember the current class files (call when the game starts, so the first swap only sends what changed since). */
    void snapshot(Workspace.Target t) { try { snapshot = scan(t); } catch (IOException e) { snapshot = null; } }

    /** Compile, diff, redefine. Blocking; call off the EDT. Returns false if it could not run. */
    boolean swap(Workspace.Target t, long gamePid, Path agentJar) {
        if (t == null || gamePid <= 0 || agentJar == null) { log.accept("hotswap: no running game / agent"); return false; }
        if (!busy.compareAndSet(false, true)) { log.accept("hotswap: already running"); return false; }
        try {
            long t0 = System.currentTimeMillis();
            if (snapshot == null) snapshot = scan(t);
            if (!compile(t)) return false;
            Map<Path, Long> now = scan(t);
            var changed = new ArrayList<Path>();
            for (var e : now.entrySet()) {
                Long before = snapshot.get(e.getKey());
                if (before == null || before < e.getValue()) changed.add(e.getKey());
            }
            snapshot = now;
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
                try { vm.loadAgent(agentJar.toString(), "codingFps=15,redefine=" + list); } finally { vm.detach(); }
            } catch (com.sun.tools.attach.AgentLoadException e) {
                if (!String.valueOf(e.getMessage()).endsWith(": 0")) throw e;   // Java 8 game: reply-format quirk
            }
            for (int i = 0; i < 100 && !Files.exists(result); i++) Thread.sleep(100);
            if (!Files.exists(result)) { log.accept("hotswap: no answer from the game"); return false; }
            lastResult = Files.readAllLines(result, StandardCharsets.UTF_8);
            Files.deleteIfExists(list); Files.deleteIfExists(result);
            String summary = String.join(", ", lastResult.stream().filter(s -> !s.startsWith("ms ")).toList());
            log.accept("hotswap (" + changed.size() + " changed file(s), " + (System.currentTimeMillis() - t0) + " ms): " + summary);
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
        var p = new ProcessBuilder(cmd).directory(t.buildRoot().toFile()).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            log.accept("hotswap: compile failed");
            out.lines().filter(l -> l.contains("error:") || l.startsWith("> ") || l.contains("What went wrong")).limit(8).forEach(l -> log.accept("  " + l.strip()));
            return false;
        }
        return true;
    }

    /** All .class files under build/classes of the target, with their modification times. */
    static Map<Path, Long> scan(Workspace.Target t) throws IOException {
        var m = new HashMap<Path, Long>();
        Path classes = t.dir().resolve("build/classes");
        if (!Files.isDirectory(classes)) return m;
        try (Stream<Path> s = Files.walk(classes)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".class")).toList()) m.put(p, Files.getLastModifiedTime(p).toMillis());
        }
        return m;
    }

    /** build/classes/<lang>/<sourceSet>/a/b/C$D.class -> a.b.C$D */
    static String binaryName(Workspace.Target t, Path classFile) {
        Path rel = t.dir().resolve("build/classes").relativize(classFile);
        String s = rel.subpath(2, rel.getNameCount()).toString().replace('\\', '/');
        return s.substring(0, s.length() - ".class".length()).replace('/', '.');
    }
}
