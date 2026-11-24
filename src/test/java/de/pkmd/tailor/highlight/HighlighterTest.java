package de.pkmd.tailor.highlight;

import de.pkmd.tailor.highlight.HighlightRule.Scope;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighlighterTest {

    @Test
    void layersLineColorsWithMatchFontAttributes() {
        HighlightRule boldKeyword = new HighlightRule("kw", "ERROR", Scope.MATCH, null, null).bold().caseSensitive();
        HighlightRule redLine = new HighlightRule("line", "ERROR", Scope.LINE, "#550000", "#FFCCCC");
        Highlighter h = Highlighter.compile(new HighlightProfile("p", List.of(boldKeyword, redLine)));

        Highlighter.LineHighlight r = h.apply("12:00 ERROR boom");
        assertEquals(new Color(0xFFCCCC), r.lineStyle().background());
        assertEquals(1, r.spans().size());
        Highlighter.Span s = r.spans().getFirst();
        assertEquals(6, s.start());
        assertEquals(11, s.end());
        // the span only sets bold; composed over the line it keeps the line's colors
        TextStyle composed = s.style().over(r.lineStyle());
        assertTrue(composed.isBold());
        assertEquals(new Color(0xFFCCCC), composed.background());
        assertEquals(new Color(0x550000), composed.foreground());
    }

    @Test
    void earlierRulesLieOnTop() {
        HighlightRule top = new HighlightRule("a", "foo", Scope.MATCH, "#111111", null);
        HighlightRule bottom = new HighlightRule("b", "o+", Scope.MATCH, "#222222", "#EEEEEE");
        Highlighter h = Highlighter.compile(new HighlightProfile("p", List.of(top, bottom)));
        List<Highlighter.Span> spans = h.apply("foo").spans();
        // bottom layer first
        assertEquals(new Color(0x222222), spans.get(0).style().foreground());
        assertEquals(new Color(0x111111), spans.get(1).style().foreground());
        TextStyle atO = spans.get(1).style().over(spans.get(0).style());
        assertEquals(new Color(0x111111), atO.foreground());
        assertEquals(new Color(0xEEEEEE), atO.background()); // inherited from the lower layer
    }

    @Test
    void lineRulesCombineAttributeWise() {
        HighlightRule italic = new HighlightRule("i", "user=admin", Scope.LINE, null, null).italic();
        HighlightRule red = new HighlightRule("r", "ERROR", Scope.LINE, null, "#FFCCCC");
        Highlighter h = Highlighter.compile(new HighlightProfile("p", List.of(italic, red)));
        TextStyle s = h.lineStyle("ERROR user=admin");
        assertTrue(s.isItalic());
        assertEquals(new Color(0xFFCCCC), s.background());
        assertNull(h.lineStyle("nothing"));
        assertTrue(h.matchesLineRule("an ERROR"));
        assertFalse(h.matchesLineRule("fine"));
    }

    @Test
    void explicitOffOverridesLowerOn() {
        TextStyle lower = new TextStyle(null, null, true, null, null, null);
        TextStyle upper = new TextStyle(null, null, false, null, null, null);
        assertFalse(upper.over(lower).isBold());
    }

    @Test
    void skipsDisabledAndInvalidRules() {
        HighlightRule broken = new HighlightRule("x", "([", Scope.MATCH, "#FF0000", null);
        HighlightRule off = new HighlightRule("y", "a", Scope.MATCH, "#FF0000", null);
        off.setEnabled(false);
        Highlighter h = Highlighter.compile(new HighlightProfile("p", List.of(broken, off)));
        assertTrue(h.isEmpty());
        assertTrue(broken.validate() != null);
    }

    @Test
    void literalRulesQuoteRegexCharacters() {
        HighlightRule r = new HighlightRule("lit", "a.b", Scope.MATCH, "#FF0000", null);
        r.setRegex(false);
        Highlighter h = Highlighter.compile(new HighlightProfile("p", List.of(r)));
        assertEquals(1, h.apply("xa.bx").spans().size());
        assertEquals(0, h.apply("xaxbx").spans().size());
    }
}
