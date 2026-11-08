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
 *   options.txt, servers.dat, hotbar.nbt             copied in before a launch and back after the game exits (the game
 *                                                     rewrites these files whole, which would break a link)
 * When a project is first linked, the worlds already in its run/saves move into the shared folder; a world whose name
 * is taken there keeps its data under "name (project)". Nothing is deleted.
 */
final class SharedInstance {
    static final List<String> FOLDERS = List.of("saves", "resourcepacks", "screenshots", "shaderpacks");
    static final List<String> FILES = List.of("options.txt", "servers.dat", "hotbar.nbt");

    /** ~/.sulfurframe, or -Dsulfurframe.home (self-tests keep their worlds out of the real one). */
    static Path home() {
        String h = System.getProperty("sulfurframe.home");
        return h != null ? Path.of(h) : Path.of(System.getProperty("user.home"), ".sulfurframe");
    }

    static Path root() { return home().resolve("instances"); }

    static Path forVersion(String mc) { return root().resolve(mc == null || mc.isBlank() ? "unknown" : mc); }

    /** The dev run folder of a target (Loom, ModDevGradle and ForgeGradle all default to "run"). */
    static Path runDir(dev.sulfurframe.idea.profile.Workspace.Target t) { return t.dir().resolve("run"); }

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
        log.accept("shared instance " + mc + ": " + shared);
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
