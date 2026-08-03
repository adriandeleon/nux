package com.nux.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file side of {@link HistoryStore}: what actually reaches and leaves the disk. */
class HistoryStoreIoTest {

    private static HistorySample sample(long epoch, double charge, String status) {
        return new HistorySample(epoch, charge, 20, 1200, 120, status);
    }

    @Test
    void recordsAndReadsBackASample(@TempDir Path dir) {
        HistoryStore store = new HistoryStore(dir, 30);
        assertTrue(store.record("cp", sample(1000, 87, "OL")));
        store.awaitWrites();

        List<HistorySample> loaded = store.load("cp", 0);
        assertEquals(1, loaded.size());
        assertEquals(87, loaded.getFirst().charge());
        assertEquals("OL", loaded.getFirst().status());
    }

    @Test
    void downsamplesWithinTheInterval(@TempDir Path dir) {
        HistoryStore store = new HistoryStore(dir, 30);
        assertTrue(store.record("cp", sample(1000, 87, "OL")));
        assertFalse(store.record("cp", sample(1030, 88, "OL")), "30s later is inside the interval");
        assertTrue(store.record("cp", sample(1060, 89, "OL")), "60s later earns a row");
        store.awaitWrites();

        assertEquals(2, store.load("cp", 0).size());
    }

    @Test
    void aStatusChangeIsAlwaysRecorded(@TempDir Path dir) {
        HistoryStore store = new HistoryStore(dir, 30);
        store.record("cp", sample(1000, 87, "OL"));
        // A power cut one second later is the most important row in the file.
        assertTrue(store.record("cp", sample(1001, 87, "OB")));
        store.awaitWrites();

        List<HistorySample> loaded = store.load("cp", 0);
        assertEquals(2, loaded.size());
        assertEquals("OB", loaded.get(1).status());
    }

    @Test
    void loadFiltersBySinceEpoch(@TempDir Path dir) {
        HistoryStore store = new HistoryStore(dir, 30);
        store.record("cp", sample(1000, 10, "OL"));
        store.record("cp", sample(2000, 20, "OL"));
        store.record("cp", sample(3000, 30, "OL"));
        store.awaitWrites();

        assertEquals(2, store.load("cp", 2000).size());
        assertEquals(0, store.load("cp", 9999).size());
    }

    @Test
    void separateUpsesGetSeparateFiles(@TempDir Path dir) {
        HistoryStore store = new HistoryStore(dir, 30);
        store.record("cp", sample(1000, 10, "OL"));
        HistoryStore other = new HistoryStore(dir, 30);
        other.record("rack", sample(1000, 90, "OL"));
        store.awaitWrites();
        other.awaitWrites();

        assertEquals(10, store.load("cp", 0).getFirst().charge());
        assertEquals(90, other.load("rack", 0).getFirst().charge());
    }

    @Test
    void loadOfAMissingFileIsEmptyRatherThanAnError(@TempDir Path dir) {
        assertEquals(List.of(), new HistoryStore(dir, 30).load("never-seen", 0));
    }

    @Test
    void loadSkipsCorruptLines(@TempDir Path dir) throws IOException {
        HistoryStore store = new HistoryStore(dir, 30);
        Path file = store.fileFor("cp");
        Files.createDirectories(file.getParent());
        // An append-only file can be torn by a crash mid-write.
        Files.writeString(file, "1000,50,20,1200,120,OL\ngarbage\n\n2000,60,20,1200,120,OL\n", StandardCharsets.UTF_8);

        List<HistorySample> loaded = store.load("cp", 0);
        assertEquals(2, loaded.size());
        assertEquals(50, loaded.getFirst().charge());
    }

    @Test
    void pruneDropsRowsOlderThanTheRetentionWindow(@TempDir Path dir) throws IOException {
        HistoryStore store = new HistoryStore(dir, 7);
        long now = Instant.now().getEpochSecond();
        Path file = store.fileFor("cp");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                sample(now - 30 * 86400L, 10, "OL").toCsv() + "\n"
                        + sample(now - 2 * 86400L, 20, "OL").toCsv() + "\n"
                        + sample(now, 30, "OL").toCsv() + "\n",
                StandardCharsets.UTF_8);

        store.prune("cp");

        List<HistorySample> kept = store.load("cp", 0);
        assertEquals(2, kept.size(), "only rows inside the window survive");
        assertEquals(20, kept.getFirst().charge());
    }

    @Test
    void pruneLeavesAnInRangeFileUntouched(@TempDir Path dir) throws IOException {
        HistoryStore store = new HistoryStore(dir, 30);
        store.record("cp", sample(Instant.now().getEpochSecond(), 55, "OL"));
        store.awaitWrites();
        Path file = store.fileFor("cp");
        String before = Files.readString(file);

        store.prune("cp");

        assertEquals(before, Files.readString(file), "nothing to drop means no rewrite");
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")), "temp file must be cleaned up");
    }

    @Test
    void pruneOfAMissingFileIsSilent(@TempDir Path dir) {
        new HistoryStore(dir, 30).prune("never-seen");
    }
}
