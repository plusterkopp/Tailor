package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.Query;
import de.pkmd.tailor.settings.Settings;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.util.List;

/**
 * Query input shared by the find bar and the filter pane: an editable combo box with history,
 * the syntax mode (Text / Simple / Regex), "Match case", and live validation.
 */
final class QueryBar {

    static final int HISTORY = 30;

    final JComboBox<String> combo = new JComboBox<>();
    final JTextField field;
    final JComboBox<Query.Mode> mode = new JComboBox<>(Query.Mode.values());
    final JCheckBox matchCase = new JCheckBox("Match case");
    final JLabel error = new JLabel();
    private final List<String> history;
    private final Timer debounce;
    private Query query;
    private Runnable onChange = () -> { };

    QueryBar(List<String> history, String placeholder) {
        this.history = history;
        combo.setEditable(true);
        field = (JTextField) combo.getEditor().getEditorComponent();
        field.putClientProperty("JTextField.placeholderText", placeholder);
        field.putClientProperty("JTextField.showClearButton", true);
        combo.setPrototypeDisplayValue("x".repeat(36));
        combo.setMaximumSize(new Dimension(460, combo.getPreferredSize().height));
        fillHistory();
        combo.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                String t = field.getText();
                fillHistory();
                field.setText(t);
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });
        mode.setSelectedItem(Query.Mode.SIMPLE);
        mode.setMaximumSize(mode.getPreferredSize());
        mode.setToolTipText("<html><b>Text</b>: the literal text<br>"
                + "<b>Simple</b>: all words must occur &nbsp; <code>a | b</code> either &nbsp; "
                + "<code>-word</code> exclude &nbsp; <code>\"two words\"</code> &nbsp; <code>*</code> anything<br>"
                + "<b>Regex</b>: Java regular expression</html>");
        error.setForeground(new Color(0xD03838));

        debounce = new Timer(250, e -> update());
        debounce.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                debounce.restart();
            }
        });
        mode.addActionListener(e -> update());
        matchCase.addActionListener(e -> update());
    }

    private void fillHistory() {
        combo.removeAllItems();
        for (String h : history) {
            combo.addItem(h);
        }
    }

    void onChange(Runnable r) {
        onChange = r;
    }

    /** Adds the components to a horizontal panel. */
    void addTo(JPanel p, String label) {
        p.add(new JLabel(label));
        p.add(combo);
        p.add(Box.createHorizontalStrut(6));
        p.add(mode);
        p.add(Box.createHorizontalStrut(6));
        p.add(matchCase);
    }

    String text() {
        return field.getText();
    }

    void setText(String t) {
        field.setText(t == null ? "" : t);
    }

    /** The compiled query, or null if empty or invalid. */
    Query query() {
        if (debounce.isRunning()) {
            debounce.stop();
            update();
        }
        return query;
    }

    /** Remembers the current text in the history (call when the query is used). */
    void remember() {
        String t = field.getText();
        if (!t.isBlank()) {
            Settings.addToHistory(history, t, HISTORY);
        }
    }

    void bind(KeyStroke ks, String name, Runnable r) {
        field.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    void focus() {
        field.requestFocusInWindow();
        field.selectAll();
    }

    void setFound(boolean found) {
        field.putClientProperty("JComponent.outline", found ? null : "warning");
    }

    private void update() {
        String t = field.getText();
        Query q = null;
        error.setText("");
        error.setToolTipText(null);
        field.putClientProperty("JComponent.outline", null);
        if (!t.isEmpty()) {
            try {
                q = Query.compile(t, (Query.Mode) mode.getSelectedItem(), matchCase.isSelected());
            } catch (Query.SyntaxException ex) {
                error.setText(ex.getMessage());
                field.putClientProperty("JComponent.outline", "error");
            }
        }
        query = q;
        onChange.run();
    }
}
