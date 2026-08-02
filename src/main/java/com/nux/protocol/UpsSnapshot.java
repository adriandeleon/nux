package com.nux.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** One full {@code LIST VAR} poll of a UPS, with typed accessors for the dashboard. */
public record UpsSnapshot(String ups, Map<String, String> vars, Instant at) {

    public UpsStatus status() {
        return UpsStatus.parse(vars.get("ups.status"));
    }

    public Optional<Double> number(String key) {
        String v = vars.get(key);
        if (v == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

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
