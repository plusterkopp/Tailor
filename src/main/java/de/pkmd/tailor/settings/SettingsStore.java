package de.pkmd.tailor.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import de.pkmd.tailor.highlight.HighlightProfile;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Loads and saves {@link Settings} as JSON in the platform's configuration directory. */
public final class SettingsStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;

    public SettingsStore(Path file) {
        this.file = file;
    }

    /** {@code %APPDATA%\Tailor}, {@code ~/Library/Application Support/Tailor} or {@code $XDG_CONFIG_HOME/tailor}. */
    public static SettingsStore forUser() {
        String override = System.getProperty("tailor.config");
        if (override != null) {
            return new SettingsStore(Path.of(override));
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        Path dir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            dir = (appData != null ? Path.of(appData) : home.resolve("AppData/Roaming")).resolve("Tailor");
        } else if (os.contains("mac")) {
            dir = home.resolve("Library/Application Support/Tailor");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            dir = (xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".config")).resolve("tailor");
        }
        return new SettingsStore(dir.resolve("settings.json"));
    }

    public Path file() {
        return file;
    }

    /** Reads settings from a file, failing if it cannot be read or parsed. */
    public static Settings read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Settings s = GSON.fromJson(r, Settings.class);
            if (s == null) {
                throw new IOException("empty file");
            }
            return s.normalize();
        } catch (JsonParseException e) {
            throw new IOException("not a Tailor settings file: " + e.getMessage(), e);
        }
    }

    /**
     * Copies parts of imported settings. Profiles and file sets are merged by name: imported ones
     * replace existing ones with the same name, others are added.
     */
    public static void merge(Settings from, Settings into, boolean profiles, boolean fileSets, boolean general,
                             boolean history) {
        if (profiles) {
            for (HighlightProfile p : from.profiles) {
                into.profiles.removeIf(q -> q.getName().equals(p.getName()));
                into.profiles.add(p.copy());
            }
        }
        if (fileSets) {
            for (FileSet f : from.fileSets) {
                into.fileSets.removeIf(g -> g.getName().equals(f.getName()));
                into.fileSets.add(f.copy());
            }
        }
        if (general) {
            into.fontFamily = from.fontFamily;
            into.fontSize = from.fontSize;
            into.lineGap = from.lineGap;
            into.wrapGap = from.wrapGap;
            into.wrap = from.wrap;
            into.tabWidth = from.tabWidth;
            into.showLineNumbers = from.showLineNumbers;
            into.followTail = from.followTail;
            into.textForeground = from.textForeground;
            into.textBackground = from.textBackground;
            into.searchHighlight = from.searchHighlight;
            into.currentLineHighlight = from.currentLineHighlight;
            into.pollIntervalMs = from.pollIntervalMs;
            into.maxLineKiB = from.maxLineKiB;
            into.defaultCharset = from.defaultCharset;
            into.theme = from.theme;
            into.tabPlacement = from.tabPlacement;
            into.restoreSession = from.restoreSession;
        }
        if (history) {
            for (String h : from.findHistory.reversed()) {
                Settings.addToHistory(into.findHistory, h, 30);
            }
            for (String h : from.filterHistory.reversed()) {
                Settings.addToHistory(into.filterHistory, h, 30);
            }
        }
        into.normalize();
    }

    /** Loads the settings; returns defaults if the file is missing or unreadable. */
    public Settings load() {
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Settings s = GSON.fromJson(r, Settings.class);
                if (s != null) {
                    return s.normalize();
                }
            } catch (IOException | JsonParseException e) {
                System.err.println("Tailor: cannot read " + file + ": " + e.getMessage());
            }
        }
        return new Settings().normalize();
    }

    public void save(Settings settings) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(settings, w);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String toJson(Object o) {
        return GSON.toJson(o);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }
}
