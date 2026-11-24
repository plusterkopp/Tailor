package de.pkmd.tailor.ui;

import de.pkmd.tailor.io.LineIndex;
import de.pkmd.tailor.io.LogFile;

import java.util.List;

/** Lines shown by a {@link LogView}. Implementations are snapshots and must not change. */
public interface LineSource {

    LineSource EMPTY = of(List.of());

    long lineCount();

    String line(long n);

    /** Byte length in the file if line {@code n} was truncated for display, else -1. */
    default long truncatedLength(long n) {
        return -1;
    }

    /** Line number shown in the gutter (0-based); differs from {@code n} in filtered views. */
    default long lineNumber(long n) {
        return n;
    }

    static LineSource of(List<String> lines) {
        return new LineSource() {
            @Override
            public long lineCount() {
                return lines.size();
            }

            @Override
            public String line(long n) {
                return lines.get((int) n);
            }
        };
    }

    /** A view of one index snapshot of a log file. */
    static LineSource of(LogFile file, LineIndex idx) {
        return new LineSource() {
            @Override
            public long lineCount() {
                return idx.lineCount();
            }

            @Override
            public String line(long n) {
                return file.line(idx, n);
            }

            @Override
            public long truncatedLength(long n) {
                LogFile.Block b = file.block(idx, LineIndex.blockOf(n));
                return b == null ? -1 : b.truncatedLength((int) (n - b.firstLine()));
            }
        };
    }
}
