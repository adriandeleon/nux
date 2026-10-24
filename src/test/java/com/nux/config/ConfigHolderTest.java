package com.nux.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigHolderTest {

    @Test
    void editsApplyAtOnceAndABurstIsSavedOnceAsTheLatest(@TempDir Path dir) {
        // Saving is disk I/O and leaves the FX thread; reads must not wait for it.
        Path file = dir.resolve("config.properties");
        Deque<Runnable> writer = new ArrayDeque<>();
        ConfigHolder holder = ConfigHolder.withSaver(file, Config.defaults(), writer::add);

        holder.update(c -> c.withTrayMetric("load"));
        holder.update(c -> c.withTrayMetric("runtime"));
        holder.update(c -> c.withLast("nas", 3494, "rack", false));

        assertEquals("runtime", holder.get().trayMetric(), "reads see the edit immediately");
        assertEquals(1, writer.size(), "a burst of edits queues one save");
        writer.poll().run();
        Config saved = ConfigStore.load(file);
        assertEquals("runtime", saved.trayMetric());
        assertEquals("nas", saved.lastHost(), "the save writes the latest config, not the first edit");
    }

    @Test
    void anEditAfterTheSaveRanQueuesAnother(@TempDir Path dir) {
        Deque<Runnable> writer = new ArrayDeque<>();
        ConfigHolder holder = ConfigHolder.withSaver(dir.resolve("c.properties"), Config.defaults(), writer::add);
        holder.update(c -> c.withTrayMetric("load"));
        writer.poll().run();
        holder.update(c -> c.withTrayMetric("runtime"));
        assertEquals(1, writer.size());
    }

    @Test
    void theBackgroundWriterIsFlushedOnClose(@TempDir Path dir) {
        Path file = dir.resolve("config.properties");
        ConfigHolder holder = ConfigHolder.load(file);
        holder.update(c -> c.withTrayMetric("runtime"));
        holder.close();
        assertEquals("runtime", ConfigStore.load(file).trayMetric());
    }
}
