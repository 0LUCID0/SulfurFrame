package dev.sulfurframe.idea;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;

import javax.swing.*;
import java.util.function.Consumer;

/**
 * Every SulfurFrame function as an IDE action (plugin.xml): the game window's toolbar, Tools | SulfurFrame, the
 * status bar menu, Find Action and the keymap all use these. An action that needs the panel opens the tool window
 * first when it has not been created yet.
 */
final class SfActions {
    static final String TOOL_WINDOW = "SulfurFrame";

    /** Base: runs on the panel of the event's project; {@link #needsGame} actions are disabled without a game. */
    abstract static class PanelAction extends DumbAwareAction {
        abstract void run(SulfurPanel p);
        boolean needsGame() { return false; }

        @Override public void actionPerformed(AnActionEvent e) { withPanel(e.getProject(), this::run); }

        @Override public void update(AnActionEvent e) {
            Project project = e.getProject();
            var host = project == null ? null : PanelHost.of(project);
            e.getPresentation().setEnabled(project != null && (!needsGame() || host != null && host.panel.embedder.attached()));
        }

        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    }

    static void withPanel(Project project, Consumer<SulfurPanel> r) {
        if (project == null) return;
        var host = PanelHost.of(project);
        if (host != null) { r.accept(host.panel); return; }
        var tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW);
        if (tw == null) return;
        tw.activate(() -> { var h = PanelHost.of(project); if (h != null) r.accept(h.panel); });
    }

    public static final class RunClient extends PanelAction { void run(SulfurPanel p) { p.runClient(); } }
    public static final class Restart extends PanelAction { void run(SulfurPanel p) { p.restartAsync(); } boolean needsGame() { return true; } }
    public static final class Hotswap extends PanelAction { void run(SulfurPanel p) { p.hotswapAsync(); } boolean needsGame() { return true; } }
    public static final class Datagen extends PanelAction { void run(SulfurPanel p) { p.datagenAsync(); } }
    public static final class Inspect extends PanelAction { void run(SulfurPanel p) { p.inspectAsync(); } boolean needsGame() { return true; } }
    public static final class Snapshot extends PanelAction { void run(SulfurPanel p) { p.snapshotAsync(); } boolean needsGame() { return true; } }
    public static final class Restore extends PanelAction { void run(SulfurPanel p) { p.restoreAsync(); } }
    public static final class TestWorld extends PanelAction { void run(SulfurPanel p) { p.testWorldAsync(false); } }
    public static final class TempWorld extends PanelAction { void run(SulfurPanel p) { p.testWorldAsync(true); } }
    public static final class AttachPid extends PanelAction { void run(SulfurPanel p) { p.attachPidDialog(); } }
    public static final class Detach extends PanelAction { void run(SulfurPanel p) { p.embedder.detach(); } boolean needsGame() { return true; } }
    public static final class Rescan extends PanelAction { void run(SulfurPanel p) { p.scan(); } }

    public static final class OpenAsTab extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) { if (e.getProject() != null) withPanel(e.getProject(), p -> GameTab.open(e.getProject())); }
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    }

    public static final class OpenSettings extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) { ShowSettingsUtil.getInstance().showSettingsDialog(e.getProject(), SulfurConfigurable.class); }
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    }

    /** A Settings switch; every open panel shows the change right away. */
    abstract static class SettingToggle extends DumbAwareToggleAction {
        abstract boolean get();
        abstract void set(boolean on);
        @Override public boolean isSelected(AnActionEvent e) { return get(); }
        @Override public void setSelected(AnActionEvent e, boolean on) { set(on); PanelHost.all().forEach(h -> h.panel.syncSettings()); }
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    }

    public static final class HotswapOnSave extends SettingToggle {
        boolean get() { return Settings.hotswapOnSave(); }
        void set(boolean on) { Settings.set(Settings.HOTSWAP_ON_SAVE, on, true); }
    }
    public static final class SharedWorlds extends SettingToggle {
        boolean get() { return Settings.sharedWorlds(); }
        void set(boolean on) { Settings.set(Settings.SHARED_WORLDS, on, false); }
    }
    public static final class KeepRatio extends SettingToggle {
        boolean get() { return Settings.keepRatio(); }
        void set(boolean on) { Settings.set(Settings.KEEP_RATIO, on, true); }
    }

    private SfActions() {}
}
