package com.nux.ui;

import java.util.Optional;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/** A small username/password prompt for upsd authentication (INSTCMD needs it). */
public final class CredentialsDialog {

    public record Credentials(String user, String password) {}

    private CredentialsDialog() {}

    public static Optional<Credentials> show(String header, String initialUser) {
        Dialog<Credentials> dialog = new Dialog<>();
        dialog.setTitle("NUT authentication");
        dialog.setHeaderText(header);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField user = new TextField(initialUser);
        PasswordField password = new PasswordField();
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Username"), user);
        grid.addRow(1, new Label("Password"), password);
        dialog.getDialogPane().setContent(grid);

        Platform.runLater(() -> (initialUser.isEmpty() ? user : password).requestFocus());
        dialog.setResultConverter(button ->
                button == ButtonType.OK ? new Credentials(user.getText().trim(), password.getText()) : null);
        return dialog.showAndWait();
    }
}
