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
        if (a.length < 2) { System.err.println("usage: fetch|launch <version> ..."); System.exit(2); }
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
