import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Download (from Mojang, SHA-1 verified) and launch a vanilla Minecraft client for versions that are not in the local
 * Gradle/Loom cache. No sound assets are fetched (the game runs without them).
 *
 *   java McFetch.java fetch  1.12.2
 *   java McFetch.java launch 1.12.2 [--agent path/sf-agent.jar[=args]] [-- extra game args]
 *
 * Files go to %TEMP%/sf-mc-dl (downloads) and %TEMP%/sf-mc-game-<version> (game dir); the log to %TEMP%/sf-mc-<version>.log.
 * The version manifest comes from the local Loom cache (~/.gradle/caches/fabric-loom/versions_manifest.json).
 */
public class McFetch {
    static final Path TMP = Path.of(System.getenv().getOrDefault("TEMP", System.getProperty("java.io.tmpdir")));
    static final Path DL = TMP.resolve("sf-mc-dl");
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public static void main(String[] a) throws Exception {
        if (a.length < 2) { System.err.println("usage: fetch|launch <version> ... | launch-installed <dir> <versionId> ..."); System.exit(2); }
        if (a[0].equals("launch-installed")) { launchInstalled(Path.of(a[1]), a[2], Arrays.copyOfRange(a, 3, a.length)); return; }
        String version = a[1];
        Map<String, Object> v = fetch(version);
        if (a[0].equals("launch")) launch(version, v, Arrays.copyOfRange(a, 2, a.length));
    }

    // ------------------------------------------------------------------ fetch

    @SuppressWarnings("unchecked")
    static Map<String, Object> fetch(String version) throws Exception {
        Path manifest = Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom", "versions_manifest.json");
        var versions = (List<Object>) ((Map<String, Object>) Json.parse(Files.readString(manifest))).get("versions");
        Map<String, Object> entry = null;
        for (Object o : versions) if (version.equals(((Map<String, Object>) o).get("id"))) entry = (Map<String, Object>) o;
        if (entry == null) throw new IllegalArgumentException("version " + version + " not in " + manifest);

        Path vdir = DL.resolve(version);
        Path vjson = download((String) entry.get("url"), (String) entry.get("sha1"), vdir.resolve(version + ".json"));
        var v = (Map<String, Object>) Json.parse(Files.readString(vjson));

        var client = (Map<String, Object>) ((Map<String, Object>) v.get("downloads")).get("client");
        download((String) client.get("url"), (String) client.get("sha1"), vdir.resolve("client.jar"));

        int n = 0;
        for (Object o : (List<Object>) v.get("libraries")) {
            var lib = (Map<String, Object>) o;
            if (!allowed((List<Object>) lib.get("rules"))) continue;
            var dls = (Map<String, Object>) lib.get("downloads");
            if (dls == null) continue;
            var art = (Map<String, Object>) dls.get("artifact");
            if (art != null) { downloadLib(art); n++; }
            String nativeCls = nativeClassifier(lib);
            if (nativeCls != null) {
                var cls = (Map<String, Object>) ((Map<String, Object>) dls.get("classifiers")).get(nativeCls);
                if (cls != null) { downloadLib(cls); n++; }
            }
        }
        var ai = (Map<String, Object>) v.get("assetIndex");
        download((String) ai.get("url"), (String) ai.get("sha1"), DL.resolve("assets/indexes/" + ai.get("id") + ".json"));
        System.out.println("[fetch] " + version + ": client + " + n + " library files + asset index (sounds/lang not fetched)");
        return v;
    }

    @SuppressWarnings("unchecked")
    static String nativeClassifier(Map<String, Object> lib) {
        var natives = (Map<String, Object>) lib.get("natives");
        if (natives == null || natives.get("windows") == null) return null;
        return ((String) natives.get("windows")).replace("${arch}", "64");
    }

    @SuppressWarnings("unchecked")
    static boolean allowed(List<Object> rules) {
        if (rules == null) return true;
        boolean ok = false;
        for (Object o : rules) {
            var r = (Map<String, Object>) o;
            var os = (Map<String, Object>) r.get("os");
            if (r.get("features") != null) continue;
            if (os == null || "windows".equals(os.get("name"))) ok = "allow".equals(r.get("action"));
        }
        return ok;
    }

    static Path downloadLib(Map<String, Object> d) throws Exception {
        return download((String) d.get("url"), (String) d.get("sha1"), DL.resolve("libraries").resolve((String) d.get("path")));
    }

    static Path download(String url, String sha1, Path to) throws Exception {
        if (Files.exists(to) && sha1(to).equalsIgnoreCase(sha1)) return to;
        Files.createDirectories(to.getParent());
        var resp = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(to));
        if (resp.statusCode() != 200) throw new IOException(url + " -> HTTP " + resp.statusCode());
        String got = sha1(to);
        if (!got.equalsIgnoreCase(sha1)) { Files.delete(to); throw new IOException("SHA-1 mismatch for " + url + ": " + got); }
        return to;
    }

    static String sha1(Path p) throws Exception {
        var md = MessageDigest.getInstance("SHA-1");
        try (var in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int r; while ((r = in.read(b)) > 0) md.update(b, 0, r); }
        return HexFormat.of().formatHex(md.digest());
    }

    // ------------------------------------------------------------------ launch

    @SuppressWarnings("unchecked")
    static void launch(String version, Map<String, Object> v, String[] opts) throws Exception {
        Path vdir = DL.resolve(version), natives = vdir.resolve("natives"), gameDir = TMP.resolve("sf-mc-game-" + version);
        Files.createDirectories(natives);
        Files.createDirectories(gameDir);
        var cp = new ArrayList<String>();
        for (Object o : (List<Object>) v.get("libraries")) {
            var lib = (Map<String, Object>) o;
            if (!allowed((List<Object>) lib.get("rules"))) continue;
            var dls = (Map<String, Object>) lib.get("downloads");
            if (dls == null) continue;
            var art = (Map<String, Object>) dls.get("artifact");
            if (art != null) cp.add(DL.resolve("libraries").resolve((String) art.get("path")).toString());
            String nc = nativeClassifier(lib);
            if (nc != null) {
                var cls = (Map<String, Object>) ((Map<String, Object>) dls.get("classifiers")).get(nc);
                if (cls != null) extract(DL.resolve("libraries").resolve((String) cls.get("path")), natives);
            }
        }
        cp.add(vdir.resolve("client.jar").toString());

        var jv = (Map<String, Object>) v.get("javaVersion");
        int major = jv == null ? 8 : ((Number) jv.get("majorVersion")).intValue();
        String java = switch (major) {
            case 8 -> "C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin/java.exe";
            case 16, 17 -> "C:/Program Files/Eclipse Adoptium/jdk-17.0.16.8-hotspot/bin/java.exe";
            case 21 -> "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin/java.exe";
            default -> Path.of(System.getProperty("user.home"), ".jdks", "temurin-25.0.4.1", "bin", "java.exe").toString();
        };

        var cmd = new ArrayList<>(List.of(java, "-Xmx2G", "-Djava.library.path=" + natives));
        var extra = new ArrayList<String>();
        for (int i = 0; i < opts.length; i++) {
            if (opts[i].equals("--agent")) cmd.add("-javaagent:" + opts[++i]);
            else if (opts[i].equals("--")) { extra.addAll(Arrays.asList(opts).subList(i + 1, opts.length)); break; }
        }
        cmd.addAll(List.of("-cp", String.join(File.pathSeparator, cp), (String) v.get("mainClass")));
        var vars = Map.of("auth_player_name", "SFDev", "version_name", version, "game_directory", gameDir.toString(),
                "assets_root", DL.resolve("assets").toString(), "game_assets", DL.resolve("assets").toString(),
                "assets_index_name", (String) ((Map<String, Object>) v.get("assetIndex")).get("id"),
                "auth_uuid", "00000000000000000000000000000000", "auth_access_token", "0", "user_type", "legacy",
                "version_type", "release");
        for (String arg : gameArgs(v)) {
            String s = arg;
            for (var e : vars.entrySet()) s = s.replace("${" + e.getKey() + "}", e.getValue());
            if (s.equals("${user_properties}")) s = "{}";
            if (s.contains("${")) continue;   // e.g. ${clientid}, ${auth_xuid}: drop value-less placeholders
            cmd.add(s);
        }
        // drop flags whose value was a dropped placeholder ("--xuid" followed by another flag or nothing)
        for (int i = cmd.size() - 1; i >= 0; i--)
            if (cmd.get(i).startsWith("--") && (i == cmd.size() - 1 || cmd.get(i + 1).startsWith("--"))
                    && List.of("--clientId", "--xuid", "--quickPlayPath").contains(cmd.get(i))) cmd.remove(i);
        cmd.addAll(extra);

        Path log = TMP.resolve("sf-mc-" + version + ".log");
        var p = new ProcessBuilder(cmd).directory(TMP.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        System.out.println("[launch] " + version + " pid=" + p.pid() + " java " + major + " log=" + log);
    }

    // ------------------------------------------------------------------ launch an installed profile (Forge/NeoForge)

    /**
     * Launch a version installed by a mod loader installer into {@code dir} (launcher layout: versions/, libraries/).
     * Follows inheritsFrom, merges libraries and arguments, applies Windows rules, substitutes ${...} like the official
     * launcher. The version jar is left off the class path: modern Forge/NeoForge load the patched client from a
     * library and list ${version_name}.jar in their ignore list.
     */
    @SuppressWarnings("unchecked")
    static void launchInstalled(Path dir, String id, String[] opts) throws Exception {
        var child = (Map<String, Object>) Json.parse(Files.readString(dir.resolve("versions/" + id + "/" + id + ".json")));
        var chain = new ArrayList<Map<String, Object>>();
        chain.add(child);
        for (var cur = child; cur.get("inheritsFrom") != null; ) {
            String p = (String) cur.get("inheritsFrom");
            Path pj = dir.resolve("versions/" + p + "/" + p + ".json");
            if (!Files.exists(pj)) {   // Forge's installer leaves only the vanilla jar, not its json
                var versions = (List<Object>) ((Map<String, Object>) Json.parse(Files.readString(Path.of(System.getProperty("user.home"),
                        ".gradle", "caches", "fabric-loom", "versions_manifest.json")))).get("versions");
                for (Object o : versions) {
                    var e = (Map<String, Object>) o;
                    if (p.equals(e.get("id"))) download((String) e.get("url"), (String) e.get("sha1"), pj);
                }
            }
            cur = (Map<String, Object>) Json.parse(Files.readString(pj));
            chain.add(cur);
        }
        Path libDir = dir.resolve("libraries"), natives = dir.resolve("natives"), gameDir = TMP.resolve("sf-mc-game-" + id);
        Files.createDirectories(natives);
        Files.createDirectories(gameDir);

        // libraries: child first, then parents; skip duplicates (same group:artifact:classifier) and foreign natives
        var seen = new HashSet<String>();
        var cp = new ArrayList<String>();
        boolean legacyJar = false;
        for (var v : chain) for (Object o : (List<Object>) v.getOrDefault("libraries", List.of())) {
            var lib = (Map<String, Object>) o;
            if (!allowed((List<Object>) lib.get("rules"))) continue;
            String name = (String) lib.get("name");
            String[] g = name.split(":");
            String key = g[0] + ":" + g[1] + (g.length > 3 ? ":" + g[3] : "");
            if (g.length > 3 && (g[3].contains("arm64") || g[3].contains("x86") || g[3].contains("linux") || g[3].contains("macos"))) continue;
            if (!seen.add(key)) continue;
            String nc = nativeClassifier(lib);
            var dls = (Map<String, Object>) lib.get("downloads");
            if (nc != null && dls != null && dls.get("classifiers") != null) {   // LWJGL 2 style natives
                var cls = (Map<String, Object>) ((Map<String, Object>) dls.get("classifiers")).get(nc);
                if (cls != null) {
                    Path nj = libDir.resolve((String) cls.get("path"));
                    if (!Files.exists(nj)) download((String) cls.get("url"), (String) cls.get("sha1"), nj);
                    extract(nj, natives);
                }
            }
            var art = dls == null ? null : (Map<String, Object>) dls.get("artifact");
            Path jar = art != null ? libDir.resolve((String) art.get("path")) : libDir.resolve(mavenPath(name));
            // installers fetch only their own libraries; the vanilla ones are the launcher's job, so fetch them here
            if (!Files.exists(jar) && art != null && art.get("url") != null && !((String) art.get("url")).isEmpty())
                download((String) art.get("url"), (String) art.get("sha1"), jar);
            if (Files.exists(jar)) cp.add(jar.toString());
            else if (dls == null || art != null) System.err.println("[launch] missing " + jar);
        }
        // old (LaunchWrapper) Forge needs the vanilla jar on the class path; modern ones must not have it
        String mainClass = (String) child.get("mainClass");
        if (mainClass.contains("launchwrapper")) {
            String base = (String) chain.get(chain.size() - 1).get("id");
            cp.add(dir.resolve("versions/" + base + "/" + base + ".jar").toString());
            legacyJar = true;
        }

        var root = chain.get(chain.size() - 1);
        var jvRoot = (Map<String, Object>) root.get("javaVersion");
        int major = jvRoot == null ? 8 : ((Number) jvRoot.get("majorVersion")).intValue();
        String java = javaFor(major);
        var assetIndex = (Map<String, Object>) root.get("assetIndex");
        var vars = new HashMap<String, String>();
        vars.put("natives_directory", natives.toString());
        vars.put("launcher_name", "sulfurframe");
        vars.put("launcher_version", "1");
        vars.put("classpath", String.join(File.pathSeparator, cp));
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", libDir.toString());
        vars.put("version_name", id);
        vars.put("game_directory", gameDir.toString());
        Path loomAssets = Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom", "assets");
        vars.put("assets_root", (Files.isDirectory(loomAssets) ? loomAssets : DL.resolve("assets")).toString());
        vars.put("game_assets", vars.get("assets_root"));
        vars.put("assets_index_name", assetIndex == null ? "legacy" : (String) assetIndex.get("id"));
        vars.put("auth_player_name", "SFDev");
        vars.put("auth_uuid", "00000000000000000000000000000000");
        vars.put("auth_access_token", "0");
        vars.put("auth_session", "0");
        vars.put("user_type", "legacy");
        vars.put("user_properties", "{}");
        vars.put("version_type", "release");

        var jvm = new ArrayList<String>(List.of("-Xmx2G"));
        var game = new ArrayList<String>();
        boolean hasCp = false;
        for (int i = chain.size() - 1; i >= 0; i--) {   // parent args first, like the launcher
            var v = chain.get(i);
            var args = (Map<String, Object>) v.get("arguments");
            if (args != null) {
                for (String s : ruledArgs((List<Object>) args.getOrDefault("jvm", List.of()))) jvm.add(s);
                for (String s : ruledArgs((List<Object>) args.getOrDefault("game", List.of()))) game.add(s);
            } else if (v.get("minecraftArguments") != null && i == 0) {
                game.addAll(Arrays.asList(((String) v.get("minecraftArguments")).split(" ")));
            }
        }
        if (child.get("arguments") == null && child.get("minecraftArguments") == null && root.get("minecraftArguments") != null)
            game.addAll(Arrays.asList(((String) root.get("minecraftArguments")).split(" ")));
        for (String s : jvm) if (s.equals("-cp") || s.equals("${classpath}")) hasCp = true;
        if (!hasCp) { jvm.add("-Djava.library.path=${natives_directory}"); jvm.add("-cp"); jvm.add("${classpath}"); }

        var cmd = new ArrayList<String>(List.of(java));
        var extra = new ArrayList<String>();
        for (int i = 0; i < opts.length; i++) {
            if (opts[i].equals("--agent")) cmd.add("-javaagent:" + opts[++i]);
            else if (opts[i].equals("--")) { extra.addAll(Arrays.asList(opts).subList(i + 1, opts.length)); break; }
        }
        if (major >= 22) cmd.add("--enable-native-access=ALL-UNNAMED");
        for (String s : jvm) cmd.add(subst(s, vars));
        cmd.add(mainClass);
        for (String s : game) { String r = subst(s, vars); if (!r.contains("${")) cmd.add(r); }
        cmd.add("--width"); cmd.add("854"); cmd.add("--height"); cmd.add("480");
        cmd.addAll(extra);

        Path log = TMP.resolve("sf-mc-" + id + ".log");
        var p = new ProcessBuilder(cmd).directory(TMP.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        System.out.println("[launch] " + id + " pid=" + p.pid() + " java " + major + (legacyJar ? " (LaunchWrapper)" : "") + " log=" + log);
    }

    @SuppressWarnings("unchecked")
    static List<String> ruledArgs(List<Object> list) {
        var out = new ArrayList<String>();
        for (Object o : list) {
            if (o instanceof String s) { out.add(s); continue; }
            var m = (Map<String, Object>) o;
            if (!allowedArg((List<Object>) m.get("rules"))) continue;
            Object val = m.get("value");
            if (val instanceof String s) out.add(s);
            else for (Object x : (List<Object>) val) out.add((String) x);
        }
        return out;
    }

    /** Argument rules: no feature flags (demo, custom resolution, quick play ...), Windows x64 only. */
    @SuppressWarnings("unchecked")
    static boolean allowedArg(List<Object> rules) {
        if (rules == null) return true;
        boolean ok = false;
        for (Object o : rules) {
            var r = (Map<String, Object>) o;
            if (r.get("features") != null) return false;
            var os = (Map<String, Object>) r.get("os");
            boolean match = os == null || (("windows".equals(os.get("name")) || os.get("name") == null) && !"x86".equals(os.get("arch")));
            if (match) ok = "allow".equals(r.get("action"));
        }
        return ok;
    }

    static String subst(String s, Map<String, String> vars) {
        for (var e : vars.entrySet()) s = s.replace("${" + e.getKey() + "}", e.getValue());
        return s;
    }

    static String mavenPath(String name) {
        String[] g = name.split(":");
        String ext = "jar", ver = g[2];
        if (ver.contains("@")) { ext = ver.substring(ver.indexOf('@') + 1); ver = ver.substring(0, ver.indexOf('@')); }
        String cls = g.length > 3 ? "-" + g[3] : "";
        return g[0].replace('.', '/') + "/" + g[1] + "/" + ver + "/" + g[1] + "-" + ver + cls + "." + ext;
    }

    static String javaFor(int major) {
        return switch (major) {
            case 8 -> "C:/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin/java.exe";
            case 16, 17 -> "C:/Program Files/Eclipse Adoptium/jdk-17.0.16.8-hotspot/bin/java.exe";
            case 21 -> "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin/java.exe";
            default -> Path.of(System.getProperty("user.home"), ".jdks", "temurin-25.0.4.1", "bin", "java.exe").toString();
        };
    }

    @SuppressWarnings("unchecked")
    static List<String> gameArgs(Map<String, Object> v) {
        if (v.get("minecraftArguments") != null) return Arrays.asList(((String) v.get("minecraftArguments")).split(" "));
        var out = new ArrayList<String>();
        var args = (Map<String, Object>) v.get("arguments");
        for (Object o : (List<Object>) args.get("game")) if (o instanceof String s) out.add(s);   // skip rule-gated entries
        return out;
    }

    static void extract(Path jar, Path dir) throws IOException {
        try (var z = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.isDirectory() || e.getName().startsWith("META-INF")) continue;
                Path out = dir.resolve(Path.of(e.getName()).getFileName().toString());
                if (!Files.exists(out)) Files.copy(z, out);
            }
        }
    }

    /** Minimal JSON reader (objects, arrays, strings, numbers, booleans, null). */
    static final class Json {
        private final String s; private int i;
        private Json(String s) { this.s = s; }
        static Object parse(String s) { return new Json(s).value(); }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') { i++; var m = new LinkedHashMap<String, Object>(); ws(); if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = str(); ws(); i++; m.put(k, value()); ws(); if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; var l = new ArrayList<Object>(); ws(); if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }
        private String str() {
            var b = new StringBuilder(); i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') { char e = s.charAt(i++);
                    switch (e) { case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e); } }
                else b.append(c);
            }
        }
    }
}
