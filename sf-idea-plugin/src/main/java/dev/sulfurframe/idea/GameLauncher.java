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

    void runClient(Workspace.Target target, BiConsumer<Long, Long> onWindow) { runClient(target, List.of(), onWindow); }

    /**
     * @param gameArgs extra program arguments for the game (e.g. --quickPlaySingleplayer world). They reach runClient
     *                 through an init script as Base64, so names with spaces or non-ASCII survive cmd.exe and Gradle.
     */
    void runClient(Workspace.Target target, List<String> gameArgs, BiConsumer<Long, Long> onWindow) {
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
            if (!gameArgs.isEmpty()) {
                Path init = tmp("sf-args.init.gradle");
                Files.writeString(init, ARGS_SCRIPT, StandardCharsets.UTF_8);
                String b64 = Base64.getEncoder().encodeToString(String.join("\n", gameArgs).getBytes(StandardCharsets.UTF_8));
                cmd.addAll(List.of("--init-script", init.toString(), "-PsfGameArgs=" + b64));
                log.accept("game arguments: " + String.join(" ", gameArgs));
            }
            // Which game is ours: the agent rides along from the start and writes its PID into a file unique to this
            // launch. Only that process's window is embedded, never a Minecraft started meanwhile by something else.
            if (agentJar != null) {
                marker = tmp("launch-" + UUID.randomUUID() + ".pid");
                Path init = tmp("sf-agent.init.gradle");
                Files.writeString(init, AGENT_SCRIPT, StandardCharsets.UTF_8);
                String spec = agentJar + "=codingFps=15,launch=" + marker;
                cmd.addAll(List.of("--init-script", init.toString(),
                        "-PsfAgent=" + Base64.getEncoder().encodeToString(spec.getBytes(StandardCharsets.UTF_8))));
            } else {
                marker = null;
            }
            var pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            gradle = pb.start();
            log.accept("started " + wrapper + " " + target.runTask() + " for " + profile);
            Thread.ofPlatform().daemon().name("sf-gradle-out").start(() -> pump(gradle));
        } catch (IOException e) {
            log.accept("could not start Gradle: " + e);
            return;
        }
        final Path mine = marker;
        Thread.ofPlatform().daemon().name("sf-wait-window").start(() -> {
            long deadline = System.currentTimeMillis() + 10 * 60_000;
            long pid = 0;
            while (System.currentTimeMillis() < deadline && gradle.isAlive()) {
                if (mine != null && pid == 0) pid = readPid(mine);
                for (long h : dev.sulfurframe.idea.embed.NativeWindows.gameWindows()) {
                    if (before.contains(h)) continue;
                    long owner = dev.sulfurframe.idea.embed.NativeWindows.pidOf(h);
                    if (mine != null && owner != pid) {   // not ours (or ours has not reported yet)
                        if (pid != 0 && warned.add(h)) log.accept("ignoring a Minecraft window of another process (pid " + owner + ")");
                        continue;
                    }
                    log.accept("game window appeared (pid " + owner + ")");
                    onWindow.accept(owner, h);
                    return;
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
            log.accept(gradle.isAlive() ? "no game window after 10 minutes" : "Gradle exited with " + gradle.exitValue());
        });
    }

    private volatile Path agentJar, marker;
    private final Set<Long> warned = Collections.synchronizedSet(new HashSet<>());

    /** The SulfurFrame agent to start the game with (marks which process is ours). */
    void setAgentJar(Path jar) { agentJar = jar; }

    private static Path tmp(String name) throws IOException {
        Path p = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", name);
        Files.createDirectories(p.getParent());
        return p;
    }

    private static long readPid(Path marker) {
        try { return Files.exists(marker) ? Long.parseLong(Files.readString(marker).strip()) : 0; }
        catch (Exception e) { return 0; }
    }

    /** Puts -javaagent:<decoded -PsfAgent> on runClient-like JavaExec tasks. */
    static final String AGENT_SCRIPT = """
            def sfAgent = gradle.startParameter.projectProperties['sfAgent']
            if (sfAgent != null) allprojects {
                def spec = new String(java.util.Base64.decoder.decode(sfAgent), 'UTF-8')
                tasks.withType(JavaExec).configureEach { t ->
                    if (t.name ==~ /(?i).*runclient.*/) t.jvmArgs('-javaagent:' + spec)
                }
            }
            """;

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

    /** Appends the decoded -PsfGameArgs (one argument per line) to runClient-like JavaExec tasks. */
    static final String ARGS_SCRIPT = """
            def sfArgs = gradle.startParameter.projectProperties['sfGameArgs']
            if (sfArgs != null) allprojects {
                def decoded = new String(java.util.Base64.decoder.decode(sfArgs), 'UTF-8').split('\\n') as List
                tasks.withType(JavaExec).configureEach { t ->
                    if (t.name ==~ /(?i).*runclient.*/) t.args(decoded)
                }
            }
            """;

    static Path findWrapper(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            Path w = d.resolve(System.getProperty("os.name", "").startsWith("Windows") ? "gradlew.bat" : "gradlew");
            if (Files.exists(w)) return w;
        }
        return null;
    }
}
