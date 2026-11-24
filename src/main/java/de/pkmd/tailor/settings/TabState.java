package de.pkmd.tailor.settings;

import java.util.ArrayList;
import java.util.List;

/** Remembered state of a tab: follow mode, position and bookmarks. */
public final class TabState {

    public Boolean follow;
    public long topLine = -1;
    public long caretLine = -1;
    public List<Long> bookmarks = new ArrayList<>();

    public TabState copy() {
        TabState s = new TabState();
        s.follow = follow;
        s.topLine = topLine;
        s.caretLine = caretLine;
        s.bookmarks = bookmarks == null ? new ArrayList<>() : new ArrayList<>(bookmarks);
        return s;
    }
}
