package com.nux.app;

import static com.nux.app.AppHarness.await;
import static com.nux.app.AppHarness.ticks;
import static com.nux.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nux.config.Config;
import com.nux.config.Profile;
import com.nux.protocol.FakeNutServer;
import com.nux.store.UpsEvent;

/**
 * The connection state machine against a scripted upsd: what the screen is told, what
 * is saved, what is logged and notified — for connect, UPS and server switches,
 * transitions, and the server vanishing and coming back.
 */
class ConnectionControllerTest {

    @TempDir
    Path dir;

    private AppHarness app;

    @BeforeEach
    void setUp() throws IOException {
        app = new AppHarness(dir);
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    private List<String> eventTitles() {
        return app.events.load().stream().map(UpsEvent::title).toList();
    }

    @Test
    void connectingShowsTheUpsPollsItAndRemembersIt() {
        app.oneUps("OL");
        app.runFx(app.connection::connect);

        await("a snapshot", () -> app.view.saw("snapshot:cp:OL"));
        assertTrue(app.view.saw("running:true"));
        assertTrue(app.view.saw("choices:[cp]@cp"));
        await("the command list", () -> app.view.saw("commands:[beeper.toggle]"));
        assertEquals("cp", app.onFx(app.connection::activeUps));
        assertEquals("cp", app.onFx(() -> app.config.get().lastUps()), "the server's pick is saved");
        String server = app.onFx(app.connection::serverKey);
        await("a history row", () -> !app.history.load(server, "cp", 0).isEmpty());
    }

    @Test
    void aUpsNameTheServerDoesNotHaveFallsBackInsteadOfLooping() {
        // e.g. renamed in ups.conf: polling the missing name used to reconnect every tick.
        app.oneUps("OL");
        app.config.update(c -> c.withLast(c.lastHost(), c.lastPort(), "gone", false));
        app.runFx(app.connection::connect);

        await("a snapshot", () -> app.view.saw("snapshot:cp:OL"));
        assertTrue(app.view.saw("choices:[cp]@cp"));
        assertFalse(app.server.received().contains("LIST VAR gone"));
        assertFalse(app.view.saw("disconnected"));
        assertEquals("cp", app.onFx(() -> app.config.get().lastUps()));
    }

    @Test
    void aStatusTransitionIsNotifiedAndLogged() {
        app.oneUps("OL");
        app.runFx(app.connection::connect);
        await("on mains", () -> app.view.saw("snapshot:cp:OL"));

        app.status("cp", "OB DISCHRG");
        await("on battery", () -> app.view.saw("snapshot:cp:OB DISCHRG"));
        await("the event", () -> eventTitles().contains(tr("alert.onBattery.title")));
        assertTrue(app.events.load().getFirst().critical());
        assertTrue(app.notices.stream().anyMatch(n -> n.startsWith(tr("alert.onBattery.title"))));

        app.status("cp", "OL CHRG");
        await("power restored", () -> eventTitles().contains(tr("alert.powerRestored.title")));
    }

    @Test
    void theOutageClockStartsOnTheTransitionAndStopsBackOnMains() {
        app.oneUps("OL");
        app.runFx(app.connection::connect);
        await("on mains", () -> app.view.saw("snapshot:cp:OL"));
        assertNull(app.onFx(app.connection::onBatterySince));

        app.status("cp", "OB DISCHRG");
        await("on battery", () -> app.view.saw("snapshot:cp:OB DISCHRG"));
        assertTrue(app.onFx(() -> app.connection.onBatterySince() != null));
        assertTrue(app.onFx(app.connection::onBatterySinceExact), "the transition was seen: a real start");

        app.status("cp", "OL CHRG");
        await("back on mains", () -> app.view.saw("snapshot:cp:OL CHRG"));
        assertNull(app.onFx(app.connection::onBatterySince));
    }

    @Test
    void connectingMidOutageGivesOnlyALowerBound() {
        app.oneUps("OB DISCHRG");
        app.runFx(app.connection::connect);
        await("on battery", () -> app.view.saw("snapshot:cp:OB DISCHRG"));
        assertTrue(app.onFx(() -> app.connection.onBatterySince() != null));
        assertFalse(app.onFx(app.connection::onBatterySinceExact), "it was already on battery: at least this long");
    }

    @Test
    void aConnectionBlipDoesNotRestartTheOutageClock() {
        app.oneUps("OL");
        app.runFx(app.connection::connect);
        await("on mains", () -> app.view.saw("snapshot:cp:OL"));
        app.status("cp", "OB");
        await("on battery", () -> app.view.saw("snapshot:cp:OB"));
        java.time.Instant since = app.onFx(app.connection::onBatterySince);

        app.server.hangUp(true);
        await("lost", () -> app.view.saw("disconnected"));
        app.server.hangUp(false);
        int seen = app.view.calls.size();
        await("back", () -> app.view.calls.subList(seen, app.view.calls.size()).contains("snapshot:cp:OB"));
        assertEquals(since, app.onFx(app.connection::onBatterySince));
        assertTrue(app.onFx(app.connection::onBatterySinceExact));
    }

    @Test
    void withNotificationsOffEventsAreStillLogged() {
        app.config.update(c -> new Config(
                c.profiles(), c.lastHost(), c.lastPort(), c.lastUps(), c.lastTls(), c.tlsTrustAll(),
                c.autoConnect(), c.tray(), c.closeToTray(), c.trayMetric(), false, c.advanced()));
        app.oneUps("OL");
        app.runFx(app.connection::connect);
        await("on mains", () -> app.view.saw("snapshot:cp:OL"));
        app.status("cp", "OB");
        await("the event", () -> eventTitles().contains(tr("alert.onBattery.title")));
        assertEquals(List.of(), app.notices);
    }

    @Test
    void aServerThatVanishesIsAnnouncedOnceAndItsReturnTimed() {
        app.oneUps("OL");
        app.runFx(app.connection::connect);
        await("connected", () -> app.view.saw("snapshot:cp:OL"));

        app.server.hangUp(true);
        await("lost contact announced", () -> eventTitles().contains(tr("event.contactLost")));
        assertNull(app.onFx(app.connection::activeUps), "no live UPS to command while it's gone");
        ticks(5);
        assertEquals(1, eventTitles().stream().filter(tr("event.contactLost")::equals).count(), "once per outage");

        app.server.hangUp(false);
        await("restoration logged", () -> eventTitles().contains(tr("event.contactRestored")));
        UpsEvent restored = app.events.load().getFirst();
        assertTrue(restored.message().startsWith(tr("event.downFor", "").trim()), restored.message());
        assertEquals(2, app.notices.size(), "lost + restored: " + app.notices);
    }

    @Test
    void aServerThatNeverAnsweredIsNotAnnouncedAsLost() {
        app.oneUps("OL");
        app.server.hangUp(true);
        app.runFx(app.connection::connect);
        await("the dashboard shows it", () -> app.view.saw("disconnected"));
        ticks(6);
        assertEquals(List.of(), app.notices);
        assertEquals(List.of(), eventTitles());
    }

    @Test
    void disconnectStopsPollingAndLeavesAutoConnectAlone() {
        app.oneUps("OL");
        app.runFx(app.connection::connect);
        await("connected", () -> app.view.saw("snapshot:cp:OL"));

        app.runFx(app.connection::disconnect);
        assertTrue(app.view.saw("stopped"));
        assertTrue(app.view.saw("running:false"));
        assertNull(app.onFx(app.connection::activeUps));
        assertTrue(app.onFx(() -> app.config.get().autoConnect()), "that is the checkbox's to decide");
        ticks(1); // a poll already in flight may still land (and be dropped)
        long polls = app.server.received().stream().filter("LIST VAR cp"::equals).count();
        ticks(4);
        assertEquals(polls, app.server.received().stream().filter("LIST VAR cp"::equals).count());
    }

    @Test
    void switchingUpsRestartsOnTheOtherOneAndSavesIt() {
        app.server.on("LIST UPS", "BEGIN LIST UPS", "UPS a \"A\"", "UPS b \"B\"", "END LIST UPS")
                .on("LIST CMD a", "BEGIN LIST CMD a", "END LIST CMD a")
                .on("LIST CMD b", "BEGIN LIST CMD b", "END LIST CMD b");
        app.status("a", "OL").status("b", "OB");
        app.runFx(app.connection::connect);
        await("a", () -> app.view.saw("snapshot:a:OL"));
        await("the overview", () -> app.view.saw("overview:[a, b]@a"));

        app.runFx(() -> app.connection.switchUps("b"));
        await("b", () -> app.view.saw("snapshot:b:OB"));
        assertTrue(app.view.saw("resetTrends"), "b's sparklines must not continue a's");
        assertEquals("b", app.onFx(() -> app.config.get().lastUps()));
        assertEquals("b", app.onFx(app.connection::selectedUps));
    }

    @Test
    void changingServerInSettingsForgetsTheOldUpsName() throws IOException {
        try (FakeNutServer other = new FakeNutServer()) {
            other.on("LIST UPS", "BEGIN LIST UPS", "UPS rack \"Rack\"", "END LIST UPS")
                    .on("LIST VAR rack", "BEGIN LIST VAR rack", "VAR rack ups.status \"OL\"", "END LIST VAR rack")
                    .on("LIST CMD rack", "BEGIN LIST CMD rack", "END LIST CMD rack");
            app.oneUps("OL");
            app.runFx(app.connection::connect);
            await("cp", () -> app.view.saw("snapshot:cp:OL"));

            Config before = app.onFx(app.config::get);
            app.runFx(() -> {
                app.config.update(c -> c.withLast(other.host(), other.port(), c.lastUps(), false));
                app.connection.settingsSaved(before);
            });
            await("rack on the new server", () -> app.view.saw("snapshot:rack:OL"));
            assertTrue(app.view.saw("clearChoices"));
            assertTrue(app.view.saw("server:" + other.port()));
            assertFalse(other.received().contains("LIST VAR cp"), "cp belongs to the old server");
            assertEquals("rack", app.onFx(() -> app.config.get().lastUps()));
        }
    }

    @Test
    void aDiscoveredServerIsConnectedToEvenWhenStopped() throws IOException {
        try (FakeNutServer other = new FakeNutServer()) {
            other.on("LIST UPS", "BEGIN LIST UPS", "UPS rack \"Rack\"", "END LIST UPS")
                    .on("LIST VAR rack", "BEGIN LIST VAR rack", "VAR rack ups.status \"OL\"", "END LIST VAR rack")
                    .on("LIST CMD rack", "BEGIN LIST CMD rack", "END LIST CMD rack");
            app.runFx(() -> app.connection.pointAt(other.host(), other.port()));
            await("rack", () -> app.view.saw("snapshot:rack:OL"));
            assertTrue(app.onFx(app.connection::isRunning));
            assertEquals(other.port(), app.onFx(() -> app.config.get().lastPort()));
        }
    }

    @Test
    void aProfileConnectsToItsServerAndUps() {
        app.server.on("LIST UPS", "BEGIN LIST UPS", "UPS a \"A\"", "UPS b \"B\"", "END LIST UPS")
                .on("LIST CMD b", "BEGIN LIST CMD b", "END LIST CMD b");
        app.status("a", "OL").status("b", "OL");
        Profile profile = new Profile("rack", app.server.host(), app.server.port(), "b", false);
        app.runFx(() -> app.connection.connectProfile(profile));
        await("b", () -> app.view.saw("snapshot:b:OL"));
        assertFalse(app.view.saw("snapshot:a:OL"), "the profile names b, not the first listed");
        assertTrue(app.view.saw("running:true"));
        assertEquals("b", app.onFx(() -> app.config.get().lastUps()));
    }
}
