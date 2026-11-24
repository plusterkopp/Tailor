package de.pkmd.tailor.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Builds a {@link LineIndex} incrementally from consecutive chunks of a file.
 * Not thread-safe; owned by the thread polling the file, readers use {@link #snapshot()}.
 */
final class LineIndexer {

    private static final long LF8 = 0x0A0A0A0A0A0A0A0AL;
    private static final long LOW7 = 0x7F7F7F7F7F7F7F7FL;
    private static final long MASK = LineIndex.STRIDE - 1;

    private final TextEncoding encoding;
    private long[] checkpoints = new long[256];
    private int checkpointCount;
    private long completeLines;
    private long partialStart;
    private long scanPos;
    private long[] longLines = new long[3 * 16];
    private int longCount;

    LineIndexer(TextEncoding encoding) {
        this.encoding = encoding;
        checkpoints[0] = encoding.bomLength();
        checkpointCount = 1;
        partialStart = encoding.bomLength();
        scanPos = encoding.bomLength();
    }

    TextEncoding encoding() {
        return encoding;
    }

    /** File offset where the next chunk must start. */
    long scanPos() {
        return scanPos;
    }

    LineIndex snapshot() {
        return new LineIndex(checkpoints, checkpointCount, completeLines, partialStart, scanPos, longLines,
                longCount);
    }

    /**
     * Scans {@code buf[0, len)}, which holds the file bytes starting at {@link #scanPos()}.
     * For two-byte encodings a trailing odd byte is left for the next chunk.
     */
    void scan(ByteBuffer buf, int len) {
        if (encoding.unitSize() == 1) {
            scanBytes(buf, len);
        } else {
            scanUnits(buf, len & ~1);
        }
    }

    private void scanBytes(ByteBuffer buf, int len) {
        assert buf.order() == ByteOrder.LITTLE_ENDIAN;
        final long base = scanPos;
        long lines = completeLines;
        long lineStart = partialStart;
        int i = 0;
        // SWAR: test 8 bytes per step; t gets the high bit set exactly in bytes equal to LF
        for (int limit = len - 8; i <= limit; i += 8) {
            long w = buf.getLong(i) ^ LF8;
            long t = ~(((w & LOW7) + LOW7) | w | LOW7);
            while (t != 0) {
                long lf = base + i + (Long.numberOfTrailingZeros(t) >>> 3);
                if (lf - lineStart > LineIndex.LONG_LINE) {
                    addLongLine(lines, lineStart, lf);
                }
                lineStart = lf + 1;
                if ((++lines & MASK) == 0) {
                    addCheckpoint(lineStart);
                }
                t &= t - 1;
            }
        }
        for (; i < len; i++) {
            if (buf.get(i) == '\n') {
                long lf = base + i;
                if (lf - lineStart > LineIndex.LONG_LINE) {
                    addLongLine(lines, lineStart, lf);
                }
                lineStart = lf + 1;
                if ((++lines & MASK) == 0) {
                    addCheckpoint(lineStart);
                }
            }
        }
        completeLines = lines;
        partialStart = lineStart;
        scanPos = base + len;
    }

    private void scanUnits(ByteBuffer buf, int len) {
        final long base = scanPos;
        final int lfHi = encoding.bigEndian() ? 0 : 1;
        final int lfLo = 1 - lfHi;
        long lines = completeLines;
        long lineStart = partialStart;
        for (int i = 0; i < len; i += 2) {
            if (buf.get(i + lfLo) == '\n' && buf.get(i + lfHi) == 0) {
                long lf = base + i;
                if (lf - lineStart > LineIndex.LONG_LINE) {
                    addLongLine(lines, lineStart, lf);
                }
                lineStart = lf + 2;
                if ((++lines & MASK) == 0) {
                    addCheckpoint(lineStart);
                }
            }
        }
        completeLines = lines;
        partialStart = lineStart;
        scanPos = base + len;
    }

    private void addLongLine(long line, long start, long lf) {
        if (longCount * 3 == longLines.length) {
            longLines = Arrays.copyOf(longLines, longLines.length * 2);
        }
        int k = longCount * 3;
        longLines[k] = line;
        longLines[k + 1] = start;
        longLines[k + 2] = lf;
        longCount++;
    }

    private void addCheckpoint(long offset) {
        if (checkpointCount == checkpoints.length) {
            // snapshots keep the old array; entries they can see are already final
            checkpoints = Arrays.copyOf(checkpoints, checkpoints.length * 2);
        }
        checkpoints[checkpointCount++] = offset;
    }
}
