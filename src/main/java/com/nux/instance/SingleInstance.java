package com.nux.instance;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps a single Nux per user: a second launch raises the running window instead of
 * starting a rival copy that would poll the same UPS twice.
 *
 * <p>The primary instance listens on an ephemeral <em>loopback</em> port and records it
 * in a lock file. A later launch reads that port and asks the primary to show itself.
 *
 * <p>The handshake matters more than it looks: after a crash the recorded port can be
 * reused by unrelated software, and a launch that connected, said its piece and exited
 * would leave the user with <em>no</em> Nux at all. So the primary must answer with
 * {@link #RESPONSE}; anything else is treated as a stale lock and the new instance
 * starts normally.
 */
public final class SingleInstance implements AutoCloseable {

    static final String REQUEST = "NUX-SHOW";
    static final String RESPONSE = "NUX-OK";

    /** Long enough for a loaded desktop, short enough not to delay a cold start. */
    public static final int HANDSHAKE_TIMEOUT_MS = 1500;

    private final ServerSocket server;
    private final Path lockFile;
    private final Thread acceptor;

    private volatile Runnable onActivate = () -> {};
    private volatile boolean closed;

    private SingleInstance(ServerSocket server, Path lockFile) {
        this.server = server;
        this.lockFile = lockFile;
        this.acceptor = new Thread(this::acceptLoop, "nux-instance");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    /**
     * Becomes the primary instance, or returns empty if the port could not be claimed
     * (in which case the app should still run — never leave the user with nothing).
     */
    public static Optional<SingleInstance> acquire(Path lockFile) {
        try {
            ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            SingleInstance instance = new SingleInstance(server, lockFile);
            instance.writeLockFile();
            return Optional.of(instance);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Called when another launch asks for the window; runs on the listener thread. */
    public void setOnActivate(Runnable action) {
        this.onActivate = action;
    }

    /**
     * Asks an already-running Nux to show itself. Returns true only when one answered,
     * which is the caller's signal that it should exit instead of starting up.
     */
    public static boolean signalExisting(Path lockFile, int timeoutMillis) {
        Optional<Integer> port = readPort(lockFile);
        if (port.isEmpty()) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port.get()), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            BufferedWriter out =
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            out.write(REQUEST);
            out.write('\n');
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return RESPONSE.equals(in.readLine());
        } catch (IOException e) {
            // Nothing listening, or something that isn't Nux: start normally.
            return false;
        }
    }

    static Optional<Integer> readPort(Path lockFile) {
        try {
            if (!Files.isRegularFile(lockFile)) {
                return Optional.empty();
            }
            String text = Files.readString(lockFile, StandardCharsets.UTF_8).trim();
            int port = Integer.parseInt(text);
            return port > 0 && port <= 65535 ? Optional.of(port) : Optional.empty();
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    private void writeLockFile() throws IOException {
        Files.createDirectories(lockFile.getParent());
        Files.writeString(lockFile, Integer.toString(port()), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(lockFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows has no POSIX permissions; the file holds only a port number
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                if (!REQUEST.equals(in.readLine())) {
                    continue;
                }
                BufferedWriter out =
                        new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                out.write(RESPONSE);
                out.write('\n');
                out.flush();
                onActivate.run();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
            }
        }
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
            // Only remove the lock if it still points at us; a replacement may own it now.
            if (readPort(lockFile).filter(p -> p == server.getLocalPort()).isPresent()) {
                Files.deleteIfExists(lockFile);
            }
        } catch (IOException ignored) {
            // a stale lock is handled by the handshake on the next launch
        }
    }
}
