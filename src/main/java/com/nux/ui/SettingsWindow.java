package com.nux.ui;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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

import com.nux.config.Config;
import com.nux.protocol.NutClient;
import com.nux.tray.TrayMetric;

/**
 * The settings dialog: connection (host/port/TLS/auto-connect — moved off the toolbar),
 * tray (enabled, close-to-tray, displayed metric), and notifications. Save hands a new
 * {@link Config} to the caller, which persists and re-applies it.
 */
public final class SettingsWindow {

    private SettingsWindow() {}

    public static void show(Window owner, Config current, Consumer<Config> onSave) {
        TextField host = new TextField(current.lastHost());
        TextField port = new TextField(Integer.toString(current.lastPort()));
        port.setPrefColumnCount(5);
        CheckBox tls = new CheckBox("Use TLS (STARTTLS; upsd needs CERTFILE)");
        tls.setSelected(current.lastTls());
        CheckBox autoConnect = new CheckBox("Connect automatically on launch");
        autoConnect.setSelected(current.autoConnect());

        CheckBox tray = new CheckBox("Show tray icon");
        tray.setSelected(current.tray());
        CheckBox closeToTray = new CheckBox("Close hides to tray (Quit from the tray menu)");
        closeToTray.setSelected(current.closeToTray());
        ComboBox<TrayMetric> metric = new ComboBox<>();
        metric.getItems().setAll(TrayMetric.values());
        metric.setValue(TrayMetric.from(current.trayMetric()));
        metric.setConverter(new StringConverter<>() {
            @Override
            public String toString(TrayMetric m) {
                return m == null ? "" : m.label();
            }

            @Override
            public TrayMetric fromString(String s) {
                return TrayMetric.from(s);
            }
        });
        closeToTray.disableProperty().bind(tray.selectedProperty().not());
        metric.disableProperty().bind(tray.selectedProperty().not());

        CheckBox notifications = new CheckBox("Desktop notifications on power events");
        notifications.setSelected(current.notifications());

        GridPane connection = grid();
        connection.addRow(0, new Label("Host"), host);
        connection.addRow(1, new Label("Port"), port);
        connection.add(tls, 0, 2, 2, 1);
        connection.add(autoConnect, 0, 3, 2, 1);

        GridPane trayGrid = grid();
        trayGrid.add(tray, 0, 0, 2, 1);
        trayGrid.add(closeToTray, 0, 1, 2, 1);
        trayGrid.addRow(2, new Label("Tray shows"), metric);

        Stage stage = new Stage();
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());
        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
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
                    notifications.isSelected()));
            stage.close();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, spacer, cancel, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(
                10,
                section("Connection"),
                connection,
                section("Tray icon"),
                trayGrid,
                section("Notifications"),
                notifications,
                buttons);
        root.setPadding(new Insets(18));

        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Nux Settings");
        stage.setScene(new Scene(root, 440, 430));
        stage.getScene()
                .getStylesheets()
                .add(SettingsWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.show();
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        return grid;
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-section");
        return label;
    }

    private static int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return NutClient.DEFAULT_PORT;
        }
    }
}
