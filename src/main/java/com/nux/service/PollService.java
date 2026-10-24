package com.nux.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.nux.protocol.NutClient;
import com.nux.protocol.NutException;
import com.nux.protocol.RwVariable;
import com.nux.protocol.TlsMode;
import com.nux.protocol.Tracking;
import com.nux.protocol.UpsDevice;
import com.nux.protocol.UpsSnapshot;

/**
 * Polls one upsd on a single daemon thread and posts results to the FX thread.
 *
 * <p>A monotonically increasing generation guards stale callbacks: {@link #start} bumps it,
 * so results from a superseded configuration are dropped on arrival. While started, a lost
 * connection is retried on every tick (the poll interval doubles as the reconnect backoff).
 *
 * <p>Authenticated actions ({@link #runInstCmd}, {@link #setVariable}) and RW-variable
 * discovery run on the same thread and connection, so they serialize naturally with
 * polling and authentication state stays connection-accurate.
 */
public final class PollService {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long POLL_SECONDS = 5;
    /** Tracking polls: 10 × 700 ms ≈ 7 s, comfortably longer than a driver takes to answer. */
    private static final int TRACKING_ATTEMPTS = 10;
    private static final long TRACKING_INTERVAL_MS = 700;

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "nux-poll");
        t.setDaemon(true);
        return t;
    });

    /**
     * How results reach the UI. Injected so the polling, reconnect, auth and tracking
     * logic can be tested without a JavaFX toolkit; production always marshals to the
     * FX thread.
     */
    private final Consumer<Runnable> ui;

    public PollService() {
        this(Platform::runLater);
    }

    PollService(Consumer<Runnable> ui) {
        this.ui = ui;
        // A cancelled poll task should leave the queue now, not linger until its next due time.
        executor.setRemoveOnCancelPolicy(true);
    }

    private volatile long generation = 0;
    private ScheduledFuture<?> pollTask; // the current fixed-delay poll; guarded by "this"

    // Poll-thread confined:
    private NutClient client;
    private boolean wasConnected;
    private String authedUser; // non-null once USERNAME/PASSWORD succeeded on this connection
    private String cmdTarget; // the UPS whose command list was last fetched and announced
    private Boolean trackingEnabled; // null = not yet asked on this connection
    private List<UpsDevice> devices = List.of();
    private String target; // the UPS actually being polled, resolved once per connection

    // FX-thread confined:
    private BiConsumer<List<UpsDevice>, String> onConnected = (devices, target) -> {};
    private Consumer<UpsSnapshot> onSnapshot = snapshot -> {};
    private Consumer<Map<String, UpsSnapshot>> onOverview = all -> {};
    private Consumer<List<String>> onCommands = commands -> {};
    private Consumer<String> onDisconnected = reason -> {};

    /**
     * Fires on each (re)connection with the server's UPS list and the one being polled:
     * the requested UPS when the server has it, otherwise the first listed.
     */
    public void setOnConnected(BiConsumer<List<UpsDevice>, String> handler) {
        this.onConnected = handler;
    }

    public void setOnSnapshot(Consumer<UpsSnapshot> handler) {
        this.onSnapshot = handler;
    }

    /** Every UPS on the server, for the multi-UPS overview (only fires when there's more than one). */
    public void setOnOverview(Consumer<Map<String, UpsSnapshot>> handler) {
        this.onOverview = handler;
    }

    public void setOnCommands(Consumer<List<String>> handler) {
        this.onCommands = handler;
    }

    public void setOnDisconnected(Consumer<String> handler) {
        this.onDisconnected = handler;
    }

    /** (Re)starts polling {@code host:port}; {@code ups} null means "the first UPS listed". */
    public synchronized void start(String host, int port, String ups, TlsMode tls) {
        long gen = ++generation;
        cancelPolling();
        executor.execute(this::dropConnection);
        pollTask = executor.scheduleWithFixedDelay(
                () -> tick(gen, host, port, ups, tls), 0, POLL_SECONDS, TimeUnit.SECONDS);
    }

    /** Stops polling; safe to {@link #start} again afterwards. */
    public synchronized void stop() {
        generation++;
        cancelPolling();
        executor.execute(this::dropConnection);
    }

    public synchronized void shutdown() {
        generation++;
        cancelPolling();
        executor.execute(this::dropConnection);
        executor.shutdown();
    }

    /**
     * Cancels the superseded poll task. The generation guard already makes it a no-op, but
     * left scheduled it would wake every 5 seconds forever — one more per UPS switch.
     * Not interrupted: a tick mid-request finishes and its results are dropped by the guard.
     */
    private void cancelPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    /** Tasks waiting on the poll thread; for tests to prove superseded polls don't pile up. */
    int queuedTasks() {
        return executor.getQueue().size();
    }

    /**
     * Runs an instant command on the current connection, authenticating first if needed.
     * The result callback always fires on the FX thread — success or failure — because a
     * user action deserves an answer even if polling has since been reconfigured.
     */
    public void runInstCmd(
            String ups, String user, String password, String command, BiConsumer<Boolean, String> onResult) {
        authenticated(user, password, onResult, c -> c.instCmd(ups, command));
    }

    /** Writes a settable variable (needs the same instcmds-style upsd permissions). */
    public void setVariable(
            String ups, String user, String password, String name, String value, BiConsumer<Boolean, String> onResult) {
        authenticated(user, password, onResult, c -> c.setVar(ups, name, value));
    }

    /** Fetches the writable variables with their types/choices; empty list on failure. */
    public void loadRwVariables(String ups, Consumer<List<RwVariable>> onResult) {
        long gen = generation;
        executor.execute(() -> {
            List<RwVariable> vars = List.of();
            if (gen == generation && client != null) {
                try {
                    vars = client.listRw(ups);
                } catch (IOException | RuntimeException e) {
                    dropConnection();
                }
            }
            List<RwVariable> result = vars;
            ui.accept(() -> onResult.accept(result));
        });
    }

    /** Shared auth-then-act path for the two mutating operations. */
    private void authenticated(
            String user, String password, BiConsumer<Boolean, String> onResult, ClientAction action) {
        long gen = generation;
        executor.execute(() -> {
            if (gen != generation || client == null) {
                fxResult(onResult, false, "Not connected");
                return;
            }
            try {
                if (authedUser == null) {
                    client.username(user);
                    client.password(password);
                    authedUser = user;
                } else if (!authedUser.equals(user)) {
                    // upsd allows one USERNAME per connection; a clean reconnect is the only switch
                    fxResult(onResult, false, "Already authenticated as " + authedUser + " — reconnect to switch user");
                    return;
                }
            } catch (NutException e) {
                // A half-done auth (USERNAME ok, PASSWORD refused) poisons the connection;
                // drop it so the next tick reconnects clean.
                dropConnection();
                fxResult(onResult, false, e.code());
                return;
            } catch (IOException | RuntimeException e) {
                dropConnection();
                fxResult(onResult, false, reason(e));
                return;
            }
            try {
                enableTrackingOnce();
                String trackingId = action.run(client);
                if (trackingId.isEmpty()) {
                    // Pre-2.8 server (or tracking refused): OK is the whole answer we get.
                    fxResult(onResult, true, "OK");
                } else {
                    scheduleTrackingCheck(gen, trackingId, TRACKING_ATTEMPTS, onResult);
                }
            } catch (NutException e) {
                // upsd answered, so the stream is still in step: a refusal such as
                // CMD-NOT-SUPPORTED leaves a healthy connection alone. Only a permissions
                // refusal drops it, so the user can retry as someone else (one USERNAME
                // per connection).
                if (NutException.isAuthFailure(e.code())) {
                    dropConnection();
                }
                fxResult(onResult, false, e.code());
            } catch (IOException | RuntimeException e) {
                dropConnection();
                fxResult(onResult, false, reason(e));
            }
        });
    }

    private static String reason(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Asks for tracking once per connection; a refusal is remembered, not retried. */
    private void enableTrackingOnce() throws IOException {
        if (trackingEnabled == null) {
            trackingEnabled = client.enableTracking();
        }
    }

    /**
     * Polls a tracking id on the shared scheduler rather than sleeping, so waiting for a
     * command's real outcome never stalls the 5-second measurement poll.
     */
    private void scheduleTrackingCheck(long gen, String id, int attemptsLeft, BiConsumer<Boolean, String> onResult) {
        executor.schedule(
                () -> {
                    if (gen != generation || client == null) {
                        // upsd already accepted the command; only its outcome is lost with
                        // the connection, so don't report a command that ran as a failure.
                        fxResult(onResult, true, "OK (outcome unknown — the connection changed)");
                        return;
                    }
                    Tracking.Result result;
                    try {
                        result = client.trackingResult(id);
                    } catch (IOException | RuntimeException e) {
                        dropConnection();
                        fxResult(onResult, false, e.getMessage() != null ? e.getMessage() : "tracking failed");
                        return;
                    }
                    switch (result.status()) {
                        case SUCCESS -> fxResult(onResult, true, "OK");
                        case FAILED -> fxResult(onResult, false, result.detail());
                        // An unknown id means the server dropped the record; the command was
                        // still accepted, so report the accept rather than inventing a failure.
                        case UNKNOWN -> fxResult(onResult, true, "OK (not tracked)");
                        case PENDING -> {
                            if (attemptsLeft > 1) {
                                scheduleTrackingCheck(gen, id, attemptsLeft - 1, onResult);
                            } else {
                                fxResult(onResult, false, "still pending after "
                                        + (TRACKING_ATTEMPTS * TRACKING_INTERVAL_MS / 1000) + "s");
                            }
                        }
                    }
                },
                TRACKING_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private interface ClientAction {
        /** Returns the tracking id, or "" when the connection isn't tracking. */
        String run(NutClient client) throws IOException;
    }

    private void tick(long gen, String host, int port, String ups, TlsMode tls) {
        if (gen != generation) {
            return; // superseded configuration; its fixed-delay task keeps firing but does nothing
        }
        try {
            if (client == null) {
                client = NutClient.connect(host, port, TIMEOUT, tls);
                devices = client.listUps();
                target = resolveTarget(ups, devices);
                wasConnected = true;
                List<UpsDevice> found = devices;
                String resolved = target;
                fx(gen, () -> onConnected.accept(found, resolved));
            }
            if (target == null) {
                throw new IOException("Server reports no UPS devices");
            }
            String polled = target;
            UpsSnapshot snapshot = new UpsSnapshot(polled, client.listVars(polled), Instant.now());
            fx(gen, () -> onSnapshot.accept(snapshot));
            if (!polled.equals(cmdTarget)) {
                List<String> commands = client.listCmds(polled);
                cmdTarget = polled;
                fx(gen, () -> onCommands.accept(commands));
            }
            if (devices.size() > 1) {
                pollOverview(gen, polled, snapshot);
            }
        } catch (IOException | RuntimeException e) {
            // RuntimeException too: one escaping a fixed-delay task cancels it silently, which
            // would stop polling for good with the UI stuck on "connecting" (a port out of
            // range is enough — InetSocketAddress throws IllegalArgumentException).
            dropConnection();
            String reason = reason(e);
            boolean announce = wasConnected || gen == generation;
            wasConnected = false;
            if (announce) {
                fx(gen, () -> onDisconnected.accept(reason));
            }
        }
    }

    /**
     * The requested UPS when the server lists it, else the first one. Polling a name the
     * server doesn't have fails every tick, so falling back here (and announcing the
     * choice through {@code onConnected}) is what keeps the UI and the poller agreeing.
     */
    static String resolveTarget(String requested, List<UpsDevice> devices) {
        if (requested != null && devices.stream().anyMatch(d -> d.name().equals(requested))) {
            return requested;
        }
        return devices.isEmpty() ? null : devices.getFirst().name();
    }

    /** Adds the other UPSes to the just-polled one; a single failure doesn't sink the tick. */
    private void pollOverview(long gen, String target, UpsSnapshot targetSnapshot) {
        Map<String, UpsSnapshot> all = new LinkedHashMap<>();
        for (UpsDevice device : devices) {
            if (device.name().equals(target)) {
                all.put(target, targetSnapshot);
                continue;
            }
            try {
                all.put(device.name(), new UpsSnapshot(device.name(), client.listVars(device.name()), Instant.now()));
            } catch (NutException e) {
                // upsd refused this one UPS (DATA-STALE, DRIVER-NOT-CONNECTED…) but the
                // connection is fine: keep its card, with no data, so it reads as unknown.
                all.put(device.name(), new UpsSnapshot(device.name(), Map.of(), Instant.now()));
            } catch (IOException e) {
                // The stream may be mid-reply, so it can't be trusted for the next request.
                dropConnection();
                return;
            }
        }
        fx(gen, () -> onOverview.accept(all));
    }

    private void dropConnection() {
        if (client != null) {
            client.close();
            client = null;
        }
        authedUser = null;
        target = null;
        cmdTarget = null;
        trackingEnabled = null;
    }

    private void fx(long gen, Runnable action) {
        ui.accept(() -> {
            if (gen == generation) {
                action.run();
            }
        });
    }

    private void fxResult(BiConsumer<Boolean, String> onResult, boolean ok, String message) {
        ui.accept(() -> onResult.accept(ok, message));
    }
}
