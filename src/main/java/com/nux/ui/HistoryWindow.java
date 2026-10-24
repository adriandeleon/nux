package com.nux.ui;

import static com.nux.i18n.Messages.tr;

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
            new Range(tr("history.lastHour"), 3600),
            new Range(tr("history.last24Hours"), 86400),
            new Range(tr("history.last7Days"), 604800),
            new Range(tr("history.last30Days"), 2592000));

    private HistoryWindow() {}

    public static void show(
            Window owner, HistoryStore store, String server, String ups, Advanced advanced, double nominalWatts) {
        String key = "history:" + server + "/" + ups;
        if (Windows.raise(key)) {
            return;
        }
        TimeChart charge = new TimeChart(tr("history.chartCharge"), "%", 0.0, 100.0);
        TimeChart load = new TimeChart(tr("history.chartLoad"), "%", 0.0, 100.0);
        TimeChart voltage = new TimeChart(tr("history.chartVoltage"), "V", null, null);
        for (TimeChart c : List.of(charge, load, voltage)) {
            VBox.setVgrow(c, Priority.ALWAYS);
        }

        ComboBox<Range> range = new ComboBox<>();
        range.getItems().setAll(RANGES);
        range.setValue(RANGES.get(1));
        Label summary = new Label(tr("history.loading"));
        summary.getStyleClass().add("detail-name");
        Button refresh = new Button(tr("history.refresh"));

        // Each load is numbered and only the newest may paint: a slow 30-day read finishing
        // after a quick 1-hour one would otherwise replace the range the user is looking at.
        long[] latest = {0};
        Runnable reload = () -> {
            long request = ++latest[0];
            long since = Instant.now().getEpochSecond() - range.getValue().seconds();
            summary.setText(tr("history.loading"));
            // Reading the whole history file is disk work: keep it off the FX thread.
            Thread loader = new Thread(
                    () -> {
                        List<HistorySample> samples = store.load(server, ups, since);
                        double kwh = EnergyEstimate.kilowattHours(samples, nominalWatts);
                        Platform.runLater(() -> {
                            if (request != latest[0]) {
                                return;
                            }
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

        HBox bar = new HBox(10, new Label(tr("history.range")), range, refresh, summary);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(10, bar, charge, load, voltage);
        root.setPadding(new Insets(14));

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle(tr("history.title", ups));
        Scene scene = new Scene(root, 780, 620);
        scene.getStylesheets().add(HistoryWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(480);
        stage.setMinHeight(400);
        Windows.track(key, stage);
        stage.show();
        reload.run();
    }

    private static String summarize(List<HistorySample> samples, double kwh, Advanced advanced) {
        if (samples.isEmpty()) {
            return tr("history.empty");
        }
        StringBuilder sb = new StringBuilder(tr("history.samples", samples.size()));
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
