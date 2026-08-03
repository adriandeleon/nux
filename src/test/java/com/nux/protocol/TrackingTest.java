package com.nux.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrackingTest {

    @Test
    void extractsTrackingIdFromOkReply() {
        assertEquals("f9f9f9-1234", Tracking.idOf("OK TRACKING f9f9f9-1234"));
        assertEquals("", Tracking.idOf("OK"), "a plain OK means the connection isn't tracking");
        assertEquals("", Tracking.idOf(null));
    }

    @Test
    void parsesTerminalStatuses() {
        assertEquals(Tracking.Status.SUCCESS, Tracking.parse("SUCCESS").status());
        assertEquals(Tracking.Status.PENDING, Tracking.parse("PENDING").status());
        assertTrue(Tracking.parse("SUCCESS").done());
        assertFalse(Tracking.parse("PENDING").done());
    }

    @Test
    void readsStatusAsTheLastTokenSoBothReplyShapesWork() {
        // Bare word and the prefixed form parse identically — no version sniffing needed.
        assertEquals(Tracking.Status.SUCCESS, Tracking.parse("TRACKING abc SUCCESS").status());
        assertEquals(Tracking.Status.PENDING, Tracking.parse("  pending  ").status());
    }

    @Test
    void distinguishesDriverFailureFromCannotTell() {
        Tracking.Result failed = Tracking.parse("ERR CMD-NOT-SUPPORTED");
        assertEquals(Tracking.Status.FAILED, failed.status());
        assertEquals("CMD-NOT-SUPPORTED", failed.detail());
        // These mean the server can't answer, not that the command failed — blaming the
        // command for the server's bookkeeping would report false failures.
        assertEquals(Tracking.Status.UNKNOWN, Tracking.parse("ERR UNKNOWN").status());
        assertEquals(Tracking.Status.UNKNOWN, Tracking.parse("ERR FEATURE-NOT-CONFIGURED").status());
        assertEquals(Tracking.Status.UNKNOWN, Tracking.parse("ERR FEATURE-NOT-SUPPORTED").status());
    }

    @Test
    void unrecognisedRepliesAreUnknownRatherThanFailure() {
        assertEquals(Tracking.Status.UNKNOWN, Tracking.parse("").status());
        assertEquals(Tracking.Status.UNKNOWN, Tracking.parse(null).status());
        assertEquals(Tracking.Status.UNKNOWN, Tracking.parse("something else").status());
    }
}
