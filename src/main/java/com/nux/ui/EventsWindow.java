package com.nux.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.nux.store.EventLog;
import com.nux.store.UpsEvent;

/** The persistent power-event log: what happened and when, exportable as CSV. */
public final class EventsWindow {

    private EventsWindow() {}

    public static void show(Window owner, EventLog log) {
        TableView<UpsEvent> table = new TableView<>();
        TableColumn<UpsEvent, String> when = column("When", UpsEvent::displayTime, 160);
        TableColumn<UpsEvent, String> ups = column("UPS", UpsEvent::ups, 110);
        TableColumn<UpsEvent, String> title = column("Event", UpsEvent::title, 190);
        TableColumn<UpsEvent, String> message = column("Details", UpsEvent::message, 340);
        table.getColumns().addAll(List.of(when, ups, title, message));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No events recorded yet"));
        // Critical rows read at a glance — that's the whole point of scanning this list.
        table.setRowFactory(v -> new TableRow<>() {
            @Override
            protected void updateItem(UpsEvent item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("event-critical");
                if (!empty && item != null && item.critical()) {
                    getStyleClass().add("event-critical");
                }
            }
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        Label count = new Label();
        Runnable reload = () -> {
            Thread loader = new Thread(
                    () -> {
                        List<UpsEvent> events = log.load();
                        Platform.runLater(() -> {
                            table.getItems().setAll(events);
                            count.setText(events.size() + " events");
                        });
                    },
                    "nux-events-load");
            loader.setDaemon(true);
            loader.start();
        };

        Stage stage = new Stage();
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload.run());
        Button export = new Button("Export…");
        export.setOnAction(e -> export(stage, log));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, count, spacer, refresh, export);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, bar, table);
        root.setPadding(new Insets(14));
        stage.initOwner(owner);
        stage.setTitle("Nux Events");
        Scene scene = new Scene(root, 820, 520);
        scene.getStylesheets().add(EventsWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        reload.run();
    }

    private static void export(Window owner, EventLog log) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export events");
        chooser.setInitialFileName("nux-events.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return;
        }
        try {
            Path source = log.file();
            if (Files.isRegularFile(source)) {
                Files.copy(source, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.writeString(target.toPath(), "");
            }
        } catch (IOException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Export failed");
            alert.setHeaderText("Could not write " + target.getName());
            alert.setContentText(String.valueOf(e.getMessage()));
            alert.show();
        }
    }

    private static TableColumn<UpsEvent, String> column(
            String title, java.util.function.Function<UpsEvent, String> get, double width) {
        TableColumn<UpsEvent, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cd -> new SimpleStringProperty(get.apply(cd.getValue())));
        col.setPrefWidth(width);
        return col;
    }
}
