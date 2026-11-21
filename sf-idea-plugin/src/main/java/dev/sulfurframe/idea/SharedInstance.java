package dev.sulfurframe.idea;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * One game folder per Minecraft version, shared by every mod project of that version.
 *
 * Each project's dev client normally plays in its own run/ folder, so worlds, options and key bindings are scattered
 * across projects. With sharing on, the project's run/ keeps its own config/, logs/ and mods/ but its world-ish folders
 * point into ~/.sulfurframe/instances/<mc version>/:
 *   saves, resourcepacks, screenshots, shaderpacks   linked (Windows: directory junction, no admin rights needed;
 *                                                     elsewhere: symbolic link)
 *   servers.dat, hotbar.nbt                          copied in before a launch and back after the game exits (the game
 *                                                     rewrites these files whole, which would break a link)
 *   options.txt                                      NOT copied any more: key bindings and options are merged through
 *                                                     the master profile by the agent (dev.sulfurframe.profile
 *                                                     .OptionsSync), across versions and launchers, and without moving
 *                                                     one project's own mod keys into another. Copying the whole file
 *                                                     between projects of a version did exactly that. A project whose
 *                                                     run/ has no options.txt yet still gets the shared one once.
 * When a project is first linked, the worlds already in its run/saves move into the shared folder; a world whose name
 * is taken there keeps its data under "name (project)". Nothing is deleted.
 */
final class SharedInstance {
    static final List<String> FOLDERS = List.of("saves", "resourcepacks", "screenshots", "shaderpacks");
    static final List<String> FILES = List.of("servers.dat", "hotbar.nbt");

    /** ~/.sulfurframe, or -Dsulfurframe.home (self-tests keep their worlds out of the real one). */
    static Path home() {
        String h = System.getProperty("sulfurframe.home");
        return h != null ? Path.of(h) : Path.of(System.getProperty("user.home"), ".sulfurframe");
    }

    static Path root() { return home().resolve("instances"); }

    static Path forVersion(String mc) { return root().resolve(mc == null || mc.isBlank() ? "unknown" : mc); }

    /**
     * The dev run folder of a target: what the build script sets as runDir / workingDir (Loom runDir = "...",
     * runDir("..."), or a local val/def variable pointing at it), resolved against the subproject folder; else "run".
     */
    static Path runDir(dev.sulfurframe.idea.profile.Workspace.Target t) {
        for (String name : new String[]{"build.gradle", "build.gradle.kts"}) {
            Path f = t.dir().resolve(name);
            if (!Files.isRegularFile(f)) continue;
            try {
                String s = dev.sulfurframe.idea.profile.ProjectProfile.stripComments(Files.readString(f));
                var vars = new HashMap<String, String>();
                var vm = java.util.regex.Pattern.compile("(?:val|var|def)\\s+(\\w+)\\s*(?::\\s*\\w+\\s*)?=\\s*[\"']([^\"']+)[\"']").matcher(s);
                while (vm.find()) vars.putIfAbsent(vm.group(1), vm.group(2));
                var rm = java.util.regex.Pattern.compile("\\b(?:runDir|workingDir)\\s*(?:=\\s*|\\(\\s*)(?:[\"']([^\"']+)[\"']|(\\w+))").matcher(s);
                if (rm.find()) {
                    String raw = rm.group(1) != null ? rm.group(1) : vars.get(rm.group(2));
                    if (raw != null && !raw.isBlank() && !raw.contains("$")) return t.dir().resolve(raw).normalize();
                }
            } catch (IOException ignored) { }
        }
        return t.dir().resolve("run");
    }

    /** Link the project's run folder to the shared one and bring the shared files in. Before a launch. */
    static void apply(Path run, String mc, String project, Consumer<String> log) throws IOException, InterruptedException {
        Path shared = forVersion(mc);
        Files.createDirectories(run);
        for (String name : FOLDERS) {
            Path target = shared.resolve(name), link = run.resolve(name);
            Files.createDirectories(target);
            if (linksTo(link, target)) continue;
            if (isLink(link)) {
                // already linked somewhere else (e.g. the shared folder of the version the project used before):
                // re-point it, never move what is behind it
                Files.delete(link);
            } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                int moved = moveContents(link, target, project);
                if (moved > 0) log.accept("shared instance: moved " + moved + " item(s) from " + link + " into " + target);
                deleteEmptyOrLink(link);
            }
            link(link, target);
        }
        for (String name : FILES) {
            Path from = shared.resolve(name), to = run.resolve(name);
            if (Files.exists(from) && (!Files.exists(to) || Files.getLastModifiedTime(from).compareTo(Files.getLastModifiedTime(to)) > 0))
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
        // a fresh project starts from the version's last options instead of the game's defaults; from then on the
        // master profile keeps them in step
        Path seed = shared.resolve("options.txt"), opts = run.resolve("options.txt");
        if (Files.exists(seed) && !Files.exists(opts)) Files.copy(seed, opts);
        log.accept("shared instance " + mc + ": " + shared);
    }

    /**
     * Minecraft's first-launch accessibility screen ("Welcome to Minecraft! ... Narrator") holds back --quickPlay*: in
     * a run folder whose options.txt does not say onboardAccessibility:false yet, the game stayed on that screen and
     * "restart into the same world" landed on the title screen. Set it before such a launch (the other options are
     * left alone; a missing options.txt gets just this line and the game fills in its defaults).
     */
    static void skipAccessibilityOnboarding(Path run, Consumer<String> log) {
        Path opts = run.resolve("options.txt");
        try {
            var lines = Files.exists(opts) ? new ArrayList<>(Files.readAllLines(opts)) : new ArrayList<String>();
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).startsWith("onboardAccessibility:")) continue;
                if (lines.get(i).equals("onboardAccessibility:false")) return;
                lines.set(i, "onboardAccessibility:false");
                found = true;
            }
            if (!found) lines.add("onboardAccessibility:false");
            Files.createDirectories(run);
            Files.write(opts, lines);
            log.accept("options.txt: first-launch accessibility screen turned off (it keeps quick play from entering the world)");
        } catch (IOException e) {
            log.accept("options.txt not updated: " + e.getMessage());
        }
    }

    /** After the game exited: keep the files it rewrote (options, servers, hotbars) for the next project. */
    static void collect(Path run, String mc) {
        Path shared = forVersion(mc);
        for (String name : FILES) {
            Path from = run.resolve(name), to = shared.resolve(name);
            try {
                if (Files.exists(from) && (!Files.exists(to) || Files.getLastModifiedTime(from).compareTo(Files.getLastModifiedTime(to)) > 0))
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { }
        }
    }

    static boolean linksTo(Path link, Path target) {
        try {
            if (!Files.exists(link)) return false;
            return link.toRealPath().equals(target.toRealPath()) && !link.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize());
        } catch (IOException e) { return false; }
    }

    /** A junction or symbolic link (its real path is somewhere else). Files.isSymbolicLink misses junctions. */
    static boolean isLink(Path p) {
        try {
            if (Files.isSymbolicLink(p)) return true;
            if (!Files.exists(p)) return false;
            return !p.toRealPath().equals(p.toAbsolutePath().normalize().getParent().toRealPath().resolve(p.getFileName()));
        } catch (IOException e) { return false; }
    }

    /** Move every entry of dir into target; on a name clash keep both ("name (project)", then numbered). */
    private static int moveContents(Path dir, Path target, String project) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return 0;
        int n = 0;
        List<Path> entries;
        try (Stream<Path> s = Files.list(dir)) { entries = s.toList(); }
        for (Path e : entries) {
            Path to = target.resolve(e.getFileName().toString());
            for (int i = 1; Files.exists(to, LinkOption.NOFOLLOW_LINKS); i++)
                to = target.resolve(e.getFileName() + " (" + project + (i > 1 ? " " + i : "") + ")");
            Files.move(e, to);
            n++;
        }
        return n;
    }

    private static void deleteEmptyOrLink(Path p) throws IOException {
        // a junction or symlink is removed without touching what it points to; a real folder only once empty
        Files.delete(p);
    }

    private static void link(Path link, Path target) throws IOException, InterruptedException {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            var p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) throw new IOException("mklink /J failed: " + out.strip());
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    private SharedInstance() {}
}
