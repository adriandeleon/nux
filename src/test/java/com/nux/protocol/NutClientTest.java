package com.nux.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the client against a scripted upsd. These assert what goes on the wire —
 * the parsers are covered elsewhere, but nothing else checks that we send the right
 * command line or interpret the reply correctly.
 */
class NutClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private FakeNutServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeNutServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private NutClient connect() throws IOException {
        return NutClient.connect(server.host(), server.port(), TIMEOUT);
    }

    @Test
    void listsUpsDevices() throws IOException {
        server.on(
                "LIST UPS",
                "BEGIN LIST UPS",
                "UPS cyberpower \"Desk UPS\"",
                "UPS rack \"Rack UPS\"",
                "END LIST UPS");
        try (NutClient client = connect()) {
            List<UpsDevice> devices = client.listUps();
            assertEquals(2, devices.size());
            assertEquals("cyberpower", devices.getFirst().name());
            assertEquals("Desk UPS", devices.getFirst().description());
        }
        assertTrue(server.received().contains("LIST UPS"));
    }

    @Test
    void listsVariablesInServerOrder() throws IOException {
        server.on(
                "LIST VAR cp",
                "BEGIN LIST VAR cp",
                "VAR cp battery.charge \"100\"",
                "VAR cp ups.status \"OL CHRG\"",
                "END LIST VAR cp");
        try (NutClient client = connect()) {
            Map<String, String> vars = client.listVars("cp");
            assertEquals(List.of("battery.charge", "ups.status"), List.copyOf(vars.keySet()));
            assertEquals("OL CHRG", vars.get("ups.status"));
        }
    }

    @Test
    void listsInstantCommands() throws IOException {
        server.on(
                "LIST CMD cp",
                "BEGIN LIST CMD cp",
                "CMD cp beeper.toggle",
                "CMD cp test.battery.start.quick",
                "END LIST CMD cp");
        try (NutClient client = connect()) {
            assertEquals(List.of("beeper.toggle", "test.battery.start.quick"), client.listCmds("cp"));
        }
    }

    @Test
    void getVarReturnsTheValue() throws IOException {
        server.on("GET VAR cp battery.charge", "VAR cp battery.charge \"87\"");
        try (NutClient client = connect()) {
            assertEquals("87", client.getVar("cp", "battery.charge"));
        }
    }

    @Test
    void serverErrorsBecomeNutExceptionsCarryingTheCode() throws IOException {
        server.on("LIST VAR nope", "ERR UNKNOWN-UPS");
        try (NutClient client = connect()) {
            NutException e = assertThrows(NutException.class, () -> client.listVars("nope"));
            assertEquals("UNKNOWN-UPS", e.code());
        }
    }

    @Test
    void unexpectedRepliesFailLoudlyRatherThanBeingIgnored() throws IOException {
        server.on("INSTCMD cp beeper.toggle", "SOMETHING ELSE");
        try (NutClient client = connect()) {
            assertThrows(IOException.class, () -> client.instCmd("cp", "beeper.toggle"));
        }
    }

    @Test
    void instCmdSendsTheExactCommandAndReportsNoTrackingId() throws IOException {
        server.on("INSTCMD cp beeper.toggle", "OK");
        try (NutClient client = connect()) {
            assertEquals("", client.instCmd("cp", "beeper.toggle"));
        }
        assertTrue(server.received().contains("INSTCMD cp beeper.toggle"));
    }

    @Test
    void instCmdReturnsTheTrackingIdWhenTrackingIsOn() throws IOException {
        server.on("INSTCMD cp beeper.toggle", "OK TRACKING abc-123");
        try (NutClient client = connect()) {
            assertEquals("abc-123", client.instCmd("cp", "beeper.toggle"));
        }
    }

    @Test
    void authenticationSendsUsernameThenPassword() throws IOException {
        server.on("USERNAME nuxuser", "OK").on("PASSWORD s3cret", "OK");
        try (NutClient client = connect()) {
            client.username("nuxuser");
            client.password("s3cret");
        }
        List<String> sent = server.received();
        assertEquals("USERNAME nuxuser", sent.get(0));
        assertEquals("PASSWORD s3cret", sent.get(1));
    }

    @Test
    void enableTrackingReportsFalseInsteadOfThrowingOnOlderServers() throws IOException {
        // The whole pre-2.8 fallback depends on this not throwing.
        server.on("SET TRACKING ON", "ERR FEATURE-NOT-CONFIGURED");
        try (NutClient client = connect()) {
            assertFalse(client.enableTracking());
        }
    }

    @Test
    void enableTrackingSucceedsOnASupportingServer() throws IOException {
        server.on("SET TRACKING ON", "OK");
        try (NutClient client = connect()) {
            assertTrue(client.enableTracking());
        }
    }

    @Test
    void trackingResultIsParsedFromTheServerReply() throws IOException {
        server.on("GET TRACKING abc-123", "SUCCESS");
        try (NutClient client = connect()) {
            assertEquals(Tracking.Status.SUCCESS, client.trackingResult("abc-123").status());
        }
    }

    @Test
    void setVarQuotesTheValueAndEscapesQuotesAndBackslashes() throws IOException {
        // The escaping lives in the client, so only a wire assertion can cover it.
        String wire = "SET VAR cp ups.id \"say \\\"hi\\\" c:\\\\path\"";
        server.on(wire, "OK");
        try (NutClient client = connect()) {
            client.setVar("cp", "ups.id", "say \"hi\" c:\\path");
        }
        assertTrue(server.received().contains(wire), "actual: " + server.received());
    }

    @Test
    void listRwResolvesEachVariablesTypeChoicesAndRange() throws IOException {
        server.on(
                        "LIST RW cp",
                        "BEGIN LIST RW cp",
                        "RW cp beeper.status \"enabled\"",
                        "RW cp battery.charge.low \"20\"",
                        "RW cp ups.id \"desk\"",
                        "END LIST RW cp")
                .on("GET TYPE cp beeper.status", "TYPE cp beeper.status ENUM")
                .on(
                        "LIST ENUM cp beeper.status",
                        "BEGIN LIST ENUM cp beeper.status",
                        "ENUM cp beeper.status \"enabled\"",
                        "ENUM cp beeper.status \"disabled\"",
                        "END LIST ENUM cp beeper.status")
                .on("GET TYPE cp battery.charge.low", "TYPE cp battery.charge.low NUMBER RANGE")
                .on(
                        "LIST RANGE cp battery.charge.low",
                        "BEGIN LIST RANGE cp battery.charge.low",
                        "RANGE cp battery.charge.low \"10\" \"50\"",
                        "END LIST RANGE cp battery.charge.low")
                .on("GET TYPE cp ups.id", "TYPE cp ups.id STRING:8");

        try (NutClient client = connect()) {
            List<RwVariable> vars = client.listRw("cp");
            assertEquals(3, vars.size());

            RwVariable beeper = vars.get(0);
            assertEquals(RwVariable.Kind.ENUM, beeper.kind());
            assertEquals(List.of("enabled", "disabled"), beeper.options());
            assertTrue(beeper.accepts("disabled"));

            RwVariable low = vars.get(1);
            assertEquals(RwVariable.Kind.RANGE, low.kind());
            assertEquals(10.0, low.min());
            assertEquals(50.0, low.max());
            assertFalse(low.accepts("5"));

            RwVariable id = vars.get(2);
            assertEquals(RwVariable.Kind.STRING, id.kind());
            assertEquals(8, id.maxLength());
        }
    }

    @Test
    void closeSaysGoodbyeBeforeHangingUp() throws IOException {
        server.on("LIST UPS", "BEGIN LIST UPS", "END LIST UPS");
        NutClient client = connect();
        client.listUps();
        client.close();
        // close() writes LOGOUT and hangs up without reading a reply, so the server may
        // not have recorded it yet — assert with a wait rather than instantaneously.
        assertTrue(server.awaitReceived("LOGOUT", 2000));
    }

    // --- A hostile or broken server must not exhaust memory or hold the caller forever.

    @Test
    void anOverlongReplyLineIsRefused() throws IOException {
        server.on("LIST UPS", "BEGIN LIST UPS", "UPS x \"" + "A".repeat(NutClient.MAX_LINE_CHARS + 10) + "\"", "END LIST UPS");
        try (NutClient client = connect()) {
            IOException e = assertThrows(IOException.class, client::listUps);
            assertTrue(e.getMessage().contains("too long"), e.getMessage());
        }
    }

    @Test
    void anEndlessListIsRefused() throws IOException {
        String[] reply = new String[NutClient.MAX_LIST_LINES + 2];
        reply[0] = "BEGIN LIST UPS";
        for (int i = 1; i < reply.length; i++) {
            reply[i] = "UPS u" + i + " \"x\"";
        }
        server.on("LIST UPS", reply); // no END LIST within the cap
        try (NutClient client = connect()) {
            IOException e = assertThrows(IOException.class, client::listUps);
            assertTrue(e.getMessage().contains("too long"), e.getMessage());
        }
    }

    @Test
    void aServerTricklingBytesHitsTheRequestDeadline() throws Exception {
        // One byte every 100 ms never trips a 300 ms socket timeout; only the per-request
        // deadline (3 x timeout) can end it.
        try (java.net.ServerSocket trickler = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Thread feeder = new Thread(() -> {
                try (java.net.Socket s = trickler.accept()) {
                    s.getInputStream().read(); // wait for the request
                    java.io.OutputStream out = s.getOutputStream();
                    for (int i = 0; i < 200; i++) {
                        out.write('B');
                        out.flush();
                        Thread.sleep(100);
                    }
                } catch (Exception ignored) {
                    // the client hanging up is the point
                }
            });
            feeder.setDaemon(true);
            feeder.start();
            long start = System.nanoTime();
            try (NutClient client = NutClient.connect(
                    "127.0.0.1", trickler.getLocalPort(), Duration.ofMillis(300))) {
                assertThrows(java.net.SocketTimeoutException.class, client::listUps);
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 5000, "gave up after " + millis + " ms");
        }
    }

    @Test
    void credentialsWithSpacesOrQuotesAreQuotedOnTheWire() throws IOException {
        server.on("USERNAME nuxuser", "OK").on("PASSWORD \"pa ss\\\"w\"", "OK");
        try (NutClient client = connect()) {
            client.username("nuxuser");
            client.password("pa ss\"w");
        }
        assertTrue(server.received().contains("PASSWORD \"pa ss\\\"w\""), server.received().toString());
    }
}
