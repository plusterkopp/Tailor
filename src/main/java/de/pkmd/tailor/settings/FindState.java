package de.pkmd.tailor.settings;

/** The find bar: one query for the whole window, used in whichever tab is selected. */
public final class FindState {

    public String text;
    public String mode = "SIMPLE";
    public boolean matchCase;
    public boolean highlightAll = true;
}
