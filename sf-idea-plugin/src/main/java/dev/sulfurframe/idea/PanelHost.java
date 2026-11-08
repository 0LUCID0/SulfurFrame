package dev.sulfurframe.idea;

import com.intellij.openapi.project.Project;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One SulfurPanel per project, shown either in the tool window or in an editor tab (wide, like a normal editor).
 * Moving it re-creates the canvas' native window; GameEmbedder parks the game across that and re-embeds it.
 */
final class PanelHost {
    private static final Map<Project, PanelHost> HOSTS = Collections.synchronizedMap(new WeakHashMap<>());

    final SulfurPanel panel;
    final JPanel toolHolder = new JPanel(new BorderLayout());
    private final JLabel placeholder = new JLabel("Minecraft is open in an editor tab", SwingConstants.CENTER);
    private JPanel editorHolder;

    PanelHost(SulfurPanel panel) {
        this.panel = panel;
        toolHolder.add(panel, BorderLayout.CENTER);
    }

    /** The project's panel, created on first use (by the game tool window or the log tool window, whichever opens first). */
    static synchronized PanelHost obtain(Project project) {
        var h = HOSTS.get(project);
        if (h != null) return h;
        java.nio.file.Path dir = project.getBasePath() == null ? null : java.nio.file.Path.of(project.getBasePath());
        var props = com.intellij.ide.util.PropertiesComponent.getInstance(project);
        var panel = new SulfurPanel(dir, () -> props.getValue("sulfurframe.target", ""), v -> props.setValue("sulfurframe.target", v),
                () -> GameTab.open(project));
        panel.attachProject(project);
        h = new PanelHost(panel);
        HOSTS.put(project, h);
        return h;
    }

    static void register(Project p, PanelHost h) { HOSTS.put(p, h); }
    static PanelHost of(Project p) { return HOSTS.get(p); }
    static java.util.List<PanelHost> all() { synchronized (HOSTS) { return java.util.List.copyOf(HOSTS.values()); } }

    boolean inEditor() { return editorHolder != null; }

    /** Move the panel into an editor tab's component. */
    void moveTo(JPanel holder) {
        if (editorHolder == holder) return;
        toolHolder.remove(panel);
        toolHolder.add(placeholder, BorderLayout.CENTER);
        holder.add(panel, BorderLayout.CENTER);
        editorHolder = holder;
        refresh(toolHolder); refresh(holder);
    }

    /** Back to the tool window (editor tab closed). */
    void moveBack() {
        if (editorHolder == null) return;
        editorHolder.remove(panel);
        refresh(editorHolder);
        editorHolder = null;
        toolHolder.remove(placeholder);
        toolHolder.add(panel, BorderLayout.CENTER);
        refresh(toolHolder);
    }

    private static void refresh(JComponent c) { c.revalidate(); c.repaint(); }
}
