package com.nux.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * When the next scheduled battery self-test should run. Pure: the caller supplies
 * "now", so the whole policy is testable without waiting for a clock.
 */
public final class TestSchedule {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private TestSchedule() {}

    /** Parses {@code HH:mm}; anything unparseable falls back to 03:00 (a quiet hour). */
    public static LocalTime parseTime(String raw) {
        try {
            return LocalTime.parse(raw == null ? "" : raw.trim());
        } catch (Exception e) {
            return LocalTime.of(3, 0);
        }
    }

    /**
     * The instant the next test is scheduled for — in the past means overdue.
     * A never-run schedule starts at the next occurrence of the configured time, so
     * enabling the feature never fires a surprise test immediately.
     */
    public static Instant nextRun(long lastRunEpoch, int intervalDays, LocalTime time, ZoneId zone, Instant now) {
        int interval = Math.max(1, intervalDays);
        if (lastRunEpoch <= 0) {
            Instant todayAt = LocalDate.ofInstant(now, zone).atTime(time).atZone(zone).toInstant();
            return todayAt.isAfter(now) ? todayAt : todayAt.plusSeconds(86400);
        }
        LocalDate lastDate = LocalDate.ofInstant(Instant.ofEpochSecond(lastRunEpoch), zone);
        return lastDate.plusDays(interval).atTime(time).atZone(zone).toInstant();
    }

    public static boolean isDue(long lastRunEpoch, int intervalDays, LocalTime time, ZoneId zone, Instant now) {
        return !nextRun(lastRunEpoch, intervalDays, time, zone, now).isAfter(now);
    }

    /** "2026-09-01 03:00" — for the settings/status display. */
    public static String describeNext(Optional<Instant> next) {
        return next.map(DISPLAY::format).orElse("—");
    }
}
