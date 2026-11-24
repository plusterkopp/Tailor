package de.pkmd.tailor.highlight;

import java.awt.Color;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One highlighting rule: a pattern plus the style applied either to the whole line
 * ({@link Scope#LINE}) or to each match within the line ({@link Scope#MATCH}).
 * <p>
 * Every style attribute is optional (null colors, null flags) and then inherited from the layer
 * below, see {@link Highlighter}. Mutable bean so it can be edited in the GUI and serialized as
 * JSON; colors are "#RRGGBB" strings.
 */
public final class HighlightRule {

    public enum Scope {
        LINE("Whole line"), MATCH("Matched text");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private boolean enabled = true;
    private String name = "";
    private String pattern = "";
    private boolean regex = true;
    private boolean caseSensitive;
    private Scope scope = Scope.MATCH;
    private String foreground;
    private String background;
    private Boolean bold;
    private Boolean italic;
    private Boolean underline;
    private Boolean strikethrough;

    public HighlightRule() {
    }

    public HighlightRule(String name, String pattern, Scope scope, String foreground, String background) {
        this.name = name;
        this.pattern = pattern;
        this.scope = scope;
        this.foreground = foreground;
        this.background = background;
    }

    public HighlightRule copy() {
        HighlightRule r = new HighlightRule(name, pattern, scope, foreground, background);
        r.enabled = enabled;
        r.regex = regex;
        r.caseSensitive = caseSensitive;
        r.bold = bold;
        r.italic = italic;
        r.underline = underline;
        r.strikethrough = strikethrough;
        return r;
    }

    /** Compiles the pattern; throws {@link PatternSyntaxException} if it is invalid. */
    public Pattern compile() {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if (!regex) {
            flags |= Pattern.LITERAL;
        }
        return Pattern.compile(pattern == null ? "" : pattern, flags);
    }

    /** Error message of an invalid pattern, or null if it compiles. */
    public String validate() {
        if (pattern == null || pattern.isEmpty()) {
            return "empty pattern";
        }
        try {
            compile();
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    public TextStyle style() {
        return new TextStyle(parseColor(foreground), parseColor(background), bold, italic, underline, strikethrough);
    }

    public static Color parseColor(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Color.decode(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String formatColor(Color c) {
        return c == null ? null : String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public boolean isRegex() {
        return regex;
    }

    public void setRegex(boolean regex) {
        this.regex = regex;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public Color getForeground() {
        return parseColor(foreground);
    }

    public void setForeground(Color c) {
        this.foreground = formatColor(c);
    }

    public Color getBackground() {
        return parseColor(background);
    }

    public void setBackground(Color c) {
        this.background = formatColor(c);
    }

    /** Tri-state: true, false, or null to inherit. */
    public Boolean getBold() {
        return bold;
    }

    public void setBold(Boolean bold) {
        this.bold = bold;
    }

    public Boolean getItalic() {
        return italic;
    }

    public void setItalic(Boolean italic) {
        this.italic = italic;
    }

    public Boolean getUnderline() {
        return underline;
    }

    public void setUnderline(Boolean underline) {
        this.underline = underline;
    }

    public Boolean getStrikethrough() {
        return strikethrough;
    }

    public void setStrikethrough(Boolean strikethrough) {
        this.strikethrough = strikethrough;
    }

    public HighlightRule bold() {
        this.bold = true;
        return this;
    }

    public HighlightRule italic() {
        this.italic = true;
        return this;
    }

    public HighlightRule caseSensitive() {
        this.caseSensitive = true;
        return this;
    }
}
