package com.nux.ui;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.nux.protocol.RwVariable;

/**
 * The settable-variable editor (NUT {@code upsrw}): pick a writable variable, get a
 * control matched to its type, apply. Values are validated client-side first so an
 * obviously-bad write never reaches the UPS.
 */
public final class VariablesWindow {

    /**
     * Applies a value; the callback reports (ok, message). The window passes its own UPS:
     * it isn't modal, so the main window's selection may have moved on since it opened.
     */
    public interface Setter {
        void set(String ups, String variable, String value, BiConsumer<Boolean, String> onResult);
    }

    /** Re-reads the writable variables, handing them back on the FX thread. */
    public interface Reloader {
        void reload(Consumer<List<RwVariable>> onLoaded);
    }

    private VariablesWindow() {}

    public static void show(Window owner, String ups, List<RwVariable> variables, Setter setter, Reloader reloader) {
        ListView<RwVariable> list = new ListView<>();
        list.getItems().setAll(variables);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(RwVariable item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + "  =  " + item.value());
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        Label hint = new Label("Select a variable to edit");
        hint.getStyleClass().add("detail-name");
        HBox editorRow = new HBox(10);
        editorRow.setAlignment(Pos.CENTER_LEFT);
        Label status = new Label();

        Button apply = new Button("Apply");
        apply.setDisable(true);

        // A successful write must be reflected in the list, or the row keeps showing the
        // old value and the change looks like it failed. Re-select the same variable so
        // the user stays where they were.
        Runnable refresh = () -> reloader.reload(reloaded -> {
            String selectedName = list.getSelectionModel().getSelectedItem() == null
                    ? null
                    : list.getSelectionModel().getSelectedItem().name();
            list.getItems().setAll(reloaded);
            reloaded.stream()
                    .filter(v -> v.name().equals(selectedName))
                    .findFirst()
                    .ifPresent(v -> list.getSelectionModel().select(v));
        });

        list.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            editorRow.getChildren().clear();
            status.setText("");
            if (selected == null) {
                apply.setDisable(true);
                hint.setText("Select a variable to edit");
                return;
            }
            hint.setText(describe(selected));
            apply.setDisable(false);
            switch (selected.kind()) {
                case ENUM -> {
                    ComboBox<String> combo = new ComboBox<>();
                    combo.getItems().setAll(selected.options());
                    combo.setValue(selected.value());
                    editorRow.getChildren().addAll(new Label(selected.name()), combo);
                    apply.setOnAction(e -> applyValue(ups, selected, combo.getValue(), setter, status, refresh));
                }
                default -> {
                    TextField field = new TextField(selected.value());
                    field.setPrefColumnCount(16);
                    editorRow.getChildren().addAll(new Label(selected.name()), field);
                    apply.setOnAction(e -> applyValue(ups, selected, field.getText().trim(), setter, status, refresh));
                }
            }
        });

        Stage stage = new Stage();
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, status, spacer, apply, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, new Label("Writable variables on " + ups), list, hint, editorRow, buttons);
        root.setPadding(new Insets(14));

        stage.initOwner(owner);
        stage.setTitle("Nux Variables — " + ups);
        Scene scene = new Scene(root, 520, 520);
        scene.getStylesheets().add(VariablesWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private static void applyValue(
            String ups, RwVariable variable, String value, Setter setter, Label status, Runnable refresh) {
        if (!variable.accepts(value)) {
            status.setText("Value not allowed for " + variable.name());
            return;
        }
        status.setText("Applying…");
        setter.set(ups, variable.name(), value, (ok, message) -> {
            status.setText(ok ? "Applied" : "Failed: " + message);
            if (ok) {
                refresh.run();
            }
        });
    }

    private static String describe(RwVariable v) {
        return switch (v.kind()) {
            case ENUM -> "One of: " + String.join(", ", v.options());
            case RANGE -> "Number between " + fmt(v.min()) + " and " + fmt(v.max());
            case NUMBER -> "Numeric value";
            case STRING -> v.maxLength() > 0 ? "Text, up to " + v.maxLength() + " characters" : "Text";
        };
    }

    private static String fmt(Double d) {
        if (d == null) {
            return "?";
        }
        return d == Math.rint(d) ? Long.toString((long) (double) d) : String.valueOf(d);
    }
}
