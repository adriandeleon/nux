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
        boolean closeToTray) {

    public static Config defaults() {
        return new Config(List.of(), "localhost", NutClient.DEFAULT_PORT, "", false, false, false, true, true);
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
        return new Config(profiles, host, port, ups == null ? "" : ups, tls, tlsTrustAll, autoConnect, tray, closeToTray);
    }

    public Config withAutoConnect(boolean auto) {
        return new Config(profiles, lastHost, lastPort, lastUps, lastTls, tlsTrustAll, auto, tray, closeToTray);
    }

    /** Adds the profile, replacing any existing one of the same name. */
    public Config withProfileSaved(Profile profile) {
        List<Profile> next = new ArrayList<>(profiles);
        next.removeIf(p -> p.name().equals(profile.name()));
        next.add(profile);
        return new Config(List.copyOf(next), lastHost, lastPort, lastUps, lastTls, tlsTrustAll, autoConnect, tray, closeToTray);
    }

    public Config withProfileRemoved(String name) {
        List<Profile> next = new ArrayList<>(profiles);
        next.removeIf(p -> p.name().equals(name));
        return new Config(List.copyOf(next), lastHost, lastPort, lastUps, lastTls, tlsTrustAll, autoConnect, tray, closeToTray);
    }
}
