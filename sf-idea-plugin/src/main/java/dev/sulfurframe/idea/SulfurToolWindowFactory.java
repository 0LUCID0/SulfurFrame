package dev.sulfurframe.idea;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;

import java.nio.file.Path;

public final class SulfurToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        Path dir = project.getBasePath() == null ? null : Path.of(project.getBasePath());
        var props = com.intellij.ide.util.PropertiesComponent.getInstance(project);
        var panel = new SulfurPanel(dir, () -> props.getValue("sulfurframe.target", ""), v -> props.setValue("sulfurframe.target", v),
                () -> GameTab.open(project));
        var host = new PanelHost(panel);
        PanelHost.register(project, host);
        var content = ContentFactory.getInstance().createContent(host.toolHolder, "", false);
        toolWindow.getContentManager().addContent(content);
        Disposer.register(toolWindow.getDisposable(), panel.embedder::dispose);
        AutoTest.maybeRun(panel, toolWindow, project);
    }
}
