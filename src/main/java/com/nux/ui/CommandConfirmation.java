package com.nux.ui;

import static com.nux.i18n.Messages.tr;

import java.util.Optional;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import com.nux.protocol.CommandRisk;

/**
 * The "run this instant command?" prompt. A harmless command gets a plain OK/Cancel; one
 * that can cut power or drain the battery gets a warning that names the consequence, a
 * button that names the command, and Cancel as the default — so Enter, Enter from the
 * palette can no longer switch off the equipment.
 */
public final class CommandConfirmation {

    private CommandConfirmation() {}

    /** Builds the dialog without showing it, so tests can inspect it. */
    public static Alert create(String command, String ups) {
        CommandRisk risk = CommandRisk.of(command);
        if (risk == CommandRisk.NONE) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(tr("dialog.runCommand.title"));
            confirm.setHeaderText(tr("dialog.runCommand.header", command, ups));
            confirm.setContentText(tr("dialog.runCommand.body"));
            return confirm;
        }
        ButtonType run = new ButtonType(tr("dialog.riskyCommand.run", command), ButtonBar.ButtonData.OK_DONE);
        Alert warning = new Alert(Alert.AlertType.WARNING, tr(risk.bodyKey()), run, ButtonType.CANCEL);
        warning.setTitle(tr("dialog.riskyCommand.title"));
        warning.setHeaderText(tr("dialog.runCommand.header", command, ups));
        ((Button) warning.getDialogPane().lookupButton(run)).setDefaultButton(false);
        ((Button) warning.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return warning;
    }

    /** True only for the affirmative button; closing the dialog or Cancel is a no. */
    public static boolean confirmed(Optional<ButtonType> answer) {
        return answer.map(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE).orElse(false);
    }
}
