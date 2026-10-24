package com.nux.app;

import static com.nux.app.AppHarness.await;
import static com.nux.app.AppHarness.ticks;
import static com.nux.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nux.config.Config;
import com.nux.config.NotifyRule;
import com.nux.config.Profile;
import com.nux.protocol.FakeNutServer;
import com.nux.service.PollService;
import com.nux.store.UpsEvent;

/** Background watching of other saved servers, against scripted upsds. */
class FleetWatcherTest {

    @TempDir
    Path dir;

    private AppHarness app;
    private FakeNutServer rack;
    private FleetWatcher fleet;
    private final List<List<FleetWatcher.Card>> shown = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        app = new AppHarness(dir);
        rack = new FakeNutServer();
        rack.on("LIST UPS", "BEGIN LIST UPS", "UPS rack \"Rack\"", "END LIST UPS");
        status("OL");
        rack.on("LIST CMD rack", "BEGIN LIST CMD rack", "END LIST CMD rack");
        fleet = app.onFx(() -> new FleetWatcher(
                app.config, () -> new PollService(app.fx::execute, AppHarness.TICK), app.history, app.events,
                shown::add, (title, body, critical) -> app.notices.add(title + "|" + body), Instant::now));
    }

    @AfterEach
    void tearDown() {
        app.runFx(fleet::shutdown);
        rack.close();
        app.close();
    }

    private void status(String status) {
        rack.replace("LIST VAR rack", "BEGIN LIST VAR rack", "VAR rack battery.charge \"90\"",
                "VAR rack ups.status \"" + status + "\"", "END LIST VAR rack");
    }

    private Profile rackProfile() {
        return new Profile("rack-room", rack.host(), rack.port(), "rack", false);
    }

    /** Saves the rack profile and switches watching on or off. */
    private void configure(boolean watch) {
        app.config.update(c -> {
            Config saved = c.withProfileSaved(rackProfile());
            var a = saved.advanced();
            return saved.withAdvanced(new com.nux.config.Advanced(
                    a.historyDays(), a.nominalWatts(), a.tariffPerKwh(), a.currency(), a.selfTestEnabled(),
                    a.selfTestIntervalDays(), a.selfTestTime(), a.selfTestLastRun(), a.selfTestArmed(),
                    a.selfTestCommand(), a.singleInstance(), a.uiLanguage(), a.updateCheck(),
                    a.lastUpdateCheckMs(), a.dismissedUpdateVersion(), a.theme(), a.notifyRules(), watch));
        });
    }

    private List<FleetWatcher.Card> latest() {
        return shown.isEmpty() ? List.of() : shown.getLast();
    }

    private List<String> eventTitles() {
        return app.events.load().stream().map(UpsEvent::title).toList();
    }

    @Test
    void withWatchingOffNothingIsPolled() {
        configure(false);
        app.runFx(() -> fleet.refresh("main:3493", "cp"));
        ticks(3);
        assertEquals(List.of(), rack.received());
        assertEquals(List.of(), latest());
    }

    @Test
    void aWatchedServerShowsAsACardAndRecordsHistory() {
        configure(true);
        app.runFx(() -> fleet.refresh("main:3493", "cp"));
        await("the rack card", () -> latest().size() == 1 && latest().getFirst().snapshot() != null);
        FleetWatcher.Card card = latest().getFirst();
        assertEquals("rack-room", card.profile().name());
        assertEquals("rack", card.ups());
        await("history", () -> !app.history.load(rack.host() + ":" + rack.port(), "rack", 0).isEmpty());
    }

    @Test
    void itsPowerEventsAreLoggedAndNotifiedUnderItsName() {
        configure(true);
        app.runFx(() -> fleet.refresh("main:3493", "cp"));
        await("on mains", () -> !latest().isEmpty() && latest().getFirst().snapshot() != null);
        status("OB DISCHRG");
        await("logged", () -> eventTitles().contains(tr("alert.onBattery.title")));
        assertTrue(app.events.load().getFirst().ups().startsWith("rack-room"));
        await("notified", () -> !app.notices.isEmpty());
        assertTrue(app.notices.getFirst().startsWith(tr("fleet.alertTitle", "rack-room", tr("alert.onBattery.title"))));
    }

    @Test
    void itsOwnNotificationRuleApplies() {
        configure(true);
        app.config.update(c -> c.withAdvanced(c.advanced()
                .withNotifyRule(rack.host() + ":" + rack.port() + "/rack", NotifyRule.NONE)));
        app.runFx(() -> fleet.refresh("main:3493", "cp"));
        await("on mains", () -> !latest().isEmpty() && latest().getFirst().snapshot() != null);
        status("OB");
        await("logged", () -> eventTitles().contains(tr("alert.onBattery.title")));
        assertEquals(List.of(), app.notices);
    }

    @Test
    void aServerThatGoesAwayIsAnnouncedAndItsCardSaysSo() {
        configure(true);
        app.runFx(() -> fleet.refresh("main:3493", "cp"));
        await("on mains", () -> !latest().isEmpty() && latest().getFirst().snapshot() != null);
        rack.hangUp(true);
        await("lost", () -> eventTitles().contains(tr("event.contactLost")));
        await("card", () -> latest().getFirst().problem() != null);
        rack.hangUp(false);
        await("restored", () -> eventTitles().contains(tr("event.contactRestored")));
    }

    @Test
    void theProfileOnScreenIsNotWatchedTwiceAndARemovedOneStops() {
        configure(true);
        String rackServer = rack.host() + ":" + rack.port();
        app.runFx(() -> fleet.refresh(rackServer, "rack"));
        assertEquals(0, app.onFx(fleet::watchedCount), "the main view already covers it");

        app.runFx(() -> fleet.refresh("main:3493", "cp"));
        await("watched", () -> app.onFx(fleet::watchedCount) == 1);
        app.config.update(c -> c.withProfileRemoved("rack-room"));
        app.runFx(() -> fleet.refresh("main:3493", "cp"));
        assertEquals(0, app.onFx(fleet::watchedCount));
        ticks(1);
        long polls = rack.received().stream().filter("LIST VAR rack"::equals).count();
        ticks(4);
        assertEquals(polls, rack.received().stream().filter("LIST VAR rack"::equals).count(), "stopped polling");
    }
}
