package com.nux.ui;

import java.util.function.BiConsumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.nux.protocol.NutClient;
import com.nux.service.DiscoveryService;

/** Scans the local network for NUT servers and lets the user pick one. */
public final class DiscoveryWindow {

    private DiscoveryWindow() {}

    public static void show(Window owner, BiConsumer<String, Integer> onPick) {
        DiscoveryService discovery = new DiscoveryService();
        ListView<DiscoveryService.Found> list = new ListView<>();
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(DiscoveryService.Found item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String ups = item.upsNames().isEmpty() ? "no UPS configured" : String.join(", ", item.upsNames());
                setText(item.host() + ":" + item.port() + "   —   " + ups);
            }
        });
        list.setPlaceholder(new Label("No servers found yet"));
        VBox.setVgrow(list, Priority.ALWAYS);

        Label status = new Label("Ready");
        status.getStyleClass().add("detail-name");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(16, 16);
        spinner.setVisible(false);

        Stage stage = new Stage();
        Button scan = new Button("Scan");
        Button use = new Button("Use this server");
        use.setDisable(true);
        list.getSelectionModel()
                .selectedItemProperty()
                .addListener((o, a, b) -> use.setDisable(b == null));

        scan.setOnAction(e -> {
            list.getItems().clear();
            scan.setDisable(true);
            spinner.setVisible(true);
            status.setText("Scanning the local network…");
            discovery.scan(
                    NutClient.DEFAULT_PORT,
                    found -> list.getItems().add(found),
                    probed -> {
                        scan.setDisable(false);
                        spinner.setVisible(false);
                        status.setText("Probed " + probed + " addresses — found " + list.getItems().size());
                    });
        });
        use.setOnAction(e -> {
            DiscoveryService.Found selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                onPick.accept(selected.host(), selected.port());
                discovery.cancel();
                stage.close();
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                use.fire();
            }
        });
        stage.setOnHidden(e -> discovery.cancel());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, spinner, status, spacer, scan, use);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(
                10,
                new Label("NUT servers answering on port " + NutClient.DEFAULT_PORT + " in your subnets"),
                list,
                bar);
        root.setPadding(new Insets(14));

        stage.initOwner(owner);
        stage.setTitle("Find NUT servers");
        Scene scene = new Scene(root, 520, 380);
        scene.getStylesheets().add(DiscoveryWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        scan.fire();
    }
}
