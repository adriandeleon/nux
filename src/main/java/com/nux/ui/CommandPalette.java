package com.nux.ui;

import java.util.List;
import java.util.function.Supplier;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.nux.command.Command;
import com.nux.command.CommandRegistry;

/**
 * An in-scene command palette (Ctrl/Cmd+Shift+P): a dimmed backdrop + a centered card
 * with a filter field over the command list. In-scene rather than a Popup so keyboard
 * focus can never strand in a separate native window.
 */
public final class CommandPalette {

    private final StackPane host;
    private final Supplier<List<Command>> commands;
    private Region backdrop;
    private VBox card;
    private List<Command> current = List.of();

    public CommandPalette(StackPane host, Supplier<List<Command>> commands) {
        this.host = host;
        this.commands = commands;
    }

    public void toggle() {
        if (card != null) {
            hide();
        } else {
            show();
        }
    }

    private void show() {
        List<Command> all = commands.get();

        TextField input = new TextField();
        input.setPromptText("Type a command…");
        ListView<Command> list = new ListView<>();
        list.setCellFactory(v -> new CommandCell());
        list.setFocusTraversable(false);
        list.setPrefHeight(280);

        Runnable refill = () -> {
            current = CommandRegistry.filter(all, input.getText());
            list.getItems().setAll(current);
            if (!current.isEmpty()) {
                list.getSelectionModel().select(0);
            }
        };
        input.textProperty().addListener((obs, old, text) -> refill.run());
        input.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> hide();
                case DOWN -> move(list, 1);
                case UP -> move(list, -1);
                case ENTER -> runSelected(list);
                default -> {
                    return;
                }
            }
            e.consume();
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                runSelected(list);
            }
        });

        card = new VBox(8, input, list);
        card.getStyleClass().add("palette-card");
        card.setPadding(new Insets(12));
        card.setMaxWidth(540);
        card.setMaxHeight(340);
        StackPane.setAlignment(card, Pos.TOP_CENTER);
        StackPane.setMargin(card, new Insets(64, 0, 0, 0));

        backdrop = new Region();
        backdrop.getStyleClass().add("palette-backdrop");
        backdrop.setOnMouseClicked(e -> hide());

        host.getChildren().addAll(backdrop, card);
        refill.run();
        input.requestFocus();
    }

    private void hide() {
        host.getChildren().removeAll(backdrop, card);
        backdrop = null;
        card = null;
    }

    private static void move(ListView<Command> list, int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int next = Math.clamp(list.getSelectionModel().getSelectedIndex() + delta, 0, size - 1);
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void runSelected(ListView<Command> list) {
        Command selected = list.getSelectionModel().getSelectedItem();
        if (selected == null && !current.isEmpty()) {
            selected = current.getFirst();
        }
        hide();
        if (selected != null) {
            selected.action().run();
        }
    }

    private static final class CommandCell extends ListCell<Command> {
        @Override
        protected void updateItem(Command item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label title = new Label(item.title());
            Label id = new Label(item.id());
            id.getStyleClass().add("palette-command-id");
            VBox box = new VBox(1, title, id);
            setGraphic(box);
        }
    }
}
