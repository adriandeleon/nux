package com.nux.ui;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.nux.analysis.TestSchedule;
import com.nux.config.Advanced;
import com.nux.config.Config;
import com.nux.protocol.NutClient;
import com.nux.tray.TrayMetric;

/**
 * The settings dialog: connection (moved off the toolbar), tray, power/energy, and
 * automation. Save hands a whole new {@link Config} to the caller, which diffs it and
 * re-applies only what changed.
 */
public final class SettingsWindow {

    private SettingsWindow() {}

    public static void show(Window owner, Config current, Consumer<Config> onSave) {
        Advanced adv = current.advanced();

        // --- Connection
        TextField host = new TextField(current.lastHost());
        TextField port = new TextField(Integer.toString(current.lastPort()));
        port.setPrefColumnCount(5);
        CheckBox tls = new CheckBox("Use TLS (STARTTLS; upsd needs CERTFILE)");
        tls.setSelected(current.lastTls());
        CheckBox autoConnect = new CheckBox("Connect automatically on launch");
        autoConnect.setSelected(current.autoConnect());
        GridPane connection = grid();
        connection.addRow(0, new Label("Host"), host);
        connection.addRow(1, new Label("Port"), port);
        connection.add(tls, 0, 2, 2, 1);
        connection.add(autoConnect, 0, 3, 2, 1);

        // --- Tray + notifications
        CheckBox tray = new CheckBox("Show tray icon");
        tray.setSelected(current.tray());
        CheckBox closeToTray = new CheckBox("Close hides to tray (Quit from the tray menu)");
        closeToTray.setSelected(current.closeToTray());
        ComboBox<TrayMetric> metric = new ComboBox<>();
        metric.getItems().setAll(TrayMetric.values());
        metric.setValue(TrayMetric.from(current.trayMetric()));
        metric.setConverter(converter(TrayMetric::label, TrayMetric::from));
        closeToTray.disableProperty().bind(tray.selectedProperty().not());
        metric.disableProperty().bind(tray.selectedProperty().not());
        CheckBox notifications = new CheckBox("Desktop notifications on power events");
        notifications.setSelected(current.notifications());
        GridPane trayGrid = grid();
        trayGrid.add(tray, 0, 0, 2, 1);
        trayGrid.add(closeToTray, 0, 1, 2, 1);
        trayGrid.addRow(2, new Label("Tray shows"), metric);
        trayGrid.add(notifications, 0, 3, 2, 1);

        // --- Power & history
        TextField watts = new TextField(adv.nominalWatts() > 0 ? trim(adv.nominalWatts()) : "");
        watts.setPromptText("auto from UPS");
        watts.setPrefColumnCount(8);
        TextField tariff = new TextField(adv.tariffPerKwh() > 0 ? trim(adv.tariffPerKwh()) : "");
        tariff.setPromptText("0 = off");
        tariff.setPrefColumnCount(8);
        TextField currency = new TextField(adv.currency());
        currency.setPrefColumnCount(3);
        Spinner<Integer> historyDays = new Spinner<>(1, 365, adv.historyDays());
        historyDays.setPrefWidth(90);
        GridPane power = grid();
        power.addRow(0, new Label("UPS output rating"), withSuffix(watts, "W"));
        power.addRow(1, new Label("Electricity price"), withSuffix(tariff, "per kWh"));
        power.addRow(2, new Label("Currency symbol"), currency);
        power.addRow(3, new Label("Keep history for"), withSuffix(historyDays, "days"));
        power.add(note("Load is usually reported as a percentage, so power and cost are estimates."), 0, 4, 2, 1);

        // --- Automation
        CheckBox selfTest = new CheckBox("Run a battery self-test on a schedule");
        selfTest.setSelected(adv.selfTestEnabled());
        Spinner<Integer> interval = new Spinner<>(1, 365, adv.selfTestIntervalDays());
        interval.setPrefWidth(90);
        TextField time = new TextField(adv.selfTestTime());
        time.setPrefColumnCount(6);
        TextField command = new TextField(adv.selfTestCommand());
        interval.disableProperty().bind(selfTest.selectedProperty().not());
        time.disableProperty().bind(selfTest.selectedProperty().not());
        command.disableProperty().bind(selfTest.selectedProperty().not());
        GridPane automation = grid();
        automation.add(selfTest, 0, 0, 2, 1);
        automation.addRow(1, new Label("Every"), withSuffix(interval, "days"));
        automation.addRow(2, new Label("At"), withSuffix(time, "HH:mm"));
        automation.addRow(3, new Label("Command"), command);
        automation.add(
                note("Tests run only while Nux is open and after you have entered UPS credentials this session — "
                        + "credentials are never stored on disk."),
                0, 4, 2, 1);
        automation.add(
                new Label("Last run: "
                        + (adv.selfTestLastRun() > 0
                                ? TestSchedule.describeNext(java.util.Optional.of(
                                        java.time.Instant.ofEpochSecond(adv.selfTestLastRun())))
                                : "never")),
                0, 5, 2, 1);

        TabPane tabs = new TabPane(
                tab("Connection", connection), tab("Tray", trayGrid), tab("Power", power), tab("Automation", automation));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Stage stage = new Stage();
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());
        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            Advanced updatedAdv = new Advanced(
                    historyDays.getValue(),
                    parseDouble(watts.getText()),
                    parseDouble(tariff.getText()),
                    currency.getText().isBlank() ? "$" : currency.getText().trim(),
                    selfTest.isSelected(),
                    interval.getValue(),
                    time.getText().trim(),
                    adv.selfTestLastRun(),
                    command.getText().isBlank() ? Advanced.DEFAULT_TEST_COMMAND : command.getText().trim());
            onSave.accept(new Config(
                    current.profiles(),
                    host.getText().trim(),
                    parsePort(port.getText()),
                    current.lastUps(),
                    tls.isSelected(),
                    current.tlsTrustAll(),
                    autoConnect.isSelected(),
                    tray.isSelected(),
                    closeToTray.isSelected(),
                    metric.getValue().key(),
                    notifications.isSelected(),
                    updatedAdv));
            stage.close();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, spacer, cancel, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, tabs, buttons);
        root.setPadding(new Insets(16));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Nux Settings");
        Scene scene = new Scene(root, 500, 430);
        scene.getStylesheets().add(SettingsWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private static Tab tab(String title, Region content) {
        content.setPadding(new Insets(14));
        return new Tab(title, content);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(9);
        return grid;
    }

    private static HBox withSuffix(Region control, String suffix) {
        Label label = new Label(suffix);
        label.getStyleClass().add("detail-name");
        HBox box = new HBox(6, control, label);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("detail-name");
        label.setWrapText(true);
        label.setMaxWidth(400);
        return label;
    }

    private static <T> StringConverter<T> converter(
            java.util.function.Function<T, String> toString, java.util.function.Function<String, T> fromString) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : toString.apply(value);
            }

            @Override
            public T fromString(String s) {
                return fromString.apply(s);
            }
        };
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.valueOf(value);
    }

    private static double parseDouble(String text) {
        try {
            return Math.max(0, Double.parseDouble(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return NutClient.DEFAULT_PORT;
        }
    }
}
