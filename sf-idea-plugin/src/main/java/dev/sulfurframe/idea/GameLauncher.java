package dev.sulfurframe.idea;

import dev.sulfurframe.idea.profile.ProjectProfile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Starts the project's own dev client (its Gradle runClient task: Loom, ModDevGradle, ForgeGradle all name it so),
 * then waits for the new Minecraft window. The game is found by its window, not by process ancestry: the client is
 * started by the Gradle daemon, not by us.
 */
final class GameLauncher {
    private final Consumer<String> log;
    private volatile Process gradle;

    GameLauncher(Consumer<String> log) { this.log = log; }

    void runClient(ProjectProfile profile, BiConsumer<Long, Long> onWindow) {
        Path dir = profile.dir();
        Path wrapper = findWrapper(dir);
        if (wrapper == null) { log.accept("no gradlew.bat in " + dir + " or its parents"); return; }
        Set<Long> before = new HashSet<>(dev.sulfurframe.idea.embed.Win32Access.gameWindows());
        try {
            var pb = new ProcessBuilder(wrapper.toString(), "runClient", "--console=plain").directory(dir.toFile()).redirectErrorStream(true);
            gradle = pb.start();
            log.accept("started " + wrapper + " runClient for " + profile);
            Thread.ofPlatform().daemon().name("sf-gradle-out").start(() -> pump(gradle));
        } catch (IOException e) {
            log.accept("could not start Gradle: " + e);
            return;
        }
        Thread.ofPlatform().daemon().name("sf-wait-window").start(() -> {
            long deadline = System.currentTimeMillis() + 10 * 60_000;
            while (System.currentTimeMillis() < deadline && gradle.isAlive()) {
                for (long h : dev.sulfurframe.idea.embed.Win32Access.gameWindows()) {
                    if (before.contains(h)) continue;
                    long pid = dev.sulfurframe.idea.embed.Win32Access.pidOf(h);
                    log.accept("game window appeared (pid " + pid + ")");
                    onWindow.accept(pid, h);
                    return;
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
            log.accept(gradle.isAlive() ? "no game window after 10 minutes" : "Gradle exited with " + gradle.exitValue());
        });
    }

    private void pump(Process p) {
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; )
                if (line.startsWith("> Task") || line.contains("BUILD") || line.contains("FAILURE") || line.contains("error"))
                    log.accept("[gradle] " + line);
        } catch (IOException ignored) { }
    }

    static Path findWrapper(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            Path w = d.resolve("gradlew.bat");
            if (Files.exists(w)) return w;
        }
        return null;
    }
}
