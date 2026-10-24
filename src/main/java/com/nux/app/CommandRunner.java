package com.nux.app;

import static com.nux.i18n.Messages.tr;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.nux.AppInfo;
import com.nux.analysis.TestSchedule;
import com.nux.config.Advanced;
import com.nux.config.ConfigHolder;
import com.nux.protocol.NutException;
import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;
import com.nux.service.PollService;
import com.nux.store.EventLog;
import com.nux.store.UpsEvent;

/**
 * Everything that needs upsd credentials: instant commands, writable variables and the
 * scheduled self-test, plus the session's cached login. Out of {@code App} so the
 * confirm → log in → run → report flow can be driven against a scripted upsd.
 *
 * <p>Credentials are session-only by design and never persisted; that is also why a
 * scheduled test only runs once the user has logged in this session. Toolkit-free: the
 * dialogs go through {@link Prompts}. One thread, like {@link ConnectionController}.
 */
public final class CommandRunner {

    /** The dialogs a command may need. */
    public interface Prompts {
        /** "Run {command} on {ups}?" — the dialog decides how loudly to warn. */
        boolean confirm(String command, String ups);

        /** Asks for upsd credentials; empty when cancelled. */
        Optional<Credentials> credentials(String header, String initialUser);

        void info(String title, String body);

        void commandFailed(String command, String reason);
    }

    public record Credentials(String user, String password) {}

    private final ConfigHolder config;
    private final PollService poll;
    private final ConnectionController connection;
    private final EventLog events;
    private final Prompts prompts;
    private final ConnectionController.Alerts alerts;
    private final Supplier<Instant> clock;
    private final ZoneId zone;

    private String authUser; // session-cached upsd credentials
    private String authPass;

    public CommandRunner(
            ConfigHolder config,
            PollService poll,
            ConnectionController connection,
            EventLog events,
            Prompts prompts,
            ConnectionController.Alerts alerts,
            Supplier<Instant> clock,
            ZoneId zone) {
        this.config = config;
        this.poll = poll;
        this.connection = connection;
        this.events = events;
        this.prompts = prompts;
        this.alerts = alerts;
        this.clock = clock;
        this.zone = zone;
    }

    /** Runs an instant command on the live UPS, after confirmation (and login, once). */
    public void run(String command) {
        String ups = connection.activeUps();
        if (ups == null) {
            prompts.info(tr("error.notConnected"), tr("error.notConnectedBody"));
            return;
        }
        if (!prompts.confirm(command, ups) || !ensureCredentials(tr("dialog.credentials.instcmd"))) {
            return;
        }
        poll.runInstCmd(ups, authUser, authPass, command, (ok, message) -> {
            if (ok) {
                if (config.get().notifications()) {
                    alerts.notify(AppInfo.NAME, tr("alert.commandSent", command, ups), false);
                }
            } else {
                forgetCredentialsIfRejected(message);
                prompts.commandFailed(command, friendlyError(message));
            }
        });
    }

    public void runSelfTestNow() {
        run(config.get().advanced().selfTestCommand());
    }

    /** Applies a writable variable on {@code ups} — the one the editor shows, not the current pick. */
    public void setVariable(String ups, String name, String value, BiConsumer<Boolean, String> onResult) {
        if (!connection.isRunning()) {
            onResult.accept(false, tr("error.notConnected"));
            return;
        }
        if (!ensureCredentials(tr("dialog.credentials.setvar"))) {
            onResult.accept(false, tr("error.cancelled"));
            return;
        }
        poll.setVariable(ups, authUser, authPass, name, value, (ok, message) -> {
            if (ok) {
                events.append(UpsEvent.now(ups, false, tr("alert.variableChanged"), name + " = " + value));
            } else {
                forgetCredentialsIfRejected(message);
            }
            onResult.accept(ok, ok ? message : friendlyError(message));
        });
    }

    /**
     * Fires the scheduled self-test when it comes due. lastRun is stamped <em>before</em>
     * the command so a failing test can't retry on every poll.
     */
    public void maybeRunScheduledTest(UpsSnapshot snapshot) {
        Advanced adv = config.get().advanced();
        if (!adv.selfTestEnabled()) {
            return;
        }
        Instant now = clock.get();
        if (adv.selfTestArmed() <= 0) {
            // Enabled by a config that predates arming: anchor it now, as Settings would have.
            config.update(c -> c.withAdvanced(c.advanced().armSelfTest(c.advanced(), now.getEpochSecond())));
            return; // armed at "now", so it can't be due on this tick anyway
        }
        if (authPass == null || authUser == null) {
            return;
        }
        // Never drain a battery the load may need: a due test waits (still due) until the
        // UPS is back on mains.
        UpsStatus status = snapshot.status();
        if (!status.online() || status.onBattery() || status.lowBattery()) {
            return;
        }
        if (!TestSchedule.isDue(
                adv.selfTestLastRun(),
                adv.selfTestArmed(),
                adv.selfTestIntervalDays(),
                TestSchedule.parseTime(adv.selfTestTime()),
                zone,
                now)) {
            return;
        }
        config.update(c -> c.withAdvanced(c.advanced().withSelfTestLastRun(now.getEpochSecond())));
        String ups = snapshot.ups();
        String command = adv.selfTestCommand();
        poll.runInstCmd(ups, authUser, authPass, command, (ok, message) -> events.append(UpsEvent.now(
                ups,
                !ok,
                ok ? tr("alert.selfTestStarted") : tr("alert.selfTestFailed"),
                ok ? command : friendlyError(message))));
    }

    /** Whether this session has credentials cached (for tests and the settings note). */
    public boolean hasCredentials() {
        return authPass != null;
    }

    /** Prompts for credentials when none are cached; false when the user cancelled. */
    private boolean ensureCredentials(String header) {
        if (authPass != null) {
            return true;
        }
        Optional<Credentials> creds = prompts.credentials(header, authUser != null ? authUser : "");
        if (creds.isEmpty() || creds.get().user().isEmpty()) {
            return false;
        }
        authUser = creds.get().user();
        authPass = creds.get().password();
        return true;
    }

    /**
     * Re-prompts next time only when upsd rejected the credentials themselves; a command
     * the UPS doesn't support says nothing about the password.
     */
    private void forgetCredentialsIfRejected(String message) {
        if (NutException.isAuthFailure(message)) {
            authPass = null;
        }
    }

    static String friendlyError(String message) {
        return switch (message) {
            case "ACCESS-DENIED" -> tr("error.accessDenied");
            case "USERNAME-REQUIRED", "PASSWORD-REQUIRED" -> tr("error.authRequired");
            case "CMD-NOT-SUPPORTED" -> tr("error.notSupported");
            default -> message;
        };
    }
}
