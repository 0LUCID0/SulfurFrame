package dev.sulfurframe.idea;

import dev.sulfurframe.idea.profile.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Hotswap compile without Gradle. Gradle spends ~15 s per swap configuring a Loom/NeoForge build before javac even
 * starts; the compile itself is a second or two. So Gradle is asked once (init script, task "sfClasspath") for what
 * javac needs per source set: source dirs, output dir, compile classpath, release, encoding, the javac it uses. That is
 * cached next to the agent until a build file changes. A swap then runs that javac on just the changed .java files,
 * straight into the source set's build/classes directory, where the game's dev class path reads them.
 *
 * Only the edited files are compiled (the rest come from the output dir on the class path), annotation processors are
 * off. That is right for what hotswap can apply anyway: changed method bodies. Anything this cannot handle (Kotlin,
 * no cache yet) returns {@link Result#UNAVAILABLE} and the caller uses Gradle.
 */
final class FastCompile {
    enum Result { OK, FAILED, UNAVAILABLE }

    record SourceSet(String name, List<Path> srcDirs, Path out, List<Path> classpath, String release, String encoding,
                     Path javac, List<String> args) { }

    private final Consumer<String> log;
    private final Path cacheDir;
    private final Map<String, List<SourceSet>> loaded = new HashMap<>();
    private final Set<String> preparing = Collections.synchronizedSet(new HashSet<>());

    FastCompile(Consumer<String> log, Path cacheDir) { this.log = log; this.cacheDir = cacheDir; }

    private static String key(Workspace.Target t) { return t.buildRoot().toAbsolutePath().normalize() + "|" + t.projectPath(); }

    private Path cacheFile(Workspace.Target t) {
        return cacheDir.resolve("classpath-" + Integer.toHexString(key(t).hashCode()) + ".txt");
    }

    /** The cached source sets, or null if there is no cache or a build file is newer than it. */
    synchronized List<SourceSet> sourceSets(Workspace.Target t) {
        Path f = cacheFile(t);
        try {
            if (!Files.exists(f) || newestBuildFile(t) > Files.getLastModifiedTime(f).toMillis()) { loaded.remove(key(t)); return null; }
            var sets = loaded.get(key(t));
            if (sets == null) { sets = parse(Files.readAllLines(f, StandardCharsets.UTF_8)); loaded.put(key(t), sets); }
            return sets;
        } catch (IOException e) { return null; }
    }

    boolean ready(Workspace.Target t) { return sourceSets(t) != null; }

    /** Ask Gradle for the class paths in the background, unless cached or already running. */
    void prepareAsync(Workspace.Target t) {
        if (ready(t) || !preparing.add(key(t))) return;
        Thread.ofPlatform().daemon().name("sf-classpath").start(() -> {
            try { prepare(t); } finally { preparing.remove(key(t)); }
        });
    }

    boolean preparing(Workspace.Target t) { return preparing.contains(key(t)); }

    private void prepare(Workspace.Target t) {
        long t0 = System.currentTimeMillis();
        try {
            Path wrapper = GameLauncher.findWrapper(t.buildRoot());
            if (wrapper == null) return;
            Files.createDirectories(cacheDir);
            Path init = cacheDir.resolve("sf-classpath.init.gradle");
            Files.writeString(init, INIT_SCRIPT, StandardCharsets.UTF_8);
            Path tmp = Path.of(cacheFile(t) + ".tmp");
            Files.deleteIfExists(tmp);
            String prefix = t.projectPath().equals(":") ? "" : t.projectPath() + ":";
            var p = new ProcessBuilder(wrapper.toString(), "--init-script", init.toString(), prefix + "sfClasspath",
                    "-PsfOut=" + tmp, "--no-configuration-cache", "--console=plain", "-q")
                    .directory(t.buildRoot().toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0 || !Files.exists(tmp)) {
                log.accept("fast compile unavailable (Gradle could not list the class path); hotswap uses Gradle");
                out.lines().filter(l -> l.contains("What went wrong") || l.startsWith("> ")).limit(4).forEach(l -> log.accept("  " + l.strip()));
                return;
            }
            Files.move(tmp, cacheFile(t), StandardCopyOption.REPLACE_EXISTING);
            var sets = sourceSets(t);
            log.accept("fast compile ready: " + (sets == null ? 0 : sets.size()) + " source set(s), "
                    + (System.currentTimeMillis() - t0) + " ms to read the class path");
        } catch (Exception e) {
            log.accept("fast compile unavailable: " + e);
        }
    }

    /**
     * Compile the .java files under the target's source sets that changed after {@code since} (millis).
     * {@code compiled} receives how many files were compiled.
     */
    Result compile(Workspace.Target t, long since, int[] compiled) throws IOException, InterruptedException {
        var sets = sourceSets(t);
        if (sets == null) return Result.UNAVAILABLE;
        if (hasChangedKotlin(t, since)) return Result.UNAVAILABLE;
        int total = 0;
        for (SourceSet s : sets) {
            if (s.name().equals("test") || s.name().endsWith("Test")) continue;   // not on the game's class path
            var files = changedSources(s, since);
            if (files.isEmpty()) continue;
            if (s.javac() == null || !Files.isExecutable(s.javac())) return Result.UNAVAILABLE;
            if (!javac(s, sets, files)) return Result.FAILED;
            total += files.size();
        }
        compiled[0] = total;
        return Result.OK;
    }

    private boolean javac(SourceSet s, List<SourceSet> all, List<Path> files) throws IOException, InterruptedException {
        var cp = new LinkedHashSet<Path>();
        for (SourceSet o : all) cp.add(o.out());   // the other sets' classes (client sees main, etc.)
        cp.addAll(s.classpath());
        var a = new ArrayList<String>();
        a.add("-d"); a.add(q(s.out()));
        a.add("-cp"); a.add(q(String.join(java.io.File.pathSeparator, cp.stream().map(Path::toString).toList())));
        a.add("-sourcepath"); a.add("\"\"");
        a.add("-implicit:none"); a.add("-proc:none"); a.add("-nowarn"); a.add("-g");
        a.add("-encoding"); a.add(s.encoding().isEmpty() ? "UTF-8" : s.encoding());
        if (!s.release().isEmpty()) { a.add("--release"); a.add(s.release()); }
        for (String x : s.args()) if (!x.startsWith("-A") && !x.startsWith("-Xplugin")) a.add(q(x));
        for (Path f : files) a.add(q(f));
        Path argfile = Files.createTempFile(cacheDir, "javac-", ".args");
        try {
            Files.write(argfile, a, StandardCharsets.UTF_8);
            // the argfile is read with javac's default charset: force UTF-8 so non-ASCII paths survive on any JDK
            var p = new ProcessBuilder(s.javac().toString(), "-J-Dfile.encoding=UTF-8", "-J-Dsun.jnu.encoding=UTF-8", "@" + argfile)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() == 0) return true;
            log.accept("hotswap: compile failed");
            out.lines().filter(l -> l.contains("error")).limit(8).forEach(l -> log.accept("  " + l.strip()));
            return false;
        } finally {
            Files.deleteIfExists(argfile);
        }
    }

    private static String q(Object o) { return "\"" + o.toString().replace('\\', '/') + "\""; }

    static List<Path> changedSources(SourceSet s, long since) throws IOException {
        var out = new ArrayList<Path>();
        for (Path d : s.srcDirs()) {
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> w = Files.walk(d)) {
                for (Path f : w.filter(p -> p.toString().endsWith(".java")).toList())
                    if (Files.getLastModifiedTime(f).toMillis() > since) out.add(f);
            }
        }
        return out;
    }

    private static boolean hasChangedKotlin(Workspace.Target t, long since) throws IOException {
        Path src = t.dir().resolve("src");
        if (!Files.isDirectory(src)) return false;
        try (Stream<Path> w = Files.walk(src)) {
            return w.filter(p -> p.toString().endsWith(".kt")).anyMatch(p -> {
                try { return Files.getLastModifiedTime(p).toMillis() > since; } catch (IOException e) { return true; }
            });
        }
    }

    /** Build scripts / properties / version catalog of the build root and the target dir. */
    private static long newestBuildFile(Workspace.Target t) {
        long newest = 0;
        for (Path d : new LinkedHashSet<>(List.of(t.buildRoot(), t.dir()))) {
            for (String n : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
                    "gradle.properties", "gradle/libs.versions.toml", "stonecutter.gradle", "stonecutter.gradle.kts")) {
                try { Path f = d.resolve(n); if (Files.exists(f)) newest = Math.max(newest, Files.getLastModifiedTime(f).toMillis()); }
                catch (IOException ignored) { }
            }
        }
        return newest;
    }

    /** Format written by the init script: "set\tname", then "src|out|cp|release|encoding|javac|arg\tvalue" lines. */
    static List<SourceSet> parse(List<String> lines) {
        var sets = new ArrayList<SourceSet>();
        String name = null, release = "", enc = "";
        Path out = null, javac = null, javaHome = null;
        List<Path> src = new ArrayList<>(), cp = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String k = line.substring(0, tab), v = line.substring(tab + 1);
            switch (k) {
                case "javahome" -> javaHome = Path.of(v);
                case "set" -> {
                    if (name != null && out != null) sets.add(new SourceSet(name, src, out, cp, release, enc, javac(javac, javaHome), args));
                    name = v; release = ""; enc = ""; out = null; javac = null;
                    src = new ArrayList<>(); cp = new ArrayList<>(); args = new ArrayList<>();
                }
                case "src" -> src.add(Path.of(v));
                case "out" -> out = Path.of(v);
                case "cp" -> cp.add(Path.of(v));
                case "release" -> release = v.startsWith("1.") ? v.substring(2) : v;   // targetCompatibility "1.8" -> 8
                case "encoding" -> enc = v;
                case "javac" -> javac = Path.of(v);
                case "arg" -> args.add(v);
                default -> { }
            }
        }
        if (name != null && out != null) sets.add(new SourceSet(name, src, out, cp, release, enc, javac(javac, javaHome), args));
        return sets;
    }

    /** The toolchain's javac if the build names one, else the JDK the Gradle daemon compiles with. */
    private static Path javac(Path toolchain, Path javaHome) {
        if (toolchain != null) return toolchain;
        if (javaHome == null) return null;
        Path j = javaHome.resolve("bin").resolve(System.getProperty("os.name", "").startsWith("Windows") ? "javac.exe" : "javac");
        return Files.exists(j) ? j : null;
    }

    /** Groovy init script; Gradle 7 .. 9. Writes the source-set description of the project it runs in. */
    static final String INIT_SCRIPT = """
            allprojects {
                tasks.register('sfClasspath') {
                    def p = project
                    doLast {
                        def lines = ["javahome\\t" + System.getProperty('java.home')]
                        def sets = p.extensions.findByName('sourceSets')
                        if (sets != null) sets.each { ss ->
                            def jc = p.tasks.findByName(ss.compileJavaTaskName)
                            if (jc == null) return
                            lines << "set\\t" + ss.name
                            ss.java.srcDirs.each { lines << "src\\t" + it.absolutePath }
                            def out = jc.hasProperty('destinationDirectory') ? jc.destinationDirectory.get().asFile : jc.destinationDir
                            lines << "out\\t" + out.absolutePath
                            ss.compileClasspath.files.each { lines << "cp\\t" + it.absolutePath }
                            def rel = null
                            try { rel = jc.options.release.orNull } catch (Throwable ignored) { }
                            lines << "release\\t" + (rel != null ? rel : (jc.targetCompatibility ?: ''))
                            lines << "encoding\\t" + (jc.options.encoding ?: '')
                            try {
                                def c = jc.javaCompiler.orNull
                                if (c != null) lines << "javac\\t" + c.executablePath.asFile.absolutePath
                            } catch (Throwable ignored) { }
                            (jc.options.compilerArgs ?: []).each { lines << "arg\\t" + it }
                        }
                        new File(p.property('sfOut').toString()).withWriter('UTF-8') { w -> lines.each { w.println(it) } }
                    }
                }
            }
            """;
}
