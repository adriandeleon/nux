package com.nux.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Append-only measurement history, one file per UPS <em>per server</em>:
 * {@code <configDir>/history/<ups>__<host>_<port>.csv}. Keying by name alone mixed two
 * servers' {@code ups} (the most common name there is) into one chart and one kWh sum.
 *
 * <p>Samples are downsampled to {@link #MIN_INTERVAL_SECONDS} per file (a status change
 * always writes, so an outage is never smoothed away). Writes and pruning share one
 * daemon thread, so a prune's rewrite can never swallow an append that lands mid-way;
 * reads are synchronous and belong off the FX thread.
 */
public final class HistoryStore {

    /** ~1 write/minute: 30 days of one UPS is a couple of MB. */
    public static final long MIN_INTERVAL_SECONDS = 60;

    private final Path dir;
    private volatile int retentionDays;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nux-history");
        t.setDaemon(true);
        return t;
    });

    /** Per-file downsampling state; {@link #record} is called from one thread (the FX thread). */
    private final Map<Path, HistorySample> lastWritten = new HashMap<>();

    public HistoryStore(Path configDir, int retentionDays) {
        this.dir = configDir.resolve("history");
        this.retentionDays = Math.max(1, retentionDays);
    }

    /** Applies a new retention window from the next prune on — no restart needed. */
    public void setRetentionDays(int days) {
        retentionDays = Math.max(1, days);
    }

    /**
     * Prunes every history file now and then daily. Once per launch wasn't enough: a
     * tray-resident app runs for weeks, and only the last-used UPS was ever pruned.
     */
    public void schedulePruning() {
        writer.scheduleWithFixedDelay(this::pruneAllNow, 0, 1, TimeUnit.DAYS);
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
    public boolean record(String server, String ups, HistorySample sample) {
        Path file = fileFor(server, ups);
        HistorySample last = lastWritten.get(file);
        if (!shouldPersist(
                last == null ? Long.MIN_VALUE : last.epochSecond(),
                last == null ? "" : last.status(),
                sample,
                MIN_INTERVAL_SECONDS)) {
            return false;
        }
        lastWritten.put(file, sample);
        String line = sample.toCsv();
        writer.execute(() -> {
            adoptLegacy(file, ups);
            appendQuietly(file, line);
        });
        return true;
    }

    /** Loads samples newer than {@code sinceEpoch}, oldest first. Blocking — call off the FX thread. */
    public List<HistorySample> load(String server, String ups, long sinceEpoch) {
        Path file = fileFor(server, ups);
        if (!Files.isRegularFile(file)) {
            Path legacy = legacyFileFor(ups);
            if (!Files.isRegularFile(legacy)) {
                return List.of();
            }
            file = legacy; // not adopted yet (no sample written since upgrading): read it in place
        }
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

    /** Prunes every history file; runs on the writer thread. */
    private void pruneAllNow() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".csv")).forEach(this::pruneFile);
        } catch (IOException e) {
            // retention is best-effort
        }
    }

    /** Drops rows outside the retention window; rewrites only when something was actually dropped. */
    private void pruneFile(Path file) {
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

    public Path fileFor(String server, String ups) {
        return dir.resolve(safeName(ups) + "__" + safeName(server) + ".csv");
    }

    /** Where history lived before it was keyed by server. */
    private Path legacyFileFor(String ups) {
        return dir.resolve(safeName(ups) + ".csv");
    }

    /**
     * Gives a pre-upgrade {@code <ups>.csv} to the first server that writes that UPS name,
     * so upgrading doesn't orphan the history. Writer thread only, like every write.
     */
    private void adoptLegacy(Path file, String ups) {
        Path legacy = legacyFileFor(ups);
        if (!Files.exists(file) && Files.isRegularFile(legacy)) {
            try {
                Files.move(legacy, file);
            } catch (IOException e) {
                // start a fresh file; the old one stays readable by hand
            }
        }
    }

    /**
     * A UPS name is server-supplied, so reduce it to an unambiguously safe file name
     * (the server key, {@code host:port}, goes through the same filter).
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

    /** Prunes every file on the writer thread and waits for it; for tests. */
    void pruneNow() {
        writer.execute(this::pruneAllNow);
        awaitWrites();
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
