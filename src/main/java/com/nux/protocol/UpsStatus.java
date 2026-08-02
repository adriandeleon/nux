package com.nux.protocol;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

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

    /** A short human label for the dominant state. */
    public String label() {
        if (flags.isEmpty()) {
            return "Unknown";
        }
        if (forcedShutdown()) {
            return "Forced shutdown";
        }
        if (onBattery() && lowBattery()) {
            return "On battery — LOW";
        }
        if (onBattery()) {
            return "On battery";
        }
        if (online() && charging()) {
            return "Online, charging";
        }
        if (online()) {
            return "Online";
        }
        return String.join(" ", flags);
    }
}
