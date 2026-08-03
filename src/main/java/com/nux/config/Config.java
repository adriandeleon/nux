package com.nux.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.nux.protocol.NutClient;
import com.nux.protocol.TlsMode;

/** Immutable app configuration; the withers return updated copies for {@link ConfigStore#save}. */
public record Config(
        List<Profile> profiles,
        String lastHost,
        int lastPort,
        String lastUps,
        boolean lastTls,
        boolean tlsTrustAll,
        boolean autoConnect,
        boolean tray,
        boolean closeToTray,
        String trayMetric,
        boolean notifications,
        Advanced advanced) {

    public static Config defaults() {
        return new Config(
                List.of(), "localhost", NutClient.DEFAULT_PORT, "", false, false, false, true, true, "battery", true,
                Advanced.defaults());
    }

    public Optional<Profile> profile(String name) {
        return profiles.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /** The effective mode for a "TLS on/off" choice, honoring the trust-all escape hatch. */
    public TlsMode tlsModeFor(boolean tlsOn) {
        if (!tlsOn) {
            return TlsMode.DISABLED;
        }
        return tlsTrustAll ? TlsMode.REQUIRED_TRUST_ALL : TlsMode.REQUIRED;
    }

    public Config withLast(String host, int port, String ups, boolean tls) {
        return copy(profiles, host, port, ups == null ? "" : ups, tls, autoConnect, trayMetric, advanced);
    }

    public Config withAutoConnect(boolean auto) {
        return copy(profiles, lastHost, lastPort, lastUps, lastTls, auto, trayMetric, advanced);
    }

    public Config withTrayMetric(String metric) {
        return copy(profiles, lastHost, lastPort, lastUps, lastTls, autoConnect, metric, advanced);
    }

    public Config withAdvanced(Advanced updated) {
        return copy(profiles, lastHost, lastPort, lastUps, lastTls, autoConnect, trayMetric, updated);
    }

    /** Adds the profile, replacing any existing one of the same name. */
    public Config withProfileSaved(Profile profile) {
        List<Profile> next = new ArrayList<>(profiles);
        next.removeIf(p -> p.name().equals(profile.name()));
        next.add(profile);
        return copy(List.copyOf(next), lastHost, lastPort, lastUps, lastTls, autoConnect, trayMetric, advanced);
    }

    public Config withProfileRemoved(String name) {
        List<Profile> next = new ArrayList<>(profiles);
        next.removeIf(p -> p.name().equals(name));
        return copy(List.copyOf(next), lastHost, lastPort, lastUps, lastTls, autoConnect, trayMetric, advanced);
    }

    private Config copy(
            List<Profile> profiles,
            String host,
            int port,
            String ups,
            boolean tls,
            boolean auto,
            String metric,
            Advanced adv) {
        return new Config(
                profiles, host, port, ups, tls, tlsTrustAll, auto, tray, closeToTray, metric, notifications, adv);
    }
}
