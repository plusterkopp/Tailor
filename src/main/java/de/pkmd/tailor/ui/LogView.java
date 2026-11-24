package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.Highlighter;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.font.FontRenderContext;
import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/**
 * Virtual text view for arbitrarily many lines.
 * <p>
 * Only visible lines are read, highlighted and laid out. The scroll position is a logical line
 * plus a row within it (for wrapped lines), so no total pixel height is needed. All rows have the
 * same height; logical lines are separated by {@link ViewConfig#lineGap()}, rows of a wrapped
 * line by {@link ViewConfig#wrapGap()}.
 */
public final class LogView extends JComponent {

    /** A text position: line and character index in the original (unexpanded) line text. */
    public record Pos(long line, int col) implements Comparable<Pos> {
        @Override
        public int compareTo(Pos o) {
            int c = Long.compare(line, o.line);
            return c != 0 ? c : Integer.compare(col, o.col);
        }
    }

    /** Scroll position: first visible row. */
    private record Top(long line, int row) implements Comparable<Top> {
        @Override
        public int compareTo(Top o) {
            int c = Long.compare(line, o.line);
            return c != 0 ? c : Integer.compare(row, o.row);
        }
    }

    private record Row(long line, int row, int y, LineLayout layout) {
    }

    private static final int GUTTER_PAD = 8;
    private static final int MARKER_WIDTH = 6;
    private static final int TEXT_PAD = 6;
    private static final int LAYOUT_CACHE = 4000;
    private static final long MAX_COPY_LINES = 2_000_000;

    private LineSource source = LineSource.EMPTY;
    private ViewConfig cfg;
    private Highlighter highlighter = Highlighter.NONE;
    private Pattern search;
    private FontSet fonts;
    private LineLayout.Params params;
    private final Map<Long, LineLayout> layouts = new LinkedHashMap<>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, LineLayout> e) {
            return size() > LAYOUT_CACHE;
        }
    };

    private long topLine;
    private int topRow;
    private float xOffset;
    private float maxLineWidth;
    private long caret = -1;
    private Pos anchor;
    private Pos lead;
    private final TreeSet<Long> bookmarks = new TreeSet<>();
    private boolean follow;
    private String placeholder;
    private boolean bookmarksEnabled = true;
    /** View size for which the follow position was last computed. */
    private int followWidth = -1;
    private int followHeight = -1;

    private final List<Row> rows = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private IntConsumer zoomHandler;
    private double wheelRemainder;
    private int clickCount;
    private int dragY;
    private final Timer autoscroll = new Timer(40, e -> autoscrollStep());

    public LogView(ViewConfig cfg) {
        this.cfg = cfg;
        setFocusable(true);
        setOpaque(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        setFocusTraversalKeysEnabled(false);
        installKeys();
        MouseHandler mh = new MouseHandler();
        addMouseListener(mh);
        addMouseMotionListener(mh);
        addMouseWheelListener(mh);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (follow) {
                    setTop(endTop(), false);
                } else {
                    setTop(clamp(new Top(topLine, topRow)), false);
                }
            }
        });
    }

    // ------------------------------------------------------------------ configuration

    public void addChangeListener(Runnable r) {
        listeners.add(r);
    }

    /** Receives +1/-1 for Ctrl+wheel zoom. */
    public void setZoomHandler(IntConsumer h) {
        zoomHandler = h;
    }

    public ViewConfig config() {
        return cfg;
    }

    public void setConfig(ViewConfig cfg) {
        Top keep = new Top(topLine, 0);
        this.cfg = cfg;
        fonts = null;
        invalidateLayouts();
        maxLineWidth = 0;
        setTop(follow ? endTop() : clamp(keep), false);
    }

    public void setHighlighter(Highlighter h) {
        highlighter = h;
        invalidateLayouts();
        repaint();
    }

    public Highlighter highlighter() {
        return highlighter;
    }

    /** Pattern whose matches are marked in all lines, or null. */
    public void setSearchPattern(Pattern p) {
        search = p;
        invalidateLayouts();
        repaint();
    }

    /** Message shown when there are no lines, e.g. "file not found". */
    public void setPlaceholder(String text) {
        placeholder = text;
        repaint();
    }

    /**
     * Replaces the displayed lines.
     *
     * @param reset true if line numbers changed meaning (file truncated or replaced)
     */
    public void setSource(LineSource src, boolean reset) {
        source = src;
        if (reset) {
            invalidateLayouts();
            maxLineWidth = 0;
            bookmarks.clear();
            anchor = lead = null;
            caret = -1;
            topLine = 0;
            topRow = 0;
            xOffset = 0;
        }
        if (caret >= src.lineCount()) {
            caret = src.lineCount() - 1;
        }
        setTop(follow ? endTop() : clamp(new Top(topLine, topRow)), false);
    }

    public LineSource source() {
        return source;
    }

    private void invalidateLayouts() {
        layouts.clear();
        params = null;
    }

    // ------------------------------------------------------------------ state accessors

    public long lineCount() {
        return source.lineCount();
    }

    public long topLine() {
        return topLine;
    }

    public long caretLine() {
        return caret;
    }

    public boolean isFollow() {
        return follow;
    }

    public void setFollow(boolean f) {
        if (follow != f) {
            follow = f;
            if (f) {
                setTop(endTop(), false);
            }
            fireChanged();
        }
    }

    /** Approximate number of lines that fit into the view. */
    public int pageLines() {
        int h = Math.max(1, getHeight());
        return Math.max(1, (h + gap()) / (fonts().rowHeight + gap()));
    }

    /** Lines (fully or partly) visible in the last paint. */
    public int visibleLineCount() {
        if (rows.isEmpty()) {
            return pageLines();
        }
        return (int) (rows.getLast().line() - rows.getFirst().line() + 1);
    }

    public float maxLineWidth() {
        return maxLineWidth;
    }

    public float textAreaWidth() {
        return Math.max(1, getWidth() - textX() - TEXT_PAD);
    }

    public float xOffset() {
        return xOffset;
    }

    public void setXOffset(float x) {
        float max = Math.max(0, maxLineWidth + TEXT_PAD * 4 - textAreaWidth());
        x = cfg.wrap() ? 0 : Math.max(0, Math.min(x, max));
        if (x != xOffset) {
            xOffset = x;
            repaint();
            fireChanged();
        }
    }

    public NavigableSet<Long> bookmarks() {
        return bookmarks;
    }

    /** Filtered views have no bookmarks of their own; clicks on the gutter then do nothing. */
    public void setBookmarksEnabled(boolean on) {
        bookmarksEnabled = on;
    }

    // ------------------------------------------------------------------ navigation

    /** Scrolls by visual rows; positive is down. */
    public void scrollRows(int delta) {
        scrollRowsInternal(delta);
    }

    private void scrollRowsInternal(int delta) {
        long n = source.lineCount();
        if (n == 0 || delta == 0) {
            return;
        }
        Top t = new Top(topLine, topRow);
        if (!cfg.wrap()) {
            t = new Top(Math.max(0, Math.min(n - 1, topLine + delta)), 0);
        } else if (delta > 0) {
            Top end = endTop();
            long line = topLine;
            int row = topRow;
            while (delta-- > 0 && new Top(line, row).compareTo(end) < 0) {
                if (row + 1 < rowsOf(line)) {
                    row++;
                } else {
                    line++;
                    row = 0;
                }
            }
            t = new Top(line, row);
        } else {
            long line = topLine;
            int row = topRow;
            while (delta++ < 0 && (line > 0 || row > 0)) {
                if (row > 0) {
                    row--;
                } else {
                    line--;
                    row = rowsOf(line) - 1;
                }
            }
            t = new Top(line, row);
        }
        setTop(clamp(t), true);
    }

    /** Puts {@code line} at the top of the view (used by the scrollbar). */
    public void scrollToLine(long line) {
        setTop(clamp(new Top(line, 0)), true);
    }

    public void scrollToEnd() {
        setTop(endTop(), true);
    }

    public void pageDown() {
        long last = lastFullyVisibleLine();
        long target = last > topLine ? last : topLine + 1;
        long delta = target - topLine;
        setTop(clamp(new Top(target, 0)), true);
        moveCaretTo(caret < 0 ? topLine : Math.min(source.lineCount() - 1, caret + delta), false);
    }

    public void pageUp() {
        long oldTop = topLine;
        Top t = topAbove(topLine, Math.max(fonts().rowHeight, getHeight() - fonts().rowHeight - gap()));
        setTop(clamp(t), true);
        long delta = oldTop - topLine;
        moveCaretTo(caret < 0 ? topLine : Math.max(0, caret - Math.max(1, delta)), false);
    }

    public void goToStart() {
        setTop(new Top(0, 0), true);
        moveCaretTo(0, false);
    }

    public void goToEnd() {
        moveCaretTo(source.lineCount() - 1, false);
        setTop(endTop(), true);
    }

    /** Moves the caret to {@code line} and scrolls so it is visible (centered if far away). */
    public void goToLine(long line, boolean center) {
        long n = source.lineCount();
        if (n == 0) {
            return;
        }
        line = Math.max(0, Math.min(n - 1, line));
        caret = line;
        anchor = lead = null;
        if (center && !isFullyVisible(line)) {
            setTop(clamp(topAbove(line, getHeight() / 3)), true);
        } else {
            ensureVisible(line);
        }
        repaint();
        fireChanged();
    }

    public void moveCaret(int delta) {
        long n = source.lineCount();
        if (n == 0) {
            return;
        }
        long from = caret >= 0 && caret < n ? caret : topLine;
        moveCaretTo(Math.max(0, Math.min(n - 1, from + delta)), true);
    }

    private void moveCaretTo(long line, boolean scroll) {
        if (line < 0) {
            return;
        }
        caret = line;
        anchor = lead = null;
        if (scroll) {
            ensureVisible(line);
        }
        repaint();
        fireChanged();
    }

    private void ensureVisible(long line) {
        if (line < topLine || (line == topLine && topRow > 0)) {
            setTop(new Top(line, 0), true);
        } else if (!isFullyVisible(line)) {
            Top t = topAbove(line + 1, getHeight() + gap());
            if (t.compareTo(new Top(line, 0)) > 0) {
                t = new Top(line, 0); // taller than the view: show its start
            }
            setTop(clamp(t), true);
        }
    }

    private boolean isFullyVisible(long line) {
        if (line < topLine || (line == topLine && topRow > 0)) {
            return false;
        }
        return line <= lastFullyVisibleLine();
    }

    private long lastFullyVisibleLine() {
        int h = getHeight();
        int y = 0;
        long n = source.lineCount();
        long line = topLine;
        int row = topRow;
        long last = topLine - 1;
        int rh = fonts().rowHeight;
        while (line < n) {
            int rows = rowsOf(line) - row;
            int lh = rows * rh + (rows - 1) * cfg.wrapGap();
            if (y + lh > h) {
                break;
            }
            last = line;
            y += lh + gap();
            line++;
            row = 0;
        }
        return Math.max(last, topLine);
    }

    public void toggleBookmark(long line) {
        if (!bookmarksEnabled || line < 0 || line >= source.lineCount()) {
            return;
        }
        if (!bookmarks.remove(line)) {
            bookmarks.add(line);
        }
        repaint();
        fireChanged();
    }

    public void toggleBookmark() {
        toggleBookmark(caret >= 0 ? caret : topLine);
    }

    public void nextBookmark(boolean forward) {
        long from = caret >= 0 ? caret : topLine;
        Long b = forward ? bookmarks.higher(from) : bookmarks.lower(from);
        if (b == null && !bookmarks.isEmpty()) {
            b = forward ? bookmarks.first() : bookmarks.last();
        }
        if (b != null) {
            goToLine(b, true);
        }
    }

    // ------------------------------------------------------------------ selection

    public boolean hasSelection() {
        return anchor != null && lead != null && !anchor.equals(lead);
    }

    /** Start of the selection, or null if nothing is selected. */
    public Pos selectionStart() {
        return hasSelection() ? selStart() : null;
    }

    /** End of the selection, or null if nothing is selected. */
    public Pos selectionEnd() {
        return hasSelection() ? selEnd() : null;
    }

    public void selectAll() {
        long n = source.lineCount();
        if (n == 0) {
            return;
        }
        anchor = new Pos(0, 0);
        lead = new Pos(n - 1, source.line(n - 1).length());
        repaint();
    }

    /** Selects a range and scrolls it into view, e.g. a search hit. */
    public void select(long line, int start, int end) {
        caret = line;
        anchor = new Pos(line, start);
        lead = new Pos(line, end);
        if (!isFullyVisible(line)) {
            setTop(clamp(topAbove(line, getHeight() / 3)), true);
        }
        scrollColumnIntoView(line, start, end);
        repaint();
        fireChanged();
    }

    private void scrollColumnIntoView(long line, int start, int end) {
        if (cfg.wrap()) {
            return;
        }
        LineLayout l = layout(line);
        TextLayout tl = l.rows[0];
        if (tl == null) {
            return;
        }
        int chars = tl.getCharacterCount();
        float x0 = tl.getCaretInfo(TextHitInfo.leading(Math.min(chars, l.toDisplay(start))))[0];
        float x1 = tl.getCaretInfo(TextHitInfo.leading(Math.min(chars, l.toDisplay(end))))[0];
        float w = textAreaWidth();
        if (x0 < xOffset || x1 > xOffset + w) {
            setXOffset(Math.max(0, x0 - w / 4));
        }
    }

    /**
     * Selected text, or the caret line if nothing is selected; null if there is nothing.
     * Throws {@link IllegalStateException} if the selection is too large.
     */
    public String selectedText() {
        if (!hasSelection()) {
            return caret >= 0 && caret < source.lineCount() ? source.line(caret) : null;
        }
        Pos s = selStart();
        Pos e = selEnd();
        if (e.line() - s.line() > MAX_COPY_LINES) {
            throw new IllegalStateException("Selection too large to copy (" + Format.count(e.line() - s.line())
                    + " lines, limit " + Format.count(MAX_COPY_LINES) + ")");
        }
        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        for (long l = s.line(); l <= e.line(); l++) {
            String t = source.line(l);
            int a = l == s.line() ? Math.min(s.col(), t.length()) : 0;
            int b = l == e.line() ? Math.min(e.col(), t.length()) : t.length();
            sb.append(t, a, Math.max(a, b));
            if (l < e.line()) {
                sb.append(nl);
            }
        }
        return sb.toString();
    }

    private Pos selStart() {
        return anchor.compareTo(lead) <= 0 ? anchor : lead;
    }

    private Pos selEnd() {
        return anchor.compareTo(lead) <= 0 ? lead : anchor;
    }

    // ------------------------------------------------------------------ geometry

    private int gap() {
        return cfg.lineGap();
    }

    private FontSet fonts() {
        if (fonts == null) {
            fonts = new FontSet(cfg.font(), defaultFrc());
        }
        return fonts;
    }

    private FontRenderContext defaultFrc() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        AffineTransform tx = gc != null ? gc.getDefaultTransform() : new AffineTransform();
        tx = new AffineTransform(tx.getScaleX(), tx.getShearY(), tx.getShearX(), tx.getScaleY(), 0, 0);
        return new FontRenderContext(tx, textAntialiasHint(), RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    }

    private static Object textAntialiasHint() {
        Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (hints instanceof Map<?, ?> m) {
            Object aa = m.get(RenderingHints.KEY_TEXT_ANTIALIASING);
            if (aa != null && aa != RenderingHints.VALUE_TEXT_ANTIALIAS_OFF) {
                return aa;
            }
        }
        return RenderingHints.VALUE_TEXT_ANTIALIAS_ON;
    }

    private static boolean sameFrc(FontRenderContext a, FontRenderContext b) {
        AffineTransform ta = a.getTransform();
        AffineTransform tb = b.getTransform();
        return ta.getScaleX() == tb.getScaleX() && ta.getScaleY() == tb.getScaleY()
                && a.getAntiAliasingHint().equals(b.getAntiAliasingHint())
                && a.getFractionalMetricsHint().equals(b.getFractionalMetricsHint());
    }

    private int gutterWidth() {
        if (!cfg.lineNumbers()) {
            return MARKER_WIDTH + 4;
        }
        long n = source.lineCount();
        long maxNumber = n == 0 ? 1 : source.lineNumber(n - 1) + 1;
        int digits = Math.max(4, Long.toString(Math.max(1, maxNumber)).length());
        return (int) Math.ceil(digits * fonts().digitWidth) + 2 * GUTTER_PAD;
    }

    private int textX() {
        return gutterWidth() + TEXT_PAD;
    }

    private LineLayout.Params params() {
        float wrapWidth = cfg.wrap() ? textAreaWidth() : 0;
        if (params == null || params.wrapWidth() != wrapWidth || params.fonts() != fonts()) {
            layouts.clear();
            params = new LineLayout.Params(cfg, fonts(), highlighter, search, wrapWidth);
        }
        return params;
    }

    private LineLayout layout(long line) {
        LineLayout.Params p = params();
        String text = source.line(line);
        LineLayout l = layouts.get(line);
        if (l != null && l.text.equals(text)) {
            return l;
        }
        l = LineLayout.build(text, source.truncatedLength(line), p);
        layouts.put(line, l);
        if (l.width > maxLineWidth) {
            maxLineWidth = l.width;
        }
        return l;
    }

    private int rowsOf(long line) {
        return cfg.wrap() ? layout(line).rowCount() : 1;
    }

    /** Pixel height of a line including the gap below it. */
    private int lineHeight(int rowCount) {
        return rowCount * fonts().rowHeight + (rowCount - 1) * cfg.wrapGap() + gap();
    }

    /**
     * Scroll position that puts the top of {@code line} {@code pixels} below the top of the view,
     * or as close as possible.
     */
    private Top topAbove(long line, int pixels) {
        int rh = fonts().rowHeight;
        if (!cfg.wrap()) {
            long k = Math.max(0, pixels / (rh + gap()));
            return new Top(Math.max(0, line - k), 0);
        }
        long l = line;
        int avail = pixels;
        while (l > 0) {
            int rc = rowsOf(l - 1);
            int h = lineHeight(rc);
            if (h > avail) {
                int k = (avail - gap() + cfg.wrapGap()) / (rh + cfg.wrapGap());
                return k > 0 ? new Top(l - 1, rc - k) : new Top(l, 0);
            }
            avail -= h;
            l--;
        }
        return new Top(0, 0);
    }

    /** Scroll position showing the end of the file at the bottom of the view. */
    private Top endTop() {
        long n = source.lineCount();
        if (n == 0) {
            return new Top(0, 0);
        }
        Top t = topAbove(n, Math.max(fonts().rowHeight, getHeight()) + gap());
        if (t.line() >= n) {
            return new Top(n - 1, Math.max(0, rowsOf(n - 1) - 1));
        }
        return t;
    }

    private Top clamp(Top t) {
        long n = source.lineCount();
        if (n == 0 || t.line() < 0) {
            return new Top(0, 0);
        }
        Top end = endTop();
        if (t.compareTo(end) > 0) {
            return end;
        }
        if (cfg.wrap() && t.row() > 0 && t.row() >= rowsOf(t.line())) {
            return new Top(t.line(), rowsOf(t.line()) - 1);
        }
        return cfg.wrap() ? t : new Top(t.line(), 0);
    }

    private void setTop(Top t, boolean user) {
        boolean changed = t.line() != topLine || t.row() != topRow;
        topLine = t.line();
        topRow = t.row();
        if (user && source.lineCount() > 0) {
            boolean atEnd = t.compareTo(endTop()) >= 0;
            if (atEnd != follow) {
                follow = atEnd;
                changed = true;
            }
        }
        if (changed) {
            repaint();
        }
        fireChanged();
    }

    private void fireChanged() {
        for (Runnable r : listeners) {
            r.run();
        }
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof Map<?, ?> m) {
                g.addRenderingHints(m);
            } else {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            FontRenderContext frc = g.getFontRenderContext();
            if (fonts != null && !sameFrc(frc, fonts.frc)) {
                fonts = new FontSet(cfg.font(), frc);
            }
            if (follow && (getWidth() != followWidth || getHeight() != followHeight)) {
                // the end position depends on the size, which may have changed while hidden
                followWidth = getWidth();
                followHeight = getHeight();
                Top end = endTop();
                if (end.line() != topLine || end.row() != topRow) {
                    topLine = end.line();
                    topRow = end.row();
                    SwingUtilities.invokeLater(this::fireChanged);
                }
            }
            paintView(g);
        } finally {
            g.dispose();
        }
    }

    private void paintView(Graphics2D g) {
        int w = getWidth();
        int h = getHeight();
        FontSet fs = fonts();
        int rh = fs.rowHeight;
        int gutter = gutterWidth();
        int tx = gutter + TEXT_PAD;

        g.setColor(cfg.background());
        g.fillRect(0, 0, w, h);
        g.setColor(cfg.gutterBackground());
        g.fillRect(0, 0, gutter, h);

        rows.clear();
        long n = source.lineCount();
        if (n == 0) {
            if (placeholder != null) {
                g.setFont(cfg.font().deriveFont(Font.ITALIC));
                g.setColor(cfg.dimmed());
                g.drawString(placeholder, tx, fs.baseline + 4);
            }
            return;
        }

        Pos s = hasSelection() ? selStart() : null;
        Pos e = hasSelection() ? selEnd() : null;
        Shape clip = g.getClip();
        int y = 0;
        long line = topLine;
        int row = topRow;
        while (y < h && line < n) {
            LineLayout l = layout(line);
            int rc = l.rowCount();
            for (int r = Math.min(row, rc - 1); r < rc && y < h; r++) {
                boolean lastRow = r == rc - 1;
                int rowH = rh + (lastRow ? 0 : cfg.wrapGap());
                rows.add(new Row(line, r, y, l));

                // text area: backgrounds, selection, text
                g.clipRect(gutter, 0, w - gutter, h);
                if (l.lineBackground != null) {
                    g.setColor(l.lineBackground);
                    g.fillRect(gutter, y, w - gutter, rowH);
                }
                if (line == caret) {
                    g.setColor(cfg.currentLine());
                    g.fillRect(gutter, y, w - gutter, rowH);
                }
                TextLayout tl = l.rows[r];
                float x = tx - xOffset;
                int rs = l.rowStart[r];
                int re = l.rowEnd(r);
                if (tl != null) {
                    for (LineLayout.Background b : l.backgrounds) {
                        int a = Math.max(b.start(), rs);
                        int z = Math.min(b.end(), re);
                        if (a < z) {
                            g.setColor(b.color());
                            fillRange(g, tl, a - rs, z - rs, x, y, rh);
                        }
                    }
                }
                if (s != null && line >= s.line() && line <= e.line()) {
                    g.setColor(cfg.selection());
                    int a = line == s.line() ? l.toDisplay(s.col()) : 0;
                    int z = line == e.line() ? l.toDisplay(e.col()) : l.contentLength;
                    int ca = Math.max(a, rs);
                    int cz = Math.min(z, re);
                    if (tl != null && ca < cz) {
                        fillRange(g, tl, ca - rs, cz - rs, x, y, rh);
                    }
                    if (lastRow && line < e.line()) {
                        float end = tl == null ? 0 : tl.getAdvance();
                        g.fillRect(Math.round(x + end), y, Math.round(fs.digitWidth), rh);
                    }
                }
                if (tl != null) {
                    tl.draw(g, x, y + fs.baseline);
                }
                g.setClip(clip);

                // gutter
                if (r == 0 || r == Math.min(row, rc - 1)) {
                    paintGutter(g, line, r == 0, y, gutter, fs);
                }
                y += rowH;
            }
            y += gap();
            line++;
            row = 0;
        }
    }

    private void paintGutter(Graphics2D g, long line, boolean firstRow, int y, int gutter, FontSet fs) {
        boolean marked = bookmarks.contains(line);
        if (marked) {
            g.setColor(cfg.bookmark());
            if (cfg.lineNumbers()) {
                g.fillRoundRect(2, y, gutter - 4, fs.rowHeight, 6, 6);
            } else {
                g.fillRoundRect(2, y + 2, MARKER_WIDTH, fs.rowHeight - 4, 4, 4);
            }
        }
        if (cfg.lineNumbers() && firstRow) {
            String num = Long.toString(source.lineNumber(line) + 1);
            Font f = line == caret ? fs.font(FontSet.PRIMARY, Font.BOLD) : fs.base;
            g.setFont(f);
            g.setColor(marked ? Color.WHITE : line == caret ? cfg.foreground() : cfg.gutterForeground());
            float numW = (float) f.getStringBounds(num, g.getFontRenderContext()).getWidth();
            g.drawString(num, gutter - GUTTER_PAD - numW, y + fs.baseline);
        }
    }

    /** Fills the logical range {@code [a, z)} of a row over the full row height. */
    private static void fillRange(Graphics2D g, TextLayout tl, int a, int z, float x, int y, int rh) {
        Shape shape = tl.getLogicalHighlightShape(a, z);
        Rectangle2D b = shape.getBounds2D();
        if (b.getHeight() <= 0) {
            return;
        }
        AffineTransform at = AffineTransform.getTranslateInstance(x, y);
        at.scale(1, rh / b.getHeight());
        at.translate(0, -b.getMinY());
        g.fill(at.createTransformedShape(shape));
    }

    // ------------------------------------------------------------------ hit testing

    private Row rowAt(int y) {
        if (rows.isEmpty()) {
            return null;
        }
        Row hit = rows.getFirst();
        for (Row r : rows) {
            if (r.y() <= y) {
                hit = r;
            } else {
                break;
            }
        }
        return hit;
    }

    private Pos posAt(int x, int y) {
        Row r = rowAt(y);
        if (r == null) {
            return null;
        }
        if (y >= getHeight() && r == rows.getLast() && r.row() == r.layout().rowCount() - 1) {
            return new Pos(r.line(), r.layout().text.length());
        }
        float lx = x - textX() + xOffset;
        int disp = r.layout().hitTest(r.row(), lx);
        return new Pos(r.line(), r.layout().toOriginal(disp));
    }

    // ------------------------------------------------------------------ input

    private void installKeys() {
        InputMap im = getInputMap(WHEN_FOCUSED);
        ActionMap am = getActionMap();
        bind(im, am, "up", KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), () -> moveCaret(-1));
        bind(im, am, "down", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), () -> moveCaret(1));
        bind(im, am, "scrollUp", KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK),
                () -> scrollRows(-1));
        bind(im, am, "scrollDown", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK),
                () -> scrollRows(1));
        bind(im, am, "pageUp", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), this::pageUp);
        bind(im, am, "pageDown", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), this::pageDown);
        bind(im, am, "home", KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), this::goToStart);
        bind(im, am, "end", KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), this::goToEnd);
        bind(im, am, "left", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),
                () -> setXOffset(xOffset - 4 * fonts().digitWidth));
        bind(im, am, "right", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0),
                () -> setXOffset(xOffset + 4 * fonts().digitWidth));
        bind(im, am, "lineStart", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK),
                () -> setXOffset(0));
        bind(im, am, "lineEnd", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK),
                () -> setXOffset(Float.MAX_VALUE));
        bind(im, am, "escape", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), () -> {
            anchor = lead = null;
            repaint();
        });
    }

    private static void bind(InputMap im, ActionMap am, String name, KeyStroke ks, Runnable r) {
        im.put(ks, name);
        am.put(name, new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private void autoscrollStep() {
        if (dragY < 0) {
            scrollRows(Math.min(-1, dragY / Math.max(1, fonts().rowHeight)));
        } else if (dragY > getHeight()) {
            scrollRows(Math.max(1, (dragY - getHeight()) / Math.max(1, fonts().rowHeight)));
        } else {
            autoscroll.stop();
            return;
        }
        paintImmediately(0, 0, getWidth(), getHeight());
        Pos p = posAt(Math.max(textX(), 0), Math.max(0, Math.min(getHeight(), dragY)));
        if (p != null && anchor != null) {
            lead = p;
            repaint();
        }
    }

    private final class MouseHandler extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            if (!SwingUtilities.isLeftMouseButton(e)) {
                return;
            }
            Row r = rowAt(e.getY());
            if (r == null) {
                return;
            }
            if (e.getX() < gutterWidth()) {
                toggleBookmark(r.line());
                return;
            }
            Pos p = posAt(e.getX(), e.getY());
            clickCount = e.getClickCount();
            if (clickCount == 2) {
                selectWord(p);
            } else if (clickCount >= 3) {
                anchor = new Pos(p.line(), 0);
                lead = new Pos(p.line(), r.layout().text.length());
            } else if (e.isShiftDown() && anchor != null) {
                lead = p;
            } else {
                anchor = lead = p;
            }
            caret = p.line();
            repaint();
            fireChanged();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e) || anchor == null || clickCount > 1) {
                return;
            }
            dragY = e.getY();
            if (dragY < 0 || dragY > getHeight()) {
                if (!autoscroll.isRunning()) {
                    autoscroll.start();
                }
                return;
            }
            autoscroll.stop();
            Pos p = posAt(e.getX(), e.getY());
            if (p != null) {
                lead = p;
                caret = p.line();
                repaint();
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            autoscroll.stop();
            fireChanged();
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            if (e.isControlDown() || e.isMetaDown()) {
                if (zoomHandler != null && e.getWheelRotation() != 0) {
                    zoomHandler.accept(e.getWheelRotation() < 0 ? 1 : -1);
                }
                return;
            }
            double units = e.getPreciseWheelRotation() * Math.max(1, e.getScrollAmount()) + wheelRemainder;
            int whole = (int) units;
            wheelRemainder = units - whole;
            if (whole == 0) {
                return;
            }
            if (e.isShiftDown()) {
                setXOffset(xOffset + whole * 2 * fonts().digitWidth);
            } else {
                scrollRows(whole);
            }
        }
    }

    private void selectWord(Pos p) {
        String t = source.line(p.line());
        int a = Math.min(p.col(), t.length());
        int b = a;
        while (a > 0 && isWordChar(t.charAt(a - 1))) {
            a--;
        }
        while (b < t.length() && isWordChar(t.charAt(b))) {
            b++;
        }
        if (a == b && b < t.length()) {
            b++;
        }
        anchor = new Pos(p.line(), a);
        lead = new Pos(p.line(), b);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '$';
    }
}
