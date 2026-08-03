package com.nux.ui;

import java.time.Instant;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.nux.analysis.EnergyEstimate;
import com.nux.config.Advanced;
import com.nux.store.HistorySample;
import com.nux.store.HistoryStore;

/** Persistent history: charge/load/input-voltage charts over a selectable range, plus energy use. */
public final class HistoryWindow {

    private record Range(String label, long seconds) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<Range> RANGES = List.of(
            new Range("Last hour", 3600),
            new Range("Last 24 hours", 86400),
            new Range("Last 7 days", 604800),
            new Range("Last 30 days", 2592000));

    private HistoryWindow() {}

    public static void show(Window owner, HistoryStore store, String ups, Advanced advanced, double nominalWatts) {
        TimeChart charge = new TimeChart("Battery charge", "%", 0.0, 100.0);
        TimeChart load = new TimeChart("Load", "%", 0.0, 100.0);
        TimeChart voltage = new TimeChart("Input voltage", "V", null, null);
        for (TimeChart c : List.of(charge, load, voltage)) {
            VBox.setVgrow(c, Priority.ALWAYS);
        }

        ComboBox<Range> range = new ComboBox<>();
        range.getItems().setAll(RANGES);
        range.setValue(RANGES.get(1));
        Label summary = new Label("Loading…");
        summary.getStyleClass().add("detail-name");
        Button refresh = new Button("Refresh");

        Runnable reload = () -> {
            long since = Instant.now().getEpochSecond() - range.getValue().seconds();
            summary.setText("Loading…");
            // Reading the whole history file is disk work: keep it off the FX thread.
            Thread loader = new Thread(
                    () -> {
                        List<HistorySample> samples = store.load(ups, since);
                        double kwh = EnergyEstimate.kilowattHours(samples, nominalWatts);
                        Platform.runLater(() -> {
                            charge.setData(samples, HistorySample::charge);
                            load.setData(samples, HistorySample::load);
                            voltage.setData(samples, HistorySample::inputVoltage);
                            summary.setText(summarize(samples, kwh, advanced));
                        });
                    },
                    "nux-history-load");
            loader.setDaemon(true);
            loader.start();
        };
        range.valueProperty().addListener((o, a, b) -> reload.run());
        refresh.setOnAction(e -> reload.run());

        HBox bar = new HBox(10, new Label("Range"), range, refresh, summary);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(10, bar, charge, load, voltage);
        root.setPadding(new Insets(14));

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("Nux History — " + ups);
        Scene scene = new Scene(root, 780, 620);
        scene.getStylesheets().add(HistoryWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        reload.run();
    }

    private static String summarize(List<HistorySample> samples, double kwh, Advanced advanced) {
        if (samples.isEmpty()) {
            return "No history recorded yet for this range.";
        }
        StringBuilder sb = new StringBuilder(samples.size() + " samples");
        if (kwh > 0) {
            sb.append(String.format(" · %.2f kWh", kwh));
            if (advanced.tariffPerKwh() > 0) {
                sb.append(String.format(
                        " · %s%.2f", advanced.currency(), EnergyEstimate.cost(kwh, advanced.tariffPerKwh())));
            }
        }
        return sb.toString();
    }
}
