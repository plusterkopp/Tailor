package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.HighlightRule;
import de.pkmd.tailor.settings.Settings;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.charset.Charset;
import java.util.List;
import java.util.TreeSet;

/** Preferences: font and spacing, files and polling, appearance. */
final class SettingsDialog extends JDialog {

    private final MainFrame owner;
    private final Settings settings;

    private final JComboBox<String> fontFamily = new JComboBox<>();
    private final JCheckBox monospacedOnly = new JCheckBox("Monospaced fonts only", true);
    private final JSpinner fontSize = spinner(13, 6, 72);
    private final JSpinner lineGap = spinner(2, 0, 64);
    private final JSpinner wrapGap = spinner(0, 0, 64);
    private final JSpinner tabWidth = spinner(4, 1, 16);
    private final JCheckBox wrap = new JCheckBox("Wrap long lines");
    private final JCheckBox lineNumbers = new JCheckBox("Show line numbers");
    private final JCheckBox follow = new JCheckBox("Follow the end of newly opened files");
    private final JSpinner pollInterval = spinner(500, 50, 60_000);
    private final JSpinner maxLine = spinner(64, 1, 16 * 1024);
    private final JComboBox<String> charset = new JComboBox<>();
    private final JComboBox<String> theme = new JComboBox<>(Settings.THEMES.toArray(new String[0]));
    private final JComboBox<String> tabPlacement = new JComboBox<>(new String[]{"Top", "Bottom", "Left", "Right"});
    private final JCheckBox restoreSession = new JCheckBox("Reopen files and their state on startup");
    private final ColorButton searchColor = new ColorButton("Search highlight", false);
    private final ColorButton textFg = new ColorButton("Text color", true);
    private final ColorButton textBg = new ColorButton("Background", true);
    private final LogView preview;

    static void show(MainFrame owner, Settings settings) {
        new SettingsDialog(owner, settings).setVisible(true);
    }

    private SettingsDialog(MainFrame owner, Settings settings) {
        super(owner, "Settings", true);
        this.owner = owner;
        this.settings = settings;
        preview = new LogView(ViewConfig.from(settings));
        preview.setPreferredSize(new Dimension(520, 150));
        preview.setSource(LineSource.of(List.of(
                "2026-11-24 10:15:09,042 ERROR [pool-3] de.pkmd.app.Handler - Request failed while processing order "
                        + "4711 for customer 0815; the remote service answered with status 503 (Service Unavailable)",
                "2026-11-24 10:15:10,500 INFO  [main] Unicode: Ärger 日本語 Δελτα "
                        + "✓ →",
                "\tat de.pkmd.app.Db.query(Db.java:117)",
                "2026-11-24 10:15:11,000 WARN  [pool-1] Spacing: lines are separated by the line gap, "
                        + "wrapped rows of one line by the wrap gap.")), true);
        preview.setHighlighter(owner.highlighter(null));

        JTabbedPane pages = new JTabbedPane();
        pages.addTab("View", viewPage());
        pages.addTab("Files", filesPage());
        pages.addTab("Appearance", appearancePage());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        JButton apply = new JButton("Apply");
        ok.addActionListener(e -> {
            store();
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        apply.addActionListener(e -> store());
        buttons.add(ok);
        buttons.add(cancel);
        buttons.add(apply);
        getRootPane().setDefaultButton(ok);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(pages, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        load();
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    private JComponent viewPage() {
        Form f = new Form();
        monospacedOnly.addActionListener(e -> fillFonts((String) fontFamily.getSelectedItem()));
        JPanel fontRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fontRow.add(fontFamily);
        fontRow.add(new JLabel("  Size "));
        fontRow.add(fontSize);
        f.row("Font:", fontRow);
        f.row("", monospacedOnly);
        f.row("Space between lines:", withUnit(lineGap, "px"));
        f.row("Space between wrapped rows:", withUnit(wrapGap, "px"));
        f.row("Tab width:", withUnit(tabWidth, "columns"));
        f.row("", wrap);
        f.row("", lineNumbers);
        f.row("", follow);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(f, BorderLayout.NORTH);
        JPanel prev = new JPanel(new BorderLayout());
        prev.setBorder(BorderFactory.createTitledBorder("Preview"));
        prev.add(preview);
        p.add(prev, BorderLayout.CENTER);
        for (JSpinner s : List.of(fontSize, lineGap, wrapGap, tabWidth)) {
            s.addChangeListener(e -> updatePreview());
        }
        fontFamily.addActionListener(e -> updatePreview());
        wrap.addActionListener(e -> updatePreview());
        lineNumbers.addActionListener(e -> updatePreview());
        return p;
    }

    private JComponent filesPage() {
        Form f = new Form();
        f.row("Check files every:", withUnit(pollInterval, "ms"));
        f.row("Maximum line length:", withUnit(maxLine, "KiB (longer lines are shown truncated)"));
        charset.setEditable(true);
        for (String cs : List.of("UTF-8", "ISO-8859-1", "ISO-8859-15", "windows-1252", "UTF-16LE", "UTF-16BE")) {
            charset.addItem(cs);
        }
        f.row("Encoding without BOM:", charset);
        f.row("", restoreSession);
        f.row("", new JLabel("<html><small>Files are polled, never locked: writers can rotate, rename or delete "
                + "them at any time.</small></html>"));
        return wrapNorth(f);
    }

    private JComponent appearancePage() {
        Form f = new Form();
        f.row("Theme:", theme);
        f.row("Tabs:", tabPlacement);
        f.row("Text color:", textFg);
        f.row("Background:", textBg);
        f.row("Search highlight:", searchColor);
        f.row("", new JLabel("<html><small>Text and background default to the theme's colors.</small></html>"));
        return wrapNorth(f);
    }

    private void load() {
        fillFonts(ViewConfig.resolveFontFamily(settings.fontFamily));
        fontSize.setValue(settings.fontSize);
        lineGap.setValue(settings.lineGap);
        wrapGap.setValue(settings.wrapGap);
        tabWidth.setValue(settings.tabWidth);
        wrap.setSelected(settings.wrap);
        lineNumbers.setSelected(settings.showLineNumbers);
        follow.setSelected(settings.followTail);
        pollInterval.setValue(settings.pollIntervalMs);
        maxLine.setValue(settings.maxLineKiB);
        charset.setSelectedItem(settings.defaultCharset);
        theme.setSelectedItem(settings.theme);
        tabPlacement.setSelectedItem(settings.tabPlacement);
        restoreSession.setSelected(settings.restoreSession);
        searchColor.set(HighlightRule.parseColor(settings.searchHighlight));
        textFg.set(HighlightRule.parseColor(settings.textForeground));
        textBg.set(HighlightRule.parseColor(settings.textBackground));
        updatePreview();
    }

    private void writeTo(Settings s) {
        s.fontFamily = (String) fontFamily.getSelectedItem();
        s.fontSize = (Integer) fontSize.getValue();
        s.lineGap = (Integer) lineGap.getValue();
        s.wrapGap = (Integer) wrapGap.getValue();
        s.tabWidth = (Integer) tabWidth.getValue();
        s.wrap = wrap.isSelected();
        s.showLineNumbers = lineNumbers.isSelected();
        s.followTail = follow.isSelected();
        s.pollIntervalMs = (Integer) pollInterval.getValue();
        s.maxLineKiB = (Integer) maxLine.getValue();
        String cs = String.valueOf(charset.getSelectedItem()).trim();
        s.defaultCharset = Charset.isSupported(cs) ? cs : "UTF-8";
        s.restoreSession = restoreSession.isSelected();
        s.tabPlacement = (String) tabPlacement.getSelectedItem();
        s.searchHighlight = HighlightRule.formatColor(searchColor.get());
        s.textForeground = HighlightRule.formatColor(textFg.get());
        s.textBackground = HighlightRule.formatColor(textBg.get());
    }

    private void store() {
        writeTo(settings);
        String newTheme = (String) theme.getSelectedItem();
        if (!newTheme.equals(settings.theme)) {
            settings.theme = newTheme;
            owner.applyTheme();
        } else {
            owner.applySettings();
        }
        owner.saveSettings();
    }

    private void updatePreview() {
        Settings tmp = new Settings();
        writeTo(tmp);
        tmp.maxLineKiB = Math.max(1, tmp.maxLineKiB);
        preview.setConfig(ViewConfig.from(tmp.normalize()));
    }

    private void fillFonts(String select) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.add(Font.MONOSPACED);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        for (String fam : ViewConfig.installedFamilies()) {
            if (!monospacedOnly.isSelected() || isMonospaced(g, fam)) {
                names.add(fam);
            }
        }
        g.dispose();
        if (select != null) {
            names.add(select);
        }
        fontFamily.removeAllItems();
        for (String n : names) {
            fontFamily.addItem(n);
        }
        fontFamily.setSelectedItem(select);
    }

    private static boolean isMonospaced(java.awt.Graphics2D g, String family) {
        java.awt.FontMetrics fm = g.getFontMetrics(new Font(family, Font.PLAIN, 12));
        return fm.charWidth('i') == fm.charWidth('W') && fm.charWidth('i') > 0 && fm.charWidth('.') == fm.charWidth('m');
    }

    // ------------------------------------------------------------------ helpers

    private static JSpinner spinner(int value, int min, int max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, 1));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(5);
        return s;
    }

    private static JComponent withUnit(JComponent c, String unit) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.add(c);
        p.add(new JLabel("  " + unit));
        return p;
    }

    private static JComponent wrapNorth(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(c, BorderLayout.NORTH);
        return p;
    }

    private static final class Form extends JPanel {
        private int y;

        Form() {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        }

        void row(String label, JComponent c) {
            GridBagConstraints l = new GridBagConstraints();
            l.gridx = 0;
            l.gridy = y;
            l.anchor = GridBagConstraints.LINE_END;
            l.insets = new Insets(4, 0, 4, 10);
            add(new JLabel(label), l);
            GridBagConstraints f = new GridBagConstraints();
            f.gridx = 1;
            f.gridy = y++;
            f.weightx = 1;
            f.anchor = GridBagConstraints.LINE_START;
            f.insets = new Insets(4, 0, 4, 0);
            add(c, f);
        }
    }

    /** Color picker button; optional colors get a "Default" button to clear them. */
    private static final class ColorButton extends JPanel {
        private final JButton button = new JButton();
        private Color color;

        ColorButton(String title, boolean optional) {
            super(new FlowLayout(FlowLayout.LEFT, 0, 0));
            add(button);
            button.addActionListener(e -> {
                Color c = JColorChooser.showDialog(this, title, color != null ? color : Color.WHITE);
                if (c != null) {
                    set(new Color(c.getRGB()));
                }
            });
            if (optional) {
                JButton reset = new JButton("Default");
                reset.addActionListener(e -> set(null));
                add(javax.swing.Box.createHorizontalStrut(6));
                add(reset);
            }
        }

        void set(Color c) {
            color = c;
            button.setIcon(Icons.swatch(c, 28, 14));
            button.setText(c == null ? "theme default" : HighlightRule.formatColor(c));
        }

        Color get() {
            return color;
        }
    }
}
