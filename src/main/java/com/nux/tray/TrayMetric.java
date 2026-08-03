package com.nux.tray;

import java.util.Locale;

/** Which number the tray icon shows. Persisted by its lowercase {@link #key()}. */
public enum TrayMetric {
    BATTERY("Battery %"),
    LOAD("Load %"),
    RUNTIME("Runtime minutes");

    private final String label;

    TrayMetric(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Lenient parse; anything unknown falls back to BATTERY. */
    public static TrayMetric from(String key) {
        if (key != null) {
            for (TrayMetric m : values()) {
                if (m.key().equalsIgnoreCase(key.trim())) {
                    return m;
                }
            }
        }
        return BATTERY;
    }
}
