package com.nux.protocol;

import java.util.ArrayList;
import java.util.List;

/** IPv4 subnet enumeration for LAN discovery. Pure. */
public final class Subnet {

    /** Scanning is a burst of connections, so refuse ranges bigger than a /22. */
    public static final int MAX_HOSTS = 1024;

    private Subnet() {}

    /**
     * Every usable host address in the subnet containing {@code ipv4}, excluding the
     * network and broadcast addresses. Returns an empty list for a malformed address
     * or a range wider than {@code max} — a discovery feature must never turn into an
     * unbounded scan of someone's /8.
     */
    public static List<String> hosts(String ipv4, int prefixLength, int max) {
        long base = toLong(ipv4);
        if (base < 0 || prefixLength < 0 || prefixLength > 32) {
            return List.of();
        }
        if (prefixLength >= 31) {
            // /31 and /32 have no usable host range in the classic sense; just the address.
            return List.of(ipv4);
        }
        long size = 1L << (32 - prefixLength);
        long usable = size - 2;
        if (usable > max) {
            return List.of();
        }
        long mask = (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
        long network = base & mask;
        List<String> out = new ArrayList<>((int) usable);
        for (long i = 1; i <= usable; i++) {
            out.add(toStringAddress(network + i));
        }
        return out;
    }

    /** Parses dotted-quad IPv4 to an unsigned long, or -1 when it isn't one. */
    static long toLong(String ipv4) {
        if (ipv4 == null) {
            return -1;
        }
        String[] parts = ipv4.trim().split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long value = 0;
        for (String part : parts) {
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    return -1;
                }
                value = (value << 8) | octet;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return value;
    }

    static String toStringAddress(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "." + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
