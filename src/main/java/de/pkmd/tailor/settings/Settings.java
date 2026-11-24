package de.pkmd.tailor.settings;

import de.pkmd.tailor.highlight.HighlightProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** All persistent preferences. Plain fields, serialized as JSON by {@link SettingsStore}. */
public final class Settings {

    public static final int MAX_RECENT = 15;

    // --- view
    /** Font family; null selects the first installed of a list of good monospaced fonts. */
    public String fontFamily;
    public int fontSize = 13;
    /** Extra pixels between two log lines. */
    public int lineGap = 2;
    /** Extra pixels between the rows of a wrapped log line. */
    public int wrapGap = 0;
    public boolean wrap = false;
    public int tabWidth = 4;
    public boolean showLineNumbers = true;
    public boolean followTail = true;
    /** Optional color overrides ("#RRGGBB"); null uses the theme's text colors. */
    public String textForeground;
    public String textBackground;
    public String searchHighlight = "#FFB000";
    public String currentLineHighlight;

    // --- files
    public int pollIntervalMs = 500;
    public int maxLineKiB = 64;
    public String defaultCharset = "UTF-8";

    // --- application
    /** One of {@link #THEMES}. */
    public String theme = "Light";
    public boolean restoreSession = true;
    public String lastDirectory;
    /** Top, Bottom, Left or Right. */
    public String tabPlacement = "Top";

    public List<HighlightProfile> profiles = new ArrayList<>();
    public List<FileSet> fileSets = new ArrayList<>();
    public List<String> recentFiles = new ArrayList<>();
    public List<String> findHistory = new ArrayList<>();
    public List<String> filterHistory = new ArrayList<>();
    public FilterState filter = new FilterState();
    public FindState find = new FindState();

    // --- session
    public List<FileEntry> session = new ArrayList<>();
    public int sessionSelected;
    public int windowX = -1;
    public int windowY = -1;
    public int windowWidth = 1200;
    public int windowHeight = 800;
    public boolean windowMaximized;

    public static final List<String> THEMES = List.of("Light", "Dark", "IntelliJ", "Darcula", "macOS Light",
            "macOS Dark");

    /** Repairs values after loading: missing lists, out-of-range numbers, missing default profile. */
    public Settings normalize() {
        if (profiles == null) {
            profiles = new ArrayList<>();
        }
        if (profiles.stream().noneMatch(p -> HighlightProfile.DEFAULT_NAME.equals(p.getName()))) {
            profiles.addFirst(HighlightProfile.createDefault());
        }
        if (fileSets == null) {
            fileSets = new ArrayList<>();
        }
        if (recentFiles == null) {
            recentFiles = new ArrayList<>();
        }
        if (session == null) {
            session = new ArrayList<>();
        }
        if (findHistory == null) {
            findHistory = new ArrayList<>();
        }
        if (filterHistory == null) {
            filterHistory = new ArrayList<>();
        }
        if (filter == null) {
            filter = new FilterState();
        }
        if (find == null) {
            find = new FindState();
        }
        if (!List.of("Top", "Bottom", "Left", "Right").contains(tabPlacement)) {
            tabPlacement = "Top";
        }
        fontSize = clamp(fontSize, 6, 72);
        lineGap = clamp(lineGap, 0, 64);
        wrapGap = clamp(wrapGap, 0, 64);
        tabWidth = clamp(tabWidth, 1, 16);
        pollIntervalMs = clamp(pollIntervalMs, 50, 60_000);
        maxLineKiB = clamp(maxLineKiB, 1, 16 * 1024);
        if (theme == null || !THEMES.contains(theme)) {
            theme = "Light";
        }
        return this;
    }

    public HighlightProfile profile(String name) {
        return findProfile(name).orElseGet(this::defaultProfile);
    }

    public Optional<HighlightProfile> findProfile(String name) {
        return profiles.stream().filter(p -> p.getName().equals(name)).findFirst();
    }

    public HighlightProfile defaultProfile() {
        return findProfile(HighlightProfile.DEFAULT_NAME).orElseGet(() -> profiles.getFirst());
    }

    public void addRecent(String path) {
        addToHistory(recentFiles, path, MAX_RECENT);
    }

    /** Moves {@code value} to the front of a most-recently-used list. */
    public static void addToHistory(List<String> list, String value, int max) {
        if (value == null || value.isBlank()) {
            return;
        }
        list.remove(value);
        list.addFirst(value);
        while (list.size() > max) {
            list.removeLast();
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
