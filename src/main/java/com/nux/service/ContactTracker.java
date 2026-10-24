package com.nux.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides when losing the server is worth telling the user about. Pure: the caller feeds
 * it poll outcomes and "now", so the policy is testable without sockets or clocks.
 *
 * <ul>
 *   <li>A loss is announced only after {@link #LOSS_THRESHOLD} consecutive failed polls —
 *       a upsd restart that recovers on the next tick is not an outage, and announcing
 *       each one buried the real events in the log.
 *   <li>Only a server we were actually talking to can be "lost": a first connection that
 *       never succeeds is shown on the dashboard, not raised as an alert.
 *   <li>Restoration is reported only for an announced loss, with how long it lasted.
 * </ul>
 */
public final class ContactTracker {

    /** Consecutive failed polls before a loss is announced: ~10 s at the 5 s cadence. */
    public static final int LOSS_THRESHOLD = 3;

    private boolean everConnected;
    private int failures;
    private Instant firstFailure;
    private boolean announced;

    /** A fresh target (connect, UPS or server switch, disconnect): forget everything. */
    public void reset() {
        everConnected = false;
        failures = 0;
        firstFailure = null;
        announced = false;
    }

    /** A failed poll; true exactly once per outage, when the loss should be announced. */
    public boolean failed(Instant now) {
        if (failures++ == 0) {
            firstFailure = now;
        }
        if (everConnected && !announced && failures >= LOSS_THRESHOLD) {
            announced = true;
            return true;
        }
        return false;
    }

    /** A (re)connection; the outage's length when an announced loss just ended. */
    public Optional<Duration> connected(Instant now) {
        Optional<Duration> restored = announced && firstFailure != null
                ? Optional.of(Duration.between(firstFailure, now))
                : Optional.empty();
        everConnected = true;
        failures = 0;
        firstFailure = null;
        announced = false;
        return restored;
    }

    /** "45s", "3m 20s", "2h 05m" — compact and language-neutral, for event details. */
    public static String format(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return (s / 60) + "m " + String.format(java.util.Locale.ROOT, "%02ds", s % 60);
        }
        return (s / 3600) + "h " + String.format(java.util.Locale.ROOT, "%02dm", (s % 3600) / 60);
    }
}
