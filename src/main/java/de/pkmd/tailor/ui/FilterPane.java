package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.Query;
import de.pkmd.tailor.settings.FilterState;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * The filter pane below the tabs. Its query belongs to the window, so it stays as it is when
 * switching tabs; the list below shows the matching lines of the selected tab's file.
 */
final class FilterPane extends JPanel {

    private final MainFrame owner;
    private final QueryBar query;
    private final JCheckBox invert = new JCheckBox("Invert");
    private final JLabel status = new JLabel(" ");
    private final JLabel noTab = new JLabel("No file open", SwingConstants.CENTER);
    private LogTab shown;
    private Component center;

    FilterPane(MainFrame owner) {
        super(new BorderLayout());
        this.owner = owner;
        query = new QueryBar(owner.settings().filterHistory, "Filter: show only lines matching …");
        noTab.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        query.addTo(bar, "Filter: ");
        bar.add(Box.createHorizontalStrut(6));
        bar.add(invert);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(query.error);
        bar.add(status);
        bar.add(Box.createHorizontalGlue());
        JButton close = new JButton("✕");
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setToolTipText("Hide filter pane (Esc)");
        close.addActionListener(e -> owner.setFilterVisible(false));
        bar.add(close);
        invert.setToolTipText("Show the lines that do not match");
        add(bar, BorderLayout.NORTH);
        setCenter(noTab);

        query.onChange(this::apply);
        invert.addActionListener(e -> apply());
        query.bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "remember", () -> {
            query.remember();
            if (shown != null) {
                shown.results().view().requestFocusInWindow();
            }
        });
        query.bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "toList", () -> {
            if (shown != null) {
                shown.results().view().requestFocusInWindow();
            }
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hide");
        getActionMap().put("hide", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                owner.setFilterVisible(false);
            }
        });
    }

    /** Shows the results of {@code tab} (null: none), bringing them up to date with the query. */
    void show(LogTab tab) {
        if (shown != null && shown != tab) {
            shown.results().onStatus(() -> { });
        }
        shown = tab;
        if (tab == null) {
            setCenter(noTab);
            status.setText(" ");
            return;
        }
        FilterResults r = tab.results();
        r.onStatus(() -> status.setText(r.status()));
        setCenter(r.pane());
        r.setQuery(query.query(), invert.isSelected());
        status.setText(r.status());
    }

    /** The selected tab's file grew. */
    void tabGrew(LogTab tab) {
        if (tab == shown) {
            tab.results().update();
        }
    }

    void focusQuery() {
        query.focus();
    }

    boolean hasFocus(Component c) {
        return c != null && isAncestorOf(c);
    }

    private void apply() {
        if (shown != null) {
            shown.results().setQuery(query.query(), invert.isSelected());
        }
    }

    private void setCenter(JComponent c) {
        if (center == c) {
            return;
        }
        if (center != null) {
            remove(center);
        }
        center = c;
        add(c, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------------ state

    void restore(FilterState s) {
        query.mode.setSelectedItem(Query.Mode.parse(s.mode));
        query.matchCase.setSelected(s.matchCase);
        invert.setSelected(s.invert);
        query.setText(s.text);
        query.query(); // compile now rather than after the debounce delay
    }

    void store(FilterState s) {
        String t = query.text();
        s.text = t.isEmpty() ? null : t;
        s.mode = ((Query.Mode) query.mode.getSelectedItem()).name();
        s.matchCase = query.matchCase.isSelected();
        s.invert = invert.isSelected();
    }
}
