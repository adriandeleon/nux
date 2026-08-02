package com.nux.ui;

import java.util.Optional;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;
import com.nux.service.History;

/** The main dashboard: status pill, three gauges with history sparklines, a voltage strip, details. */
public final class Dashboard extends VBox {

    private static final double RUNTIME_GAUGE_CAP_MINUTES = 60;
    /** ~1 hour of history at the 5 s poll cadence. */
    private static final int HISTORY_CAPACITY = 720;

    private final Label statusPill = new Label("Not connected");
    private final Label modelLabel = new Label("");
    private final ArcGauge charge = new ArcGauge("Battery", false);
    private final ArcGauge load = new ArcGauge("Load", true);
    private final ArcGauge runtime = new ArcGauge("Runtime", false);

    private final History chargeHistory = new History(HISTORY_CAPACITY);
    private final History loadHistory = new History(HISTORY_CAPACITY);
    private final History runtimeHistory = new History(HISTORY_CAPACITY);
    private final History voltageHistory = new History(HISTORY_CAPACITY);

    private final Sparkline chargeSpark = new Sparkline(170, 36, 0.0, 100.0);
    private final Sparkline loadSpark = new Sparkline(170, 36, 0.0, 100.0);
    private final Sparkline runtimeSpark = new Sparkline(170, 36, null, null);
    private final Sparkline voltageSpark = new Sparkline(566, 46, null, null);

    private final Label inputVoltage = detailValue();
    private final Label outputVoltage = detailValue();
    private final Label batteryVoltage = detailValue();
    private final Label serial = detailValue();

    public Dashboard() {
        setSpacing(18);
        setPadding(new Insets(24));
        setAlignment(Pos.TOP_CENTER);

        statusPill.getStyleClass().addAll("status-pill", "status-neutral");
        modelLabel.getStyleClass().add("model-label");

        HBox header = new HBox(14, statusPill, modelLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox gauges = new HBox(28, column(charge, chargeSpark), column(load, loadSpark), column(runtime, runtimeSpark));
        gauges.setAlignment(Pos.CENTER);

        Label voltageTitle = new Label("Input voltage");
        voltageTitle.getStyleClass().add("detail-name");
        VBox voltageStrip = new VBox(4, voltageTitle, voltageSpark);
        voltageStrip.setAlignment(Pos.CENTER);

        GridPane details = new GridPane();
        details.setHgap(24);
        details.setVgap(8);
        details.setAlignment(Pos.CENTER);
        details.addRow(0, detailName("Input"), inputVoltage, detailName("Output"), outputVoltage);
        details.addRow(1, detailName("Battery"), batteryVoltage, detailName("Serial"), serial);

        getChildren().addAll(header, gauges, voltageStrip, details);
    }

    public void update(UpsSnapshot s) {
        UpsStatus status = s.status();
        statusPill.setText(status.label());
        statusPill.getStyleClass().removeAll("status-ok", "status-warning", "status-critical", "status-neutral");
        statusPill.getStyleClass().add(switch (status.severity()) {
            case OK -> "status-ok";
            case WARNING -> "status-warning";
            case CRITICAL -> "status-critical";
        });
        modelLabel.setText(s.modelLabel());

        setGauge(charge, s.chargePercent(), 100, "%.0f%%");
        setGauge(load, s.loadPercent(), 100, "%.0f%%");
        Optional<Double> minutes = s.runtimeSeconds().map(sec -> sec / 60);
        if (minutes.isPresent()) {
            runtime.set(minutes.get() / RUNTIME_GAUGE_CAP_MINUTES, formatMinutes(minutes.get()));
        } else {
            runtime.clear();
        }

        chargeHistory.add(s.chargePercent().orElse(Double.NaN));
        loadHistory.add(s.loadPercent().orElse(Double.NaN));
        runtimeHistory.add(minutes.orElse(Double.NaN));
        voltageHistory.add(s.number("input.voltage").orElse(Double.NaN));
        redrawSparklines();

        inputVoltage.setText(volts(s, "input.voltage"));
        outputVoltage.setText(volts(s, "output.voltage"));
        batteryVoltage.setText(volts(s, "battery.voltage"));
        serial.setText(s.vars().getOrDefault("device.serial", s.vars().getOrDefault("ups.serial", "—")));
    }

    public void showDisconnected(String reason) {
        statusPill.setText("Disconnected — retrying");
        statusPill.getStyleClass().removeAll("status-ok", "status-warning", "status-critical", "status-neutral");
        statusPill.getStyleClass().add("status-critical");
        modelLabel.setText(reason);
        charge.clear();
        load.clear();
        runtime.clear();
        inputVoltage.setText("—");
        outputVoltage.setText("—");
        batteryVoltage.setText("—");
        serial.setText("—");
    }

    /** Clears all history — call when switching to a different UPS or server. */
    public void resetHistory() {
        chargeHistory.clear();
        loadHistory.clear();
        runtimeHistory.clear();
        voltageHistory.clear();
        redrawSparklines();
    }

    private void redrawSparklines() {
        chargeSpark.update(chargeHistory.values());
        loadSpark.update(loadHistory.values());
        runtimeSpark.update(runtimeHistory.values());
        voltageSpark.update(voltageHistory.values());
    }

    private static VBox column(ArcGauge gauge, Sparkline spark) {
        VBox box = new VBox(2, gauge, spark);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private static void setGauge(ArcGauge gauge, Optional<Double> value, double max, String format) {
        if (value.isPresent()) {
            gauge.set(value.get() / max, String.format(format, value.get()));
        } else {
            gauge.clear();
        }
    }

    private static String formatMinutes(double minutes) {
        if (minutes >= 90) {
            return String.format("%.1fh", minutes / 60);
        }
        return String.format("%.0fm", minutes);
    }

    private static String volts(UpsSnapshot s, String key) {
        return s.number(key).map(v -> String.format("%.0f V", v)).orElse("—");
    }

    private static Label detailName(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("detail-name");
        return l;
    }

    private static Label detailValue() {
        Label l = new Label("—");
        l.getStyleClass().add("detail-value");
        return l;
    }
}
