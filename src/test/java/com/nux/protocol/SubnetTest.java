package com.nux.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SubnetTest {

    @Test
    void enumeratesAUsableHostRange() {
        List<String> hosts = Subnet.hosts("192.168.1.42", 24, Subnet.MAX_HOSTS);
        assertEquals(254, hosts.size());
        assertEquals("192.168.1.1", hosts.getFirst());
        assertEquals("192.168.1.254", hosts.getLast());
        // network and broadcast are not hosts
        assertFalse(hosts.contains("192.168.1.0"));
        assertFalse(hosts.contains("192.168.1.255"));
        assertTrue(hosts.contains("192.168.1.42"), "the local address is a legitimate candidate");
    }

    @Test
    void handlesSmallPrefixes() {
        assertEquals(6, Subnet.hosts("10.0.0.1", 29, Subnet.MAX_HOSTS).size());
        assertEquals(List.of("10.0.0.5"), Subnet.hosts("10.0.0.5", 32, Subnet.MAX_HOSTS));
    }

    @Test
    void refusesRangesTooLargeToScan() {
        // A /8 is 16 million addresses: discovery must never become that.
        assertTrue(Subnet.hosts("10.0.0.1", 8, Subnet.MAX_HOSTS).isEmpty());
        assertTrue(Subnet.hosts("172.16.0.1", 16, Subnet.MAX_HOSTS).isEmpty());
        assertFalse(Subnet.hosts("192.168.0.1", 22, Subnet.MAX_HOSTS).isEmpty());
    }

    @Test
    void rejectsMalformedAddresses() {
        assertTrue(Subnet.hosts("not.an.ip", 24, Subnet.MAX_HOSTS).isEmpty());
        assertTrue(Subnet.hosts("192.168.1.999", 24, Subnet.MAX_HOSTS).isEmpty());
        assertTrue(Subnet.hosts(null, 24, Subnet.MAX_HOSTS).isEmpty());
        assertTrue(Subnet.hosts("192.168.1.1", 33, Subnet.MAX_HOSTS).isEmpty());
    }

    @Test
    void addressConversionRoundTrips() {
        assertEquals("192.168.1.42", Subnet.toStringAddress(Subnet.toLong("192.168.1.42")));
        assertEquals("255.255.255.255", Subnet.toStringAddress(Subnet.toLong("255.255.255.255")));
        assertEquals(-1, Subnet.toLong("1.2.3"));
    }
}
