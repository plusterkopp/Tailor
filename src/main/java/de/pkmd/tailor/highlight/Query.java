package de.pkmd.tailor.highlight;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A line query used by find and filter, in one of three syntaxes:
 * <ul>
 *   <li>{@link Mode#TEXT}: the literal text.</li>
 *   <li>{@link Mode#SIMPLE}: search-engine style for humans. Words separated by spaces must all
 *       occur (in any order). {@code a | b} or {@code a OR b} accepts either. {@code -word} excludes
 *       lines containing the word. {@code "quoted text"} keeps spaces. {@code *} matches any
 *       characters.</li>
 *   <li>{@link Mode#REGEX}: a full {@link java.util.regex.Pattern} regular expression.</li>
 * </ul>
 */
public final class Query {

    public enum Mode {
        TEXT("Text"), SIMPLE("Simple"), REGEX("Regex");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        public static Mode parse(String s) {
            for (Mode m : values()) {
                if (m.name().equalsIgnoreCase(s) || m.label.equalsIgnoreCase(s)) {
                    return m;
                }
            }
            return SIMPLE;
        }
    }

    /** Thrown for queries that cannot be compiled; the message is meant for the user. */
    public static final class SyntaxException extends Exception {
        public SyntaxException(String message) {
            super(message);
        }
    }

    /**
     * One searchable term. Literal terms avoid the regex engine: plain {@code indexOf} is several
     * times faster, which matters when a filter scans gigabytes.
     */
    private sealed interface Term {
        /** @param lower the line in lower case, computed once per line if any term needs it */
        boolean find(String line, String lower);
    }

    private record Literal(String text, boolean ignoreCase) implements Term {
        @Override
        public boolean find(String line, String lower) {
            return (ignoreCase ? lower : line).contains(text);
        }
    }

    private record Regex(Pattern pattern) implements Term {
        @Override
        public boolean find(String line, String lower) {
            return pattern.matcher(line).find();
        }
    }

    /** Each inner list is a group of alternatives; every group must match. */
    private final List<List<Term>> required;
    private final List<Term> excluded;
    private final Pattern highlight;
    private final boolean needsLower;

    private Query(List<List<Term>> required, List<Term> excluded, Pattern highlight) {
        this.required = required;
        this.excluded = excluded;
        this.highlight = highlight;
        boolean lower = false;
        for (List<Term> g : required) {
            lower |= g.stream().anyMatch(t -> t instanceof Literal l && l.ignoreCase());
        }
        lower |= excluded.stream().anyMatch(t -> t instanceof Literal l && l.ignoreCase());
        this.needsLower = lower;
    }

    private static Term term(String literalOrNull, Pattern p, boolean matchCase) {
        if (literalOrNull != null) {
            return new Literal(matchCase ? literalOrNull : literalOrNull.toLowerCase(Locale.ROOT), !matchCase);
        }
        return new Regex(p);
    }

    public static Query compile(String text, Mode mode, boolean matchCase) throws SyntaxException {
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if (text == null || text.isEmpty()) {
            throw new SyntaxException("empty query");
        }
        try {
            return switch (mode) {
                case TEXT -> single(term(text, null, matchCase), Pattern.compile(text, flags | Pattern.LITERAL));
                case REGEX -> {
                    Pattern p = Pattern.compile(text, flags);
                    yield single(term(null, p, matchCase), p);
                }
                case SIMPLE -> simple(text, flags, matchCase);
            };
        } catch (PatternSyntaxException e) {
            throw new SyntaxException(e.getDescription() + (e.getIndex() >= 0 ? " at " + e.getIndex() : ""));
        }
    }

    private static Query single(Term t, Pattern highlight) {
        return new Query(List.of(List.of(t)), List.of(), highlight);
    }

    private static Query simple(String text, int flags, boolean matchCase) throws SyntaxException {
        List<List<Term>> required = new ArrayList<>();
        List<Term> excluded = new ArrayList<>();
        List<String> highlightParts = new ArrayList<>();
        boolean joinNext = false;
        for (Token t : tokenize(text)) {
            if (t.or()) {
                if (required.isEmpty()) {
                    throw new SyntaxException("“|” needs a word on both sides");
                }
                joinNext = true;
                continue;
            }
            // a|b inside a word is an alternative as well
            List<Term> alternatives = new ArrayList<>();
            for (String part : t.quoted() ? List.of(t.text()) : List.of(t.text().split("\\|"))) {
                if (part.isEmpty()) {
                    continue;
                }
                String regex = wildcardToRegex(part);
                boolean literal = part.indexOf('*') < 0;
                alternatives.add(literal ? term(part, null, matchCase) : term(null, Pattern.compile(regex, flags),
                        matchCase));
                if (!t.negated()) {
                    highlightParts.add(regex);
                }
            }
            if (alternatives.isEmpty()) {
                continue;
            }
            if (t.negated()) {
                excluded.addAll(alternatives);
            } else if (joinNext) {
                required.getLast().addAll(alternatives);
            } else {
                required.add(new ArrayList<>(alternatives));
            }
            joinNext = false;
        }
        if (joinNext) {
            throw new SyntaxException("“|” needs a word on both sides");
        }
        if (required.isEmpty() && excluded.isEmpty()) {
            throw new SyntaxException("empty query");
        }
        Pattern highlight = highlightParts.isEmpty() ? null
                : Pattern.compile(String.join("|", highlightParts.stream().map(s -> "(?:" + s + ")").toList()), flags);
        return new Query(required, excluded, highlight);
    }

    private record Token(String text, boolean quoted, boolean negated, boolean or) {
    }

    private static List<Token> tokenize(String s) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            boolean negated = false;
            if (c == '-' && i + 1 < n && !Character.isWhitespace(s.charAt(i + 1))) {
                negated = true;
                i++;
                c = s.charAt(i);
            }
            if (c == '"') {
                int end = s.indexOf('"', i + 1);
                if (end < 0) {
                    end = n;
                }
                tokens.add(new Token(s.substring(i + 1, end), true, negated, false));
                i = Math.min(n, end + 1);
                continue;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            String word = s.substring(start, i);
            if (!negated && (word.equals("|") || word.equals("OR"))) {
                tokens.add(new Token(word, false, false, true));
            } else {
                tokens.add(new Token(word, false, negated, false));
            }
        }
        return tokens;
    }

    /** Quotes everything except {@code *}, which becomes "any characters". */
    static String wildcardToRegex(String s) {
        StringBuilder sb = new StringBuilder();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '*') {
                if (i > start) {
                    sb.append(Pattern.quote(s.substring(start, i)));
                }
                sb.append(".*?");
                start = i + 1;
            }
        }
        if (start < s.length()) {
            sb.append(Pattern.quote(s.substring(start)));
        }
        return sb.toString();
    }

    public boolean matches(String line) {
        String lower = needsLower ? line.toLowerCase(Locale.ROOT) : null;
        for (Term t : excluded) {
            if (t.find(line, lower)) {
                return false;
            }
        }
        for (List<Term> group : required) {
            boolean any = false;
            for (Term t : group) {
                if (t.find(line, lower)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** Pattern matching the text a hit consists of, for highlighting; null if there is none (pure exclusion). */
    public Pattern highlightPattern() {
        return highlight;
    }
}
