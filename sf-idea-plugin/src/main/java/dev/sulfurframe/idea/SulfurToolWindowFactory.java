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
        var host = PanelHost.obtain(project);
        var panel = host.panel;
        var content = ContentFactory.getInstance().createContent(host.toolHolder, "", false);
        toolWindow.getContentManager().addContent(content);
        Disposer.register(toolWindow.getDisposable(), panel.embedder::dispose);
        listenForSaves(project, toolWindow, panel);
        AutoTest.maybeRun(panel, toolWindow, project);
    }

    /**
     * Saved .java/.kt files -> hotswap (debounced, so "Save All" of many files makes one swap). Two sources: the IDE's
     * file system events (saves in the editor) and a watcher on the source folders (files changed outside the IDE,
     * which the IDE only notices when its window is activated). A save seen by both lands in the same debounce.
     */
    private static void listenForSaves(Project project, ToolWindow toolWindow, SulfurPanel panel) {
        var pending = new java.util.LinkedHashSet<Path>();
        var timer = new javax.swing.Timer(500, e -> {
            var files = new java.util.ArrayList<>(pending);
            pending.clear();
            panel.onSourcesSaved(files);
        });
        timer.setRepeats(false);
        java.util.function.Consumer<String> offer = p -> {   // p with '/' separators
            // build output and the game's run dir change all the time (Gradle, our own resource copies):
            // they are not saves, and treating them as such offered restarts for nothing
            if (p.contains("/build/") || p.contains("/.gradle/") || p.contains("/run/") || p.contains("/out/")) return;
            if (!(p.endsWith(".java") || p.endsWith(".kt") || p.contains("/resources/") || SulfurPanel.needsRestart(Path.of(p)))) return;
            javax.swing.SwingUtilities.invokeLater(() -> { pending.add(Path.of(p).toAbsolutePath().normalize()); timer.restart(); });
        };
        project.getMessageBus().connect(toolWindow.getDisposable()).subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
                new com.intellij.openapi.vfs.newvfs.BulkFileListener() {
                    @Override public void after(java.util.List<? extends com.intellij.openapi.vfs.newvfs.events.VFileEvent> events) {
                        for (var ev : events)
                            if (ev instanceof com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent) offer.accept(ev.getPath());   // VFS paths always use '/'
                    }
                });
        panel.watchSources(f -> offer.accept(f.toString().replace('\\', '/')));
        Disposer.register(toolWindow.getDisposable(), panel::stopWatchingSources);
    }
}
