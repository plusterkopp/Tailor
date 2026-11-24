package de.pkmd.tailor.io;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * How bytes of a log file map to text lines.
 * <p>
 * Line splitting works on code units: one byte for ASCII-compatible charsets (UTF-8, ISO-8859-x,
 * Windows-125x, ...), two bytes for UTF-16. A line ends at a LF code unit; a CR directly before
 * it is stripped when decoding.
 *
 * @param charset    charset used to decode line bytes (for UTF-16 always the explicit LE/BE variant)
 * @param unitSize   1 or 2
 * @param bigEndian  byte order of two-byte code units
 * @param bomLength  length of the byte order mark at the start of the file, 0 if none
 */
public record TextEncoding(Charset charset, int unitSize, boolean bigEndian, int bomLength) {

    public static final TextEncoding UTF8 = new TextEncoding(StandardCharsets.UTF_8, 1, false, 0);

    public TextEncoding {
        if (unitSize != 1 && unitSize != 2) {
            throw new IllegalArgumentException("unitSize must be 1 or 2");
        }
    }

    /** Encoding for an explicitly chosen charset. */
    public static TextEncoding of(Charset cs, int bomLength) {
        String name = cs.name();
        if (name.equals("UTF-16LE")) {
            return new TextEncoding(cs, 2, false, bomLength);
        }
        if (name.equals("UTF-16BE") || name.equals("UTF-16")) {
            return new TextEncoding(StandardCharsets.UTF_16BE, 2, true, bomLength);
        }
        return new TextEncoding(cs, 1, false, bomLength);
    }

    /**
     * Detects the encoding from the first bytes of a file: byte order marks first, then a
     * heuristic for BOM-less UTF-16, then {@code fallback}.
     */
    public static TextEncoding detect(byte[] head, int len, Charset fallback) {
        if (len >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return new TextEncoding(StandardCharsets.UTF_8, 1, false, 3);
        }
        if (len >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return new TextEncoding(StandardCharsets.UTF_16LE, 2, false, 2);
        }
        if (len >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return new TextEncoding(StandardCharsets.UTF_16BE, 2, true, 2);
        }
        // BOM-less UTF-16: mostly-ASCII text has a zero in every other byte
        int pairs = len / 2;
        if (pairs >= 8) {
            int zeroOdd = 0, zeroEven = 0;
            for (int i = 0; i + 1 < len; i += 2) {
                if (head[i] == 0 && head[i + 1] != 0) {
                    zeroEven++;
                } else if (head[i] != 0 && head[i + 1] == 0) {
                    zeroOdd++;
                }
            }
            if (zeroOdd * 10 >= pairs * 7) {
                return new TextEncoding(StandardCharsets.UTF_16LE, 2, false, 0);
            }
            if (zeroEven * 10 >= pairs * 7) {
                return new TextEncoding(StandardCharsets.UTF_16BE, 2, true, 0);
            }
        }
        return of(fallback, 0);
    }

    /** True if the code unit starting at {@code buf[i]} is a LF. */
    boolean isLineFeed(byte[] buf, int i) {
        if (unitSize == 1) {
            return buf[i] == '\n';
        }
        return bigEndian ? buf[i] == 0 && buf[i + 1] == '\n' : buf[i] == '\n' && buf[i + 1] == 0;
    }

    private boolean isCarriageReturn(byte[] buf, int i) {
        if (unitSize == 1) {
            return buf[i] == '\r';
        }
        return bigEndian ? buf[i] == 0 && buf[i + 1] == '\r' : buf[i] == '\r' && buf[i + 1] == 0;
    }

    /** Decodes line bytes (without the LF), dropping a trailing CR. */
    public String decode(byte[] buf, int off, int len) {
        len -= len % unitSize;
        if (len >= unitSize && isCarriageReturn(buf, off + len - unitSize)) {
            len -= unitSize;
        }
        return new String(buf, off, len, charset);
    }

    public String displayName() {
        return charset.name() + (bomLength > 0 ? " (BOM)" : "");
    }
}
