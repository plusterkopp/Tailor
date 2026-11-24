package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.Highlighter;
import de.pkmd.tailor.highlight.Query;
import de.pkmd.tailor.io.LineIndex;
import de.pkmd.tailor.io.LogFile;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The lines of one tab's file matching the window's filter query (or, inverted, the others), and
 * the view listing them. Keeps up with the file as it grows: only new lines are scanned. Moving
 * the current line in the list moves the tab's main view to the same line.
 */
final class FilterResults {

    private static final long PUBLISH_MS = 200;
    private static final String EMPTY_HINT = "Enter a filter to list matching lines here.";

    /** Matching lines of one scan state; arrays are only appended to, so snapshots stay valid. */
    private record Snapshot(long[] lines, int count, long partial) {
    }

    private final LogTab tab;
    private final LogFile file;
    private final LogView view;
    private final LogViewPane pane;
    private Runnable statusListener = () -> { };
    private String status = " ";

    // requested query; the scan catches up with it
    private Query query;
    private boolean invert;

    // scan state: touched by at most one background task at a time; the EDT starts tasks
    private long[] lines = new long[1024];
    private int count;
    private long scanned;
    private long scannedGeneration = -1;
    private Query scannedQuery;
    private boolean scannedInvert;
    private boolean scanning;
    private boolean again;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private int scanId;
    private long lastCaret = -1;

    FilterResults(LogTab tab, LogFile file, ViewConfig cfg, Highlighter highlighter) {
        this.tab = tab;
        this.file = file;
        view = new LogView(cfg);
        view.setHighlighter(highlighter);
        view.setBookmarksEnabled(false);
        view.setFollow(true);
        view.setPlaceholder(EMPTY_HINT);
        view.addChangeListener(this::viewChanged);
        pane = new LogViewPane(view);
    }

    LogViewPane pane() {
        return pane;
    }

    LogView view() {
        return view;
    }

    /** "1,234 matching lines" etc. */
    String status() {
        return status;
    }

    void onStatus(Runnable r) {
        statusListener = r;
    }

    void setConfig(ViewConfig cfg) {
        view.setConfig(cfg);
    }

    void setHighlighter(Highlighter h) {
        view.setHighlighter(h);
    }

    void dispose() {
        cancel.set(true);
        scanId++;
    }

    /** Applies the window's query; rescans only if it differs from what was scanned. */
    void setQuery(Query q, boolean inv) {
        query = q;
        invert = inv;
        if (q == null) {
            cancel.set(true);
            scanId++;
            scannedQuery = null;
            view.setSearchPattern(null);
            view.setSource(LineSource.EMPTY, true);
            view.setPlaceholder(EMPTY_HINT);
            setStatus(" ");
            return;
        }
        if (q != scannedQuery || inv != scannedInvert) {
            cancel.set(true); // a running scan for the old query stops and restarts via update()
        }
        update();
    }

    /** Scans what is new: the whole file after a query change or reset, else the appended lines. */
    void update() {
        if (query == null) {
            return;
        }
        if (scanning) {
            again = true;
            return;
        }
        boolean fresh = query != scannedQuery || invert != scannedInvert;
        LineIndex idx = file.index();
        long gen = file.generation();
        if (!fresh && gen == scannedGeneration && scanned >= idx.completeLines()
                && idx.lineCount() == idx.completeLines()) {
            return; // nothing new
        }
        scanning = true;
        again = false;
        cancel.set(false);
        Query q = query;
        boolean inv = invert;
        int id = ++scanId;
        if (fresh || gen != scannedGeneration) {
            view.setSearchPattern(!inv ? q.highlightPattern() : null);
            view.setSource(LineSource.EMPTY, true);
            view.setPlaceholder("Scanning …");
        }
        tab.owner().background().submit(() -> scan(id, q, inv, fresh, idx, gen));
    }

    private void scan(int id, Query q, boolean inv, boolean fresh, LineIndex idx, long gen) {
        if (fresh || gen != scannedGeneration) {
            lines = new long[1024];
            count = 0;
            scanned = 0;
            scannedGeneration = gen;
        }
        long complete = idx.completeLines();
        long total = idx.lineCount();
        long[] partial = {-1};
        long[] lastPublish = {System.currentTimeMillis()};
        String error = null;
        try {
            file.forEachLine(idx, scanned, total, (line, text) -> {
                boolean m = q.matches(text) != inv;
                if (line < complete) {
                    if (m) {
                        add(line);
                    }
                    scanned = line + 1;
                } else if (m) {
                    partial[0] = line;
                }
                long now = System.currentTimeMillis();
                if (now - lastPublish[0] > PUBLISH_MS) {
                    lastPublish[0] = now;
                    publish(id, new Snapshot(lines, count, -1), line, total);
                }
                return !cancel.get();
            }, cancel::get);
        } catch (IOException | RuntimeException e) {
            error = e.getMessage();
        }
        boolean cancelled = cancel.get();
        Snapshot snap = new Snapshot(lines, count, partial[0]);
        String err = error;
        SwingUtilities.invokeLater(() -> {
            scanning = false;
            if (!cancelled && id == scanId) {
                scannedQuery = q;
                scannedInvert = inv;
                show(snap, total, total, err);
                view.setPlaceholder("No matching lines.");
            } else {
                scannedQuery = null; // incomplete: start over
            }
            if (again || cancelled) {
                update();
            }
        });
    }

    private void add(long line) {
        if (count == lines.length) {
            if (count == Integer.MAX_VALUE - 8) {
                return;
            }
            lines = Arrays.copyOf(lines, (int) Math.min(Integer.MAX_VALUE - 8L, lines.length * 2L));
        }
        lines[count++] = line;
    }

    private void publish(int id, Snapshot snap, long at, long total) {
        SwingUtilities.invokeLater(() -> {
            if (id == scanId && !cancel.get()) {
                show(snap, at, total, null);
            }
        });
    }

    private void show(Snapshot snap, long at, long total, String error) {
        LineIndex idx = file.index();
        view.setSource(new LineSource() {
            @Override
            public long lineCount() {
                return snap.count() + (snap.partial() >= 0 ? 1 : 0);
            }

            @Override
            public String line(long n) {
                return file.line(idx, lineNumber(n));
            }

            @Override
            public long lineNumber(long n) {
                return n < snap.count() ? snap.lines()[(int) n] : snap.partial();
            }

            @Override
            public long truncatedLength(long n) {
                long l = lineNumber(n);
                LogFile.Block b = file.block(idx, LineIndex.blockOf(l));
                return b == null ? -1 : b.truncatedLength((int) (l - b.firstLine()));
            }
        }, false);
        long n = view.lineCount();
        String text = Format.count(n) + (invert ? " non-matching" : " matching") + " line" + (n == 1 ? "" : "s");
        if (at < total && total > 0) {
            text += String.format("   scanning %d%%", at * 100 / total);
        }
        if (error != null) {
            text += "   error: " + error;
        }
        setStatus(text);
    }

    private void setStatus(String s) {
        status = s;
        statusListener.run();
    }

    private void viewChanged() {
        long caret = view.caretLine();
        if (caret != lastCaret) {
            lastCaret = caret;
            if (caret >= 0 && caret < view.lineCount()) {
                tab.view().goToLine(view.source().lineNumber(caret), true);
            }
        }
    }
}
