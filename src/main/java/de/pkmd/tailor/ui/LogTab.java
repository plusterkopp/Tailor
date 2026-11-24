package de.pkmd.tailor.ui;

import de.pkmd.tailor.highlight.Highlighter;
import de.pkmd.tailor.highlight.Query;
import de.pkmd.tailor.io.LineIndex;
import de.pkmd.tailor.io.LineSearch;
import de.pkmd.tailor.io.LogFile;
import de.pkmd.tailor.settings.TabState;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One open log file: the main view, the lines matching the window's filter, searches with the
 * window's find query, and the glue to the polled file.
 */
public final class LogTab extends JPanel {

    private final MainFrame owner;
    private final LogFile file;
    private final LogView view;
    private final FilterResults results;
    private String profileName;

    private final AtomicBoolean updateQueued = new AtomicBoolean();
    private volatile boolean resetPending = true;
    private boolean firstContent = true;
    private TabState pendingRestore;
    private boolean unseen;
    private String message;

    private Future<?> task;
    private AtomicBoolean currentCancel;

    LogTab(MainFrame owner, LogFile file, ViewConfig cfg, Highlighter highlighter, String profileName,
           TabState state) {
        super(new BorderLayout());
        this.owner = owner;
        this.file = file;
        this.profileName = profileName;
        view = new LogView(cfg);
        view.setHighlighter(highlighter);
        view.setFollow(state != null && state.follow != null ? state.follow : owner.settings().followTail);
        view.setPlaceholder("Opening " + file.path() + " …");
        view.setZoomHandler(owner::zoom);
        view.addChangeListener(() -> owner.tabStatusChanged(this));

        results = new FilterResults(this, file, cfg, highlighter);
        add(new LogViewPane(view), BorderLayout.CENTER);

        if (state != null) {
            pendingRestore = state;
            if (state.bookmarks != null) {
                view.bookmarks().addAll(state.bookmarks);
            }
        }

        file.addListener((f, change) -> {
            if (change == LogFile.Change.RESET) {
                resetPending = true;
            }
            if (updateQueued.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(this::applyUpdate);
            }
        });
    }

    MainFrame owner() {
        return owner;
    }

    public LogFile file() {
        return file;
    }

    public LogView view() {
        return view;
    }

    /** Lines of this file matching the window's filter. */
    FilterResults results() {
        return results;
    }

    public String profileName() {
        return profileName;
    }

    void setProfile(String name, Highlighter h) {
        profileName = name;
        view.setHighlighter(h);
        results.setHighlighter(h);
    }

    void setConfig(ViewConfig cfg) {
        view.setConfig(cfg);
        results.setConfig(cfg);
    }

    /** True if the file changed while the tab was not selected. */
    boolean isUnseen() {
        return unseen;
    }

    void markSeen() {
        unseen = false;
    }

    /** Transient status (search progress, "not found", ...), or null. */
    String message() {
        return message;
    }

    private void setMessage(String m) {
        message = m;
        owner.tabStatusChanged(this);
    }

    void setCharset(Charset cs) {
        file.setCharset(cs);
        owner.poller().pollNow(file);
    }

    void reload() {
        file.requestReset();
        owner.poller().pollNow(file);
    }

    void dispose() {
        cancelTask();
        results.dispose();
        owner.poller().remove(file);
        file.close();
    }

    // ------------------------------------------------------------------ state

    /** Current view state, for the session. */
    TabState captureState() {
        TabState s = new TabState();
        s.follow = view.isFollow();
        s.topLine = view.topLine();
        s.caretLine = view.caretLine();
        s.bookmarks = new ArrayList<>(view.bookmarks());
        return s;
    }

    /** Restores the position once the file has been indexed far enough. */
    private void restorePosition() {
        TabState s = pendingRestore;
        if (s == null || Boolean.TRUE.equals(s.follow)) {
            pendingRestore = null;
            return;
        }
        long n = view.lineCount();
        long target = Math.max(s.topLine, s.caretLine);
        if (target >= n && file.state() == LogFile.State.INDEXING) {
            return; // not there yet
        }
        pendingRestore = null;
        if (s.topLine >= 0 && s.topLine < n) {
            view.scrollToLine(s.topLine);
        }
        if (s.caretLine >= 0 && s.caretLine < n) {
            view.goToLine(s.caretLine, false);
        }
    }

    // ------------------------------------------------------------------ file updates

    private void applyUpdate() {
        updateQueued.set(false);
        if (file.isClosed()) {
            return;
        }
        boolean reset = resetPending;
        resetPending = false;
        long before = view.lineCount();
        LineIndex idx = file.index();
        boolean keepBookmarks = firstContent && pendingRestore != null;
        var marks = keepBookmarks ? new ArrayList<>(view.bookmarks()) : null;
        view.setSource(LineSource.of(file, idx), reset);
        if (marks != null) {
            view.bookmarks().addAll(marks.stream().filter(l -> l < idx.lineCount() || idx.lineCount() == 0)
                    .toList());
        }
        if (idx.lineCount() > 0) {
            firstContent = false;
        }
        view.setPlaceholder(switch (file.state()) {
            case OPENING -> "Opening …";
            case MISSING -> "File not found – waiting for it to appear: " + file.path();
            case ERROR -> "Cannot read file: " + file.error();
            default -> "(empty file)";
        });
        if (pendingRestore != null && idx.lineCount() > 0) {
            restorePosition();
        }
        boolean grew = reset || idx.lineCount() != before;
        if (grew && !owner.isSelected(this)) {
            unseen = true;
        }
        owner.tabUpdated(this, grew);
    }

    // ------------------------------------------------------------------ find

    /** Stops a running search and clears its message. */
    void clearSearch() {
        cancelTask();
        setMessage(null);
    }

    /**
     * Finds the next or previous match of the window's find query, starting at the current line.
     *
     * @param label  the query text, for messages
     * @param result receives whether a match was found
     */
    void find(Query q, String label, boolean forward, Consumer<Boolean> result) {
        Pattern hp = q.highlightPattern();
        // further matches in the current line first
        long caret = view.caretLine();
        LogView.Pos selStart = view.selectionStart();
        LogView.Pos selEnd = view.selectionEnd();
        if (hp != null && caret >= 0 && selStart != null && selStart.line() == caret && selEnd.line() == caret) {
            String text = view.source().line(caret);
            if (q.matches(text)) {
                int[] r = forward ? nextRange(hp, text, selEnd.col()) : prevRange(hp, text, selStart.col());
                if (r != null) {
                    view.select(caret, r[0], r[1]);
                    setMessage(null);
                    result.accept(true);
                    return;
                }
            }
        }
        long from = caret >= 0 ? caret + (forward ? 1 : -1) : forward ? view.topLine() : view.topLine() - 1;
        if (!forward && from < 0) {
            from = view.lineCount() - 1;
        }
        search(from, forward, q::matches, "“" + label + "”", result, line -> {
            String text = view.source().line(line);
            int[] r = hp == null ? null : forward ? nextRange(hp, text, 0) : prevRange(hp, text, text.length() + 1);
            if (r != null) {
                view.select(line, r[0], r[1]);
            } else {
                view.goToLine(line, true);
            }
        });
    }

    private static int[] nextRange(Pattern p, String text, int from) {
        Matcher m = p.matcher(text);
        int pos = Math.min(from, text.length());
        while (pos <= text.length() && m.find(pos)) {
            if (m.end() > m.start()) {
                return new int[]{m.start(), m.end()};
            }
            pos = m.end() + 1;
        }
        return null;
    }

    private static int[] prevRange(Pattern p, String text, int before) {
        Matcher m = p.matcher(text);
        int[] last = null;
        while (m.find() && m.start() < before) {
            if (m.end() > m.start()) {
                last = new int[]{m.start(), m.end()};
            }
        }
        return last;
    }

    /** Jumps to the next line colored by a line rule of the highlight profile. */
    void nextHighlighted(boolean forward) {
        Highlighter h = view.highlighter();
        if (!h.hasLineRules()) {
            setMessage("The highlight profile has no line rules");
            return;
        }
        long caret = view.caretLine() >= 0 ? view.caretLine() : view.topLine();
        long from = caret + (forward ? 1 : -1);
        if (!forward && from < 0) {
            from = view.lineCount() - 1;
        }
        search(from, forward, h::matchesLineRule, "highlighted line", found -> { },
                line -> view.goToLine(line, true));
    }

    private void search(long from, boolean forward, Predicate<String> pred, String what, Consumer<Boolean> onResult,
                        LongConsumer onFound) {
        cancelTask();
        LineIndex idx = file.index();
        if (idx.lineCount() == 0) {
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        currentCancel = cancel;
        long total = idx.lineCount();
        setMessage("Searching " + what + " …");
        task = owner.background().submit(() -> {
            LineSearch.Result r;
            try {
                r = LineSearch.find(file, idx, from, forward, true, pred,
                        line -> SwingUtilities.invokeLater(() -> {
                            if (!cancel.get()) {
                                setMessage(String.format("Searching %s … %d%%", what,
                                        forward ? line * 100 / total : 100 - line * 100 / total));
                            }
                        }),
                        cancel::get);
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> setMessage("Search failed: " + e.getMessage()));
                return;
            }
            LineSearch.Result result = r;
            SwingUtilities.invokeLater(() -> {
                if (cancel.get()) {
                    return;
                }
                if (result.found()) {
                    onFound.accept(result.line());
                    setMessage(result.wrapped()
                            ? (forward ? "Reached the end, continued from the top"
                            : "Reached the top, continued from the end")
                            : null);
                    onResult.accept(true);
                } else {
                    setMessage("No " + what + " found");
                    onResult.accept(false);
                }
            });
        });
    }

    void cancelTask() {
        if (currentCancel != null) {
            currentCancel.set(true);
        }
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    // ------------------------------------------------------------------ status

    String statusText() {
        LineIndex idx = file.index();
        StringBuilder sb = new StringBuilder();
        switch (file.state()) {
            case INDEXING -> sb.append(String.format("Indexing %d%%", (int) (file.progress() * 100)));
            case MISSING -> sb.append("Missing");
            case ERROR -> sb.append("Error");
            case OPENING -> sb.append("Opening");
            case LIVE -> sb.append("Live");
        }
        sb.append("   ").append(Format.count(idx.lineCount())).append(" lines   ")
                .append(Format.bytes(file.fileSize())).append("   ")
                .append(file.encoding().displayName());
        if (view.caretLine() >= 0) {
            sb.append("   Ln ").append(Format.count(view.caretLine() + 1));
        }
        return sb.toString();
    }
}
