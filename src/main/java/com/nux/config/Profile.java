package com.nux.config;

import java.util.Locale;

/** A saved connection: name + host/port + preferred UPS ("" = first listed) + TLS. */
public record Profile(String name, String host, int port, String ups, boolean tls) {

    /** Profile names key the properties file and join a comma list, so strip what would break that. */
    public static String sanitizeName(String raw) {
        return raw == null
                ? ""
                : raw.trim().replaceAll("[,=:\\s]+", "-").toLowerCase(Locale.ROOT);
    }
}
