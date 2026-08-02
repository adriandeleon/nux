package com.nux.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.nux.protocol.NutClient;
import com.nux.protocol.NutException;
import com.nux.protocol.TlsMode;
import com.nux.protocol.UpsDevice;
import com.nux.protocol.UpsSnapshot;

/**
 * Polls one upsd on a single daemon thread and posts results to the FX thread.
 *
 * <p>A monotonically increasing generation guards stale callbacks: {@link #start} bumps it,
 * so results from a superseded configuration are dropped on arrival. While started, a lost
 * connection is retried on every tick (the poll interval doubles as the reconnect backoff).
 *
 * <p>Instant commands ({@link #runInstCmd}) run on the same thread and connection, so they
 * serialize naturally with polling and authentication state stays connection-accurate.
 */
public final class PollService {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long POLL_SECONDS = 5;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nux-poll");
        t.setDaemon(true);
        return t;
    });

    private volatile long generation = 0;

    // Poll-thread confined:
    private NutClient client;
    private boolean wasConnected;
    private String authedUser; // non-null once USERNAME/PASSWORD succeeded on this connection
    private String cmdTarget; // the UPS whose command list was last fetched and announced

    // FX-thread confined:
    private Consumer<List<UpsDevice>> onConnected = devices -> {};
    private Consumer<UpsSnapshot> onSnapshot = snapshot -> {};
    private Consumer<List<String>> onCommands = commands -> {};
    private Consumer<String> onDisconnected = reason -> {};

    public void setOnConnected(Consumer<List<UpsDevice>> handler) {
        this.onConnected = handler;
    }

    public void setOnSnapshot(Consumer<UpsSnapshot> handler) {
        this.onSnapshot = handler;
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
    public void runInstCmd(String ups, String user, String password, String command, BiConsumer<Boolean, String> onResult) {
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
                client.instCmd(ups, command);
                fxResult(onResult, true, "OK");
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

    private void tick(long gen, String host, int port, String ups, TlsMode tls) {
        if (gen != generation) {
            return; // superseded configuration; its fixed-delay task keeps firing but does nothing
        }
        try {
            if (client == null) {
                client = NutClient.connect(host, port, TIMEOUT, tls);
                List<UpsDevice> devices = client.listUps();
                wasConnected = true;
                fx(gen, () -> onConnected.accept(devices));
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

    private String firstUpsName() throws IOException {
        List<UpsDevice> devices = client.listUps();
        return devices.isEmpty() ? null : devices.getFirst().name();
    }

    private void dropConnection() {
        if (client != null) {
            client.close();
            client = null;
        }
        authedUser = null;
        cmdTarget = null;
    }

    private void fx(long gen, Runnable action) {
        Platform.runLater(() -> {
            if (gen == generation) {
                action.run();
            }
        });
    }

    private static void fxResult(BiConsumer<Boolean, String> onResult, boolean ok, String message) {
        Platform.runLater(() -> onResult.accept(ok, message));
    }
}
