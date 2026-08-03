package com.nux.analysis;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.nux.protocol.UpsSnapshot;

/**
 * Battery age from the UPS's reported manufacture/replacement date. UPS batteries
 * typically last ~3 years and fail silently, so surfacing age is most of the value.
 * Pure.
 */
public final class BatteryHealth {

    public enum Status {
        /** No date reported — most consumer UPSes don't. */
        UNKNOWN,
        OK,
        AGING,
        REPLACE
    }

    public static final int AGING_YEARS = 3;
    public static final int REPLACE_YEARS = 4;

    private BatteryHealth() {}

    /** The battery date from {@code battery.date} / {@code battery.mfr.date}, if parseable. */
    public static Optional<LocalDate> dateOf(UpsSnapshot snapshot) {
        for (String key : new String[] {"battery.date", "battery.mfr.date", "battery.date.maintenance"}) {
            String raw = snapshot.vars().get(key);
            Optional<LocalDate> parsed = parseDate(raw);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    /**
     * Parses the date formats UPS firmware actually emits: ISO, {@code yyyy/MM/dd},
     * {@code MM/dd/yyyy} (or {@code dd/MM/yyyy} when the first part exceeds 12), and
     * {@code yyyyMMdd}. Two-digit years are rejected as too ambiguous to guess.
     */
    public static Optional<LocalDate> parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim();
        try {
            if (s.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                return Optional.of(LocalDate.parse(normalizeIso(s, "-")));
            }
            if (s.matches("\\d{4}/\\d{1,2}/\\d{1,2}")) {
                return Optional.of(LocalDate.parse(normalizeIso(s.replace('/', '-'), "-")));
            }
            if (s.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
                String[] p = s.split("/");
                int a = Integer.parseInt(p[0]);
                int b = Integer.parseInt(p[1]);
                int year = Integer.parseInt(p[2]);
                // Ambiguous by nature; >12 in the first slot can only be a day.
                int month = a > 12 ? b : a;
                int day = a > 12 ? a : b;
                return Optional.of(LocalDate.of(year, month, day));
            }
            if (s.matches("\\d{8}")) {
                return Optional.of(LocalDate.of(
                        Integer.parseInt(s.substring(0, 4)),
                        Integer.parseInt(s.substring(4, 6)),
                        Integer.parseInt(s.substring(6, 8))));
            }
        } catch (NumberFormatException | java.time.DateTimeException e) {
            // DateTimeParseException extends DateTimeException, so the latter covers both
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static String normalizeIso(String s, String sep) {
        String[] p = s.split(sep);
        return String.format("%04d-%02d-%02d", Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    /** Whole months between the battery date and today (negative dates in the future → 0). */
    public static long ageMonths(LocalDate batteryDate, LocalDate today) {
        long months = ChronoUnit.MONTHS.between(batteryDate, today);
        return Math.max(0, months);
    }

    /** Health from age, escalated to REPLACE whenever the UPS raises its own RB flag. */
    public static Status status(Optional<LocalDate> batteryDate, LocalDate today, boolean replaceFlag) {
        if (replaceFlag) {
            return Status.REPLACE;
        }
        if (batteryDate.isEmpty()) {
            return Status.UNKNOWN;
        }
        long months = ageMonths(batteryDate.get(), today);
        if (months >= REPLACE_YEARS * 12L) {
            return Status.REPLACE;
        }
        return months >= AGING_YEARS * 12L ? Status.AGING : Status.OK;
    }

    /** "2 y 4 mo" — a compact age label. */
    public static String ageLabel(long months) {
        long years = months / 12;
        long rest = months % 12;
        if (years == 0) {
            return rest + " mo";
        }
        return rest == 0 ? years + " y" : years + " y " + rest + " mo";
    }
}
