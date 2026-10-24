package com.nux.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.nux.config.Config;
import com.nux.config.ConfigHolder;
import com.nux.protocol.FakeNutServer;
import com.nux.protocol.UpsSnapshot;
import com.nux.service.PollService;
import com.nux.store.EventLog;
import com.nux.store.HistoryStore;

/**
 * The app's controllers wired as in production, minus JavaFX: a single "fx" executor
 * stands in for the FX thread (PollService marshals onto it, and tests call the
 * controllers through {@link #onFx}), a scripted upsd stands in for the server, and the
 * history and event stores write to a temp directory.
 */
final class AppHarness implements AutoCloseable {

    /** Fast polls, so reconnects and transitions take milliseconds, not 5-second ticks. */
    static final Duration TICK = Duration.ofMillis(120);
    private static final long WAIT_SECONDS = 10;

    final FakeNutServer server;
    final ExecutorService fx = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fake-fx");
        t.setDaemon(true);
        return t;
    });
    final PollService poll = new PollService(fx::execute, TICK);
    final ConfigHolder config;
    final HistoryStore history;
    final EventLog events;
    final RecordingView view = new RecordingView();
    final List<String> notices = new CopyOnWriteArrayList<>();
    final ConnectionController connection;

    AppHarness(Path dir) throws IOException {
        server = new FakeNutServer();
        config = new ConfigHolder(
                dir.resolve("config.properties"),
                Config.defaults().withLast(server.host(), server.port(), "", false));
        history = new HistoryStore(dir, 30);
        events = new EventLog(dir);
        connection = onFx(() -> new ConnectionController(
                config, poll, history, events, view, (title, body, critical) -> notices.add(title + "|" + body),
                Instant::now));
    }

    /** Scripts a server with one UPS, {@code cp}, reporting {@code status}. */
    AppHarness oneUps(String status) {
        server.on("LIST UPS", "BEGIN LIST UPS", "UPS cp \"Desk\"", "END LIST UPS");
        status("cp", status);
        server.on("LIST CMD cp", "BEGIN LIST CMD cp", "CMD cp beeper.toggle", "END LIST CMD cp");
        return this;
    }

    /** (Re)scripts {@code ups}'s variables with a new status. */
    AppHarness status(String ups, String status) {
        server.replace(
                "LIST VAR " + ups,
                "BEGIN LIST VAR " + ups,
                "VAR " + ups + " battery.charge \"87\"",
                "VAR " + ups + " ups.status \"" + status + "\"",
                "END LIST VAR " + ups);
        return this;
    }

    <T> T onFx(Callable<T> action) {
        try {
            return fx.submit(action).get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("fx action failed", e);
        }
    }

    void runFx(Runnable action) {
        onFx(() -> {
            action.run();
            return null;
        });
    }

    /** Polls {@code condition} until it holds; fails the test after a generous timeout. */
    static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    /** Lets {@code ticks} poll intervals pass — for asserting that something did NOT happen. */
    static void ticks(int ticks) {
        sleep(TICK.toMillis() * ticks + 50);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        poll.shutdown();
        fx.shutdownNow();
        server.close();
        history.shutdown();
        events.shutdown();
    }

    /** Records what the controller asked the screen to do, as short readable strings. */
    static final class RecordingView implements ConnectionController.View {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile UpsSnapshot snapshot;

        boolean saw(String call) {
            return calls.contains(call);
        }

        boolean sawPrefix(String prefix) {
            return calls.stream().anyMatch(c -> c.startsWith(prefix));
        }

        @Override
        public void showRunning(boolean running) {
            calls.add("running:" + running);
        }

        @Override
        public void showUpsChoices(List<String> names, String selected) {
            calls.add("choices:" + names + "@" + selected);
        }

        @Override
        public void clearUpsChoices() {
            calls.add("clearChoices");
        }

        @Override
        public void showServer(String host, int port, boolean tls) {
            calls.add("server:" + port);
        }

        @Override
        public void showSnapshot(UpsSnapshot s) {
            snapshot = s;
            calls.add("snapshot:" + s.ups() + ":" + s.vars().get("ups.status"));
        }

        @Override
        public void showCommands(List<String> commands) {
            calls.add("commands:" + commands);
        }

        @Override
        public void showOverview(Map<String, UpsSnapshot> all, String selected) {
            calls.add("overview:" + all.keySet() + "@" + selected);
        }

        @Override
        public void clearLiveData() {
            calls.add("clearLive");
        }

        @Override
        public void resetTrends() {
            calls.add("resetTrends");
        }

        @Override
        public void showDisconnected(String reason) {
            calls.add("disconnected");
        }

        @Override
        public void showStopped() {
            calls.add("stopped");
        }
    }
}
