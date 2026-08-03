package com.nux.service;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.nux.protocol.NutClient;
import com.nux.protocol.Subnet;
import com.nux.protocol.UpsDevice;

/**
 * Finds NUT servers on the local network by probing port 3493 across the machine's own
 * IPv4 subnets. A candidate only counts once it answers {@code LIST UPS}, so the result
 * is a confirmed NUT server rather than "something is listening".
 */
public final class DiscoveryService {

    /** Short per-host timeout: a LAN host answers in milliseconds, and there are many. */
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(400);

    private static final int PARALLELISM = 48;

    public record Found(String host, int port, List<String> upsNames) {}

    private volatile boolean cancelled;

    /**
     * Scans in the background, reporting each hit as it lands. All callbacks fire on the
     * FX thread; {@code onDone} receives the number of addresses probed.
     */
    public void scan(int port, Consumer<Found> onFound, Consumer<Integer> onDone) {
        cancelled = false;
        Thread driver = new Thread(
                () -> {
                    List<String> targets = candidates();
                    ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM, r -> {
                        Thread t = new Thread(r, "nux-discover");
                        t.setDaemon(true);
                        return t;
                    });
                    AtomicInteger probed = new AtomicInteger();
                    for (String host : targets) {
                        pool.execute(() -> {
                            probed.incrementAndGet();
                            if (cancelled) {
                                return;
                            }
                            probe(host, port).ifPresent(found -> Platform.runLater(() -> onFound.accept(found)));
                        });
                    }
                    pool.shutdown();
                    try {
                        pool.awaitTermination(2, TimeUnit.MINUTES);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    Platform.runLater(() -> onDone.accept(probed.get()));
                },
                "nux-discover-driver");
        driver.setDaemon(true);
        driver.start();
    }

    public void cancel() {
        cancelled = true;
    }

    /** Localhost plus every address in the machine's own IPv4 subnets, deduplicated. */
    static List<String> candidates() {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add("127.0.0.1");
        try {
            for (NetworkInterface nif : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress address = ia.getAddress();
                    if (address instanceof Inet4Address) {
                        hosts.addAll(Subnet.hosts(address.getHostAddress(), ia.getNetworkPrefixLength(), Subnet.MAX_HOSTS));
                    }
                }
            }
        } catch (SocketException e) {
            // no interface enumeration: localhost alone is still a useful scan
        }
        return new ArrayList<>(hosts);
    }

    /** A hit only counts if the host completes a real NUT exchange. */
    private static java.util.Optional<Found> probe(String host, int port) {
        try (NutClient client = NutClient.connect(host, port, PROBE_TIMEOUT)) {
            List<UpsDevice> devices = client.listUps();
            return java.util.Optional.of(
                    new Found(host, port, devices.stream().map(UpsDevice::name).toList()));
        } catch (IOException | RuntimeException e) {
            return java.util.Optional.empty();
        }
    }
}
