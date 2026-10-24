package com.nux.tray;

import static com.nux.i18n.Messages.tr;

import java.util.Locale;

/** Which number the tray icon shows. Persisted by its lowercase {@link #key()}. */
public enum TrayMetric {
    BATTERY,
    LOAD,
    RUNTIME;

    /** The display name, from the {@code tray.metric.<key>} catalogue entry. */
    public String label() {
        return tr("tray.metric." + key());
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
