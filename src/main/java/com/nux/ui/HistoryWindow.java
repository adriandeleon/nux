package com.nux.ui;

import static com.nux.i18n.Messages.tr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.nux.analysis.EnergyEstimate;
import com.nux.analysis.EnergyEstimate.Bucket;
import com.nux.analysis.EnergyEstimate.Period;
import com.nux.analysis.OutageReport;
import com.nux.analysis.OutageReport.Outage;
import com.nux.config.Advanced;
import com.nux.service.ContactTracker;
import com.nux.store.HistoryExport;
import com.nux.store.HistorySample;
import com.nux.store.HistoryStore;

/**
 * Persistent history in three tabs: charts over a selectable range (exportable as CSV),
 * energy and cost per hour/day/week/month, and every power outage on record.
 */
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
        Stage stage = new Stage();
        TabPane tabs = new TabPane(
                new Tab(tr("history.tab.charts"), charts(stage, store, server, ups, advanced, nominalWatts)),
                new Tab(tr("history.tab.energy"), energy(store, server, ups, advanced, nominalWatts)),
                new Tab(tr("history.tab.outages"), outages(store, server, ups)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        stage.initOwner(owner);
        stage.setTitle(tr("history.title", ups));
        Scene scene = new Scene(tabs, 800, 660);
        scene.getStylesheets().add(HistoryWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(520);
        stage.setMinHeight(420);
        Windows.track(key, stage);
        stage.show();
    }

    // --- Charts ---------------------------------------------------------------

    private static VBox charts(
            Window owner, HistoryStore store, String server, String ups, Advanced advanced, double nominalWatts) {
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
        Button export = new Button(tr("history.export"));
        List<HistorySample> shown = new ArrayList<>(); // what the charts show, for Export

        // Each load is numbered and only the newest may paint: a slow 30-day read finishing
        // after a quick 1-hour one would otherwise replace the range the user is looking at.
        long[] latest = {0};
        Runnable reload = () -> {
            long request = ++latest[0];
            long since = Instant.now().getEpochSecond() - range.getValue().seconds();
            summary.setText(tr("history.loading"));
            offFx("nux-history-load", () -> store.load(server, ups, since), samples -> {
                if (request != latest[0]) {
                    return;
                }
                shown.clear();
                shown.addAll(samples);
                charge.setData(samples, HistorySample::charge);
                load.setData(samples, HistorySample::load);
                voltage.setData(samples, HistorySample::inputVoltage);
                summary.setText(summarize(samples, EnergyEstimate.kilowattHours(samples, nominalWatts), advanced));
            });
        };
        range.valueProperty().addListener((o, a, b) -> reload.run());
        refresh.setOnAction(e -> reload.run());
        export.setOnAction(e -> exportCsv(owner, ups, List.copyOf(shown)));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, new Label(tr("history.range")), range, refresh, summary, spacer, export);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(10, bar, charge, load, voltage);
        root.setPadding(new Insets(14));
        reload.run();
        return root;
    }

    private static void exportCsv(Window owner, String ups, List<HistorySample> samples) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("history.exportTitle"));
        chooser.setInitialFileName("nux-history-" + ups + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return;
        }
        String csv = HistoryExport.csv(samples, ZoneId.systemDefault());
        Thread writer = new Thread(
                () -> {
                    try {
                        Files.writeString(target.toPath(), csv, StandardCharsets.UTF_8);
                    } catch (IOException ex) {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.initOwner(owner);
                            alert.setTitle(tr("events.exportFailed"));
                            alert.setHeaderText(tr("events.exportFailedHeader", target.getName()));
                            alert.setContentText(String.valueOf(ex.getMessage()));
                            alert.show();
                        });
                    }
                },
                "nux-history-export");
        writer.setDaemon(true);
        writer.start();
    }

    private static String summarize(List<HistorySample> samples, double kwh, Advanced advanced) {
        if (samples.isEmpty()) {
            return tr("history.empty");
        }
        StringBuilder sb = new StringBuilder(tr("history.samples", samples.size()));
        if (kwh > 0) {
            sb.append(String.format(" · %.2f kWh", kwh));
            if (advanced.tariffPerKwh() > 0) {
                sb.append(" · ").append(Money.format(advanced.currency(), EnergyEstimate.cost(kwh, advanced.tariffPerKwh())));
            }
        }
        return sb.toString();
    }

    // --- Energy ---------------------------------------------------------------

    /** One row of the energy table. */
    private record EnergyRow(String period, String energy, String cost) {}

    private static VBox energy(HistoryStore store, String server, String ups, Advanced advanced, double nominalWatts) {
        ComboBox<Period> period = new ComboBox<>();
        period.getItems().setAll(Period.values());
        period.setValue(Period.DAY);
        period.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Period p) {
                return p == null ? "" : tr("history.period." + p.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public Period fromString(String s) {
                return Period.DAY;
            }
        });
        Button refresh = new Button(tr("history.refresh"));
        Label total = new Label();
        total.getStyleClass().add("detail-name");

        TableView<EnergyRow> table = new TableView<>();
        table.getColumns().add(column(tr("history.col.period"), EnergyRow::period, 260));
        table.getColumns().add(column(tr("history.col.energy"), EnergyRow::energy, 140));
        table.getColumns().add(column(tr("history.col.cost"), EnergyRow::cost, 140));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(tr("history.empty")));
        VBox.setVgrow(table, Priority.ALWAYS);

        // What's missing for this tab to say anything: a power rating, a price.
        Label hint = new Label(nominalWatts <= 0 ? tr("history.noRating")
                : advanced.tariffPerKwh() <= 0 ? tr("history.noPrice") : "");
        hint.getStyleClass().add("detail-name");
        hint.setWrapText(true);
        hint.setManaged(!hint.getText().isEmpty());

        List<HistorySample> all = new ArrayList<>();
        Runnable render = () -> {
            List<Bucket> buckets = EnergyEstimate.byPeriod(
                    all, nominalWatts, period.getValue(), ZoneId.systemDefault(), firstDayOfWeek());
            List<EnergyRow> rows = new ArrayList<>();
            double kwhSum = 0;
            for (Bucket b : buckets.reversed()) { // newest first
                kwhSum += b.kilowattHours();
                rows.add(new EnergyRow(
                        periodLabel(period.getValue(), b.start()),
                        String.format("%.3f kWh", b.kilowattHours()),
                        costText(b.kilowattHours(), advanced)));
            }
            table.getItems().setAll(rows);
            total.setText(rows.isEmpty() ? "" : tr("history.energyTotal",
                    String.format("%.2f kWh", kwhSum), costText(kwhSum, advanced)));
        };
        Runnable reload = () -> offFx("nux-energy-load", () -> store.load(server, ups, 0), samples -> {
            all.clear();
            all.addAll(samples);
            render.run();
        });
        period.valueProperty().addListener((o, a, b) -> render.run());
        refresh.setOnAction(e -> reload.run());

        HBox bar = new HBox(10, new Label(tr("history.period")), period, refresh, total);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(10, bar, hint, table);
        root.setPadding(new Insets(14));
        reload.run();
        return root;
    }

    private static String costText(double kwh, Advanced advanced) {
        return advanced.tariffPerKwh() > 0
                ? Money.format(advanced.currency(), EnergyEstimate.cost(kwh, advanced.tariffPerKwh()))
                : "—";
    }

    private static String periodLabel(Period period, LocalDateTime start) {
        Locale locale = Locale.getDefault();
        return switch (period) {
            case HOUR -> start.format(DateTimeFormatter.ofPattern("EEE yyyy-MM-dd  HH:00", locale));
            case DAY -> start.format(DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", locale));
            case WEEK -> tr("history.weekOf", start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", locale)));
            case MONTH -> start.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale));
        };
    }

    /** The user's first day of the week (Monday in most of Europe, Sunday in the US). */
    private static DayOfWeek firstDayOfWeek() {
        return WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
    }

    // --- Outages --------------------------------------------------------------

    /** One row of the outage table. */
    private record OutageRow(String start, String duration, String lowest, String runtimeLeft, String note) {}

    private static VBox outages(HistoryStore store, String server, String ups) {
        TableView<OutageRow> table = new TableView<>();
        table.getColumns().add(column(tr("outages.start"), OutageRow::start, 165));
        table.getColumns().add(column(tr("outages.duration"), OutageRow::duration, 100));
        table.getColumns().add(column(tr("outages.lowest"), OutageRow::lowest, 135));
        table.getColumns().add(column(tr("outages.runtimeLeft"), OutageRow::runtimeLeft, 120));
        table.getColumns().add(column(tr("outages.note"), OutageRow::note, 200));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(tr("outages.none")));
        VBox.setVgrow(table, Priority.ALWAYS);
        Label summary = new Label();
        summary.getStyleClass().add("detail-name");
        Button refresh = new Button(tr("history.refresh"));

        DateTimeFormatter when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        Runnable reload = () -> offFx("nux-outage-load", () -> OutageReport.find(store.load(server, ups, 0)), found -> {
            List<OutageRow> rows = new ArrayList<>();
            long totalSeconds = 0;
            for (Outage o : found.reversed()) { // newest first
                totalSeconds += o.durationSeconds();
                rows.add(new OutageRow(
                        when.format(Instant.ofEpochSecond(o.startEpoch())),
                        (o.unclear() ? "≥ " : "") + ContactTracker.format(java.time.Duration.ofSeconds(o.durationSeconds())),
                        Double.isNaN(o.lowestCharge()) ? "—" : String.format("%.0f%%", o.lowestCharge()),
                        Double.isNaN(o.runtimeLeftAtEnd()) ? "—" : String.format("%.0f min", o.runtimeLeftAtEnd() / 60),
                        o.ongoing() ? tr("outages.ongoing")
                                : o.unclear() ? tr("outages.unclear")
                                : o.reachedLowBattery() ? tr("outages.lowBattery")
                                : ""));
            }
            table.getItems().setAll(rows);
            summary.setText(rows.isEmpty() ? "" : tr("outages.summary",
                    rows.size(), ContactTracker.format(java.time.Duration.ofSeconds(totalSeconds))));
        });
        refresh.setOnAction(e -> reload.run());

        HBox bar = new HBox(10, summary, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(10, bar, table);
        root.setPadding(new Insets(14));
        reload.run();
        return root;
    }

    // --- Helpers --------------------------------------------------------------

    /** Reads history off the FX thread (it is disk work) and hands the result back on it. */
    private static <T> void offFx(String name, java.util.function.Supplier<T> work, java.util.function.Consumer<T> done) {
        Thread loader = new Thread(
                () -> {
                    T result = work.get();
                    Platform.runLater(() -> done.accept(result));
                },
                name);
        loader.setDaemon(true);
        loader.start();
    }

    private static <R> TableColumn<R, String> column(String title, Function<R, String> get, double width) {
        TableColumn<R, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cd -> new SimpleStringProperty(get.apply(cd.getValue())));
        col.setPrefWidth(width);
        return col;
    }
}
