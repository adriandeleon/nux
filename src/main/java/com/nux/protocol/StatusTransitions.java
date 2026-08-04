package com.nux.protocol;

/**
 * Pure detection of alert-worthy {@code ups.status} transitions between two polls.
 * Ordered by severity: a transition that is simultaneously FSD+LB+OB reports as FSD.
 *
 * <p>Alerts carry catalogue <em>keys</em> rather than text, so the decision stays pure
 * and testable and the wording is resolved in the user's language at the edge.
 */
public final class StatusTransitions {

    /** An alert to surface; {@code critical} maps to notification urgency. */
    public record Alert(String titleKey, String bodyKey, boolean critical) {}

    private StatusTransitions() {}

    /** The alert for {@code before → now}, or null when the change isn't alert-worthy. */
    public static Alert alert(UpsStatus before, UpsStatus now) {
        if (before == null || now == null) {
            return null;
        }
        if (!before.forcedShutdown() && now.forcedShutdown()) {
            return new Alert("alert.forcedShutdown.title", "alert.forcedShutdown.body", true);
        }
        if (!before.lowBattery() && now.lowBattery()) {
            return new Alert("alert.lowBattery.title", "alert.lowBattery.body", true);
        }
        if (!before.onBattery() && now.onBattery()) {
            return new Alert("alert.onBattery.title", "alert.onBattery.body", true);
        }
        if (before.onBattery() && now.online()) {
            return new Alert("alert.powerRestored.title", "alert.powerRestored.body", false);
        }
        if (!before.replaceBattery() && now.replaceBattery()) {
            return new Alert("alert.replaceBattery.title", "alert.replaceBattery.body", false);
        }
        return null;
    }
}
