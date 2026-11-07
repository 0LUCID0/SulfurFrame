package dev.sulfurframe.idea;

import dev.sulfurframe.idea.embed.GameEmbedder;
import dev.sulfurframe.idea.profile.ProjectProfile;

import javax.swing.*;
import java.awt.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Tool window content: detected project profile, controls, the embedded game, a short log. */
final class SulfurPanel extends JPanel {
    final GameEmbedder embedder;
    private final GameLauncher launcher;
    private final JLabel profileLabel = new JLabel();
    private final JTextArea log = new JTextArea(5, 40);
    private final Path projectDir;
    ProjectProfile profile;

    SulfurPanel(Path projectDir) {
        super(new BorderLayout());
        this.projectDir = projectDir;
        embedder = new GameEmbedder(this::log);
        launcher = new GameLauncher(this::log);
        embedder.setAgentJar(extractAgent());

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(button("Run client", this::runClient));
        buttons.add(button("Attach PID…", () -> {
            String s = JOptionPane.showInputDialog(this, "PID of a running Minecraft:");
            if (s != null && !s.isBlank()) embedder.attach(Long.parseLong(s.trim()));
        }));
        buttons.add(button("Detach", embedder::detach));
        buttons.add(button("Re-detect", this::detect));
        var north = new JPanel(new BorderLayout());
        north.add(profileLabel, BorderLayout.NORTH);
        north.add(buttons, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(embedder.component(), BorderLayout.CENTER);
        add(new JScrollPane(log), BorderLayout.SOUTH);
        detect();
    }

    private JButton button(String text, Runnable r) {
        var b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    void detect() {
        try {
            profile = projectDir == null ? null : ProjectProfile.detect(projectDir);
        } catch (Exception e) {
            profile = null;
            log("profile detection failed: " + e);
        }
        profileLabel.setText(profile == null ? " No project" : " " + profile + (profile.isComplete() ? "" : "  (incomplete: see log)"));
        if (profile != null) profile.evidence().forEach(e -> log("  " + e));
    }

    void runClient() {
        if (profile == null || !profile.isComplete()) { log("cannot tell which Minecraft this project uses"); return; }
        launcher.runClient(profile, (pid, hwnd) -> SwingUtilities.invokeLater(() -> embedder.attach(pid)));
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
}
