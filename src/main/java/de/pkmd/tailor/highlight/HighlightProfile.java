package de.pkmd.tailor.highlight;

import de.pkmd.tailor.highlight.HighlightRule.Scope;

import java.util.ArrayList;
import java.util.List;

/** A named, ordered list of highlight rules. Earlier rules take precedence. */
public final class HighlightProfile {

    public static final String DEFAULT_NAME = "Default";

    private String name;
    private List<HighlightRule> rules = new ArrayList<>();

    public HighlightProfile() {
    }

    public HighlightProfile(String name, List<HighlightRule> rules) {
        this.name = name;
        this.rules = new ArrayList<>(rules);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<HighlightRule> getRules() {
        if (rules == null) {
            rules = new ArrayList<>();
        }
        return rules;
    }

    public void setRules(List<HighlightRule> rules) {
        this.rules = new ArrayList<>(rules);
    }

    public HighlightProfile copy() {
        return new HighlightProfile(name, getRules().stream().map(HighlightRule::copy).toList());
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Rules for typical Java/log4j/syslog style logs; colors chosen to read on light and dark themes.
     * Shows the layering: error lines get colors from a line rule, their level keyword is made bold
     * by a match rule that sets no colors.
     */
    public static HighlightProfile createDefault() {
        List<HighlightRule> r = new ArrayList<>();
        r.add(new HighlightRule("Level keywords", "\\b(ERROR|FATAL|SEVERE|CRITICAL|WARN|WARNING)\\b", Scope.MATCH,
                null, null).bold().caseSensitive());
        r.add(new HighlightRule("Exceptions", "\\b[\\w$.]+(?:Exception|Error)\\b|^Caused by:", Scope.MATCH,
                "#D03838", null).bold().caseSensitive());
        r.add(new HighlightRule("Timestamp",
                "^\\[?\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?\\]?",
                Scope.MATCH, "#3F7CC4", null));
        r.add(new HighlightRule("Info", "\\bINFO\\b", Scope.MATCH, "#2E8B57", null).bold().caseSensitive());
        r.add(new HighlightRule("Debug/Trace", "\\b(DEBUG|TRACE|FINE|FINER|FINEST)\\b", Scope.MATCH, "#8A8A8A", null)
                .caseSensitive());
        r.add(new HighlightRule("Stack frames", "^\\s+at\\s.*$", Scope.MATCH, "#8A8A8A", null).italic()
                .caseSensitive());
        r.add(new HighlightRule("Error lines", "\\b(ERROR|FATAL|SEVERE|CRITICAL|EMERG|ALERT)\\b", Scope.LINE,
                "#5C0000", "#F6C9C9").caseSensitive());
        r.add(new HighlightRule("Warning lines", "\\b(WARN|WARNING)\\b", Scope.LINE, "#4A3A00", "#FCEDB8")
                .caseSensitive());
        return new HighlightProfile(DEFAULT_NAME, r);
    }
}
