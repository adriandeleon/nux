package com.nux.app;

import static com.nux.i18n.Messages.tr;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.nux.AppInfo;
import com.nux.config.Config;
import com.nux.config.ConfigHolder;
import com.nux.config.Profile;
import com.nux.protocol.StatusTransitions;
import com.nux.protocol.UpsDevice;
import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;
import com.nux.service.ContactTracker;
import com.nux.service.PollService;
import com.nux.store.EventLog;
import com.nux.store.HistorySample;
import com.nux.store.HistoryStore;
import com.nux.store.UpsEvent;

/**
 * The connection's state machine, out of {@code App} so it can be driven against a
 * scripted upsd: connect / disconnect / switch UPS / switch server, what each poll
 * result does (dashboard, history, transition alerts, lost and restored contact), and
 * which UPS everything else may act on.
 *
 * <p>Toolkit-free: everything it shows goes through {@link View}, and every call —
 * including the {@link PollService} callbacks, which the service marshals — happens on
 * one thread (the FX thread in the app, a single executor in tests).
 */
public final class ConnectionController {

    /** What the connection changes on screen (and in the tray). */
    public interface View {
        /** Connected-or-not, for the Connect/Disconnect button and the UPS picker. */
        void showRunning(boolean running);

        /** The server's UPS list with the polled one selected; must not echo back as a switch. */
        void showUpsChoices(List<String> names, String selected);

        /** Forgets the UPS list (a different server is about to answer). */
        void clearUpsChoices();

        /** The server the next poll goes to, for the toolbar label. */
        void showServer(String host, int port, boolean tls);

        void showSnapshot(UpsSnapshot snapshot);

        void showCommands(List<String> commands);

        void showOverview(Map<String, UpsSnapshot> all, String selected);

        /** Drops variables and commands that describe the UPS as it was. */
        void clearLiveData();

        /** Restarts the sparklines: they would otherwise join two different UPSes' lines. */
        void resetTrends();

        void showDisconnected(String reason);

        /** The user disconnected: nothing is wrong. */
        void showStopped();
    }

    /** Desktop notifications; only called when the user has them switched on. */
    @FunctionalInterface
    public interface Alerts {
        void notify(String title, String body, boolean critical);
    }

    private final ConfigHolder config;
    private final PollService poll;
    private final HistoryStore history;
    private final EventLog events;
    private final View view;
    private final Alerts alerts;
    private final Supplier<Instant> clock;
    private final ContactTracker contact = new ContactTracker();

    private boolean running;
    private String selectedUps; // the UPS being polled, as the server confirmed it
    private UpsStatus lastStatus; // previous poll's status, for transition alerts
    private UpsSnapshot lastSnapshot; // latest live data; null whenever it would be stale
    private Instant onBatterySince; // when this outage began (or was first seen); null on mains
    private boolean onBatterySinceExact; // false when Nux connected mid-outage
    private Consumer<UpsSnapshot> afterSnapshot = snapshot -> {};

    public ConnectionController(
            ConfigHolder config,
            PollService poll,
            HistoryStore history,
            EventLog events,
            View view,
            Alerts alerts,
            Supplier<Instant> clock) {
        this.config = config;
        this.poll = poll;
        this.history = history;
        this.events = events;
        this.view = view;
        this.alerts = alerts;
        this.clock = clock;
        poll.setOnConnected(this::onConnected);
        poll.setOnSnapshot(this::onSnapshot);
        poll.setOnCommands(view::showCommands);
        poll.setOnOverview(all -> view.showOverview(all, selectedUps));
        poll.setOnDisconnected(this::onDisconnected);
    }

    /** Runs after each live snapshot is shown — the self-test scheduler hooks in here. */
    public void setAfterSnapshot(Consumer<UpsSnapshot> hook) {
        this.afterSnapshot = hook;
    }

    // --- Queries -------------------------------------------------------------

    public boolean isRunning() {
        return running;
    }

    /** The UPS commands may target right now, or null when there's no live connection. */
    public String activeUps() {
        return running && lastSnapshot != null ? lastSnapshot.ups() : null;
    }

    /** The UPS chosen for this server (live or not), or null before the server named one. */
    public String selectedUps() {
        return selectedUps;
    }

    /** The latest live data, or null when disconnected or between targets. */
    public UpsSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    /** When the current outage began, or null while on mains (or not connected). */
    public Instant onBatterySince() {
        return onBatterySince;
    }

    /**
     * False when Nux only saw the outage already under way (it connected mid-outage), so
     * {@link #onBatterySince} is a lower bound rather than the real start.
     */
    public boolean onBatterySinceExact() {
        return onBatterySinceExact;
    }

    /** Which server a history file belongs to; UPS names repeat across servers. */
    public String serverKey() {
        return config.get().lastHost() + ":" + config.get().lastPort();
    }

    // --- User actions ---------------------------------------------------------

    public void toggle() {
        if (running) {
            disconnect();
        } else {
            connect();
        }
    }

    public void connect() {
        running = true;
        contact.reset();
        view.showRunning(true);
        Config c = config.get();
        String ups = selectedUps != null ? selectedUps : (c.lastUps().isBlank() ? null : c.lastUps());
        poll.start(c.lastHost(), c.lastPort(), ups, c.tlsModeFor(c.lastTls()));
    }

    /**
     * Stops polling. Deliberately leaves {@code autoConnect} alone: that is the Settings
     * checkbox's to decide, and writing it here (and back on every reconnect) meant
     * unchecking it was undone by the next network blip.
     */
    public void disconnect() {
        running = false;
        poll.stop();
        contact.reset();
        onBatterySince = null;
        view.showRunning(false);
        clearLiveData();
        view.showStopped();
    }

    /** The user picked another UPS on the same server (toolbar or overview card). */
    public void switchUps(String ups) {
        if (!running || ups == null || ups.equals(selectedUps)) {
            return;
        }
        selectedUps = ups;
        startFresh(ups);
        config.update(c -> c.withLast(c.lastHost(), c.lastPort(), ups, c.lastTls()));
    }

    public void connectProfile(Profile profile) {
        forgetUps();
        config.update(c -> c.withLast(profile.host(), profile.port(), profile.ups(), profile.tls()));
        showServer();
        running = true;
        view.showRunning(true);
        startFresh(profile.ups().isBlank() ? null : profile.ups());
    }

    /** A server picked in discovery: point at it, and (re)connect. */
    public void pointAt(String host, int port) {
        config.update(c -> c.withLast(host, port, "", c.lastTls()));
        showServer();
        forgetUps();
        if (running) {
            startFresh(null);
        } else {
            connect();
        }
    }

    /**
     * Settings were saved over {@code before}. A UPS name belongs to the server it came
     * from, so a host/port change forgets it rather than carrying it to the new server.
     */
    public void settingsSaved(Config before) {
        Config now = config.get();
        boolean serverChanged = !now.lastHost().equals(before.lastHost()) || now.lastPort() != before.lastPort();
        boolean connectionChanged = serverChanged || now.lastTls() != before.lastTls();
        if (serverChanged) {
            config.update(c -> c.withLast(c.lastHost(), c.lastPort(), "", c.lastTls()));
            forgetUps();
        }
        showServer();
        if (running && connectionChanged) {
            startFresh(selectedUps);
        }
    }

    public void showServer() {
        Config c = config.get();
        view.showServer(c.lastHost(), c.lastPort(), c.lastTls());
    }

    // --- Poll results ---------------------------------------------------------

    /**
     * The picker shows exactly what the poller resolved, never a guess of its own: when
     * the two disagreed (a UPS name the new server doesn't have), the picker showed one
     * UPS while every poll asked for the missing one and reconnected.
     */
    private void onConnected(List<UpsDevice> devices, String target) {
        selectedUps = target;
        view.showUpsChoices(devices.stream().map(UpsDevice::name).toList(), target);
        contact.connected(clock.get()).ifPresent(outage -> {
            notifyUser(AppInfo.NAME, tr("alert.contactRestored"), false);
            events.append(UpsEvent.now(
                    upsLabel(), false, tr("event.contactRestored"), tr("event.downFor", ContactTracker.format(outage))));
        });
        if (target != null && !target.equals(config.get().lastUps())) {
            config.update(c -> c.withLast(c.lastHost(), c.lastPort(), target, c.lastTls()));
        }
    }

    private void onSnapshot(UpsSnapshot snapshot) {
        lastSnapshot = snapshot;
        trackOutage(snapshot.status());
        view.showSnapshot(snapshot);
        history.record(serverKey(), snapshot.ups(), HistorySample.of(snapshot));

        UpsStatus status = snapshot.status();
        StatusTransitions.Alert alert = StatusTransitions.alert(lastStatus, status);
        if (alert != null) {
            // The alert carries catalogue keys; resolve them once, here at the edge.
            String title = tr(alert.titleKey());
            String body = tr(alert.bodyKey());
            notifyUser(title, body, alert.critical());
            events.append(UpsEvent.now(snapshot.ups(), alert.critical(), title, body));
        }
        lastStatus = status;
        afterSnapshot.accept(snapshot);
    }

    private void onDisconnected(String reason) {
        view.showDisconnected(reason);
        clearLiveData();
        if (running && contact.failed(clock.get())) {
            notifyUser(AppInfo.NAME, tr("alert.contactLost", reason), false);
            events.append(UpsEvent.now(upsLabel(), false, tr("event.contactLost"), reason));
        }
    }

    // --- Internals ------------------------------------------------------------

    /** Starts the on-battery clock on the transition (exact) or the first sighting (a lower bound). */
    private void trackOutage(UpsStatus status) {
        if (!status.onBattery()) {
            onBatterySince = null;
        } else if (onBatterySince == null) {
            onBatterySince = clock.get();
            onBatterySinceExact = lastStatus != null; // seen on mains first, so this is the start
        }
    }

    /** Restart polling against a (possibly) different target: history and alert state reset. */
    private void startFresh(String ups) {
        // A different target: its outage clock starts over. A mere connection blip keeps
        // both (startFresh isn't called), so an outage keeps counting through it.
        lastStatus = null;
        onBatterySince = null;
        contact.reset();
        clearLiveData();
        view.resetTrends();
        Config c = config.get();
        poll.start(c.lastHost(), c.lastPort(), ups, c.tlsModeFor(c.lastTls()));
    }

    /**
     * Drops everything that describes the UPS as it was. Kept, it read as live data and
     * offered commands that could only fail.
     */
    private void clearLiveData() {
        lastSnapshot = null;
        view.clearLiveData();
    }

    private void forgetUps() {
        selectedUps = null;
        view.clearUpsChoices();
    }

    private String upsLabel() {
        return selectedUps != null ? selectedUps : config.get().lastHost();
    }

    private void notifyUser(String title, String body, boolean critical) {
        if (config.get().notifications()) {
            alerts.notify(title, body, critical);
        }
    }
}
