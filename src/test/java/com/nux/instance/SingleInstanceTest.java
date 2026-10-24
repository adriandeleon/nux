package com.nux.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleInstanceTest {

    private static final int TIMEOUT = 2000;

    private static SingleInstance primary(Path dir) throws IOException {
        return SingleInstance.tryAcquire(dir).orElseThrow();
    }

    private static Path portFile(Path dir) {
        return dir.resolve(SingleInstance.PORT_FILE);
    }

    @Test
    void aSecondLaunchIsToldToStandDownAndRaisesTheFirstWindow(@TempDir Path dir) throws Exception {
        CountDownLatch raised = new CountDownLatch(1);
        try (SingleInstance first = primary(dir)) {
            first.setOnActivate(raised::countDown);

            assertTrue(SingleInstance.signalExisting(dir, TIMEOUT), "the running instance should answer");
            assertTrue(raised.await(5, TimeUnit.SECONDS), "the window should have been asked to show");
            assertInstanceOf(SingleInstance.Launch.AlreadyRunning.class, SingleInstance.claim(dir, TIMEOUT));
        }
    }

    @Test
    void theFirstClaimBecomesPrimary(@TempDir Path dir) {
        SingleInstance.Launch launch = SingleInstance.claim(dir, TIMEOUT);
        SingleInstance.Launch.Primary primary = assertInstanceOf(SingleInstance.Launch.Primary.class, launch);
        primary.instance().close();
    }

    @Test
    void theLockIsTheArbiterNotThePortFile(@TempDir Path dir) throws IOException {
        // Two launches racing: neither has published a port when the other checks, so
        // only the OS lock can stop both becoming primary.
        try (SingleInstance first = primary(dir)) {
            Files.delete(portFile(dir)); // as if the winner hadn't written it yet
            assertEquals(Optional.empty(), SingleInstance.tryAcquire(dir), "the lock is taken");
        }
        try (SingleInstance next = primary(dir)) {
            assertTrue(next.port() > 0, "a closed primary releases the lock");
        }
    }

    @Test
    void anActivationBeforeTheWindowExistsIsDeliveredLater(@TempDir Path dir) throws Exception {
        // main() claims before the toolkit starts; a launch in that gap must not be lost.
        try (SingleInstance first = primary(dir)) {
            assertTrue(SingleInstance.signalExisting(dir, TIMEOUT));
            CountDownLatch raised = new CountDownLatch(1);
            first.setOnActivate(raised::countDown);
            assertTrue(raised.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aThrowingHandlerDoesNotKillTheListener(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (SingleInstance first = primary(dir)) {
            first.setOnActivate(() -> {
                calls.incrementAndGet();
                throw new IllegalStateException("boom");
            });
            assertTrue(SingleInstance.signalExisting(dir, TIMEOUT));
            assertTrue(SingleInstance.signalExisting(dir, TIMEOUT), "still answering after a failure");
        }
        assertTrue(calls.get() >= 1);
    }

    @Test
    void aRequestWithoutTheTokenIsIgnored(@TempDir Path dir) throws Exception {
        // Another local user can reach the loopback port but can't read the port file.
        AtomicInteger calls = new AtomicInteger();
        try (SingleInstance first = primary(dir);
                Socket socket = new Socket(InetAddress.getLoopbackAddress(), first.port())) {
            first.setOnActivate(calls::incrementAndGet);
            socket.setSoTimeout(TIMEOUT);
            socket.getOutputStream().write("NUX-SHOW\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertEquals(null, in.readLine(), "no answer, and the connection is dropped");
        }
        assertEquals(0, calls.get());
    }

    @Test
    void thePortFileIsPrivateToItsOwner(@TempDir Path dir) throws IOException {
        try (SingleInstance first = primary(dir)) {
            var endpoint = SingleInstance.readPortFile(portFile(dir)).orElseThrow();
            assertEquals(first.port(), endpoint.port());
            assertEquals(32, endpoint.requestToken().length());
            assertFalse(endpoint.requestToken().equals(endpoint.responseToken()));
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertEquals(
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(portFile(dir)));
            }
            try (var files = Files.list(dir)) {
                assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")), "no temp file left behind");
            }
        }
    }

    @Test
    void withNoPortFileTheLaunchProceeds(@TempDir Path dir) {
        assertFalse(SingleInstance.signalExisting(dir, TIMEOUT));
    }

    @Test
    void aStalePortFileDoesNotStopTheLaunch(@TempDir Path dir) throws IOException {
        // The recorded port is dead — the previous run crashed.
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = probe.getLocalPort();
        }
        Files.writeString(portFile(dir), deadPort + " aaaa bbbb", StandardCharsets.UTF_8);

        assertFalse(SingleInstance.signalExisting(dir, TIMEOUT), "must start normally, not exit");
        SingleInstance.Launch launch = SingleInstance.claim(dir, TIMEOUT);
        assertInstanceOf(SingleInstance.Launch.Primary.class, launch);
        ((SingleInstance.Launch.Primary) launch).instance().close();
    }

    @Test
    void aSquatterOnTheRecordedPortCannotSwallowTheLaunch(@TempDir Path dir) throws Exception {
        // After a crash the port can be reused by unrelated software — even something
        // that knows the protocol and answers NUX-OK. It hears the request token, but
        // the response token was never sent, so it can't produce it.
        try (ServerSocket foreign = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Files.writeString(portFile(dir), foreign.getLocalPort() + " req123 resp456", StandardCharsets.UTF_8);
            Thread responder = new Thread(() -> {
                try (Socket socket = foreign.accept()) {
                    String heard = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                            .readLine();
                    String echoed = heard.replace("NUX-SHOW", "NUX-OK");
                    socket.getOutputStream().write((echoed + "\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // the assertion below is what matters
                }
            });
            responder.setDaemon(true);
            responder.start();

            assertFalse(SingleInstance.signalExisting(dir, TIMEOUT), "only the real response token counts");
        }
    }

    @Test
    void garbageInThePortFileIsIgnored(@TempDir Path dir) throws IOException {
        Path file = portFile(dir);
        Files.writeString(file, "not-a-port", StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), SingleInstance.readPortFile(file));
        assertFalse(SingleInstance.signalExisting(dir, TIMEOUT));

        Files.writeString(file, "70000 a b", StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), SingleInstance.readPortFile(file), "out-of-range ports are not ports");

        Files.writeString(file, "4000", StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), SingleInstance.readPortFile(file), "a port without tokens is not trusted");
    }

    @Test
    void closingRemovesThePortFile(@TempDir Path dir) throws IOException {
        SingleInstance first = primary(dir);
        assertEquals(first.port(), SingleInstance.readPortFile(portFile(dir)).orElseThrow().port());

        first.close();
        assertFalse(Files.exists(portFile(dir)), "a clean exit should not leave a stale port behind");
    }

    @Test
    void closingDoesNotDeleteAPortFileThatAnotherInstanceNowOwns(@TempDir Path dir) throws IOException {
        SingleInstance first = primary(dir);
        // A replacement claimed the port file while this one was on its way out.
        Files.writeString(portFile(dir), "65000 x y", StandardCharsets.UTF_8);

        first.close();

        assertEquals(65000, SingleInstance.readPortFile(portFile(dir)).orElseThrow().port(),
                "the successor's port file must survive");
    }
}
