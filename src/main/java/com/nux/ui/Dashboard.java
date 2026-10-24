package com.nux.ui;

import static com.nux.i18n.Messages.tr;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.nux.analysis.RuntimeHealth;
import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;
import com.nux.service.History;

/** The main dashboard: status pill, three gauges with history sparklines, a voltage strip, details. */
public final class Dashboard extends VBox {

    private static final double RUNTIME_GAUGE_CAP_MINUTES = 60;
    /** ~1 hour of history at the 5 s poll cadence. */
    private static final int HISTORY_CAPACITY = 720;

    private final Label statusPill = new Label(tr("dashboard.notConnected"));
    private final Label modelLabel = new Label("");
    private final ArcGauge charge = new ArcGauge(tr("dashboard.battery"), false);
    private final ArcGauge load = new ArcGauge(tr("dashboard.load"), true);
    private final ArcGauge runtime = new ArcGauge(tr("dashboard.runtime"), false);

    private final History chargeHistory = new History(HISTORY_CAPACITY);
    private final History loadHistory = new History(HISTORY_CAPACITY);
    private final History runtimeHistory = new History(HISTORY_CAPACITY);
    private final History voltageHistory = new History(HISTORY_CAPACITY);

    private final Sparkline chargeSpark = new Sparkline(170, 36, 0.0, 100.0);
    private final Sparkline loadSpark = new Sparkline(170, 36, 0.0, 100.0);
    private final Sparkline runtimeSpark = new Sparkline(170, 36, null, null).withMinSpan(10); // minutes
    private final Sparkline voltageSpark = new Sparkline(566, 46, null, null).withMinSpan(10); // volts

    private final Label inputVoltage = detailValue();
    private final Label outputVoltage = detailValue();
    private final Label batteryVoltage = detailValue();
    private final Label serial = detailValue();
    private final Label batteryAge = detailValue();
    private final Label power = detailValue();
    private final HBox overview = new HBox(8);

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

        Label voltageTitle = new Label(tr("dashboard.inputVoltage"));
        voltageTitle.getStyleClass().add("detail-name");
        VBox voltageStrip = new VBox(4, voltageTitle, voltageSpark);
        voltageStrip.setAlignment(Pos.CENTER);

        GridPane details = new GridPane();
        details.setHgap(24);
        details.setVgap(8);
        details.setAlignment(Pos.CENTER);
        details.addRow(0, detailName(tr("dashboard.input")), inputVoltage, detailName(tr("dashboard.output")), outputVoltage);
        details.addRow(1, detailName(tr("dashboard.batteryLabel")), batteryVoltage, detailName(tr("dashboard.serial")), serial);
        details.addRow(2, detailName(tr("dashboard.batteryAge")), batteryAge, detailName(tr("dashboard.power")), power);

        overview.setAlignment(Pos.CENTER);
        overview.setVisible(false);
        overview.setManaged(false); // only takes space on a multi-UPS server

        getChildren().addAll(header, overview, gauges, voltageStrip, details);
    }

    /** Battery age/health text; {@code emphasis} styles it as a warning when replacement is due. */
    public void setBatteryAge(String text, boolean emphasis) {
        batteryAge.setText(text);
        batteryAge.getStyleClass().remove("detail-warn");
        if (emphasis) {
            batteryAge.getStyleClass().add("detail-warn");
        }
    }

    public void setPower(String text) {
        power.setText(text);
    }

    /** The multi-UPS strip: one clickable card per UPS, hidden when the server has only one. */
    public void setOverview(Map<String, UpsSnapshot> all, String selected, Consumer<String> onSelect) {
        overview.getChildren().clear();
        boolean show = all != null && all.size() > 1;
        overview.setVisible(show);
        overview.setManaged(show);
        if (!show) {
            return;
        }
        all.forEach((name, snapshot) -> {
            Label title = new Label(name);
            title.getStyleClass().add("overview-title");
            Label state = new Label(snapshot.status().label()
                    + snapshot.chargePercent().map(c -> "  " + Math.round(c) + "%").orElse(""));
            state.getStyleClass().add("detail-name");
            VBox card = new VBox(2, title, state);
            card.getStyleClass().add("overview-card");
            if (name.equals(selected)) {
                card.getStyleClass().add("overview-selected");
            }
            card.setOnMouseClicked(e -> onSelect.accept(name));
            // Reachable without a mouse: Tab to a card, Enter or Space to pick it.
            card.setFocusTraversable(true);
            card.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    onSelect.accept(name);
                    e.consume();
                }
            });
            card.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
            card.setAccessibleText(name + ": " + state.getText());
            overview.getChildren().add(card);
        });
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
            // Coloured by the UPS's own low-runtime threshold, not by how full the arc is.
            runtime.set(
                    minutes.get() / RUNTIME_GAUGE_CAP_MINUTES,
                    formatMinutes(minutes.get()),
                    RuntimeHealth.of(s, minutes.get() * 60));
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

    /** Lost the server while meant to be connected: a fault, retried every tick. */
    public void showDisconnected(String reason) {
        showIdle(tr("dashboard.disconnected"), "status-critical", reason);
    }

    /** The user pressed Disconnect: nothing is wrong, so no alarm colour. */
    public void showStopped() {
        showIdle(tr("dashboard.stopped"), "status-neutral", "");
    }

    private void showIdle(String pillText, String pillStyle, String detail) {
        statusPill.setText(pillText);
        statusPill.getStyleClass().removeAll("status-ok", "status-warning", "status-critical", "status-neutral");
        statusPill.getStyleClass().add(pillStyle);
        modelLabel.setText(detail);
        charge.clear();
        load.clear();
        runtime.clear();
        inputVoltage.setText("—");
        outputVoltage.setText("—");
        batteryVoltage.setText("—");
        serial.setText("—");
        setBatteryAge("—", false);
        power.setText("—");
        setOverview(null, null, name -> {});
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
