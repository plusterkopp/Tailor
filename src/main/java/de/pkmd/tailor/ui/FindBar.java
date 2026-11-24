package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.Query;
import de.pkmd.tailor.settings.FindState;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.regex.Pattern;

/**
 * Search bar below the tabs. Its query belongs to the window, so it stays as it is when switching
 * tabs; next/previous search the selected tab, "highlight all" marks matches in every tab.
 */
final class FindBar extends JPanel {

    private final QueryBar query;
    private final JCheckBox highlightAll = new JCheckBox("Highlight all", true);

    FindBar(MainFrame owner) {
        query = new QueryBar(owner.settings().findHistory, "Find");
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        JButton prev = new JButton("▲ Previous");
        JButton next = new JButton("▼ Next");
        JButton close = new JButton("✕");
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setToolTipText("Close (Esc)");
        prev.setToolTipText("Find previous (Shift+F3, Shift+Enter)");
        next.setToolTipText("Find next (F3, Enter)");

        query.addTo(this, "Find: ");
        add(Box.createHorizontalStrut(6));
        add(highlightAll);
        add(Box.createHorizontalStrut(8));
        add(prev);
        add(next);
        add(Box.createHorizontalStrut(8));
        add(query.error);
        add(Box.createHorizontalGlue());
        add(close);

        query.onChange(owner::findPatternChanged);
        highlightAll.addActionListener(e -> owner.findPatternChanged());
        prev.addActionListener(e -> owner.findNext(false));
        next.addActionListener(e -> owner.findNext(true));
        close.addActionListener(e -> owner.closeFind());
        query.bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "findNext", () -> owner.findNext(true));
        query.bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "findPrev",
                () -> owner.findNext(false));
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                owner.closeFind();
            }
        });
    }

    QueryBar queryBar() {
        return query;
    }

    /** Shows the bar and focuses the query, optionally replacing it with {@code initial}. */
    void activate(String initial) {
        if (initial != null && !initial.isEmpty() && initial.indexOf('\n') < 0 && initial.indexOf('\r') < 0) {
            if (query.mode.getSelectedItem() == Query.Mode.REGEX) {
                query.setText(Pattern.quote(initial));
            } else {
                query.mode.setSelectedItem(Query.Mode.TEXT);
                query.setText(initial);
            }
        }
        setVisible(true);
        revalidate();
        query.focus();
    }

    /** Pattern to mark matches in the views, or null. */
    Pattern highlightPattern() {
        Query q = query.query();
        return isVisible() && highlightAll.isSelected() && q != null ? q.highlightPattern() : null;
    }

    void restore(FindState s) {
        query.mode.setSelectedItem(Query.Mode.parse(s.mode));
        query.matchCase.setSelected(s.matchCase);
        highlightAll.setSelected(s.highlightAll);
        query.setText(s.text);
    }

    void store(FindState s) {
        String t = query.text();
        s.text = t.isEmpty() ? null : t;
        s.mode = ((Query.Mode) query.mode.getSelectedItem()).name();
        s.matchCase = query.matchCase.isSelected();
        s.highlightAll = highlightAll.isSelected();
    }
}
