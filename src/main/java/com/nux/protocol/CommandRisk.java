package com.nux.protocol;

import java.util.Locale;

/**
 * What an instant command can do to the equipment behind the UPS, judged from its NUT
 * name. The confirmation dialog uses it to stop a {@code load.off} being confirmed as
 * casually as a {@code beeper.toggle}. Pure — returns catalogue keys, not text.
 */
public enum CommandRisk {
    NONE(null),
    /** Turns the output off (load.off, load.cycle, shutdown.*, and their outlet.N forms). */
    CUTS_POWER("risk.cutsPower"),
    /** Runs the battery down on purpose (runtime calibration, deep test, simulated failure). */
    DRAINS_BATTERY("risk.drainsBattery"),
    /** Leaves the load on mains with no battery behind it. */
    UNPROTECTED("risk.unprotected");

    private final String bodyKey;

    CommandRisk(String bodyKey) {
        this.bodyKey = bodyKey;
    }

    /** The catalogue key describing the consequence; null for {@link #NONE}. */
    public String bodyKey() {
        return bodyKey;
    }

    public static CommandRisk of(String command) {
        String c = command.toLowerCase(Locale.ROOT);
        if (c.endsWith(".stop")) {
            return NONE; // shutdown.stop, calibrate.stop, bypass.stop… all undo something risky
        }
        // Dotted on both ends so "outlet.1.load.off" and "load.off.delay" match by segment,
        // never by substring.
        String dotted = "." + c + ".";
        if (dotted.contains(".load.off.") || dotted.contains(".load.cycle.") || dotted.contains(".shutdown.")) {
            return CUTS_POWER;
        }
        if (dotted.contains(".bypass.start.")) {
            return UNPROTECTED;
        }
        if (dotted.contains(".calibrate.start.")
                || dotted.contains(".test.battery.start.deep.")
                || dotted.contains(".test.failure.start.")) {
            return DRAINS_BATTERY;
        }
        return NONE;
    }
}
