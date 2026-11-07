package dev.sulfurframe.idea;

import dev.sulfurframe.idea.embed.GameEmbedder;
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
    final GameEmbedder embedder;
    private final GameLauncher launcher;
    private final JComboBox<Workspace.Target> targets = new JComboBox<>();
    private final JTextArea log = new JTextArea(5, 40);
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
        embedder = new GameEmbedder(this::log);
        launcher = new GameLauncher(this::log);
        hotswapper = new Hotswapper(this::log, Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe"));
        agentJar = extractAgent();
        embedder.setAgentJar(agentJar);

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

    void runClient() {
        var t = (Workspace.Target) targets.getSelectedItem();
        if (t == null || !t.profile().isComplete()) { log("pick a client to run (none detected)"); return; }
        if (!t.hasWrapper()) log("note: " + t.where() + " has no Gradle wrapper of its own; using the nearest parent's");
        launcher.runClient(t, (pid, hwnd) -> SwingUtilities.invokeLater(() -> { embedder.attach(pid); hotswapper.snapshot(t); }));
    }

    Workspace.Target selectedTarget() { return (Workspace.Target) targets.getSelectedItem(); }

    /** Compile the selected target and reload what changed into the running game (background thread). */
    void hotswapAsync() {
        var t = selectedTarget();
        long pid = embedder.gamePid();
        Thread.ofPlatform().daemon().name("sf-hotswap").start(() -> hotswapper.swap(t, pid, agentJar));
    }

    /** Source files were saved: hotswap if they belong to the selected target and a game is attached. */
    void onSourcesSaved(java.util.List<Path> files) {
        var t = selectedTarget();
        if (!swapOnSave.isSelected() || t == null || !embedder.attached()) return;
        Path dir = t.dir().toAbsolutePath().normalize();
        if (files.stream().anyMatch(f -> f.toAbsolutePath().normalize().startsWith(dir))) hotswapAsync();
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
