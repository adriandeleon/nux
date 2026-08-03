package com.nux.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The persistent power-event log in {@code <configDir>/events.csv} — append-only,
 * capped at {@link #MAX_EVENTS} rows (oldest trimmed). Writes on a daemon thread;
 * {@link #load()} is synchronous and belongs off the FX thread.
 */
public final class EventLog {

    public static final int MAX_EVENTS = 5000;

    private final Path file;
    private final int maxEvents;
    /**
     * Trimming reads the whole file, so it runs every {@code maxEvents/10} appends
     * rather than on each one; the log may overshoot the cap by that much between
     * checks, which is far cheaper than an O(n) read per event.
     */
    private final int trimInterval;

    private int appendsSinceTrim;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nux-events");
        t.setDaemon(true);
        return t;
    });

    public EventLog(Path configDir) {
        this(configDir, MAX_EVENTS);
    }

    EventLog(Path configDir, int maxEvents) {
        this.file = configDir.resolve("events.csv");
        this.maxEvents = Math.max(1, maxEvents);
        this.trimInterval = Math.max(1, this.maxEvents / 10);
    }

    public Path file() {
        return file;
    }

    public void append(UpsEvent event) {
        String line = event.toCsv();
        writer.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(
                        file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                trimIfNeeded();
            } catch (IOException e) {
                // the log is best-effort; the in-app status already showed the event
            }
        });
    }

    /** All events, newest first. */
    public List<UpsEvent> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<UpsEvent> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                UpsEvent e = UpsEvent.parse(line);
                if (e != null) {
                    out.add(e);
                }
            }
        } catch (IOException e) {
            return out;
        }
        Collections.reverse(out);
        return out;
    }

    private void trimIfNeeded() throws IOException {
        if (++appendsSinceTrim < trimInterval) {
            return;
        }
        appendsSinceTrim = 0;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() <= maxEvents) {
            return;
        }
        List<String> kept = lines.subList(lines.size() - maxEvents, lines.size());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, kept, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Blocks until queued writes have landed. The writer is a single FIFO thread. */
    void awaitWrites() {
        try {
            writer.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("event writer did not settle", e);
        }
    }

    public void shutdown() {
        writer.shutdown();
    }
}
