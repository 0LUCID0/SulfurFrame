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

    /** "javac in the IDE" or "javac process": how the last compile ran. */
    volatile String lastMode = "";
    /** How the current compile ran, step by step (Kotlin and Java may both run in one swap). */
    private final Set<String> modes = Collections.synchronizedSet(new LinkedHashSet<>());

    /** Kotlin compile of a source set (KGP): sources, output, class path, jvmTarget, module name, the compiler's jars. */
    record Kotlin(List<Path> srcDirs, Path out, List<Path> classpath, String jvmTarget, String module, List<Path> compiler) { }

    record SourceSet(String name, List<Path> srcDirs, Path out, List<Path> classpath, String release, String encoding,
                     Path javac, List<String> args, List<Path> resSrcDirs, Path resOut, List<Path> processors, Path generated, Kotlin kotlin) {
        /** Annotation processors to run: all of the build's except Mixin's (refmap writer: not needed in dev, and a
         *  partial compile would overwrite the refmap with only the changed mixins). */
        List<Path> activeProcessors() { return processors.stream().filter(p -> !p.getFileName().toString().toLowerCase().contains("mixin")).toList(); }
    }

    private final Consumer<String> log;
    private final Path cacheDir;
    private final Map<String, List<SourceSet>> loaded = new HashMap<>();
    private final Set<String> preparing = Collections.synchronizedSet(new HashSet<>());

    FastCompile(Consumer<String> log, Path cacheDir) { this.log = log; this.cacheDir = cacheDir; }

    /** Per build, project and run task: runAllModsClient brings other modules' source sets that runClient does not. */
    private static String key(Workspace.Target t) { return t.buildRoot().toAbsolutePath().normalize() + "|" + t.projectPath() + "|" + t.task(); }

    private Path cacheFile(Workspace.Target t) {
        return cacheDir.resolve("classpath5-" + Integer.toHexString(key(t).hashCode()) + ".txt");
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
        if (ready(t)) { if (warmed.add(key(t))) warmUp(t); return; }
        if (!preparing.add(key(t))) return;
        Thread.ofPlatform().daemon().name("sf-classpath").start(() -> {
            try { prepare(t); if (ready(t) && warmed.add(key(t))) warmUp(t); } finally { preparing.remove(key(t)); }
        });
    }

    private final Set<String> warmed = Collections.synchronizedSet(new HashSet<>());

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
            var p = GradleJava.apply(new ProcessBuilder(wrapper.toString(), "--init-script", init.toString(), prefix + "sfClasspath",
                    "-PsfOut=" + tmp, "-PsfTask=" + t.task(), "--no-configuration-cache", "--console=plain", "-q")
                    .directory(t.buildRoot().toFile()).redirectErrorStream(true), t).start();
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
        modes.clear();
        var sets = sourceSets(t);
        if (sets == null) return Result.UNAVAILABLE;
        int total = 0;
        // Kotlin first: Java sources may use what changed in Kotlin
        for (SourceSet s : sets) {
            if (s.name().equals("test") || s.name().endsWith("Test")) continue;
            var kfiles = changedKotlin(s, since);
            if (kfiles.isEmpty()) continue;
            Boolean ok = s.kotlin() == null ? null : kotlinc(s, sets, kfiles);
            if (ok == null) return Result.UNAVAILABLE;   // no Kotlin compiler from the build: let Gradle do it
            if (!ok) return Result.FAILED;
            total += kfiles.size();
        }
        if (total == 0 && hasChangedKotlin(t, since)) return Result.UNAVAILABLE;   // .kt outside any known source set
        for (SourceSet s : sets) {
            if (s.name().equals("test") || s.name().endsWith("Test")) continue;   // not on the game's class path
            var files = changedSources(s, since);
            if (files.isEmpty()) continue;
            Boolean ok = inProcess(s, sets, files);   // null: no compiler in this runtime
            modes.add("javac in the IDE");
            if (ok == null) {
                if (s.javac() == null || !Files.isExecutable(s.javac())) return Result.UNAVAILABLE;
                ok = javac(s, sets, files);
                modes.remove("javac in the IDE"); modes.add("javac process");
            }
            if (!ok) return Result.FAILED;
            total += files.size();
        }
        compiled[0] = total;
        lastMode = String.join(" + ", modes);
        return Result.OK;
    }

    /**
     * Recompile the Java sources that mention any of these simple class names (as a word): the users of a class whose
     * compile-time constants changed, which still hold the old values. Returns how many files were compiled, -1 if
     * that failed (or fast compile is not available).
     */
    int compileUsersOf(Workspace.Target t, Collection<String> names) throws IOException, InterruptedException {
        var sets = sourceSets(t);
        if (sets == null || names.isEmpty()) return -1;
        var word = java.util.regex.Pattern.compile("\\b(" + String.join("|", names.stream().map(java.util.regex.Pattern::quote).toList()) + ")\\b");
        int total = 0;
        for (SourceSet s : sets) {
            if (s.name().equals("test") || s.name().endsWith("Test") || s.name().endsWith(":test")) continue;
            var files = new ArrayList<Path>();
            for (Path dir : s.srcDirs()) {
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> w = Files.walk(dir)) {
                    for (Path f : w.filter(p -> p.toString().endsWith(".java")).toList())
                        if (word.matcher(Files.readString(f, StandardCharsets.UTF_8)).find()) files.add(f);
                }
            }
            if (files.isEmpty()) continue;
            Boolean ok = inProcess(s, sets, files);
            if (ok == null) ok = s.javac() != null && Files.isExecutable(s.javac()) && javac(s, sets, files);
            if (!ok) return -1;
            total += files.size();
        }
        return total;
    }

    private boolean javac(SourceSet s, List<SourceSet> all, List<Path> files) throws IOException, InterruptedException {
        var cp = new LinkedHashSet<Path>();
        for (SourceSet o : all) { cp.add(o.out()); if (o.kotlin() != null) cp.add(o.kotlin().out()); }   // other sets, and Kotlin output (Java may use it)
        cp.addAll(s.classpath());
        var a = new ArrayList<String>();
        a.add("-d"); a.add(q(s.out()));
        a.add("-cp"); a.add(q(String.join(java.io.File.pathSeparator, cp.stream().map(Path::toString).toList())));
        a.add("-sourcepath"); a.add("\"\"");
        a.add("-implicit:none"); a.add("-nowarn"); a.add("-g");
        var procs = s.activeProcessors();
        if (procs.isEmpty()) a.add("-proc:none");
        else {
            a.add("-processorpath"); a.add(q(String.join(java.io.File.pathSeparator, procs.stream().map(Path::toString).toList())));
            if (s.generated() != null) { Files.createDirectories(s.generated()); a.add("-s"); a.add(q(s.generated())); }
        }
        a.add("-encoding"); a.add(s.encoding().isEmpty() ? "UTF-8" : s.encoding());
        if (!s.release().isEmpty()) { a.add("--release"); a.add(s.release()); }
        for (String x : s.args()) if (keepArg(x, !procs.isEmpty())) a.add(q(x));
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

    // ------------------------------------------------------------------ in-process javac (warm, no JVM start)

    /**
     * The IDE runs on JBR, which ships jdk.compiler and ct.sym: compile right here with javax.tools, --release of the
     * project. One file manager per source set, kept between swaps, so jar indexes of the (large) Minecraft class path
     * are read once. Returns null if this runtime has no compiler.
     */
    private Boolean inProcess(SourceSet s, List<SourceSet> all, List<Path> files) throws IOException {
        var jc = compiler();
        if (jc == null) return null;
        var fm = fileManager(jc, s, all);
        var diags = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        var opts = new ArrayList<>(List.of("-implicit:none", "-nowarn", "-g",
                "-encoding", s.encoding().isEmpty() ? "UTF-8" : s.encoding()));
        boolean processing = !s.activeProcessors().isEmpty();
        if (!processing) opts.add("-proc:none");
        if (!s.release().isEmpty()) { opts.add("--release"); opts.add(s.release()); }
        for (String x : s.args()) if (keepArg(x, processing)) opts.add(x);
        boolean ok;
        synchronized (fm) {
            var task = jc.getTask(null, fm, diags, opts, null, fm.getJavaFileObjectsFromPaths(files));
            if (processing) task.setProcessors(processors(s));   // from a kept loader: jars are not re-read every swap
            ok = task.call();
        }
        if (!ok) {
            log.accept("hotswap: compile failed");
            diags.getDiagnostics().stream().filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR).limit(8)
                    .forEach(d -> log.accept("  " + (d.getSource() == null ? "" : Path.of(d.getSource().toUri()).getFileName() + ":" + d.getLineNumber() + ": ") + d.getMessage(null)));
        }
        return ok;
    }

    // ------------------------------------------------------------------ Kotlin (the build's own compiler, in the IDE)

    static List<Path> changedKotlin(SourceSet s, long since) throws IOException {
        var out = new ArrayList<Path>();
        if (s.kotlin() == null) return out;
        for (Path d : s.kotlin().srcDirs()) {
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> w = Files.walk(d)) {
                for (Path f : w.filter(p -> p.toString().endsWith(".kt")).toList())
                    if (Files.getLastModifiedTime(f).toMillis() > since) out.add(f);
            }
        }
        return out;
    }

    /** One warm Kotlin compile server per compiler class path (see {@link KotlinServer}). */
    private final Map<String, Process> kotlinServers = new HashMap<>();

    /**
     * Compile with the build's own Kotlin compiler (kotlinCompilerClasspath: the exact version the project uses), in a
     * compile server process started once and kept warm. Changed files go to a scratch dir and only their .class files
     * are copied into the Kotlin output: a partial compile would otherwise rewrite META-INF/*.kotlin_module with only
     * those files. Returns null when the build gave no compiler.
     */
    private Boolean kotlinc(SourceSet s, List<SourceSet> all, List<Path> files) throws IOException {
        var k = s.kotlin();
        var jars = k.compiler().stream().filter(Files::exists).toList();
        if (jars.isEmpty()) return null;
        modes.add("kotlinc server");
        var cp = new LinkedHashSet<Path>();
        for (SourceSet o : all) { cp.add(o.out()); if (o.kotlin() != null) cp.add(o.kotlin().out()); }
        cp.addAll(k.classpath());
        cp.removeIf(p -> !Files.exists(p));
        Path scratch = Files.createTempDirectory(cacheDir, "kotlinc-");
        try {
            var args = new ArrayList<String>();
            for (Path f : files) args.add(f.toString());
            args.addAll(List.of("-d", scratch.toString(), "-classpath", String.join(java.io.File.pathSeparator, cp.stream().map(Path::toString).toList()),
                    "-no-stdlib", "-no-reflect", "-nowarn", "-module-name", k.module().isEmpty() ? "main" : k.module()));
            if (!k.jvmTarget().isEmpty()) { args.add("-jvm-target"); args.add(k.jvmTarget()); }
            var messages = new ArrayList<String>();
            String code;
            synchronized (kotlinServers) {
                Process server = kotlinServer(jars);
                if (server == null) return null;
                var w = new java.io.OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8);
                w.write(String.join("\t", args) + "\n");
                w.flush();
                code = readUntilExit(server, messages);
                if (code == null) { kotlinServers.remove(jars.toString()); server.destroy(); log.accept("Kotlin compile server died"); return false; }
            }
            if (!code.equals("OK")) {
                log.accept("hotswap: Kotlin compile failed (" + code + ")");
                messages.stream().filter(l -> l.startsWith("e:") || l.contains("error")).limit(8).forEach(l -> log.accept("  " + l.strip()));
                return false;
            }
            try (Stream<Path> w = Files.walk(scratch)) {
                for (Path c : w.filter(p -> p.toString().endsWith(".class")).toList()) {
                    Path to = k.out().resolve(scratch.relativize(c).toString());
                    Files.createDirectories(to.getParent());
                    Files.copy(c, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return true;
        } finally {
            try (Stream<Path> w = Files.walk(scratch)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }

    /** The running server for these compiler jars, started on first use (java of the IDE's runtime). */
    private Process kotlinServer(List<Path> jars) throws IOException {
        String key = jars.toString();
        Process p = kotlinServers.get(key);
        if (p != null && p.isAlive()) return p;
        // IntelliJ's plugin class loader gives no code source location: put the server class into a folder of its own
        Path self = cacheDir.resolve("kserver");
        Path cls = self.resolve("dev/sulfurframe/idea/KotlinServer.class");
        try (var in = KotlinServer.class.getResourceAsStream("KotlinServer.class")) {
            if (in == null) { log.accept("Kotlin compile server: class not found in the plugin"); return null; }
            Files.createDirectories(cls.getParent());
            Files.write(cls, in.readAllBytes());
        }
        var cp = new ArrayList<String>();
        cp.add(self.toString());
        for (Path j : jars) cp.add(j.toString());
        Path javaExe = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        p = new ProcessBuilder(javaExe.toString(), "-Xmx1g", "-Dfile.encoding=UTF-8", "-cp", String.join(java.io.File.pathSeparator, cp), KotlinServer.class.getName())
                .redirectErrorStream(true).start();
        var ready = new ArrayList<String>();
        var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        kotlinReaders.put(p, r);
        for (String line; (line = r.readLine()) != null; ) {
            if (line.equals("SF-READY")) { kotlinServers.put(key, p); return p; }
            ready.add(line);
        }
        log.accept("Kotlin compile server did not start: " + String.join(" | ", ready));
        return null;
    }

    private final Map<Process, java.io.BufferedReader> kotlinReaders = new HashMap<>();

    /** Collect the compiler's messages up to "SF-EXIT <code>"; null if the server went away. */
    private String readUntilExit(Process p, List<String> messages) throws IOException {
        var r = kotlinReaders.get(p);
        for (String line; (line = r.readLine()) != null; ) {
            if (line.startsWith("SF-EXIT ")) return line.substring("SF-EXIT ".length()).strip();
            messages.add(line);
        }
        return null;
    }

    private final Map<String, ClassLoader> processorLoaders = new HashMap<>();

    /**
     * Fresh processor instances (javac initializes them per compilation) from one class loader per source set, kept
     * between swaps: loading Lombok & co. again for every compile cost over a second.
     */
    private synchronized List<javax.annotation.processing.Processor> processors(SourceSet s) throws IOException {
        var procs = s.activeProcessors().stream().filter(Files::exists).toList();
        String key = s.name() + procs;
        var loader = processorLoaders.get(key);
        if (loader == null) {
            var urls = new ArrayList<java.net.URL>();
            for (Path p : procs) urls.add(p.toUri().toURL());
            loader = new java.net.URLClassLoader(urls.toArray(new java.net.URL[0]), javax.tools.ToolProvider.class.getClassLoader());
            processorLoaders.put(key, loader);
        }
        var out = new ArrayList<javax.annotation.processing.Processor>();
        for (var p : java.util.ServiceLoader.load(javax.annotation.processing.Processor.class, loader)) out.add(p);
        return out;
    }

    /**
     * Compiler arguments of the build worth passing on. -A options only when processors run, and never Mixin's
     * (refmap/mapping file options for the processor we leave out); plugin and processor-selection flags never.
     */
    static boolean keepArg(String x, boolean processing) {
        if (x.startsWith("-Xplugin") || x.startsWith("-processor")) return false;
        if (x.startsWith("-A")) {
            String k = x.toLowerCase();
            return processing && !(k.contains("refmap") || k.contains("mapfile") || k.contains("obfuscationenv") || k.contains("mixin") || k.equals("-aquiet=true"));
        }
        return true;
    }

    private static volatile javax.tools.JavaCompiler compiler;
    private static volatile boolean compilerLooked;

    /**
     * The runtime's javac. ToolProvider finds nothing inside IntelliJ (its own system class loader), but the IDE starts
     * with --add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED, so JavacTool.create() is reachable.
     */
    static javax.tools.JavaCompiler compiler() {
        if (compilerLooked) return compiler;
        javax.tools.JavaCompiler c = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (c == null) {
            try { c = (javax.tools.JavaCompiler) Class.forName("com.sun.tools.javac.api.JavacTool").getMethod("create").invoke(null); }
            catch (Throwable ignored) { }
        }
        compiler = c;
        compilerLooked = true;
        return c;
    }

    private final Map<SourceSet, javax.tools.StandardJavaFileManager> managers = new HashMap<>();

    private synchronized javax.tools.StandardJavaFileManager fileManager(javax.tools.JavaCompiler jc, SourceSet s, List<SourceSet> all) throws IOException {
        var fm = managers.get(s);
        if (fm != null) return fm;
        managers.keySet().removeIf(k -> k.name().equals(s.name()));   // a re-read class path replaces the old set
        fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        var cp = new LinkedHashSet<Path>();
        for (SourceSet o : all) { cp.add(o.out()); if (o.kotlin() != null) cp.add(o.kotlin().out()); }
        cp.addAll(s.classpath());
        cp.removeIf(p -> !Files.exists(p));
        fm.setLocationFromPaths(javax.tools.StandardLocation.CLASS_PATH, cp);
        fm.setLocationFromPaths(javax.tools.StandardLocation.SOURCE_PATH, List.of());
        var procs = s.activeProcessors().stream().filter(Files::exists).toList();
        if (!procs.isEmpty()) {   // the build's annotation processors (Lombok, AutoService...), in their own loader
            fm.setLocationFromPaths(javax.tools.StandardLocation.ANNOTATION_PROCESSOR_PATH, procs);
            if (s.generated() != null) {
                Files.createDirectories(s.generated());
                fm.setLocationFromPaths(javax.tools.StandardLocation.SOURCE_OUTPUT, List.of(s.generated()));
            }
        }
        Files.createDirectories(s.out());
        fm.setLocationFromPaths(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(s.out()));
        managers.put(s, fm);
        return fm;
    }

    /** Compile one source of each set into a scratch dir in the background, so the first real swap is warm. */
    void warmUp(Workspace.Target t) {
        var sets = sourceSets(t);
        if (sets == null || compiler() == null) return;
        Thread.ofPlatform().daemon().name("sf-javac-warmup").start(() -> {
            long t0 = System.currentTimeMillis();
            try {
                for (SourceSet s : sets) {
                    if (s.name().equals("test") || s.name().endsWith("Test")) continue;
                    var files = changedSources(s, 0);
                    if (files.isEmpty()) continue;
                    var jc = compiler();
                    var fm = fileManager(jc, s, sets);
                    Path scratch = Files.createTempDirectory(cacheDir, "warmup-");
                    var opts = new ArrayList<>(List.of("-implicit:none", "-proc:none", "-nowarn"));
                    if (!s.release().isEmpty()) { opts.add("--release"); opts.add(s.release()); }
                    synchronized (fm) {   // output to the scratch dir for this one, then back
                        fm.setLocationFromPaths(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(scratch));
                        try { jc.getTask(null, fm, d -> { }, opts, null, fm.getJavaFileObjectsFromPaths(files.subList(0, 1))).call(); }
                        finally { fm.setLocationFromPaths(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(s.out())); }
                    }
                    try (var w = Files.walk(scratch)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
                }
                log.accept("javac warmed up in " + (System.currentTimeMillis() - t0) + " ms");
                for (SourceSet s : sets) {   // and Kotlin's compiler, if the build has Kotlin sources
                    if (s.kotlin() == null || s.name().equals("test") || s.name().endsWith("Test")) continue;
                    var kfiles = changedKotlin(s, 0);
                    if (kfiles.isEmpty()) continue;
                    long k0 = System.currentTimeMillis();
                    if (Boolean.TRUE.equals(kotlinc(s, sets, kfiles.subList(0, 1)))) log.accept("kotlinc warmed up in " + (System.currentTimeMillis() - k0) + " ms");
                }
            } catch (Exception e) { log.accept("javac warm-up skipped: " + e); }
        });
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
        Path out = null, javac = null, javaHome = null, resOut = null, gen = null, kout = null;
        String kjvm = "", kmod = "";
        List<Path> ksrc = new ArrayList<>(), kcp = new ArrayList<>(), kc = new ArrayList<>();
        List<Path> src = new ArrayList<>(), cp = new ArrayList<>(), resSrc = new ArrayList<>(), ap = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String k = line.substring(0, tab), v = line.substring(tab + 1);
            switch (k) {
                case "javahome" -> javaHome = Path.of(v);
                case "set" -> {
                    if (name != null && out != null) sets.add(new SourceSet(name, src, out, cp, release, enc, javac(javac, javaHome), args, resSrc, resOut, ap, gen, kout == null ? null : new Kotlin(ksrc, kout, kcp, kjvm, kmod, kc)));
                    name = v; release = ""; enc = ""; out = null; javac = null; resOut = null; gen = null; kout = null; kjvm = ""; kmod = "";
                    ksrc = new ArrayList<>(); kcp = new ArrayList<>(); kc = new ArrayList<>();
                    src = new ArrayList<>(); cp = new ArrayList<>(); args = new ArrayList<>(); resSrc = new ArrayList<>(); ap = new ArrayList<>();
                }
                case "ressrc" -> resSrc.add(Path.of(v));
                case "ap" -> ap.add(Path.of(v));
                case "ksrc" -> ksrc.add(Path.of(v));
                case "kout" -> kout = Path.of(v);
                case "kcp" -> kcp.add(Path.of(v));
                case "kjvm" -> kjvm = v;
                case "kmod" -> kmod = v;
                case "kc" -> kc.add(Path.of(v));
                case "gensrc" -> gen = Path.of(v);
                case "resout" -> resOut = Path.of(v);
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
        if (name != null && out != null) sets.add(new SourceSet(name, src, out, cp, release, enc, javac(javac, javaHome), args, resSrc, resOut, ap, gen, kout == null ? null : new Kotlin(ksrc, kout, kcp, kjvm, kmod, kc)));
        return sets;
    }

    /** The toolchain's javac if the build names one, else the JDK the Gradle daemon compiles with. */
    private static Path javac(Path toolchain, Path javaHome) {
        if (toolchain != null) return toolchain;
        if (javaHome == null) return null;
        Path j = javaHome.resolve("bin").resolve(System.getProperty("os.name", "").startsWith("Windows") ? "javac.exe" : "javac");
        return Files.exists(j) ? j : null;
    }

    /**
     * Groovy init script; Gradle 7 .. 9. Writes the source-set description of the project it runs in, plus (with
     * -PsfTask=<run task>) every source set of other projects of the build whose output is on that task's class path:
     * a run config like a multi-project mod's runAllModsClient puts the other modules' classes next to core's, and their
     * changes must be hotswapped too. Those sets are named "<project path>:<set>" to stay distinct.
     */
    static final String INIT_SCRIPT = """
            allprojects {
                tasks.register('sfClasspath') {
                    def p = project
                    doLast {
                        def lines = ["javahome\\t" + System.getProperty('java.home')]
                        def onRun = new HashSet()
                        try {
                            def rt = p.findProperty('sfTask') ? p.tasks.findByName(p.property('sfTask').toString()) : null
                            if (rt != null && rt.hasProperty('classpath')) rt.classpath.files.each { onRun << it.absoluteFile }
                        } catch (Throwable ignored) { }
                        def todo = [[p, null]]
                        if (!onRun.isEmpty()) p.rootProject.allprojects.each { o ->
                            if (o == p) return
                            def os = o.extensions.findByName('sourceSets')
                            if (os != null) os.each { s ->
                                try { if (s.output.classesDirs.files.any { onRun.contains(it.absoluteFile) }) todo << [o, s.name] } catch (Throwable ignored) { }
                            }
                        }
                        todo.each { pair ->
                          def q = pair[0]
                          def sets = q.extensions.findByName('sourceSets')
                          if (sets != null) sets.each { ss ->
                            if (pair[1] != null && ss.name != pair[1]) return
                            def jc = q.tasks.findByName(ss.compileJavaTaskName)
                            if (jc == null) return
                            lines << "set\\t" + (q == p ? ss.name : q.path + ':' + ss.name)
                            ss.java.srcDirs.each { lines << "src\\t" + it.absolutePath }
                            def out = jc.hasProperty('destinationDirectory') ? jc.destinationDirectory.get().asFile : jc.destinationDir
                            lines << "out\\t" + out.absolutePath
                            ss.resources.srcDirs.each { lines << "ressrc\\t" + it.absolutePath }
                            if (ss.output.resourcesDir != null) lines << "resout\\t" + ss.output.resourcesDir.absolutePath
                            try { (jc.options.annotationProcessorPath?.files ?: []).each { lines << "ap\\t" + it.absolutePath } } catch (Throwable ignored) { }
                            try {
                                def g = jc.options.generatedSourceOutputDirectory.orNull
                                if (g != null) lines << "gensrc\\t" + g.asFile.absolutePath
                            } catch (Throwable ignored) { }
                            try {   // Kotlin (KGP): compileKotlin / compileClientKotlin ...
                                def kt = q.tasks.findByName(ss.getCompileTaskName('kotlin'))
                                def kdirs = ss.extensions.findByName('kotlin')
                                if (kt != null && kdirs != null) {
                                    kdirs.srcDirs.each { lines << "ksrc\\t" + it.absolutePath }
                                    lines << "kout\\t" + kt.destinationDirectory.get().asFile.absolutePath
                                    kt.libraries.files.each { lines << "kcp\\t" + it.absolutePath }
                                    try { lines << "kjvm\\t" + kt.compilerOptions.jvmTarget.get().target } catch (Throwable ignored) { }
                                    try { lines << "kmod\\t" + (kt.compilerOptions.moduleName.orNull ?: q.name) } catch (Throwable ignored) { lines << "kmod\\t" + q.name }
                                    def kc = q.configurations.findByName('kotlinCompilerClasspath')
                                    if (kc != null) kc.files.each { lines << "kc\\t" + it.absolutePath }
                                }
                            } catch (Throwable ignored) { }
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
                        }
                        new File(p.property('sfOut').toString()).withWriter('UTF-8') { w -> lines.each { w.println(it) } }
                    }
                }
            }
            """;
}
