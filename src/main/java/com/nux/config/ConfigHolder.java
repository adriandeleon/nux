package com.nux.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * The live configuration, shared by the app's controllers. Every change goes through
 * {@link #update}, so no part of the app can hold a stale copy and write it back over
 * someone else's change — the bug Settings had when it rebuilt a whole Config from the
 * copy taken when it opened.
 *
 * <p>Reads and edits are FX-thread confined and take effect at once. Saving is file I/O,
 * so {@link #load} hands it to a background writer that coalesces a burst of edits into
 * one write of the latest config; {@link #close} flushes it on quit. The plain
 * constructor saves inline, which keeps tests deterministic.
 */
public final class ConfigHolder implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ConfigHolder.class.getName());

    private final Path path;
    private final Executor saver;
    private final ExecutorService ownedSaver; // non-null when this holder owns its writer thread
    private Config config;

    private Config unsaved; // guarded by this: the newest config not yet on disk
    private boolean saveQueued; // guarded by this

    /** Saves inline on every update. */
    public ConfigHolder(Path path, Config initial) {
        this(path, initial, Runnable::run, null);
    }

    private ConfigHolder(Path path, Config initial, Executor saver, ExecutorService ownedSaver) {
        this.path = path;
        this.config = initial;
        this.saver = saver;
        this.ownedSaver = ownedSaver;
    }

    /** Package-private: saves go to {@code saver}, for tests of the coalescing. */
    static ConfigHolder withSaver(Path path, Config initial, Executor saver) {
        return new ConfigHolder(path, initial, saver, null);
    }

    /**
     * Loads {@code path} (defaults when missing or malformed — never throws) and saves
     * on a background thread from then on.
     */
    public static ConfigHolder load(Path path) {
        ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nux-config");
            t.setDaemon(true);
            return t;
        });
        return new ConfigHolder(path, ConfigStore.load(path), writer, writer);
    }

    public Config get() {
        return config;
    }

    public Path path() {
        return path;
    }

    /** Applies {@code edit} to the current config at once; the save follows on the writer. */
    public void update(UnaryOperator<Config> edit) {
        config = edit.apply(config);
        synchronized (this) {
            unsaved = config;
            if (saveQueued) {
                return; // the queued save will pick up this newer config
            }
            saveQueued = true;
        }
        saver.execute(this::saveLatest);
    }

    private void saveLatest() {
        Config toSave;
        synchronized (this) {
            toSave = unsaved;
            unsaved = null;
            saveQueued = false;
        }
        if (toSave == null) {
            return;
        }
        try {
            ConfigStore.save(path, toSave);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not save config to " + path, e);
        }
    }

    /** Waits (bounded) for a pending save, so a change made just before quitting isn't lost. */
    @Override
    public void close() {
        if (ownedSaver == null) {
            return;
        }
        ownedSaver.shutdown();
        try {
            ownedSaver.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
