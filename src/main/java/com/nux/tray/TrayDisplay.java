package com.nux.tray;

import java.util.Optional;

import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;

/**
 * Pure description of what the tray icon should show: the chosen metric's number when
 * known (else the bolt glyph), colored by severity. Value-equal snapshots let the
 * caller skip redundant repaints.
 */
public record TrayDisplay(String text, UpsStatus.Severity severity, boolean connected) {

    public static TrayDisplay of(UpsSnapshot snapshot) {
        return of(snapshot, TrayMetric.BATTERY);
    }

    public static TrayDisplay of(UpsSnapshot snapshot, TrayMetric metric) {
        String text = switch (metric) {
            case BATTERY -> snapshot.chargePercent().map(TrayDisplay::round).orElse(null);
            case LOAD -> snapshot.loadPercent().map(TrayDisplay::round).orElse(null);
            // The "m" suffix marks the number as minutes, distinguishing it from the % metrics.
            case RUNTIME -> snapshot.runtimeSeconds()
                    .map(sec -> round(sec / 60) + "m")
                    .orElse(null);
        };
        return new TrayDisplay(text, snapshot.status().severity(), true);
    }

    private static String round(double value) {
        return Integer.toString((int) Math.round(value));
    }

    public static TrayDisplay disconnected() {
        return new TrayDisplay(null, UpsStatus.Severity.WARNING, false);
    }

    /** True when the tray should demand attention (SNI NeedsAttention). */
    public boolean attention() {
        return connected && severity == UpsStatus.Severity.CRITICAL;
    }

    /** The tray tooltip: "cyberpower · Online, charging · 87% · 38m". */
    public static String tooltip(UpsSnapshot s) {
        StringBuilder sb = new StringBuilder();
        String model = s.modelLabel();
        sb.append(model.isEmpty() ? s.ups() : model);
        sb.append(" · ").append(s.status().label());
        s.chargePercent().ifPresent(c -> sb.append(" · ").append(Math.round(c)).append("%"));
        Optional<Double> minutes = s.runtimeSeconds().map(sec -> sec / 60);
        minutes.ifPresent(m -> sb.append(" · ").append(formatMinutes(m)));
        return sb.toString();
    }

    private static String formatMinutes(double minutes) {
        if (minutes >= 90) {
            return String.format("%.1fh", minutes / 60);
        }
        return Math.round(minutes) + "m";
    }
}
