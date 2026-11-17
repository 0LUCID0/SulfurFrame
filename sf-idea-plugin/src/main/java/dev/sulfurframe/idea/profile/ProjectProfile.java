package dev.sulfurframe.idea.profile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Works out which Minecraft a mod project targets, from its own build files only (nothing is executed, no Gradle sync):
 * Minecraft version, mod loader, loader version and the Java version that game needs.
 *
 * Sources, in order of trust:
 *   1. build script plugin ids            -> loader (fabric-loom, net.neoforged.moddev, net.minecraftforge.gradle, ...)
 *   2. gradle.properties (project, then parents up to the settings root)
 *                                         -> minecraft_version / mc_version, loader_version, neo_version, forge_version
 *   3. build script coordinates           -> "com.mojang:minecraft:X", "net.fabricmc:fabric-loader:Y",
 *                                            "net.minecraftforge:forge:MC-FORGE", neoForge { version = "..." }
 *   4. gradle/libs.versions.toml          -> [versions] minecraft / fabric-loader / neoforge / forge
 * ${...} / property references are resolved against gradle.properties.
 */
public record ProjectProfile(Path dir, String minecraftVersion, Loader loader, String loaderVersion, int javaMajor,
                             List<String> evidence) {

    public enum Loader { VANILLA, FABRIC, QUILT, NEOFORGE, FORGE, FORGE_LEGACY, UNKNOWN }

    @Override public String toString() {
        return String.format("%s %s %s (Java %d)", loader, minecraftVersion == null ? "?" : minecraftVersion,
                loaderVersion == null ? "" : loaderVersion, javaMajor);
    }

    public boolean isComplete() { return minecraftVersion != null && loader != Loader.UNKNOWN; }

    // ------------------------------------------------------------------ detection

    public static ProjectProfile detect(Path dir) throws IOException { return detect(dir, dir, null); }

    public static ProjectProfile detect(Path dir, Path scriptsDir) throws IOException { return detect(dir, scriptsDir, null); }

    /**
     * @param dir        the (sub)project directory: its gradle.properties chain is used
     * @param scriptsDir where its build script lives (differs for Stonecutter, whose version projects share the
     *                   root build script)
     * @param buildRoot  root of the Gradle build this project belongs to, or null to stop at the first settings file.
     *                   A subproject may carry a stray settings.gradle of its own and still read the root's properties.
     */
    public static ProjectProfile detect(Path dir, Path scriptsDir, Path buildRoot) throws IOException {
        var ev = new ArrayList<String>();
        var props = gradleProperties(dir, buildRoot);
        String scripts = readScripts(scriptsDir);
        if (!scriptsDir.equals(dir)) scripts += readScripts(dir);
        // "${property("deps.fabric_loader")}" (Stonecutter style) -> "${deps.fabric_loader}", resolvable below
        scripts = scripts.replaceAll("\\$\\{\\s*(?:project\\.|rootProject\\.)?(?:property|findProperty)\\s*\\(\\s*[\"']([\\w.\\-]+)[\"']\\s*\\)\\s*(?:\\.toString\\(\\))?\\s*\\}", "\\${$1}");
        var toml = versionCatalog(dir);
        scriptVariables(scripts, props);   // val mcVersion = rootProject.property("mc_1_21_1_version") -> "$mcVersion" resolves

        // the loader must come from the build script itself (a directory name like mod-forge-1_21_1 is only a
        // version hint: scaffolded stubs carry such names without any loader plugin)
        Loader loader = architecturyPlatform(scripts, ev);
        if (loader == null) loader = loaderFromPlugins(scripts, ev);

        String mc = first(ev, "minecraft version",
                kv(props, "gradle.properties", "minecraft_version", "mc_version", "minecraftVersion", "minecraft.version"),
                coord(scripts, props, "com\\.mojang:minecraft:([^\"')\\s]+)", "build script com.mojang:minecraft"),
                forgeCoordMc(scripts, props),
                legacyForgeMc(scripts, props),
                kv(toml, "libs.versions.toml", "minecraft", "minecraft-version", "mc"));

        String lv = switch (loader) {
            case FABRIC -> first(ev, "loader version",
                    kv(props, "gradle.properties", "loader_version", "fabric_loader_version"),
                    coord(scripts, props, "net\\.fabricmc:fabric-loader:([^\"')\\s]+)", "build script fabric-loader"),
                    kv(toml, "libs.versions.toml", "fabric-loader", "fabric_loader", "loader"));
            case QUILT -> first(ev, "loader version",
                    kv(props, "gradle.properties", "loader_version", "quilt_loader_version"),
                    coord(scripts, props, "org\\.quiltmc:quilt-loader:([^\"')\\s]+)", "build script quilt-loader"));
            case NEOFORGE -> first(ev, "loader version",
                    kv(props, "gradle.properties", "neo_version", "neoforge_version"),
                    coord(scripts, props, "net\\.neoforged:neoforge:([^\"')\\s]+)", "build script net.neoforged:neoforge"),
                    assign(scripts, props, "neoForge\\s*\\{[^}]*?version\\s*=\\s*([\"'][^\"']+[\"']|[\\w.]+)", "build script neoForge { version }"),
                    kv(toml, "libs.versions.toml", "neoforge", "neo"));
            case FORGE -> first(ev, "loader version",
                    kv(props, "gradle.properties", "forge_version"),
                    forgeCoordLoader(scripts, props),
                    kv(toml, "libs.versions.toml", "forge"));
            case FORGE_LEGACY -> first(ev, "loader version", legacyForgeLoader(scripts, props), kv(props, "gradle.properties", "forge_version"));
            default -> null;
        };
        // Forge MDKs often write forge_version as "1.20.1-47.4.28"
        if (lv != null && mc != null && (loader == Loader.FORGE || loader == Loader.FORGE_LEGACY) && lv.startsWith(mc + "-"))
            lv = lv.substring(mc.length() + 1);
        // NeoForge versions encode the Minecraft version: 21.1.x -> 1.21.1, 20.4.x -> 1.20.4, 26.3.x -> 26.3
        if (mc == null && loader == Loader.NEOFORGE && lv != null) {
            mc = mcFromNeoForge(lv);
            if (mc != null) ev.add("minecraft version " + mc + " derived from NeoForge " + lv);
        }
        if (mc == null && loader != Loader.UNKNOWN) {
            mc = mcFromDirName(dir);
            if (mc != null) ev.add("minecraft version " + mc + " from directory name " + dir.getFileName());
        }
        if (loader == Loader.UNKNOWN && mc != null && scripts.isEmpty()) loader = Loader.VANILLA;
        return new ProjectProfile(dir, mc, loader, lv, javaFor(mc), List.copyOf(ev));
    }

    /**
     * Architectury subprojects get Loom from the root build ({@code subprojects { apply plugin: ... }}) and say which
     * platform they are in their own {@code architectury { fabric() / neoForge() / forge() / common(...) }} block.
     * Returns null when there is no such block; UNKNOWN for {@code common} (shared code, not runnable).
     */
    static Loader architecturyPlatform(String s, List<String> ev) {
        Matcher m = Pattern.compile("architectury\\s*\\{([^}]*)\\}", Pattern.DOTALL).matcher(s);
        while (m.find()) {
            String body = m.group(1);
            Loader l = body.matches("(?s).*\\bneoForge\\s*\\(.*") ? Loader.NEOFORGE
                    : body.matches("(?s).*\\bforge\\s*\\(.*") ? Loader.FORGE
                    : body.matches("(?s).*\\bfabric\\s*\\(.*") ? Loader.FABRIC
                    : body.matches("(?s).*\\bquilt\\s*\\(.*") ? Loader.QUILT
                    : body.matches("(?s).*\\bcommon\\s*\\(.*") ? Loader.UNKNOWN : null;
            if (l != null) { ev.add("loader " + l + " from architectury { } block" + (l == Loader.UNKNOWN ? " (common: shared code)" : "")); return l; }
        }
        return null;
    }

    static final Pattern DIR_HINT = Pattern.compile("(?i)(neoforge|forge|fabric|quilt)[-_]?(\\d+[._]\\d+(?:[._]\\d+)?)");

    /** e.g. mod-fabric-1_21_1, forge-1.20.1: last resort when the build script gives nothing. */
    static Loader loaderFromDirName(Path dir, List<String> ev) {
        Matcher m = DIR_HINT.matcher(String.valueOf(dir.getFileName()));
        if (!m.find()) return Loader.UNKNOWN;
        Loader l = Loader.valueOf(m.group(1).toUpperCase(Locale.ROOT));
        ev.add("loader " + l + " from directory name " + dir.getFileName());
        return l;
    }

    static String mcFromDirName(Path dir) {
        Matcher m = DIR_HINT.matcher(String.valueOf(dir.getFileName()));
        if (m.find()) return m.group(2).replace('_', '.');
        Matcher v = Pattern.compile("^(1\\.\\d+(?:\\.\\d+)?|\\d{2}\\.\\d+(?:\\.\\d+)?)$").matcher(String.valueOf(dir.getFileName()));
        return v.find() ? v.group(1) : null;
    }

    /**
     * Script-local variables that point at Gradle properties, added to {@code props} under the variable name:
     * kts  {@code val x = rootProject.property("k")}, {@code project.findProperty("k")}, {@code providers.gradleProperty("k")},
     *      {@code rootProject.extra["k"]}, {@code property("k")}
     * groovy {@code def x = rootProject.k}, {@code def x = project.property('k')}
     */
    static void scriptVariables(String s, Map<String, String> props) {
        Matcher kts = Pattern.compile("(?:val|var|def)\\s+(\\w+)\\s*(?::\\s*\\w+\\s*)?=\\s*(?:rootProject\\.|project\\.|providers\\.)?"
                + "(?:property|findProperty|gradleProperty|extra|properties)\\s*[(\\[]\\s*[\"']([\\w.\\-]+)[\"']").matcher(s);
        while (kts.find()) if (props.containsKey(kts.group(2))) props.putIfAbsent(kts.group(1), props.get(kts.group(2)));
        Matcher groovy = Pattern.compile("def\\s+(\\w+)\\s*=\\s*(?:rootProject|project)\\.([\\w]+)\\b(?!\\s*\\()").matcher(s);
        while (groovy.find()) if (props.containsKey(groovy.group(2))) props.putIfAbsent(groovy.group(1), props.get(groovy.group(2)));
    }

    static Loader loaderFromPlugins(String s, List<String> ev) {
        Object[][] table = {
                {"org.quiltmc.loom", Loader.QUILT},
                {"net.neoforged.moddev", Loader.NEOFORGE}, {"net.neoforged.gradle", Loader.NEOFORGE},
                {"net.minecraftforge.gradle.forge", Loader.FORGE_LEGACY},     // ForgeGradle 2.x (1.8 - 1.12.2)
                {"net.minecraftforge.gradle", Loader.FORGE},
                {"fabric-loom", Loader.FABRIC}, {"net.fabricmc.fabric-loom", Loader.FABRIC}, {"dev.architectury.loom", Loader.FABRIC},
        };
        // plugins applied inside subprojects { } belong to the children, not to this project
        s = withoutBlocks(s, "subprojects");
        // plugins declared with "apply false" (Architectury roots, convention roots) are not applied to this project
        s = s.replaceAll("id\\s*\\(?\\s*[\"'][^\"']+[\"']\\s*\\)?(\\s*version\\s*\\(?\\s*[\"'][^\"']*[\"']\\s*\\)?)?\\s*apply\\s*\\(?\\s*false\\s*\\)?", "");
        for (Object[] row : table) {
            if (s.contains("'" + row[0] + "'") || s.contains("\"" + row[0] + "\"") || s.contains("id(" + row[0])) {
                Loader l = (Loader) row[1];
                // ForgeGradle 2 projects with FG 2.3 use "net.minecraftforge.gradle.forge"; FG 3+ apply "net.minecraftforge.gradle"
                ev.add("loader " + l + " from plugin id " + row[0]);
                if (l == Loader.FORGE && s.matches("(?s).*minecraft\\s*\\{[^}]*version\\s*=\\s*[\"']1\\.(7|8|9|10|11|12)[.\\d]*-.*")) {
                    ev.add("loader FORGE_LEGACY: minecraft { version = \"1.x-...\" } block");
                    return Loader.FORGE_LEGACY;
                }
                return l;
            }
        }
        return Loader.UNKNOWN;
    }

    /** Remove every {@code name { ... }} block, honouring nested braces. */
    static String withoutBlocks(String s, String name) {
        var out = new StringBuilder();
        Matcher m = Pattern.compile("\\b" + name + "\\s*\\{").matcher(s);
        int from = 0;
        while (m.find(from)) {
            out.append(s, from, m.start());
            int depth = 0, i = m.end() - 1;
            for (; i < s.length(); i++) {
                if (s.charAt(i) == '{') depth++;
                else if (s.charAt(i) == '}' && --depth == 0) break;
            }
            from = Math.min(i + 1, s.length());
        }
        return out.append(s.substring(from)).toString();
    }

    // ------------------------------------------------------------------ sources

    /**
     * gradle.properties of the project and of every parent up to {@code buildRoot} (or, if null, up to the first
     * directory with a settings file).
     */
    static Map<String, String> gradleProperties(Path dir, Path buildRoot) throws IOException {
        var chain = new ArrayList<Path>();
        Path stop = buildRoot == null ? null : buildRoot.toAbsolutePath().normalize();
        for (Path d = dir.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
            chain.add(d);
            if (stop != null ? d.equals(stop)
                    : Files.exists(d.resolve("settings.gradle")) || Files.exists(d.resolve("settings.gradle.kts"))) break;
            if (chain.size() > 6) break;
        }
        var props = new LinkedHashMap<String, String>();
        for (int i = chain.size() - 1; i >= 0; i--) {     // root first, project last (project wins)
            Path f = chain.get(i).resolve("gradle.properties");
            if (!Files.exists(f)) continue;
            var p = new Properties();
            try (var in = Files.newInputStream(f)) { p.load(in); }
            for (String k : p.stringPropertyNames()) props.put(k, p.getProperty(k).trim());
        }
        return props;
    }

    static String readScripts(Path dir) throws IOException {
        var sb = new StringBuilder();
        for (String n : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            Path f = dir.resolve(n);
            if (Files.exists(f)) sb.append(stripComments(Files.readString(f))).append('\n');
        }
        return sb.toString();
    }

    public static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)(^|[^:])//.*$", "$1");
    }

    /** [versions] table of gradle/libs.versions.toml (project or settings root). */
    static Map<String, String> versionCatalog(Path dir) throws IOException {
        var out = new LinkedHashMap<String, String>();
        for (Path d = dir.toAbsolutePath(), stop = d.getRoot(); d != null && !d.equals(stop); d = d.getParent()) {
            Path f = d.resolve("gradle/libs.versions.toml");
            if (Files.exists(f)) {
                boolean inVersions = false;
                for (String line : Files.readAllLines(f)) {
                    String t = line.strip();
                    if (t.startsWith("[")) { inVersions = t.equals("[versions]"); continue; }
                    if (!inVersions || t.startsWith("#") || !t.contains("=")) continue;
                    String k = t.substring(0, t.indexOf('=')).strip();
                    String v = t.substring(t.indexOf('=') + 1).strip();
                    if (v.startsWith("\"")) out.put(k, v.substring(1, v.indexOf('"', 1)));
                }
                break;
            }
            if (Files.exists(d.resolve("settings.gradle")) || Files.exists(d.resolve("settings.gradle.kts"))) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ extractors (return {value, evidence} or null)

    static String[] kv(Map<String, String> m, String where, String... keys) {
        for (String k : keys) if (m.get(k) != null && !m.get(k).isBlank()) return new String[]{m.get(k), where + " " + k};
        return null;
    }

    static String[] coord(String scripts, Map<String, String> props, String regex, String where) {
        Matcher m = Pattern.compile(regex).matcher(scripts);
        return m.find() ? resolved(m.group(1), props, where) : null;
    }

    static String[] assign(String scripts, Map<String, String> props, String regex, String where) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(scripts);
        if (!m.find()) return null;
        String v = m.group(1).replaceAll("^[\"']|[\"']$", "");
        if (!m.group(1).startsWith("\"") && !m.group(1).startsWith("'")) v = props.getOrDefault(v.replaceFirst("^project\\.", ""), v);
        return resolved(v, props, where);
    }

    /** net.minecraftforge:forge:1.20.1-47.4.28 */
    static String[] forgeCoordMc(String s, Map<String, String> props) {
        String[] v = coord(s, props, "net\\.minecraftforge:forge:([^\"')\\s]+)", "build script net.minecraftforge:forge");
        if (v == null || !v[0].contains("-")) return null;
        return new String[]{v[0].substring(0, v[0].indexOf('-')), v[1]};
    }

    static String[] forgeCoordLoader(String s, Map<String, String> props) {
        String[] v = coord(s, props, "net\\.minecraftforge:forge:([^\"')\\s]+)", "build script net.minecraftforge:forge");
        if (v == null) return null;
        return new String[]{v[0].contains("-") ? v[0].substring(v[0].indexOf('-') + 1) : v[0], v[1]};
    }

    /** ForgeGradle 2: minecraft { version = "1.12.2-14.23.5.2860" } */
    static String[] legacyForgeMc(String s, Map<String, String> props) {
        String[] v = assign(s, props, "minecraft\\s*\\{[^}]*?version\\s*=\\s*([\"'][^\"']+[\"']|[\\w.]+)", "build script minecraft { version }");
        if (v == null || !v[0].matches("1\\.\\d+(\\.\\d+)?-.*")) return null;
        return new String[]{v[0].substring(0, v[0].indexOf('-')), v[1]};
    }

    static String[] legacyForgeLoader(String s, Map<String, String> props) {
        String[] v = assign(s, props, "minecraft\\s*\\{[^}]*?version\\s*=\\s*([\"'][^\"']+[\"']|[\\w.]+)", "build script minecraft { version }");
        if (v == null || !v[0].contains("-")) return null;
        return new String[]{v[0].substring(v[0].indexOf('-') + 1), v[1]};
    }

    /** Resolve "${minecraft_version}", "$minecraft_version", "${project.minecraft_version}" against gradle.properties. */
    static String[] resolved(String raw, Map<String, String> props, String where) {
        Matcher m = Pattern.compile("\\$\\{?(?:project\\.|rootProject\\.)?([\\w.]+)\\}?").matcher(raw);
        var sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group())));
        m.appendTail(sb);
        String v = sb.toString();
        return v.contains("$") ? null : new String[]{v, where + (raw.equals(v) ? "" : " (" + raw + ")")};
    }

    static String first(List<String> ev, String what, String[]... candidates) {
        for (String[] c : candidates) if (c != null) { ev.add(what + " " + c[0] + " from " + c[1]); return c[0]; }
        return null;
    }

    // ------------------------------------------------------------------ version facts

    static String mcFromNeoForge(String neo) {
        String[] p = neo.split("[.-]");
        if (p.length < 2) return null;
        int major = Integer.parseInt(p[0]);
        if (major >= 26) return p[1].equals("0") ? String.valueOf(major) : major + "." + p[1];   // year-based versions
        return "1." + major + (p[1].equals("0") ? "" : "." + p[1]);
    }

    /** Java a given Minecraft version needs (Mojang's javaVersion in the version manifest). */
    public static int javaFor(String mc) {
        if (mc == null) return 21;
        String[] p = mc.split("\\.");
        try {
            if (!mc.startsWith("1.")) return 25;                  // 26.x (year-based) and later
            int minor = Integer.parseInt(p[1]);
            int patch = p.length > 2 ? Integer.parseInt(p[2].replaceAll("\\D.*", "")) : 0;
            if (minor <= 16) return 8;
            if (minor == 17) return 16;
            if (minor < 20 || (minor == 20 && patch <= 4)) return 17;
            return 21;                                            // 1.20.5 .. 1.21.x
        } catch (RuntimeException e) {
            return 21;
        }
    }

    /** Every Gradle (sub)project under {@code root} that looks like a mod project, for multi-project builds. */
    public static List<ProjectProfile> detectAll(Path root) throws IOException {
        var out = new ArrayList<ProjectProfile>();
        try (Stream<Path> s = Files.walk(root, 3)) {
            for (Path f : s.filter(p -> p.getFileName().toString().matches("build\\.gradle(\\.kts)?"))
                    .filter(p -> p.toString().replace('\\', '/').matches(".*?/(?!build/|\\.gradle/|run/)[^/]*/?.*")).toList()) {
                var prof = detect(f.getParent());
                if (prof.loader() != Loader.UNKNOWN || prof.minecraftVersion() != null) out.add(prof);
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            var p = detect(Path.of(a));
            System.out.println(a + "\n  -> " + p + (p.isComplete() ? "" : "   (incomplete)"));
            p.evidence().forEach(e -> System.out.println("     " + e));
        }
    }
}
