package com.nux.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpsStatusTest {

    @Test
    void onlineIsOk() {
        UpsStatus s = UpsStatus.parse("OL");
        assertTrue(s.online());
        assertEquals(UpsStatus.Severity.OK, s.severity());
        assertEquals("Online", s.label());
    }

    @Test
    void onlineChargingIsOk() {
        UpsStatus s = UpsStatus.parse("OL CHRG");
        assertTrue(s.charging());
        assertEquals(UpsStatus.Severity.OK, s.severity());
        assertEquals("Online, charging", s.label());
    }

    @Test
    void onBatteryWarns() {
        UpsStatus s = UpsStatus.parse("OB DISCHRG");
        assertTrue(s.onBattery());
        assertFalse(s.online());
        assertEquals(UpsStatus.Severity.WARNING, s.severity());
        assertEquals("On battery", s.label());
    }

    @Test
    void lowBatteryIsCritical() {
        UpsStatus s = UpsStatus.parse("OB LB");
        assertEquals(UpsStatus.Severity.CRITICAL, s.severity());
        assertEquals("On battery — LOW", s.label());
    }

    @Test
    void forcedShutdownIsCritical() {
        UpsStatus s = UpsStatus.parse("FSD OB LB");
        assertTrue(s.forcedShutdown());
        assertEquals(UpsStatus.Severity.CRITICAL, s.severity());
        assertEquals("Forced shutdown", s.label());
    }

    @Test
    void replaceBatteryWarnsEvenWhenOnline() {
        UpsStatus s = UpsStatus.parse("OL RB");
        assertTrue(s.replaceBattery());
        assertEquals(UpsStatus.Severity.WARNING, s.severity());
    }

    @Test
    void unknownStatusNeverReadsAsOk() {
        assertEquals(UpsStatus.Severity.WARNING, UpsStatus.parse("").severity());
        assertEquals(UpsStatus.Severity.WARNING, UpsStatus.parse("WEIRD").severity());
        assertEquals("Unknown", UpsStatus.parse(null).label());
    }

    @Test
    void parsingIsCaseInsensitiveAndWhitespaceTolerant() {
        UpsStatus s = UpsStatus.parse("  ol   chrg ");
        assertTrue(s.online());
        assertTrue(s.charging());
    }
}
