package dev.sulfurframe.idea;

import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;

import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeListener;

/** "Minecraft" editor tab: hosts the project's SulfurPanel in the wide editor area instead of the tool window. */
final class GameTab {
    /** Marker file; one per open tab. */
    static final class GameFile extends LightVirtualFile {
        GameFile() { super("Minecraft"); setWritable(false); }
    }

    static void open(Project project) {
        // opens and focuses the tab; every FileEditorManager.openFile is deprecated in 2026.3 and requestOpenFile is experimental
        new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, new GameFile()).navigate(true);
    }

    public static final class Provider implements FileEditorProvider, DumbAware {
        @Override public boolean accept(Project project, VirtualFile file) { return file instanceof GameFile; }
        @Override public boolean acceptRequiresReadAction() { return false; }
        @Override public FileEditor createEditor(Project project, VirtualFile file) { return new Editor(project, file); }
        @Override public String getEditorTypeId() { return "sulfurframe-game"; }
        @Override public FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }
    }

    static final class Editor extends UserDataHolderBase implements FileEditor {
        private final JPanel holder = new JPanel(new BorderLayout());
        private final VirtualFile file;
        private final PanelHost host;

        Editor(Project project, VirtualFile file) {
            this.file = file;
            this.host = PanelHost.of(project);
            if (host != null) host.moveTo(holder);
            else holder.add(new JLabel("Open the SulfurFrame tool window first", SwingConstants.CENTER));
        }

        @Override public JComponent getComponent() { return holder; }
        @Override public JComponent getPreferredFocusedComponent() { return holder; }
        @Override public String getName() { return "Minecraft"; }
        @Override public void setState(FileEditorState state) { }
        @Override public boolean isModified() { return false; }
        @Override public boolean isValid() { return true; }
        @Override public void addPropertyChangeListener(PropertyChangeListener l) { }
        @Override public void removePropertyChangeListener(PropertyChangeListener l) { }
        @Override public VirtualFile getFile() { return file; }
        @Override public void dispose() { if (host != null) host.moveBack(); }
    }

    private GameTab() {}
}
