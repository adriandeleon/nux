package com.nux.analysis;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import com.nux.protocol.UpsSnapshot;
import com.nux.store.HistorySample;

/**
 * Power/energy estimates. Most UPSes report load only as a percentage, so watts are
 * derived from the nominal rating; the result is an estimate and is labelled as such
 * in the UI. Pure.
 */
public final class EnergyEstimate {

    /** VA→W conversion when a UPS reports only apparent power (typical for consumer units). */
    public static final double DEFAULT_POWER_FACTOR = 0.6;

    /** Gaps longer than this mean the app wasn't running; they contribute no energy. */
    public static final long MAX_GAP_SECONDS = 900;

    private EnergyEstimate() {}

    /**
     * The output rating in watts: an explicit override, else {@code ups.realpower.nominal},
     * else {@code ups.power.nominal} (VA) × power factor.
     */
    public static Optional<Double> nominalWatts(UpsSnapshot snapshot, double overrideWatts) {
        if (overrideWatts > 0) {
            return Optional.of(overrideWatts);
        }
        Optional<Double> real = snapshot.number("ups.realpower.nominal");
        if (real.isPresent() && real.get() > 0) {
            return real;
        }
        return snapshot.number("ups.power.nominal")
                .filter(va -> va > 0)
                .map(va -> va * DEFAULT_POWER_FACTOR);
    }

    /** Current draw in watts: the UPS's own reading when present, else load% × nominal. */
    public static Optional<Double> watts(UpsSnapshot snapshot, double overrideWatts) {
        Optional<Double> measured = snapshot.number("ups.realpower");
        if (measured.isPresent()) {
            return measured;
        }
        Optional<Double> nominal = nominalWatts(snapshot, overrideWatts);
        Optional<Double> load = snapshot.loadPercent();
        if (nominal.isEmpty() || load.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(nominal.get() * load.get() / 100.0);
    }

    /**
     * Energy over a sample series, trapezoid-integrating load between consecutive
     * samples. Samples with no load reading, and gaps over {@link #MAX_GAP_SECONDS}
     * (the app was closed), contribute nothing rather than being interpolated across.
     */
    public static double kilowattHours(List<HistorySample> samples, double nominalWatts) {
        double[] total = {0};
        integrate(samples, nominalWatts, (startEpoch, wattSeconds) -> total[0] += wattSeconds);
        return total[0] / WATT_SECONDS_PER_KWH;
    }

    /** A calendar period energy is grouped by; {@link #hours()} is its length for cost rates. */
    public enum Period {
        HOUR(1),
        DAY(24),
        WEEK(24 * 7),
        /** An average month: 365.25 days / 12. */
        MONTH(365.25 * 24 / 12);

        private final double hours;

        Period(double hours) {
            this.hours = hours;
        }

        public double hours() {
            return hours;
        }

        /** The start of the period containing {@code time}; weeks start on {@code firstDay}. */
        public LocalDateTime startOf(LocalDateTime time, DayOfWeek firstDay) {
            return switch (this) {
                case HOUR -> time.truncatedTo(ChronoUnit.HOURS);
                case DAY -> time.toLocalDate().atStartOfDay();
                case WEEK -> time.toLocalDate().with(TemporalAdjusters.previousOrSame(firstDay)).atStartOfDay();
                case MONTH -> time.toLocalDate().withDayOfMonth(1).atStartOfDay();
            };
        }
    }

    /** The energy measured in one period. */
    public record Bucket(LocalDateTime start, double kilowattHours) {}

    /**
     * Energy per calendar period, oldest first, by the same integration as
     * {@link #kilowattHours}. Each interval counts toward the period it starts in: at
     * most {@link #MAX_GAP_SECONDS} long, so splitting it across a boundary would change
     * nothing a person could see. Periods with no measured interval are absent, not zero —
     * the app wasn't running then, which is not the same as drawing no power.
     */
    public static List<Bucket> byPeriod(
            List<HistorySample> samples, double nominalWatts, Period period, ZoneId zone, DayOfWeek firstDay) {
        TreeMap<LocalDateTime, Double> buckets = new TreeMap<>();
        integrate(samples, nominalWatts, (startEpoch, wattSeconds) -> {
            LocalDateTime start = period.startOf(
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(startEpoch), zone), firstDay);
            buckets.merge(start, wattSeconds, Double::sum);
        });
        List<Bucket> out = new ArrayList<>();
        buckets.forEach((start, ws) -> out.add(new Bucket(start, ws / WATT_SECONDS_PER_KWH)));
        return out;
    }

    private interface IntervalSink {
        void accept(long startEpoch, double wattSeconds);
    }

    private static final double WATT_SECONDS_PER_KWH = 3_600_000.0;

    private static void integrate(List<HistorySample> samples, double nominalWatts, IntervalSink sink) {
        if (nominalWatts <= 0 || samples.size() < 2) {
            return;
        }
        for (int i = 1; i < samples.size(); i++) {
            HistorySample a = samples.get(i - 1);
            HistorySample b = samples.get(i);
            long dt = b.epochSecond() - a.epochSecond();
            if (dt <= 0 || dt > MAX_GAP_SECONDS || Double.isNaN(a.load()) || Double.isNaN(b.load())) {
                continue;
            }
            double avgLoad = (a.load() + b.load()) / 2.0;
            sink.accept(a.epochSecond(), nominalWatts * avgLoad / 100.0 * dt);
        }
    }

    public static double cost(double kilowattHours, double tariffPerKwh) {
        return kilowattHours * Math.max(0, tariffPerKwh);
    }

    /** What drawing {@code watts} steadily for one {@code period} costs. */
    public static double costPer(Period period, double watts, double tariffPerKwh) {
        return cost(Math.max(0, watts) / 1000.0 * period.hours(), tariffPerKwh);
    }
}
