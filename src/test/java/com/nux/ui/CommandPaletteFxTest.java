package com.nux.ui;

import static com.nux.ui.FxTestSupport.callOnFx;
import static com.nux.ui.FxTestSupport.field;
import static com.nux.ui.FxTestSupport.runOnFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.nux.command.Command;
import com.nux.command.CommandRegistry;

/** The palette's keyboard flow: filter, choose, run, dismiss. */
@Tag("fx")
class CommandPaletteFxTest {

    @BeforeAll
    static void boot() {
        FxTestSupport.startToolkit();
    }

    private record Fixture(StackPane host, CommandPalette palette, AtomicReference<String> ran, AtomicInteger runs) {}

    private static Fixture fixture() {
        AtomicReference<String> ran = new AtomicReference<>();
        AtomicInteger runs = new AtomicInteger();
        CommandRegistry registry = new CommandRegistry();
        registry.register("app.connect", "Connect / Disconnect", () -> {
            ran.set("app.connect");
            runs.incrementAndGet();
        });
        registry.register("app.settings", "Open Settings…", () -> {
            ran.set("app.settings");
            runs.incrementAndGet();
        });
        registry.register("ups.cmd.beeper.toggle", "UPS command: beeper.toggle", () -> {
            ran.set("ups.cmd.beeper.toggle");
            runs.incrementAndGet();
        });

        StackPane host = callOnFx(() -> {
            StackPane pane = new StackPane();
            // A palette must live in a scene for focus and key handling to behave.
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 800, 600));
            stage.show();
            return pane;
        });
        List<Command> commands = registry.all();
        return new Fixture(host, new CommandPalette(host, () -> commands), ran, runs);
    }

    private static TextField input(CommandPalette palette) {
        javafx.scene.layout.VBox card = field(palette, "card");
        return (TextField) card.getChildren().get(0);
    }

    @SuppressWarnings("unchecked")
    private static ListView<Command> list(CommandPalette palette) {
        javafx.scene.layout.VBox card = field(palette, "card");
        return (ListView<Command>) card.getChildren().get(1);
    }

    private static void press(TextField input, KeyCode code) {
        runOnFx(() -> input.getOnKeyPressed()
                .handle(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

    @Test
    void togglingAddsAndRemovesTheOverlay() {
        Fixture f = fixture();
        int before = callOnFx(() -> f.host().getChildren().size());

        runOnFx(f.palette()::toggle);
        assertEquals(before + 2, callOnFx(() -> f.host().getChildren().size()), "backdrop + card");

        runOnFx(f.palette()::toggle);
        assertEquals(before, callOnFx(() -> f.host().getChildren().size()));
    }

    @Test
    void opensWithEveryCommandAndTheFirstSelected() {
        Fixture f = fixture();
        runOnFx(f.palette()::toggle);
        ListView<Command> list = list(f.palette());
        assertEquals(3, list.getItems().size());
        assertEquals(0, list.getSelectionModel().getSelectedIndex());
    }

    @Test
    void typingNarrowsTheList() {
        Fixture f = fixture();
        runOnFx(f.palette()::toggle);
        TextField input = input(f.palette());

        runOnFx(() -> input.setText("beeper"));
        ListView<Command> list = list(f.palette());
        assertEquals(1, list.getItems().size());
        assertEquals("ups.cmd.beeper.toggle", list.getItems().getFirst().id());

        runOnFx(() -> input.setText("zzz"));
        assertTrue(list.getItems().isEmpty());
    }

    @Test
    void enterRunsTheSelectedCommandAndCloses() {
        Fixture f = fixture();
        runOnFx(f.palette()::toggle);
        TextField input = input(f.palette());
        runOnFx(() -> input.setText("settings"));
        press(input, KeyCode.ENTER);

        assertEquals("app.settings", f.ran().get());
        assertEquals(1, f.runs().get());
        assertNull(callOnFx(() -> f.host().lookup(".palette-card")), "the palette should have closed");
    }

    @Test
    void arrowKeysMoveTheSelection() {
        Fixture f = fixture();
        runOnFx(f.palette()::toggle);
        TextField input = input(f.palette());
        ListView<Command> list = list(f.palette());

        press(input, KeyCode.DOWN);
        assertEquals(1, list.getSelectionModel().getSelectedIndex());
        press(input, KeyCode.UP);
        assertEquals(0, list.getSelectionModel().getSelectedIndex());
        // Selection must not run off either end.
        press(input, KeyCode.UP);
        assertEquals(0, list.getSelectionModel().getSelectedIndex());
    }

    @Test
    void escapeDismissesWithoutRunningAnything() {
        Fixture f = fixture();
        runOnFx(f.palette()::toggle);
        press(input(f.palette()), KeyCode.ESCAPE);

        assertNull(f.ran().get());
        assertEquals(0, f.runs().get());
        assertNull(callOnFx(() -> f.host().lookup(".palette-card")));
    }

    @Test
    void enterOnAnEmptyResultListDoesNothingHarmful() {
        Fixture f = fixture();
        runOnFx(f.palette()::toggle);
        TextField input = input(f.palette());
        runOnFx(() -> input.setText("no such command"));
        press(input, KeyCode.ENTER);

        assertNull(f.ran().get());
        assertFalse(f.runs().get() > 0);
    }
}
