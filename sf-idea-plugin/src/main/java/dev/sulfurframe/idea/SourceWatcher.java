package dev.sulfurframe.idea;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Watches the build's source folders on disk, so a file changed outside the IDE (an external editor, a coding agent,
 * git) is hotswapped like a save. The IDE's own listener only hears about such a change once IntelliJ refreshes its
 * virtual file system, which normally waits until its window is activated: an edit made from elsewhere did nothing.
 *
 * Every "src" folder of the build (all modules: a run config such as runAllModsClient runs several) is watched,
 * recursively. Build output, Gradle and run folders are never under src, so our own compiles do not echo back. An IDE
 * save is seen here too; the caller's debounce merges the two into one swap.
 */
final class SourceWatcher implements AutoCloseable {
    private final WatchService ws;
    private final Map<WatchKey, Path> dirs = new HashMap<>();
    private final Thread thread;
    private volatile boolean tree = System.getProperty("os.name", "").startsWith("Windows");   // one recursive watch per root

    private SourceWatcher(List<Path> roots, Consumer<Path> changed) throws IOException {
        ws = FileSystems.getDefault().newWatchService();
        for (Path r : roots) register(r);
        thread = Thread.ofPlatform().daemon().name("sf-source-watch").start(() -> loop(changed));
    }

    /** Watch the src folders under {@code buildRoot}; null if there are none or watching is not possible. */
    static SourceWatcher start(Path buildRoot, Consumer<Path> changed) {
        try {
            var roots = srcFolders(buildRoot);
            return roots.isEmpty() ? null : new SourceWatcher(roots, changed);
        } catch (IOException e) {
            return null;
        }
    }

    int rootCount() { return roots; }
    private int roots;

    /** "src" folders up to 4 levels below the build root, outside build/.gradle/run/out/node_modules and hidden folders. */
    static List<Path> srcFolders(Path buildRoot) throws IOException {
        var out = new ArrayList<Path>();
        try (Stream<Path> s = Files.walk(buildRoot, 4)) {
            s.filter(Files::isDirectory)
             .filter(d -> d.getFileName() != null && d.getFileName().toString().equals("src"))
             .filter(d -> { for (Path part : buildRoot.relativize(d)) { String n = part.toString();
                     if (n.equals("build") || n.equals("run") || n.equals("out") || n.equals("node_modules") || n.startsWith(".")) return false; }
                     return true; })
             .forEach(out::add);
        }
        return out;
    }

    private void register(Path root) throws IOException {
        roots++;
        if (tree) {
            // Windows watches a whole tree with one handle (JDK-specific modifier, present in JBR); without it, one
            // watch per folder
            try {
                WatchEvent.Modifier fileTree = com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE;
                dirs.put(root.register(ws, new WatchEvent.Kind<?>[]{StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY}, fileTree), root);
                return;
            } catch (Throwable notAvailable) {
                tree = false;
            }
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path d : s.filter(Files::isDirectory).toList()) registerOne(d);
        }
    }

    private void registerOne(Path d) throws IOException {
        dirs.put(d.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY), d);
    }

    /**
     * Tell IntelliJ the file changed, so an open editor shows the new text now and not only when the IDE window is next
     * activated (otherwise the game would show an outside edit before the code on screen does).
     */
    private static void refreshInIde(Path p) {
        try {
            var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(p);
            if (vf != null) vf.refresh(true, false);
        } catch (Throwable ignored) { }   // outside the IDE (tests): nothing to refresh
    }

    private void loop(Consumer<Path> changed) {
        try {
            while (true) {
                WatchKey key = ws.take();
                Path dir = dirs.get(key);
                for (WatchEvent<?> ev : key.pollEvents()) {
                    if (dir == null || !(ev.context() instanceof Path rel)) continue;
                    Path p = dir.resolve(rel);
                    if (!tree && ev.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(p)) { try { registerOne(p); } catch (IOException ignored) { } continue; }
                    if (Files.isRegularFile(p)) { refreshInIde(p); changed.accept(p); }
                }
                if (!key.reset()) dirs.remove(key);
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // closed
        }
    }

    @Override public void close() {
        try { ws.close(); } catch (IOException ignored) { }
        thread.interrupt();
    }
}
