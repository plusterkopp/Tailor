package de.pkmd.tailor.ui;

import javax.swing.JPanel;
import javax.swing.JScrollBar;
import java.awt.BorderLayout;

/**
 * A {@link LogView} with scrollbars. The vertical scrollbar counts logical lines (scaled down if
 * there are more than a billion), the horizontal one pixels of the widest line seen so far.
 */
final class LogViewPane extends JPanel {

    private static final long SCROLL_RANGE = 1_000_000_000L;

    private final LogView view;
    private final JScrollBar vbar = new JScrollBar(JScrollBar.VERTICAL);
    private final JScrollBar hbar = new JScrollBar(JScrollBar.HORIZONTAL);
    private boolean adjusting;
    private long scrollScale = 1;

    LogViewPane(LogView view) {
        super(new BorderLayout());
        this.view = view;
        add(view, BorderLayout.CENTER);
        add(vbar, BorderLayout.EAST);
        add(hbar, BorderLayout.SOUTH);
        hbar.setVisible(false);
        vbar.setUnitIncrement(1);
        vbar.addAdjustmentListener(e -> {
            if (adjusting) {
                return;
            }
            int v = vbar.getValue();
            if (v + vbar.getVisibleAmount() >= vbar.getMaximum()) {
                view.scrollToEnd();
            } else {
                view.scrollToLine(v * scrollScale);
            }
        });
        hbar.addAdjustmentListener(e -> {
            if (!adjusting) {
                view.setXOffset(hbar.getValue());
            }
        });
        view.addChangeListener(this::sync);
    }

    LogView view() {
        return view;
    }

    void sync() {
        adjusting = true;
        try {
            long n = view.lineCount();
            scrollScale = n > SCROLL_RANGE ? n / SCROLL_RANGE + 1 : 1;
            int extent = (int) Math.max(1, view.visibleLineCount() / scrollScale);
            int max = (int) (n / scrollScale);
            int value = (int) (view.topLine() / scrollScale);
            if (view.isFollow()) {
                value = Math.max(0, max - extent);
            }
            vbar.setValues(value, extent, 0, Math.max(max, value + extent));
            vbar.setBlockIncrement(Math.max(1, extent - 1));

            int width = (int) Math.ceil(view.maxLineWidth()) + 24;
            int visible = (int) view.textAreaWidth();
            boolean needH = !view.config().wrap() && width > visible;
            if (hbar.isVisible() != needH) {
                hbar.setVisible(needH);
                revalidate();
            }
            if (needH) {
                hbar.setValues((int) view.xOffset(), visible, 0, width);
                hbar.setUnitIncrement(16);
                hbar.setBlockIncrement(Math.max(16, visible - 32));
            }
        } finally {
            adjusting = false;
        }
    }
}
