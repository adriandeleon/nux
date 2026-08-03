package com.nux.store;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** One recorded power/connection event. */
public record UpsEvent(long epochSecond, String ups, String severity, String title, String message) {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public static UpsEvent now(String ups, boolean critical, String title, String message) {
        return new UpsEvent(Instant.now().getEpochSecond(), ups, critical ? "critical" : "info", title, message);
    }

    public String displayTime() {
        return DISPLAY.format(Instant.ofEpochSecond(epochSecond));
    }

    public boolean critical() {
        return "critical".equals(severity);
    }

    public String toCsv() {
        return Csv.join(Long.toString(epochSecond), ups, severity, title, message);
    }

    /** Parses one line; null when blank or unparseable. */
    public static UpsEvent parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        List<String> f = Csv.split(line);
        if (f.size() < 5) {
            return null;
        }
        try {
            return new UpsEvent(Long.parseLong(f.get(0).trim()), f.get(1), f.get(2), f.get(3), f.get(4));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
