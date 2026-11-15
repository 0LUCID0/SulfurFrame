import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Merge-sync of key bindings and chosen options between Minecraft instances through one master profile.
 *
 * Why not just link options.txt: the game rewrites the file with only the options it knows, so one run of an older
 * version (or of an instance without mod X) silently deletes everything else. Here the master keeps the union, and
 * each instance only ever gets the shared keys merged into its own file; everything else in that file is left as is.
 *
 *   pull <gameDir>...   instance -> master   (only shared keys; newer file wins over older master entries)
 *   push <gameDir>...   master   -> instance (only shared keys; the instance's other lines and its version: line stay)
 *   status <gameDir>... what push would change
 *
 * Writes nothing unless --apply is given. Before writing an options.txt it saves options.txt.sf-bak-<time> next to it.
 * Key bindings are stored in the modern form (key.keyboard.w) and converted for pre-1.13 files (LWJGL 2 key codes).
 *
 * usage: java OptionsSync.java pull|push|status [--master <dir>] [--apply] <gameDir>...
 */
public class OptionsSync {
    /** Shared by default: controls and comfort. Graphics/video stay per instance (dev and play want different ones). */
    static final List<String> DEFAULT_SHARE = List.of(
            "key_*", "mouseSensitivity", "invertYMouse", "invertXMouse", "autoJump", "toggleCrouch", "toggleSprint",
            "toggleAttack", "toggleUse", "discrete_mouse_scroll", "mouseWheelSensitivity", "rawMouseInput", "lang",
            "soundCategory_*", "chatScale", "chatWidth", "chatOpacity", "chatLineSpacing", "narrator", "showSubtitles",
            "fov", "bobView", "hideServerAddress", "operatorItemsTab");

    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    record Entry(String value, long modified, String source) {}

    public static void main(String[] a) throws Exception {
        if (a.length < 2) { System.err.println("usage: pull|push|status [--master dir] [--apply] <gameDir>..."); System.exit(2); }
        String cmd = a[0];
        Path master = Path.of(System.getProperty("user.home"), ".sulfurframe", "profile");
        boolean apply = false;
        var dirs = new ArrayList<Path>();
        for (int i = 1; i < a.length; i++) {
            switch (a[i]) {
                case "--master" -> master = Path.of(a[++i]);
                case "--apply" -> apply = true;
                default -> dirs.add(Path.of(a[i]));
            }
        }
        var sync = new OptionsSync(master);
        switch (cmd) {
            case "pull" -> sync.pullAll(dirs);
            case "push", "status" -> { for (Path d : dirs) sync.push(d, cmd.equals("push") && apply); }
            default -> throw new IllegalArgumentException(cmd);
        }
        if (cmd.equals("pull")) {
            if (apply) sync.saveMaster();
            else System.out.println("(dry run: master not written; add --apply)");
        } else if (cmd.equals("push") && !apply) System.out.println("(dry run: nothing written; add --apply)");
    }

    // =================================================================== master

    final Path masterDir;
    final Map<String, Entry> master = new TreeMap<>();
    final List<String> share;

    OptionsSync(Path masterDir) throws IOException {
        this.masterDir = masterDir;
        Path f = masterDir.resolve("options.master");
        if (Files.exists(f)) {
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                // key \t value \t modifiedMillis \t source
                String[] p = l.split("\t", 4);
                if (p.length == 4) master.put(p[0], new Entry(p[1], Long.parseLong(p[2]), p[3]));
            }
        }
        Path sf = masterDir.resolve("share.txt");
        share = Files.exists(sf)
                ? Files.readAllLines(sf).stream().map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList()
                : DEFAULT_SHARE;
    }

    void saveMaster() throws IOException {
        Files.createDirectories(masterDir);
        var sb = new StringBuilder();
        for (var e : master.entrySet())
            sb.append(e.getKey()).append('\t').append(e.getValue().value()).append('\t')
              .append(e.getValue().modified()).append('\t').append(e.getValue().source()).append('\n');
        Files.writeString(masterDir.resolve("options.master"), sb.toString(), StandardCharsets.UTF_8);
        Path sf = masterDir.resolve("share.txt");
        if (!Files.exists(sf)) Files.writeString(sf, "# options shared across instances (glob: *)\n" + String.join("\n", DEFAULT_SHARE) + "\n");
        System.out.println("master written: " + masterDir.resolve("options.master") + " (" + master.size() + " entries)");
    }

    boolean shared(String key) { return matches(share, key) && !matches(keep, key); }

    static boolean matches(List<String> globs, String key) {
        for (String g : globs) if (g.endsWith("*") ? key.startsWith(g.substring(0, g.length() - 1)) : key.equals(g)) return true;
        return false;
    }

    /** Per-instance exceptions: <gameDir>/sulfurframe-keep.txt lists keys (globs) this instance keeps to itself. */
    List<String> keep = List.of();

    void loadKeep(Path gameDir) throws IOException {
        Path f = gameDir.resolve("sulfurframe-keep.txt");
        keep = Files.exists(f)
                ? Files.readAllLines(f).stream().map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList()
                : List.of();
    }

    // =================================================================== pull / push

    /**
     * Three-way merge. options.txt is rewritten on every game start, so a file's mtime says nothing about when the
     * user changed a setting. Instead each instance has a baseline: the shared values it held right after our last
     * push. A value that differs from its baseline is a real edit made in that instance and is propagated.
     * Keys nobody edited (and the very first sync, when there are no baselines) are decided by majority vote.
     */
    void pullAll(List<Path> dirs) throws IOException {
        record Edit(String value, long mtime, Path dir) {}
        var edits = new TreeMap<String, List<Edit>>();
        var votes = new TreeMap<String, Map<String, long[]>>();   // key -> value -> {count, latest mtime}
        for (Path gameDir : dirs) {
            loadKeep(gameDir);
            Path f = gameDir.resolve("options.txt");
            if (!Files.exists(f)) { System.out.println("skip (no options.txt): " + gameDir); continue; }
            long mtime = Files.getLastModifiedTime(f).toMillis();
            Map<String, String> base = loadBaseline(gameDir);
            var values = sharedValues(read(f));
            int edited = 0;
            for (var e : values.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                if (base != null && base.containsKey(k) && !base.get(k).equals(v)) {
                    edits.computeIfAbsent(k, x -> new ArrayList<>()).add(new Edit(v, mtime, gameDir));
                    edited++;
                }
                long[] t = votes.computeIfAbsent(k, x -> new HashMap<>()).computeIfAbsent(v, x -> new long[2]);
                t[0]++;
                t[1] = Math.max(t[1], mtime);
            }
            System.out.printf("read %s: %d shared values%s%n", gameDir, values.size(),
                    base == null ? " (first sync: no baseline yet)" : ", " + edited + " edited since last sync");
        }
        int fromEdits = 0, fromVotes = 0, conflicts = 0;
        for (var e : edits.entrySet()) {
            var list = e.getValue();
            Edit win = list.stream().max(Comparator.comparingLong(Edit::mtime)).orElseThrow();
            if (list.stream().map(Edit::value).distinct().count() > 1) {
                conflicts++;
                System.out.println("  conflict " + e.getKey() + ": " + list.stream().map(x -> x.value() + " (" + x.dir().getFileName() + ")").toList()
                        + " -> " + win.value() + " (most recently saved)");
            }
            master.put(e.getKey(), new Entry(win.value(), win.mtime(), win.dir().toString()));
            fromEdits++;
        }
        for (var e : votes.entrySet()) {
            if (edits.containsKey(e.getKey()) || master.containsKey(e.getKey())) continue;
            var best = e.getValue().entrySet().stream()
                    .max(Comparator.<Map.Entry<String, long[]>>comparingLong(x -> x.getValue()[0]).thenComparingLong(x -> x.getValue()[1]))
                    .orElseThrow();
            master.put(e.getKey(), new Entry(best.getKey(), best.getValue()[1],
                    "majority " + best.getValue()[0] + "/" + e.getValue().values().stream().mapToLong(x -> x[0]).sum()));
            fromVotes++;
        }
        System.out.printf("master: %d from edits, %d by majority, %d conflict(s)%n", fromEdits, fromVotes, conflicts);
    }

    /** Shared key -> value of one options.txt, key bindings in modern form. */
    Map<String, String> sharedValues(List<String> lines) {
        boolean legacy = isLegacy(lines);
        var out = new LinkedHashMap<String, String>();
        for (String l : lines) {
            int c = l.indexOf(':');
            if (c <= 0) continue;
            String k = l.substring(0, c), v = l.substring(c + 1);
            if (!shared(k)) continue;
            if (legacy && k.startsWith("key_")) { v = KeyCodes.toModern(v); if (v == null) continue; }
            out.put(k, v);
        }
        return out;
    }

    // ---- baselines: <master>/baselines/<hash>.txt, first line = game dir, then key \t value

    Path baselineFile(Path gameDir) {
        String id = Integer.toHexString(gameDir.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT).hashCode());
        return masterDir.resolve("baselines").resolve(id + ".txt");
    }

    Map<String, String> loadBaseline(Path gameDir) throws IOException {
        Path f = baselineFile(gameDir);
        if (!Files.exists(f)) return null;
        var m = new HashMap<String, String>();
        var lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (String l : lines.subList(1, lines.size())) { String[] p = l.split("\t", 2); if (p.length == 2) m.put(p[0], p[1]); }
        return m;
    }

    void saveBaseline(Path gameDir, Map<String, String> values) throws IOException {
        Path f = baselineFile(gameDir);
        Files.createDirectories(f.getParent());
        var sb = new StringBuilder(gameDir.toAbsolutePath().normalize().toString()).append('\n');
        values.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
    }

    void push(Path gameDir, boolean write) throws IOException {
        loadKeep(gameDir);
        Path f = gameDir.resolve("options.txt");
        if (!Files.exists(f)) { System.out.println("skip (no options.txt yet; start the game once): " + gameDir); return; }
        var lines = read(f);
        boolean legacy = isLegacy(lines);
        var out = new ArrayList<String>();
        var seen = new HashSet<String>();
        var changes = new ArrayList<String>();
        for (String l : lines) {
            int c = l.indexOf(':');
            String k = c > 0 ? l.substring(0, c) : null;
            if (k == null || !shared(k) || !master.containsKey(k)) { out.add(l); if (k != null) seen.add(k); continue; }
            seen.add(k);
            String want = convertFor(k, master.get(k).value(), legacy);
            String have = l.substring(c + 1);
            if (want == null || want.equals(have)) { out.add(l); continue; }
            out.add(k + ":" + want);
            changes.add("  ~ " + k + ": " + have + " -> " + want);
        }
        // keys this instance has never written: vanilla ones always (a newer binding the version may know), a mod's
        // only where that mod is present (the instance already has a key of the same namespace)
        var namespaces = new HashSet<String>();
        for (String k : seen) if (k.startsWith("key_")) namespaces.add(namespace(k));
        for (var e : master.entrySet()) {
            if (seen.contains(e.getKey()) || !shared(e.getKey())) continue;
            if (e.getKey().startsWith("key_") && !namespace(e.getKey()).equals("key") && !namespaces.contains(namespace(e.getKey()))) continue;
            String want = convertFor(e.getKey(), e.getValue().value(), legacy);
            if (want == null) continue;
            out.add(e.getKey() + ":" + want);
            changes.add("  + " + e.getKey() + ":" + want);
        }
        System.out.printf("%s %s%s: %d change(s)%n", write ? "push" : "would push to", gameDir, legacy ? " [pre-1.13 keys]" : "", changes.size());
        int limit = Integer.getInteger("sf.limit", 40);
        changes.stream().limit(limit).forEach(System.out::println);
        if (changes.size() > limit) System.out.println("  … " + (changes.size() - limit) + " more");
        if (!write) return;
        if (gameRunning(gameDir)) { System.out.println("  ! a game is running from this folder: not writing (it would overwrite on exit)"); return; }
        if (!changes.isEmpty()) {
            Path bak = f.resolveSibling("options.txt.sf-bak-" + LocalDateTime.now().format(TS));
            Files.copy(f, bak, StandardCopyOption.COPY_ATTRIBUTES);
            Files.write(f, out, StandardCharsets.UTF_8);
            System.out.println("  written; backup " + bak.getFileName());
        }
        saveBaseline(gameDir, sharedValues(out));   // what this instance holds now = the base for the next merge
    }

    /** "key_key.jump" -> "key" (vanilla), "key_iris.keybind.reload" -> "iris". */
    static String namespace(String key) {
        String rest = key.substring(4);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    static String convertFor(String key, String modernValue, boolean legacy) {
        if (!legacy || !key.startsWith("key_")) return modernValue;
        return KeyCodes.toLegacy(modernValue);   // null when old versions have no such key
    }

    /** Pre-1.13 files store key bindings as LWJGL 2 key codes (numbers). */
    static boolean isLegacy(List<String> lines) {
        for (String l : lines) if (l.startsWith("key_")) return l.substring(l.indexOf(':') + 1).matches("-?\\d+");
        return false;
    }

    /** Is a Minecraft JVM running with this folder as its game directory? (Then our write would be overwritten.) */
    static boolean gameRunning(Path gameDir) {
        String dir = gameDir.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        return ProcessHandle.allProcesses().anyMatch(p -> p.info().commandLine().map(c -> {
            String s = c.toLowerCase(Locale.ROOT);
            return (s.contains("net.minecraft") || s.contains("minecraft")) && s.contains(dir);
        }).orElse(false));
    }

    /** options.txt is UTF-8 in current versions; older ones wrote the platform charset. Read leniently. */
    static List<String> read(Path f) throws IOException {
        byte[] b = Files.readAllBytes(f);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b)).toString().lines().toList();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(b, StandardCharsets.ISO_8859_1).lines().toList();
        }
    }

    // =================================================================== key codes

    /** LWJGL 2 key codes (Minecraft 1.12.2 and older) <-> 1.13+ names. */
    static final class KeyCodes {
        static final Map<Integer, String> CODE_TO_NAME = new HashMap<>();
        static final Map<String, Integer> NAME_TO_CODE = new HashMap<>();

        static void m(int code, String name) { CODE_TO_NAME.put(code, name); NAME_TO_CODE.putIfAbsent(name, code); }

        static {
            m(0, "key.keyboard.unknown");
            m(1, "key.keyboard.escape");
            String digits = "1234567890";
            for (int i = 0; i < 10; i++) m(2 + i, "key.keyboard." + digits.charAt(i));
            m(12, "key.keyboard.minus"); m(13, "key.keyboard.equal"); m(14, "key.keyboard.backspace"); m(15, "key.keyboard.tab");
            String row1 = "qwertyuiop"; for (int i = 0; i < row1.length(); i++) m(16 + i, "key.keyboard." + row1.charAt(i));
            m(26, "key.keyboard.left.bracket"); m(27, "key.keyboard.right.bracket"); m(28, "key.keyboard.enter");
            m(29, "key.keyboard.left.control");
            String row2 = "asdfghjkl"; for (int i = 0; i < row2.length(); i++) m(30 + i, "key.keyboard." + row2.charAt(i));
            m(39, "key.keyboard.semicolon"); m(40, "key.keyboard.apostrophe"); m(41, "key.keyboard.grave.accent");
            m(42, "key.keyboard.left.shift"); m(43, "key.keyboard.backslash");
            String row3 = "zxcvbnm"; for (int i = 0; i < row3.length(); i++) m(44 + i, "key.keyboard." + row3.charAt(i));
            m(51, "key.keyboard.comma"); m(52, "key.keyboard.period"); m(53, "key.keyboard.slash"); m(54, "key.keyboard.right.shift");
            m(55, "key.keyboard.keypad.multiply"); m(56, "key.keyboard.left.alt"); m(57, "key.keyboard.space"); m(58, "key.keyboard.caps.lock");
            for (int i = 0; i < 10; i++) m(59 + i, "key.keyboard.f" + (i + 1));
            m(69, "key.keyboard.num.lock"); m(70, "key.keyboard.scroll.lock");
            m(71, "key.keyboard.keypad.7"); m(72, "key.keyboard.keypad.8"); m(73, "key.keyboard.keypad.9"); m(74, "key.keyboard.keypad.subtract");
            m(75, "key.keyboard.keypad.4"); m(76, "key.keyboard.keypad.5"); m(77, "key.keyboard.keypad.6"); m(78, "key.keyboard.keypad.add");
            m(79, "key.keyboard.keypad.1"); m(80, "key.keyboard.keypad.2"); m(81, "key.keyboard.keypad.3"); m(82, "key.keyboard.keypad.0");
            m(83, "key.keyboard.keypad.decimal"); m(87, "key.keyboard.f11"); m(88, "key.keyboard.f12");
            m(156, "key.keyboard.keypad.enter"); m(157, "key.keyboard.right.control"); m(181, "key.keyboard.keypad.divide");
            m(184, "key.keyboard.right.alt"); m(197, "key.keyboard.pause"); m(199, "key.keyboard.home"); m(200, "key.keyboard.up");
            m(201, "key.keyboard.page.up"); m(203, "key.keyboard.left"); m(205, "key.keyboard.right"); m(207, "key.keyboard.end");
            m(208, "key.keyboard.down"); m(209, "key.keyboard.page.down"); m(210, "key.keyboard.insert"); m(211, "key.keyboard.delete");
            m(219, "key.keyboard.left.win"); m(220, "key.keyboard.right.win");
            m(-100, "key.mouse.left"); m(-99, "key.mouse.right"); m(-98, "key.mouse.middle"); m(-97, "key.mouse.4"); m(-96, "key.mouse.5");
        }

        static String toModern(String legacyValue) {
            try { return CODE_TO_NAME.get(Integer.parseInt(legacyValue.strip())); } catch (NumberFormatException e) { return null; }
        }

        static String toLegacy(String modern) {
            // 1.13+ may append modifiers (key.keyboard.x:alt etc. in some mods); only the plain key maps back
            Integer c = NAME_TO_CODE.get(modern);
            return c == null ? null : Integer.toString(c);
        }
    }
}
