package dev.sulfurframe.idea;

import dev.sulfurframe.idea.profile.Workspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Save -> running game, without a debugger:
 *   1. compile the selected target with its own Gradle wrapper ({@code <path>:classes}, plus {@code clientClasses}
 *      for Loom's split client source set)
 *   2. find .class files under build/classes that changed since the last swap
 *   3. hand the list to the SulfurFrame agent in the game (Attach API, "redefine=<list>"), which redefines the loaded
 *      classes and writes a result file
 * Method-body changes apply live; added/removed methods or fields are reported as "restart needed".
 */
final class Hotswapper {
    private final Consumer<String> log;
    private final AtomicBoolean busy = new AtomicBoolean();
    private Map<Path, Long> snapshot;
    private long sourcesSince;   // .java files modified after this (millis) are compiled by the fast path
    private int counter;
    private final FastCompile fast;

    Hotswapper(Consumer<String> log, Path cacheDir) { this.log = log; this.fast = new FastCompile(log, cacheDir); }

    /** "javac" or "gradle": how the last swap compiled (self-test). */
    volatile String lastCompiler = "";

    /** Attach the SulfurFrame agent with these arguments (each request is a fresh attach). */
    static void loadAgent(long pid, Path agentJar, String args) throws Exception {
        try {
            var vm = com.sun.tools.attach.VirtualMachine.attach(Long.toString(pid));
            try { vm.loadAgent(agentJar.toString(), args); } finally { vm.detach(); }
        } catch (com.sun.tools.attach.AgentLoadException e) {
            if (!String.valueOf(e.getMessage()).endsWith(": 0")) throw e;   // Java 8 game: reply-format quirk
        }
    }

    /** Startup-only code that changed in the last swap ("Class.method"), see {@link StartupCode}. */
    volatile List<String> lastStartupChanges = List.of();
    /** Class files the last swap found changed (to see which project jars of the game are behind them). */
    volatile List<Path> lastChangedFiles = List.of();

    /** What the last resource reload did (self-test). */
    volatile String lastResources = "";

    /**
     * Temporary files of editors and tools next to the real one: {@code sed -i} (sedXXXXXX), Vim swap files, Emacs
     * locks and backups, IntelliJ's safe write ({@code ___jb_tmp___}), {@code *.tmp}. Not resources of the mod.
     */
    static boolean isEditorTemp(String name) {
        return name.matches("sed[A-Za-z0-9]{6}") || name.endsWith("~") || name.endsWith(".swp") || name.endsWith(".swx")
                || name.startsWith(".#") || (name.startsWith("#") && name.endsWith("#")) || name.endsWith(".tmp")
                || name.endsWith("___jb_tmp___") || name.endsWith("___jb_old___");
    }

    /**
     * Saved resource files: copy them where the dev game reads resources (the source set's resource output, what
     * processResources fills), then ask the agent to reload: only lang/*.json -> the language alone, other assets/ ->
     * client reload (F3+T), data/ -> /reload. Each copied file is logged.
     * Files processResources expands (mod metadata) are left alone: they need a restart anyway.
     */
    boolean reloadResources(Workspace.Target t, long gamePid, Path agentJar, List<Path> files) {
        if (t == null || gamePid <= 0 || agentJar == null) return false;
        var sets = fast.sourceSets(t);
        if (sets == null) { log.accept("resources: class path not read yet (Run client first)"); return false; }
        boolean assets = false, data = false, worldData = false, langOnly = true;
        var copied = new ArrayList<String>();
        try {
            for (Path f : files) {
                Path abs = f.toAbsolutePath().normalize();
                String name = abs.getFileName().toString();
                if (isEditorTemp(name)) continue;
                if (needsGradle(name)) {
                    log.accept("resources: " + name + " changed: restart the game to apply"); continue;
                }
                for (var s : sets) {
                    if (s.resOut() == null || isTestSet(s.name())) continue;
                    for (Path srcDir : s.resSrcDirs()) {
                        Path root = srcDir.toAbsolutePath().normalize();
                        if (!abs.startsWith(root)) continue;
                        Path rel = root.relativize(abs);
                        Path to = s.resOut().resolve(rel);
                        boolean exists = Files.exists(abs);
                        if (!exists && !Files.exists(to)) continue;   // gone before it was copied (a tool's temporary file)
                        Files.createDirectories(to.getParent());
                        if (exists) Files.copy(abs, to, StandardCopyOption.REPLACE_EXISTING); else Files.deleteIfExists(to);
                        copied.add(rel.toString().replace(java.io.File.separatorChar, '/') + (exists ? "" : " (deleted)") + " -> " + s.name());
                        if (!isLang(rel)) langOnly = false;
                        String top = rel.getName(0).toString();
                        if (top.equals("assets")) assets = true;
                        else if (top.equals("data")) {
                            data = true;
                            if (rel.getNameCount() > 3 && WORLD_DATA.contains(rel.getName(2).toString())) worldData = true;
                        }
                        else assets = true;   // pack.mcmeta and friends: a client reload picks them up
                    }
                }
            }
            if (copied.isEmpty()) return false;
            int n = copied.size();
            log.accept("resources: " + n + " file(s) copied: " + String.join(", ", copied.size() > 12 ? copied.subList(0, 12) : copied)
                    + (n > 12 ? " and " + (n - 12) + " more" : ""));
            if (worldData) {   // read only when a world opens: leave it and open it again (the game keeps running)
                data = false;
                Path done = agentJar.getParent().resolve("rejoin-" + gamePid + ".txt");
                Files.deleteIfExists(done);
                loadAgent(gamePid, agentJar, "codingFps=" + Settings.codingFps() + ",rejoin=" + done);
                for (int i = 0; i < 300 && !Files.exists(done); i++) Thread.sleep(100);
                String answer = Files.exists(done) ? Files.readString(done).strip() : "no answer";
                lastRejoin = answer;
                log.accept("resources: world data changed: " + (answer.equals("done") ? "left the world and opened it again" : "could not open the world again (" + answer + ")"));
                if (!assets) { lastResources = "world data (" + n + " file(s))"; return answer.equals("done"); }
            }
            // only translations: the language alone is read again (no full resource reload), and the open screen is
            // laid out again so text it fetched when it was built shows the new strings
            String what = langOnly ? "lang" : (assets ? "assets" : "") + (assets && data ? "+" : "") + (data ? "data" : "");
            try {
                var vm = com.sun.tools.attach.VirtualMachine.attach(Long.toString(gamePid));
                try { vm.loadAgent(agentJar.toString(), "codingFps=" + Settings.codingFps() + ",reload=" + what); } finally { vm.detach(); }
            } catch (com.sun.tools.attach.AgentLoadException e) {
                if (!String.valueOf(e.getMessage()).endsWith(": 0")) throw e;
            }
            lastResources = what + " (" + n + " file(s))";
            log.accept(langOnly ? "resources: lang reloaded: " + n + " file(s) (language only, open screen laid out again)"
                    : "resources: reload " + what);
            return true;
        } catch (Exception e) {
            log.accept("resources failed: " + e);
            return false;
        }
    }

    /** assets/<namespace>/lang/<code>.json: a translation file, read by the language manager alone. */
    static boolean isLang(Path rel) {
        return rel.getNameCount() == 4 && rel.getName(0).toString().equals("assets") && rel.getName(2).toString().equals("lang")
                && rel.getName(3).toString().endsWith(".json");
    }

    static boolean isTestSet(String name) { return name.equals("test") || name.endsWith("Test") || name.endsWith(":test"); }

    /**
     * Registration code changed and the classes were redefined: have the agent run it again in the game, registering
     * only what is new (RuntimeRegister). The startup changes it covered no longer ask for a restart.
     */
    /** Internal names of the project's own classes (everything under the class roots). */
    private Set<String> ownClasses(Workspace.Target t) throws IOException {
        var own = new HashSet<String>();
        for (Path root : classRoots(t))
            if (Files.isDirectory(root))
                try (Stream<Path> s = Files.walk(root)) {
                    s.filter(f -> f.toString().endsWith(".class")).forEach(f -> own.add(root.relativize(f).toString().replace('\\', '/').replaceFirst("\\.class$", "")));
                }
        return own;
    }

    /** Class files that had no earlier bytes (new classes): not loaded yet is expected for them. */
    private final Set<Path> newClassFiles = new HashSet<>();

    /** Classes whose superclass / interfaces changed to or from a type outside the project, with that type: not sent. */
    private final Map<Path, Set<String>> foreignHierarchy = new LinkedHashMap<>();
    private final Map<Path, byte[]> bytesBefore = new HashMap<>();   // to put back for a class that is held

    /** The list for the agent's register= request (RuntimeRegister), from the classes in {@link #rerunnable}. */
    private Path registerList(Workspace.Target t, Path agentJar) throws IOException {
        Path list = agentJar.getParent().resolve("register-" + ProcessHandle.current().pid() + "-" + (++counter) + ".txt");
        var lines = new ArrayList<String>();
        var ownClasses = ownClasses(t);   // the only possible owners
        // enums first (their new constants), switch maps last (computed against the enums as they are now)
        var order = new ArrayList<>(rerunnable.entrySet());
        order.sort(Comparator.comparingInt(e -> switch (e.getValue().plan().mode()) { case "enum" -> 0; case "switchmap" -> 2; default -> 1; }));
        for (var e : order) {
            var plan = e.getValue().plan();
            var owners = plan.called().stream().filter(ownClasses::contains).map(c -> c.replace('/', '.')).toList();
            lines.add(String.join("\t", binaryName(t, e.getKey()), e.getKey().toString(), String.join(";", plan.statics()),
                    String.join(";", plan.entries()), String.join(",", owners), plan.mode()));
        }
        Files.write(list, lines, StandardCharsets.UTF_8);
        Files.deleteIfExists(Path.of(list + ".result"));
        return list;
    }

    /**
     * The agent's answer to a register= request that went with the hotswap: what ran again, what was registered. The
     * startup changes it covered no longer ask for a restart.
     */
    private void awaitRegistration(Path list) throws Exception {
        Path result = Path.of(list + ".result");
        for (int i = 0; i < 300 && !Files.exists(result); i++) Thread.sleep(100);   // runs on the render thread: a frame or so, 30 s at most
        if (!Files.exists(result)) { log.accept("registration: no answer from the game"); Files.deleteIfExists(list); return; }
        var answer = Files.readAllLines(result, StandardCharsets.UTF_8);
        lastRegistration = List.copyOf(answer);
        Files.deleteIfExists(list); Files.deleteIfExists(result);
        var errors = answer.stream().filter(s -> s.startsWith("error")).toList();
        var registered = answer.stream().filter(s -> s.startsWith("registered ")).map(s -> s.substring("registered ".length())).toList();
        if (!errors.isEmpty()) {
            failedInitSnapshot = lastInitSnapshot;
            log.accept("registration failed in the game: " + String.join("; ", errors) + " (restart to apply"
                    + (lastInitSnapshot == null ? ")" : "; the world before it: " + lastInitSnapshot.getFileName() + ")"));
            return;
        }
        if (answer.contains("skipped static code: the classes could not all be redefined")) return;   // the restart offer stays
        var restart = answer.stream().filter(s -> s.startsWith("restart needed")).toList();
        if (!restart.isEmpty()) {   // e.g. an enum constant removed: the panel offers the restart like a failed redefinition
            var all = new ArrayList<>(lastResult);
            all.addAll(restart);
            lastResult = List.copyOf(all);
            log.accept("registration: " + String.join("; ", restart));
            return;
        }
        var covered = new ArrayList<String>();
        for (var r : rerunnable.values()) covered.addAll(r.startupChanges());
        var rest = new ArrayList<>(lastStartupChanges);
        rest.removeAll(covered);
        lastStartupChanges = List.copyOf(rest);
        log.accept("registration: ran " + String.join(", ", covered) + " again in the game: "
                + (registered.isEmpty() ? "nothing new" : registered.size() + " new " + registered)
                + answer.stream().filter(s -> s.startsWith("ms ")).map(s -> ", " + s.substring(3) + " ms").findFirst().orElse(""));
    }

    /**
     * The mixins ("list:Class") the running game has from each mixins.json: those it started with, plus and minus what
     * addMixins changed since, so a second save sends only what is new.
     */
    private final Map<Path, Set<String>> liveMixins = new HashMap<>();
    private int hotConfigs;

    /** What the last addMixins answered (self-test). */
    volatile List<String> lastMixins = List.of();

    /**
     * A saved mixins.json: if the only change is mixin classes added to or removed from its lists, that goes into the
     * running game (the agent's MixinAdd) and null is returned; otherwise why a restart is needed. The game's copy in
     * the resource output (what it loaded at start) is left alone; the new mixins go into an extra config next to it,
     * which Mixin takes after startup. Targets are read from the classes' @Mixin annotation, after compiling them. A
     * removed mixin leaves Mixin's configs and its loaded targets are transformed again without it; one whose
     * interface (an accessor, an implemented interface) other code still names needs a restart.
     */
    String addMixins(Workspace.Target t, long gamePid, Path agentJar, Path json) {
        String name = json.getFileName().toString();
        try {
            var sets = fast.sourceSets(t);
            Path loaded = null, resOut = null;
            if (sets != null)
                for (var s : sets)
                    for (Path srcDir : s.resSrcDirs())
                        if (s.resOut() != null && json.toAbsolutePath().normalize().startsWith(srcDir.toAbsolutePath().normalize())) {
                            resOut = s.resOut();
                            loaded = resOut.resolve(srcDir.toAbsolutePath().normalize().relativize(json.toAbsolutePath().normalize()));
                        }
            if (loaded == null || !Files.exists(loaded)) return name + " changed (the game's copy was not found)";
            var before = com.google.gson.JsonParser.parseString(Files.readString(loaded)).getAsJsonObject();
            var after = com.google.gson.JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            // what the game runs from this file: what it started with, then the live changes (per game)
            var live = liveMixins.computeIfAbsent(json.toAbsolutePath().normalize().resolveSibling(name + "@" + gamePid), k -> mixinEntries(before));
            var added = new LinkedHashMap<String, List<String>>();
            var removed = new ArrayList<String>();
            String refused = mixinChanges(before, after, live, added, removed);
            if (refused != null) return name + " changed (" + refused + ")";
            if (added.isEmpty() && removed.isEmpty()) return null;
            String pkg = after.has("package") ? after.get("package").getAsString() : "";
            if (!removed.isEmpty()) {
                var classes = new LinkedHashSet<String>();
                for (String e : removed) { String m = e.substring(e.indexOf(':') + 1); classes.add(pkg.isEmpty() ? m : pkg + "." + m); }
                for (String c : classes) {
                    String used = interfaceStillUsed(t, c);
                    if (used != null) return name + " changed (mixin " + c.substring(c.lastIndexOf('.') + 1) + " removed, but " + used + "): restart to apply";
                }
                Path list = agentJar.getParent().resolve("unmixins-" + ProcessHandle.current().pid() + "-" + (++counter) + ".txt");
                Files.write(list, classes, StandardCharsets.UTF_8);
                Path result = Path.of(list + ".result");
                Files.deleteIfExists(result);
                loadAgent(gamePid, agentJar, "codingFps=" + Settings.codingFps() + ",unmixins=" + list);
                for (int i = 0; i < 300 && !Files.exists(result); i++) Thread.sleep(100);
                if (!Files.exists(result)) return name + " changed (no answer from the game)";
                var answer = Files.readAllLines(result, StandardCharsets.UTF_8);
                lastMixins = List.copyOf(answer);
                Files.deleteIfExists(list); Files.deleteIfExists(result);
                var errors = answer.stream().filter(s -> s.startsWith("error")).toList();
                if (!errors.isEmpty()) return name + " changed (" + String.join("; ", errors) + ")";
                removed.forEach(live::remove);
                log.accept("mixins: " + classes.stream().map(c -> c.substring(c.lastIndexOf('.') + 1)).toList() + " removed from the running game, "
                        + answer.stream().filter(s -> s.startsWith("applied to")).count() + " loaded target(s) transformed again");
                if (added.isEmpty()) return null;
            }
            // the new classes compiled (and anything else saved with them swapped)
            boolean compiled = false;
            for (int i = 0; i < 40 && !(compiled = swap(t, gamePid, agentJar)); i++) Thread.sleep(250);   // busy: a save-triggered swap
            if (!compiled) return name + " changed (its mixins did not compile)";
            var targets = new LinkedHashSet<String>();
            for (var e : added.values())
                for (String m : e) {
                    String rel = (pkg.isEmpty() ? m : pkg + "." + m).replace('.', '/') + ".class";
                    Path cls = null;
                    for (Path root : classRoots(t)) if (Files.isRegularFile(root.resolve(rel))) cls = root.resolve(rel);
                    if (cls == null) return name + " changed (" + m + " not compiled)";
                    targets.addAll(StartupCode.mixinTargets(Files.readAllBytes(cls)));
                }
            var config = after.deepCopy();
            for (String list : MIXIN_LISTS) config.remove(list);
            // a required config refuses a mixin whose target is loaded already (MixinTargetAlreadyLoadedException), which
            // is every target here: not required, Mixin logs "loaded too early" and goes on; the redefinition applies it
            config.addProperty("required", false);
            for (var e : added.entrySet()) {
                var arr = new com.google.gson.JsonArray();
                e.getValue().forEach(arr::add);
                config.add(e.getKey(), arr);
            }
            String configName = "sulfurframe-hot-" + ProcessHandle.current().pid() + "-" + (++hotConfigs) + ".mixins.json";
            Files.writeString(resOut.resolve(configName), config.toString());
            Path list = agentJar.getParent().resolve("mixins-" + ProcessHandle.current().pid() + "-" + (++counter) + ".txt");
            var lines = new ArrayList<String>();
            lines.add(configName);
            lines.addAll(targets);
            Files.write(list, lines, StandardCharsets.UTF_8);
            Path result = Path.of(list + ".result");
            Files.deleteIfExists(result);
            loadAgent(gamePid, agentJar, "codingFps=" + Settings.codingFps() + ",mixins=" + list);
            for (int i = 0; i < 300 && !Files.exists(result); i++) Thread.sleep(100);
            if (!Files.exists(result)) return name + " changed (no answer from the game)";
            var answer = Files.readAllLines(result, StandardCharsets.UTF_8);
            lastMixins = List.copyOf(answer);
            Files.deleteIfExists(list); Files.deleteIfExists(result);
            var errors = answer.stream().filter(s -> s.startsWith("error")).toList();
            if (!errors.isEmpty()) return name + " changed (" + String.join("; ", errors) + ")";
            for (var e : added.entrySet()) for (String m : e.getValue()) live.add(e.getKey() + ":" + m);
            log.accept("mixins: " + added.values().stream().flatMap(List::stream).toList() + " added to the running game, "
                    + answer.stream().filter(s -> s.startsWith("applied to")).count() + " loaded target(s) transformed again");
            return null;
        } catch (Exception e) {
            return name + " changed (" + e + ")";
        }
    }

    /** Access widener entries already added to the running game, per game and file (by widenLive). */
    private final Map<String, Set<AwOverlay.Entry>> liveWidenings = new HashMap<>();

    /** An entry as a widener line ("access kind owner [name desc]"). */
    private static String widenerLine(AwOverlay.Entry e) {
        return e.access() + " " + e.kind() + " " + e.owner() + (e.name() == null ? "" : " " + e.name() + " " + e.desc());
    }

    /** What the last widenLive answered (self-test). */
    volatile List<String> lastWiden = List.of();

    /**
     * A saved access widener whose only change is added entries: they go into the running game (the agent's
     * AccessWiden) and the compile overlay (AwOverlay) at once, and null is returned; otherwise why a restart is needed
     * (a removed entry, an unreadable file, a class the agent will not touch).
     */
    String widenLive(Workspace.Target t, long gamePid, Path agentJar, Path aw) {
        String name = aw.getFileName().toString();
        try {
            var sets = fast.sourceSets(t);
            Path loaded = null;
            if (sets != null)
                for (var s : sets)
                    for (Path srcDir : s.resSrcDirs())
                        if (s.resOut() != null && aw.toAbsolutePath().normalize().startsWith(srcDir.toAbsolutePath().normalize()))
                            loaded = s.resOut().resolve(srcDir.toAbsolutePath().normalize().relativize(aw.toAbsolutePath().normalize()));
            if (loaded == null || !Files.exists(loaded)) return name + " changed (the game's copy was not found)";
            var before = AwOverlay.parse(Files.readString(loaded)); var after = AwOverlay.parse(Files.readString(aw));
            if (before == null || after == null) return name + " changed (not a widener this can read)";
            // an entry the game started with is baked into Loom's Minecraft jar (the access it had before is gone): a
            // restart. One added during this run can be taken out again: its class is redefined from the original bytes
            // with the live entries that remain.
            for (var e : before) if (!after.contains(e)) return name + " changed (" + e.owner() + " " + e.name() + " no longer widened): restart to apply";
            var live = liveWidenings.computeIfAbsent(aw.toAbsolutePath().normalize() + "@" + gamePid, k -> new LinkedHashSet<>());
            var added = after.stream().filter(e -> !before.contains(e) && !live.contains(e)).toList();
            var takenOut = live.stream().filter(e -> !after.contains(e)).toList();
            var resetOwners = new LinkedHashSet<String>();
            for (var e : takenOut) resetOwners.add(e.owner());
            if (added.isEmpty() && takenOut.isEmpty()) return null;
            // compiling sees the new access at once (the overlay goes first on fast compile's class path)
            var cp = new LinkedHashSet<Path>();
            for (var s : sets) cp.addAll(s.classpath());
            if (AwOverlay.build(List.of(aw), new ArrayList<>(cp), fast.awOverlayDir(t)) < 0) return name + " changed (could not apply it for compiling)";
            fast.invalidateManagers();
            Path list = agentJar.getParent().resolve("widen-" + ProcessHandle.current().pid() + "-" + (++counter) + ".txt");
            var lines = new ArrayList<String>();
            for (String owner : resetOwners) {   // that owner's remaining live entries, sent anew after a reset
                lines.add("reset " + owner);
                for (var e : after) if (e.owner().equals(owner) && !before.contains(e)) lines.add(widenerLine(e));
            }
            for (var e : added) if (!resetOwners.contains(e.owner())) lines.add(widenerLine(e));
            Files.write(list, lines, StandardCharsets.UTF_8);
            Path result = Path.of(list + ".result");
            Files.deleteIfExists(result);
            loadAgent(gamePid, agentJar, "codingFps=" + Settings.codingFps() + ",widen=" + list);
            for (int i = 0; i < 100 && !Files.exists(result); i++) Thread.sleep(100);
            if (!Files.exists(result)) return name + " changed (no answer from the game)";
            var answer = Files.readAllLines(result, StandardCharsets.UTF_8);
            lastWiden = List.copyOf(answer);
            Files.deleteIfExists(list); Files.deleteIfExists(result);
            var refused = answer.stream().filter(s -> s.startsWith("error") || s.startsWith("restart needed")).toList();
            if (!refused.isEmpty()) return name + " changed (" + String.join("; ", refused) + ")";
            live.addAll(added);
            takenOut.forEach(live::remove);
            log.accept("access widener: " + added.size() + " entr" + (added.size() == 1 ? "y" : "ies") + " added, " + takenOut.size()
                    + " taken out in the running game (" + answer.stream().filter(s -> s.startsWith("widened")).count() + " loaded class(es) redefined)");
            return null;
        } catch (Exception e) {
            return name + " changed (" + e + ")";
        }
    }

    static final List<String> MIXIN_LISTS = List.of("mixins", "client", "server");

    /**
     * What a mixins.json change adds, per list ("mixins", "client", "server"), into {@code added}, and removes
     * ("list:Class") into {@code removed}; null if it is only that, else why it needs a restart. {@code live}: the
     * "list:Class" this game runs now.
     */
    static String mixinChanges(com.google.gson.JsonObject before, com.google.gson.JsonObject after, Set<String> live,
                               Map<String, List<String>> added, List<String> removed) {
        var keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys)
            if (!MIXIN_LISTS.contains(k) && !Objects.equals(before.get(k), after.get(k))) return k + ": only added or removed mixins apply while the game runs";
        var now = mixinEntries(after);
        for (String e : live) if (!now.contains(e)) removed.add(e);
        for (String list : MIXIN_LISTS) {
            var add = names(after, list).stream().filter(m -> !live.contains(list + ":" + m)).toList();
            if (!add.isEmpty()) added.put(list, add);
        }
        return null;
    }

    /** A mixins.json's mixins as "list:Class". */
    static Set<String> mixinEntries(com.google.gson.JsonObject o) {
        var out = new LinkedHashSet<String>();
        for (String list : MIXIN_LISTS) for (String m : names(o, list)) out.add(list + ":" + m);
        return out;
    }

    /**
     * A project class other than the mixin (and its nested classes) that names an interface the mixin puts on its
     * targets, as "Class uses Interface"; null if none. The mixin's own class file gone, only the mixin's name is looked for.
     */
    private String interfaceStillUsed(Workspace.Target t, String mixin) throws IOException {
        String own = mixin.replace('.', '/');
        var roots = classRoots(t);
        var interfaces = new ArrayList<String>();
        boolean found = false;
        for (Path root : roots) {
            Path cls = root.resolve(own + ".class");
            if (Files.isRegularFile(cls)) { interfaces.addAll(StartupCode.mixinInterfaces(Files.readAllBytes(cls))); found = true; break; }
        }
        if (!found) interfaces.add(own);
        interfaces.removeIf(i -> i.startsWith("java/"));
        if (interfaces.isEmpty()) return null;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    String name = root.relativize(f).toString().replace('\\', '/');
                    name = name.substring(0, name.length() - ".class".length());
                    if (name.equals(own) || name.startsWith(own + "$")) continue;
                    String text = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);   // names sit in the constant pool as-is
                    for (String i : interfaces)
                        if (!name.equals(i) && text.contains(i)) return name.replace('/', '.') + " uses " + i.replace('/', '.');
                }
            }
        }
        return null;
    }

    /**
     * Supertypes (internal names) a class gained or lost that are not the project's own and not java/lang/Object,
     * dotted: such a class is held back (JBR nulled unrelated fields after a JDK interface was added).
     */
    static List<String> foreignSupertypes(Set<String> changes, Set<String> own) {
        return changes.stream().filter(s -> !own.contains(s) && !s.equals("java/lang/Object")).map(s -> s.replace('/', '.')).sorted().toList();
    }

    private static List<String> names(com.google.gson.JsonObject o, String list) {
        var out = new ArrayList<String>();
        if (o.has(list) && o.get(list).isJsonArray()) o.getAsJsonArray(list).forEach(e -> out.add(e.getAsString()));
        return out;
    }

    /**
     * data/<namespace>/<folder>: folders of the registries a server reads only when a world opens (dynamic registries,
     * Minecraft 1.21.1), so /reload does not pick them up; the world is left and opened again instead.
     */
    static final Set<String> WORLD_DATA = Set.of("worldgen", "dimension", "dimension_type", "damage_type", "enchantment",
            "enchantment_provider", "chat_type", "trim_material", "trim_pattern", "banner_pattern", "painting_variant",
            "wolf_variant", "jukebox_song");

    /** What the last rejoin for changed world data answered (self-test). */
    volatile String lastRejoin = "";

    /** Wait (up to {@code seconds}) until the fast path has its class path; false if it never got one. */
    boolean awaitFastCompile(Workspace.Target t, int seconds) throws InterruptedException {
        fast.prepareAsync(t);
        for (int i = 0; i < seconds * 4 && !fast.ready(t); i++) {
            if (!fast.preparing(t)) return fast.ready(t);
            Thread.sleep(250);
        }
        return fast.ready(t);
    }

    /** What the last swap did, for callers that want to check (self-test). */
    volatile List<String> lastResult = List.of();

    /** Remember the current class files (call when the game starts, so the first swap only sends what changed since). */
    void snapshot(Workspace.Target t) {
        try {
            snapshot = scan(t);
            sourcesSince = System.currentTimeMillis();
            classBytes.clear();
            for (Path p : snapshot.keySet()) classBytes.put(p, Files.readAllBytes(p));   // a mod's classes: small
        } catch (IOException e) { snapshot = null; }
        snapshotMetadata(t);
        fast.prepareAsync(t);   // ask Gradle for the class path once, in the background
    }

    /**
     * Mod metadata sources as they were when this game started, so a later change can be filled in without Gradle
     * ({@link MetadataTemplate}): this text is what produced the output the game reads. Found by walking the build,
     * since the source sets may not be known yet at the first start.
     */
    private final Map<Path, String> metadataTemplates = new java.util.concurrent.ConcurrentHashMap<>();

    private void snapshotMetadata(Workspace.Target t) {
        metadataTemplates.clear();
        Path root = t.buildRoot();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 9, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                    String n = d.getFileName() == null ? "" : d.getFileName().toString();
                    return !d.equals(root) && (n.startsWith(".") || n.equals("build") || n.equals("run") || n.equals("out")
                            || n.equals("node_modules")) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                    if (MetadataTemplate.isMetadata(f.getFileName().toString()) && f.toString().replace('\\', '/').contains("/resources/"))
                        try { metadataTemplates.put(f.toAbsolutePath().normalize(), Files.readString(f)); } catch (IOException ignored) { }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { }
    }

    /**
     * A changed metadata file filled in from the last Gradle output and written to {@code to} (where the game reads it).
     * Null when done, else why Gradle has to do it.
     */
    private String expandMetadata(Path src, Path to) throws IOException {
        Path key = src.toAbsolutePath().normalize();
        String template = metadataTemplates.get(key);
        if (template == null) return "not seen when the game started";
        if (!Files.isRegularFile(to)) return "no build output to learn its values from";
        String last = Files.readString(to);
        var values = MetadataTemplate.learn(template, last);
        if (values == null) return "its placeholders could not be read from the last build";
        String now = Files.readString(src);
        String filled = MetadataTemplate.renderLike(now, values, last);
        if (filled == null) {
            var unknown = new ArrayList<>(MetadataTemplate.names(now));
            unknown.removeAll(values.keySet());
            return unknown.isEmpty() ? "it uses template syntax beyond ${name}" : "new placeholder(s) " + String.join(", ", unknown);
        }
        Files.createDirectories(to.getParent());
        Files.writeString(to, filled);
        metadataTemplates.put(key, now);   // the output now comes from this text
        log.accept("relaunch: " + src.getFileName() + " filled in without Gradle" + (values.isEmpty() ? "" : " (" + String.join(", ", values.keySet()) + ")"));
        return null;
    }

    /** A class whose changed startup code can run again in the game (StartupCode.rerunMethods). */
    record Rerun(StartupCode.Rerun plan, List<String> startupChanges) {}
    private final Map<Path, Rerun> rerunnable = new LinkedHashMap<>();

    /**
     * Safety net: takes a snapshot of the open world (SulfurPanel.snapshot) right before init code runs again in the
     * game; null when no world is open. Set by the panel.
     */
    volatile java.util.function.Supplier<Path> beforeInitRerun;
    /** The world as it was before the last init code run (null: none taken), and when it was taken. */
    volatile Path lastInitSnapshot;
    volatile long lastInitSnapshotAt;
    /** Set when the last run failed in the game: the snapshot to go back to. */
    volatile Path failedInitSnapshot;

    /** What the last runtime registration answered ("registered ResourceKey[...]", "error ..."), for the self-test. */
    volatile List<String> lastRegistration = List.of();

    /** Last swapped bytes of every class file, to see what a change touched (startup code). */
    private final Map<Path, byte[]> classBytes = new HashMap<>();

    /** A swap was asked for while one ran (a save during it): compile again when that one ends. */
    private final AtomicBoolean again = new AtomicBoolean();

    /**
     * {@link #swap}, then again for saves that came in while it ran (up to a few rounds). True if any round sent
     * something, so the caller's restart advice is about this call and not about an earlier swap.
     */
    boolean swapCatchingUp(Workspace.Target t, long gamePid, Path agentJar) {
        deferred.set(false);
        boolean ran = swap(t, gamePid, agentJar);
        if (deferred.get()) return false;   // left to the swap that runs: its caller catches up
        for (int i = 0; i < 5 && again.getAndSet(false); i++) {
            deferred.set(false);
            ran |= swap(t, gamePid, agentJar);
            if (deferred.get()) break;      // another caller's swap is running and takes the save
        }
        return ran;
    }

    /** Set on the thread whose swap found another one running (and marked {@link #again}). */
    private final ThreadLocal<Boolean> deferred = ThreadLocal.withInitial(() -> false);

    /** Compile, diff, redefine. Blocking; call off the EDT. Returns false if it could not run. */
    boolean swap(Workspace.Target t, long gamePid, Path agentJar) {
        if (t == null || gamePid <= 0 || agentJar == null) { log.accept("hotswap: no running game / agent"); return false; }
        while (!busy.compareAndSet(false, true)) {
            // A save while a swap runs: that swap compiled before it, so it would be lost. Mark it; the running swap's
            // caller (swapCatchingUp) compiles again when it ends. Re-checked, in case that swap just ended.
            again.set(true);
            if (busy.get()) { deferred.set(true); log.accept("hotswap: already running; compiling again when it ends"); return false; }
        }
        again.set(false);   // this swap compiles every save made before it
        try {
            long t0 = System.currentTimeMillis();
            boolean fresh = snapshot == null;   // attached without a snapshot: don't know which sources changed
            if (fresh) snapshot = scan(t);
            adoptNewClassFiles(t);
            long started = System.currentTimeMillis();
            FastCompile.Result r = FastCompile.Result.UNAVAILABLE;
            int[] n = {0};
            if (!fresh) r = fast.compile(t, sourcesSince - 1000, n);   // 1 s slack for coarse file times
            if (r == FastCompile.Result.FAILED) return false;           // real compile errors: keep sourcesSince, retry them next time
            if (r == FastCompile.Result.OK) lastCompiler = fast.lastMode;
            else {
                fast.prepareAsync(t);
                if (!compile(t)) return false;
                lastCompiler = "gradle";
            }
            sourcesSince = started;
            var changed = new ArrayList<Path>();
            var startup = new ArrayList<String>();
            rerunnable.clear();
            var constantOwners = new LinkedHashSet<String>();
            apiOwners.clear();
            foreignHierarchy.clear();
            collectChanged(changed, startup, constantOwners, t);
            // A changed compile-time constant (static final int/float/String...) is copied into every class that reads
            // it; fast compile only rebuilt the edited file, so the readers would go on with the old value. Recompile
            // the sources that mention the class and send what that changes too. (Gradle does this itself.)
            // A method or field other classes use is gone or changed its signature: their old code would fail at run time
            // (NoSuchMethodError). Compile them against the new one; if they no longer compile, nothing is sent.
            if (!apiOwners.isEmpty() && r == FastCompile.Result.OK) {
                var owners = new LinkedHashSet<>(apiOwners);
                int users = fast.compileUsersOf(t, owners);
                if (users < 0 && fast.sourceSets(t) != null) {   // the errors are in the log above
                    log.accept("hotswap: " + String.join(", ", owners) + " changed a method or field that other files use, and they no longer compile"
                            + " against it: fix them (nothing was sent)");
                    lastResult = List.of("compile failed: callers of " + String.join(", ", owners));
                    sourcesSince = started - 1;   // the same files again with the next save
                    return false;
                }
                if (users > 0) {
                    collectChanged(changed, startup, constantOwners, t);
                    log.accept("hotswap: " + String.join(", ", owners) + " changed a signature other files use: recompiled " + users + " file(s) that use it");
                }
                constantOwners.removeAll(owners);   // compiled already
            }
            if (!constantOwners.isEmpty() && r == FastCompile.Result.OK) {
                int users = fast.compileUsersOf(t, constantOwners);
                if (users < 0) log.accept("hotswap: constant changed in " + constantOwners + " but its users could not be recompiled: restart to apply it everywhere");
                else {
                    collectChanged(changed, startup, new LinkedHashSet<>(), t);
                    log.accept("hotswap: constant changed in " + String.join(", ", constantOwners) + ": recompiled " + users + " file(s) that use it");
                }
            }
            lastStartupChanges = List.copyOf(startup);
            lastChangedFiles = List.copyOf(changed);
            // A superclass / interface change is fine for the JetBrains Runtime between the project's own types, but adding
            // a JDK interface (Supplier) to one class made it null unrelated Supplier fields in Minecraft, and the game
            // crashed. Those classes are not sent; they need a restart.
            var held = new ArrayList<String>();
            if (!foreignHierarchy.isEmpty()) {
                var own = ownClasses(t);
                for (var e : foreignHierarchy.entrySet()) {
                    var foreign = foreignSupertypes(e.getValue(), own);
                    if (foreign.isEmpty()) continue;
                    changed.remove(e.getKey());
                    classBytes.put(e.getKey(), bytesBefore.get(e.getKey()));   // the game still runs the old one
                    held.add("restart needed: " + binaryName(t, e.getKey()) + " (superclass or interfaces changed: " + String.join(", ", foreign) + ")");
                }
            }
            if (changed.isEmpty()) {
                lastResult = held.isEmpty() ? List.of("redefined 0") : List.copyOf(held);
                log.accept(held.isEmpty() ? "hotswap: no class changed" : "hotswap: " + String.join("; ", held));
                return true;
            }

            Path dir = agentJar.getParent();
            Path list = dir.resolve("hotswap-" + ProcessHandle.current().pid() + "-" + (++counter) + ".txt");
            var lines = new ArrayList<String>();
            for (Path c : changed) lines.add(binaryName(t, c) + "\t" + c);
            Files.write(list, lines, StandardCharsets.UTF_8);
            Path result = Path.of(list + ".result");
            Files.deleteIfExists(result);
            // registration / init code changed: the agent runs it again around the redefinition (RuntimeRegister),
            // after a snapshot of the open world (a run that goes wrong can be undone)
            Path register = rerunnable.isEmpty() ? null : registerList(t, agentJar);
            failedInitSnapshot = null;
            if (register != null && beforeInitRerun != null) {
                Path snap = beforeInitRerun.get();
                if (snap != null) { lastInitSnapshot = snap; lastInitSnapshotAt = System.currentTimeMillis(); }
            }

            try {
                var vm = com.sun.tools.attach.VirtualMachine.attach(Long.toString(gamePid));
                try { vm.loadAgent(agentJar.toString(), "codingFps=" + Settings.codingFps() + ",redefine=" + list + (register == null ? "" : ",register=" + register)); }
                finally { vm.detach(); }
            } catch (com.sun.tools.attach.AgentLoadException e) {
                if (!String.valueOf(e.getMessage()).endsWith(": 0")) throw e;   // Java 8 game: reply-format quirk
            }
            for (int i = 0; i < (register == null ? 100 : 300) && !Files.exists(result); i++) Thread.sleep(100);   // with register: on the render thread
            if (!Files.exists(result)) { log.accept("hotswap: no answer from the game"); return false; }
            lastResult = Files.readAllLines(result, StandardCharsets.UTF_8);
            if (!held.isEmpty()) { var all = new ArrayList<>(lastResult); all.addAll(held); lastResult = List.copyOf(all); }
            // a mixin class is never loaded by name: only Mixin's hotswap agent (added by Run client) picks a change up.
            // Without it (a game attached later), the change would be silently dropped: a restart applies it.
            var mixinsNotApplied = new ArrayList<String>();
            for (String line : lastResult)
                if (line.startsWith("not loaded: "))
                    for (Path c : changed)
                        if (binaryName(t, c).equals(line.substring("not loaded: ".length())) && !newClassFiles.contains(c) && classBytes.get(c) != null && !StartupCode.mixinTargets(classBytes.get(c)).isEmpty())
                            mixinsNotApplied.add("restart needed: " + line.substring("not loaded: ".length()) + " (a mixin, and Mixin's hotswap agent is not in this game)");
            // without the JetBrains Runtime, Mixin's agent answers "redefined" for a changed mixin but the target keeps the
            // old code (seen in the self-test's run without it): say restart instead of claiming it applied
            if (Jbr.find(t.profile().javaMajor()) == null)
                for (Path c : changed)
                    if (!newClassFiles.contains(c) && classBytes.get(c) != null && !StartupCode.mixinTargets(classBytes.get(c)).isEmpty())
                        mixinsNotApplied.add("restart needed: " + binaryName(t, c) + " (a mixin: without the JetBrains Runtime it is not applied to its targets reliably)");
            if (!mixinsNotApplied.isEmpty()) { var all = new ArrayList<>(lastResult); all.addAll(mixinsNotApplied); lastResult = List.copyOf(all); }
            Files.deleteIfExists(list); Files.deleteIfExists(result);
            String summary = String.join(", ", lastResult.stream().filter(s -> !s.startsWith("ms ")).toList());
            log.accept("hotswap (" + lastCompiler + ", " + changed.size() + " class file(s), " + (System.currentTimeMillis() - t0) + " ms): " + summary);
            if (register != null) awaitRegistration(register);
            return true;
        } catch (Exception e) {
            log.accept("hotswap failed: " + e);
            return false;
        } finally {
            busy.set(false);
        }
    }

    /** Fabric/Quilt access wideners and class tweakers: Loader applies them as the game starts, Loom only for compiling. */
    static boolean isWidener(String fileName) { return fileName.endsWith(".accesswidener") || fileName.endsWith(".classtweaker"); }

    /** A Gradle build applied the current widener to Loom's jar: the compile overlay goes. */
    void clearAwOverlay(Workspace.Target t) { fast.clearAwOverlay(t); }

    /** Files read only when the game starts (a running game cannot take them): restart to apply. */
    static boolean needsGradle(String fileName) {
        return fileName.equals("fabric.mod.json") || fileName.equals("quilt.mod.json") || fileName.endsWith("mods.toml")
                || fileName.endsWith(".accesswidener") || fileName.endsWith(".classtweaker") || fileName.equals("accesstransformer.cfg");
    }

    /**
     * Before the game starts again without Gradle ({@link QuickRelaunch}): what Gradle's runClient would have built,
     * done the hotswap way. Sources saved since the last compile are compiled into the output folders, resources
     * changed since {@code since} are copied where the game reads them. Null when the outputs are up to date, else why
     * Gradle has to build (compile errors, mod metadata, no fast compile for this build). Blocking.
     */
    String prepareRelaunch(Workspace.Target t, long since) {
        var sets = fast.sourceSets(t);
        if (sets == null) return "the build's class path is not read yet";
        // a save right before the restart starts a hotswap of its own: let it finish (it writes the same outputs)
        boolean got = false;
        for (int i = 0; i < 150 && !(got = busy.compareAndSet(false, true)); i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (!got) return "a hotswap is still running after 15 s";
        try {
            int copied = 0;
            var wideners = new ArrayList<Path>();
            for (var s : sets) {
                if (isTestSet(s.name())) continue;
                if (s.resOut() == null) continue;
                for (Path srcDir : s.resSrcDirs()) {
                    if (!Files.isDirectory(srcDir)) continue;
                    try (Stream<Path> files = Files.walk(srcDir)) {
                        for (Path f : files.filter(Files::isRegularFile).toList()) {
                            long m = Files.getLastModifiedTime(f).toMillis();
                            if (m <= since) continue;
                            Path to = s.resOut().resolve(srcDir.relativize(f));
                            if (Files.exists(to) && Files.getLastModifiedTime(to).toMillis() >= m) continue;
                            String name = f.getFileName().toString();
                            if (MetadataTemplate.isMetadata(name)) {   // filled in the way processResources would
                                String why = expandMetadata(f, to);
                                if (why != null) return name + " changed (" + why + ")";
                                copied++;
                                continue;
                            }
                            if (isWidener(name)) wideners.add(f);   // Loader applies it at start; compile needs the overlay
                            else if (needsGradle(name)) return name + " changed";
                            Files.createDirectories(to.getParent());
                            Files.copy(f, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            copied++;
                        }
                    }
                }
            }
            if (copied > 0) log.accept("relaunch: " + copied + " resource file(s) copied");
            if (!wideners.isEmpty()) {
                var cp = new LinkedHashSet<Path>();
                for (var s : sets) cp.addAll(s.classpath());
                int classes = AwOverlay.build(wideners, new ArrayList<>(cp), fast.awOverlayDir(t));
                if (classes < 0) return wideners.get(0).getFileName() + " changed (could not apply it for compiling)";
                fast.invalidateManagers();
                log.accept("relaunch: " + wideners.stream().map(w -> w.getFileName().toString()).toList()
                        + " applied by Fabric Loader at start, no Loom remap (" + classes + " class(es) widened for compiling)");
            }
            long started = System.currentTimeMillis();
            int[] n = {0};
            var r = fast.compile(t, sourcesSince - 1000, n);
            if (r == FastCompile.Result.FAILED) return "compile errors";
            if (r == FastCompile.Result.UNAVAILABLE) return "these sources need Gradle to compile";
            if (n[0] > 0) log.accept("relaunch: compiled " + n[0] + " source file(s) (" + fast.lastMode + ")");
            sourcesSince = started;
            return null;
        } catch (Exception e) {
            return "could not bring the build output up to date: " + e;
        } finally {
            busy.set(false);
        }
    }

    private boolean compile(Workspace.Target t) throws IOException, InterruptedException {
        Path wrapper = GameLauncher.findWrapper(t.buildRoot());
        if (wrapper == null) { log.accept("hotswap: no gradlew.bat for " + t.where()); return false; }
        String prefix = t.projectPath().equals(":") ? "" : t.projectPath() + ":";
        var cmd = new ArrayList<>(List.of(wrapper.toString(), prefix + "classes"));
        if (Files.isDirectory(t.dir().resolve("src/client"))) cmd.add(prefix + "clientClasses");
        if (!t.task().equals("runClient") && !t.projectPath().equals(":")) cmd.add("classes");
        cmd.addAll(List.of("--console=plain", "-q"));
        var p = GradleJava.apply(new ProcessBuilder(cmd).directory(t.buildRoot().toFile()).redirectErrorStream(true), t).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            log.accept("hotswap: compile failed (nothing sent; the next save compiles again, so a change spread over several files goes in once they all compile)");
            out.lines().filter(l -> l.contains("error:") || l.startsWith("> ") || l.contains("What went wrong")).limit(8).forEach(l -> log.accept("  " + l.strip()));
            return false;
        }
        return true;
    }

    /**
     * Class output folders of the game's code: every source set fast compile knows (with a run config such as
     * runAllModsClient, other modules' too), else the target's build/classes/<lang>/<sourceSet>.
     */
    private List<Path> classRoots(Workspace.Target t) throws IOException {
        var roots = new ArrayList<Path>();
        var sets = fast.sourceSets(t);
        if (sets != null)
            for (var s : sets) {
                if (isTestSet(s.name())) continue;
                roots.add(s.out());
                if (s.kotlin() != null) roots.add(s.kotlin().out());
            }
        if (roots.isEmpty()) {
            Path classes = t.dir().resolve("build/classes");
            if (Files.isDirectory(classes))
                try (Stream<Path> langs = Files.list(classes)) {
                    for (Path lang : langs.filter(Files::isDirectory).toList())
                        try (Stream<Path> ss = Files.list(lang)) { ss.filter(Files::isDirectory).forEach(roots::add); }
                }
        }
        return roots;
    }

    /**
     * Class files whose bytes differ from what the game was last given, added to {@code changed}; the snapshot moves on.
     * A newer file is not yet a changed class: a Gradle build rewrites every class file of a module, and sending them
     * all (751 for a one-line edit) crashed the game inside the JVM's class redefinition.
     */
    /** Classes whose methods or fields other classes use changed (StartupCode.apiOwnerNames), during one swap. */
    private final Set<String> apiOwners = new LinkedHashSet<>();

    private void collectChanged(List<Path> changed, List<String> startup, Set<String> constantOwners, Workspace.Target t) throws IOException {
        Map<Path, Long> now = scan(t);
        for (var e : now.entrySet()) {
            Path c = e.getKey();
            Long before = snapshot.get(c);
            if (before != null && before >= e.getValue()) continue;
            byte[] after = Files.readAllBytes(c), old = classBytes.get(c);
            if (old != null && Arrays.equals(old, after)) continue;
            if (old == null) newClassFiles.add(c); else newClassFiles.remove(c);
            // lambda methods keep the names the game knows them by (javac renumbers them class-wide); the class file is
            // rewritten too, so a restart, a new mixin or the next comparison sees what the game runs
            try {
                byte[] stable = LambdaNames.stabilize(old, after);
                // a lambda the new code dropped: objects made from it before the swap still call it, so it stays
                var kept = new ArrayList<String>();
                stable = LambdaNames.keepRemoved(old, stable, kept);
                if (!kept.isEmpty())
                    log.accept("hotswap: kept " + kept + " in " + new org.jetbrains.org.objectweb.asm.ClassReader(stable).getClassName().replace('/', '.')
                            + " (removed from the code, but objects made before the swap may still call it; unused by the new code)");
                if (stable != after) {
                    Files.write(c, stable);
                    after = stable;
                    if (old != null && Arrays.equals(old, after)) continue;
                }
            } catch (Throwable ignored) { }
            try {
                var st = StartupCode.changed(old, after);
                startup.addAll(st);
                var plan = st.isEmpty() ? null : StartupCode.rerunPlan(old, after);
                if (plan != null) rerunnable.put(c, new Rerun(plan, st));
                else rerunnable.remove(c);
            } catch (Throwable ignored) { }
            try { constantOwners.addAll(StartupCode.constantOwnerNames(old, after)); } catch (Throwable ignored) { }
            try { apiOwners.addAll(StartupCode.apiOwnerNames(old, after)); } catch (Throwable ignored) { }
            try {
                var sup = StartupCode.supertypeChanges(old, after);
                if (!sup.isEmpty()) { foreignHierarchy.put(c, sup); bytesBefore.put(c, old); }
            } catch (Throwable ignored) { }
            classBytes.put(c, after);
            if (!changed.contains(c)) changed.add(c);
        }
        snapshot = now;
    }

    /**
     * Class files the snapshot does not know yet, from output folders it did not cover (fast compile read the class
     * path after the game started, and a run config brings other modules): what they hold now is what the game runs,
     * so they become the baseline instead of being sent as changes. Before compiling.
     */
    private void adoptNewClassFiles(Workspace.Target t) throws IOException {
        for (var e : scan(t).entrySet()) {
            if (snapshot.containsKey(e.getKey())) continue;
            snapshot.put(e.getKey(), e.getValue());
            classBytes.put(e.getKey(), Files.readAllBytes(e.getKey()));
        }
    }

    /** All .class files under the class roots, with their modification times. */
    private Map<Path, Long> scan(Workspace.Target t) throws IOException {
        var m = new HashMap<Path, Long>();
        for (Path root : classRoots(t)) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".class")).toList()) m.put(p, Files.getLastModifiedTime(p).toMillis());
            }
        }
        return m;
    }

    /** <class root>/a/b/C$D.class -> a.b.C$D */
    private String binaryName(Workspace.Target t, Path classFile) throws IOException {
        Path root = null;
        for (Path r : classRoots(t)) if (classFile.startsWith(r) && (root == null || r.getNameCount() > root.getNameCount())) root = r;
        String s = (root != null ? root.relativize(classFile) : classFile.getFileName()).toString().replace('\\', '/');
        return s.substring(0, s.length() - ".class".length()).replace('/', '.');
    }
}
