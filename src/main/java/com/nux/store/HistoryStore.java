package com.nux.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Append-only per-UPS measurement history in {@code <configDir>/history/<ups>.csv}.
 *
 * <p>Samples are downsampled to {@link #MIN_INTERVAL_SECONDS} (a status change always
 * writes, so an outage is never smoothed away) and pruned to a retention window when
 * the file is opened. Writes go to a single daemon thread; reads are synchronous and
 * belong off the FX thread.
 */
public final class HistoryStore {

    /** ~1 write/minute: 30 days of one UPS is a couple of MB. */
    public static final long MIN_INTERVAL_SECONDS = 60;

    private final Path dir;
    private final int retentionDays;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nux-history");
        t.setDaemon(true);
        return t;
    });

    private long lastWrittenEpoch = Long.MIN_VALUE;
    private String lastWrittenStatus = "";

    public HistoryStore(Path configDir, int retentionDays) {
        this.dir = configDir.resolve("history");
        this.retentionDays = retentionDays;
    }

    /** Decides whether a sample earns a row: first one, status change, or the interval elapsed. Pure. */
    public static boolean shouldPersist(long lastEpoch, String lastStatus, HistorySample sample, long minInterval) {
        if (lastEpoch == Long.MIN_VALUE) {
            return true;
        }
        if (!lastStatus.equals(sample.status())) {
            return true;
        }
        return sample.epochSecond() - lastEpoch >= minInterval;
    }

    /** Records a sample if it earns a row; returns whether it was queued for writing. */
    public boolean record(String ups, HistorySample sample) {
        if (!shouldPersist(lastWrittenEpoch, lastWrittenStatus, sample, MIN_INTERVAL_SECONDS)) {
            return false;
        }
        lastWrittenEpoch = sample.epochSecond();
        lastWrittenStatus = sample.status();
        Path file = fileFor(ups);
        String line = sample.toCsv();
        writer.execute(() -> appendQuietly(file, line));
        return true;
    }

    /** Loads samples newer than {@code sinceEpoch}, oldest first. Blocking — call off the FX thread. */
    public List<HistorySample> load(String ups, long sinceEpoch) {
        Path file = fileFor(ups);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<HistorySample> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                HistorySample s = HistorySample.parse(line);
                if (s != null && s.epochSecond() >= sinceEpoch) {
                    out.add(s);
                }
            }
        } catch (IOException e) {
            return out; // a partially readable history is better than none
        }
        return out;
    }

    /** Drops rows outside the retention window; rewrites only when something was actually dropped. */
    public void prune(String ups) {
        Path file = fileFor(ups);
        if (!Files.isRegularFile(file)) {
            return;
        }
        long cutoff = Instant.now().getEpochSecond() - retentionDays * 86400L;
        try {
            List<String> kept = new ArrayList<>();
            boolean dropped = false;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                HistorySample s = HistorySample.parse(line);
                if (s == null) {
                    dropped = true;
                } else if (s.epochSecond() >= cutoff) {
                    kept.add(line);
                } else {
                    dropped = true;
                }
            }
            if (dropped) {
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(temp, kept, StandardCharsets.UTF_8);
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // keep the existing file; retention is best-effort
        }
    }

    public Path fileFor(String ups) {
        return dir.resolve(safeName(ups) + ".csv");
    }

    /**
     * A UPS name is server-supplied, so reduce it to an unambiguously safe file name.
     * Dots are dropped along with separators: keeping them would let a name like
     * {@code ../x} survive as {@code .._x}, which is harmless today only because
     * slashes are stripped — not a property worth depending on.
     */
    static String safeName(String ups) {
        String cleaned = ups == null ? "" : ups.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return cleaned.isEmpty() ? "ups" : cleaned;
    }

    private void appendQuietly(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // history is best-effort; never disturb the UI over it
        }
    }

    /** Blocks until queued writes have landed. The writer is a single FIFO thread. */
    void awaitWrites() {
        try {
            writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("history writer did not settle", e);
        }
    }

    public void shutdown() {
        writer.shutdown();
    }
}
