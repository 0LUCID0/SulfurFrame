import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Read-only inventory of every Minecraft instance on this PC: official launcher, Prism, CurseForge, Modrinth App and
 * mod-dev projects (Gradle run dirs). For each instance: game dir, Minecraft version, loader, mod count, options
 * (data version, key bindings) and worlds (name, last-opened version from level.dat, last played).
 *
 * Nothing is written anywhere except the optional JSON report.
 *
 * usage: java InstanceScanner.java [--dev-root <dir>]... [--json <file>]
 */
public class InstanceScanner {
    record World(String folder, String levelName, String version, int dataVersion, FileTime lastPlayed) {}
    record Instance(String launcher, String name, Path gameDir, String mcVersion, String loader, int mods,
                    int optionsDataVersion, int keyBindings, List<World> worlds) {}

    static final Path HOME = Path.of(System.getProperty("user.home"));
    static final Path APPDATA = Path.of(System.getenv().getOrDefault("APPDATA", HOME.resolve("AppData/Roaming").toString()));

    public static void main(String[] args) throws Exception {
        var devRoots = new ArrayList<Path>();
        Path json = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--dev-root")) devRoots.add(Path.of(args[++i]));
            else if (args[i].equals("--json")) json = Path.of(args[++i]);
        }
        var all = new ArrayList<Instance>();
        all.addAll(official());
        all.addAll(prism());
        all.addAll(curseforge());
        all.addAll(modrinth());
        for (var r : devRoots) all.addAll(dev(r));
        print(all);
        if (json != null) { Files.writeString(json, toJson(all)); System.out.println("\nJSON: " + json.toAbsolutePath()); }
    }

    // =================================================================== launchers

    static List<Instance> official() throws IOException {
        var out = new ArrayList<Instance>();
        Path mc = APPDATA.resolve(".minecraft");
        var dirs = new LinkedHashMap<Path, String>();   // game dir -> profile names
        for (var f : List.of("launcher_profiles.json", "launcher_profiles_microsoft_store.json")) {
            Path p = mc.resolve(f);
            if (!Files.exists(p)) continue;
            var root = (Map<?, ?>) Json.parse(Files.readString(p));
            var profiles = (Map<?, ?>) root.get("profiles");
            if (profiles == null) continue;
            for (var e : profiles.values()) {
                var prof = (Map<?, ?>) e;
                String gd = (String) prof.get("gameDir");
                Path dir = gd == null || gd.isBlank() ? mc : Path.of(gd);
                String name = str(prof.get("name"));
                if (name.isBlank()) name = str(prof.get("type"));
                if (name.isBlank()) name = str(prof.get("lastVersionId"));
                dirs.merge(dir, name, (a, b) -> a + ", " + b);
            }
        }
        if (dirs.isEmpty() && Files.isDirectory(mc)) dirs.put(mc, "default");
        for (var e : dirs.entrySet()) out.add(describe("Official", e.getValue(), e.getKey(), null, null));
        return out;
    }

    static List<Instance> prism() throws IOException {
        var out = new ArrayList<Instance>();
        Path base = APPDATA.resolve("PrismLauncher");
        if (!Files.isDirectory(base)) return out;
        Path instances = base.resolve("instances");
        Path cfg = base.resolve("prismlauncher.cfg");
        if (Files.exists(cfg)) for (var l : Files.readAllLines(cfg)) if (l.startsWith("InstanceDir=")) {
            Path p = Path.of(l.substring("InstanceDir=".length()).strip());
            instances = p.isAbsolute() ? p : base.resolve(p);
        }
        if (!Files.isDirectory(instances)) return out;
        try (var s = Files.list(instances)) {
            for (Path inst : s.filter(p -> Files.exists(p.resolve("instance.cfg"))).toList()) {
                String name = inst.getFileName().toString();
                for (var l : Files.readAllLines(inst.resolve("instance.cfg"))) if (l.startsWith("name=")) name = l.substring(5);
                String mcv = null, loader = null;
                Path pack = inst.resolve("mmc-pack.json");
                if (Files.exists(pack)) {
                    var comps = (List<?>) ((Map<?, ?>) Json.parse(Files.readString(pack))).get("components");
                    for (var c : comps) {
                        var m = (Map<?, ?>) c;
                        String uid = str(m.get("uid")), ver = str(m.get("version"));
                        if (uid.equals("net.minecraft")) mcv = ver;
                        else if (LOADERS.containsKey(uid)) loader = LOADERS.get(uid) + " " + ver;
                    }
                }
                Path gd = Files.isDirectory(inst.resolve(".minecraft")) ? inst.resolve(".minecraft") : inst.resolve("minecraft");
                out.add(describe("Prism", name, gd, mcv, loader == null ? "vanilla" : loader));
            }
        }
        return out;
    }

    static final Map<String, String> LOADERS = Map.of(
            "net.fabricmc.fabric-loader", "Fabric", "org.quiltmc.quilt-loader", "Quilt",
            "net.minecraftforge", "Forge", "net.neoforged", "NeoForge");

    static List<Instance> curseforge() throws IOException {
        var out = new ArrayList<Instance>();
        Path dir = HOME.resolve("curseforge/minecraft/Instances");
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (Path inst : s.filter(Files::isDirectory).toList()) {
                String mcv = null, loader = null, name = inst.getFileName().toString();
                Path meta = inst.resolve("minecraftinstance.json");
                if (Files.exists(meta)) {
                    var m = (Map<?, ?>) Json.parse(Files.readString(meta));
                    name = str(m.get("name")).isBlank() ? name : str(m.get("name"));
                    mcv = str(m.get("gameVersion"));
                    var bml = (Map<?, ?>) m.get("baseModLoader");
                    if (bml != null) loader = str(bml.get("name"));
                }
                out.add(describe("CurseForge", name, inst, mcv, loader));
            }
        }
        return out;
    }

    /** Modrinth keeps its metadata in app.db (SQLite); version/loader are read from the instance's own files instead. */
    static List<Instance> modrinth() throws IOException {
        var out = new ArrayList<Instance>();
        Path dir = APPDATA.resolve("ModrinthApp/profiles");
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (Path inst : s.filter(Files::isDirectory).toList()) out.add(describe("Modrinth", inst.getFileName().toString(), inst, null, null));
        }
        return out;
    }

    /** Mod projects: gradle.properties with minecraft_version (Loom/ForgeGradle/NeoGradle/MDG) and a run directory. */
    static List<Instance> dev(Path root) throws IOException {
        var out = new ArrayList<Instance>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.walk(root, 4)) {
            for (Path props : s.filter(p -> p.getFileName().toString().equals("gradle.properties"))
                    .filter(p -> !p.toString().contains(File.separator + ".gradle" + File.separator)).toList()) {
                var p = new Properties();
                try (var r = Files.newBufferedReader(props)) { p.load(r); } catch (Exception e) { continue; }
                String mcv = p.getProperty("minecraft_version", p.getProperty("mc_version"));
                if (mcv == null) continue;
                String loader = p.containsKey("loader_version") ? "Fabric " + p.getProperty("loader_version")
                        : p.containsKey("neo_version") ? "NeoForge " + p.getProperty("neo_version")
                        : p.containsKey("forge_version") ? "Forge " + p.getProperty("forge_version") : "?";
                Path proj = props.getParent();
                for (var runName : List.of("run", "runs/client", "run/client")) {
                    Path run = proj.resolve(runName);
                    if (Files.isDirectory(run) && (Files.exists(run.resolve("options.txt")) || Files.isDirectory(run.resolve("saves"))))
                        out.add(describe("Dev", proj.getFileName() + "/" + runName, run, mcv, loader));
                }
            }
        }
        return out;
    }

    // =================================================================== per instance

    static final Pattern LOG_FABRIC = Pattern.compile("Loading Minecraft ([0-9]\\S*) with (Fabric|Quilt) Loader ([0-9]\\S*)");
    static final Pattern LOG_FORGE = Pattern.compile("(?:--fml\\.mcVersion|Minecraft Version:?) ?,? ?([0-9][0-9.]+)");
    static final Pattern LOG_FORGE_VER = Pattern.compile("(?:--fml\\.(neoForgeVersion|forgeVersion)),? ?([0-9][^ ,\\]]*)");
    static final Pattern LOG_FORGE_JAR = Pattern.compile("(neoforge|forge)-([0-9][0-9.]*)-([0-9][0-9.]*[0-9])");

    static Instance describe(String launcher, String name, Path gd, String mcv, String loader) {
        int mods = countJars(gd.resolve("mods"));
        // version/loader from the last log when the launcher's metadata did not tell us
        if (mcv == null || loader == null) {
            String log = head(gd.resolve("logs/latest.log"), 3000);
            var m = LOG_FABRIC.matcher(log);
            if (m.find()) { mcv = mcv == null ? m.group(1) : mcv; loader = loader == null ? m.group(2) + " " + m.group(3) : loader; }
            else if (LOG_FORGE_JAR.matcher(log).find()) {
                var j = LOG_FORGE_JAR.matcher(log); j.find();
                if (mcv == null) mcv = j.group(2);
                if (loader == null) loader = (j.group(1).equals("neoforge") ? "NeoForge " : "Forge ") + j.group(3);
            }
            else {
                var f = LOG_FORGE.matcher(log);
                if (f.find() && mcv == null) mcv = f.group(1);
                var fv = LOG_FORGE_VER.matcher(log);
                if (fv.find() && loader == null) loader = (fv.group(1).startsWith("neo") ? "NeoForge " : "Forge ") + fv.group(2);
            }
        }
        int optData = -1, keys = 0;
        Path opts = gd.resolve("options.txt");
        if (Files.exists(opts)) {
            try {
                for (var l : Files.readAllLines(opts, StandardCharsets.ISO_8859_1)) {
                    if (l.startsWith("version:")) optData = parseInt(l.substring(8));
                    else if (l.startsWith("key_")) keys++;
                }
            } catch (IOException ignored) {}
        }
        if (mcv == null && optData > 0) mcv = DATA_VERSIONS.getOrDefault(optData, "data " + optData);
        if (loader == null) loader = mods > 0 ? "?" : "vanilla";
        return new Instance(launcher, name, gd, mcv == null ? "?" : mcv, loader, mods, optData, keys, worlds(gd.resolve("saves")));
    }

    /** A few data versions for instances that only have an options.txt to go on. */
    static final Map<Integer, String> DATA_VERSIONS = Map.ofEntries(
            Map.entry(1343, "1.12.2"), Map.entry(1976, "1.14.4"), Map.entry(2230, "1.15.2"), Map.entry(2586, "1.16.5"),
            Map.entry(2730, "1.17.1"), Map.entry(2975, "1.18.2"), Map.entry(3120, "1.19.2"), Map.entry(3337, "1.19.4"),
            Map.entry(3465, "1.20.1"), Map.entry(3700, "1.20.4"), Map.entry(3839, "1.20.6"), Map.entry(3955, "1.21.1"),
            Map.entry(4189, "1.21.4"));

    static List<World> worlds(Path saves) {
        var out = new ArrayList<World>();
        if (!Files.isDirectory(saves)) return out;
        try (var s = Files.list(saves)) {
            for (Path w : s.filter(p -> Files.exists(p.resolve("level.dat"))).sorted().toList()) {
                String levelName = w.getFileName().toString(), version = "?";
                int dv = -1;
                try {
                    var data = (Map<?, ?>) ((Map<?, ?>) Nbt.read(w.resolve("level.dat"))).get("Data");
                    if (data != null) {
                        if (data.get("LevelName") instanceof String ln) levelName = ln;
                        if (data.get("DataVersion") instanceof Integer d) dv = d;
                        if (data.get("Version") instanceof Map<?, ?> v && v.get("Name") instanceof String n) version = n;
                    }
                } catch (Exception ignored) {}
                out.add(new World(w.getFileName().toString(), levelName, version, dv, Files.getLastModifiedTime(w.resolve("level.dat"))));
            }
        } catch (IOException ignored) {}
        return out;
    }

    // =================================================================== output

    static void print(List<Instance> all) {
        String last = "";
        for (var i : all) {
            if (!i.launcher().equals(last)) { System.out.println("\n== " + i.launcher()); last = i.launcher(); }
            System.out.printf("  %-28s %-10s %-22s mods %3d  keys %3d  worlds %d%n", trim(i.name(), 28), i.mcVersion(),
                    trim(i.loader(), 22), i.mods(), i.keyBindings(), i.worlds().size());
            System.out.println("      " + i.gameDir());
            for (var w : i.worlds())
                System.out.printf("      - %-30s last opened in %-10s (data %d)  %s%n",
                        trim(w.levelName().equals(w.folder()) ? w.folder() : w.levelName() + " [" + w.folder() + "]", 30), w.version(),
                        w.dataVersion(), w.lastPlayed().toString().substring(0, 10)
                        // a world saved by a newer game than this instance last ran cannot be opened safely here
                        + (i.optionsDataVersion() > 0 && w.dataVersion() > i.optionsDataVersion()
                           ? "  ⚠ newer than this instance (" + i.mcVersion() + ")" : ""));
        }
        int worlds = all.stream().mapToInt(i -> i.worlds().size()).sum();
        System.out.println("\n" + all.size() + " instances, " + worlds + " worlds");
    }

    static String toJson(List<Instance> all) {
        var sb = new StringBuilder("[\n");
        for (int k = 0; k < all.size(); k++) {
            var i = all.get(k);
            sb.append("  {\"launcher\":").append(q(i.launcher())).append(",\"name\":").append(q(i.name()))
              .append(",\"gameDir\":").append(q(i.gameDir().toString())).append(",\"mcVersion\":").append(q(i.mcVersion()))
              .append(",\"loader\":").append(q(i.loader())).append(",\"mods\":").append(i.mods())
              .append(",\"optionsDataVersion\":").append(i.optionsDataVersion()).append(",\"keyBindings\":").append(i.keyBindings())
              .append(",\"worlds\":[");
            for (int j = 0; j < i.worlds().size(); j++) {
                var w = i.worlds().get(j);
                sb.append(j == 0 ? "" : ",").append("{\"folder\":").append(q(w.folder())).append(",\"levelName\":").append(q(w.levelName()))
                  .append(",\"version\":").append(q(w.version())).append(",\"dataVersion\":").append(w.dataVersion())
                  .append(",\"lastPlayed\":").append(q(w.lastPlayed().toString())).append("}");
            }
            sb.append("]}").append(k == all.size() - 1 ? "\n" : ",\n");
        }
        return sb.append("]\n").toString();
    }

    // =================================================================== helpers

    static int countJars(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) { return (int) s.filter(p -> p.toString().endsWith(".jar")).count(); } catch (IOException e) { return 0; }
    }

    static String head(Path p, int lines) {
        if (!Files.exists(p)) return "";
        try (var r = Files.newBufferedReader(p, StandardCharsets.ISO_8859_1)) {   // logs are not always UTF-8; patterns are ASCII
            var sb = new StringBuilder(); String l; int n = 0;
            while ((l = r.readLine()) != null && n++ < lines) sb.append(l).append('\n');
            return sb.toString();
        } catch (IOException e) { return ""; }
    }

    static int parseInt(String s) { try { return Integer.parseInt(s.strip()); } catch (NumberFormatException e) { return -1; } }
    static String str(Object o) { return o == null ? "" : o.toString(); }
    static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
    static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    /** Minimal NBT reader (gzip or raw): enough for level.dat. Lists become Lists, compounds Maps. */
    static final class Nbt {
        static Object read(Path p) throws IOException {
            byte[] raw = Files.readAllBytes(p);
            InputStream in = new ByteArrayInputStream(raw);
            if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) in = new GZIPInputStream(in);
            var d = new DataInputStream(new BufferedInputStream(in));
            int type = d.readByte();
            d.readUTF();   // root name
            return payload(d, type);
        }

        static Object payload(DataInputStream d, int t) throws IOException {
            switch (t) {
                case 1: return d.readByte();
                case 2: return d.readShort();
                case 3: return d.readInt();
                case 4: return d.readLong();
                case 5: return d.readFloat();
                case 6: return d.readDouble();
                case 7: { int n = d.readInt(); d.skipNBytes(n); return null; }
                case 8: return d.readUTF();
                case 9: { int et = d.readByte(), n = d.readInt(); var l = new ArrayList<Object>(); for (int i = 0; i < n; i++) l.add(payload(d, et)); return l; }
                case 10: { var m = new LinkedHashMap<String, Object>();
                    while (true) { int ct = d.readByte(); if (ct == 0) return m; String k = d.readUTF(); m.put(k, payload(d, ct)); } }
                case 11: { int n = d.readInt(); d.skipNBytes(4L * n); return null; }
                case 12: { int n = d.readInt(); d.skipNBytes(8L * n); return null; }
                default: throw new IOException("bad NBT tag " + t);
            }
        }
    }

    /** Minimal JSON reader. */
    static final class Json {
        private final String s; private int i;
        private Json(String s) { this.s = s; }
        static Object parse(String s) { return new Json(s).value(); }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private Object value() {
            ws(); char c = s.charAt(i);
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
            while (true) { char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') { char e = s.charAt(i++);
                    switch (e) { case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e); } }
                else b.append(c); }
        }
    }
}
