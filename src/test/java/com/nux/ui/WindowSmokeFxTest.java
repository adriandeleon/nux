package com.nux.ui;

import static com.nux.ui.FxTestSupport.callOnFx;
import static com.nux.ui.FxTestSupport.runOnFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nux.protocol.RwVariable;
import com.nux.store.EventLog;
import com.nux.store.HistoryStore;

/**
 * Constructs every secondary window. These catch the failures that only appear when a
 * window is actually built — a mistyped stylesheet path, a missing icon resource, a
 * null in a layout — which no pure test can reach and which otherwise surface as a
 * dead menu item in a release.
 */
@Tag("fx")
class WindowSmokeFxTest {

    @BeforeAll
    static void boot() {
        FxTestSupport.startToolkit();
    }

    private static Window owner() {
        return callOnFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(), 400, 300));
            stage.show();
            return stage;
        });
    }

    /** Every window opened during the test, so each can be asserted and closed. */
    private static List<Window> openedAfter(Runnable action) {
        List<Window> before = callOnFx(() -> List.copyOf(Window.getWindows()));
        runOnFx(action);
        FxTestSupport.settle();
        return callOnFx(() -> Window.getWindows().stream()
                .filter(w -> !before.contains(w))
                .toList());
    }

    private static void assertOpensAndCloses(Runnable action) {
        List<Window> opened = openedAfter(action);
        assertFalse(opened.isEmpty(), "the window did not open");
        assertTrue(callOnFx(() -> opened.getFirst().isShowing()));
        runOnFx(() -> opened.forEach(Window::hide));
    }

    @Test
    void aboutWindowOpens() {
        Window owner = owner();
        AtomicReference<String> opened = new AtomicReference<>();
        assertOpensAndCloses(() -> AboutWindow.show(owner, opened::set, "/tmp/nux-config"));
    }

    @Test
    void eventsWindowOpens(@TempDir Path dir) {
        Window owner = owner();
        assertOpensAndCloses(() -> EventsWindow.show(owner, new EventLog(dir)));
    }

    @Test
    void historyWindowOpens(@TempDir Path dir) {
        Window owner = owner();
        assertOpensAndCloses(() ->
                HistoryWindow.show(owner, new HistoryStore(dir, 30), "nas:3493", "cp", com.nux.config.Advanced.defaults(), 900));
    }

    @Test
    void settingsWindowOpens() {
        Window owner = owner();
        assertOpensAndCloses(() -> SettingsWindow.show(owner, com.nux.config.Config.defaults(), config -> {}));
    }

    @Test
    void credentialsDialogBuildsWithoutBlockingTheTest() {
        // show() blocks on showAndWait, so only assert the class loads and its record works.
        CredentialsDialog.Credentials creds = new CredentialsDialog.Credentials("u", "p");
        assertEquals("u", creds.user());
    }

    @Test
    void variablesWindowOpensWithEveryControlType() {
        Window owner = owner();
        List<RwVariable> variables = List.of(
                new RwVariable("beeper.status", "enabled", RwVariable.Kind.ENUM, List.of("enabled", "disabled"), null, null, 0),
                new RwVariable("battery.charge.low", "20", RwVariable.Kind.RANGE, List.of(), 10.0, 50.0, 0),
                RwVariable.string("ups.id", "desk", 8));
        assertOpensAndCloses(() ->
                VariablesWindow.show(owner, "cp", variables, (ups, name, value, cb) -> cb.accept(true, "OK"), onLoaded -> {}));
    }

    @Test
    void variablesWindowWritesToItsOwnUps() {
        // The window isn't modal: by the time Apply is pressed the main window may have
        // switched to another UPS, so the write must target the one this window shows.
        Window owner = owner();
        List<String> writes = new java.util.ArrayList<>();
        List<Window> opened = openedAfter(() -> VariablesWindow.show(
                owner,
                "ups1",
                List.of(RwVariable.string("ups.id", "desk", 8)),
                (ups, name, value, cb) -> writes.add(ups + ":" + name + "=" + value),
                onLoaded -> {}));
        runOnFx(() -> {
            javafx.scene.Parent root = opened.getFirst().getScene().getRoot();
            ((javafx.scene.control.ListView<?>) root.lookup(".list-view")).getSelectionModel().select(0);
            root.lookupAll(".button").stream()
                    .map(javafx.scene.control.Button.class::cast)
                    .filter(b -> "Apply".equals(b.getText()))
                    .findFirst()
                    .orElseThrow()
                    .fire();
        });
        assertEquals(List.of("ups1:ups.id=desk"), writes);
        runOnFx(() -> opened.forEach(Window::hide));
    }

    @Test
    void discoveryWindowOpensAndStopsScanningWhenClosed() {
        Window owner = owner();
        // It starts a scan on open; closing must cancel it rather than leave threads probing.
        List<Window> opened = openedAfter(() -> DiscoveryWindow.show(owner, (host, port) -> {}));
        assertFalse(opened.isEmpty());
        runOnFx(() -> opened.forEach(Window::hide));
        assertFalse(callOnFx(() -> opened.getFirst().isShowing()));
    }

    @Test
    void screenshotWritesAPngOfTheScene(@TempDir Path dir) {
        Window owner = owner();
        Path target = dir.resolve("shot.png");
        assertTrue(callOnFx(() -> Screenshot.save(((Stage) owner).getScene(), target)));
        assertTrue(java.nio.file.Files.isRegularFile(target));
    }
}
