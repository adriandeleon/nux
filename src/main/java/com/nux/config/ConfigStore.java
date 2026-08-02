package com.nux.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringJoiner;

import com.nux.protocol.NutClient;

/**
 * Loads/saves {@link Config} as a properties file (default {@code ~/.nux/config.properties}).
 * Parse/format are pure and unit-tested; file I/O is a thin atomic-write shell around them.
 * A missing or malformed file yields {@link Config#defaults()} — never an exception.
 */
public final class ConfigStore {

    public static final Path DEFAULT_PATH =
            Path.of(System.getProperty("user.home"), ".nux", "config.properties");

    private ConfigStore() {}

    public static Config parse(Properties p) {
        String lastHost = p.getProperty("lastHost", "localhost");
        int lastPort = parseInt(p.getProperty("lastPort"), NutClient.DEFAULT_PORT);
        String lastUps = p.getProperty("lastUps", "");
        boolean lastTls = Boolean.parseBoolean(p.getProperty("lastTls", "false"));
        boolean tlsTrustAll = Boolean.parseBoolean(p.getProperty("tlsTrustAll", "false"));
        boolean autoConnect = Boolean.parseBoolean(p.getProperty("autoConnect", "false"));

        List<Profile> profiles = new ArrayList<>();
        for (String rawName : p.getProperty("profiles", "").split(",")) {
            String name = rawName.trim();
            if (name.isEmpty()) {
                continue;
            }
            String prefix = "profile." + name + ".";
            String host = p.getProperty(prefix + "host");
            if (host == null || host.isBlank()) {
                continue; // a listed name with no host is a half-written entry; skip it
            }
            int port = parseInt(p.getProperty(prefix + "port"), NutClient.DEFAULT_PORT);
            String ups = p.getProperty(prefix + "ups", "");
            boolean tls = Boolean.parseBoolean(p.getProperty(prefix + "tls", "false"));
            profiles.add(new Profile(name, host, port, ups, tls));
        }
        return new Config(List.copyOf(profiles), lastHost, lastPort, lastUps, lastTls, tlsTrustAll, autoConnect);
    }

    public static Properties format(Config c) {
        Properties p = new Properties();
        p.setProperty("schema", "1");
        p.setProperty("lastHost", c.lastHost());
        p.setProperty("lastPort", Integer.toString(c.lastPort()));
        p.setProperty("lastUps", c.lastUps());
        p.setProperty("lastTls", Boolean.toString(c.lastTls()));
        p.setProperty("tlsTrustAll", Boolean.toString(c.tlsTrustAll()));
        p.setProperty("autoConnect", Boolean.toString(c.autoConnect()));
        StringJoiner names = new StringJoiner(",");
        for (Profile profile : c.profiles()) {
            names.add(profile.name());
            String prefix = "profile." + profile.name() + ".";
            p.setProperty(prefix + "host", profile.host());
            p.setProperty(prefix + "port", Integer.toString(profile.port()));
            p.setProperty(prefix + "ups", profile.ups());
            p.setProperty(prefix + "tls", Boolean.toString(profile.tls()));
        }
        p.setProperty("profiles", names.toString());
        return p;
    }

    public static Config load(Path file) {
        if (!Files.isRegularFile(file)) {
            return Config.defaults();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return Config.defaults();
        }
        return parse(p);
    }

    /** Atomic write (temp + move) so a crash can never leave a half-written config. */
    public static void save(Path file, Config config) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            format(config).store(out, "Nux configuration");
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); // filesystem without atomic move
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
