package de.pkmd.tailor.settings;

import de.pkmd.tailor.highlight.HighlightProfile;
import de.pkmd.tailor.highlight.HighlightRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsEverything() throws IOException {
        Settings s = new Settings().normalize();
        s.lineGap = 5;
        s.wrapGap = 1;
        s.fontFamily = "Monospaced";
        HighlightRule r = new HighlightRule("x", "foo", HighlightRule.Scope.LINE, "#112233", null);
        r.setUnderline(false);
        s.profiles.add(new HighlightProfile("Mine", List.of(r)));
        FileEntry e = new FileEntry("/var/log/app/*.log", "Mine", "ISO-8859-1");
        e.setNewestOnly(true);
        TabState st = new TabState();
        st.bookmarks.add(42L);
        s.find.text = "needle";
        s.find.highlightAll = false;
        e.setState(st);
        s.filter.text = "error -heartbeat";
        s.filter.visible = true;
        s.filter.divider = 321;
        s.fileSets.add(new FileSet("App", List.of(e)));
        s.session.add(e);
        s.findHistory.add("needle");

        SettingsStore store = new SettingsStore(dir.resolve("sub/settings.json"));
        store.save(s);
        Settings t = store.load();

        assertEquals(5, t.lineGap);
        assertEquals(1, t.wrapGap);
        HighlightRule r2 = t.findProfile("Mine").orElseThrow().getRules().getFirst();
        assertEquals(HighlightRule.Scope.LINE, r2.getScope());
        assertEquals(Boolean.FALSE, r2.getUnderline());
        assertNull(r2.getBold());
        assertNull(r2.getBackground());
        FileEntry e2 = t.fileSets.getFirst().getFiles().getFirst();
        assertTrue(e2.isNewestOnly());
        assertEquals("error -heartbeat", t.filter.text);
        assertTrue(t.filter.visible);
        assertEquals(321, t.filter.divider);
        assertEquals("needle", t.find.text);
        assertEquals(false, t.find.highlightAll);
        assertEquals(List.of(42L), t.session.getFirst().getState().bookmarks);
        assertEquals(List.of("needle"), t.findHistory);
        assertTrue(t.findProfile(HighlightProfile.DEFAULT_NAME).isPresent());
    }

    @Test
    void mergesImportedProfilesAndSetsByName() {
        Settings into = new Settings().normalize();
        into.fileSets.add(new FileSet("A", List.of(new FileEntry("/old"))));
        into.lineGap = 3;
        Settings from = new Settings().normalize();
        from.fileSets.add(new FileSet("A", List.of(new FileEntry("/new"))));
        from.fileSets.add(new FileSet("B", List.of()));
        from.profiles.add(new HighlightProfile("Extra", List.of()));
        from.lineGap = 9;

        SettingsStore.merge(from, into, true, true, false, false);
        assertEquals(2, into.fileSets.size());
        assertEquals("/new", into.fileSets.stream().filter(f -> f.getName().equals("A")).findFirst()
                .orElseThrow().getFiles().getFirst().getPath());
        assertTrue(into.findProfile("Extra").isPresent());
        assertEquals(1, into.profiles.stream().filter(p -> p.getName().equals(HighlightProfile.DEFAULT_NAME))
                .count());
        assertEquals(3, into.lineGap);

        SettingsStore.merge(from, into, false, false, true, false);
        assertEquals(9, into.lineGap);
    }

    @Test
    void resolvesWildcardEntries() throws IOException {
        Path a = Files.writeString(dir.resolve("app-2026-11-23.log"), "a");
        Path b = Files.writeString(dir.resolve("app-2026-11-24.log"), "b");
        Files.writeString(dir.resolve("other.txt"), "c");
        Files.setLastModifiedTime(a, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(b, FileTime.fromMillis(2_000_000));
        FileEntry all = new FileEntry(dir.resolve("app-*.log").toString());
        assertEquals(List.of(a, b), all.resolve());
        all.setNewestOnly(true);
        assertEquals(List.of(b), all.resolve());
        assertEquals(List.of(dir.resolve("plain.log")), new FileEntry(dir.resolve("plain.log").toString()).resolve());
    }
}
