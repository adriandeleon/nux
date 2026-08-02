package com.nux.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class UpsSnapshotTest {

    private static UpsSnapshot snapshot(Map<String, String> vars) {
        return new UpsSnapshot("cp", vars, Instant.EPOCH);
    }

    @Test
    void typedAccessorsParseNumbers() {
        UpsSnapshot s = snapshot(Map.of(
                "battery.charge", "87",
                "ups.load", "23",
                "battery.runtime", "2280",
                "ups.status", "OL CHRG"));
        assertEquals(Optional.of(87.0), s.chargePercent());
        assertEquals(Optional.of(23.0), s.loadPercent());
        assertEquals(Optional.of(2280.0), s.runtimeSeconds());
        assertTrue(s.status().online());
    }

    @Test
    void missingOrMalformedNumbersAreEmpty() {
        UpsSnapshot s = snapshot(Map.of("battery.charge", "n/a"));
        assertEquals(Optional.empty(), s.chargePercent());
        assertEquals(Optional.empty(), s.loadPercent());
    }

    @Test
    void modelLabelPrefersDeviceKeysAndFallsBack() {
        assertEquals(
                "CyberPower Value 2200E",
                snapshot(Map.of("device.mfr", "CyberPower", "device.model", "Value 2200E")).modelLabel());
        assertEquals("CP1500", snapshot(Map.of("ups.model", "CP1500")).modelLabel());
        assertEquals("", snapshot(Map.of()).modelLabel());
    }
}
