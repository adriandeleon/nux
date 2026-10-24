package com.nux.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

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
     * The instant the next test is scheduled for — in the past means overdue — or empty
     * while the schedule has never been armed.
     *
     * <p>Two candidates, and the later one wins: {@code intervalDays} after the last run,
     * and the first occurrence of {@code time} after the schedule was armed. The armed
     * instant is persisted, so a never-run schedule has a fixed first run that genuinely
     * comes due (recomputing it from "now" on every poll pushed it forever into the
     * future), and re-enabling a lapsed schedule never fires a surprise overdue test.
     */
    public static Optional<Instant> nextRun(
            long lastRunEpoch, long armedEpoch, int intervalDays, LocalTime time, ZoneId zone) {
        Optional<Instant> afterLastRun = Optional.empty();
        if (lastRunEpoch > 0) {
            LocalDate lastDate = LocalDate.ofInstant(Instant.ofEpochSecond(lastRunEpoch), zone);
            afterLastRun = Optional.of(at(lastDate.plusDays(Math.max(1, intervalDays)), time, zone));
        }
        Optional<Instant> afterArming = Optional.empty();
        if (armedEpoch > 0) {
            Instant armed = Instant.ofEpochSecond(armedEpoch);
            LocalDate armedDay = LocalDate.ofInstant(armed, zone);
            Instant sameDay = at(armedDay, time, zone);
            afterArming = Optional.of(sameDay.isAfter(armed) ? sameDay : at(armedDay.plusDays(1), time, zone));
        }
        return Stream.of(afterLastRun, afterArming).flatMap(Optional::stream).max(Comparator.naturalOrder());
    }

    private static Instant at(LocalDate date, LocalTime time, ZoneId zone) {
        return date.atTime(time).atZone(zone).toInstant();
    }

    public static boolean isDue(
            long lastRunEpoch, long armedEpoch, int intervalDays, LocalTime time, ZoneId zone, Instant now) {
        return nextRun(lastRunEpoch, armedEpoch, intervalDays, time, zone)
                .map(next -> !next.isAfter(now))
                .orElse(false);
    }

    /** "2026-09-01 03:00" — for the settings/status display. */
    public static String describeNext(Optional<Instant> next) {
        return next.map(DISPLAY::format).orElse("—");
    }
}
