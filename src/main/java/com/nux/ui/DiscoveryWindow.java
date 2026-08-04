package com.nux.ui;

import static com.nux.i18n.Messages.tr;

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
                String ups = item.upsNames().isEmpty() ? tr("discovery.noUps") : String.join(", ", item.upsNames());
                setText(item.host() + ":" + item.port() + "   —   " + ups);
            }
        });
        list.setPlaceholder(new Label(tr("discovery.empty")));
        VBox.setVgrow(list, Priority.ALWAYS);

        Label status = new Label(tr("discovery.ready"));
        status.getStyleClass().add("detail-name");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(16, 16);
        spinner.setVisible(false);

        Stage stage = new Stage();
        Button scan = new Button(tr("discovery.scan"));
        Button use = new Button(tr("discovery.use"));
        use.setDisable(true);
        list.getSelectionModel()
                .selectedItemProperty()
                .addListener((o, a, b) -> use.setDisable(b == null));

        scan.setOnAction(e -> {
            list.getItems().clear();
            scan.setDisable(true);
            spinner.setVisible(true);
            status.setText(tr("discovery.scanning"));
            discovery.scan(
                    NutClient.DEFAULT_PORT,
                    found -> list.getItems().add(found),
                    probed -> {
                        scan.setDisable(false);
                        spinner.setVisible(false);
                        status.setText(tr("discovery.result", probed, list.getItems().size()));
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
                new Label(tr("discovery.heading", NutClient.DEFAULT_PORT)),
                list,
                bar);
        root.setPadding(new Insets(14));

        stage.initOwner(owner);
        stage.setTitle(tr("discovery.title"));
        Scene scene = new Scene(root, 520, 380);
        scene.getStylesheets().add(DiscoveryWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        scan.fire();
    }
}
