# Tailor

A cross-platform live log viewer in the spirit of [BareTail](https://baremetalsoft.com/baretail/):
Java 21, Swing + FlatLaf, no native code.

## Features

- **Live following** by file system polling (works the same on every OS and on network shares).
  Growth is indexed incrementally; truncation and rotation (rename + recreate, copytruncate) are
  detected and the file is re-read. Files are never locked: polls open them briefly, reads share a
  handle that is closed after 1.5 s idle.
- **Large files** (tested with 5 GB): a sparse line index (one offset per 64 lines, ~6 MB for
  50 M lines) lets you jump anywhere instantly. Lines longer than 256 KB are recorded in the index and
  skipped by seeking. Very long lines are shown truncated, with a marker.
- **Highlighting** with regex or literal rules in named profiles. Each attribute (text color, background,
  bold, italic, underline, strikethrough) is optional and inherited from the layer below:
  view defaults → line rules (whole line) → match rules (matched text). So a line rule can color
  error lines while a match rule makes just the keyword bold. Rule editor with live preview.
- **Filter pane** below the tabs: only lines matching a query (or, inverted, not matching), with their
  original line numbers; keeps up as the file grows; clicking a line moves the main view there.
  The query belongs to the window and stays as it is when switching tabs; each tab keeps its own
  results, so switching back and forth only scans lines that are new.
- **Find** with next/previous, wrap-around and highlight-all. Like the filter, the find query belongs
  to the window and stays as it is when switching tabs; highlight-all marks matches in every tab.
  **Jump to next line colored by a line rule** (Alt+↓/↑); bookmarks; go to line.
- Query syntax for find and filter, selectable per query:
  - **Text**: the literal text
  - **Simple** (for humans): `timeout order` both words, any order · `error | warn` either ·
    `-heartbeat` exclude · `"user 42"` phrase · `conn*refused` wildcard
  - **Regex**: full `java.util.regex`
- **Fonts and Unicode**: any installed font; characters missing in it fall back to a font that has
  them (CJK, Greek, symbols). Tabs expanded, control characters made visible. UTF-8, UTF-16 (BOM or
  heuristic), any Java charset per file. NUL-preallocated files (IIS) are handled.
- **Spacing**: space between log lines and space between the rows of a wrapped line are separate
  settings.
- **Tabs** for multiple files (closable, middle-click, change indicator, top/bottom/left/right).
- **File sets**: named groups of files (with wildcards, optionally only the newest match), per-file
  highlight profile and encoding; open with one click or `--fileset NAME`.
- **State**: open files with their follow mode, position and bookmarks, the filter and find
  queries, query history and window geometry are restored on the next start (autosaved every 30 s).
  Settings export/import (selectively: profiles, file sets, general settings, history).
- Drag and drop of files onto the window; light and dark themes.

## Build and run

```bash
mvn package
java -jar target/tailor-0.1.0-SNAPSHOT-all.jar [--fileset NAME] [--config FILE] [file...]
```

Without arguments the last session is reopened. In IntelliJ IDEA open the folder as a Maven project
and use the shared run configuration *Tailor*.

Settings live in `~/.config/tailor/settings.json` (Linux), `~/Library/Application Support/Tailor`
(macOS) or `%APPDATA%\Tailor` (Windows); `--config FILE` uses another file (e.g. portable use).

Tests: `mvn test`; the 5 GB sparse-file test runs with `mvn test -Dtailor.largeTests=true`.

## Keyboard

| Key | Action |
|---|---|
| ↑ / ↓, Ctrl+↑ / Ctrl+↓ | move current line / scroll one row |
| PgUp / PgDn, Home / End | page / start, end of file (End starts following) |
| ← / →, Shift+wheel | scroll horizontally (no wrap) |
| Ctrl+F, F3 / Shift+F3 | find, next / previous |
| Ctrl+Shift+F | filter pane |
| Alt+↓ / Alt+↑ | next / previous line colored by a line rule |
| Ctrl+G | go to line |
| Ctrl+F2, F2 / Shift+F2, click on gutter | toggle bookmark, next / previous |
| Ctrl+T, Alt+Z, Ctrl+Shift+L | follow tail, word wrap, line numbers |
| Ctrl+= / Ctrl+- / Ctrl+0, Ctrl+wheel | zoom |
| Ctrl+O, Ctrl+W, Ctrl+PgDn / PgUp, F5 | open, close tab, switch tab, reload |

## Design notes

- `io.LineIndexer` scans 1 MB chunks with SWAR (8 bytes per step) for LF; ~2 GB/s from page cache.
- `io.LogFile` publishes immutable `LineIndex` snapshots; readers pass a snapshot so line counts and
  contents stay consistent while the file grows. Decoded 64-line blocks are cached (LRU, by size).
- `ui.LogView` is a virtual view: the scroll position is (line, wrapped row), only visible lines are
  read, highlighted and laid out (`java.awt.font.TextLayout`), layouts are cached.
- Measured on a 1 GB / 10 M line log (page cache hot): fetching the 60 visible lines at a random
  position 0.04 ms, a full 1400×900 frame after a random jump 2.8 ms median, wheel scrolling 0.7 ms
  per frame, filter scan 2.9 s/GB. I/O is ~1.5 % of a frame, so memory mapping would not help
  scrolling; it would also block rotation on Windows and crash (SIGBUS) on truncation.
