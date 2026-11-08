package dev.sulfurframe.idea;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import dev.sulfurframe.idea.profile.Workspace;
import org.jdom.Element;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;

/** Which client to start (build folder, project path, task), and in which world. */
final class SulfurRunConfiguration extends RunConfigurationBase<Object> {
    /** Same key as the panel uses: buildRoot|projectPath[|task]. */
    String target = "";
    /** World to join right away ("" = title screen). */
    String world = "";

    SulfurRunConfiguration(Project project, ConfigurationFactory factory, String name) { super(project, factory, name); }

    @Override public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() { return new Editor(getProject()); }

    @Override public RunProfileState getState(Executor executor, ExecutionEnvironment env) throws ExecutionException {
        return new SulfurRunState(this, env);
    }

    @Override public void checkConfiguration() throws RuntimeConfigurationException {
        if (target.isBlank()) throw new RuntimeConfigurationError("Pick the client to run");
    }

    @Override public void readExternal(Element element) {
        super.readExternal(element);
        target = valueOr(element.getAttributeValue("sfTarget"));
        world = valueOr(element.getAttributeValue("sfWorld"));
    }

    @Override public void writeExternal(Element element) {
        super.writeExternal(element);
        element.setAttribute("sfTarget", target);
        element.setAttribute("sfWorld", world);
    }

    private static String valueOr(String s) { return s == null ? "" : s; }

    /** Name shown in the run configuration list. */
    static String nameFor(Workspace.Target t) {
        return "Minecraft: " + (t.where().equals(".") ? t.buildRoot().getFileName() : t.where().replaceFirst("^\\. ", "")) + (t.task().equals("runClient") ? "" : " [" + t.task() + "]");
    }

    static final class Editor extends SettingsEditor<SulfurRunConfiguration> {
        private final JComboBox<Workspace.Target> client = new JComboBox<>();
        private final JTextField worldField = new JTextField(20);
        private String unknownTarget = "";

        Editor(Project project) {
            client.setRenderer(new MarqueeRenderer<>(client));
            try {
                Path dir = project.getBasePath() == null ? null : Path.of(project.getBasePath());
                List<Workspace.Target> found = dir == null ? List.of() : Workspace.scan(dir);
                found.forEach(client::addItem);
            } catch (Exception ignored) { }
        }

        @Override protected void resetEditorFrom(SulfurRunConfiguration c) {
            unknownTarget = c.target;
            for (int i = 0; i < client.getItemCount(); i++)
                if (SulfurPanel.key(client.getItemAt(i)).equals(c.target)) { client.setSelectedIndex(i); unknownTarget = ""; break; }
            worldField.setText(c.world);
        }

        @Override protected void applyEditorTo(SulfurRunConfiguration c) {
            var t = (Workspace.Target) client.getSelectedItem();
            c.target = t != null ? SulfurPanel.key(t) : unknownTarget;
            c.world = worldField.getText().strip();
        }

        @Override protected JComponent createEditor() {
            var p = new JPanel(new GridBagLayout());
            var g = new GridBagConstraints();
            g.anchor = GridBagConstraints.WEST; g.insets = new Insets(2, 2, 2, 6);
            g.gridx = 0; g.gridy = 0; p.add(new JLabel("Client:"), g);
            g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; p.add(client, g);
            g.gridx = 0; g.gridy = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0; p.add(new JLabel("Join world:"), g);
            g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; p.add(worldField, g);
            g.gridx = 1; g.gridy = 2; p.add(new JLabel("<html>Folder name of a world in saves/, or empty for the title screen. Rerun goes back into the<br>"
                    + "world you were in anyway; Debug starts the game with a debug port and attaches the debugger.</html>"), g);
            return p;
        }
    }
}
