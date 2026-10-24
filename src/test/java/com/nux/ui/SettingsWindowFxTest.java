package com.nux.ui;

import static com.nux.ui.FxTestSupport.callOnFx;
import static com.nux.ui.FxTestSupport.runOnFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.nux.config.Config;

/** Drives the settings dialog's own controls and checks what Save hands back. */
@Tag("fx")
class SettingsWindowFxTest {

    @BeforeAll
    static void boot() {
        FxTestSupport.startToolkit();
    }

    /** Opens the dialog and returns its root, plus a slot that receives the Save edit. */
    private static Parent open(Config config, AtomicReference<UnaryOperator<Config>> saved) {
        Window owner = callOnFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(), 200, 100));
            stage.show();
            return stage;
        });
        List<Window> before = callOnFx(() -> List.copyOf(Window.getWindows()));
        runOnFx(() -> SettingsWindow.show(owner, config, saved::set));
        FxTestSupport.settle();
        return callOnFx(() -> Window.getWindows().stream()
                .filter(w -> !before.contains(w))
                .findFirst()
                .orElseThrow()
                .getScene()
                .getRoot());
    }

    @Test
    void saveEditsOnlyWhatTheDialogShowsAndKeepsNewerState() {
        Config opened = Config.defaults().withLast("old-host", 3493, "cp", false);
        AtomicReference<UnaryOperator<Config>> saved = new AtomicReference<>();
        Parent root = open(opened, saved);

        runOnFx(() -> {
            ((TextField) root.lookup("#settings-host")).setText("nas.local");
            ((TextField) root.lookup("#settings-port")).setText("3494");
            ((Button) root.lookup("#settings-save")).fire();
        });

        // Meanwhile the app moved on: a reconnect picked another UPS, a self-test ran.
        Config live = opened.withLast("old-host", 3493, "rack", false)
                .withAdvanced(opened.advanced().withSelfTestLastRun(1_800_000_000L).withSelfTestArmed(1_700_000_000L));
        Config result = saved.get().apply(live);

        assertEquals("nas.local", result.lastHost());
        assertEquals(3494, result.lastPort());
        assertEquals("rack", result.lastUps(), "not rolled back to the UPS seen when the dialog opened");
        assertEquals(1_800_000_000L, result.advanced().selfTestLastRun());
        assertEquals(1_700_000_000L, result.advanced().selfTestArmed());
    }

    @Test
    void anInvalidPortOrBlankHostDisablesSave() {
        AtomicReference<UnaryOperator<Config>> saved = new AtomicReference<>();
        Parent root = open(Config.defaults(), saved);
        Button save = (Button) root.lookup("#settings-save");
        TextField host = (TextField) root.lookup("#settings-host");
        TextField port = (TextField) root.lookup("#settings-port");

        assertFalse(callOnFx(save::isDisabled));
        runOnFx(() -> port.setText("99999"));
        assertTrue(callOnFx(save::isDisabled), "out-of-range port");
        runOnFx(() -> port.setText("3493"));
        runOnFx(() -> host.setText("  "));
        assertTrue(callOnFx(save::isDisabled), "blank host");
        runOnFx(() -> host.setText("ups.lan"));
        assertFalse(callOnFx(save::isDisabled));
    }

    @Test
    void portAndTimeParsingIsStrict() {
        assertEquals(java.util.Optional.of(3493), SettingsWindow.port(" 3493 "));
        assertEquals(java.util.Optional.empty(), SettingsWindow.port("0"));
        assertEquals(java.util.Optional.empty(), SettingsWindow.port("65536"));
        assertEquals(java.util.Optional.empty(), SettingsWindow.port("http"));
        assertTrue(SettingsWindow.validTime("03:00"));
        assertFalse(SettingsWindow.validTime("3am"));
    }
}
