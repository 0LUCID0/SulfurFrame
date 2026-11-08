package dev.sulfurframe.idea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * World snapshots: ~/.sulfurframe/snapshots/<mc>/<world>/<yyyyMMdd-HHmmss>/ hold copies of a world folder.
 * Taking one while the game runs first has the integrated server write everything out ("save-all flush", through the
 * agent) so the copy is consistent. Restoring is a quick restart that swaps the world folder while the game is down;
 * the world being replaced is kept as a snapshot too ("before-restore-..."), so a restore can itself be undone.
 */
final class WorldSnapshots {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    static Path root(String mc, String world) {
        return SharedInstance.home().resolve("snapshots").resolve(mc == null ? "unknown" : mc).resolve(world);
    }

    /** Copy saves/<world> into a new snapshot; returns its folder. */
    static Path take(Path saves, String world, String mc, String label) throws IOException {
        Path from = saves.resolve(world);
        if (!Files.isDirectory(from)) throw new NoSuchFileException(from.toString());
        Path to = root(mc, world).resolve(LocalDateTime.now().format(STAMP) + (label == null ? "" : "-" + label));
        copyTree(from, to);
        return to;
    }

    /** Snapshots of a world, newest first. */
    static List<Path> list(String mc, String world) throws IOException {
        Path r = root(mc, world);
        if (!Files.isDirectory(r)) return List.of();
        try (Stream<Path> s = Files.list(r)) { return s.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList(); }
    }

    /** Replace saves/<world> with a snapshot (the game must not have the world open). The old one is kept as a snapshot. */
    static void restore(Path saves, String world, String mc, Path snapshot) throws IOException {
        Path dir = saves.resolve(world);
        if (Files.isDirectory(dir)) {
            take(saves, world, mc, "before-restore");
            deleteTree(dir);
        }
        copyTree(snapshot, dir);
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                if (p.getFileName().toString().equals("session.lock")) continue;   // held by a running game
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else { Files.createDirectories(t.getParent()); Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    static String label(Path snapshot) { return snapshot.getFileName().toString(); }

    static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    private WorldSnapshots() {}
}
