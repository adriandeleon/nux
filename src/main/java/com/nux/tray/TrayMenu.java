package com.nux.tray;

import static com.nux.i18n.Messages.tr;

import java.util.ArrayList;
import java.util.List;

/**
 * The tray menu's actions, as a pure value both backends render (dbusmenu on Linux, an
 * AWT PopupMenu elsewhere). Items carry string ids that come back through the backend's
 * action callback; the live status line is added by each backend from the tooltip.
 * Value equality lets the app skip re-sending an unchanged menu.
 */
public record TrayMenu(List<Item> items) {

    public static final String OPEN = "open";
    public static final String QUIT = "quit";
    public static final String CONNECT = "connect";
    public static final String SELF_TEST = "selftest";
    private static final String UPS_PREFIX = "ups:";
    private static final String COMMAND_PREFIX = "cmd:";

    public enum Kind { ACTION, RADIO, SEPARATOR, SUBMENU }

    public record Item(Kind kind, String id, String label, boolean enabled, boolean checked, List<Item> children) {
        public Item {
            children = List.copyOf(children);
        }

        public static Item action(String id, String label, boolean enabled) {
            return new Item(Kind.ACTION, id, label, enabled, false, List.of());
        }

        public static Item radio(String id, String label, boolean checked) {
            return new Item(Kind.RADIO, id, label, true, checked, List.of());
        }

        public static Item separator() {
            return new Item(Kind.SEPARATOR, null, null, true, false, List.of());
        }

        public static Item submenu(String label, List<Item> children) {
            return new Item(Kind.SUBMENU, null, label, !children.isEmpty(), false, children);
        }
    }

    public TrayMenu {
        items = List.copyOf(items);
    }

    /** Open and Quit only: what the menu offers before the app has told it more. */
    public static TrayMenu minimal() {
        return new TrayMenu(List.of(
                Item.action(OPEN, tr("tray.open"), true), Item.separator(), Item.action(QUIT, tr("tray.quit"), true)));
    }

    /**
     * The quick actions: open, connect/disconnect, switch UPS (on a multi-UPS server), the
     * scheduled self-test command and every other instant command — so the common jobs
     * don't need the window. Commands still go through the app's confirmation.
     */
    public static TrayMenu quickActions(
            boolean running, List<String> upses, String selected, List<String> commands, String selfTestCommand) {
        List<Item> items = new ArrayList<>();
        items.add(Item.action(OPEN, tr("tray.open"), true));
        items.add(Item.action(CONNECT, running ? tr("toolbar.disconnect") : tr("toolbar.connect"), true));
        if (running && upses.size() > 1) {
            items.add(Item.submenu(tr("tray.switchUps"), upses.stream()
                    .map(u -> Item.radio(UPS_PREFIX + u, u, u.equals(selected)))
                    .toList()));
        }
        if (running && commands.contains(selfTestCommand)) {
            items.add(Item.action(SELF_TEST, tr("command.selfTest"), true));
        }
        if (running && !commands.isEmpty()) {
            items.add(Item.submenu(tr("toolbar.commands"), commands.stream()
                    .map(c -> Item.action(COMMAND_PREFIX + c, c, true))
                    .toList()));
        }
        items.add(Item.separator());
        items.add(Item.action(QUIT, tr("tray.quit"), true));
        return new TrayMenu(items);
    }

    /** The UPS a "switch UPS" action names, or null when {@code id} isn't one. */
    public static String upsOf(String id) {
        return id != null && id.startsWith(UPS_PREFIX) ? id.substring(UPS_PREFIX.length()) : null;
    }

    /** The instant command a command action names, or null when {@code id} isn't one. */
    public static String commandOf(String id) {
        return id != null && id.startsWith(COMMAND_PREFIX) ? id.substring(COMMAND_PREFIX.length()) : null;
    }
}
