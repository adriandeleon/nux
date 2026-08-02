package com.nux.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatusTransitionsTest {

    private static StatusTransitions.Alert alert(String before, String now) {
        return StatusTransitions.alert(UpsStatus.parse(before), UpsStatus.parse(now));
    }

    @Test
    void powerLossAlertsCritically() {
        StatusTransitions.Alert a = alert("OL", "OB DISCHRG");
        assertEquals("On battery power", a.title());
        assertTrue(a.critical());
    }

    @Test
    void powerRestoredAlertsNormally() {
        StatusTransitions.Alert a = alert("OB DISCHRG", "OL CHRG");
        assertEquals("Power restored", a.title());
        assertFalse(a.critical());
    }

    @Test
    void lowBatteryOutranksOnBattery() {
        // Going straight OL → OB+LB must report the LOW alert, not the milder on-battery one.
        StatusTransitions.Alert a = alert("OL", "OB LB");
        assertEquals("UPS battery LOW", a.title());
        assertTrue(a.critical());
    }

    @Test
    void forcedShutdownOutranksEverything() {
        StatusTransitions.Alert a = alert("OB LB", "FSD OB LB");
        assertEquals("Forced shutdown", a.title());
    }

    @Test
    void replaceBatteryAlertsOnce() {
        assertEquals("Replace UPS battery", alert("OL", "OL RB").title());
        assertNull(alert("OL RB", "OL RB")); // no re-alert while the flag persists
    }

    @Test
    void steadyStatesAreQuiet() {
        assertNull(alert("OL", "OL"));
        assertNull(alert("OL", "OL CHRG")); // charging toggles are routine, not alerts
        assertNull(alert("OB", "OB DISCHRG"));
    }

    @Test
    void firstPollHasNoBaseline() {
        assertNull(StatusTransitions.alert(null, UpsStatus.parse("OB")));
    }
}
