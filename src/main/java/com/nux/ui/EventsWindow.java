package com.nux.ui;

import static com.nux.i18n.Messages.tr;

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
        if (Windows.raise("events")) {
            return;
        }
        TableView<UpsEvent> table = new TableView<>();
        TableColumn<UpsEvent, String> when = column(tr("events.when"), UpsEvent::displayTime, 150);
        when.setMinWidth(150); // "2026-10-04 08:20" must never be cut to an ellipsis
        // Severity as words as well as row colour, so it doesn't rest on colour alone.
        TableColumn<UpsEvent, String> severity = column(
                tr("events.severity"), e -> e.critical() ? tr("events.critical") : tr("events.info"), 90);
        TableColumn<UpsEvent, String> ups = column(tr("events.ups"), UpsEvent::ups, 110);
        TableColumn<UpsEvent, String> title = column(tr("events.event"), UpsEvent::title, 190);
        TableColumn<UpsEvent, String> message = column(tr("events.details"), UpsEvent::message, 300);
        table.getColumns().addAll(List.of(when, severity, ups, title, message));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(tr("events.empty")));
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
                            count.setText(tr("events.count", events.size()));
                        });
                    },
                    "nux-events-load");
            loader.setDaemon(true);
            loader.start();
        };

        Stage stage = new Stage();
        Button refresh = new Button(tr("events.refresh"));
        refresh.setOnAction(e -> reload.run());
        Button export = new Button(tr("events.export"));
        export.setOnAction(e -> export(stage, log));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, count, spacer, refresh, export);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, bar, table);
        root.setPadding(new Insets(14));
        stage.initOwner(owner);
        stage.setTitle(tr("events.title"));
        Scene scene = new Scene(root, 900, 520);
        scene.getStylesheets().add(EventsWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(560);
        stage.setMinHeight(300);
        Windows.track("events", stage);
        stage.show();
        reload.run();
    }

    private static void export(Window owner, EventLog log) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("events.exportTitle"));
        chooser.setInitialFileName("nux-events.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return;
        }
        // The target may be a slow or network drive: copy off the FX thread.
        Thread copier = new Thread(
                () -> {
                    try {
                        Path source = log.file();
                        if (Files.isRegularFile(source)) {
                            Files.copy(source, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.writeString(target.toPath(), "");
                        }
                    } catch (IOException e) {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.initOwner(owner);
                            alert.setTitle(tr("events.exportFailed"));
                            alert.setHeaderText(tr("events.exportFailedHeader", target.getName()));
                            alert.setContentText(String.valueOf(e.getMessage()));
                            alert.show();
                        });
                    }
                },
                "nux-events-export");
        copier.setDaemon(true);
        copier.start();
    }

    private static TableColumn<UpsEvent, String> column(
            String title, java.util.function.Function<UpsEvent, String> get, double width) {
        TableColumn<UpsEvent, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cd -> new SimpleStringProperty(get.apply(cd.getValue())));
        col.setPrefWidth(width);
        return col;
    }
}
