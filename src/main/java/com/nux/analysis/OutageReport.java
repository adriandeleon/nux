package com.nux.analysis;

import java.util.ArrayList;
import java.util.List;

import com.nux.protocol.UpsStatus;
import com.nux.store.HistorySample;

/**
 * The on-battery stretches in a history: when each began and ended, how deep it went.
 * Pure. History always writes a row on a status change, so the first and last
 * on-battery samples are the outage's real edges, not something smoothed by the
 * once-a-minute downsampling.
 */
public final class OutageReport {

    /**
     * One outage. {@code ongoing} means the history ends on battery; {@code unclear} means
     * Nux stopped recording mid-outage (a gap over {@link EnergyEstimate#MAX_GAP_SECONDS}),
     * so the true end is unknown and {@code end} is the last sample seen.
     */
    public record Outage(
            long startEpoch,
            long endEpoch,
            boolean ongoing,
            boolean unclear,
            double startCharge,
            double lowestCharge,
            double runtimeLeftAtEnd,
            boolean reachedLowBattery) {

        public long durationSeconds() {
            return Math.max(0, endEpoch - startEpoch);
        }
    }

    private OutageReport() {}

    /** Every outage in {@code samples} (oldest first), oldest first. */
    public static List<Outage> find(List<HistorySample> samples) {
        List<Outage> out = new ArrayList<>();
        Builder current = null;
        HistorySample previous = null;
        for (HistorySample s : samples) {
            UpsStatus status = UpsStatus.parse(s.status());
            boolean onBattery = status.onBattery();
            if (current != null && previous != null
                    && s.epochSecond() - previous.epochSecond() > EnergyEstimate.MAX_GAP_SECONDS) {
                out.add(current.unclear(previous));
                current = null;
            }
            if (onBattery) {
                if (current == null) {
                    current = new Builder(s);
                }
                current.add(s, status);
            } else if (current != null) {
                out.add(current.endedAt(s));
                current = null;
            }
            previous = s;
        }
        if (current != null) {
            out.add(current.ongoing(previous));
        }
        return out;
    }

    private static final class Builder {
        private final long start;
        private final double startCharge;
        private double lowest = Double.NaN;
        private double runtime = Double.NaN;
        private boolean lowBattery;

        Builder(HistorySample first) {
            start = first.epochSecond();
            startCharge = first.charge();
        }

        void add(HistorySample s, UpsStatus status) {
            if (!Double.isNaN(s.charge())) {
                lowest = Double.isNaN(lowest) ? s.charge() : Math.min(lowest, s.charge());
            }
            if (!Double.isNaN(s.runtimeSeconds())) {
                runtime = s.runtimeSeconds();
            }
            lowBattery |= status.lowBattery();
        }

        Outage endedAt(HistorySample backOnMains) {
            return new Outage(start, backOnMains.epochSecond(), false, false, startCharge, lowest, runtime, lowBattery);
        }

        Outage unclear(HistorySample lastSeen) {
            return new Outage(start, lastSeen.epochSecond(), false, true, startCharge, lowest, runtime, lowBattery);
        }

        Outage ongoing(HistorySample lastSeen) {
            return new Outage(start, lastSeen.epochSecond(), true, false, startCharge, lowest, runtime, lowBattery);
        }
    }
}
