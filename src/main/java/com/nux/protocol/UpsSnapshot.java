package com.nux.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** One full {@code LIST VAR} poll of a UPS, with typed accessors for the dashboard. */
public record UpsSnapshot(String ups, Map<String, String> vars, Instant at) {

    public UpsStatus status() {
        return UpsStatus.parse(vars.get("ups.status"));
    }

    /**
     * A variable as a finite decimal number, or empty. Stricter than
     * {@link Double#parseDouble}, which also accepts {@code NaN}, {@code Infinity},
     * hex floats and a trailing {@code d} — junk a cheap UPS can report, and which
     * would show as 0% in the tray or write {@code Long.MAX_VALUE} into history.
     */
    public Optional<Double> number(String key) {
        String v = vars.get(key);
        if (v == null || !DECIMAL.matcher(v.trim()).matches()) {
            return Optional.empty();
        }
        double value = Double.parseDouble(v.trim());
        return Double.isFinite(value) ? Optional.of(value) : Optional.empty();
    }

    private static final java.util.regex.Pattern DECIMAL =
            java.util.regex.Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /** Battery charge, 0–100. */
    public Optional<Double> chargePercent() {
        return number("battery.charge");
    }

    /** Output load, 0–100. */
    public Optional<Double> loadPercent() {
        return number("ups.load");
    }

    /** Estimated runtime remaining, in seconds. */
    public Optional<Double> runtimeSeconds() {
        return number("battery.runtime");
    }

    /** "CyberPower Value 2200E" — manufacturer + model when present. */
    public String modelLabel() {
        String mfr = vars.getOrDefault("device.mfr", vars.getOrDefault("ups.mfr", "")).trim();
        String model = vars.getOrDefault("device.model", vars.getOrDefault("ups.model", "")).trim();
        return (mfr + " " + model).trim();
    }
}
