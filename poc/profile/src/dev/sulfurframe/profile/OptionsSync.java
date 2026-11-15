package dev.sulfurframe.profile;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Merge-sync of key bindings and chosen options between Minecraft instances through one master profile.
 * Java 8 source: the same class runs as a command-line tool and inside the SulfurFrame javaagent (auto-sync).
 *
 * Why not just link options.txt: the game rewrites the file with only the options it knows, so one run of an older
 * version (or of an instance without mod X) silently deletes everything else. Here the master keeps the union, and
 * each instance only ever gets the shared keys merged into its own file; everything else in that file is left as is.
 *
 * Three-way merge: options.txt is rewritten on every game start, so its mtime does not say when a setting changed.
 * Each instance has a baseline (its shared values right after the last sync); only values that differ from it are
 * edits and propagate. Keys nobody edited, and the first sync, are decided by majority vote.
 *
 *   pull   <gameDir>...   instance -> master
 *   push   <gameDir>...   master   -> instance (only shared keys; the instance's other lines and version: line stay)
 *   status <gameDir>...   what push would change
 *
 * Writes nothing unless --apply is given. Before writing an options.txt it saves options.txt.sf-bak-<time>.
 * Key bindings are stored in the modern form (key.keyboard.w) and converted for pre-1.13 files (LWJGL 2 key codes).
 *
 * usage: java OptionsSync.java pull|push|status [--master <dir>] [--apply] <gameDir>...
 * agent: OptionsSync.atGameStart(gameDir, master) / atGameExit(gameDir, master)
 */
public class OptionsSync {
    /** Shared by default: controls and comfort. Graphics/video stay per instance (dev and play want different ones). */
    static final List<String> DEFAULT_SHARE = Arrays.asList(
            "key_*", "mouseSensitivity", "invertYMouse", "invertXMouse", "autoJump", "toggleCrouch", "toggleSprint",
            "toggleAttack", "toggleUse", "discrete_mouse_scroll", "mouseWheelSensitivity", "rawMouseInput", "lang",
            "soundCategory_*", "chatScale", "chatWidth", "chatOpacity", "chatLineSpacing", "narrator", "showSubtitles",
            "fov", "bobView", "hideServerAddress", "operatorItemsTab");

    public static Path defaultMaster() { return Paths.get(System.getProperty("user.home"), ".sulfurframe", "profile"); }

    static final class Entry {
        final String value; final long modified; final String source;
        Entry(String value, long modified, String source) { this.value = value; this.modified = modified; this.source = source; }
    }

    // =================================================================== command line

    public static void main(String[] a) throws Exception {
        if (a.length < 2) { System.err.println("usage: pull|push|status [--master dir] [--apply] <gameDir>..."); System.exit(2); }
        String cmd = a[0];
        Path master = defaultMaster();
        boolean apply = false;
        List<String> pos = new ArrayList<>();
        for (int i = 1; i < a.length; i++) {
            if (a[i].equals("--master")) master = Paths.get(a[++i]);
            else if (a[i].equals("--apply")) apply = true;
            else pos.add(a[i]);
        }
        OptionsSync sync = new OptionsSync(master, "");
        if (cmd.equals("profile-new")) {   // profile-new <name> <gameDir> <key or glob>[,...]
            sync.newProfile(pos.get(0), Paths.get(pos.get(1)), Arrays.asList(pos.get(2).split(",")), apply);
            return;
        }
        if (cmd.equals("assign")) {        // assign <gameDir> <name>[,<name>...]   ("-" removes the assignment)
            sync.assign(Paths.get(pos.get(0)), pos.get(1), apply);
            return;
        }
        List<Path> dirs = new ArrayList<>();
        for (String p : pos) dirs.add(Paths.get(p));
        if (cmd.equals("profiles")) { sync.listProfiles(dirs); return; }
        if (cmd.equals("suggest")) { sync.suggest(dirs); return; }
        if (cmd.equals("pull")) {
            sync.pullAll(dirs);
            if (apply) sync.saveMaster(); else System.out.println("(dry run: master not written; add --apply)");
        } else if (cmd.equals("push") || cmd.equals("status")) {
            boolean write = cmd.equals("push") && apply;
            for (Path d : dirs) sync.push(d, write, true);
            if (cmd.equals("push") && !apply) System.out.println("(dry run: nothing written; add --apply)");
        } else throw new IllegalArgumentException(cmd);
    }

    // =================================================================== profiles (command line)

    void listProfiles(List<Path> dirs) throws IOException {
        if (profiles.isEmpty()) System.out.println("no profiles in " + masterDir.resolve("profiles"));
        for (Map.Entry<String, Map<String, String>> p : profiles.entrySet())
            System.out.println("profile " + p.getKey() + ": " + p.getValue().size() + " key(s) " + p.getValue().keySet());
        for (Path d : dirs) { loadKeep(d); System.out.println("  " + d + " -> " + (active.isEmpty() ? "(base only)" : String.join("+", active))); }
    }

    /**
     * Where do the instances disagree? Keys whose values split the instances the same way are grouped: each group is
     * a candidate profile (e.g. the instances that unbind the shader keys, or that move attack to another button).
     */
    void suggest(List<Path> dirs) throws IOException {
        Map<Path, Map<String, String>> vals = new LinkedHashMap<>();
        for (Path d : dirs) {
            loadKeep(d);
            Path f = d.resolve("options.txt");
            if (Files.exists(f)) vals.put(d, sharedValues(read(f)));
        }
        Set<String> keys = new TreeSet<>();
        for (Map<String, String> m : vals.values()) keys.addAll(m.keySet());
        Map<String, List<String>> bySplit = new LinkedHashMap<>();   // "value=instances | ..." -> keys
        for (String k : keys) {
            Map<String, List<String>> groups = new TreeMap<>();
            for (Map.Entry<Path, Map<String, String>> e : vals.entrySet()) {
                String v = e.getValue().get(k);
                if (v != null) groups.computeIfAbsent(v, x -> new ArrayList<>()).add(name(e.getKey()));
            }
            if (groups.size() < 2) continue;
            List<String> parts = new ArrayList<>();
            for (List<String> g : groups.values()) parts.add(g.toString());
            Collections.sort(parts);
            bySplit.computeIfAbsent(String.join(" | ", parts), x -> new ArrayList<>()).add(k);
        }
        if (bySplit.isEmpty()) { System.out.println("the instances agree on every shared key"); return; }
        int n = 0;
        for (Map.Entry<String, List<String>> e : bySplit.entrySet()) {
            System.out.println("candidate " + (++n) + ": " + e.getValue().size() + " key(s) " + e.getValue());
            System.out.println("    split: " + e.getKey());
        }
        System.out.println("\nmake one with: profile-new <name> <gameDir> <key or glob>[,...]  then  assign <gameDir> <name>");
    }

    static String name(Path d) {
        Path n = d.getFileName();
        String s = n == null ? d.toString() : n.toString();
        if ((s.equals("run") || s.equals("minecraft") || s.equals(".minecraft")) && d.getParent() != null) s = d.getParent().getFileName() + "/" + s;
        return s;
    }

    /** New profile from an instance's current values for the matching shared keys. */
    void newProfile(String name, Path from, List<String> globs, boolean apply) throws IOException {
        if (!name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("profile name: letters, digits, - and _ only");
        loadKeep(from);
        Map<String, String> m = new TreeMap<>();
        for (Map.Entry<String, String> e : sharedValues(read(from.resolve("options.txt"))).entrySet())
            if (matches(globs, e.getKey())) m.put(e.getKey(), e.getValue());
        System.out.println("profile " + name + " from " + from + ": " + m.size() + " key(s)");
        for (Map.Entry<String, String> e : m.entrySet()) System.out.println("  " + e.getKey() + ":" + e.getValue());
        if (!apply) { System.out.println("(dry run: add --apply)"); return; }
        profiles.put(name, m);
        saveProfile(name);
        System.out.println("written: " + masterDir.resolve("profiles").resolve(name + ".txt"));
    }

    void assign(Path gameDir, String names, boolean apply) throws IOException {
        Path f = gameDir.resolve("sulfurframe-profile.txt");
        if (names.equals("-")) {
            System.out.println("remove " + f);
            if (apply) Files.deleteIfExists(f); else System.out.println("(dry run: add --apply)");
            return;
        }
        for (String n : names.split(",")) if (!profiles.containsKey(n.trim())) System.out.println("  ! no profile '" + n.trim() + "' yet");
        System.out.println(gameDir + " -> " + names);
        if (apply) write(f, "# SulfurFrame content profiles for this instance (later ones win)\n" + names + "\n");
        else System.out.println("(dry run: add --apply)");
    }

    // =================================================================== auto-sync (called by the javaagent)

    /**
     * Before the game reads options.txt: take this instance's edits made since the last sync (e.g. while the exit
     * sync could not run, after a crash) into the master, then merge everyone else's changes into this instance.
     */
    public static void atGameStart(Path gameDir, Path masterDir) throws Exception {
        if (!Files.exists(masterDir.resolve("options.master"))) { log("[SF-Sync] ", "no master profile yet (" + masterDir + "): auto-sync off"); return; }
        try (FileLock ignored = lock(masterDir)) {
            OptionsSync s = new OptionsSync(masterDir, "[SF-Sync] ");
            s.out("game start: " + gameDir);
            s.pullAll(Collections.singletonList(gameDir));
            s.saveMaster();
            s.push(gameDir, true, false);   // the game reading this file is us: no running-game check
        }
    }

    /** After the game has saved options.txt for the last time: take what the player changed into the master. */
    public static void atGameExit(Path gameDir, Path masterDir) throws Exception {
        if (!Files.exists(masterDir.resolve("options.master"))) return;
        try (FileLock ignored = lock(masterDir)) {
            OptionsSync s = new OptionsSync(masterDir, "[SF-Sync] ");
            s.out("game exit: " + gameDir);
            s.pullAll(Collections.singletonList(gameDir));
            s.saveMaster();
            // the instance's current values are in the master now: they are this instance's new base, so a later
            // change made elsewhere is not mistaken for an edit here
            Path f = gameDir.resolve("options.txt");
            if (Files.exists(f)) { s.loadKeep(gameDir); s.saveBaseline(gameDir, s.sharedValues(read(f))); }
        }
    }

    /** Several games may start or stop at once: serialize master updates. Waits up to 10 s. */
    static FileLock lock(Path masterDir) throws Exception {
        Files.createDirectories(masterDir);
        FileChannel ch = new RandomAccessFile(masterDir.resolve("lock").toFile(), "rw").getChannel();
        for (int i = 0; i < 100; i++) {
            FileLock l = ch.tryLock();
            if (l != null) return l;
            Thread.sleep(100);
        }
        ch.close();
        throw new IOException("master profile is locked by another game: " + masterDir);
    }

    // =================================================================== master

    final Path masterDir;
    final String prefix;
    final Map<String, Entry> master = new TreeMap<>();
    final List<String> share;
    List<String> keep = Collections.emptyList();

    /**
     * Content profiles: named key->value sets (<master>/profiles/<name>.txt, "key:value" lines) layered over the base
     * master for the instances that list them in <gameDir>/sulfurframe-profile.txt (later names win). An edit to a key
     * a profile owns goes to that profile, so it reaches only the instances using the same profile.
     */
    final Map<String, Map<String, String>> profiles = new TreeMap<>();
    final Set<String> dirtyProfiles = new TreeSet<>();
    List<String> active = Collections.emptyList();

    OptionsSync(Path masterDir, String prefix) throws IOException {
        this.masterDir = masterDir;
        this.prefix = prefix;
        Path f = masterDir.resolve("options.master");
        if (Files.exists(f)) {
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = l.split("\t", 4);   // key \t value \t modifiedMillis \t source
                if (p.length == 4) master.put(p[0], new Entry(p[1], Long.parseLong(p[2]), p[3]));
            }
        }
        Path sf = masterDir.resolve("share.txt");
        share = Files.exists(sf) ? globs(sf) : DEFAULT_SHARE;
        Path pd = masterDir.resolve("profiles");
        if (Files.isDirectory(pd)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(pd, "*.txt")) {
                for (Path p : ds) {
                    Map<String, String> m = new TreeMap<>();
                    for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                        int c = l.indexOf(':');
                        if (c > 0 && !l.startsWith("#")) m.put(l.substring(0, c), l.substring(c + 1));
                    }
                    String n = p.getFileName().toString();
                    profiles.put(n.substring(0, n.length() - 4), m);
                }
            }
        }
    }

    void saveProfile(String name) throws IOException {
        Path pd = masterDir.resolve("profiles");
        Files.createDirectories(pd);
        StringBuilder sb = new StringBuilder("# SulfurFrame content profile '" + name + "': key:value, layered over the base master\n");
        for (Map.Entry<String, String> e : profiles.get(name).entrySet()) sb.append(e.getKey()).append(':').append(e.getValue()).append('\n');
        write(pd.resolve(name + ".txt"), sb.toString());
    }

    /** The active profile (last listed wins) that owns this key, or null when the base master decides. */
    String owner(String key) {
        for (int i = active.size() - 1; i >= 0; i--) {
            Map<String, String> p = profiles.get(active.get(i));
            if (p != null && p.containsKey(key)) return active.get(i);
        }
        return null;
    }

    /** Value this instance should hold for a shared key: its profiles first, then the base master. */
    String effective(String key) {
        String o = owner(key);
        if (o != null) return profiles.get(o).get(key);
        Entry e = master.get(key);
        return e == null ? null : e.value;
    }

    static List<String> globs(Path f) throws IOException {
        List<String> out = new ArrayList<>();
        for (String s : Files.readAllLines(f, StandardCharsets.UTF_8)) { s = s.trim(); if (!s.isEmpty() && !s.startsWith("#")) out.add(s); }
        return out;
    }

    void saveMaster() throws IOException {
        Files.createDirectories(masterDir);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Entry> e : master.entrySet())
            sb.append(e.getKey()).append('\t').append(e.getValue().value).append('\t')
              .append(e.getValue().modified).append('\t').append(e.getValue().source).append('\n');
        write(masterDir.resolve("options.master"), sb.toString());
        for (String p : dirtyProfiles) { saveProfile(p); out("profile written: " + p); }
        dirtyProfiles.clear();
        Path sf = masterDir.resolve("share.txt");
        if (!Files.exists(sf)) write(sf, "# options shared across instances (glob: *)\n" + String.join("\n", DEFAULT_SHARE) + "\n");
        out("master written: " + masterDir.resolve("options.master") + " (" + master.size() + " entries)");
    }

    boolean shared(String key) { return matches(share, key) && !matches(keep, key); }

    static boolean matches(List<String> globs, String key) {
        for (String g : globs) if (g.endsWith("*") ? key.startsWith(g.substring(0, g.length() - 1)) : key.equals(g)) return true;
        return false;
    }

    /** Per-instance exceptions: <gameDir>/sulfurframe-keep.txt lists keys (globs) this instance keeps to itself. */
    void loadKeep(Path gameDir) throws IOException {
        Path f = gameDir.resolve("sulfurframe-keep.txt");
        keep = Files.exists(f) ? globs(f) : Collections.<String>emptyList();
        Path pf = gameDir.resolve("sulfurframe-profile.txt");   // profile names, one per line or comma separated
        List<String> names = new ArrayList<>();
        if (Files.exists(pf)) for (String l : globs(pf)) for (String n : l.split(",")) if (!n.trim().isEmpty()) names.add(n.trim());
        active = names;
    }

    // =================================================================== pull

    static final class Edit {
        final String value; final long mtime; final Path dir;
        Edit(String value, long mtime, Path dir) { this.value = value; this.mtime = mtime; this.dir = dir; }
    }

    void pullAll(List<Path> dirs) throws IOException {
        Map<String, List<Edit>> edits = new TreeMap<>();
        Map<String, Map<String, long[]>> votes = new TreeMap<>();   // key -> value -> {count, latest mtime}
        for (Path gameDir : dirs) {
            loadKeep(gameDir);
            Path f = gameDir.resolve("options.txt");
            if (!Files.exists(f)) { out("skip (no options.txt): " + gameDir); continue; }
            long mtime = Files.getLastModifiedTime(f).toMillis();
            Map<String, String> base = loadBaseline(gameDir);
            Map<String, String> values = sharedValues(read(f));
            int edited = 0;
            for (Map.Entry<String, String> e : values.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                String o = owner(k);
                if (o != null) {   // a profile owns this key here: edits go to the profile, and it takes no part in the base vote
                    if (base != null && base.containsKey(k) && !base.get(k).equals(v) && !v.equals(profiles.get(o).get(k))) {
                        profiles.get(o).put(k, v);
                        dirtyProfiles.add(o);
                        out("  profile " + o + ": " + k + " = " + v + " (edited in " + gameDir.getFileName() + ")");
                        edited++;
                    }
                    continue;
                }
                if (base != null && base.containsKey(k) && !base.get(k).equals(v)) {
                    edits.computeIfAbsent(k, x -> new ArrayList<>()).add(new Edit(v, mtime, gameDir));
                    edited++;
                }
                long[] t = votes.computeIfAbsent(k, x -> new HashMap<>()).computeIfAbsent(v, x -> new long[2]);
                t[0]++;
                t[1] = Math.max(t[1], mtime);
            }
            out("read " + gameDir + ": " + values.size() + " shared values"
                    + (base == null ? " (first sync: no baseline yet)" : ", " + edited + " edited since last sync"));
        }
        int fromEdits = 0, fromVotes = 0, conflicts = 0;
        for (Map.Entry<String, List<Edit>> e : edits.entrySet()) {
            List<Edit> list = e.getValue();
            Edit win = list.get(0);
            Set<String> distinct = new LinkedHashSet<>();
            for (Edit x : list) { distinct.add(x.value); if (x.mtime > win.mtime) win = x; }
            if (distinct.size() > 1) {
                conflicts++;
                StringBuilder sb = new StringBuilder();
                for (Edit x : list) sb.append(x.value).append(" (").append(x.dir.getFileName()).append(") ");
                out("  conflict " + e.getKey() + ": " + sb + "-> " + win.value + " (most recently saved)");
            }
            master.put(e.getKey(), new Entry(win.value, win.mtime, win.dir.toString()));
            fromEdits++;
        }
        for (Map.Entry<String, Map<String, long[]>> e : votes.entrySet()) {
            if (edits.containsKey(e.getKey()) || master.containsKey(e.getKey())) continue;
            String bestValue = null; long[] best = null; long total = 0;
            for (Map.Entry<String, long[]> v : e.getValue().entrySet()) {
                total += v.getValue()[0];
                if (best == null || v.getValue()[0] > best[0] || (v.getValue()[0] == best[0] && v.getValue()[1] > best[1])) {
                    best = v.getValue(); bestValue = v.getKey();
                }
            }
            master.put(e.getKey(), new Entry(bestValue, best[1], "majority " + best[0] + "/" + total));
            fromVotes++;
        }
        out("master: " + fromEdits + " from edits, " + fromVotes + " by majority, " + conflicts + " conflict(s)");
    }

    /** Shared key -> value of one options.txt, key bindings in modern form. */
    Map<String, String> sharedValues(List<String> lines) {
        boolean legacy = isLegacy(lines);
        Map<String, String> out = new LinkedHashMap<>();
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
        Map<String, String> m = new HashMap<>();
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (String l : lines.subList(1, lines.size())) { String[] p = l.split("\t", 2); if (p.length == 2) m.put(p[0], p[1]); }
        return m;
    }

    void saveBaseline(Path gameDir, Map<String, String> values) throws IOException {
        Path f = baselineFile(gameDir);
        Files.createDirectories(f.getParent());
        StringBuilder sb = new StringBuilder(gameDir.toAbsolutePath().normalize().toString()).append('\n');
        for (Map.Entry<String, String> e : values.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        write(f, sb.toString());
    }

    // =================================================================== push

    void push(Path gameDir, boolean write, boolean checkRunningGame) throws IOException {
        loadKeep(gameDir);
        Path f = gameDir.resolve("options.txt");
        if (!Files.exists(f)) { out("skip (no options.txt yet; start the game once): " + gameDir); return; }
        List<String> lines = read(f);
        boolean legacy = isLegacy(lines);
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> changes = new ArrayList<>();
        for (String l : lines) {
            int c = l.indexOf(':');
            String k = c > 0 ? l.substring(0, c) : null;
            if (k == null || !shared(k) || effective(k) == null) { result.add(l); if (k != null) seen.add(k); continue; }
            seen.add(k);
            String want = convertFor(k, effective(k), legacy);
            String have = l.substring(c + 1);
            if (want == null || want.equals(have)) { result.add(l); continue; }
            result.add(k + ":" + want);
            changes.add("  ~ " + k + ": " + have + " -> " + want);
        }
        // keys this instance has never written: vanilla ones always, a mod's only where that mod is present
        Set<String> namespaces = new HashSet<>();
        for (String k : seen) if (k.startsWith("key_")) namespaces.add(namespace(k));
        Set<String> candidates = new TreeSet<>(master.keySet());
        for (String p : active) if (profiles.containsKey(p)) candidates.addAll(profiles.get(p).keySet());
        for (String k : candidates) {
            if (seen.contains(k) || !shared(k)) continue;
            if (k.startsWith("key_") && !namespace(k).equals("key") && !namespaces.contains(namespace(k))) continue;
            String want = convertFor(k, effective(k), legacy);
            if (want == null) continue;
            result.add(k + ":" + want);
            changes.add("  + " + k + ":" + want);
        }
        out((write ? "push " : "would push to ") + gameDir + (legacy ? " [pre-1.13 keys]" : "")
                + (active.isEmpty() ? "" : " [profiles " + String.join("+", active) + "]") + ": " + changes.size() + " change(s)");
        for (String p : active) if (!profiles.containsKey(p)) out("  ! unknown profile '" + p + "' in sulfurframe-profile.txt");
        int limit = Integer.getInteger("sf.limit", 40);
        for (int i = 0; i < Math.min(limit, changes.size()); i++) out(changes.get(i));
        if (changes.size() > limit) out("  … " + (changes.size() - limit) + " more");
        if (!write) return;
        if (checkRunningGame && gameRunning(gameDir)) { out("  ! a game is running from this folder: not writing (it would overwrite on exit)"); return; }
        if (!changes.isEmpty()) {
            Path bak = f.resolveSibling("options.txt.sf-bak-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
            Files.copy(f, bak, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            Files.write(f, result, StandardCharsets.UTF_8);
            out("  written; backup " + bak.getFileName());
            pruneBackups(gameDir, 5);
        }
        saveBaseline(gameDir, sharedValues(result));   // what this instance holds now = the base for the next merge
    }

    /** Auto-sync writes on every start that brings changes: keep the newest few backups only. */
    static void pruneBackups(Path gameDir, int keep) throws IOException {
        List<Path> baks = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(gameDir, "options.txt.sf-bak-*")) { for (Path p : ds) baks.add(p); }
        Collections.sort(baks);   // the timestamp sorts lexicographically
        for (int i = 0; i < baks.size() - keep; i++) Files.deleteIfExists(baks.get(i));
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

    /** Is a Minecraft JVM running with this folder as its game directory? (Java 9+ ProcessHandle; false on Java 8.) */
    static boolean gameRunning(Path gameDir) {
        String dir = gameDir.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        try {
            Class<?> ph = Class.forName("java.lang.ProcessHandle");
            Method all = ph.getMethod("allProcesses"), info = ph.getMethod("info");
            Class<?> infoC = Class.forName("java.lang.ProcessHandle$Info");
            Method cmdLine = infoC.getMethod("commandLine");
            Iterator<?> it = ((java.util.stream.Stream<?>) all.invoke(null)).iterator();
            while (it.hasNext()) {
                Optional<?> c = (Optional<?>) cmdLine.invoke(info.invoke(it.next()));
                if (c.isPresent()) {
                    String s = c.get().toString().toLowerCase(Locale.ROOT);
                    if (s.contains("minecraft") && s.contains(dir)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** options.txt is UTF-8 in current versions; older ones wrote the platform charset. Read leniently. */
    static List<String> read(Path f) throws IOException {
        byte[] b = Files.readAllBytes(f);
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            s = new String(b, StandardCharsets.ISO_8859_1);
        }
        List<String> out = new ArrayList<>(Arrays.asList(s.split("\r?\n", -1)));
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    static void write(Path f, String s) throws IOException { Files.write(f, s.getBytes(StandardCharsets.UTF_8)); }

    /**
     * Auto-sync (non-empty prefix) also appends to <master>/sync.log: at JVM exit the game's own logging is often
     * shut down already, and the player needs one place to see what was synced.
     */
    void out(String s) {
        log(prefix, s);
        if (prefix.isEmpty()) return;
        try {
            Files.createDirectories(masterDir);
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + s + System.lineSeparator();
            Files.write(masterDir.resolve("sync.log"), line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    static void log(String prefix, String s) { System.out.println(prefix + s); }

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
            try { return CODE_TO_NAME.get(Integer.parseInt(legacyValue.trim())); } catch (NumberFormatException e) { return null; }
        }

        static String toLegacy(String modern) {
            Integer c = NAME_TO_CODE.get(modern);
            return c == null ? null : Integer.toString(c);
        }
    }
}
