package de.pkmd.tailor.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Streams lines out of a byte range of a file with bounded memory.
 * Lines longer than {@code maxLineBytes} are delivered truncated, together with their full length.
 */
final class LineReader {

    @FunctionalInterface
    interface Sink {
        /**
         * @param fullLength length of the line in the file (without LF); larger than {@code len} if truncated
         * @return false to stop reading
         */
        boolean line(long lineNo, byte[] buf, int off, int len, long fullLength);
    }

    private static final int MIN_BUFFER = 256 * 1024;

    private LineReader() {
    }

    /**
     * Reads lines from {@code [start, end)}. {@code start} must be the start of a line.
     * A final segment without LF before {@code end} is delivered as a line as well.
     * With an index, lines it knows to be long are read only up to {@code maxLineBytes} and then
     * skipped by seeking instead of streaming through them.
     *
     * @param idx index of the file, or null
     * @return number of lines delivered
     */
    static long read(FileChannel ch, TextEncoding enc, long start, long end, long firstLineNo, long maxLines,
                     int maxLineBytes, LineIndex idx, Sink sink, BooleanSupplier cancelled) throws IOException {
        if (maxLines <= 0 || start >= end) {
            return 0;
        }
        final int u = enc.unitSize();
        maxLineBytes = Math.max(u, maxLineBytes - maxLineBytes % u);
        long range = end - start;
        int cap = (int) Math.min(Math.max(MIN_BUFFER, 2L * maxLineBytes + u), Math.max(range + u, 16));
        byte[] buf = new byte[cap];
        int bufLen = 0;
        int lineStart = 0;
        int scan = 0;
        long pos = start;
        long bufOff = start; // file offset of buf[0]
        long lineNo = firstLineNo;
        long delivered = 0;

        int longIdx = idx == null ? 0 : idx.longLineAtOrAfter(firstLineNo);
        long nextLong = idx != null && longIdx < idx.longLineCount() ? idx.longLineNo(longIdx) : -1;
        long longPartial = idx != null && idx.end() - idx.partialStart() > LineIndex.LONG_LINE
                ? idx.completeLines() : -1;

        byte[] trunc = null;   // first maxLineBytes of an overlong line, while skipping its rest
        long truncTotal = 0;

        while (true) {
            if (trunc == null && scan == lineStart && (lineNo == nextLong || lineNo == longPartial)) {
                // known long line: read its head directly and seek past the rest
                long ls = bufOff + lineStart;
                boolean hasLf = lineNo == nextLong;
                long le = Math.min(end, hasLf ? idx.longLineEnd(longIdx) : idx.end());
                if (hasLf) {
                    longIdx++;
                    nextLong = longIdx < idx.longLineCount() ? idx.longLineNo(longIdx) : -1;
                }
                byte[] head = new byte[(int) Math.min(maxLineBytes, Math.max(0, le - ls))];
                int got = 0;
                while (got < head.length) {
                    int n = ch.read(ByteBuffer.wrap(head, got, head.length - got), ls + got);
                    if (n <= 0) {
                        break;
                    }
                    got += n;
                }
                boolean more = sink.line(lineNo, head, 0, got, le - ls);
                lineNo++;
                long next = hasLf ? le + u : le;
                if (!more || ++delivered >= maxLines || cancelled.getAsBoolean() || next >= end) {
                    return delivered;
                }
                pos = next;
                bufOff = next;
                bufLen = 0;
                lineStart = 0;
                scan = 0;
                continue;
            }
            // look for LF in the scanned-but-unconsumed part of the buffer
            int lf = -1;
            int scanEnd = bufLen - (u - 1);
            for (int i = scan; i < scanEnd; i += u) {
                if (enc.isLineFeed(buf, i)) {
                    lf = i;
                    break;
                }
            }
            if (lf >= 0) {
                boolean more;
                if (trunc != null) {
                    more = sink.line(lineNo, trunc, 0, trunc.length, truncTotal + (lf - lineStart));
                    trunc = null;
                } else {
                    int len = lf - lineStart;
                    if (len > maxLineBytes) {
                        more = sink.line(lineNo, buf, lineStart, maxLineBytes, len);
                    } else {
                        more = sink.line(lineNo, buf, lineStart, len, len);
                    }
                }
                lineNo++;
                lineStart = scan = lf + u;
                if (!more || ++delivered >= maxLines || cancelled.getAsBoolean()) {
                    return delivered;
                }
                continue;
            }
            scan = bufLen - ((bufLen - lineStart) % u);

            if (pos >= end) {
                // final segment without LF
                if (trunc != null) {
                    sink.line(lineNo, trunc, 0, trunc.length, truncTotal + (bufLen - lineStart));
                    delivered++;
                } else if (lineStart < bufLen) {
                    int len = bufLen - lineStart;
                    sink.line(lineNo, buf, lineStart, Math.min(len, maxLineBytes), len);
                    delivered++;
                }
                return delivered;
            }

            // free space: drop consumed bytes, and the middle part of overlong lines
            if (trunc != null) {
                truncTotal += scan - lineStart;
                lineStart = scan;
            } else if (scan - lineStart > maxLineBytes) {
                trunc = Arrays.copyOfRange(buf, lineStart, lineStart + maxLineBytes);
                truncTotal = scan - lineStart;
                lineStart = scan;
            }
            if (lineStart > 0) {
                System.arraycopy(buf, lineStart, buf, 0, bufLen - lineStart);
                bufLen -= lineStart;
                scan -= lineStart;
                bufOff += lineStart;
                lineStart = 0;
            }
            int want = (int) Math.min(buf.length - bufLen, end - pos);
            int n = want > 0 ? ch.read(ByteBuffer.wrap(buf, bufLen, want), pos) : -1;
            if (n <= 0) {
                // file shrank under us, or buffer unexpectedly full: treat as end of range
                end = pos;
                continue;
            }
            pos += n;
            bufLen += n;
        }
    }
}
