package de.pkmd.tailor.highlight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiled form of a {@link HighlightProfile}. Immutable and thread-safe.
 * <p>
 * Styles are layered. From bottom to top:
 * <ol>
 *   <li>the view's default colors and font,</li>
 *   <li>every line rule matching somewhere in the line, applied to the whole line,</li>
 *   <li>every match of every match rule, applied to the matched characters.</li>
 * </ol>
 * Within each group a rule listed earlier lies above a rule listed later. Each layer only
 * overrides the attributes it sets, so e.g. a line rule can color the background of the line
 * while a match rule makes some words of it bold.
 */
public final class Highlighter {

    public static final Highlighter NONE = new Highlighter(List.of(), List.of());

    /** Safety valve against pathological patterns that match everywhere. */
    private static final int MAX_SPANS_PER_RULE = 2000;

    private record Compiled(Pattern pattern, TextStyle style) {
    }

    /** A styled character range {@code [start, end)} of a line. */
    public record Span(int start, int end, TextStyle style) {
    }

    /**
     * Result for one line.
     *
     * @param lineStyle combined style of all matching line rules, or null
     * @param spans     match styles, ordered bottom to top (apply in order, later layers win)
     */
    public record LineHighlight(TextStyle lineStyle, List<Span> spans) {
        public static final LineHighlight EMPTY = new LineHighlight(null, List.of());
    }

    private final List<Compiled> lineRules;
    private final List<Compiled> matchRules;

    private Highlighter(List<Compiled> lineRules, List<Compiled> matchRules) {
        this.lineRules = lineRules;
        this.matchRules = matchRules;
    }

    /** Compiles all enabled rules; rules with invalid or empty patterns are skipped. */
    public static Highlighter compile(HighlightProfile profile) {
        if (profile == null) {
            return NONE;
        }
        List<Compiled> line = new ArrayList<>();
        List<Compiled> match = new ArrayList<>();
        for (HighlightRule r : profile.getRules()) {
            if (!r.isEnabled() || r.getPattern() == null || r.getPattern().isEmpty()) {
                continue;
            }
            Pattern p;
            try {
                p = r.compile();
            } catch (PatternSyntaxException e) {
                continue;
            }
            TextStyle style = r.style();
            if (!style.isEmpty()) {
                (r.getScope() == HighlightRule.Scope.LINE ? line : match).add(new Compiled(p, style));
            }
        }
        // match spans are produced bottom layer first: the last rule in the list
        Collections.reverse(match);
        return new Highlighter(List.copyOf(line), List.copyOf(match));
    }

    public boolean isEmpty() {
        return lineRules.isEmpty() && matchRules.isEmpty();
    }

    public boolean hasLineRules() {
        return !lineRules.isEmpty();
    }

    public LineHighlight apply(String text) {
        if (isEmpty() || text.isEmpty()) {
            return LineHighlight.EMPTY;
        }
        TextStyle lineStyle = lineStyle(text);
        List<Span> spans = null;
        for (Compiled c : matchRules) {
            Matcher m = c.pattern.matcher(text);
            int n = 0;
            while (n < MAX_SPANS_PER_RULE && m.find()) {
                if (m.end() > m.start()) {
                    if (spans == null) {
                        spans = new ArrayList<>();
                    }
                    spans.add(new Span(m.start(), m.end(), c.style));
                    n++;
                }
            }
        }
        if (lineStyle == null && spans == null) {
            return LineHighlight.EMPTY;
        }
        return new LineHighlight(lineStyle, spans == null ? List.of() : spans);
    }

    /** Combined style of all line rules matching {@code text}, or null if none matches. */
    public TextStyle lineStyle(String text) {
        TextStyle style = null;
        for (Compiled c : lineRules) {
            if (c.pattern.matcher(text).find()) {
                style = style == null ? c.style : style.over(c.style);
            }
        }
        return style;
    }

    /** True if any line rule matches; used to jump between highlighted lines. */
    public boolean matchesLineRule(String text) {
        for (Compiled c : lineRules) {
            if (c.pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
