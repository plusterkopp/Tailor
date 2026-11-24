package de.pkmd.tailor.io;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/** Sequential search for the next or previous line satisfying a predicate. */
public final class LineSearch {

    /** Lines read per backward step; large enough to make each read efficient. */
    private static final int BACKWARD_CHUNK = 4096;
    private static final int PROGRESS_EVERY = 65536;

    /**
     * @param line    matching line, or -1
     * @param wrapped true if the hit was found after wrapping around the end (or start) of the file
     */
    public record Result(long line, boolean wrapped) {
        public static final Result NONE = new Result(-1, false);

        public boolean found() {
            return line >= 0;
        }
    }

    private LineSearch() {
    }

    /**
     * Finds the first matching line at or after {@code from} (forward) or at or before {@code from}
     * (backward). With {@code wrap}, continues at the other end of the file up to {@code from}.
     *
     * @param progress receives the current line number now and then; may be null
     */
    public static Result find(LogFile file, LineIndex idx, long from, boolean forward, boolean wrap,
                              Predicate<String> matches, LongConsumer progress, BooleanSupplier cancelled)
            throws IOException {
        long count = idx.lineCount();
        if (count == 0) {
            return Result.NONE;
        }
        from = Math.max(0, Math.min(from, count));
        long hit = forward
                ? findForward(file, idx, from, count, matches, progress, cancelled)
                : findBackward(file, idx, Math.min(from, count - 1), 0, matches, progress, cancelled);
        if (hit >= 0 || !wrap || cancelled.getAsBoolean()) {
            return hit >= 0 ? new Result(hit, false) : Result.NONE;
        }
        hit = forward
                ? findForward(file, idx, 0, Math.min(from, count), matches, progress, cancelled)
                : findBackward(file, idx, count - 1, from + 1, matches, progress, cancelled);
        return hit >= 0 ? new Result(hit, true) : Result.NONE;
    }

    /** First match in {@code [from, to)}. */
    private static long findForward(LogFile file, LineIndex idx, long from, long to, Predicate<String> matches,
                                    LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        long[] hit = {-1};
        file.forEachLine(idx, from, to, (line, text) -> {
            if (matches.test(text)) {
                hit[0] = line;
                return false;
            }
            if (progress != null && (line & (PROGRESS_EVERY - 1)) == 0) {
                progress.accept(line);
            }
            return true;
        }, cancelled);
        return cancelled.getAsBoolean() ? -1 : hit[0];
    }

    /** Last match in {@code [downTo, from]}. */
    private static long findBackward(LogFile file, LineIndex idx, long from, long downTo, Predicate<String> matches,
                                     LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        String[] chunk = new String[BACKWARD_CHUNK];
        long end = from + 1;
        while (end > downTo && !cancelled.getAsBoolean()) {
            long start = Math.max(downTo, end - BACKWARD_CHUNK);
            long base = start;
            Arrays.fill(chunk, null);
            file.forEachLine(idx, start, end, (line, text) -> {
                chunk[(int) (line - base)] = text;
                return true;
            }, cancelled);
            for (long l = end - 1; l >= start; l--) {
                String text = chunk[(int) (l - start)];
                if (text != null && matches.test(text)) {
                    return cancelled.getAsBoolean() ? -1 : l;
                }
            }
            if (progress != null) {
                progress.accept(start);
            }
            end = start;
        }
        return -1;
    }
}
