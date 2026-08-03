package com.nux.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CommandRegistryTest {

    private static CommandRegistry sample() {
        CommandRegistry r = new CommandRegistry();
        r.register("app.settings", "Open Settings…", () -> {});
        r.register("app.connect", "Connect / Disconnect", () -> {});
        r.register("ups.cmd.beeper.toggle", "UPS command: beeper.toggle", () -> {});
        r.register("profile.connect.desk", "Connect to profile: desk", () -> {});
        return r;
    }

    @Test
    void keepsInsertionOrder() {
        assertEquals("app.settings", sample().all().getFirst().id());
    }

    @Test
    void registerReplacesById() {
        CommandRegistry r = sample();
        r.register("app.settings", "Renamed", () -> {});
        assertEquals(4, r.all().size());
        assertEquals("Renamed", r.all().stream()
                .filter(c -> c.id().equals("app.settings"))
                .findFirst().orElseThrow().title());
    }

    @Test
    void removeByPrefixDropsAFamily() {
        CommandRegistry r = sample();
        r.removeByPrefix("ups.cmd.");
        assertTrue(r.all().stream().noneMatch(c -> c.id().startsWith("ups.cmd.")));
        assertEquals(3, r.all().size());
    }

    @Test
    void blankQueryMatchesEverything() {
        assertEquals(4, CommandRegistry.filter(sample().all(), "  ").size());
    }

    @Test
    void prefixMatchesRankAboveSubstringMatches() {
        List<Command> hits = CommandRegistry.filter(sample().all(), "connect");
        assertEquals("app.connect", hits.getFirst().id()); // title starts with "Connect"
        assertTrue(hits.stream().anyMatch(c -> c.id().equals("profile.connect.desk")));
    }

    @Test
    void matchesOnIdToo() {
        List<Command> hits = CommandRegistry.filter(sample().all(), "beeper");
        assertEquals(1, hits.size());
        assertEquals("ups.cmd.beeper.toggle", hits.getFirst().id());
    }

    @Test
    void noMatchesYieldsEmpty() {
        assertTrue(CommandRegistry.filter(sample().all(), "zzz").isEmpty());
    }
}
