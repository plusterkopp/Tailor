package de.pkmd.tailor.settings;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A file reference in a file set or the saved session, with per-file view options.
 * <p>
 * The file name part of {@link #getPath()} may contain the wildcards {@code *} and {@code ?}
 * (e.g. {@code /var/log/app/app-*.log}); with {@link #isNewestOnly()} only the most recently
 * modified match is opened, which suits date-stamped logs.
 */
public final class FileEntry {

    private String path;
    private String profile;
    private String charset;
    private boolean newestOnly;
    private TabState state;

    public FileEntry() {
    }

    public FileEntry(String path) {
        this.path = path;
    }

    public FileEntry(String path, String profile, String charset) {
        this.path = path;
        this.profile = profile;
        this.charset = charset;
    }

    public FileEntry copy() {
        FileEntry e = new FileEntry(path, profile, charset);
        e.newestOnly = newestOnly;
        e.state = state == null ? null : state.copy();
        return e;
    }

    public boolean isPattern() {
        if (path == null) {
            return false;
        }
        String name = Path.of(path).getFileName() == null ? "" : Path.of(path).getFileName().toString();
        return name.indexOf('*') >= 0 || name.indexOf('?') >= 0;
    }

    /** The files this entry stands for. A plain path is returned even if the file does not exist yet. */
    public List<Path> resolve() {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        if (!isPattern()) {
            return List.of(Path.of(path));
        }
        Path p = Path.of(path);
        Path dir = p.getParent() == null ? Path.of(".") : p.getParent();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + p.getFileName());
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path f : ds) {
                if (matcher.matches(f.getFileName()) && Files.isRegularFile(f)) {
                    matches.add(f);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        if (newestOnly) {
            return matches.stream().max(Comparator.comparing(FileEntry::modified)).map(List::of).orElse(List.of());
        }
        matches.sort(Comparator.naturalOrder());
        return matches;
    }

    private static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    /** Highlight profile name; null for the default profile. */
    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    /** Charset name; null to auto-detect. */
    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public boolean isNewestOnly() {
        return newestOnly;
    }

    public void setNewestOnly(boolean newestOnly) {
        this.newestOnly = newestOnly;
    }

    /** Remembered view state (follow mode, position, bookmarks); may be null. */
    public TabState getState() {
        return state;
    }

    public void setState(TabState state) {
        this.state = state;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FileEntry e && Objects.equals(path, e.path) && Objects.equals(profile, e.profile)
                && Objects.equals(charset, e.charset) && newestOnly == e.newestOnly;
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, profile, charset, newestOnly);
    }

    @Override
    public String toString() {
        return path;
    }
}
