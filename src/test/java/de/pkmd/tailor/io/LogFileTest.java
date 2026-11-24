package de.pkmd.tailor.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogFileTest {

    @TempDir
    Path dir;

    private static List<String> lines(LogFile f) {
        LineIndex idx = f.index();
        List<String> l = new ArrayList<>();
        for (long i = 0; i < idx.lineCount(); i++) {
            l.add(f.line(idx, i));
        }
        return l;
    }

    private static void append(Path p, String s) throws IOException {
        Files.writeString(p, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void indexesLinesAcrossBlocksAndChunks() throws IOException {
        Path p = dir.resolve("a.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            sb.append("line ").append(i).append(i % 7 == 0 ? "\r\n" : "\n");
        }
        Files.writeString(p, sb);
        try (LogFile f = new LogFile(p)) {
            f.poll();
            LineIndex idx = f.index();
            assertEquals(10_000, idx.lineCount());
            assertEquals("line 0", f.line(idx, 0));
            assertEquals("line 63", f.line(idx, 63));
            assertEquals("line 64", f.line(idx, 64));
            assertEquals("line 7", f.line(idx, 7)); // CR stripped
            assertEquals("line 9999", f.line(idx, 9999));
        }
    }

    @Test
    void followsAppendsIncludingPartialLastLine() throws IOException {
        Path p = dir.resolve("b.log");
        Files.writeString(p, "one\ntwo");
        try (LogFile f = new LogFile(p)) {
            f.poll();
            assertEquals(List.of("one", "two"), lines(f));
            assertEquals(1, f.index().completeLines());
            append(p, " more\nthree\n");
            f.poll();
            assertEquals(List.of("one", "two more", "three"), lines(f));
            append(p, "four\n");
            f.poll();
            assertEquals(List.of("one", "two more", "three", "four"), lines(f));
        }
    }

    @Test
    void restartsAfterTruncationAndReplacement() throws IOException {
        Path p = dir.resolve("c.log");
        Files.writeString(p, "a\nb\nc\n");
        try (LogFile f = new LogFile(p)) {
            f.poll();
            long gen = f.generation();
            assertEquals(3, f.index().lineCount());
            Files.writeString(p, "x\n"); // truncated (copytruncate rotation)
            f.poll();
            assertEquals(List.of("x"), lines(f));
            assertTrue(f.generation() > gen);

            gen = f.generation();
            Path rotated = dir.resolve("c.log.1");
            Files.move(p, rotated);
            Files.writeString(p, "new 1\nnew 2\nnew 3\n"); // rename + create: new file key
            f.poll();
            assertEquals(List.of("new 1", "new 2", "new 3"), lines(f));
            assertTrue(f.generation() > gen);
        }
    }

    @Test
    void reportsMissingFileAndPicksItUpLater() throws IOException {
        Path p = dir.resolve("later.log");
        try (LogFile f = new LogFile(p)) {
            f.poll();
            assertEquals(LogFile.State.MISSING, f.state());
            Files.writeString(p, "hello\n");
            f.poll();
            assertEquals(LogFile.State.LIVE, f.state());
            assertEquals(List.of("hello"), lines(f));
        }
    }

    @Test
    void decodesUtf16WithBom() throws IOException {
        Path p = dir.resolve("u16.log");
        byte[] body = "Grüße\r\n日本語\nend".getBytes(StandardCharsets.UTF_16LE);
        byte[] all = new byte[body.length + 2];
        all[0] = (byte) 0xFF;
        all[1] = (byte) 0xFE;
        System.arraycopy(body, 0, all, 2, body.length);
        Files.write(p, all);
        try (LogFile f = new LogFile(p)) {
            f.poll();
            assertEquals("UTF-16LE", f.encoding().charset().name());
            assertEquals(List.of("Grüße", "日本語", "end"), lines(f));
        }
    }

    @Test
    void detectsUtf8BomAndKeepsUnicode() throws IOException {
        Path p = dir.resolve("bom.log");
        byte[] body = "äöü 😀\nλ\n".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[body.length + 3];
        all[0] = (byte) 0xEF;
        all[1] = (byte) 0xBB;
        all[2] = (byte) 0xBF;
        System.arraycopy(body, 0, all, 3, body.length);
        Files.write(p, all);
        try (LogFile f = new LogFile(p)) {
            f.poll();
            assertEquals(List.of("äöü 😀", "λ"), lines(f));
        }
    }

    @Test
    void truncatesOverlongLinesButKeepsTheirLength() throws IOException {
        Path p = dir.resolve("long.log");
        String longLine = "x".repeat(300_000);
        Files.writeString(p, "short\n" + longLine + "\nafter\n");
        try (LogFile f = new LogFile(p)) {
            f.setMaxLineBytes(1000);
            f.poll();
            LineIndex idx = f.index();
            assertEquals(3, idx.lineCount());
            assertEquals(1000, f.line(idx, 1).length());
            assertEquals("after", f.line(idx, 2));
            LogFile.Block b = f.block(idx, 0);
            assertEquals(300_000, b.truncatedLength(1));
            assertEquals(-1, b.truncatedLength(0));
        }
    }

    @Test
    void stopsAtNulPaddingAndContinuesWhenFilledIn() throws IOException {
        Path p = dir.resolve("iis.log");
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.write("first\nsecond\n".getBytes(StandardCharsets.US_ASCII));
            raf.setLength(64 * 1024); // preallocated with NULs
        }
        try (LogFile f = new LogFile(p)) {
            f.poll();
            assertEquals(List.of("first", "second"), lines(f));
            try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
                raf.seek("first\nsecond\n".length());
                raf.write("third\n".getBytes(StandardCharsets.US_ASCII));
            }
            f.poll();
            assertEquals(List.of("first", "second", "third"), lines(f));
        }
    }

    @Test
    void searchesForwardBackwardAndWraps() throws IOException {
        Path p = dir.resolve("s.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append(i % 5000 == 1234 ? "needle " + i : "hay " + i).append('\n');
        }
        Files.writeString(p, sb);
        try (LogFile f = new LogFile(p)) {
            f.poll();
            LineIndex idx = f.index();
            var hit = LineSearch.find(f, idx, 0, true, false, s -> s.startsWith("needle"), null, () -> false);
            assertEquals(1234, hit.line());
            hit = LineSearch.find(f, idx, 1235, true, false, s -> s.startsWith("needle"), null, () -> false);
            assertEquals(6234, hit.line());
            hit = LineSearch.find(f, idx, 19_999, false, false, s -> s.startsWith("needle"), null, () -> false);
            assertEquals(16234, hit.line());
            hit = LineSearch.find(f, idx, 16235, true, true, s -> s.startsWith("needle"), null, () -> false);
            assertEquals(1234, hit.line());
            assertTrue(hit.wrapped());
            hit = LineSearch.find(f, idx, 1233, false, true, s -> s.startsWith("needle"), null, () -> false);
            assertEquals(16234, hit.line());
            assertTrue(hit.wrapped());
        }
    }

    /**
     * Sparse 5 GB file with lines beyond the 4 GB mark. Opt-in because it scans 5 GB:
     * {@code mvn test -Dtailor.largeTests=true}.
     */
    @Test
    @EnabledIfSystemProperty(named = "tailor.largeTests", matches = "true")
    void handlesFilesLargerThan4GB() throws IOException {
        Path p = dir.resolve("huge.log");
        long hole = 5L << 30;
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.write("start\n".getBytes(StandardCharsets.US_ASCII));
            raf.seek(hole);
            raf.write("\nafter 5 GB\nlast\n".getBytes(StandardCharsets.US_ASCII));
        }
        try (LogFile f = new LogFile(p)) {
            long t0 = System.nanoTime();
            f.poll();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LineIndex idx = f.index();
            assertEquals(4, idx.lineCount());
            assertEquals("start", f.line(idx, 0));
            assertEquals("after 5 GB", f.line(idx, 2));
            assertEquals("last", f.line(idx, 3));
            assertTrue(idx.end() > hole);
            System.out.printf("indexed %.1f GB in %d ms%n", idx.end() / 1e9, ms);
        }
    }
}
