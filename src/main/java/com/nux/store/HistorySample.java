package com.nux.store;

import java.util.List;

import com.nux.protocol.UpsSnapshot;

/**
 * One persisted measurement. Missing values are NaN and round-trip as empty CSV fields,
 * so a UPS that doesn't report a variable leaves a gap rather than a fake zero.
 */
public record HistorySample(
        long epochSecond, double charge, double load, double runtimeSeconds, double inputVoltage, String status) {

    public static HistorySample of(UpsSnapshot snapshot) {
        return new HistorySample(
                snapshot.at().getEpochSecond(),
                snapshot.chargePercent().orElse(Double.NaN),
                snapshot.loadPercent().orElse(Double.NaN),
                snapshot.runtimeSeconds().orElse(Double.NaN),
                snapshot.number("input.voltage").orElse(Double.NaN),
                String.join(" ", snapshot.status().flags()));
    }

    public String toCsv() {
        return Csv.join(Long.toString(epochSecond), num(charge), num(load), num(runtimeSeconds), num(inputVoltage), status);
    }

    /** Parses one line; returns null for a blank, short, or unparseable line (files are append-only and may be torn). */
    public static HistorySample parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        List<String> f = Csv.split(line);
        if (f.size() < 6) {
            return null;
        }
        try {
            return new HistorySample(
                    Long.parseLong(f.get(0).trim()),
                    dbl(f.get(1)),
                    dbl(f.get(2)),
                    dbl(f.get(3)),
                    dbl(f.get(4)),
                    f.get(5));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String num(double v) {
        if (Double.isNaN(v)) {
            return "";
        }
        // One decimal is plenty for UPS telemetry and keeps the file compact.
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static double dbl(String s) {
        String t = s.trim();
        return t.isEmpty() ? Double.NaN : Double.parseDouble(t);
    }
}
