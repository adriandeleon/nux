package com.nux.protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny in-process upsd that speaks canned NUT over a loopback socket.
 *
 * <p>Exists so the client can be tested in both directions: what it puts on the wire
 * (asserted through {@link #received()}) and how it interprets replies. Scripted
 * responses are matched on the exact request line; anything unscripted gets
 * {@code ERR UNKNOWN-COMMAND}, which makes a typo in a command loud instead of silent.
 */
public final class FakeNutServer implements AutoCloseable {

    private final ServerSocket server;
    private final List<String> received = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<String>> scripted = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Thread acceptor;
    private volatile boolean closed;

    public FakeNutServer() throws IOException {
        server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "fake-upsd");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Scripts a reply. Repeat calls for the same request queue up, one per invocation. */
    public FakeNutServer on(String request, String... replyLines) {
        scripted.computeIfAbsent(request, k -> new ArrayList<>()).add(String.join("\n", replyLines));
        return this;
    }

    public int port() {
        return server.getLocalPort();
    }

    public String host() {
        return server.getInetAddress().getHostAddress();
    }

    /** Every request line the client sent, in order, across all connections. */
    public List<String> received() {
        return List.copyOf(received);
    }

    /**
     * Waits for a request line to arrive. Needed for anything the client sends without
     * reading a reply — {@code LOGOUT} on close — because the server records on its own
     * thread and an immediate assertion races it.
     */
    public boolean awaitReceived(String line, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (received.contains(line)) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return received.contains(line);
    }

    private void acceptLoop() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                serve(socket);
            } catch (IOException e) {
                if (!closed) {
                    continue; // a client hung up mid-exchange; wait for the next one
                }
                return;
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out =
                new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            received.add(line);
            if (line.equals("LOGOUT")) {
                write(out, "OK Goodbye");
                return;
            }
            write(out, replyFor(line));
        }
    }

    private String replyFor(String request) {
        List<String> queued = scripted.get(request);
        if (queued == null || queued.isEmpty()) {
            return "ERR UNKNOWN-COMMAND";
        }
        // The last scripted reply repeats, so a poll loop doesn't need N copies.
        return queued.size() == 1 ? queued.get(0) : queued.remove(0);
    }

    private static void write(BufferedWriter out, String payload) throws IOException {
        out.write(payload);
        out.write('\n');
        out.flush();
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // nothing useful to do while tearing a test fixture down
        }
    }
}
