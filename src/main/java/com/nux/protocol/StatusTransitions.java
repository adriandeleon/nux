package com.nux.protocol;

/**
 * Pure detection of alert-worthy {@code ups.status} transitions between two polls.
 * Ordered by severity: a transition that is simultaneously FSD+LB+OB reports as FSD.
 */
public final class StatusTransitions {

    /** An alert to surface to the user; {@code critical} maps to notification urgency. */
    public record Alert(String title, String message, boolean critical) {}

    private StatusTransitions() {}

    /** The alert for {@code before → now}, or null when the change isn't alert-worthy. */
    public static Alert alert(UpsStatus before, UpsStatus now) {
        if (before == null || now == null) {
            return null;
        }
        if (!before.forcedShutdown() && now.forcedShutdown()) {
            return new Alert("Forced shutdown", "The UPS is shutting down its load.", true);
        }
        if (!before.lowBattery() && now.lowBattery()) {
            return new Alert("UPS battery LOW", "Runtime is nearly exhausted — save your work now.", true);
        }
        if (!before.onBattery() && now.onBattery()) {
            return new Alert("On battery power", "Utility power lost — running on the UPS battery.", true);
        }
        if (before.onBattery() && now.online()) {
            return new Alert("Power restored", "Back on utility power.", false);
        }
        if (!before.replaceBattery() && now.replaceBattery()) {
            return new Alert("Replace UPS battery", "The UPS reports its battery needs replacement.", false);
        }
        return null;
    }
}
