package dev.sulfurframe.idea;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;

import javax.swing.*;
import java.nio.file.Path;

public final class SulfurToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        Path dir = project.getBasePath() == null ? null : Path.of(project.getBasePath());
        var panel = new SulfurPanel(dir);
        var content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
        Disposer.register(toolWindow.getDisposable(), panel.embedder::dispose);
        AutoTest.maybeRun(panel, toolWindow);
    }
}
