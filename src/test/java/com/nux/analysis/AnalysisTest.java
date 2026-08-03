package com.nux.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.nux.protocol.UpsSnapshot;
import com.nux.store.HistorySample;

class AnalysisTest {

    private static UpsSnapshot snapshot(Map<String, String> vars) {
        return new UpsSnapshot("cp", vars, Instant.EPOCH);
    }

    // --- BatteryHealth

    @Test
    void parsesTheDateFormatsUpsFirmwareEmits() {
        assertEquals(LocalDate.of(2019, 3, 15), BatteryHealth.parseDate("2019-03-15").orElseThrow());
        assertEquals(LocalDate.of(2019, 3, 15), BatteryHealth.parseDate("2019/03/15").orElseThrow());
        assertEquals(LocalDate.of(2019, 3, 15), BatteryHealth.parseDate("03/15/2019").orElseThrow());
        assertEquals(LocalDate.of(2019, 3, 15), BatteryHealth.parseDate("20190315").orElseThrow());
        // >12 in the first slot can only be a day, so the order flips
        assertEquals(LocalDate.of(2019, 3, 15), BatteryHealth.parseDate("15/03/2019").orElseThrow());
    }

    @Test
    void rejectsUnparseableDates() {
        assertTrue(BatteryHealth.parseDate(null).isEmpty());
        assertTrue(BatteryHealth.parseDate("").isEmpty());
        assertTrue(BatteryHealth.parseDate("not a date").isEmpty());
        assertTrue(BatteryHealth.parseDate("03/15/19").isEmpty(), "two-digit years are too ambiguous");
        assertTrue(BatteryHealth.parseDate("2019-13-01").isEmpty());
    }

    @Test
    void healthEscalatesWithAge() {
        LocalDate today = LocalDate.of(2026, 8, 2);
        assertEquals(BatteryHealth.Status.OK, BatteryHealth.status(Optional.of(today.minusMonths(6)), today, false));
        assertEquals(BatteryHealth.Status.AGING, BatteryHealth.status(Optional.of(today.minusYears(3)), today, false));
        assertEquals(BatteryHealth.Status.REPLACE, BatteryHealth.status(Optional.of(today.minusYears(5)), today, false));
        assertEquals(BatteryHealth.Status.UNKNOWN, BatteryHealth.status(Optional.empty(), today, false));
    }

    @Test
    void upsReplaceFlagOverridesAge() {
        LocalDate today = LocalDate.of(2026, 8, 2);
        assertEquals(BatteryHealth.Status.REPLACE, BatteryHealth.status(Optional.of(today), today, true));
        assertEquals(BatteryHealth.Status.REPLACE, BatteryHealth.status(Optional.empty(), today, true));
    }

    @Test
    void ageLabelReadsNaturally() {
        assertEquals("7 mo", BatteryHealth.ageLabel(7));
        assertEquals("2 y", BatteryHealth.ageLabel(24));
        assertEquals("2 y 4 mo", BatteryHealth.ageLabel(28));
    }

    @Test
    void findsBatteryDateAcrossVariableNames() {
        assertTrue(BatteryHealth.dateOf(snapshot(Map.of("battery.mfr.date", "2020-01-05"))).isPresent());
        assertTrue(BatteryHealth.dateOf(snapshot(Map.of("ups.model", "x"))).isEmpty());
    }

    // --- EnergyEstimate

    @Test
    void wattsPrefersMeasuredThenNominalTimesLoad() {
        assertEquals(
                140.0,
                EnergyEstimate.watts(snapshot(Map.of("ups.realpower", "140", "ups.load", "20")), 0).orElseThrow());
        assertEquals(
                180.0,
                EnergyEstimate.watts(snapshot(Map.of("ups.realpower.nominal", "900", "ups.load", "20")), 0)
                        .orElseThrow());
        // VA-only UPS: apply the power factor
        assertEquals(
                180.0,
                EnergyEstimate.watts(snapshot(Map.of("ups.power.nominal", "1500", "ups.load", "20")), 0)
                        .orElseThrow(),
                0.001);
        assertEquals(100.0, EnergyEstimate.watts(snapshot(Map.of("ups.load", "20")), 500).orElseThrow());
        assertTrue(EnergyEstimate.watts(snapshot(Map.of("ups.load", "20")), 0).isEmpty());
    }

    @Test
    void integratesEnergyOverASeries() {
        // 1000 W rating at 10% load = 100 W; one hour of 60-second samples = 0.1 kWh.
        List<HistorySample> hour = new java.util.ArrayList<>();
        for (int t = 0; t <= 3600; t += 60) {
            hour.add(new HistorySample(t, 100, 10, 0, 120, "OL"));
        }
        assertEquals(0.1, EnergyEstimate.kilowattHours(hour, 1000), 1e-9);
        assertEquals(0, EnergyEstimate.kilowattHours(hour, 0), "no rating means no estimate");
    }

    @Test
    void skipsGapsWhereTheAppWasClosed() {
        // Samples are written at least every minute while running, so a long gap means
        // downtime — interpolating across it would invent energy that was never measured.
        List<HistorySample> gap = List.of(
                new HistorySample(0, 100, 10, 0, 120, "OL"), new HistorySample(86400, 100, 10, 0, 120, "OL"));
        assertEquals(0, EnergyEstimate.kilowattHours(gap, 1000));
    }

    @Test
    void costScalesWithTariff() {
        assertEquals(0.05, EnergyEstimate.cost(0.25, 0.2), 1e-9);
        assertEquals(0, EnergyEstimate.cost(0.25, -1));
    }

    // --- TestSchedule

    @Test
    void firstRunWaitsForTheNextScheduledTime() {
        ZoneId utc = ZoneOffset.UTC;
        Instant now = Instant.parse("2026-08-02T10:00:00Z");
        // 03:00 already passed today, so the first run is tomorrow — never a surprise test on enable
        assertEquals(
                Instant.parse("2026-08-03T03:00:00Z"),
                TestSchedule.nextRun(0, 30, LocalTime.of(3, 0), utc, now));
        assertEquals(
                Instant.parse("2026-08-02T22:00:00Z"),
                TestSchedule.nextRun(0, 30, LocalTime.of(22, 0), utc, now));
        assertFalse(TestSchedule.isDue(0, 30, LocalTime.of(3, 0), utc, now));
    }

    @Test
    void subsequentRunsFollowTheInterval() {
        ZoneId utc = ZoneOffset.UTC;
        long lastRun = Instant.parse("2026-07-03T03:00:00Z").getEpochSecond();
        assertEquals(
                Instant.parse("2026-08-02T03:00:00Z"),
                TestSchedule.nextRun(lastRun, 30, LocalTime.of(3, 0), utc, Instant.parse("2026-07-20T00:00:00Z")));
        assertFalse(TestSchedule.isDue(lastRun, 30, LocalTime.of(3, 0), utc, Instant.parse("2026-07-20T00:00:00Z")));
        assertTrue(TestSchedule.isDue(lastRun, 30, LocalTime.of(3, 0), utc, Instant.parse("2026-08-02T03:00:01Z")));
    }

    @Test
    void badTimeFallsBackToAQuietHour() {
        assertEquals(LocalTime.of(3, 0), TestSchedule.parseTime("nonsense"));
        assertEquals(LocalTime.of(22, 30), TestSchedule.parseTime(" 22:30 "));
    }
}
