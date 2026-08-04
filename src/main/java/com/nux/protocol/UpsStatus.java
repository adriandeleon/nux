package com.nux.protocol;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.nux.i18n.Messages;

/**
 * The decoded {@code ups.status} flag set ({@code OL}, {@code OB}, {@code LB}, {@code CHRG}, ...).
 * Pure; the UI derives its pill text and coloring from {@link #label()} and {@link #severity()}.
 */
public record UpsStatus(Set<String> flags) {

    public enum Severity {
        OK,
        WARNING,
        CRITICAL
    }

    /** Parses the raw space-separated {@code ups.status} value ("OL CHRG"). */
    public static UpsStatus parse(String raw) {
        Set<String> flags = new TreeSet<>();
        if (raw != null) {
            for (String token : raw.trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    flags.add(token.toUpperCase(Locale.ROOT));
                }
            }
        }
        return new UpsStatus(Set.copyOf(flags));
    }

    public boolean online() {
        return flags.contains("OL");
    }

    public boolean onBattery() {
        return flags.contains("OB");
    }

    public boolean lowBattery() {
        return flags.contains("LB");
    }

    public boolean charging() {
        return flags.contains("CHRG");
    }

    public boolean forcedShutdown() {
        return flags.contains("FSD");
    }

    public boolean replaceBattery() {
        return flags.contains("RB");
    }

    public boolean overloaded() {
        return flags.contains("OVER");
    }

    public Severity severity() {
        if (forcedShutdown() || lowBattery() || flags.contains("OFF")) {
            return Severity.CRITICAL;
        }
        if (onBattery() || replaceBattery() || overloaded() || flags.contains("BYPASS") || flags.contains("ALARM")) {
            return Severity.WARNING;
        }
        if (online()) {
            return Severity.OK;
        }
        return Severity.WARNING; // unknown/empty status is worth a look, never a green light
    }

    /**
     * The catalogue key for the dominant state, or null when the status is a flag
     * combination we have no wording for (the caller then shows the raw flags).
     * Pure — the translation happens in {@link #label()}, so the decision stays testable.
     */
    public String labelKey() {
        if (flags.isEmpty()) {
            return "status.unknown";
        }
        if (forcedShutdown()) {
            return "status.forcedShutdown";
        }
        if (onBattery() && lowBattery()) {
            return "status.onBatteryLow";
        }
        if (onBattery()) {
            return "status.onBattery";
        }
        if (online() && charging()) {
            return "status.onlineCharging";
        }
        if (online()) {
            return "status.online";
        }
        return null;
    }

    /** A short human label for the dominant state, in the active language. */
    public String label() {
        String key = labelKey();
        return key == null ? String.join(" ", flags) : Messages.tr(key);
    }
}
