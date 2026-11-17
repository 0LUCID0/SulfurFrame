package dev.sulfurframe.idea.profile;

import dev.sulfurframe.idea.profile.ProjectProfile.Loader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Everything in an opened folder that can be started as a Minecraft dev client, as (build, task path, profile).
 *
 *   single project     the folder is one Gradle build that is a mod                    -> ":runClient"
 *   container          the folder holds several independent builds (each with its own settings.gradle),
 *                      e.g. a repo of separate mods                                    -> one target per build
 *   multi-project      one settings.gradle includes subprojects; the mod ones become targets
 *                      (Architectury fabric/neoforge, per-version modules like core:mod-fabric-1_21_1)
 *                                                                                      -> ":core:mod-fabric-1_21_1:runClient"
 *   Stonecutter        settings.gradle(.kts) declares versions; each is a project ":<version>" under versions/<version>
 *                                                                                      -> ":1.21.1:runClient"
 * Nothing is executed; only build files are read.
 */
public final class Workspace {
    /**
     * @param where location shown to the user: build folder relative to the opened folder, plus the project path
     * @param task  the Gradle task that starts this client: runClient, or a Loom run config of its own (runAllModsClient)
     */
    public record Target(Path buildRoot, String projectPath, Path dir, ProjectProfile profile, String where, String task) {
        public Target(Path buildRoot, String projectPath, Path dir, ProjectProfile profile, String where) { this(buildRoot, projectPath, dir, profile, where, "runClient"); }
        public String runTask() { return projectPath.equals(":") ? task : projectPath + ":" + task; }
        public boolean hasWrapper() { return Files.exists(buildRoot.resolve("gradlew.bat")) || Files.exists(buildRoot.resolve("gradlew")); }
        public String label() { return profile + "  —  " + where + (task.equals("runClient") ? "" : "  [" + task + "]") + (hasWrapper() ? "" : "  (no gradlew)"); }
        @Override public String toString() { return label(); }
    }

    private static final Set<String> SKIP = Set.of("build", "run", "runs", "node_modules", "out", "bin", "src");

    public static List<Target> scan(Path opened) throws IOException {
        opened = opened.toAbsolutePath().normalize();
        var roots = buildRoots(opened);
        // a nested folder with its own settings file is still a subproject if an enclosing build includes it
        var included = new HashSet<Path>();
        for (Path r : roots)
            for (String inc : includes(ProjectProfile.stripComments(read(r.resolve("settings.gradle")) + read(r.resolve("settings.gradle.kts")))))
                included.add(r.resolve(inc.replaceFirst("^:", "").replace(':', '/')).normalize());
        var out = new ArrayList<Target>();
        for (Path root : roots) {
            if (included.contains(root)) continue;
            for (Target t : targetsOfBuild(root)) {
                String rel = opened.relativize(root).toString().replace('\\', '/');
                String where = (rel.isEmpty() ? "." : rel) + (t.projectPath().equals(":") ? "" : " " + t.projectPath());
                out.add(new Target(t.buildRoot(), t.projectPath(), t.dir(), t.profile(), where, t.task()));
            }
        }
        // newest Minecraft first, then loader; within those a run config of the project's own (runAllModsClient: the
        // client with every module's mod) before the plain runClient of one module. The first is the default choice,
        // and alphabetical order alone picked another module in a multi-project mod, a client without the GUI module.
        out.sort(Comparator.comparing((Target t) -> versionKey(t.profile().minecraftVersion())).reversed()
                .thenComparing(t -> t.profile().loader())
                .thenComparing(t -> "runClient".equals(t.task()))
                .thenComparing(Target::projectPath));
        return out;
    }

    /** The opened folder if it is a build, plus nested independent builds (depth <= 3). */
    static List<Path> buildRoots(Path opened) throws IOException {
        var roots = new ArrayList<Path>();
        try (Stream<Path> s = Files.walk(opened, 3)) {
            s.filter(Files::isDirectory)
             .filter(d -> opened.relativize(d).toString().isEmpty() || noSkippedPart(opened.relativize(d)))
             .filter(d -> Files.exists(d.resolve("settings.gradle")) || Files.exists(d.resolve("settings.gradle.kts")))
             .forEach(roots::add);
        }
        if (roots.isEmpty() && (Files.exists(opened.resolve("build.gradle")) || Files.exists(opened.resolve("build.gradle.kts"))))
            roots.add(opened);   // build without settings file
        return roots;
    }

    private static boolean noSkippedPart(Path rel) {
        for (Path p : rel) if (SKIP.contains(p.toString()) || p.toString().startsWith(".")) return false;   // .git, .gradle, .claude/worktrees ...
        return true;
    }

    static List<Target> targetsOfBuild(Path root) throws IOException {
        String settings = read(root.resolve("settings.gradle")) + read(root.resolve("settings.gradle.kts"));
        settings = ProjectProfile.stripComments(settings);
        var out = new ArrayList<Target>();

        // Stonecutter: versions("1.20.1", "1.21.1") / vers("1.21.1-fabric", "1.21.1") inside stonecutter { }
        if (settings.contains("dev.kikugie.stonecutter") || settings.matches("(?s).*stonecutter\\s*\\{.*")) {
            for (String v : stonecutterVersions(settings)) {
                Path vdir = root.resolve("versions").resolve(v);
                var p = ProjectProfile.detect(Files.isDirectory(vdir) ? vdir : root, root, root);
                if (p.minecraftVersion() == null) {   // version names are usually the Minecraft version
                    String mc = v.replaceAll("[-+].*$", "");
                    p = new ProjectProfile(p.dir(), mc, p.loader(), p.loaderVersion(), ProjectProfile.javaFor(mc),
                            append(p.evidence(), "minecraft version " + mc + " from Stonecutter version " + v));
                }
                if (p.loader() != Loader.UNKNOWN) out.add(new Target(root, ":" + v, p.dir(), p, ""));
            }
            return out;
        }

        var rootProfile = ProjectProfile.detect(root);
        if (rootProfile.loader() != Loader.UNKNOWN && rootProfile.loader() != Loader.VANILLA)
            out.add(new Target(root, ":", root, rootProfile, ""));
        if (rootProfile.loader() != Loader.UNKNOWN && rootProfile.loader() != Loader.VANILLA)
            for (String task : extraClientRuns(root)) out.add(new Target(root, ":", root, rootProfile, "", task));
        for (String path : includes(settings)) {
            Path dir = root.resolve(path.replaceFirst("^:", "").replace(':', '/'));
            if (!Files.exists(dir.resolve("build.gradle")) && !Files.exists(dir.resolve("build.gradle.kts"))) continue;
            var p = ProjectProfile.detect(dir, dir, root);
            if (p.loader() == Loader.UNKNOWN || p.loader() == Loader.VANILLA) continue;
            out.add(new Target(root, path.startsWith(":") ? path : ":" + path, dir, p, ""));
            for (String task : extraClientRuns(dir)) out.add(new Target(root, path.startsWith(":") ? path : ":" + path, dir, p, "", task));
        }
        return out;
    }

    /**
     * Client run configs a build script adds besides Loom's own "client": each becomes a task "run" + Name.
     *   runConfigs.create("allModsClient") { inherit(runConfigs.getByName("client")) ... }   (Kotlin DSL)
     *   loom { runs { allModsClient { client() } } }   /   runs { create("x") { client() } }  (Groovy / Kotlin)
     * A config counts as a client when its name says so or its block calls client() / inherits "client".
     */
    static List<String> extraClientRuns(Path dir) throws IOException {
        var names = new LinkedHashSet<String>();
        runConfigs(dir).forEach((name, body) -> consider(names, name, body));
        names.remove("client");
        names.remove("server");
        var out = new ArrayList<String>();
        for (String n : names) out.add("run" + Character.toUpperCase(n.charAt(0)) + n.substring(1));
        return out;
    }

    /** Run configs declared in the build script (Loom runConfigs / runs, ModDevGradle and NeoGradle runs), name -> block. */
    public static Map<String, String> runConfigs(Path dir) throws IOException {
        String s = ProjectProfile.stripComments(read(dir.resolve("build.gradle")) + read(dir.resolve("build.gradle.kts")));
        var names = new LinkedHashMap<String, String>();
        Matcher m = Pattern.compile("\\b(?:runConfigs|runs)\\s*(?:\\.\\s*(?:create|register|named)\\s*\\(\\s*[\"'](\\w+)[\"']|\\{)").matcher(s);
        while (m.find()) {
            if (m.group(1) != null) { names.putIfAbsent(m.group(1), block(s, openAfter(s, m.end()))); continue; }
            String body = block(s, m.end() - 1);
            // inside runs { }: create("x") { } / register("x") { } / groovy  x { }
            Matcher e = Pattern.compile("(?:(?:create|register|named)\\s*\\(\\s*[\"'](\\w+)[\"']\\s*\\)|(?<![\\w.])(\\w+))\\s*\\{").matcher(body);
            int from = 0;
            while (e.find(from)) {
                String name = e.group(1) != null ? e.group(1) : e.group(2);
                String inner = block(body, e.end() - 1);
                if (!Set.of("configureEach", "all", "matching", "withType", "named", "create", "register", "getByName").contains(name)) names.putIfAbsent(name, inner);
                from = Math.max(e.end(), e.end() - 1 + inner.length() + 1);
            }
        }
        return names;
    }

    private static void consider(Set<String> names, String name, String body) {
        if (Set.of("configureEach", "all", "matching", "withType", "named", "create", "register", "getByName").contains(name)) return;
        String lower = name.toLowerCase(Locale.ROOT);
        // datagen / gametest configs inherit the client too, but they are not a game to play
        boolean client = lower.contains("client") || !lower.matches(".*(data|gen|test).*")
                && (body.matches("(?s).*\\bclient\\s*\\(\\s*\\).*") || body.matches("(?s).*inherit\\s*\\(.*[\"']client[\"'].*"));
        if (client) names.add(name);
    }

    /** Index of the "{" that follows pos after blanks and a closing ")" (create("x") { ... }), or -1. */
    private static int openAfter(String s, int pos) {
        int i = pos;
        while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ')')) i++;
        return i < s.length() && s.charAt(i) == '{' ? i : -1;
    }

    /** The text between the brace at {@code open} and its match ("" when open is -1). */
    private static String block(String s, int open) {
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(open + 1, i);
        }
        return s.substring(open + 1);
    }

    /** include("a", "b:c"), include 'a', ':b', includes listed one per call or comma-separated. */
    static List<String> includes(String settings) {
        var out = new ArrayList<String>();
        Matcher call = Pattern.compile("\\binclude\\s*\\(?((?:\\s*[\"'][^\"']+[\"']\\s*,?)+)\\)?").matcher(settings);
        while (call.find()) {
            Matcher arg = Pattern.compile("[\"']([^\"']+)[\"']").matcher(call.group(1));
            while (arg.find()) out.add(arg.group(1));
        }
        return out;
    }

    static List<String> stonecutterVersions(String settings) {
        var out = new LinkedHashSet<String>();
        Matcher call = Pattern.compile("\\b(?:versions|vers|version)\\s*\\(([^)]*)\\)").matcher(settings);
        while (call.find()) {
            Matcher arg = Pattern.compile("[\"']([^\"']+)[\"']").matcher(call.group(1));
            while (arg.find()) out.add(arg.group(1));
        }
        return List.copyOf(out);
    }

    private static List<String> append(List<String> l, String s) { var c = new ArrayList<>(l); c.add(s); return List.copyOf(c); }

    private static String read(Path f) throws IOException { return Files.exists(f) ? Files.readString(f) : ""; }

    /** "1.21.1" -> comparable key; year versions (26.3) sort after 1.x. */
    static String versionKey(String v) {
        if (v == null) return "";
        var sb = new StringBuilder();
        for (String part : v.split("[.\\-]")) sb.append(String.format("%05d", part.matches("\\d+") ? Integer.parseInt(part) : 0));
        return (v.startsWith("1.") ? "0" : "1") + sb;
    }

    public static void main(String[] a) throws Exception {
        for (String d : a) {
            System.out.println(d);
            for (Target t : scan(Path.of(d))) System.out.println("  " + t.label() + "   [" + t.runTask() + "]");
        }
    }

    private Workspace() {}
}
