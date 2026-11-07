package dev.sulfurframe.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.wm.ToolWindowManager;

/** Opens the tool window on project start, only for the self-test (-Dsulfurframe.autotest.pid). */
public final class AutoTestStartup implements StartupActivity, DumbAware {
    @Override
    public void runActivity(Project project) {
        if (System.getProperty("sulfurframe.autotest.pid") == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            var tw = ToolWindowManager.getInstance(project).getToolWindow("SulfurFrame");
            if (tw != null) tw.show();
        });
    }
}
