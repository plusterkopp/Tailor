package de.pkmd.tailor.io;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls registered files at a fixed interval.
 * <p>
 * Polling (rather than OS change notifications) works the same on every platform and on network
 * shares, and catches writers that do not flush directory metadata. Each poll runs on a worker
 * pool, so indexing a huge file does not delay updates of the others.
 */
public final class FilePoller implements AutoCloseable {

    private final Set<LogFile> files = new CopyOnWriteArraySet<>();
    private final Set<LogFile> queued = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(daemon("poll-tick"));
    private final ExecutorService workers = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), daemon("poll-worker"));
    private ScheduledFuture<?> task;
    private long intervalMs;

    public FilePoller(long intervalMs) {
        setInterval(intervalMs);
    }

    public synchronized void setInterval(long ms) {
        ms = Math.max(50, ms);
        if (ms == intervalMs && task != null) {
            return;
        }
        intervalMs = ms;
        if (task != null) {
            task.cancel(false);
        }
        task = ticker.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
    }

    public synchronized long interval() {
        return intervalMs;
    }

    public void add(LogFile f) {
        files.add(f);
        pollNow(f);
    }

    public void remove(LogFile f) {
        files.remove(f);
    }

    public void pollNow(LogFile f) {
        if (queued.add(f)) {
            workers.execute(() -> {
                queued.remove(f);
                f.poll();
            });
        }
    }

    private void tick() {
        for (LogFile f : files) {
            if (f.isClosed()) {
                files.remove(f);
            } else if (!f.isBusy()) {
                pollNow(f);
            }
        }
    }

    @Override
    public void close() {
        ticker.shutdownNow();
        workers.shutdownNow();
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
