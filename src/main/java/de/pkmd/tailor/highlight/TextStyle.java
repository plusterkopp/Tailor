package de.pkmd.tailor.highlight;

import java.awt.Color;

/**
 * One layer of text attributes. Every attribute is optional: {@code null} means "inherit from the
 * layer below", so layers can be stacked, e.g. a background color from a line rule with bold text
 * from a match rule on top.
 */
public record TextStyle(Color foreground, Color background, Boolean bold, Boolean italic, Boolean underline,
                        Boolean strikethrough) {

    public static final TextStyle EMPTY = new TextStyle(null, null, null, null, null, null);

    public static TextStyle colors(Color fg, Color bg) {
        return new TextStyle(fg, bg, null, null, null, null);
    }

    /** This layer placed on top of {@code below}: attributes set here win. */
    public TextStyle over(TextStyle below) {
        if (below == null || below.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return below;
        }
        return new TextStyle(
                foreground != null ? foreground : below.foreground,
                background != null ? background : below.background,
                bold != null ? bold : below.bold,
                italic != null ? italic : below.italic,
                underline != null ? underline : below.underline,
                strikethrough != null ? strikethrough : below.strikethrough);
    }

    public boolean isEmpty() {
        return foreground == null && background == null && bold == null && italic == null && underline == null
                && strikethrough == null;
    }

    public boolean isBold() {
        return Boolean.TRUE.equals(bold);
    }

    public boolean isItalic() {
        return Boolean.TRUE.equals(italic);
    }

    public boolean isUnderline() {
        return Boolean.TRUE.equals(underline);
    }

    public boolean isStrikethrough() {
        return Boolean.TRUE.equals(strikethrough);
    }
}
