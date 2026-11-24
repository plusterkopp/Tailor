package de.pkmd.tailor.highlight;

import de.pkmd.tailor.highlight.Query.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTest {

    private static Query simple(String q) throws Query.SyntaxException {
        return Query.compile(q, Mode.SIMPLE, false);
    }

    @Test
    void simpleWordsMustAllOccurInAnyOrder() throws Exception {
        Query q = simple("timeout order");
        assertTrue(q.matches("Order 42 failed: Timeout"));
        assertFalse(q.matches("order 42 ok"));
    }

    @Test
    void simpleAlternativesAndExclusion() throws Exception {
        Query q = simple("error | warn -heartbeat");
        assertTrue(q.matches("ERROR db down"));
        assertTrue(q.matches("WARN slow"));
        assertFalse(q.matches("WARN heartbeat late"));
        assertFalse(q.matches("INFO ok"));
        assertTrue(simple("error|fatal").matches("fatal crash"));
        assertTrue(simple("disk OR cpu").matches("CPU at 99%"));
    }

    @Test
    void simplePhrasesAndWildcards() throws Exception {
        assertTrue(simple("\"user 42\"").matches("login user 42 ok"));
        assertFalse(simple("\"user 42\"").matches("user 4 2"));
        Query w = simple("conn*refused");
        assertTrue(w.matches("connection was refused"));
        assertFalse(w.matches("refused connection"));
        // regex characters are literal in simple mode
        assertTrue(simple("a.b").matches("a.b"));
        assertFalse(simple("a.b").matches("axb"));
    }

    @Test
    void onlyExclusionsMatchEverythingElse() throws Exception {
        Query q = simple("-DEBUG");
        assertTrue(q.matches("INFO x"));
        assertFalse(q.matches("DEBUG x"));
    }

    @Test
    void textAndRegexModes() throws Exception {
        assertTrue(Query.compile("a.b", Mode.TEXT, true).matches("xa.by"));
        assertFalse(Query.compile("a.b", Mode.TEXT, true).matches("axb"));
        assertTrue(Query.compile("^\\d{4}-\\d{2}", Mode.REGEX, true).matches("2026-11 x"));
        assertFalse(Query.compile("A", Mode.TEXT, true).matches("a"));
        assertTrue(Query.compile("A", Mode.TEXT, false).matches("a"));
    }

    @Test
    void reportsSyntaxErrors() {
        assertThrows(Query.SyntaxException.class, () -> Query.compile("([", Mode.REGEX, false));
        assertThrows(Query.SyntaxException.class, () -> simple("| foo"));
        assertThrows(Query.SyntaxException.class, () -> simple("   "));
    }

    @Test
    void highlightPatternCoversPositiveTerms() throws Exception {
        Query q = simple("alpha | beta -gamma");
        assertTrue(q.highlightPattern().matcher("BETA").find());
        assertFalse(q.highlightPattern().matcher("gamma").find());
    }
}
