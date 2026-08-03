package com.nux.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
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
    }

    private volatile long generation = 0;

    // Poll-thread confined:
    private NutClient client;
    private boolean wasConnected;
    private String authedUser; // non-null once USERNAME/PASSWORD succeeded on this connection
    private String cmdTarget; // the UPS whose command list was last fetched and announced
    private Boolean trackingEnabled; // null = not yet asked on this connection
    private List<UpsDevice> devices = List.of();

    // FX-thread confined:
    private Consumer<List<UpsDevice>> onConnected = devices -> {};
    private Consumer<UpsSnapshot> onSnapshot = snapshot -> {};
    private Consumer<Map<String, UpsSnapshot>> onOverview = all -> {};
    private Consumer<List<String>> onCommands = commands -> {};
    private Consumer<String> onDisconnected = reason -> {};

    public void setOnConnected(Consumer<List<UpsDevice>> handler) {
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
    public void start(String host, int port, String ups, TlsMode tls) {
        long gen = ++generation;
        executor.execute(this::dropConnection);
        executor.scheduleWithFixedDelay(() -> tick(gen, host, port, ups, tls), 0, POLL_SECONDS, TimeUnit.SECONDS);
    }

    /** Stops polling; safe to {@link #start} again afterwards. */
    public void stop() {
        generation++;
        executor.execute(this::dropConnection);
    }

    public void shutdown() {
        generation++;
        executor.execute(this::dropConnection);
        executor.shutdown();
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
                } catch (IOException e) {
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
                enableTrackingOnce();
                String trackingId = action.run(client);
                if (trackingId.isEmpty()) {
                    // Pre-2.8 server (or tracking refused): OK is the whole answer we get.
                    fxResult(onResult, true, "OK");
                } else {
                    scheduleTrackingCheck(gen, trackingId, TRACKING_ATTEMPTS, onResult);
                }
            } catch (NutException e) {
                // A half-done auth (USERNAME ok, PASSWORD refused) poisons the connection;
                // drop it so the next tick reconnects clean.
                dropConnection();
                fxResult(onResult, false, e.code());
            } catch (IOException e) {
                dropConnection();
                fxResult(onResult, false, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        });
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
                        fxResult(onResult, false, "Not connected");
                        return;
                    }
                    Tracking.Result result;
                    try {
                        result = client.trackingResult(id);
                    } catch (IOException e) {
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
                wasConnected = true;
                List<UpsDevice> found = devices;
                fx(gen, () -> onConnected.accept(found));
                if (ups == null && !devices.isEmpty()) {
                    ups = devices.getFirst().name();
                }
            }
            String target = ups != null ? ups : firstUpsName();
            if (target == null) {
                throw new IOException("Server reports no UPS devices");
            }
            UpsSnapshot snapshot = new UpsSnapshot(target, client.listVars(target), Instant.now());
            fx(gen, () -> onSnapshot.accept(snapshot));
            if (!target.equals(cmdTarget)) {
                List<String> commands = client.listCmds(target);
                cmdTarget = target;
                fx(gen, () -> onCommands.accept(commands));
            }
            if (devices.size() > 1) {
                pollOverview(gen, target, snapshot);
            }
        } catch (IOException e) {
            dropConnection();
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            boolean announce = wasConnected || gen == generation;
            wasConnected = false;
            if (announce) {
                fx(gen, () -> onDisconnected.accept(reason));
            }
        }
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
            } catch (IOException e) {
                return; // connection trouble; the next tick's main poll will surface it
            }
        }
        fx(gen, () -> onOverview.accept(all));
    }

    private String firstUpsName() throws IOException {
        devices = client.listUps();
        return devices.isEmpty() ? null : devices.getFirst().name();
    }

    private void dropConnection() {
        if (client != null) {
            client.close();
            client = null;
        }
        authedUser = null;
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
