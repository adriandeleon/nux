package com.nux.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nux.analysis.EnergyEstimate.Bucket;
import com.nux.analysis.EnergyEstimate.Period;
import com.nux.store.HistorySample;

class EnergyByPeriodTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    /** One sample a minute at {@code load}% from {@code from} for {@code minutes}. */
    private static List<HistorySample> steady(String from, int minutes, double load) {
        long start = Instant.parse(from).getEpochSecond();
        List<HistorySample> out = new ArrayList<>();
        for (int i = 0; i <= minutes; i++) {
            out.add(new HistorySample(start + i * 60L, 100, load, 1800, 120, "OL"));
        }
        return out;
    }

    @Test
    void costRatesScaleTheCurrentDrawByPeriodLength() {
        // 250 W at 0.20/kWh: 0.05/h, 1.20/day, 8.40/week, ~36.53/month
        assertEquals(0.05, EnergyEstimate.costPer(Period.HOUR, 250, 0.20), 1e-9);
        assertEquals(1.20, EnergyEstimate.costPer(Period.DAY, 250, 0.20), 1e-9);
        assertEquals(8.40, EnergyEstimate.costPer(Period.WEEK, 250, 0.20), 1e-9);
        assertEquals(36.525, EnergyEstimate.costPer(Period.MONTH, 250, 0.20), 1e-9);
        assertEquals(0, EnergyEstimate.costPer(Period.DAY, 250, 0), "no price, no cost");
    }

    @Test
    void energyIsGroupedByTheHourItWasDrawnIn() {
        // 50% of 1000 W for two hours from 10:00: 0.5 kWh in each hour
        List<Bucket> hours = EnergyEstimate.byPeriod(
                steady("2026-08-03T10:00:00Z", 120, 50), 1000, Period.HOUR, UTC, DayOfWeek.MONDAY);
        assertEquals(2, hours.size());
        assertEquals(LocalDateTime.parse("2026-08-03T10:00"), hours.get(0).start());
        assertEquals(0.5, hours.get(0).kilowattHours(), 1e-9);
        assertEquals(0.5, hours.get(1).kilowattHours(), 1e-9);
    }

    @Test
    void weeksStartOnTheLocaleFirstDayAndMonthsOnTheFirst() {
        List<HistorySample> samples = new ArrayList<>();
        samples.addAll(steady("2026-08-02T12:00:00Z", 60, 100)); // Sunday 2 Aug
        samples.addAll(steady("2026-08-03T12:00:00Z", 60, 100)); // Monday 3 Aug
        List<Bucket> mondayWeeks =
                EnergyEstimate.byPeriod(samples, 1000, Period.WEEK, UTC, DayOfWeek.MONDAY);
        assertEquals(List.of(LocalDateTime.parse("2026-07-27T00:00"), LocalDateTime.parse("2026-08-03T00:00")),
                mondayWeeks.stream().map(Bucket::start).toList());
        List<Bucket> sundayWeeks =
                EnergyEstimate.byPeriod(samples, 1000, Period.WEEK, UTC, DayOfWeek.SUNDAY);
        assertEquals(List.of(LocalDateTime.parse("2026-08-02T00:00")),
                sundayWeeks.stream().map(Bucket::start).toList(), "one week where weeks start on Sunday");
    }

    @Test
    void aMonthCollectsItsDaysAndAGapIsNotZeroUse() {
        List<HistorySample> samples = new ArrayList<>();
        samples.addAll(steady("2026-07-31T23:00:00Z", 60, 100)); // 1 kWh, ending at midnight
        samples.addAll(steady("2026-08-05T08:00:00Z", 60, 100)); // the app was closed in between
        List<Bucket> days = EnergyEstimate.byPeriod(samples, 1000, Period.DAY, UTC, DayOfWeek.MONDAY);
        assertEquals(2, days.size(), "days without measurements are absent, not 0 kWh");
        List<Bucket> months = EnergyEstimate.byPeriod(samples, 1000, Period.MONTH, UTC, DayOfWeek.MONDAY);
        assertEquals(LocalDateTime.parse("2026-07-01T00:00"), months.get(0).start());
        assertEquals(1.0, months.get(0).kilowattHours(), 1e-9);
        assertEquals(1.0, months.get(1).kilowattHours(), 1e-9);
    }

    @Test
    void theTotalOfTheBucketsIsTheRangeTotal() {
        List<HistorySample> samples = steady("2026-08-03T22:30:00Z", 180, 40);
        double total = EnergyEstimate.byPeriod(samples, 900, Period.DAY, UTC, DayOfWeek.MONDAY).stream()
                .mapToDouble(Bucket::kilowattHours)
                .sum();
        assertEquals(EnergyEstimate.kilowattHours(samples, 900), total, 1e-9);
    }
}
