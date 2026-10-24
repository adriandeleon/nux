package com.nux.app;

import static com.nux.app.AppHarness.await;
import static com.nux.app.AppHarness.ticks;
import static com.nux.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nux.config.Advanced;
import com.nux.store.UpsEvent;

/** Confirm → log in → run → report, and the scheduled self-test, against a scripted upsd. */
class CommandRunnerTest {

    @TempDir
    Path dir;

    private AppHarness app;
    private FakePrompts prompts;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-02T10:00:00Z"));
    private CommandRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        app = new AppHarness(dir);
        prompts = new FakePrompts();
        runner = app.onFx(() -> new CommandRunner(
                app.config, app.poll, app.connection, app.events, prompts,
                (title, body, critical) -> app.notices.add(title + "|" + body), now::get, ZoneOffset.UTC));
        app.runFx(() -> app.connection.setAfterSnapshot(runner::maybeRunScheduledTest));
        app.server.on("USERNAME admin", "OK").on("PASSWORD pw", "OK");
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    private void connect(String status) {
        app.oneUps(status);
        app.runFx(app.connection::connect);
        await("connected", () -> app.view.sawPrefix("snapshot:cp:"));
    }

    private long sent(String line) {
        return app.server.received().stream().filter(line::equals).count();
    }

    private List<String> eventTitles() {
        return app.events.load().stream().map(UpsEvent::title).toList();
    }

    @Test
    void withoutALiveUpsItSaysSoInsteadOfAsking() {
        app.runFx(() -> runner.run("beeper.toggle"));
        assertEquals(List.of("info:" + tr("error.notConnected")), prompts.calls);
    }

    @Test
    void aDeclinedConfirmationSendsNothing() {
        connect("OL");
        prompts.confirmAnswer = false;
        app.runFx(() -> runner.run("load.off"));
        assertEquals(List.of("confirm:load.off@cp"), prompts.calls, "no login prompt after a No");
        ticks(2);
        assertEquals(0, sent("INSTCMD cp load.off"));
    }

    @Test
    void logsInOnceThenRunsCommandsWithoutAsking() {
        connect("OL");
        app.server.on("INSTCMD cp beeper.toggle", "OK");
        prompts.credentials.add(new CommandRunner.Credentials("admin", "pw"));

        app.runFx(() -> runner.run("beeper.toggle"));
        await("first command", () -> sent("INSTCMD cp beeper.toggle") == 1);
        app.runFx(() -> runner.run("beeper.toggle"));
        await("second command", () -> sent("INSTCMD cp beeper.toggle") == 2);

        assertEquals(1, prompts.calls.stream().filter(c -> c.startsWith("credentials")).count());
        await("success notice", () -> app.notices.size() == 2);
        assertTrue(app.notices.getFirst().contains("beeper.toggle"));
    }

    @Test
    void aCommandTheUpsRefusesExplainsItselfAndKeepsTheLogin() {
        connect("OL");
        app.server.on("INSTCMD cp load.on", "ERR CMD-NOT-SUPPORTED").on("INSTCMD cp beeper.toggle", "OK");
        prompts.credentials.add(new CommandRunner.Credentials("admin", "pw"));

        app.runFx(() -> runner.run("load.on"));
        await("the failure", () -> prompts.calls.contains("failed:load.on:" + tr("error.notSupported")));
        app.runFx(() -> runner.run("beeper.toggle"));
        await("the next command", () -> sent("INSTCMD cp beeper.toggle") == 1);
        assertEquals(1, prompts.calls.stream().filter(c -> c.startsWith("credentials")).count(),
                "an unsupported command says nothing about the password");
    }

    @Test
    void rejectedCredentialsAreAskedForAgain() {
        connect("OL");
        app.server.on("INSTCMD cp beeper.toggle", "ERR ACCESS-DENIED");
        prompts.credentials.add(new CommandRunner.Credentials("admin", "pw"));
        prompts.credentials.add(new CommandRunner.Credentials("admin", "pw"));

        app.runFx(() -> runner.run("beeper.toggle"));
        await("the refusal", () -> prompts.calls.contains("failed:beeper.toggle:" + tr("error.accessDenied")));
        await("reconnected", () -> app.onFx(app.connection::activeUps) != null);
        app.runFx(() -> runner.run("beeper.toggle"));
        await("asked again", () -> prompts.calls.stream().filter(c -> c.startsWith("credentials")).count() == 2);
    }

    @Test
    void cancellingTheLoginSendsNothing() {
        connect("OL");
        app.runFx(() -> runner.run("beeper.toggle")); // no credentials queued: the dialog is cancelled
        ticks(2);
        assertEquals(0, sent("INSTCMD cp beeper.toggle"));
        assertFalse(app.onFx(runner::hasCredentials));
    }

    @Test
    void aVariableIsWrittenToTheUpsTheEditorShowsAndLogged() {
        connect("OL");
        app.server.on("SET VAR cp ups.id \"rack 2\"", "OK");
        prompts.credentials.add(new CommandRunner.Credentials("admin", "pw"));
        List<String> results = new CopyOnWriteArrayList<>();

        app.runFx(() -> runner.setVariable("cp", "ups.id", "rack 2", (ok, msg) -> results.add(ok + ":" + msg)));
        await("the result", () -> results.contains("true:OK"));
        await("the event", () -> eventTitles().contains(tr("alert.variableChanged")));
    }

    // --- Scheduled self-test --------------------------------------------------

    /** Enables a daily 03:00 test, armed at 2026-08-02 10:00 UTC (so first due 08-03 03:00). */
    private void scheduleDailyTest(long armed) {
        app.config.update(c -> c.withAdvanced(new Advanced(
                30, 0, 0, "$", true, 1, "03:00", 0, armed, Advanced.DEFAULT_TEST_COMMAND, true, "", true, 0, "", Advanced.THEME_SYSTEM, java.util.Map.of(), false)));
    }

    /** Caches credentials the way a user would: by running a command. */
    private void logIn() {
        app.server.on("INSTCMD cp beeper.toggle", "OK");
        prompts.credentials.add(new CommandRunner.Credentials("admin", "pw"));
        app.runFx(() -> runner.run("beeper.toggle"));
        await("logged in", () -> sent("INSTCMD cp beeper.toggle") == 1);
    }

    @Test
    void aDueTestRunsOnceAndIsStampedBeforeItIsSent() {
        scheduleDailyTest(Instant.parse("2026-08-02T10:00:00Z").getEpochSecond());
        app.server.on("INSTCMD cp test.battery.start.quick", "OK");
        connect("OL");
        logIn();
        assertEquals(0, sent("INSTCMD cp test.battery.start.quick"), "not due yet");

        now.set(Instant.parse("2026-08-03T03:00:05Z"));
        await("the test ran", () -> sent("INSTCMD cp test.battery.start.quick") == 1);
        assertEquals(now.get().getEpochSecond(), app.onFx(() -> app.config.get().advanced().selfTestLastRun()));
        await("logged", () -> eventTitles().contains(tr("alert.selfTestStarted")));
        ticks(4);
        assertEquals(1, sent("INSTCMD cp test.battery.start.quick"), "the stamp stops it repeating every poll");
    }

    @Test
    void aDueTestWaitsWhileTheUpsIsOnBattery() {
        scheduleDailyTest(Instant.parse("2026-08-02T10:00:00Z").getEpochSecond());
        app.server.on("INSTCMD cp test.battery.start.quick", "OK");
        connect("OL");
        logIn();
        app.status("cp", "OB DISCHRG");
        await("on battery", () -> app.view.saw("snapshot:cp:OB DISCHRG"));

        now.set(Instant.parse("2026-08-03T03:00:05Z"));
        ticks(4);
        assertEquals(0, sent("INSTCMD cp test.battery.start.quick"), "never drain a battery the load may need");

        app.status("cp", "OL");
        await("runs once back on mains", () -> sent("INSTCMD cp test.battery.start.quick") == 1);
    }

    @Test
    void aTestNeedsTheSessionLogin() {
        scheduleDailyTest(Instant.parse("2026-08-02T10:00:00Z").getEpochSecond());
        now.set(Instant.parse("2026-08-03T03:00:05Z"));
        connect("OL");
        ticks(4);
        assertEquals(0, sent("INSTCMD cp test.battery.start.quick"), "credentials are never stored");
    }

    @Test
    void anEnabledButUnarmedScheduleIsArmedNotRun() {
        scheduleDailyTest(0); // written before arming existed
        connect("OL");
        await("armed", () -> app.onFx(() -> app.config.get().advanced().selfTestArmed()) == now.get().getEpochSecond());
        assertEquals(0, sent("INSTCMD cp test.battery.start.quick"));
    }

    /** Scripted answers to the dialogs, and a record of which ones were shown. */
    private static final class FakePrompts implements CommandRunner.Prompts {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Deque<CommandRunner.Credentials> credentials = new ArrayDeque<>();
        volatile boolean confirmAnswer = true;

        @Override
        public boolean confirm(String command, String ups) {
            calls.add("confirm:" + command + "@" + ups);
            return confirmAnswer;
        }

        @Override
        public Optional<CommandRunner.Credentials> credentials(String header, String initialUser) {
            calls.add("credentials:" + initialUser);
            return Optional.ofNullable(credentials.poll());
        }

        @Override
        public void info(String title, String body) {
            calls.add("info:" + title);
        }

        @Override
        public void commandFailed(String command, String reason) {
            calls.add("failed:" + command + ":" + reason);
        }
    }
}
