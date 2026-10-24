package com.nux.config;

import java.util.Locale;

/**
 * Which power events raise a desktop notification for one UPS. The event log records
 * every event whatever the rule: this only decides what interrupts the user, e.g. muting
 * a lab UPS that is expected to sit on battery.
 */
public enum NotifyRule {
    ALL,
    /** Only events that need attention now: on battery, low battery, forced shutdown. */
    CRITICAL,
    NONE;

    public boolean allows(boolean critical) {
        return switch (this) {
            case ALL -> true;
            case CRITICAL -> critical;
            case NONE -> false;
        };
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Lenient parse; anything unknown is ALL, the safe default for an alerting app. */
    public static NotifyRule from(String key) {
        if (key != null) {
            for (NotifyRule r : values()) {
                if (r.key().equalsIgnoreCase(key.trim())) {
                    return r;
                }
            }
        }
        return ALL;
    }
}
