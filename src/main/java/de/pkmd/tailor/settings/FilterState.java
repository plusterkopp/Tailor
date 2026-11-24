package de.pkmd.tailor.settings;

/** The filter pane: one query for the whole window, applied to whichever tab is selected. */
public final class FilterState {

    public String text;
    public String mode = "SIMPLE";
    public boolean matchCase;
    public boolean invert;
    public boolean visible;
    /** Divider location of the filter pane in pixels, -1 for default. */
    public int divider = -1;
}
