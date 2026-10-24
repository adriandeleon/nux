package com.nux.ui;

import static com.nux.ui.FxTestSupport.callOnFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The prompt is built but never shown (showAndWait would block), which is enough to
 * check what Enter would do — the regression was that Enter, Enter from the palette ran
 * {@code load.off}.
 */
@Tag("fx")
class CommandConfirmationFxTest {

    @BeforeAll
    static void boot() {
        FxTestSupport.startToolkit();
    }

    private static Button defaultButton(Alert alert) {
        return alert.getButtonTypes().stream()
                .map(type -> (Button) alert.getDialogPane().lookupButton(type))
                .filter(Button::isDefaultButton)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aHarmlessCommandIsAPlainConfirmationWithOkAsDefault() {
        Alert alert = callOnFx(() -> CommandConfirmation.create("beeper.toggle", "cp"));
        assertEquals(Alert.AlertType.CONFIRMATION, alert.getAlertType());
        assertEquals(ButtonType.OK, callOnFx(() -> alert.getButtonTypes().stream()
                .filter(t -> alert.getDialogPane().lookupButton(t) == defaultButton(alert))
                .findFirst()
                .orElseThrow()));
    }

    @Test
    void cuttingPowerWarnsNamesTheCommandAndDefaultsToCancel() {
        Alert alert = callOnFx(() -> CommandConfirmation.create("load.off", "cp"));
        assertEquals(Alert.AlertType.WARNING, alert.getAlertType());
        assertTrue(alert.getContentText().contains("loses power"), alert.getContentText());
        Button cancel = callOnFx(() -> (Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL));
        assertTrue(cancel.isDefaultButton(), "Enter must not run a power-cutting command");
        assertTrue(alert.getButtonTypes().stream().anyMatch(t -> t.getText().contains("load.off")));
    }

    @Test
    void onlyTheAffirmativeButtonCountsAsConfirmation() {
        Alert alert = callOnFx(() -> CommandConfirmation.create("shutdown.stayoff", "cp"));
        ButtonType run = alert.getButtonTypes().stream()
                .filter(t -> t != ButtonType.CANCEL)
                .findFirst()
                .orElseThrow();
        assertTrue(CommandConfirmation.confirmed(Optional.of(run)));
        assertFalse(CommandConfirmation.confirmed(Optional.of(ButtonType.CANCEL)));
        assertFalse(CommandConfirmation.confirmed(Optional.empty()));
        assertTrue(CommandConfirmation.confirmed(Optional.of(ButtonType.OK)));
    }
}
