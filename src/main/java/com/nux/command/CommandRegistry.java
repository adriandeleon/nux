package com.nux.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The registry behind the command palette. Insertion-ordered; dynamic families
 * (per-profile, per-UPS-command) re-register under an id prefix so they can be
 * swapped wholesale when the source list changes. FX-thread confined.
 */
public final class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void register(String id, String title, Runnable action) {
        commands.put(id, new Command(id, title, action));
    }

    public void removeByPrefix(String idPrefix) {
        commands.keySet().removeIf(id -> id.startsWith(idPrefix));
    }

    public List<Command> all() {
        return List.copyOf(commands.values());
    }

    /**
     * Case-insensitive palette filter: blank matches everything; title prefix matches
     * rank above substring matches (in title or id). Pure.
     */
    public static List<Command> filter(Collection<Command> commands, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return List.copyOf(commands);
        }
        List<Command> prefix = new ArrayList<>();
        List<Command> contains = new ArrayList<>();
        for (Command c : commands) {
            String title = c.title().toLowerCase(Locale.ROOT);
            if (title.startsWith(q)) {
                prefix.add(c);
            } else if (title.contains(q) || c.id().toLowerCase(Locale.ROOT).contains(q)) {
                contains.add(c);
            }
        }
        prefix.addAll(contains);
        return prefix;
    }
}
