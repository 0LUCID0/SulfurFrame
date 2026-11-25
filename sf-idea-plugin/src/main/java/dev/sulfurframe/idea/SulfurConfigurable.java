package dev.sulfurframe.idea;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;

import javax.swing.*;
import java.awt.*;

/** Settings | Tools | SulfurFrame. Switches are IDE-wide; the dev mods list can also be kept per project. */
public final class SulfurConfigurable implements Configurable {
    private final Project project;
    private JCheckBox hotswapOnSave, sharedWorlds, keepRatio, capFps, agentBridge;
    private JSpinner codingFps;
    private DevModsEditor devMods;

    public SulfurConfigurable(Project project) { this.project = project; }

    @Override public String getDisplayName() { return "SulfurFrame"; }

    @Override public JComponent createComponent() {
        hotswapOnSave = new JCheckBox("Hotswap on save: reload changed classes into the running game when a source file is saved");
        sharedWorlds = new JCheckBox("Shared worlds: one game folder per Minecraft version for all projects (" + SharedInstance.root() + ")");
        keepRatio = new JCheckBox("Keep the game at 16:9 (black bars) instead of stretching it to the panel");
        capFps = new JCheckBox("While you type in the editor, cap the game at");
        agentBridge = new JCheckBox("Coding agents may use the running game through the sf command (" + Bridge.BIN.resolve("sf") + "; applies when the project opens again)");
        codingFps = new JSpinner(new SpinnerNumberModel(15, 1, 240, 1));
        capFps.addActionListener(e -> codingFps.setEnabled(capFps.isSelected()));
        var fpsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fpsRow.add(capFps); fpsRow.add(codingFps); fpsRow.add(new JLabel(" frames per second (applies to the next launch)"));

        var general = new JPanel();
        general.setLayout(new BoxLayout(general, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{hotswapOnSave, sharedWorlds, keepRatio, fpsRow, agentBridge}) { c.setAlignmentX(0); general.add(c); }
        general.setBorder(BorderFactory.createTitledBorder("Game"));
        devMods = new DevModsEditor(project);
        devMods.component.setBorder(BorderFactory.createTitledBorder("Dev mods"));

        var root = new JPanel(new BorderLayout(0, 8));
        root.add(general, BorderLayout.NORTH);
        root.add(devMods.component, BorderLayout.CENTER);
        reset();
        return root;
    }

    @Override public boolean isModified() {
        return hotswapOnSave.isSelected() != Settings.hotswapOnSave() || sharedWorlds.isSelected() != Settings.sharedWorlds()
                || keepRatio.isSelected() != Settings.keepRatio() || agentBridge.isSelected() != Settings.agentBridge()
                || fps() != Settings.codingFps() || devMods.isModified();
    }

    @Override public void apply() {
        Settings.set(Settings.HOTSWAP_ON_SAVE, hotswapOnSave.isSelected(), true);
        Settings.set(Settings.SHARED_WORLDS, sharedWorlds.isSelected(), false);
        Settings.set(Settings.KEEP_RATIO, keepRatio.isSelected(), true);
        Settings.set(Settings.AGENT_BRIDGE, agentBridge.isSelected(), true);
        Settings.setCodingFps(fps());
        var host = project == null ? null : PanelHost.of(project);
        devMods.apply(host == null ? null : host.panel::log);
        PanelHost.all().forEach(h -> h.panel.syncSettings());
    }

    @Override public void reset() {
        hotswapOnSave.setSelected(Settings.hotswapOnSave());
        sharedWorlds.setSelected(Settings.sharedWorlds());
        keepRatio.setSelected(Settings.keepRatio());
        agentBridge.setSelected(Settings.agentBridge());
        int f = Settings.codingFps();
        capFps.setSelected(f > 0);
        codingFps.setValue(f > 0 ? f : 15);
        codingFps.setEnabled(f > 0);
        devMods.reset();
    }

    private int fps() { return capFps.isSelected() ? (Integer) codingFps.getValue() : 0; }
}
