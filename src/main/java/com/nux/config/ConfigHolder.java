package com.nux.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * The live configuration, shared by the app's controllers. Every change goes through
 * {@link #update} and is saved at once, so no part of the app can hold a stale copy and
 * write it back over someone else's change — the bug Settings had when it rebuilt a
 * whole Config from the copy taken when it opened.
 *
 * <p>FX-thread confined, like everything that reads it.
 */
public final class ConfigHolder {

    private static final System.Logger LOG = System.getLogger(ConfigHolder.class.getName());

    private final Path path;
    private Config config;

    public ConfigHolder(Path path, Config initial) {
        this.path = path;
        this.config = initial;
    }

    /** Loads {@code path} (defaults when missing or malformed — never throws). */
    public static ConfigHolder load(Path path) {
        return new ConfigHolder(path, ConfigStore.load(path));
    }

    public Config get() {
        return config;
    }

    public Path path() {
        return path;
    }

    /** Applies {@code edit} to the current config and saves the result. */
    public void update(UnaryOperator<Config> edit) {
        config = edit.apply(config);
        try {
            ConfigStore.save(path, config);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not save config to " + path, e);
        }
    }
}
