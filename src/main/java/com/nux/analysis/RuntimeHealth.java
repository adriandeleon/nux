package com.nux.analysis;

import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus.Severity;

/**
 * How worrying the remaining runtime is, measured against the UPS's own low-runtime
 * threshold rather than a fixed gauge scale. The gauge used to colour by how full its
 * 60-minute arc was, so a healthy 27 minutes on mains showed amber.
 */
public final class RuntimeHealth {

    /** When the UPS doesn't report battery.runtime.low: a common driver default. */
    static final double DEFAULT_LOW_SECONDS = 300;

    private RuntimeHealth() {}

    /** CRITICAL at or below the low threshold, WARNING within twice it, else OK. */
    public static Severity of(UpsSnapshot snapshot, double runtimeSeconds) {
        double low = snapshot.number("battery.runtime.low").filter(v -> v > 0).orElse(DEFAULT_LOW_SECONDS);
        if (runtimeSeconds <= low) {
            return Severity.CRITICAL;
        }
        if (runtimeSeconds <= 2 * low) {
            return Severity.WARNING;
        }
        return Severity.OK;
    }
}
