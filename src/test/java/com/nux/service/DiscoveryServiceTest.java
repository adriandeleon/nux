package com.nux.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

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
}
