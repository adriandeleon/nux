package com.nux.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.nux.analysis.OutageReport.Outage;
import com.nux.store.HistorySample;

class OutageReportTest {

    private static HistorySample at(long t, double charge, double runtime, String status) {
        return new HistorySample(t, charge, 30, runtime, 120, status);
    }

    @Test
    void anOutageRunsFromTheFirstOnBatteryRowToTheRowBackOnMains() {
        List<Outage> outages = OutageReport.find(List.of(
                at(0, 100, 1800, "OL"),
                at(60, 100, 1800, "OL"),
                at(100, 99, 1700, "OB DISCHRG"),
                at(160, 92, 1400, "OB DISCHRG"),
                at(220, 85, 1100, "OB DISCHRG"),
                at(250, 85, 1100, "OL CHRG"),
                at(310, 87, 1200, "OL CHRG")));
        assertEquals(1, outages.size());
        Outage o = outages.getFirst();
        assertEquals(100, o.startEpoch());
        assertEquals(250, o.endEpoch());
        assertEquals(150, o.durationSeconds());
        assertEquals(99, o.startCharge());
        assertEquals(85, o.lowestCharge());
        assertEquals(1100, o.runtimeLeftAtEnd());
        assertFalse(o.ongoing() || o.unclear() || o.reachedLowBattery());
    }

    @Test
    void separateOutagesAreSeparateAndLowBatteryIsNoted() {
        List<Outage> outages = OutageReport.find(List.of(
                at(0, 100, 1800, "OB"),
                at(30, 95, 1600, "OL"),
                at(500, 100, 1800, "OB"),
                at(560, 20, 200, "OB LB"),
                at(600, 20, 200, "OL")));
        assertEquals(2, outages.size());
        assertFalse(outages.get(0).reachedLowBattery());
        assertTrue(outages.get(1).reachedLowBattery());
        assertEquals(20, outages.get(1).lowestCharge());
    }

    @Test
    void historyEndingOnBatteryIsOngoing() {
        Outage o = OutageReport.find(List.of(at(0, 100, 1800, "OL"), at(10, 98, 1700, "OB"), at(70, 95, 1500, "OB")))
                .getFirst();
        assertTrue(o.ongoing());
        assertEquals(60, o.durationSeconds());
    }

    @Test
    void aRecordingGapMidOutageMakesItsEndUnclear() {
        // Nux was closed during the outage: we know it lasted at least this long, no more.
        List<Outage> outages = OutageReport.find(List.of(
                at(0, 100, 1800, "OB"), at(60, 95, 1600, "OB"), at(60 + 3600, 100, 1800, "OL")));
        assertEquals(1, outages.size());
        assertTrue(outages.getFirst().unclear());
        assertEquals(60, outages.getFirst().endEpoch());
    }

    @Test
    void noOutageNoRows() {
        assertEquals(List.of(), OutageReport.find(List.of(at(0, 100, 1800, "OL"), at(60, 100, 1800, "OL CHRG"))));
        assertEquals(List.of(), OutageReport.find(List.of()));
    }
}
