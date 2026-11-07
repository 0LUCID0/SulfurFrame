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
        listenForSaves(project, toolWindow, panel);
        AutoTest.maybeRun(panel, toolWindow, project);
    }

    /** Saved .java/.kt files -> hotswap (debounced, so "Save All" of many files makes one swap). */
    private static void listenForSaves(Project project, ToolWindow toolWindow, SulfurPanel panel) {
        var pending = new java.util.LinkedHashSet<Path>();
        var timer = new javax.swing.Timer(500, e -> {
            var files = new java.util.ArrayList<>(pending);
            pending.clear();
            panel.onSourcesSaved(files);
        });
        timer.setRepeats(false);
        project.getMessageBus().connect(toolWindow.getDisposable()).subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
                new com.intellij.openapi.vfs.newvfs.BulkFileListener() {
                    @Override public void after(java.util.List<? extends com.intellij.openapi.vfs.newvfs.events.VFileEvent> events) {
                        boolean any = false;
                        for (var ev : events) {
                            if (!(ev instanceof com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent)) continue;
                            String p = ev.getPath();
                            // build output and the game's run dir change all the time (Gradle, our own resource copies):
                            // they are not saves, and treating them as such offered restarts for nothing
                            if (p.contains("/build/") || p.contains("/.gradle/") || p.contains("/run/") || p.contains("/out/")) continue;
                            if (p.endsWith(".java") || p.endsWith(".kt") || p.contains("/resources/")   /* VFS paths always use '/' */
                                    || SulfurPanel.needsRestart(Path.of(p))) { pending.add(Path.of(p)); any = true; }
                        }
                        if (any) javax.swing.SwingUtilities.invokeLater(timer::restart);
                    }
                });
    }
}
