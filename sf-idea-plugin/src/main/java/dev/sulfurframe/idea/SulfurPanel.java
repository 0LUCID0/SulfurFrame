package dev.sulfurframe.idea;

import dev.sulfurframe.idea.embed.GameView;
import dev.sulfurframe.idea.profile.ProjectProfile;
import dev.sulfurframe.idea.profile.Workspace;

import javax.swing.*;
import java.awt.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tool window content: which client to run (every runnable target found in the opened folder), controls, the
 * embedded game, a short log.
 */
final class SulfurPanel extends JPanel {
    final GameView embedder;
    private final GameLauncher launcher;
    private final JComboBox<Workspace.Target> targets = new JComboBox<>();
    private final JTextArea log = new JTextArea(5, 40);
    String logText() { return log.getText(); }
    void setSwapOnSave(boolean on) { swapOnSave.setSelected(on); }
    private final Path projectDir;
    private final Supplier<String> savedTarget;
    private final Consumer<String> saveTarget;
    /** Profile of the selected target (null if none). */
    ProjectProfile profile;
    final Hotswapper hotswapper;
    private final Path agentJar;
    private final JCheckBox swapOnSave = new JCheckBox("Hotswap on save", true);

    SulfurPanel(Path projectDir, Supplier<String> savedTarget, Consumer<String> saveTarget, Runnable openAsTab) {
        super(new BorderLayout());
        this.projectDir = projectDir;
        this.savedTarget = savedTarget;
        this.saveTarget = saveTarget;
        embedder = GameView.create(this::log);
        launcher = new GameLauncher(this::log);
        hotswapper = new Hotswapper(this::log, Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe"));
        agentJar = extractAgent();
        embedder.setAgentJar(agentJar);
        launcher.setAgentJar(agentJar);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        targets.addActionListener(e -> onTargetSelected());
        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(button("Run client", this::runClient));
        buttons.add(button("Attach PID…", () -> {
            String s = JOptionPane.showInputDialog(this, "PID of a running Minecraft:");
            if (s != null && !s.isBlank()) embedder.attach(Long.parseLong(s.trim()));
        }));
        buttons.add(button("Detach", embedder::detach));
        buttons.add(button("Rescan", this::scan));
        buttons.add(button("Open as tab", openAsTab));
        var keepRatio = new JCheckBox("16:9", true);
        keepRatio.setToolTipText("Keep the game at 16:9 (black bars) instead of stretching it to the panel");
        keepRatio.addActionListener(e -> embedder.setAspect(keepRatio.isSelected() ? 16.0 / 9.0 : 0));
        buttons.add(keepRatio);
        buttons.add(button("Hotswap", this::hotswapAsync));
        var restart = button("Restart", this::restartAsync);
        restart.setToolTipText("Save and quit the game, start it again and go straight back into the same world");
        buttons.add(restart);
        swapOnSave.setToolTipText("Compile and reload changed classes into the running game whenever a source file is saved");
        buttons.add(swapOnSave);
        var north = new JPanel(new BorderLayout());
        north.add(targets, BorderLayout.NORTH);
        north.add(buttons, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(embedder.component(), BorderLayout.CENTER);
        add(new JScrollPane(log), BorderLayout.SOUTH);
        scan();
    }

    private JButton button(String text, Runnable r) {
        var b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    /** Find every runnable client in the opened folder (single project, container of builds, multi-project, Stonecutter). */
    void scan() {
        List<Workspace.Target> found;
        try {
            found = projectDir == null ? List.of() : Workspace.scan(projectDir);
        } catch (Exception e) {
            found = List.of();
            log("scan failed: " + e);
        }
        targets.removeAllItems();
        found.forEach(targets::addItem);
        log(found.isEmpty() ? "no Minecraft mod project found in " + projectDir
                : found.size() + " runnable client" + (found.size() == 1 ? "" : "s") + " found");
        String want = savedTarget.get();
        for (int i = 0; i < targets.getItemCount(); i++)
            if (key(targets.getItemAt(i)).equals(want)) { targets.setSelectedIndex(i); break; }
        onTargetSelected();
    }

    private void onTargetSelected() {
        var t = (Workspace.Target) targets.getSelectedItem();
        profile = t == null ? null : t.profile();
        if (t == null) return;
        saveTarget.accept(key(t));
        log("selected " + t.where() + ": " + t.profile());
        t.profile().evidence().forEach(e -> log("  " + e));
    }

    private static String key(Workspace.Target t) { return t.buildRoot() + "|" + t.projectPath(); }

    void runClient() { runClient(List.of()); }

    void runClient(List<String> gameArgs) {
        var t = (Workspace.Target) targets.getSelectedItem();
        if (t == null || !t.profile().isComplete()) { log("pick a client to run (none detected)"); return; }
        if (!t.hasWrapper()) log("note: " + t.where() + " has no Gradle wrapper of its own; using the nearest parent's");
        launcher.runClient(t, gameArgs, (pid, hwnd) -> SwingUtilities.invokeLater(() -> { embedder.attach(pid); hotswapper.snapshot(t); }));
    }

    Workspace.Target selectedTarget() { return (Workspace.Target) targets.getSelectedItem(); }

    /** Compile the selected target and reload what changed into the running game (background thread). */
    void hotswapAsync() {
        var t = selectedTarget();
        long pid = embedder.gamePid();
        Thread.ofPlatform().daemon().name("sf-hotswap").start(() -> {
            hotswapper.swap(t, pid, agentJar);
            var restartNeeded = hotswapper.lastResult.stream().filter(s -> s.startsWith("restart needed")).toList();
            if (!restartNeeded.isEmpty()) offerRestart(restartNeeded.size() + " class(es) changed in a way hotswap cannot apply");
            else if (!hotswapper.lastStartupChanges.isEmpty())
                offerRestart("startup code changed (runs only once, at launch): " + String.join(", ", hotswapper.lastStartupChanges));
        });
    }

    // ------------------------------------------------------------------ quick restart

    /** World the last restart went back into (self-test). */
    volatile String lastRestartWorld;
    private volatile boolean restarting;
    private volatile long lastOffer;

    void restartAsync() { Thread.ofPlatform().daemon().name("sf-restart").start(this::restart); }

    /**
     * Quick restart for what hotswap cannot do (new registrations, mod metadata, access wideners, build changes):
     * ask the game which world is open and to quit (it saves), wait for it to exit, then Run client again with
     * --quickPlaySingleplayer, so it lands back in the same world, embedded again. Blocking; call off the EDT.
     */
    void restart() {
        var t = selectedTarget();
        if (t == null || restarting) return;
        restarting = true;
        try {
            long pid = embedder.gamePid();
            String world = "";
            var game = pid > 0 ? ProcessHandle.of(pid).orElse(null) : null;
            if (game != null && game.isAlive()) {
                Path wf = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "world-" + pid + ".txt");
                Files.deleteIfExists(wf);
                Hotswapper.loadAgent(pid, agentJar, "codingFps=15,world=" + wf + ",quit");
                for (int i = 0; i < 50 && !Files.exists(wf); i++) Thread.sleep(100);
                if (Files.exists(wf)) world = Files.readString(wf, StandardCharsets.UTF_8).strip();
                log("restart: saving and quitting" + (world.isEmpty() ? "" : " (world '" + world + "')"));
                try { game.onExit().get(90, java.util.concurrent.TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException e) { log("restart: the game did not exit in 90 s, stopping it"); game.destroyForcibly(); }
            }
            var args = !world.isEmpty() && quickPlay(t.profile().minecraftVersion()) ? List.of("--quickPlaySingleplayer", world) : List.<String>of();
            lastRestartWorld = world;
            SwingUtilities.invokeLater(() -> launcher.runClient(t, args,
                    (p, h) -> SwingUtilities.invokeLater(() -> { embedder.attach(p); hotswapper.snapshot(t); })));
        } catch (Exception e) {
            log("restart failed: " + e);
        } finally {
            restarting = false;
        }
    }

    /** --quickPlaySingleplayer exists since 1.20. */
    static boolean quickPlay(String mc) {
        if (mc == null) return false;
        String[] v = mc.split("\\.");
        try {
            int a = Integer.parseInt(v[0]), b = v.length > 1 ? Integer.parseInt(v[1].replaceAll("\\D.*", "")) : 0;
            return a > 1 || b >= 20;
        } catch (NumberFormatException e) { return false; }
    }

    /** Something changed that needs a restart: say so, with a button that does it (at most every 10 s). */
    void offerRestart(String why) {
        log("restart needed: " + why);
        if (System.currentTimeMillis() - lastOffer < 10_000) return;
        lastOffer = System.currentTimeMillis();
        SwingUtilities.invokeLater(() -> {
            try {
                var n = com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("SulfurFrame")
                        .createNotification("Minecraft needs a restart", why, com.intellij.notification.NotificationType.INFORMATION);
                n.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Restart into the same world", this::restartAsync));
                n.notify(null);
            } catch (Throwable ignored) { }
        });
    }

    /** Files that only take effect when the game starts. */
    static boolean needsRestart(Path f) {
        String n = f.getFileName().toString();
        return n.equals("fabric.mod.json") || n.equals("quilt.mod.json") || n.endsWith("mods.toml") || n.endsWith(".accesswidener")
                || n.equals("accesstransformer.cfg") || n.endsWith(".mixins.json") || n.equals("mixins.json")
                || n.startsWith("build.gradle") || n.startsWith("settings.gradle") || n.equals("gradle.properties") || n.equals("libs.versions.toml");
    }

    /** Source files were saved: hotswap if they belong to the selected target and a game is attached. */
    void onSourcesSaved(java.util.List<Path> files) {
        var t = selectedTarget();
        if (!swapOnSave.isSelected() || t == null || !embedder.attached()) return;
        Path dir = t.dir().toAbsolutePath().normalize();
        var all = files.stream().filter(f -> f.toAbsolutePath().normalize().startsWith(t.buildRoot().toAbsolutePath().normalize())).toList();
        var startup = all.stream().filter(SulfurPanel::needsRestart).toList();
        if (!startup.isEmpty())
            offerRestart(String.join(", ", startup.stream().map(f -> f.getFileName().toString()).distinct().toList()) + " changed (read only when the game starts)");
        var mine = files.stream().filter(f -> f.toAbsolutePath().normalize().startsWith(dir) && !needsRestart(f)).toList();
        var code = mine.stream().filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".kt")).toList();
        var res = mine.stream().filter(f -> !code.contains(f) && f.toString().replace('\\', '/').contains("/resources/")).toList();
        if (!code.isEmpty()) hotswapAsync();
        if (!res.isEmpty()) {
            long pid = embedder.gamePid();
            Thread.ofPlatform().daemon().name("sf-resources").start(() -> hotswapper.reloadResources(t, pid, agentJar, res));
        }
    }

    void log(String s) {
        String line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + s;
        System.out.println("[SulfurFrame] " + line);
        SwingUtilities.invokeLater(() -> { log.append(line + "\n"); log.setCaretPosition(log.getDocument().getLength()); });
    }

    /**
     * The javaagent ships inside the plugin. Java 8 games cannot load an agent from a path with non-ASCII characters,
     * so extract it to an ASCII-only directory.
     */
    private Path extractAgent() {
        try (InputStream in = SulfurPanel.class.getResourceAsStream("/sf-agent.jar")) {
            if (in == null) { log("sf-agent.jar not bundled; no FPS cap / SDL focus fix"); return null; }
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe");
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(dir.toString()))
                dir = Path.of(System.getenv().getOrDefault("PUBLIC", "C:\\Users\\Public"), "sulfurframe");
            Files.createDirectories(dir);
            Path jar = dir.resolve("sf-agent.jar");
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
            return jar;
        } catch (Exception e) {
            log("could not extract the agent: " + e);
            return null;
        }
    }

    /** For the self-test. */
    int targetCount() { return targets.getItemCount(); }
    void selectTarget(int i) { targets.setSelectedIndex(i); }
    Workspace.Target targetAt(int i) { return targets.getItemAt(i); }
}
