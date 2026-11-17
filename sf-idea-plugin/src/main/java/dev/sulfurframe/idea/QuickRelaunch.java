package dev.sulfurframe.idea;

import dev.sulfurframe.idea.profile.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;

/**
 * Starting the game again without Gradle. A restart through runClient pays Gradle's configuration every time (31 s of
 * the 83 s restart on a 9-project build) although the game's command line comes out the same. So after a Run client
 * the command line of the game is captured from the running process (the OS has it, argument files included) together
 * with its working folder and environment (the agent writes those), minus what SulfurFrame put in for that one
 * launch (its agent with that launch's marker, the debugger port, dev mods, the quick-play world). The next launch
 * reuses it, with fresh values for those, as long as nothing Gradle would have to redo has changed:
 * <ul>
 *   <li>build files (*.gradle[.kts], gradle.properties, settings, version catalogs, wrapper and daemon JVM files)
 *   <li>the JetBrains Runtime chosen for the game
 *   <li>mod metadata or access wideners (processResources expands them, Loom remaps for them)
 * </ul>
 * Code and plain resources saved since are compiled and copied by the hotswap path first ({@link Hotswapper#prepareRelaunch}).
 * Anything else, and any doubt, goes through Gradle as before. -Dsulfurframe.quickRelaunch=false turns it off.
 */
final class QuickRelaunch {
    /**
     * @param jvm       JVM options as the game got them (argument files copied, SulfurFrame's own removed)
     * @param args      program arguments without the quick-play ones of that launch
     * @param argsAt    where in {@code args} that launch's game arguments were (new ones go there), -1: at the end
     */
    record Spec(String key, String exe, String jbr, List<String> jvm, String main, List<String> args, int argsAt,
                Path cwd, Map<String, String> env, long capturedAt) {}

    static boolean enabled() { return !"false".equals(System.getProperty("sulfurframe.quickRelaunch")); }

    /** JVM options that take their value as the next argument. */
    private static final Set<String> VALUE_OPTIONS = Set.of("-cp", "-classpath", "--class-path", "-p", "--module-path",
            "--upgrade-module-path", "--add-modules", "--add-opens", "--add-exports", "--add-reads", "--patch-module",
            "--limit-modules", "--enable-native-access");

    /**
     * From the running game: its command line (OS), the context file the agent wrote next to the launch marker, and
     * what this launch added. Null (with a log line) if it cannot be taken apart safely.
     */
    static Spec capture(String key, long pid, Path marker, Path agentJar, List<String> extraJvm, List<String> gameArgs,
                        Path jbr, long startedAt, Consumer<String> log) {
        try {
            List<String> argv = dev.sulfurframe.idea.embed.ProcessArgs.of(pid);
            if (argv == null || argv.size() < 2) { log.accept("relaunch cache: could not read the game's command line"); return null; }
            Path ctx = Path.of(marker + ".ctx");
            if (!Files.exists(ctx)) { log.accept("relaunch cache: the agent wrote no launch context"); return null; }
            Path cwd = null;
            var env = new LinkedHashMap<String, String>();
            var d = Base64.getDecoder();
            for (String line : Files.readAllLines(ctx, StandardCharsets.US_ASCII)) {
                String[] p = line.split("\t", -1);
                if (p[0].equals("cwd") && p.length == 2) cwd = Path.of(new String(d.decode(p[1]), StandardCharsets.UTF_8));
                else if (p[0].equals("env") && p.length == 3) env.put(new String(d.decode(p[1]), StandardCharsets.UTF_8), new String(d.decode(p[2]), StandardCharsets.UTF_8));
            }
            Files.deleteIfExists(ctx);
            if (cwd == null) return null;

            String exe = ProcessHandle.of(pid).flatMap(h -> h.info().command()).orElse(argv.get(0));
            var jvm = new ArrayList<String>();
            int i = 1;
            for (; i < argv.size(); i++) {
                String a = argv.get(i);
                if (a.equals("-jar") || a.equals("-m") || a.equals("--module") || a.startsWith("--module=")) {
                    log.accept("relaunch cache: the game is started with " + a + ", not supported"); return null;
                }
                if (VALUE_OPTIONS.contains(a) && i + 1 < argv.size()) { jvm.add(a); jvm.add(argv.get(++i)); continue; }
                if (a.startsWith("-") || a.startsWith("@")) { jvm.add(a); continue; }
                break;
            }
            if (i >= argv.size()) { log.accept("relaunch cache: no main class on the command line (inside an argument file?)"); return null; }
            String main = argv.get(i);
            var args = new ArrayList<>(argv.subList(i + 1, argv.size()));

            // what this launch put in, to be replaced by the next launch's own values
            String agentPrefix = agentJar == null ? null : "-javaagent:" + agentJar;
            var extra = new ArrayList<>(extraJvm);
            jvm.removeIf(a -> (agentPrefix != null && a.startsWith(agentPrefix)) || a.startsWith("-agentlib:jdwp") || a.startsWith("-Xrunjdwp") || extra.remove(a));
            int at = -1;
            if (!gameArgs.isEmpty()) {
                at = Collections.lastIndexOfSubList(args, gameArgs);
                if (at < 0) { log.accept("relaunch cache: this launch's game arguments are not on the command line"); return null; }
                args.subList(at, at + gameArgs.size()).clear();
            }

            // argument files may be temporary (Gradle's own for long command lines) or rewritten by the next build:
            // keep a copy of what this game was started with
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "relaunch");
            Files.createDirectories(dir);
            for (int k = 0; k < jvm.size(); k++) {
                String a = jvm.get(k);
                if (!a.startsWith("@") || a.startsWith("@@")) continue;
                Path src = Path.of(a.substring(1));
                if (!src.isAbsolute()) src = cwd.resolve(src);
                if (!Files.isRegularFile(src)) { log.accept("relaunch cache: argument file " + src + " is gone"); return null; }
                Path copy = dir.resolve(Integer.toHexString(key.hashCode()) + "-" + k + ".args");
                Files.copy(src, copy, StandardCopyOption.REPLACE_EXISTING);
                jvm.set(k, "@" + copy);
            }
            return new Spec(key, exe, jbr == null ? "" : jbr.toString(), List.copyOf(jvm), main, List.copyOf(args), at,
                    cwd, Collections.unmodifiableMap(env), startedAt);
        } catch (Exception e) {
            log.accept("relaunch cache: " + e);
            return null;
        }
    }

    /** Null if the spec can start this target now, else why Gradle has to (for the log). Fast checks only. */
    static String whyNot(Spec spec, Workspace.Target t) {
        if (!enabled()) return "relaunch without Gradle is turned off (-Dsulfurframe.quickRelaunch=false)";
        Path jbr = Jbr.find(t.profile().javaMajor());
        if (!spec.jbr().equals(jbr == null ? "" : jbr.toString())) return "the JetBrains Runtime for the game changed";
        if (!Files.isExecutable(Path.of(spec.exe()))) return "java of the last run is gone: " + spec.exe();
        for (String a : spec.jvm()) if (a.startsWith("@") && !Files.isRegularFile(Path.of(a.substring(1)))) return "argument file copy is gone";
        try {
            Path changed = newerBuildFile(t.buildRoot(), spec.capturedAt());
            if (changed != null) return "build file changed: " + t.buildRoot().relativize(changed);
        } catch (IOException e) {
            return "could not check the build files: " + e.getMessage();
        }
        return null;
    }

    /** The command for the next launch: cached JVM options, this launch's extras and agent, cached and new game arguments. */
    static List<String> command(Spec spec, List<String> extraJvm, String agentSpec, List<String> gameArgs) {
        var cmd = new ArrayList<String>();
        cmd.add(spec.exe());
        cmd.addAll(spec.jvm());
        cmd.addAll(extraJvm);
        if (agentSpec != null) cmd.add("-javaagent:" + agentSpec);
        cmd.add(spec.main());
        var args = new ArrayList<>(spec.args());
        args.addAll(spec.argsAt() < 0 || spec.argsAt() > args.size() ? args.size() : spec.argsAt(), gameArgs);
        cmd.addAll(args);
        return cmd;
    }

    /**
     * Windows limits a command line to 32,767 characters. A build under a non-ASCII path gets no argument file from
     * Loom, so the whole class path is on the line (32,223 characters on a 9-project build), and the arguments of a
     * launch can tip it over. Then the class path moves to the CLASSPATH variable (java reads it when there is no
     * -cp; a variable has its own 32,767 limit, and the environment block is UTF-16, so any path survives).
     * Returns the class path taken out of {@code cmd}, or null if the line fits (or there is no -cp to move).
     */
    static String moveLongClasspath(List<String> cmd) {
        int limit = Integer.getInteger("sulfurframe.relaunch.maxCommandLine", 32_000);
        long length = 0;
        for (String a : cmd) length += a.length() + 3;   // separator and quotes
        if (length <= limit) return null;
        for (int i = 1; i + 1 < cmd.size(); i++) {
            String a = cmd.get(i);
            if (a.equals("-cp") || a.equals("-classpath") || a.equals("--class-path")) {
                String cp = cmd.get(i + 1);
                if (cp.length() >= 32_767) return null;
                cmd.remove(i + 1); cmd.remove(i);
                return cp;
            }
            if (!a.startsWith("-") && !a.startsWith("@")) break;   // the main class: no -cp before it
        }
        return null;
    }

    private static final Set<String> BUILD_FILE_NAMES = Set.of("gradle.properties", "libs.versions.toml",
            "gradle-wrapper.properties", "gradle-daemon-jvm.properties");
    private static final Set<String> SKIP_DIRS = Set.of("build", "out", "bin", "run", "runs", "node_modules", "src");

    /** A Gradle build file under {@code root} modified after {@code since}, or null. */
    static Path newerBuildFile(Path root, long since) throws IOException {
        Path[] found = {null};
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 8, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                String n = dir.getFileName().toString();
                if (n.equals("src") && buildLogic(root.relativize(dir))) return FileVisitResult.CONTINUE;   // plugin code of the build
                // .gradle and .git hold no build input; gradle/ (wrapper, catalogs) does
                return n.startsWith(".") || SKIP_DIRS.contains(n) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String n = file.getFileName().toString();
                boolean build = n.endsWith(".gradle") || n.endsWith(".gradle.kts") || BUILD_FILE_NAMES.contains(n)
                        || buildLogic(root.relativize(file));
                if (build && attrs.lastModifiedTime().toMillis() > since) { found[0] = file; return FileVisitResult.TERMINATE; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.CONTINUE; }
        });
        return found[0];
    }

    /** Inside buildSrc or a build-logic included build: its code configures the build. */
    private static boolean buildLogic(Path rel) {
        for (Path p : rel) { String s = p.toString(); if (s.equals("buildSrc") || s.equals("build-logic")) return true; }
        return false;
    }

    private QuickRelaunch() {}
}
