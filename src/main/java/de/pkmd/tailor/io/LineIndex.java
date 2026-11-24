package de.pkmd.tailor.io;

/**
 * Immutable snapshot of a sparse line index.
 * <p>
 * Only every {@link #STRIDE}th line start is stored ("checkpoint"), so a 50 million line file
 * needs about 6 MB of index. Lines between checkpoints are found by scanning forward from the
 * preceding checkpoint, which is cheap because a block of {@code STRIDE} lines is a few KB.
 * All offsets are {@code long}, so files larger than 4 GB work without special handling.
 * <p>
 * Snapshots share the checkpoint array with the indexer that produced them; entries below
 * {@link #checkpointCount} are never modified afterwards.
 */
public final class LineIndex {

    public static final int STRIDE_SHIFT = 6;
    public static final int STRIDE = 1 << STRIDE_SHIFT;
    /** Lines longer than this (in bytes) are recorded, so readers can jump over them. */
    public static final long LONG_LINE = 256 * 1024;

    private final long[] checkpoints;
    private final int checkpointCount;
    private final long completeLines;
    private final long partialStart;
    private final long end;
    /** Triples (line number, start offset, offset of the LF) of long lines, ordered by line. */
    private final long[] longLines;
    private final int longCount;

    LineIndex(long[] checkpoints, int checkpointCount, long completeLines, long partialStart, long end,
              long[] longLines, int longCount) {
        this.checkpoints = checkpoints;
        this.checkpointCount = checkpointCount;
        this.completeLines = completeLines;
        this.partialStart = partialStart;
        this.end = end;
        this.longLines = longLines;
        this.longCount = longCount;
    }

    /** Number of lines, including an unterminated last line. */
    public long lineCount() {
        return completeLines + (end > partialStart ? 1 : 0);
    }

    /** Number of LF-terminated lines. */
    public long completeLines() {
        return completeLines;
    }

    /** File offset up to which the file has been indexed; the readable extent of this snapshot. */
    public long end() {
        return end;
    }

    public int blockCount() {
        return (int) ((lineCount() + STRIDE - 1) >>> STRIDE_SHIFT);
    }

    public static int blockOf(long line) {
        return (int) (line >>> STRIDE_SHIFT);
    }

    /** File offset of the first line of block {@code b}. */
    public long blockStart(int b) {
        if (b < 0 || b >= checkpointCount) {
            throw new IndexOutOfBoundsException("block " + b + " of " + checkpointCount);
        }
        return checkpoints[b];
    }

    /** File offset just past the last line of block {@code b} (including its LF). */
    public long blockEnd(int b) {
        return b + 1 < checkpointCount ? checkpoints[b + 1] : end;
    }

    /** Number of lines in block {@code b}. */
    public int blockLines(int b) {
        long first = (long) b << STRIDE_SHIFT;
        return (int) Math.max(0, Math.min(STRIDE, lineCount() - first));
    }

    /** True if all lines of block {@code b} are LF-terminated, i.e. its content can no longer change. */
    public boolean blockComplete(int b) {
        return ((long) (b + 1) << STRIDE_SHIFT) <= completeLines;
    }

    /** Start offset of the unterminated last line (equals {@link #end()} if there is none). */
    long partialStart() {
        return partialStart;
    }

    int longLineCount() {
        return longCount;
    }

    /** Index of the first recorded long line with line number {@code >= line}. */
    int longLineAtOrAfter(long line) {
        int lo = 0;
        int hi = longCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (longLines[mid * 3] < line) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    long longLineNo(int i) {
        return longLines[i * 3];
    }

    long longLineEnd(int i) {
        return longLines[i * 3 + 2];
    }

    @Override
    public String toString() {
        return "LineIndex[lines=" + lineCount() + ", end=" + end + "]";
    }
}
