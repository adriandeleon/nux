package com.nux.instance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps a single Nux per user: a second launch raises the running window instead of
 * starting a rival copy that would poll the same UPS twice.
 *
 * <p>Two files in the settings directory. {@code instance.lock} is the arbiter: the
 * primary holds an OS file lock on it for its whole life, so two launches racing each
 * other can't both win, and a crash releases it automatically. {@code instance.port}
 * names the primary's ephemeral <em>loopback</em> port plus two random tokens; it is
 * written atomically and readable only by its owner.
 *
 * <p>The handshake matters more than it looks. A launch sends {@code NUX-SHOW <request>}
 * and the primary answers {@code NUX-OK <response>}. Only someone who can read the port
 * file knows the request token, so another local user can't pop up (or probe) your
 * window. And because the response token is never sent first, a process squatting on a
 * stale recorded port — after a crash it can be reused by anything, including another
 * user's software — can't produce it even after hearing the request. Anything but the
 * exact answer is treated as "no Nux running" and the launch proceeds: exiting because
 * <em>something</em> answered would leave the user with no Nux at all.
 */
public final class SingleInstance implements AutoCloseable {

    static final String REQUEST = "NUX-SHOW";
    static final String RESPONSE = "NUX-OK";
    static final String LOCK_FILE = "instance.lock";
    static final String PORT_FILE = "instance.port";

    /** Long enough for a loaded desktop, short enough not to delay a cold start. */
    public static final int HANDSHAKE_TIMEOUT_MS = 1500;

    /** A handshake line is a keyword and a 32-char token; anything longer is not Nux. */
    private static final int MAX_LINE = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServerSocket server;
    private final Path portFile;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final String requestToken = newToken();
    private final String responseToken = newToken();
    private final Thread acceptor;

    private Runnable onActivate; // guarded by this
    private boolean activationPending; // guarded by this
    private volatile boolean closed;

    /** What {@link #claim} decided. */
    public sealed interface Launch {
        /** Another Nux answered and was asked to show itself: this launch should exit. */
        record AlreadyRunning() implements Launch {}

        /** This launch is the primary; keep the instance open for the process lifetime. */
        record Primary(SingleInstance instance) implements Launch {}

        /** Couldn't tell (no lock, unanswering holder): run anyway, unguarded. */
        record Unguarded() implements Launch {}
    }

    private SingleInstance(ServerSocket server, Path dir, FileChannel lockChannel, FileLock lock) {
        this.server = server;
        this.portFile = dir.resolve(PORT_FILE);
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.acceptor = new Thread(this::acceptLoop, "nux-instance");
        this.acceptor.setDaemon(true);
    }

    /**
     * Raises a running Nux, or becomes the primary. Called from {@code main} before the
     * toolkit starts, so the claim is made before any window exists — two quick launches
     * can't both get far enough to show one.
     *
     * <p>If the lock is held but the holder doesn't answer yet, it is most likely a
     * launch that won the lock a moment ago and hasn't published its port: retry the
     * handshake briefly. A holder that never answers (hung) gets the benefit of the
     * doubt and this launch runs unguarded, rather than leaving the user with nothing.
     */
    public static Launch claim(Path dir, int timeoutMillis) {
        long deadline = System.nanoTime() + 2L * timeoutMillis * 1_000_000L;
        while (true) {
            if (signalExisting(dir, timeoutMillis)) {
                return new Launch.AlreadyRunning();
            }
            Optional<SingleInstance> acquired;
            try {
                acquired = tryAcquire(dir);
            } catch (IOException e) {
                return new Launch.Unguarded(); // filesystem trouble: never block the launch
            }
            if (acquired.isPresent()) {
                return new Launch.Primary(acquired.get());
            }
            if (System.nanoTime() > deadline) {
                return new Launch.Unguarded();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Launch.Unguarded();
            }
        }
    }

    /**
     * Becomes the primary instance, or returns empty when another process holds the lock.
     * Throws when the lock or the listener could not be set up at all.
     */
    static Optional<SingleInstance> tryAcquire(Path dir) throws IOException {
        Files.createDirectories(dir);
        FileChannel channel =
                FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        SingleInstance instance = null;
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null; // held by this same JVM (tests); just as taken
            }
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            instance = new SingleInstance(
                    new ServerSocket(0, 4, InetAddress.getLoopbackAddress()), dir, channel, lock);
            instance.writePortFile();
            instance.acceptor.start();
            return Optional.of(instance);
        } catch (IOException | RuntimeException e) {
            if (instance != null) {
                instance.close();
            }
            channel.close();
            throw e;
        }
    }

    /**
     * Sets what happens when another launch asks for the window; runs on the listener
     * thread. A request that arrived before this was set (the window didn't exist yet)
     * is delivered now, so it isn't lost.
     */
    public void setOnActivate(Runnable action) {
        boolean deliverNow;
        synchronized (this) {
            onActivate = action;
            deliverNow = activationPending && action != null;
            activationPending = false;
        }
        if (deliverNow) {
            activate(action);
        }
    }

    /**
     * Asks an already-running Nux to show itself. Returns true only when one answered
     * with the expected token, which is the caller's signal that it should exit instead
     * of starting up.
     */
    public static boolean signalExisting(Path dir, int timeoutMillis) {
        Optional<Endpoint> endpoint = readPortFile(dir.resolve(PORT_FILE));
        if (endpoint.isEmpty()) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.get().port()), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            OutputStream out = socket.getOutputStream();
            out.write((REQUEST + " " + endpoint.get().requestToken() + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String reply = readLine(socket.getInputStream());
            return reply != null && tokenEquals(reply, RESPONSE + " " + endpoint.get().responseToken());
        } catch (IOException e) {
            // Nothing listening, or something that isn't Nux: start normally.
            return false;
        }
    }

    /** The contents of the port file. */
    record Endpoint(int port, String requestToken, String responseToken) {}

    static Optional<Endpoint> readPortFile(Path portFile) {
        try {
            if (!Files.isRegularFile(portFile)) {
                return Optional.empty();
            }
            String[] parts = Files.readString(portFile, StandardCharsets.UTF_8).trim().split("\\s+");
            if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
                return Optional.empty();
            }
            int port = Integer.parseInt(parts[0]);
            return port > 0 && port <= 65535 ? Optional.of(new Endpoint(port, parts[1], parts[2])) : Optional.empty();
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Temp file + move, so a concurrent reader never sees half a file; owner-only. */
    private void writePortFile() throws IOException {
        Path dir = portFile.getParent();
        Path temp = Files.createTempFile(dir, PORT_FILE, ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(
                        temp, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows: the profile directory's ACL already keeps other users out
            }
            Files.writeString(temp, port() + " " + requestToken + " " + responseToken + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temp, portFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, portFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                String request = readLine(socket.getInputStream());
                if (request == null || !tokenEquals(request, REQUEST + " " + requestToken)) {
                    continue; // not ours, or another user guessing: say nothing
                }
                // Record the request before answering: once the caller hears back, a
                // setOnActivate that follows must see it.
                Runnable action;
                synchronized (this) {
                    action = onActivate;
                    activationPending = action == null;
                }
                OutputStream out = socket.getOutputStream();
                out.write((RESPONSE + " " + responseToken + "\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                if (action != null) {
                    activate(action);
                }
            } catch (IOException e) {
                if (closed) {
                    return;
                }
            }
        }
    }

    /** A throwing callback must not kill the listener: later launches still need it. */
    private static void activate(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            System.getLogger(SingleInstance.class.getName())
                    .log(System.Logger.Level.WARNING, "Activation handler failed", e);
        }
    }

    /** Reads one short ASCII line; null on EOF or when it runs past {@link #MAX_LINE}. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= MAX_LINE) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            if (b == '\n') {
                return line.toString(StandardCharsets.US_ASCII).strip();
            }
            line.write(b);
        }
        return null;
    }

    /** Constant-time, so a local prober can't recover the token byte by byte. */
    private static boolean tokenEquals(String actual, String expected) {
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // shutting down anyway
        }
        try {
            // Only remove the port file if it still points at us; a successor may own it
            // now. Done before the lock is released, so a successor writes after us.
            if (readPortFile(portFile)
                    .filter(e -> e.port() == server.getLocalPort() && e.requestToken().equals(requestToken))
                    .isPresent()) {
                Files.deleteIfExists(portFile);
            }
        } catch (IOException ignored) {
            // a stale port file is handled by the handshake on the next launch
        }
        try {
            lock.release();
            lockChannel.close();
        } catch (IOException ignored) {
            // the OS drops the lock with the process anyway
        }
    }
}
