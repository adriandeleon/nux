package com.nux.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file side of {@link EventLog}: persistence, ordering, and the size cap. */
class EventLogIoTest {

    @Test
    void appendsAreReadBackNewestFirst(@TempDir Path dir) {
        EventLog log = new EventLog(dir);
        log.append(new UpsEvent(1000, "cp", "info", "Power restored", "Back on utility power"));
        log.append(new UpsEvent(2000, "cp", "critical", "On battery power", "Utility power lost"));
        log.awaitWrites();

        List<UpsEvent> events = log.load();
        assertEquals(2, events.size());
        assertEquals("On battery power", events.getFirst().title(), "newest first — the UI reads top-down");
        assertTrue(events.getFirst().critical());
    }

    @Test
    void messagesWithCommasAndQuotesSurviveTheRoundTrip(@TempDir Path dir) {
        EventLog log = new EventLog(dir);
        String message = "Lost contact: \"upsd\", retrying, again";
        log.append(new UpsEvent(1000, "cp", "info", "Lost server contact", message));
        log.awaitWrites();

        assertEquals(message, log.load().getFirst().message());
    }

    @Test
    void loadOfAMissingFileIsEmpty(@TempDir Path dir) {
        assertEquals(List.of(), new EventLog(dir).load());
    }

    @Test
    void theLogIsCappedSoItCannotGrowForever(@TempDir Path dir) {
        EventLog log = new EventLog(dir, 10);
        for (int i = 1; i <= 40; i++) {
            log.append(new UpsEvent(i, "cp", "info", "event " + i, "m"));
        }
        log.awaitWrites();

        List<UpsEvent> events = log.load();
        assertTrue(events.size() <= 10, "expected the cap to hold, got " + events.size());
        // Trimming keeps the newest rows, and the very latest must always be there.
        assertEquals("event 40", events.getFirst().title());
    }

    @Test
    void theCapHoldsAcrossManyShortSessions(@TempDir Path dir) {
        // Each launch used to start its trim counter at zero, so a few events per session
        // never reached the trim interval and the file grew forever.
        for (int session = 0; session < 40; session++) {
            EventLog log = new EventLog(dir, 50); // trims every 5 appends; 2 per session never got there
            log.append(new UpsEvent(session, "cp", "info", "event " + session, "m"));
            log.append(new UpsEvent(session, "cp", "info", "event " + session + "b", "m"));
            log.awaitWrites();
            log.shutdown();
        }
        assertTrue(new EventLog(dir, 50).load().size() <= 51, "the cap overshoots by at most one append");
    }

    @Test
    void exportPathIsTheFileTheUiCopies(@TempDir Path dir) {
        EventLog log = new EventLog(dir);
        log.append(new UpsEvent(1000, "cp", "info", "t", "m"));
        log.awaitWrites();
        assertTrue(java.nio.file.Files.isRegularFile(log.file()));
    }
}
