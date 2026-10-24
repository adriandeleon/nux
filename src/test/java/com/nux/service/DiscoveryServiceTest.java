package com.nux.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nux.protocol.FakeNutServer;
import com.nux.protocol.Subnet;

class DiscoveryServiceTest {

    @Test
    void candidatesAlwaysIncludeLocalhostAndStayBounded() {
        // Interface enumeration only — no network I/O, so this is CI-safe.
        List<String> candidates = DiscoveryService.candidates();
        assertTrue(candidates.contains("127.0.0.1"), "a local upsd is the common case");
        assertEquals(candidates.size(), candidates.stream().distinct().count(), "no duplicate probes");
        // Any interface contributing a huge subnet is skipped by Subnet, so the total
        // stays a scan we can finish in seconds rather than an unbounded sweep.
        assertTrue(candidates.size() <= 16 * Subnet.MAX_HOSTS, "scan size must stay bounded");
    }

    @Test
    void aNewScanSilencesThePreviousOne() throws Exception {
        // The flag was shared: a rescan reset it and the old scan reported into stale
        // callbacks. Posted callbacks are queued here and only run once both scans have
        // started, so the old scan's results are always delivered after it was superseded.
        try (FakeNutServer server = new FakeNutServer()) {
            server.on("LIST UPS", "BEGIN LIST UPS", "UPS cp \"Desk\"", "END LIST UPS");
            LinkedBlockingQueue<Runnable> posted = new LinkedBlockingQueue<>();
            DiscoveryService discovery = new DiscoveryService(posted::add, () -> List.of(server.host()));
            List<String> calls = new ArrayList<>();

            discovery.scan(server.port(), found -> calls.add("old found"), n -> calls.add("old done"));
            discovery.scan(server.port(), found -> calls.add("new found"), n -> calls.add("new done"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!calls.contains("new done") && System.nanoTime() < deadline) {
                Runnable r = posted.poll(100, TimeUnit.MILLISECONDS);
                if (r != null) {
                    r.run();
                }
            }
            Runnable late;
            while ((late = posted.poll(300, TimeUnit.MILLISECONDS)) != null) {
                late.run();
            }
            assertEquals(List.of("new found", "new done"), calls);
        }
    }
}
