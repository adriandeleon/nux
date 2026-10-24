package com.nux.app;

import static com.nux.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nux.config.Config;
import com.nux.config.ConfigHolder;
import com.nux.protocol.UpsSnapshot;
import com.nux.tray.NuxTray;
import com.nux.tray.TrayDisplay;
import com.nux.tray.TrayMenu;

/**
 * The tray lifecycle with a fake backend. Backend creation is queued and run by hand, so
 * the races — the tray toggled while its backend is still starting — are reproduced
 * exactly rather than hoped for.
 */
class TrayControllerTest {

    @TempDir
    Path dir;

    private ConfigHolder config;
    private final Deque<Runnable> background = new ArrayDeque<>();
    private final List<FakeTray> created = new ArrayList<>();
    private Supplier<Optional<NuxTray>> factory = this::newTray;
    private final FakeWindow window = new FakeWindow();
    private TrayController tray;

    @BeforeEach
    void setUp() {
        config = new ConfigHolder(dir.resolve("config.properties"), Config.defaults());
        tray = new TrayController(config, () -> factory.get(), background::add, Runnable::run, window);
    }

    private Optional<NuxTray> newTray() {
        FakeTray t = new FakeTray();
        created.add(t);
        return Optional.of(t);
    }

    /** Runs the queued backend inits, as the tray-init thread would. */
    private void finishInits() {
        while (!background.isEmpty()) {
            background.poll().run();
        }
    }

    private static UpsSnapshot snapshot(String charge, String status) {
        return new UpsSnapshot("cp", Map.of("battery.charge", charge, "ups.status", status, "battery.runtime", "1500"),
                Instant.EPOCH);
    }

    private void setTray(boolean on, boolean closeToTray) {
        Config before = config.get();
        config.update(c -> new Config(
                c.profiles(), c.lastHost(), c.lastPort(), c.lastUps(), c.lastTls(), c.tlsTrustAll(),
                c.autoConnect(), on, closeToTray, c.trayMetric(), c.notifications(), c.advanced()));
        tray.settingsSaved(before);
    }

    @Test
    void aStartedTrayKeepsTheAppAliveWhenTheWindowCloses() {
        tray.start();
        finishInits();
        assertEquals(1, created.size());
        assertTrue(tray.closeHidesWindow());
        assertFalse(window.implicitExit, "closing the window must hide it, not quit");
    }

    @Test
    void withTheTrayOffClosingQuits() {
        setTray(false, true);
        tray.start();
        finishInits();
        assertEquals(0, created.size());
        assertTrue(window.implicitExit);
        assertFalse(tray.closeHidesWindow());
    }

    @Test
    void aBackendThatThrowsBringsTheWindowUpRatherThanStrandingIt() {
        // --minimized start: the window is hidden waiting on the tray that never comes.
        factory = () -> {
            throw new LinkageError("no D-Bus");
        };
        tray.start();
        finishInits();
        assertTrue(window.showing, "a hidden window with no tray is unreachable");
        assertTrue(window.implicitExit);
        assertFalse(tray.hasTray());
    }

    @Test
    void noTrayAvailableAlsoShowsTheWindow() {
        factory = Optional::empty;
        tray.start();
        finishInits();
        assertTrue(window.showing);
        assertTrue(window.implicitExit);
    }

    @Test
    void changingCloseToTrayAloneReappliesTheClosePolicy() {
        // The bug: implicit exit was only recomputed when the tray itself toggled.
        tray.start();
        finishInits();
        setTray(true, false);
        assertTrue(window.implicitExit, "close-to-tray off: closing quits");
        setTray(true, true);
        assertFalse(window.implicitExit, "close-to-tray on: closing hides");
    }

    @Test
    void turningTheTrayOffDisposesItAndBringsAHiddenWindowBack() {
        tray.start();
        finishInits();
        window.showing = false; // hidden to the tray
        setTray(false, true);
        assertTrue(created.getFirst().disposed);
        assertTrue(window.showing, "nothing left to bring it back from");
        assertTrue(window.implicitExit);
    }

    @Test
    void togglingOffAndOnWhileStartingLeavesExactlyOneLiveIcon() {
        tray.start(); // init #1 queued
        setTray(false, true); // off before #1 finished
        setTray(true, true); // on again: init #2 queued
        finishInits();
        assertEquals(2, created.size());
        assertTrue(created.get(0).disposed, "the superseded init's icon is removed");
        assertFalse(created.get(1).disposed);
        assertTrue(tray.hasTray());
    }

    @Test
    void identicalUpdatesAreNotRepaintedButARefreshIs() {
        tray.start();
        finishInits();
        FakeTray icon = created.getFirst();
        int initial = icon.updates.size(); // the refresh on creation
        tray.show(snapshot("87", "OL"));
        tray.show(snapshot("87", "OL"));
        assertEquals(initial + 1, icon.updates.size(), "same picture, no repaint");
        tray.refresh();
        assertEquals(initial + 2, icon.updates.size(), "settings changes bypass the dedupe");
    }

    @Test
    void aMetricChangeRerendersTheLastSnapshot() {
        tray.start();
        finishInits();
        tray.show(snapshot("87", "OL"));
        String before = created.getFirst().last().text();
        config.update(c -> c.withTrayMetric("runtime"));
        tray.refresh();
        String after = created.getFirst().last().text();
        assertFalse(before.equals(after), before + " vs " + after);
    }

    @Test
    void idleStatesSayWhetherItIsRetrying() {
        tray.start();
        finishInits();
        tray.showIdle(true);
        assertEquals(tr("tray.disconnected"), created.getFirst().lastTooltip);
        tray.showIdle(false);
        assertEquals(tr("tray.notConnected"), created.getFirst().lastTooltip);
        assertFalse(created.getFirst().last().connected());
    }

    @Test
    void aBackendCreatedLaterGetsTheLatestMenuAndUnchangedMenusAreNotResent() {
        TrayMenu connected = TrayMenu.quickActions(true, List.of("cp"), "cp", List.of("beeper.toggle"), "x");
        tray.setMenu(connected); // before the backend exists
        tray.start();
        finishInits();
        assertEquals(List.of(connected), created.getFirst().menus, "the new icon gets the current actions");
        tray.setMenu(TrayMenu.quickActions(true, List.of("cp"), "cp", List.of("beeper.toggle"), "x"));
        assertEquals(1, created.getFirst().menus.size(), "an equal menu isn't re-sent");
        tray.setMenu(TrayMenu.quickActions(false, List.of(), null, List.of(), "x"));
        assertEquals(2, created.getFirst().menus.size());
    }

    @Test
    void disposeRemovesTheIconAndCancelsAPendingInit() {
        tray.start();
        tray.dispose(); // quit while the backend is still starting
        finishInits();
        assertTrue(created.getFirst().disposed);
        assertFalse(tray.hasTray());
    }

    private static final class FakeTray implements NuxTray {
        final List<TrayDisplay> updates = new ArrayList<>();
        final List<TrayMenu> menus = new ArrayList<>();
        String lastTooltip;
        boolean disposed;

        @Override
        public void update(TrayDisplay display, String tooltip) {
            updates.add(display);
            lastTooltip = tooltip;
        }

        @Override
        public void setMenu(TrayMenu actions) {
            menus.add(actions);
        }

        @Override
        public void dispose() {
            disposed = true;
        }

        TrayDisplay last() {
            return updates.getLast();
        }
    }

    private static final class FakeWindow implements TrayController.Window {
        boolean showing;
        boolean implicitExit = true;

        @Override
        public boolean isShowing() {
            return showing;
        }

        @Override
        public void show() {
            showing = true;
        }

        @Override
        public void setImplicitExit(boolean exitOnLastClose) {
            implicitExit = exitOnLastClose;
        }
    }
}
