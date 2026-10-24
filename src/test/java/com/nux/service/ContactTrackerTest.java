package com.nux.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ContactTrackerTest {

    private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    @Test
    void aBlipThatRecoversWithinTheThresholdIsNeverAnnounced() {
        // The common case in a real log: upsd restarts and the next tick reconnects.
        ContactTracker tracker = new ContactTracker();
        tracker.connected(at(0));
        assertFalse(tracker.failed(at(5)));
        assertFalse(tracker.failed(at(10)));
        assertEquals(Optional.empty(), tracker.connected(at(15)));
    }

    @Test
    void aSustainedLossIsAnnouncedOnceAndItsRecoveryTimed() {
        ContactTracker tracker = new ContactTracker();
        tracker.connected(at(0));
        assertFalse(tracker.failed(at(5)));
        assertFalse(tracker.failed(at(10)));
        assertTrue(tracker.failed(at(15)));
        assertFalse(tracker.failed(at(20)), "announced once, not on every later tick");
        assertFalse(tracker.failed(at(125)));
        assertEquals(Optional.of(Duration.ofSeconds(125)), tracker.connected(at(130)));
    }

    @Test
    void aServerThatNeverAnsweredIsNotLost() {
        ContactTracker tracker = new ContactTracker();
        for (int i = 1; i <= 10; i++) {
            assertFalse(tracker.failed(at(i * 5L)));
        }
    }

    @Test
    void resetForgetsAPendingOutage() {
        ContactTracker tracker = new ContactTracker();
        tracker.connected(at(0));
        tracker.failed(at(5));
        tracker.failed(at(10));
        tracker.reset();
        assertFalse(tracker.failed(at(15)), "a switched-to server hasn't been talked to yet");
    }

    @Test
    void durationsAreCompact() {
        assertEquals("45s", ContactTracker.format(Duration.ofSeconds(45)));
        assertEquals("3m 05s", ContactTracker.format(Duration.ofSeconds(185)));
        assertEquals("2h 07m", ContactTracker.format(Duration.ofMinutes(127)));
    }
}
