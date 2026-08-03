package com.nux.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleInstanceTest {

    private static final int TIMEOUT = 2000;

    @Test
    void aSecondLaunchIsToldToStandDownAndRaisesTheFirstWindow(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("instance.port");
        CountDownLatch raised = new CountDownLatch(1);
        try (SingleInstance primary = SingleInstance.acquire(lock).orElseThrow()) {
            primary.setOnActivate(raised::countDown);

            assertTrue(SingleInstance.signalExisting(lock, TIMEOUT), "the running instance should answer");
            assertTrue(raised.await(5, TimeUnit.SECONDS), "the window should have been asked to show");
        }
    }

    @Test
    void withNoLockFileTheLaunchProceeds(@TempDir Path dir) {
        assertFalse(SingleInstance.signalExisting(dir.resolve("absent.port"), TIMEOUT));
    }

    @Test
    void aStaleLockFileDoesNotStopTheLaunch(@TempDir Path dir) throws IOException {
        // The recorded port is dead — the previous run crashed.
        Path lock = dir.resolve("instance.port");
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = probe.getLocalPort();
        }
        Files.writeString(lock, Integer.toString(deadPort), StandardCharsets.UTF_8);

        assertFalse(SingleInstance.signalExisting(lock, TIMEOUT), "must start normally, not exit");
    }

    @Test
    void aForeignListenerOnTheRecordedPortDoesNotSwallowTheLaunch(@TempDir Path dir) throws Exception {
        // After a crash the port can be reused by unrelated software. Exiting because
        // *something* answered would leave the user with no Nux at all.
        Path lock = dir.resolve("instance.port");
        try (ServerSocket foreign = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Files.writeString(lock, Integer.toString(foreign.getLocalPort()), StandardCharsets.UTF_8);
            Thread responder = new Thread(() -> {
                try (var socket = foreign.accept()) {
                    socket.getOutputStream().write("SOMETHING ELSE\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // the assertion below is what matters
                }
            });
            responder.setDaemon(true);
            responder.start();

            assertFalse(SingleInstance.signalExisting(lock, TIMEOUT), "only a Nux handshake counts");
        }
    }

    @Test
    void garbageInTheLockFileIsIgnored(@TempDir Path dir) throws IOException {
        Path lock = dir.resolve("instance.port");
        Files.writeString(lock, "not-a-port", StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), SingleInstance.readPort(lock));
        assertFalse(SingleInstance.signalExisting(lock, TIMEOUT));

        Files.writeString(lock, "70000", StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), SingleInstance.readPort(lock), "out-of-range ports are not ports");
    }

    @Test
    void acquiringRecordsThePortAndClosingRemovesIt(@TempDir Path dir) {
        Path lock = dir.resolve("instance.port");
        SingleInstance primary = SingleInstance.acquire(lock).orElseThrow();
        assertEquals(Optional.of(primary.port()), SingleInstance.readPort(lock));

        primary.close();
        assertFalse(Files.exists(lock), "a clean exit should not leave a stale lock behind");
    }

    @Test
    void closingDoesNotDeleteALockThatAnotherInstanceNowOwns(@TempDir Path dir) throws IOException {
        Path lock = dir.resolve("instance.port");
        SingleInstance first = SingleInstance.acquire(lock).orElseThrow();
        // A replacement claimed the lock while this one was on its way out.
        Files.writeString(lock, "65000", StandardCharsets.UTF_8);

        first.close();

        assertEquals(Optional.of(65000), SingleInstance.readPort(lock), "the successor's lock must survive");
    }
}
