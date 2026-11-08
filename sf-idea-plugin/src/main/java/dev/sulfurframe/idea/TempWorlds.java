package dev.sulfurframe.idea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Temporary worlds: test worlds that are gone once the game that played them has exited.
 *
 * Their folders are listed in ~/.sulfurframe/temp-worlds.txt. The list is swept when a game exits and when the plugin
 * starts (in case the IDE went away first); a folder is only deleted while no game holds it (session.lock not locked),
 * and only if its name carries the temporary marker, so a list gone wrong can never take a real world.
 */
final class TempWorlds {
    static final String PREFIX = "[Temp] ";

    static Path listFile() { return SharedInstance.home().resolve("temp-worlds.txt"); }

    private static final Set<String> pinned = Collections.synchronizedSet(new HashSet<>());

    /** A world about to be reopened (restart): skipped by sweeps until the new game has it. */
    /** The new game has started: the old one's exit sweep is over, let sweeps see the world again. */
    static void unpinAll() { pinned.clear(); }

    static void pin(String worldName) { if (worldName != null && !worldName.isEmpty()) { pinned.add(worldName); new Timer(true).schedule(new TimerTask() { public void run() { pinned.remove(worldName); } }, 180_000); } }

    static synchronized void register(Path worldDir) throws IOException {
        Files.createDirectories(listFile().getParent());
        var lines = new LinkedHashSet<>(read());
        lines.add(worldDir.toAbsolutePath().normalize().toString());
        Files.write(listFile(), lines, StandardCharsets.UTF_8);
    }

    /** Delete every listed temporary world that no game has open; keep the others listed. Returns how many went. */
    static synchronized int sweep(Consumer<String> log) {
        var keep = new ArrayList<String>();
        int gone = 0;
        for (String s : read()) {
            Path dir = Path.of(s);
            if (!Files.exists(dir)) continue;
            if (!dir.getFileName().toString().startsWith(PREFIX)) continue;   // never a real world
            if (pinned.contains(dir.getFileName().toString())) { keep.add(s); continue; }
            if (inUse(dir)) { keep.add(s); continue; }
            try { WorldSnapshots.deleteTree(dir); gone++; log.accept("temporary world removed: " + dir.getFileName()); }
            catch (IOException e) { keep.add(s); }
        }
        try { Files.write(listFile(), keep, StandardCharsets.UTF_8); } catch (IOException ignored) { }
        return gone;
    }

    /** A running game keeps session.lock locked. */
    static boolean inUse(Path world) {
        Path lock = world.resolve("session.lock");
        if (!Files.exists(lock)) return false;
        try (var ch = java.nio.channels.FileChannel.open(lock, StandardOpenOption.WRITE)) {
            var l = ch.tryLock();
            if (l == null) return true;
            l.release();
            return false;
        } catch (IOException e) {
            return true;   // cannot even open it: assume a game has it
        }
    }

    private static List<String> read() {
        try { return Files.exists(listFile()) ? Files.readAllLines(listFile(), StandardCharsets.UTF_8) : List.of(); }
        catch (IOException e) { return List.of(); }
    }

    private TempWorlds() {}
}
