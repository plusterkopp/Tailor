package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.HighlightProfile;
import de.pkmd.tailor.settings.FileEntry;
import de.pkmd.tailor.settings.FileSet;
import de.pkmd.tailor.settings.Settings;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Create, edit, delete and open file sets. */
final class FileSetDialog extends JDialog {

    private final MainFrame owner;
    private final Settings settings;
    private final List<FileSet> sets;
    private final DefaultListModel<FileSet> listModel = new DefaultListModel<>();
    private final JList<FileSet> list = new JList<>(listModel);
    private final EntryModel entries = new EntryModel();
    private final JTable table = new JTable(entries);

    static void show(MainFrame owner, Settings settings) {
        new FileSetDialog(owner, settings).setVisible(true);
    }

    private FileSetDialog(MainFrame owner, Settings settings) {
        super(owner, "File Sets", true);
        this.owner = owner;
        this.settings = settings;
        this.sets = new ArrayList<>(settings.fileSets.stream().map(FileSet::copy).toList());
        sets.forEach(listModel::addElement);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            entries.fireTableDataChanged();
        });

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(new JLabel("File sets"), BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel lb = new JPanel(new GridLayout(2, 2, 4, 4));
        lb.add(button("New…", this::newSet));
        lb.add(button("Duplicate…", this::duplicateSet));
        lb.add(button("Rename…", this::renameSet));
        lb.add(button("Delete", this::deleteSet));
        left.add(lb, BorderLayout.SOUTH);
        left.setMinimumSize(new Dimension(200, 200));

        table.setRowHeight(Math.max(table.getRowHeight(), 24));
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", true);
        JComboBox<String> profiles = new JComboBox<>();
        profiles.addItem("");
        settings.profiles.forEach(p -> profiles.addItem(p.getName()));
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(profiles));
        JComboBox<String> charsets = new JComboBox<>(new String[]{"", "UTF-8", "ISO-8859-1", "windows-1252",
                "UTF-16LE", "UTF-16BE"});
        charsets.setEditable(true);
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(charsets));
        table.getColumnModel().getColumn(0).setPreferredWidth(380);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.add(new JLabel("Files (the file name may contain * and ? wildcards)"), BorderLayout.NORTH);
        right.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel rb = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rb.add(button("Add Files…", this::addFiles));
        rb.add(button("Add Pattern…", this::addPattern));
        rb.add(button("Add Open Tabs", this::addOpenTabs));
        rb.add(button("Remove", this::removeEntries));
        rb.add(button("▲", () -> moveEntry(-1)));
        rb.add(button("▼", () -> moveEntry(1)));
        JLabel hint = new JLabel("<html>Newest: open only the most recently modified file matching a pattern. "
                + "Profile and encoding may be left empty for the defaults.</html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(rb, BorderLayout.NORTH);
        south.add(hint, BorderLayout.CENTER);
        right.add(south, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.25);
        split.setBorder(null);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton open = button("Open Set", () -> {
            FileSet s = list.getSelectedValue();
            if (s != null && save()) {
                dispose();
                owner.openFileSet(s);
            }
        });
        JButton ok = button("OK", () -> {
            if (save()) {
                dispose();
            }
        });
        buttons.add(open);
        buttons.add(ok);
        buttons.add(button("Cancel", this::dispose));
        getRootPane().setDefaultButton(ok);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(split, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        if (!sets.isEmpty()) {
            list.setSelectedIndex(0);
        }
        setSize(980, 520);
        setLocationRelativeTo(owner);
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    private FileSet current() {
        return list.getSelectedValue();
    }

    private boolean save() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        settings.fileSets.clear();
        settings.fileSets.addAll(sets);
        owner.saveSettings();
        return true;
    }

    // ------------------------------------------------------------------ sets

    private void newSet() {
        String name = askName("New file set");
        if (name != null) {
            FileSet s = new FileSet(name, List.of());
            sets.add(s);
            listModel.addElement(s);
            list.setSelectedValue(s, true);
        }
    }

    private void duplicateSet() {
        FileSet cur = current();
        if (cur == null) {
            return;
        }
        String name = askName(cur.getName() + " copy");
        if (name != null) {
            FileSet s = cur.copy();
            s.setName(name);
            sets.add(s);
            listModel.addElement(s);
            list.setSelectedValue(s, true);
        }
    }

    private void renameSet() {
        FileSet cur = current();
        if (cur == null) {
            return;
        }
        String name = askName(cur.getName());
        if (name != null) {
            cur.setName(name);
            list.repaint();
        }
    }

    private void deleteSet() {
        FileSet cur = current();
        if (cur != null && JOptionPane.showConfirmDialog(this, "Delete file set “" + cur.getName() + "”?",
                "Delete", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            sets.remove(cur);
            listModel.removeElement(cur);
        }
    }

    private String askName(String initial) {
        String name = (String) JOptionPane.showInputDialog(this, "Name:", "File Set", JOptionPane.PLAIN_MESSAGE,
                null, null, initial);
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim();
        if (sets.stream().anyMatch(s -> s.getName().equals(n) && s != current())) {
            JOptionPane.showMessageDialog(this, "A file set named “" + n + "” already exists.");
            return null;
        }
        return n;
    }

    // ------------------------------------------------------------------ entries

    private void addEntries(List<FileEntry> add) {
        FileSet cur = current();
        if (cur == null) {
            newSet();
            cur = current();
            if (cur == null) {
                return;
            }
        }
        cur.getFiles().addAll(add);
        entries.fireTableDataChanged();
    }

    private void addFiles() {
        JFileChooser fc = new JFileChooser(settings.lastDirectory);
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            List<FileEntry> add = new ArrayList<>();
            for (File f : fc.getSelectedFiles()) {
                add.add(new FileEntry(f.getAbsolutePath()));
            }
            addEntries(add);
        }
    }

    private void addPattern() {
        String example = (settings.lastDirectory != null ? settings.lastDirectory : System.getProperty("user.home"))
                + File.separator + "*.log";
        String p = (String) JOptionPane.showInputDialog(this,
                "Path with wildcards in the file name, e.g. " + example, "Add Pattern",
                JOptionPane.PLAIN_MESSAGE, null, null, example);
        if (p != null && !p.isBlank()) {
            FileEntry e = new FileEntry(p.trim());
            e.setNewestOnly(true);
            addEntries(List.of(e));
        }
    }

    private void addOpenTabs() {
        addEntries(owner.openTabEntries(false));
    }

    private void removeEntries() {
        FileSet cur = current();
        int[] rows = table.getSelectedRows();
        if (cur == null || rows.length == 0) {
            return;
        }
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        for (int i = rows.length - 1; i >= 0; i--) {
            cur.getFiles().remove(rows[i]);
        }
        entries.fireTableDataChanged();
    }

    private void moveEntry(int delta) {
        FileSet cur = current();
        int i = table.getSelectedRow();
        if (cur == null || i < 0 || i + delta < 0 || i + delta >= cur.getFiles().size()) {
            return;
        }
        List<FileEntry> files = cur.getFiles();
        files.add(i + delta, files.remove(i));
        entries.fireTableDataChanged();
        table.setRowSelectionInterval(i + delta, i + delta);
    }

    private final class EntryModel extends AbstractTableModel {
        private final String[] columns = {"Path", "Newest", "Highlight profile", "Encoding"};

        private List<FileEntry> rows() {
            FileSet s = current();
            return s == null ? List.of() : s.getFiles();
        }

        @Override
        public int getRowCount() {
            return rows().size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 1 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c != 1 || rows().get(r).isPattern();
        }

        @Override
        public Object getValueAt(int r, int c) {
            FileEntry e = rows().get(r);
            return switch (c) {
                case 0 -> e.getPath();
                case 1 -> e.isNewestOnly() && e.isPattern();
                case 2 -> e.getProfile() == null ? "" : e.getProfile();
                default -> e.getCharset() == null ? "" : e.getCharset();
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            FileEntry e = rows().get(r);
            String s = v == null ? "" : v.toString().trim();
            switch (c) {
                case 0 -> e.setPath(s);
                case 1 -> e.setNewestOnly(Boolean.TRUE.equals(v));
                case 2 -> e.setProfile(s.isEmpty() || s.equals(HighlightProfile.DEFAULT_NAME) ? null : s);
                default -> e.setCharset(s.isEmpty() ? null : s);
            }
            fireTableRowsUpdated(r, r);
        }
    }
}
