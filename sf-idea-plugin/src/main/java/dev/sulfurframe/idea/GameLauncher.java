package dev.sulfurframe.idea;

import dev.sulfurframe.idea.profile.ProjectProfile;
import dev.sulfurframe.idea.profile.Workspace;

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

    void runClient(Workspace.Target target, BiConsumer<Long, Long> onWindow) {
        ProjectProfile profile = target.profile();
        Path dir = target.buildRoot();
        Path wrapper = findWrapper(dir);
        if (wrapper == null) { log.accept("no gradlew.bat in " + dir + " or its parents"); return; }
        Set<Long> before = new HashSet<>(dev.sulfurframe.idea.embed.NativeWindows.gameWindows());
        try {
            var cmd = new ArrayList<>(List.of(wrapper.toString(), target.runTask(), "--console=plain"));
            Path jbr = Jbr.find(profile.javaMajor());
            if (jbr != null) {   // DCEVM: hotswap may then add/remove methods and fields
                Path init = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "sf-jbr.init.gradle");
                Files.createDirectories(init.getParent());
                Files.writeString(init, Jbr.INIT_SCRIPT, StandardCharsets.UTF_8);
                cmd.addAll(List.of("--init-script", init.toString(), "-PsfJbr=" + jbr));
                log.accept("game runs on JetBrains Runtime " + jbr.getParent().getParent().getFileName() + " (enhanced hotswap: methods and fields can change)");
            } else {
                log.accept("no JetBrains Runtime " + profile.javaMajor() + " found (~/.jdks): hotswap limited to method bodies");
            }
            var pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            gradle = pb.start();
            log.accept("started " + wrapper + " " + target.runTask() + " for " + profile);
            Thread.ofPlatform().daemon().name("sf-gradle-out").start(() -> pump(gradle));
        } catch (IOException e) {
            log.accept("could not start Gradle: " + e);
            return;
        }
        Thread.ofPlatform().daemon().name("sf-wait-window").start(() -> {
            long deadline = System.currentTimeMillis() + 10 * 60_000;
            while (System.currentTimeMillis() < deadline && gradle.isAlive()) {
                for (long h : dev.sulfurframe.idea.embed.NativeWindows.gameWindows()) {
                    if (before.contains(h)) continue;
                    long pid = dev.sulfurframe.idea.embed.NativeWindows.pidOf(h);
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
            boolean why = false;   // after "* What went wrong:" show the reason lines, they are the useful part
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("* What went wrong")) { why = true; continue; }
                if (line.startsWith("* Try")) why = false;
                if (why && !line.isBlank()) log.accept("[gradle] " + line.strip());
                else if (line.matches("> Task \\S*:?runClient.*") || line.contains("BUILD") || line.contains("error:"))
                    log.accept("[gradle] " + line);
            }
        } catch (IOException ignored) { }
    }

    static Path findWrapper(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            Path w = d.resolve(System.getProperty("os.name", "").startsWith("Windows") ? "gradlew.bat" : "gradlew");
            if (Files.exists(w)) return w;
        }
        return null;
    }
}
