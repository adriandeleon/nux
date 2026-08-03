package com.nux.analysis;

import java.util.List;
import java.util.Optional;

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
        if (nominalWatts <= 0 || samples.size() < 2) {
            return 0;
        }
        double wattSeconds = 0;
        for (int i = 1; i < samples.size(); i++) {
            HistorySample a = samples.get(i - 1);
            HistorySample b = samples.get(i);
            long dt = b.epochSecond() - a.epochSecond();
            if (dt <= 0 || dt > MAX_GAP_SECONDS || Double.isNaN(a.load()) || Double.isNaN(b.load())) {
                continue;
            }
            double avgLoad = (a.load() + b.load()) / 2.0;
            wattSeconds += nominalWatts * avgLoad / 100.0 * dt;
        }
        return wattSeconds / 3_600_000.0;
    }

    public static double cost(double kilowattHours, double tariffPerKwh) {
        return kilowattHours * Math.max(0, tariffPerKwh);
    }
}
