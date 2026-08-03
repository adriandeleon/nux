package com.nux.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class StoreTest {

    @Test
    void csvEscapesOnlyWhenNeeded() {
        assertEquals("plain", Csv.escape("plain"));
        assertEquals("\"has,comma\"", Csv.escape("has,comma"));
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""));
    }

    @Test
    void csvSplitsQuotedFields() {
        assertEquals(List.of("1", "a,b", "say \"hi\"", ""), Csv.split("1,\"a,b\",\"say \"\"hi\"\"\","));
    }

    @Test
    void historySampleRoundTrips() {
        HistorySample s = new HistorySample(1785700000L, 87, 23.5, 2280, 121, "OL CHRG");
        HistorySample back = HistorySample.parse(s.toCsv());
        assertEquals(s.epochSecond(), back.epochSecond());
        assertEquals(87, back.charge());
        assertEquals(23.5, back.load());
        assertEquals("OL CHRG", back.status());
    }

    @Test
    void historySampleKeepsMissingValuesAsGaps() {
        HistorySample s = new HistorySample(1L, Double.NaN, 10, Double.NaN, Double.NaN, "OB");
        HistorySample back = HistorySample.parse(s.toCsv());
        assertTrue(Double.isNaN(back.charge()));
        assertEquals(10, back.load());
        assertTrue(Double.isNaN(back.inputVoltage()));
    }

    @Test
    void historySampleRejectsGarbage() {
        assertNull(HistorySample.parse(""));
        assertNull(HistorySample.parse("not,enough,fields"));
        assertNull(HistorySample.parse("abc,1,2,3,4,OL"));
    }

    @Test
    void persistPolicyWritesFirstStatusChangeAndInterval() {
        HistorySample at100 = new HistorySample(100, 50, 50, 0, 120, "OL");
        assertTrue(HistoryStore.shouldPersist(Long.MIN_VALUE, "", at100, 60), "first sample always persists");
        assertFalse(HistoryStore.shouldPersist(100, "OL", new HistorySample(130, 50, 50, 0, 120, "OL"), 60));
        assertTrue(HistoryStore.shouldPersist(100, "OL", new HistorySample(160, 50, 50, 0, 120, "OL"), 60));
        // A status change is the interesting moment; never smooth it away.
        assertTrue(HistoryStore.shouldPersist(100, "OL", new HistorySample(105, 50, 50, 0, 120, "OB"), 60));
    }

    @Test
    void upsNamesBecomeSafeFileNames() {
        assertEquals("cyberpower", HistoryStore.safeName("cyberpower"));
        assertEquals("rack_1", HistoryStore.safeName("rack 1"));
        assertEquals("ups", HistoryStore.safeName(""));
        assertEquals("ups", HistoryStore.safeName("   "));
        // The name comes from the server, so no traversal component may survive.
        String hostile = HistoryStore.safeName("../../etc/passwd");
        assertFalse(hostile.contains("/"));
        assertFalse(hostile.contains(".."));
    }

    @Test
    void eventRoundTripsIncludingCommas() {
        UpsEvent e = new UpsEvent(1785700000L, "cp", "critical", "On battery power", "Utility power lost, running on battery");
        UpsEvent back = UpsEvent.parse(e.toCsv());
        assertEquals(e, back);
        assertTrue(back.critical());
        assertFalse(new UpsEvent(1, "cp", "info", "t", "m").critical());
    }
}
