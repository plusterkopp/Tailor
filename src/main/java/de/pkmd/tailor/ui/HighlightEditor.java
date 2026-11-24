package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.HighlightProfile;
import de.pkmd.tailor.highlight.HighlightRule;
import de.pkmd.tailor.highlight.HighlightRule.Scope;
import de.pkmd.tailor.highlight.Highlighter;
import de.pkmd.tailor.settings.Settings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Editor for highlight profiles and their rules.
 * <p>
 * Left: the ordered rule list of the selected profile, each row previewing the rule's own style.
 * Right: a form for the selected rule. Every attribute can be set or left to inherit from the
 * layers below. Bottom: a live preview rendering sample lines with the real log view.
 */
final class HighlightEditor extends JDialog {

    private static final String[] TRI = {"Inherit", "On", "Off"};
    private static final List<String> DEFAULT_SAMPLE = List.of(
            "2026-11-24 10:15:02,113 INFO  [main] de.pkmd.app.Server - Server started on port 8080",
            "2026-11-24 10:15:03,201 DEBUG [pool-1] de.pkmd.app.Cache - Cache warmed up: 1834 entries",
            "2026-11-24 10:15:07,880 WARN  [pool-2] de.pkmd.app.Db - Slow query took 1830 ms",
            "2026-11-24 10:15:09,042 ERROR [pool-3] de.pkmd.app.Handler - Request failed",
            "java.lang.IllegalStateException: connection closed",
            "\tat de.pkmd.app.Db.query(Db.java:117)",
            "\tat de.pkmd.app.Handler.handle(Handler.java:52)",
            "Caused by: java.net.SocketException: Connection reset",
            "2026-11-24 10:15:10,500 INFO  [main] de.pkmd.app.Server - Unicode: Ärger 日本語 Δελτα");

    private final Settings settings;
    private final List<HighlightProfile> profiles;
    private final Runnable onApply;
    private HighlightProfile profile;
    private HighlightRule rule;
    private boolean loading;

    private final JComboBox<HighlightProfile> profileCombo = new JComboBox<>();
    private final RuleTableModel tableModel = new RuleTableModel();
    private final JTable table = new JTable(tableModel);

    private final JTextField nameField = new JTextField(24);
    private final JTextField patternField = new JTextField(30);
    private final JLabel patternError = new JLabel(" ");
    private final JCheckBox regexBox = new JCheckBox("Regular expression");
    private final JCheckBox caseBox = new JCheckBox("Match case");
    private final JRadioButton scopeLine = new JRadioButton("Whole line");
    private final JRadioButton scopeMatch = new JRadioButton("Matched text only");
    private final ColorField fgField = new ColorField("Text color");
    private final ColorField bgField = new ColorField("Background");
    private final JComboBox<String> boldBox = new JComboBox<>(TRI);
    private final JComboBox<String> italicBox = new JComboBox<>(TRI);
    private final JComboBox<String> underlineBox = new JComboBox<>(TRI);
    private final JComboBox<String> strikeBox = new JComboBox<>(TRI);
    private final JPanel form = new JPanel(new GridBagLayout());

    private final LogView preview;
    private final JTextArea sampleArea = new JTextArea();
    private final Timer previewTimer = new Timer(150, e -> refreshPreview());

    static void show(Frame owner, Settings settings, String profileName, HighlightRule newRule, List<String> sample,
                     Runnable onApply) {
        HighlightEditor d = new HighlightEditor(owner, settings, profileName, newRule, sample, onApply);
        d.setVisible(true);
    }

    private HighlightEditor(Frame owner, Settings settings, String profileName, HighlightRule newRule,
                            List<String> sample, Runnable onApply) {
        super(owner, "Highlight Rules", true);
        this.settings = settings;
        this.onApply = onApply;
        this.profiles = new ArrayList<>(settings.profiles.stream().map(HighlightProfile::copy).toList());
        previewTimer.setRepeats(false);

        preview = new LogView(ViewConfig.from(settings).withWrap(false));
        preview.setPreferredSize(new Dimension(700, 190));
        sampleArea.setText(String.join("\n", sample.isEmpty() ? DEFAULT_SAMPLE : sample));
        sampleArea.setFont(ViewConfig.from(settings).font());
        onChange(sampleArea, previewTimer::restart);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(createProfileBar(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createRuleList(), createForm());
        split.setResizeWeight(0.5);
        split.setBorder(null);

        JTabbedPane bottom = new JTabbedPane();
        bottom.addTab("Preview", preview);
        bottom.addTab("Sample text", new JScrollPane(sampleArea));
        bottom.setPreferredSize(new Dimension(700, 220));

        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, split, bottom);
        vertical.setResizeWeight(0.65);
        vertical.setBorder(null);
        content.add(vertical, BorderLayout.CENTER);
        content.add(createButtons(), BorderLayout.SOUTH);
        setContentPane(content);

        HighlightProfile start = profiles.stream().filter(p -> p.getName().equals(profileName)).findFirst()
                .orElse(profiles.getFirst());
        reloadProfileCombo(start);
        selectProfile(start);
        if (newRule != null) {
            profile.getRules().addFirst(newRule);
            tableModel.fireTableDataChanged();
            table.setRowSelectionInterval(0, 0);
            SwingUtilities.invokeLater(patternField::requestFocusInWindow);
        }
        setSize(1060, 760);
        setLocationRelativeTo(owner);
    }

    // ------------------------------------------------------------------ layout

    private JComponent createProfileBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.add(new JLabel("Profile:"));
        profileCombo.setPrototypeDisplayValue(new HighlightProfile("A rather long profile name", List.of()));
        profileCombo.addActionListener(e -> {
            if (!loading && profileCombo.getSelectedItem() instanceof HighlightProfile hp) {
                selectProfile(hp);
            }
        });
        p.add(profileCombo);
        p.add(button("New…", () -> addProfile(false)));
        p.add(button("Duplicate…", () -> addProfile(true)));
        p.add(button("Rename…", this::renameProfile));
        p.add(button("Delete", this::deleteProfile));
        return p;
    }

    private JComponent createRuleList() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(Math.max(table.getRowHeight(), 24));
        table.setShowHorizontalLines(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = table.getSelectedRow();
                loadRule(row >= 0 && row < profile.getRules().size() ? profile.getRules().get(row) : null);
            }
        });
        TableColumn on = table.getColumnModel().getColumn(0);
        on.setMaxWidth(36);
        on.setMinWidth(36);
        TableColumn scope = table.getColumnModel().getColumn(2);
        scope.setPreferredWidth(90);
        scope.setMaxWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(3).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setCellRenderer(new StyleRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer(new PatternRenderer());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(button("Add", this::addRule));
        buttons.add(button("Duplicate", this::duplicateRule));
        buttons.add(button("Remove", this::removeRule));
        buttons.add(button("▲ Up", () -> moveRule(-1)));
        buttons.add(button("▼ Down", () -> moveRule(1)));

        JLabel hint = new JLabel("<html>Line rules color whole lines; match rules style the matched text on top. "
                + "Higher rules are drawn above lower ones. Unset attributes show the layer below.</html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.NORTH);
        south.add(hint, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        p.setMinimumSize(new Dimension(300, 200));
        return p;
    }

    private JComponent createForm() {
        form.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Rule"),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)));
        ButtonGroup g = new ButtonGroup();
        g.add(scopeLine);
        g.add(scopeMatch);
        patternError.setForeground(new Color(0xD03838));
        patternField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, patternField.getFont().getSize()));

        int y = 0;
        row(y++, "Name:", nameField);
        row(y++, "Pattern:", patternField);
        JPanel opts = flow(regexBox, caseBox);
        row(y++, "", opts);
        row(y++, "", patternError);
        row(y++, "Applies to:", flow(scopeLine, scopeMatch));
        row(y++, "Text color:", fgField);
        row(y++, "Background:", bgField);
        JPanel fontAttrs = new JPanel(new GridBagLayout());
        attr(fontAttrs, 0, 0, "Bold", boldBox);
        attr(fontAttrs, 1, 0, "Italic", italicBox);
        attr(fontAttrs, 0, 1, "Underline", underlineBox);
        attr(fontAttrs, 1, 1, "Strikethrough", strikeBox);
        row(y++, "Font:", fontAttrs);
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = y;
        filler.weighty = 1;
        form.add(Box.createVerticalGlue(), filler);

        onChange(nameField, this::store);
        onChange(patternField, this::store);
        regexBox.addActionListener(e -> store());
        caseBox.addActionListener(e -> store());
        scopeLine.addActionListener(e -> store());
        scopeMatch.addActionListener(e -> store());
        fgField.onChange(this::store);
        bgField.onChange(this::store);
        for (JComboBox<String> c : List.of(boldBox, italicBox, underlineBox, strikeBox)) {
            c.addActionListener(e -> store());
            c.setRenderer(new TriRenderer());
        }
        boldBox.setToolTipText("Inherit: keep what the layers below use");
        JScrollPane sp = new JScrollPane(form);
        sp.setBorder(null);
        sp.setMinimumSize(new Dimension(320, 200));
        return sp;
    }

    private void row(int y, String label, JComponent c) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = y;
        l.anchor = GridBagConstraints.LINE_END;
        l.insets = new Insets(3, 0, 3, 8);
        form.add(new JLabel(label), l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = y;
        f.weightx = 1;
        f.anchor = GridBagConstraints.LINE_START;
        f.fill = c instanceof JTextField ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        f.insets = new Insets(3, 0, 3, 0);
        form.add(c, f);
    }

    private static void attr(JPanel p, int col, int row, String label, JComponent c) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = col * 2;
        l.gridy = row;
        l.anchor = GridBagConstraints.LINE_END;
        l.insets = new Insets(0, col == 0 ? 0 : 14, 4, 6);
        p.add(new JLabel(label), l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = col * 2 + 1;
        f.gridy = row;
        f.anchor = GridBagConstraints.LINE_START;
        f.insets = new Insets(0, 0, 4, 0);
        p.add(c, f);
    }

    private static JPanel flow(JComponent... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (JComponent c : cs) {
            p.add(c);
            p.add(Box.createHorizontalStrut(12));
        }
        return p;
    }

    private JComponent createButtons() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton ok = button("OK", () -> {
            apply();
            dispose();
        });
        p.add(ok);
        p.add(button("Cancel", this::dispose));
        p.add(button("Apply", this::apply));
        getRootPane().setDefaultButton(ok);
        return p;
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    // ------------------------------------------------------------------ profiles

    private void reloadProfileCombo(HighlightProfile select) {
        loading = true;
        profileCombo.removeAllItems();
        for (HighlightProfile p : profiles) {
            profileCombo.addItem(p);
        }
        profileCombo.setSelectedItem(select);
        loading = false;
    }

    private void selectProfile(HighlightProfile p) {
        profile = p;
        tableModel.fireTableDataChanged();
        if (!p.getRules().isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        } else {
            loadRule(null);
        }
        refreshPreview();
    }

    private void addProfile(boolean duplicate) {
        String name = askName(duplicate ? profile.getName() + " copy" : "New profile");
        if (name == null) {
            return;
        }
        HighlightProfile p = duplicate ? profile.copy() : new HighlightProfile(name, List.of());
        p.setName(name);
        profiles.add(p);
        reloadProfileCombo(p);
        selectProfile(p);
    }

    private void renameProfile() {
        if (HighlightProfile.DEFAULT_NAME.equals(profile.getName())) {
            JOptionPane.showMessageDialog(this, "The default profile cannot be renamed.");
            return;
        }
        String name = askName(profile.getName());
        if (name != null) {
            profile.setName(name);
            reloadProfileCombo(profile);
        }
    }

    private void deleteProfile() {
        if (HighlightProfile.DEFAULT_NAME.equals(profile.getName())) {
            JOptionPane.showMessageDialog(this, "The default profile cannot be deleted.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Delete profile “" + profile.getName() + "”?",
                "Delete Profile", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            profiles.remove(profile);
            reloadProfileCombo(profiles.getFirst());
            selectProfile(profiles.getFirst());
        }
    }

    private String askName(String initial) {
        String name = (String) JOptionPane.showInputDialog(this, "Profile name:", "Profile",
                JOptionPane.PLAIN_MESSAGE, null, null, initial);
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim();
        if (profiles.stream().anyMatch(p -> p.getName().equals(n) && p != profile)) {
            JOptionPane.showMessageDialog(this, "A profile named “" + n + "” already exists.");
            return null;
        }
        return n;
    }

    // ------------------------------------------------------------------ rules

    private void addRule() {
        HighlightRule r = new HighlightRule("New rule", "", Scope.MATCH, null, "#C8E6FF");
        int at = Math.max(0, table.getSelectedRow());
        profile.getRules().add(at, r);
        tableModel.fireTableDataChanged();
        table.setRowSelectionInterval(at, at);
        patternField.requestFocusInWindow();
    }

    private void duplicateRule() {
        int i = table.getSelectedRow();
        if (i >= 0) {
            HighlightRule copy = profile.getRules().get(i).copy();
            copy.setName(copy.getName() + " copy");
            profile.getRules().add(i + 1, copy);
            tableModel.fireTableDataChanged();
            table.setRowSelectionInterval(i + 1, i + 1);
        }
    }

    private void removeRule() {
        int i = table.getSelectedRow();
        if (i >= 0) {
            profile.getRules().remove(i);
            tableModel.fireTableDataChanged();
            int n = profile.getRules().size();
            if (n > 0) {
                int s = Math.min(i, n - 1);
                table.setRowSelectionInterval(s, s);
            } else {
                loadRule(null);
            }
            refreshPreview();
        }
    }

    private void moveRule(int delta) {
        int i = table.getSelectedRow();
        int j = i + delta;
        List<HighlightRule> rules = profile.getRules();
        if (i < 0 || j < 0 || j >= rules.size()) {
            return;
        }
        rules.add(j, rules.remove(i));
        tableModel.fireTableDataChanged();
        table.setRowSelectionInterval(j, j);
        refreshPreview();
    }

    private void loadRule(HighlightRule r) {
        rule = r;
        loading = true;
        try {
            setFormEnabled(r != null);
            if (r == null) {
                return;
            }
            nameField.setText(r.getName());
            patternField.setText(r.getPattern());
            regexBox.setSelected(r.isRegex());
            caseBox.setSelected(r.isCaseSensitive());
            (r.getScope() == Scope.LINE ? scopeLine : scopeMatch).setSelected(true);
            fgField.set(r.getForeground());
            bgField.set(r.getBackground());
            boldBox.setSelectedIndex(tri(r.getBold()));
            italicBox.setSelectedIndex(tri(r.getItalic()));
            underlineBox.setSelectedIndex(tri(r.getUnderline()));
            strikeBox.setSelectedIndex(tri(r.getStrikethrough()));
            validatePattern();
        } finally {
            loading = false;
        }
    }

    private void setFormEnabled(boolean on) {
        for (Component c : List.of(nameField, patternField, regexBox, caseBox, scopeLine, scopeMatch, fgField,
                bgField, boldBox, italicBox, underlineBox, strikeBox)) {
            c.setEnabled(on);
        }
    }

    /** Copies the form into the selected rule. */
    private void store() {
        if (loading || rule == null) {
            return;
        }
        rule.setName(nameField.getText());
        rule.setPattern(patternField.getText());
        rule.setRegex(regexBox.isSelected());
        rule.setCaseSensitive(caseBox.isSelected());
        rule.setScope(scopeLine.isSelected() ? Scope.LINE : Scope.MATCH);
        rule.setForeground(fgField.get());
        rule.setBackground(bgField.get());
        rule.setBold(tri(boldBox.getSelectedIndex()));
        rule.setItalic(tri(italicBox.getSelectedIndex()));
        rule.setUnderline(tri(underlineBox.getSelectedIndex()));
        rule.setStrikethrough(tri(strikeBox.getSelectedIndex()));
        validatePattern();
        int row = table.getSelectedRow();
        if (row >= 0) {
            tableModel.fireTableRowsUpdated(row, row);
        }
        previewTimer.restart();
    }

    private void validatePattern() {
        String err = rule == null || rule.getPattern().isEmpty() ? null : rule.validate();
        patternError.setText(err == null ? " " : err);
        patternField.putClientProperty("JComponent.outline", err == null ? null : "error");
    }

    private static int tri(Boolean b) {
        return b == null ? 0 : b ? 1 : 2;
    }

    private static Boolean tri(int i) {
        return i == 1 ? Boolean.TRUE : i == 2 ? Boolean.FALSE : null;
    }

    private void refreshPreview() {
        preview.setHighlighter(Highlighter.compile(profile));
        preview.setSource(LineSource.of(List.of(sampleArea.getText().split("\\R", -1))), true);
    }

    private void apply() {
        settings.profiles.clear();
        settings.profiles.addAll(profiles.stream().map(HighlightProfile::copy).toList());
        onApply.run();
    }

    private static void onChange(javax.swing.text.JTextComponent c, Runnable r) {
        c.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        });
    }

    // ------------------------------------------------------------------ table

    private final class RuleTableModel extends AbstractTableModel {
        private final String[] columns = {"On", "Rule", "Applies to", "Pattern"};

        @Override
        public int getRowCount() {
            return profile == null ? 0 : profile.getRules().size();
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
            return c == 0 ? Boolean.class : Object.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            HighlightRule rule = profile.getRules().get(r);
            return switch (c) {
                case 0 -> rule.isEnabled();
                case 1 -> rule;
                case 2 -> rule.getScope() == Scope.LINE ? "Line" : "Match";
                default -> rule.getPattern();
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 0) {
                profile.getRules().get(r).setEnabled(Boolean.TRUE.equals(v));
                previewTimer.restart();
            }
        }
    }

    /** Renders the rule name in the rule's own style. */
    private static final class StyleRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r,
                                                       int c) {
            HighlightRule rule = (HighlightRule) v;
            super.getTableCellRendererComponent(t, rule.getName(), sel, focus, r, c);
            if (!sel) {
                Color bg = rule.getBackground();
                Color fg = rule.getForeground();
                setBackground(bg != null ? bg : t.getBackground());
                setForeground(fg != null ? fg : t.getForeground());
            }
            int style = (Boolean.TRUE.equals(rule.getBold()) ? Font.BOLD : 0)
                    | (Boolean.TRUE.equals(rule.getItalic()) ? Font.ITALIC : 0);
            Font f = t.getFont().deriveFont(style);
            if (Boolean.TRUE.equals(rule.getUnderline()) || Boolean.TRUE.equals(rule.getStrikethrough())) {
                f = f.deriveFont(Map.of(
                        java.awt.font.TextAttribute.UNDERLINE, Boolean.TRUE.equals(rule.getUnderline())
                                ? java.awt.font.TextAttribute.UNDERLINE_ON : -1,
                        java.awt.font.TextAttribute.STRIKETHROUGH, Boolean.TRUE.equals(rule.getStrikethrough())));
            }
            setFont(f);
            if (!rule.isEnabled()) {
                setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            return this;
        }
    }

    private static final class PatternRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r,
                                                       int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setFont(new Font(Font.MONOSPACED, Font.PLAIN, t.getFont().getSize()));
            HighlightRule rule = (HighlightRule) t.getModel().getValueAt(r, 1);
            String err = rule.validate();
            setToolTipText(err);
            if (err != null && !sel) {
                setForeground(new Color(0xD03838));
            }
            return this;
        }
    }

    private static final class TriRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
            super.getListCellRendererComponent(l, v, i, sel, focus);
            if ("Inherit".equals(v) && !sel) {
                setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            return this;
        }
    }

    /** Optional color: a checkbox ("set") and a swatch button opening a color chooser. */
    private static final class ColorField extends JPanel {
        private final JCheckBox set = new JCheckBox("set");
        private final JButton choose = new JButton("Choose…");
        private final String title;
        private Color color;
        private Runnable listener = () -> { };

        ColorField(String title) {
            this.title = title;
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            add(set);
            add(Box.createHorizontalStrut(8));
            add(choose);
            set.setToolTipText("Unset: inherit the color of the layer below");
            set.addActionListener(e -> {
                if (set.isSelected() && color == null) {
                    pick();
                    if (color == null) {
                        set.setSelected(false);
                    }
                }
                update();
                listener.run();
            });
            choose.addActionListener(e -> {
                pick();
                update();
                listener.run();
            });
        }

        private void pick() {
            Color c = JColorChooser.showDialog(this, title, color != null ? color : Color.WHITE);
            if (c != null) {
                color = new Color(c.getRGB());
                set.setSelected(true);
            }
        }

        void onChange(Runnable r) {
            listener = r;
        }

        void set(Color c) {
            color = c;
            set.setSelected(c != null);
            update();
        }

        Color get() {
            return set.isSelected() ? color : null;
        }

        private void update() {
            Color c = set.isSelected() ? color : null;
            choose.setIcon(Icons.swatch(c, 28, 14));
            choose.setText(c == null ? "inherit" : HighlightRule.formatColor(c));
        }

        @Override
        public void setEnabled(boolean on) {
            super.setEnabled(on);
            set.setEnabled(on);
            choose.setEnabled(on);
        }
    }
}
