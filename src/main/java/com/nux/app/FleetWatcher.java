package com.nux.app;

import static com.nux.i18n.Messages.tr;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.nux.config.Config;
import com.nux.config.ConfigHolder;
import com.nux.config.Profile;
import com.nux.protocol.StatusTransitions;
import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;
import com.nux.service.ContactTracker;
import com.nux.service.PollService;
import com.nux.store.EventLog;
import com.nux.store.HistorySample;
import com.nux.store.HistoryStore;
import com.nux.store.UpsEvent;

/**
 * Watches every other saved profile in the background, so one Nux can look after several
 * servers: each gets its own {@link PollService}, its history is recorded, its power
 * events are logged and notified (under that UPS's notification rule) and lost contact is
 * announced the same way as for the main connection. The profile the main view shows is
 * skipped — {@link ConnectionController} already covers it.
 *
 * <p>Toolkit-free: the cards go through {@link View}. One thread, like the other
 * controllers; each watcher's poll results arrive on it through its PollService.
 */
public final class FleetWatcher {

    /** One watched profile as the dashboard shows it. */
    public record Card(Profile profile, String ups, UpsSnapshot snapshot, String problem) {

        /** OK / WARNING / CRITICAL for the card's colour; unreachable is critical. */
        public UpsStatus.Severity severity() {
            if (snapshot == null) {
                return problem == null ? UpsStatus.Severity.WARNING : UpsStatus.Severity.CRITICAL;
            }
            return snapshot.status().severity();
        }
    }

    public interface View {
        /** The watched profiles, in profile order; an empty list hides the strip. */
        void showFleet(List<Card> cards);
    }

    private final ConfigHolder config;
    private final Supplier<PollService> polls;
    private final HistoryStore history;
    private final EventLog events;
    private final View view;
    private final ConnectionController.Alerts alerts;
    private final Supplier<Instant> clock;
    private final Map<Profile, Watch> watching = new LinkedHashMap<>();

    public FleetWatcher(
            ConfigHolder config,
            Supplier<PollService> polls,
            HistoryStore history,
            EventLog events,
            View view,
            ConnectionController.Alerts alerts,
            Supplier<Instant> clock) {
        this.config = config;
        this.polls = polls;
        this.history = history;
        this.events = events;
        this.view = view;
        this.alerts = alerts;
        this.clock = clock;
    }

    /**
     * Starts and stops watchers to match the saved profiles and the setting, skipping the
     * profile the main view shows ({@code activeServer} as host:port, {@code activeUps}).
     * Call it whenever either changes; unchanged watchers keep running undisturbed.
     */
    public void refresh(String activeServer, String activeUps) {
        Config c = config.get();
        List<Profile> wanted = new ArrayList<>();
        if (c.advanced().watchProfiles()) {
            for (Profile p : c.profiles()) {
                boolean shownAlready = serverKey(p).equals(activeServer)
                        && (p.ups().isBlank() || p.ups().equals(activeUps));
                if (!shownAlready) {
                    wanted.add(p);
                }
            }
        }
        watching.keySet().removeIf(p -> {
            if (wanted.contains(p)) {
                return false;
            }
            watching.get(p).stop();
            return true;
        });
        for (Profile p : wanted) {
            watching.computeIfAbsent(p, this::start);
        }
        publish();
    }

    /** Stops every watcher (quit). */
    public void shutdown() {
        watching.values().forEach(Watch::stop);
        watching.clear();
    }

    /** How many profiles are being watched; for tests. */
    int watchedCount() {
        return watching.size();
    }

    private Watch start(Profile profile) {
        Watch watch = new Watch(profile, polls.get());
        watch.poll.start(profile.host(), profile.port(), profile.ups().isBlank() ? null : profile.ups(),
                config.get().tlsModeFor(profile.tls()));
        return watch;
    }

    private void publish() {
        view.showFleet(watching.values().stream().map(Watch::card).toList());
    }

    private static String serverKey(Profile p) {
        return p.host() + ":" + p.port();
    }

    /** One watched profile: its own poller, alert state and latest reading. */
    private final class Watch {
        private final Profile profile;
        private final PollService poll;
        private final ContactTracker contact = new ContactTracker();
        private String ups; // as the server confirmed it
        private UpsStatus lastStatus;
        private UpsSnapshot latest;
        private String problem;

        Watch(Profile profile, PollService poll) {
            this.profile = profile;
            this.poll = poll;
            ups = profile.ups().isBlank() ? null : profile.ups();
            poll.setOnConnected((devices, target) -> {
                ups = target;
                contact.connected(clock.get()).ifPresent(outage -> {
                    notifyUser(false, tr("event.contactRestored"), tr("alert.contactRestored"));
                    events.append(UpsEvent.now(label(), false, tr("event.contactRestored"),
                            tr("event.downFor", ContactTracker.format(outage))));
                });
            });
            poll.setOnSnapshot(this::onSnapshot);
            poll.setOnDisconnected(this::onDisconnected);
        }

        private void onSnapshot(UpsSnapshot snapshot) {
            latest = snapshot;
            problem = null;
            history.record(serverKey(profile), snapshot.ups(), HistorySample.of(snapshot));
            UpsStatus status = snapshot.status();
            StatusTransitions.Alert alert = StatusTransitions.alert(lastStatus, status);
            if (alert != null) {
                String title = tr(alert.titleKey());
                String body = tr(alert.bodyKey());
                notifyUser(alert.critical(), title, body);
                events.append(UpsEvent.now(label(), alert.critical(), title, body));
            }
            lastStatus = status;
            publish();
        }

        private void onDisconnected(String reason) {
            latest = null;
            problem = reason;
            if (contact.failed(clock.get())) {
                notifyUser(false, tr("event.contactLost"), tr("alert.contactLost", reason));
                events.append(UpsEvent.now(label(), false, tr("event.contactLost"), reason));
            }
            publish();
        }

        /** Titles name the profile: with several servers, "On battery" alone says too little. */
        private void notifyUser(boolean critical, String title, String body) {
            Config c = config.get();
            String key = serverKey(profile) + "/" + (ups != null ? ups : "");
            if (c.notifications() && c.advanced().notifyRule(key).allows(critical)) {
                alerts.notify(tr("fleet.alertTitle", profile.name(), title), body, critical);
            }
        }

        private String label() {
            return profile.name() + (ups != null ? " (" + ups + ")" : "");
        }

        Card card() {
            return new Card(profile, ups, latest, problem);
        }

        void stop() {
            poll.shutdown();
        }
    }
}
