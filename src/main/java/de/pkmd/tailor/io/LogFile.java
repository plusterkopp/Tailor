package de.pkmd.tailor.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * A log file that is followed by polling.
 * <p>
 * {@link #poll()} (called periodically by {@link FilePoller}) compares the file with what has been
 * indexed: growth is indexed incrementally, shrinking or replacement (log rotation) restarts the
 * index. The file is never locked: polls open it briefly, and reads share a handle that is closed
 * after {@value #IDLE_CLOSE_MS} ms without use and dropped on rotation, so writers can rotate,
 * rename or delete the file at any time (Java opens files with full sharing on Windows).
 * <p>
 * Readers take an {@link #index()} snapshot and pass it to the read methods so that line counts
 * and line contents stay consistent while the file grows.
 */
public final class LogFile implements AutoCloseable {

    public enum State { OPENING, INDEXING, LIVE, MISSING, ERROR }

    public enum Change {
        /** Lines were added (or the last, unterminated line grew). */
        APPENDED,
        /** The file was truncated, replaced or re-decoded; all line numbers are invalid. */
        RESET,
        /** {@link #state()} or indexing progress changed. */
        STATUS
    }

    @FunctionalInterface
    public interface Listener {
        /** Called on a background thread. */
        void changed(LogFile file, Change change);
    }

    @FunctionalInterface
    public interface LineVisitor {
        /** @return false to stop */
        boolean visit(long line, String text);
    }

    /** A decoded block of {@link LineIndex#STRIDE} consecutive lines. */
    public record Block(long generation, long firstLine, String[] lines, long[] fullLengths, boolean complete,
                        long indexEnd) {
        /** Byte length of line {@code i} in the file if it was truncated, else -1. */
        public long truncatedLength(int i) {
            return fullLengths == null ? -1 : fullLengths[i];
        }
    }

    private static final int CHUNK = 1 << 20;
    private static final long PUBLISH_EVERY = 32L << 20;
    private static final long CACHE_CHAR_BUDGET = 24L << 20;
    static final long IDLE_CLOSE_MS = 1500;
    private static final ThreadLocal<ByteBuffer> SCAN_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(CHUNK).order(ByteOrder.LITTLE_ENDIAN));

    private final Path path;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Map<Integer, Block> cache = new LinkedHashMap<>(256, 0.75f, true);
    private long cacheChars;

    /** Read handle shared by concurrent readers; reference counted so it can be retired safely. */
    private static final class Handle {
        final FileChannel channel;
        int leases;
        boolean retired;

        Handle(FileChannel channel) {
            this.channel = channel;
        }
    }

    private final Object handleLock = new Object();
    private Handle handle;
    private long handleLastUse;

    private volatile Charset defaultCharset = StandardCharsets.UTF_8;
    private volatile Charset forcedCharset;
    private volatile int maxLineBytes = 64 * 1024;
    private volatile boolean resetRequested;
    private volatile boolean closed;

    // written by the polling thread only
    private LineIndexer indexer;
    private Object fileKey;

    private volatile TextEncoding encoding = TextEncoding.UTF8;
    private volatile LineIndex index = new LineIndexer(TextEncoding.UTF8).snapshot();
    private volatile long generation;
    private volatile State state = State.OPENING;
    private volatile String error;
    private volatile double progress = 1;
    private volatile long fileSize;
    private volatile long lastAppend;

    public LogFile(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public Path path() {
        return path;
    }

    public LineIndex index() {
        return index;
    }

    public long generation() {
        return generation;
    }

    public TextEncoding encoding() {
        return encoding;
    }

    public State state() {
        return state;
    }

    public String error() {
        return error;
    }

    /** Indexing progress 0..1 while {@link State#INDEXING}. */
    public double progress() {
        return progress;
    }

    public long fileSize() {
        return fileSize;
    }

    /** Wall clock time of the last observed growth, 0 if none yet. */
    public long lastAppend() {
        return lastAppend;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Charset for files without byte order mark; takes effect with the next reset. */
    public void setDefaultCharset(Charset cs) {
        defaultCharset = cs;
    }

    /** Forces a charset (null: detect), and re-reads the file. */
    public void setCharset(Charset cs) {
        forcedCharset = cs;
        requestReset();
    }

    public Charset forcedCharset() {
        return forcedCharset;
    }

    public void setMaxLineBytes(int max) {
        if (max != maxLineBytes) {
            maxLineBytes = max;
            clearCache();
        }
    }

    /** Makes the next {@link #poll()} re-read the file from scratch. */
    public void requestReset() {
        resetRequested = true;
    }

    public boolean isBusy() {
        return busy.get();
    }

    @Override
    public void close() {
        closed = true;
        listeners.clear();
        retireHandle();
        clearCache();
    }

    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------ polling

    /** Checks the file for changes and updates the index. Concurrent calls are skipped. */
    public void poll() {
        if (closed || !busy.compareAndSet(false, true)) {
            return;
        }
        try {
            doPoll();
        } catch (IOException e) {
            setState(State.ERROR, e.toString());
        } catch (RuntimeException e) {
            setState(State.ERROR, e.toString());
            throw e;
        } finally {
            busy.set(false);
        }
    }

    private void doPoll() throws IOException {
        retireIdleHandle();
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            retireHandle();
            setState(State.MISSING, null);
            return;
        }
        if (!attrs.isRegularFile()) {
            setState(State.ERROR, "not a regular file");
            return;
        }
        Object key = attrs.fileKey();
        // size from an open handle: on Windows the directory entry lags behind for files being written
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = ch.size();
            fileSize = size;
            LineIndexer ix = indexer;
            boolean replaced = fileKey != null && key != null && !fileKey.equals(key);
            boolean undetected = ix != null && forcedCharset == null && ix.scanPos() == 0 && size > 0;
            if (ix == null || resetRequested || replaced || undetected || state == State.MISSING
                    || size < ix.scanPos()) {
                resetRequested = false;
                fileKey = key;
                ix = reset(ch, size);
            }
            if (size > ix.scanPos()) {
                scan(ch, ix, size);
            }
            if (!closed) {
                setState(State.LIVE, null);
            }
        }
    }

    private LineIndexer reset(FileChannel ch, long size) throws IOException {
        byte[] head = new byte[(int) Math.min(size, 4096)];
        int n = head.length == 0 ? 0 : Math.max(0, ch.read(ByteBuffer.wrap(head), 0));
        TextEncoding enc = TextEncoding.detect(head, n, forcedCharset != null ? forcedCharset : defaultCharset);
        if (forcedCharset != null && !enc.charset().equals(TextEncoding.of(forcedCharset, 0).charset())) {
            enc = TextEncoding.of(forcedCharset, 0);
        }
        LineIndexer ix = new LineIndexer(enc);
        indexer = ix;
        encoding = enc;
        retireHandle(); // may still point to the file that was rotated away
        clearCache();
        generation++;
        index = ix.snapshot();
        fire(Change.RESET);
        return ix;
    }

    private void scan(FileChannel ch, LineIndexer ix, long size) throws IOException {
        ByteBuffer buf = SCAN_BUFFER.get();
        long startPos = ix.scanPos();
        // Some writers (e.g. IIS) preallocate the file with NUL bytes and fill it in later: the
        // data then ends at the first NUL, and later polls continue from there.
        boolean padded = ix.encoding().unitSize() == 1 && endsWithNul(ch, size);
        boolean big = size - startPos > 4 * PUBLISH_EVERY;
        if (big) {
            progress = (double) startPos / size;
            setState(State.INDEXING, null);
        }
        long lastPublish = startPos;
        while (ix.scanPos() < size && !closed) {
            long pos = ix.scanPos();
            buf.clear().limit((int) Math.min(CHUNK, size - pos));
            int n = ch.read(buf, pos);
            if (n <= 0) {
                break;
            }
            boolean stop = false;
            if (padded) {
                for (int i = 0; i < n; i++) {
                    if (buf.get(i) == 0) {
                        n = i;
                        stop = true;
                        break;
                    }
                }
            }
            ix.scan(buf, n);
            if (stop) {
                break;
            }
            if (ix.scanPos() == pos) {
                break; // a lone half code unit at the end of a UTF-16 file
            }
            if (ix.scanPos() - lastPublish >= PUBLISH_EVERY) {
                lastPublish = ix.scanPos();
                progress = (double) lastPublish / size;
                publish(ix);
            }
        }
        progress = 1;
        if (ix.scanPos() != startPos || startPos == ix.encoding().bomLength()) {
            publish(ix);
        }
    }

    private static boolean endsWithNul(FileChannel ch, long size) throws IOException {
        if (size == 0) {
            return false;
        }
        ByteBuffer one = ByteBuffer.allocate(1);
        return ch.read(one, size - 1) == 1 && one.get(0) == 0;
    }

    private void publish(LineIndexer ix) {
        if (closed) {
            return;
        }
        index = ix.snapshot();
        lastAppend = System.currentTimeMillis();
        fire(Change.APPENDED);
    }

    private void setState(State s, String err) {
        if (state != s || (err != null && !err.equals(error))) {
            state = s;
            error = err;
            fire(Change.STATUS);
        } else if (s == State.INDEXING) {
            fire(Change.STATUS);
        }
    }

    private void fire(Change c) {
        for (Listener l : listeners) {
            l.changed(this, c);
        }
    }

    // ------------------------------------------------------------------ reading

    private Handle acquire() throws IOException {
        synchronized (handleLock) {
            if (handle == null) {
                handle = new Handle(FileChannel.open(path, StandardOpenOption.READ));
            }
            handle.leases++;
            return handle;
        }
    }

    private void release(Handle h) {
        synchronized (handleLock) {
            h.leases--;
            handleLastUse = System.nanoTime();
            if (h.retired && h.leases == 0) {
                closeQuietly(h.channel);
            }
        }
    }

    private void retireHandle() {
        synchronized (handleLock) {
            Handle h = handle;
            handle = null;
            if (h != null) {
                h.retired = true;
                if (h.leases == 0) {
                    closeQuietly(h.channel);
                }
            }
        }
    }

    private void retireIdleHandle() {
        synchronized (handleLock) {
            if (handle != null && handle.leases == 0
                    && System.nanoTime() - handleLastUse > IDLE_CLOSE_MS * 1_000_000) {
                retireHandle();
            }
        }
    }

    private static void closeQuietly(FileChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /**
     * Returns block {@code b} of the given snapshot, from the cache if possible.
     * Returns null if the file cannot be read.
     */
    public Block block(LineIndex idx, int b) {
        long gen = generation;
        synchronized (cache) {
            Block blk = cache.get(b);
            if (blk != null && blk.generation == gen && (blk.complete || blk.indexEnd == idx.end())) {
                return blk;
            }
        }
        int n = idx.blockLines(b);
        if (n <= 0) {
            return null;
        }
        String[] lines = new String[n];
        long[][] full = new long[1][];
        TextEncoding enc = encoding;
        long firstLine = (long) b << LineIndex.STRIDE_SHIFT;
        Handle h = null;
        try {
            h = acquire();
            LineReader.read(h.channel, enc, idx.blockStart(b), idx.blockEnd(b), firstLine, n, maxLineBytes, idx,
                    (lineNo, buf, off, len, fullLength) -> {
                        int i = (int) (lineNo - firstLine);
                        lines[i] = enc.decode(buf, off, len);
                        if (fullLength > len) {
                            if (full[0] == null) {
                                full[0] = new long[n];
                                java.util.Arrays.fill(full[0], -1);
                            }
                            full[0][i] = fullLength;
                        }
                        return true;
                    }, () -> false);
        } catch (IOException e) {
            if (h != null) {
                retireHandle();
            }
            return null;
        } finally {
            if (h != null) {
                release(h);
            }
        }
        boolean complete = idx.blockComplete(b);
        for (int i = 0; i < n; i++) {
            if (lines[i] == null) {
                lines[i] = "";
                complete = false;
            }
        }
        Block blk = new Block(gen, firstLine, lines, full[0], complete, idx.end());
        if (gen == generation) {
            synchronized (cache) {
                Block old = cache.put(b, blk);
                if (old != null) {
                    cacheChars -= chars(old);
                }
                cacheChars += chars(blk);
                var it = cache.values().iterator();
                while (cacheChars > CACHE_CHAR_BUDGET && cache.size() > 1 && it.hasNext()) {
                    cacheChars -= chars(it.next());
                    it.remove();
                }
            }
        }
        return blk;
    }

    /** Line text, or "" if it cannot be read. */
    public String line(LineIndex idx, long line) {
        if (line < 0 || line >= idx.lineCount()) {
            return "";
        }
        Block blk = block(idx, LineIndex.blockOf(line));
        if (blk == null) {
            return "";
        }
        int i = (int) (line - blk.firstLine());
        return i < blk.lines().length ? blk.lines()[i] : "";
    }

    /**
     * Streams lines {@code [from, to)} of the snapshot without caching them.
     *
     * @return number of lines visited
     */
    public long forEachLine(LineIndex idx, long from, long to, LineVisitor visitor, BooleanSupplier cancelled)
            throws IOException {
        to = Math.min(to, idx.lineCount());
        if (from < 0 || from >= to) {
            return 0;
        }
        int b = LineIndex.blockOf(from);
        long blockFirst = (long) b << LineIndex.STRIDE_SHIFT;
        TextEncoding enc = encoding;
        long fromLine = from;
        long[] visited = {0};
        Handle h = acquire();
        try {
            LineReader.read(h.channel, enc, idx.blockStart(b), idx.end(), blockFirst, to - blockFirst, maxLineBytes,
                    idx, (lineNo, buf, off, len, fullLength) -> {
                        if (lineNo < fromLine) {
                            return !cancelled.getAsBoolean();
                        }
                        visited[0]++;
                        return visitor.visit(lineNo, enc.decode(buf, off, len));
                    }, cancelled);
        } finally {
            release(h);
        }
        return visited[0];
    }

    private void clearCache() {
        synchronized (cache) {
            cache.clear();
            cacheChars = 0;
        }
    }

    private static long chars(Block b) {
        long c = 64;
        for (String s : b.lines()) {
            c += s.length() + 16;
        }
        return c;
    }

    @Override
    public String toString() {
        return "LogFile[" + path + "]";
    }
}
