package com.nux.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nux.protocol.FakeNutServer;
import com.nux.protocol.TlsMode;
import com.nux.protocol.UpsDevice;
import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;

/**
 * Drives the real polling loop against a scripted upsd. The UI marshaller is injected
 * as {@code Runnable::run} so the concurrency, reconnect, auth and tracking logic runs
 * without a JavaFX toolkit.
 */
class PollServiceTest {

    private static final long WAIT_SECONDS = 10;

    private FakeNutServer server;
    private PollService poll;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeNutServer();
        poll = new PollService(Runnable::run);
    }

    @AfterEach
    void tearDown() {
        poll.shutdown();
        server.close();
    }

    private void scriptOneUps() {
        server.on("LIST UPS", "BEGIN LIST UPS", "UPS cp \"Desk\"", "END LIST UPS")
                .on(
                        "LIST VAR cp",
                        "BEGIN LIST VAR cp",
                        "VAR cp battery.charge \"87\"",
                        "VAR cp ups.status \"OL\"",
                        "END LIST VAR cp")
                .on("LIST CMD cp", "BEGIN LIST CMD cp", "CMD cp beeper.toggle", "END LIST CMD cp");
    }

    private void start() {
        poll.start(server.host(), server.port(), null, TlsMode.DISABLED);
    }

    private static <T> T take(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue(value != null, "timed out waiting for a callback");
        return value;
    }

    @Test
    void announcesDevicesThenPollsTheFirstUps() throws Exception {
        scriptOneUps();
        BlockingQueue<List<String>> connected = new ArrayBlockingQueue<>(4);
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(4);
        poll.setOnConnected((devices, target) -> connected.offer(devices.stream().map(d -> d.name()).toList()));
        poll.setOnSnapshot(snapshots::offer);

        start();

        assertEquals(List.of("cp"), take(connected));
        UpsSnapshot snapshot = take(snapshots);
        assertEquals("cp", snapshot.ups());
        assertEquals(87.0, snapshot.chargePercent().orElseThrow());
        assertTrue(snapshot.status().online());
    }

    @Test
    void announcesTheCommandListOnceForATarget() throws Exception {
        scriptOneUps();
        BlockingQueue<List<String>> commands = new ArrayBlockingQueue<>(4);
        poll.setOnCommands(commands::offer);
        start();
        assertEquals(List.of("beeper.toggle"), take(commands));
    }

    @Test
    void reportsDisconnectionWhenTheServerRefusesTheUps() throws Exception {
        server.on("LIST UPS", "BEGIN LIST UPS", "END LIST UPS");
        BlockingQueue<String> failures = new ArrayBlockingQueue<>(4);
        poll.setOnDisconnected(failures::offer);
        start();
        assertTrue(take(failures).contains("no UPS"));
    }

    @Test
    void instantCommandsRefuseWhenNotConnected() throws Exception {
        BlockingQueue<String> results = new ArrayBlockingQueue<>(4);
        poll.runInstCmd("cp", "u", "p", "beeper.toggle", (ok, message) -> results.offer(ok + ":" + message));
        assertEquals("false:Not connected", take(results));
    }

    @Test
    void authenticatesOnceThenRunsTheCommand() throws Exception {
        scriptOneUps();
        server.on("USERNAME nuxuser", "OK")
                .on("PASSWORD s3cret", "OK")
                .on("SET TRACKING ON", "ERR FEATURE-NOT-CONFIGURED")
                .on("INSTCMD cp beeper.toggle", "OK");
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(4);
        poll.setOnSnapshot(snapshots::offer);
        start();
        take(snapshots); // ensure the connection is up before commanding

        BlockingQueue<String> first = new ArrayBlockingQueue<>(4);
        poll.runInstCmd("cp", "nuxuser", "s3cret", "beeper.toggle", (ok, m) -> first.offer(ok + ":" + m));
        assertEquals("true:OK", take(first));

        BlockingQueue<String> second = new ArrayBlockingQueue<>(4);
        poll.runInstCmd("cp", "nuxuser", "s3cret", "beeper.toggle", (ok, m) -> second.offer(ok + ":" + m));
        assertEquals("true:OK", take(second));

        // Credentials and the tracking probe go out once per connection, not per command.
        assertEquals(1, count(server.received(), "USERNAME nuxuser"));
        assertEquals(1, count(server.received(), "SET TRACKING ON"));
        assertEquals(2, count(server.received(), "INSTCMD cp beeper.toggle"));
    }

    @Test
    void refusesToSwitchUserOnAConnectionThatIsAlreadyAuthenticated() throws Exception {
        scriptOneUps();
        server.on("USERNAME first", "OK")
                .on("PASSWORD p1", "OK")
                .on("SET TRACKING ON", "ERR FEATURE-NOT-CONFIGURED")
                .on("INSTCMD cp beeper.toggle", "OK");
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(4);
        poll.setOnSnapshot(snapshots::offer);
        start();
        take(snapshots);

        BlockingQueue<String> ok = new ArrayBlockingQueue<>(4);
        poll.runInstCmd("cp", "first", "p1", "beeper.toggle", (o, m) -> ok.offer(o + ":" + m));
        take(ok);

        BlockingQueue<String> denied = new ArrayBlockingQueue<>(4);
        poll.runInstCmd("cp", "second", "p2", "beeper.toggle", (o, m) -> denied.offer(o + ":" + m));
        String result = take(denied);
        assertTrue(result.startsWith("false:"), result);
        assertTrue(result.contains("first"), "the message should name who is authenticated: " + result);
        assertFalse(server.received().contains("USERNAME second"), "must not attempt a second USERNAME");
    }

    @Test
    void trackedCommandsReportTheDriverOutcomeNotJustAcceptance() throws Exception {
        scriptOneUps();
        server.on("USERNAME u", "OK")
                .on("PASSWORD p", "OK")
                .on("SET TRACKING ON", "OK")
                .on("INSTCMD cp beeper.toggle", "OK TRACKING t-1")
                // First poll is still pending; the second resolves.
                .on("GET TRACKING t-1", "PENDING")
                .on("GET TRACKING t-1", "SUCCESS");
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(4);
        poll.setOnSnapshot(snapshots::offer);
        start();
        take(snapshots);

        BlockingQueue<String> results = new ArrayBlockingQueue<>(4);
        poll.runInstCmd("cp", "u", "p", "beeper.toggle", (ok, m) -> results.offer(ok + ":" + m));
        assertEquals("true:OK", take(results));
        assertTrue(count(server.received(), "GET TRACKING t-1") >= 2, "should have polled until it resolved");
    }

    @Test
    void trackedFailuresSurfaceTheDriverErrorCode() throws Exception {
        scriptOneUps();
        server.on("USERNAME u", "OK")
                .on("PASSWORD p", "OK")
                .on("SET TRACKING ON", "OK")
                .on("INSTCMD cp beeper.toggle", "OK TRACKING t-2")
                .on("GET TRACKING t-2", "ERR CMD-NOT-SUPPORTED");
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(4);
        poll.setOnSnapshot(snapshots::offer);
        start();
        take(snapshots);

        BlockingQueue<String> results = new ArrayBlockingQueue<>(4);
        poll.runInstCmd("cp", "u", "p", "beeper.toggle", (ok, m) -> results.offer(ok + ":" + m));
        assertEquals("false:CMD-NOT-SUPPORTED", take(results));
    }

    @Test
    void anUnknownTrackingIdIsReportedAsAcceptedRatherThanFailed() throws Exception {
        scriptOneUps();
        server.on("USERNAME u", "OK")
                .on("PASSWORD p", "OK")
                .on("SET TRACKING ON", "OK")
                .on("INSTCMD cp beeper.toggle", "OK TRACKING t-3")
                // The server forgot the record; that is not the command failing.
                .on("GET TRACKING t-3", "ERR UNKNOWN");
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(4);
        poll.setOnSnapshot(snapshots::offer);
        start();
        take(snapshots);

        BlockingQueue<String> results = new ArrayBlockingQueue<>(4);
        poll.runInstCmd("cp", "u", "p", "beeper.toggle", (ok, m) -> results.offer(ok + ":" + m));
        assertTrue(take(results).startsWith("true:"));
    }

    @Test
    void loadsWritableVariablesForTheEditor() throws Exception {
        scriptOneUps();
        server.on("LIST RW cp", "BEGIN LIST RW cp", "RW cp ups.id \"desk\"", "END LIST RW cp")
                .on("GET TYPE cp ups.id", "TYPE cp ups.id STRING:8");
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(4);
        poll.setOnSnapshot(snapshots::offer);
        start();
        take(snapshots);

        BlockingQueue<List<String>> loaded = new ArrayBlockingQueue<>(4);
        poll.loadRwVariables("cp", vars -> loaded.offer(vars.stream().map(v -> v.name()).toList()));
        assertEquals(List.of("ups.id"), take(loaded));
    }

    @Test
    void multiUpsServersProduceAnOverviewOfEveryUps() throws Exception {
        server.on("LIST UPS", "BEGIN LIST UPS", "UPS a \"A\"", "UPS b \"B\"", "END LIST UPS")
                .on("LIST VAR a", "BEGIN LIST VAR a", "VAR a ups.status \"OL\"", "END LIST VAR a")
                .on("LIST VAR b", "BEGIN LIST VAR b", "VAR b ups.status \"OB\"", "END LIST VAR b")
                .on("LIST CMD a", "BEGIN LIST CMD a", "END LIST CMD a");
        BlockingQueue<Map<String, UpsSnapshot>> overviews = new ArrayBlockingQueue<>(4);
        poll.setOnOverview(overviews::offer);
        start();

        Map<String, UpsSnapshot> all = take(overviews);
        assertEquals(2, all.size());
        assertTrue(all.get("a").status().online());
        assertTrue(all.get("b").status().onBattery());
    }

    @Test
    void aStaleUpsKeepsItsOverviewCardInsteadOfSinkingTheOverview() throws Exception {
        server.on("LIST UPS", "BEGIN LIST UPS", "UPS a \"A\"", "UPS b \"B\"", "END LIST UPS")
                .on("LIST VAR a", "BEGIN LIST VAR a", "VAR a ups.status \"OL\"", "END LIST VAR a")
                .on("LIST VAR b", "ERR DATA-STALE")
                .on("LIST CMD a", "BEGIN LIST CMD a", "END LIST CMD a");
        BlockingQueue<Map<String, UpsSnapshot>> overviews = new ArrayBlockingQueue<>(4);
        BlockingQueue<String> failures = new ArrayBlockingQueue<>(4);
        poll.setOnOverview(overviews::offer);
        poll.setOnDisconnected(failures::offer);
        start();

        Map<String, UpsSnapshot> all = take(overviews);
        assertEquals(List.of("a", "b"), List.copyOf(all.keySet()));
        assertTrue(all.get("a").status().online());
        assertTrue(all.get("b").vars().isEmpty(), "the stale UPS is shown without data");
        assertEquals(UpsStatus.Severity.WARNING, all.get("b").status().severity(), "unknown is never OK");
        assertTrue(failures.isEmpty(), "a refusal for one UPS is not a lost connection");
    }

    @Test
    void anUnknownUpsNameFallsBackToTheFirstListedAndSaysSo() throws Exception {
        // e.g. the user pointed Settings at another server, or renamed the UPS in ups.conf:
        // polling the missing name would fail and reconnect every tick, forever.
        scriptOneUps();
        BlockingQueue<String> targets = new ArrayBlockingQueue<>(4);
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(4);
        BlockingQueue<String> failures = new ArrayBlockingQueue<>(4);
        poll.setOnConnected((devices, target) -> targets.offer(target));
        poll.setOnSnapshot(snapshots::offer);
        poll.setOnDisconnected(failures::offer);

        poll.start(server.host(), server.port(), "gone", TlsMode.DISABLED);

        assertEquals("cp", take(targets));
        assertEquals("cp", take(snapshots).ups());
        assertFalse(server.received().contains("LIST VAR gone"));
        assertTrue(failures.isEmpty());
    }

    @Test
    void aRequestedUpsTheServerHasIsKept() {
        List<UpsDevice> devices = List.of(new UpsDevice("a", "A"), new UpsDevice("b", "B"));
        assertEquals("b", PollService.resolveTarget("b", devices));
        assertEquals("a", PollService.resolveTarget(null, devices));
        assertEquals("a", PollService.resolveTarget("gone", devices));
        assertEquals(null, PollService.resolveTarget("b", List.of()));
    }

    @Test
    void aRuntimeFailureIsReportedAndPollingCarriesOn() throws Exception {
        // A port out of range throws IllegalArgumentException, not IOException. Escaping a
        // fixed-delay task would cancel it silently and leave the UI "connecting" forever.
        BlockingQueue<String> failures = new ArrayBlockingQueue<>(4);
        poll.setOnDisconnected(failures::offer);
        poll.start(server.host(), 99999, null, TlsMode.DISABLED);
        assertTrue(take(failures).contains("99999"));
        // Still scheduled: the next tick reports again instead of the task having died.
        assertTrue(take(failures).contains("99999"));
    }

    @Test
    void aCommandTheUpsRefusesKeepsTheConnectionAndItsLogin() throws Exception {
        // CMD-NOT-SUPPORTED says nothing about the connection or the password: dropping
        // either forced a reconnect and a needless credentials prompt.
        scriptOneUps();
        server.on("USERNAME nuxuser", "OK")
                .on("PASSWORD s3cret", "OK")
                .on("SET TRACKING ON", "ERR FEATURE-NOT-CONFIGURED")
                .on("INSTCMD cp load.on", "ERR CMD-NOT-SUPPORTED")
                .on("INSTCMD cp beeper.toggle", "OK");
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(16);
        poll.setOnSnapshot(snapshots::offer);
        start();
        take(snapshots);

        BlockingQueue<String> results = new ArrayBlockingQueue<>(4);
        poll.runInstCmd("cp", "nuxuser", "s3cret", "load.on", (ok, message) -> results.offer(ok + ":" + message));
        assertEquals("false:CMD-NOT-SUPPORTED", take(results));
        poll.runInstCmd("cp", "nuxuser", "s3cret", "beeper.toggle", (ok, message) -> results.offer(ok + ":" + message));
        assertEquals("true:OK", take(results));
        assertEquals(1, count(server.received(), "USERNAME nuxuser"), "logged in once, on one connection");
    }

    @Test
    void anAccessDeniedCommandDropsTheLoginSoAnotherUserCanTry() throws Exception {
        scriptOneUps();
        server.on("USERNAME viewer", "OK")
                .on("PASSWORD pw", "OK")
                .on("SET TRACKING ON", "ERR FEATURE-NOT-CONFIGURED")
                .on("INSTCMD cp beeper.toggle", "ERR ACCESS-DENIED")
                .on("USERNAME admin", "OK")
                .on("PASSWORD pw2", "OK");
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(16);
        poll.setOnSnapshot(snapshots::offer);
        start();
        take(snapshots);

        BlockingQueue<String> results = new ArrayBlockingQueue<>(4);
        poll.runInstCmd("cp", "viewer", "pw", "beeper.toggle", (ok, message) -> results.offer(ok + ":" + message));
        assertEquals("false:ACCESS-DENIED", take(results));
        take(snapshots); // the next tick reconnects
        poll.runInstCmd("cp", "admin", "pw2", "beeper.toggle", (ok, message) -> results.offer(ok + ":" + message));
        // Not "Already authenticated as viewer": the second user gets a clean connection.
        assertFalse(take(results).contains("Already authenticated"));
    }

    @Test
    void restartingReplacesThePollTaskInsteadOfStackingAnother() throws Exception {
        // Each UPS switch calls start(); a superseded task used to stay scheduled forever,
        // waking every 5 s as a no-op — one more per switch for the life of the app.
        scriptOneUps();
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(16);
        poll.setOnSnapshot(snapshots::offer);
        for (int i = 0; i < 5; i++) {
            start();
        }
        take(snapshots);
        // Between ticks the one live poll waits in the queue; it may be mid-run (0) but never more.
        assertTrue(awaitQueued(n -> n <= 1), "superseded poll tasks are still queued: " + poll.queuedTasks());

        poll.stop();
        assertTrue(awaitQueued(n -> n == 0), "stop() left a poll task queued: " + poll.queuedTasks());
    }

    /** The queue drains asynchronously (dropConnection runs on the poll thread), so poll briefly. */
    private boolean awaitQueued(java.util.function.IntPredicate condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.test(poll.queuedTasks())) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void aSingleUpsServerSkipsTheOverviewPoll() throws Exception {
        scriptOneUps();
        BlockingQueue<UpsSnapshot> snapshots = new ArrayBlockingQueue<>(4);
        poll.setOnSnapshot(snapshots::offer);
        poll.setOnOverview(all -> {
            throw new AssertionError("no overview should be produced for one UPS");
        });
        start();
        take(snapshots);
    }

    private static long count(List<String> lines, String value) {
        return lines.stream().filter(value::equals).count();
    }
}
