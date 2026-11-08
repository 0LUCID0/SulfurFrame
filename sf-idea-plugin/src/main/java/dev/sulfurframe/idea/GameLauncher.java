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

    /** A Run client build (and so its game) is still going. */
    boolean running() { Process g = gradle; return g != null && g.isAlive(); }

    GameLauncher(Consumer<String> log) { this.log = log; }

    void runClient(Workspace.Target target, BiConsumer<Long, Long> onWindow) { runClient(target, List.of(), onWindow); }

    /**
     * @param gameArgs extra program arguments for the game (e.g. --quickPlaySingleplayer world). They reach runClient
     *                 through an init script as Base64, so names with spaces or non-ASCII survive cmd.exe and Gradle.
     */
    void runClient(Workspace.Target target, List<String> gameArgs, BiConsumer<Long, Long> onWindow) { runClient(target, gameArgs, onWindow, () -> { }); }

    /** @param onEnd runs when the Gradle build that runs the game ends (the game exited). */
    void runClient(Workspace.Target target, List<String> gameArgs, BiConsumer<Long, Long> onWindow, Runnable onEnd) {
        ProjectProfile profile = target.profile();
        Path dir = target.buildRoot();
        Path wrapper = findWrapper(dir);
        if (wrapper == null) { log.accept("no gradlew.bat in " + dir + " or its parents"); onEnd.run(); return; }
        Set<Long> before = new HashSet<>(dev.sulfurframe.idea.embed.NativeWindows.gameWindows());
        try {
            // every init script below patches exactly this task (runClient, or a run config like runAllModsClient)
            var cmd = new ArrayList<>(List.of(wrapper.toString(), target.runTask(), "--console=plain", "-PsfRunTask=" + target.task()));
            Path jbr = Jbr.find(profile.javaMajor());
            if (jbr != null) {   // DCEVM: hotswap may then add/remove methods and fields
                Path init = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "sf-jbr.init.gradle");
                Files.createDirectories(init.getParent());
                Files.writeString(init, Jbr.INIT_SCRIPT, StandardCharsets.UTF_8);
                cmd.addAll(List.of("--init-script", init.toString(), "-PsfJbr=" + jbr));
                Path jbrHome = jbr.getParent().getParent();
                if (Files.isRegularFile(jbrHome.resolve("bin").resolve(jbr.getFileName().toString().replace("java", "javac")))) {
                    // a full JDK: usable as a Gradle toolchain, for builds that choose the game's Java by toolchain
                    String paths = jbrHome.toString();
                    String existing = GradleJava.property(dir, "org.gradle.java.installations.paths");
                    if (existing != null && !existing.isBlank()) paths += "," + existing;   // -P replaces theirs: keep it
                    cmd.addAll(List.of("-PsfJbrMajor=" + profile.javaMajor(), "-Porg.gradle.java.installations.paths=" + paths));
                }
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
            if (!extraJvmArgs.isEmpty()) {   // e.g. the debugger's JDWP agent
                Path init = tmp("sf-jvmargs.init.gradle");
                Files.writeString(init, JVM_ARGS_SCRIPT, StandardCharsets.UTF_8);
                cmd.addAll(List.of("--init-script", init.toString(), "-PsfJvmArgs=" + Base64.getEncoder().encodeToString(String.join("\n", extraJvmArgs).getBytes(StandardCharsets.UTF_8))));
            }
            if (agentJar != null) {
                marker = tmp("launch-" + UUID.randomUUID() + ".pid");
                Path init = tmp("sf-agent.init.gradle");
                Files.writeString(init, AGENT_SCRIPT, StandardCharsets.UTF_8);
                String spec = agentJar + "=codingFps=" + Settings.codingFps() + ",launch=" + marker;
                cmd.addAll(List.of("--init-script", init.toString(),
                        "-PsfAgent=" + Base64.getEncoder().encodeToString(spec.getBytes(StandardCharsets.UTF_8))));
            } else {
                marker = null;
            }
            var pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            Path gradleJdk = GradleJava.pick(dir, profile.javaMajor(), jbr);
            if (gradleJdk != null) {
                pb.environment().put("JAVA_HOME", gradleJdk.toString());
                log.accept("Gradle runs on " + gradleJdk + " (JAVA_HOME " + System.getenv("JAVA_HOME") + " is older than Java " + profile.javaMajor() + ")");
            }
            gradle = pb.start();
            gradle.onExit().thenRun(onEnd);
            log.accept("started " + wrapper + " " + target.runTask() + " for " + profile);
            Thread.ofPlatform().daemon().name("sf-gradle-out").start(() -> pump(gradle));
        } catch (IOException e) {
            log.accept("could not start Gradle: " + e);
            onEnd.run();
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

    private volatile List<String> extraJvmArgs = List.of();

    /** More JVM arguments for the next launch (debugger). */
    void setExtraJvmArgs(List<String> args) { extraJvmArgs = List.copyOf(args); }

    static final String JVM_ARGS_SCRIPT = """
            def sfJvm = gradle.startParameter.projectProperties['sfJvmArgs']
            def sfRun = gradle.startParameter.projectProperties['sfRunTask']
            if (sfJvm != null) allprojects {
                def decoded = new String(java.util.Base64.decoder.decode(sfJvm), 'UTF-8').split('\\n') as List
                tasks.withType(JavaExec).configureEach { t ->
                    if ((sfRun ? t.name == sfRun : t.name ==~ /(?i).*runclient.*/)) t.jvmArgs(decoded)
                }
            }
            """;

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
            def sfRun = gradle.startParameter.projectProperties['sfRunTask']
            if (sfAgent != null) allprojects {
                def spec = new String(java.util.Base64.decoder.decode(sfAgent), 'UTF-8')
                tasks.withType(JavaExec).configureEach { t ->
                    if ((sfRun ? t.name == sfRun : t.name ==~ /(?i).*runclient.*/)) t.jvmArgs('-javaagent:' + spec)
                }
            }
            """;

    /** Every line Gradle (and the game through it) prints, for the Run window; set per launch. */
    volatile java.util.function.Consumer<String> output;

    /** Exit code of the last Gradle run, -1 while it runs or before any. */
    int exitCode() { Process g = gradle; return g == null || g.isAlive() ? -1 : g.exitValue(); }

    /** Stop the Gradle run (and with it the game, if Gradle still holds it). */
    void stopGradle() { Process g = gradle; if (g != null && g.isAlive()) { g.descendants().forEach(ProcessHandle::destroy); g.destroy(); } }

    private void pump(Process p) {
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            boolean why = false;   // after "* What went wrong:" show the reason lines, they are the useful part
            for (String line; (line = r.readLine()) != null; ) {
                var out = output;
                if (out != null) out.accept(line);
                if (line.startsWith("* What went wrong")) { why = true; continue; }
                if (line.startsWith("* Try")) why = false;
                if (why && !line.isBlank()) log.accept("[gradle] " + line.strip());
                else if (line.matches("> Task \\S*:?run\\w*Client.*") || line.contains("BUILD") || line.contains("error:"))
                    log.accept("[gradle] " + line);
            }
        } catch (IOException ignored) { }
    }

    /** Appends the decoded -PsfGameArgs (one argument per line) to runClient-like JavaExec tasks. */
    static final String ARGS_SCRIPT = """
            def sfArgs = gradle.startParameter.projectProperties['sfGameArgs']
            def sfRun = gradle.startParameter.projectProperties['sfRunTask']
            if (sfArgs != null) allprojects {
                def decoded = new String(java.util.Base64.decoder.decode(sfArgs), 'UTF-8').split('\\n') as List
                tasks.withType(JavaExec).configureEach { t ->
                    if ((sfRun ? t.name == sfRun : t.name ==~ /(?i).*runclient.*/)) t.args(decoded)
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
