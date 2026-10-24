package com.nux.store;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * History as a spreadsheet-friendly CSV: a header row, a readable local time beside the
 * epoch, and empty cells (never a fake zero) for readings the UPS didn't report. Numbers
 * are written with a dot whatever the UI language, as CSV readers expect. Pure.
 */
public final class HistoryExport {

    public static final String HEADER = "time,epoch,charge_pct,load_pct,runtime_s,input_v,status";

    private HistoryExport() {}

    public static String csv(List<HistorySample> samples, ZoneId zone) {
        DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(zone);
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (HistorySample s : samples) {
            out.append(time.format(Instant.ofEpochSecond(s.epochSecond()))).append(',').append(s.toCsv()).append('\n');
        }
        return out.toString();
    }
}
