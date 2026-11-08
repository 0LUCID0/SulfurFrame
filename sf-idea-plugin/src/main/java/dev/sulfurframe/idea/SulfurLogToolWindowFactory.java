package dev.sulfurframe.idea;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;

/**
 * Bottom tool window "Minecraft Log" (where Run and Build show their output): the game log with source links,
 * the problems the game reported, and SulfurFrame's own log, as tabs. Crashes still pop up as notifications.
 */
public final class SulfurLogToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        var panel = PanelHost.obtain(project).panel;
        var cf = ContentFactory.getInstance();
        var cm = toolWindow.getContentManager();
        if (panel.gameLogPanel != null) {
            cm.addContent(cf.createContent(panel.gameLogPanel.logTab, "Game log", false));
            cm.addContent(cf.createContent(panel.gameLogPanel.problemsTab, "Problems", false));
        }
        cm.addContent(cf.createContent(panel.logTab, "SulfurFrame", false));
    }
}
