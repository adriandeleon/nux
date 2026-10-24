package com.nux.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

class HistoryExportTest {

    @Test
    void writesAHeaderAReadableTimeAndEmptyCellsForMissingReadings() {
        String csv = HistoryExport.csv(
                List.of(
                        new HistorySample(1_785_724_427L, 100, 35, 1200, 119, "OL"),
                        new HistorySample(1_785_724_487L, 99.5, Double.NaN, 1150, 120, "OB DISCHRG")),
                ZoneOffset.UTC);
        assertEquals(
                HistoryExport.HEADER + "\n"
                        + "2026-08-03 02:33:47,1785724427,100,35,1200,119,OL\n"
                        + "2026-08-03 02:34:47,1785724487,99.5,,1150,120,OB DISCHRG\n",
                csv);
    }
}
