package dev.sulfurframe.idea;

import com.intellij.openapi.project.Project;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The dev mods table (catalog + own Modrinth picks, IDE-wide or for this project); part of the settings page. */
final class DevModsEditor {
    private final Project project;
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Use", "Mod", "Kind", "Loaders", "What it is for"}, 0) {
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
    };
    private final JTable table = new JTable(model);
    private final JCheckBox ownForProject = new JCheckBox("This project only");
    final JPanel component = new JPanel(new BorderLayout(0, 6));

    DevModsEditor(Project project) {
        this.project = project;
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setMaxWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(150);
        table.getColumnModel().getColumn(4).setPreferredWidth(330);
        ownForProject.setEnabled(project != null);
        ownForProject.setToolTipText("Keep a separate list for this project (e.g. without a mod it conflicts with); off = the IDE-wide list");
        ownForProject.addActionListener(e -> fill(DevMods.effective(ownForProject.isSelected() ? project : null)));
        var add = new JButton("Add…");
        add.addActionListener(e -> {
            String s = JOptionPane.showInputDialog(component, "Modrinth project name or link (e.g. https://modrinth.com/mod/appleskin):");
            if (s == null || s.isBlank()) return;
            String slug = DevMods.slugOf(s);
            for (int i = 0; i < model.getRowCount(); i++) if (slug.equals(model.getValueAt(i, 1))) { model.setValueAt(true, i, 0); return; }
            model.addRow(new Object[]{true, slug, "added", "?", "your own pick"});
        });
        var remove = new JButton("Remove");
        remove.setToolTipText("Remove an added mod (catalog mods are turned off instead)");
        remove.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r < 0) return;
            if (DevMods.known(String.valueOf(model.getValueAt(r, 1))) != null) model.setValueAt(false, r, 0); else model.removeRow(r);
        });
        var defaults = new JButton("Defaults");
        defaults.addActionListener(e -> fill(DevMods.defaults()));
        var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.add(add); row.add(remove); row.add(defaults); row.add(ownForProject);
        var note = new JLabel("<html>Each launch gets the newest build of the checked mods for its Minecraft version and loader, with their<br>"
                + "required dependencies, from Modrinth (kept in " + DevMods.cacheDir("&lt;version&gt;").getParent() + "). Mods the project<br>"
                + "already includes are skipped; the project's files are not changed. Loaded on Fabric and Quilt for now.</html>");
        var scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(700, 200));
        component.add(row, BorderLayout.NORTH);
        component.add(scroll, BorderLayout.CENTER);
        component.add(note, BorderLayout.SOUTH);
        reset();
    }

    void reset() {
        ownForProject.setSelected(project != null && DevMods.stored(project) != null);
        fill(DevMods.effective(ownForProject.isSelected() ? project : null));
    }

    boolean isModified() {
        boolean own = project != null && DevMods.stored(project) != null;
        return own != ownForProject.isSelected() || !rows().equals(DevMods.effective(ownForProject.isSelected() ? project : null));
    }

    void apply(Consumer<String> log) {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        var out = rows();
        if (ownForProject.isSelected()) DevMods.store(project, out);
        else { DevMods.store(null, out); if (project != null) DevMods.store(project, null); }
        if (log != null) log.accept("dev mods" + (ownForProject.isSelected() ? " (this project)" : "") + ": "
                + (out.stream().noneMatch(DevMods.Entry::on) ? "none" : String.join(", ", out.stream().filter(DevMods.Entry::on).map(DevMods.Entry::slug).toList())) + " (next launch)");
    }

    private List<DevMods.Entry> rows() {
        var out = new ArrayList<DevMods.Entry>();
        for (int i = 0; i < model.getRowCount(); i++) out.add(new DevMods.Entry(String.valueOf(model.getValueAt(i, 1)), Boolean.TRUE.equals(model.getValueAt(i, 0))));
        return out;
    }

    private void fill(List<DevMods.Entry> list) {
        model.setRowCount(0);
        for (var e : list) {
            var k = DevMods.known(e.slug());
            model.addRow(new Object[]{e.on(), e.slug(), k == null ? "added" : k.tier() == DevMods.Tier.REQUIRED ? "required" : "optional",
                    k == null ? "?" : String.join(", ", k.loaders().stream().map(l -> l.name().toLowerCase()).toList()),
                    k == null ? "your own pick" : k.purpose()});
        }
    }
}
