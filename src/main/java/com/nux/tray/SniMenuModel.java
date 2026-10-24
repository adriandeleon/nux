package com.nux.tray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.freedesktop.dbus.types.Variant;

/**
 * One revision of the tray menu as dbusmenu sees it: integer ids assigned depth-first to
 * a {@link TrayMenu}, each item's properties, and the layout tree for GetLayout. Pure
 * (no bus), so it is tested directly. The first item is the live status line — the
 * tooltip GNOME never shows (its AppIndicator extension renders SNI menus but not SNI
 * tooltips); clicking it opens the window.
 */
final class SniMenuModel {

    static final int ROOT = 0;
    static final int STATUS = 1;

    private record Node(TrayMenu.Item item, List<Integer> children) {}

    private final String statusLine;
    private final List<Integer> top = new ArrayList<>();
    private final Map<Integer, Node> nodes = new LinkedHashMap<>();
    private int nextId = STATUS + 1;

    SniMenuModel(String statusLine, TrayMenu menu) {
        this.statusLine = statusLine;
        top.add(STATUS);
        for (TrayMenu.Item item : menu.items()) {
            top.add(add(item));
        }
    }

    private int add(TrayMenu.Item item) {
        int id = nextId++;
        List<Integer> children = new ArrayList<>();
        nodes.put(id, new Node(item, children));
        for (TrayMenu.Item child : item.children()) {
            children.add(add(child));
        }
        return id;
    }

    /** Every item id, the status line included. */
    List<Integer> ids() {
        List<Integer> ids = new ArrayList<>();
        ids.add(STATUS);
        ids.addAll(nodes.keySet());
        return ids;
    }

    /** The action a click on {@code id} means, or null for a separator, submenu or unknown id. */
    String actionFor(int id) {
        if (id == STATUS) {
            return TrayMenu.OPEN;
        }
        Node node = nodes.get(id);
        if (node == null) {
            return null;
        }
        return switch (node.item().kind()) {
            case ACTION, RADIO -> node.item().enabled() ? node.item().id() : null;
            case SEPARATOR, SUBMENU -> null;
        };
    }

    Map<String, Variant<?>> propsFor(int id) {
        if (id == ROOT) {
            return Map.of("children-display", new Variant<>("submenu"));
        }
        if (id == STATUS) {
            return Map.of("label", new Variant<>(statusLine), "enabled", new Variant<>(false));
        }
        Node node = nodes.get(id);
        if (node == null) {
            return Map.of();
        }
        TrayMenu.Item item = node.item();
        return switch (item.kind()) {
            case SEPARATOR -> Map.of("type", new Variant<>("separator"));
            case ACTION -> Map.of("label", new Variant<>(item.label()), "enabled", new Variant<>(item.enabled()));
            case RADIO -> Map.of(
                    "label", new Variant<>(item.label()),
                    "toggle-type", new Variant<>("radio"),
                    "toggle-state", new Variant<>(item.checked() ? 1 : 0));
            case SUBMENU -> Map.of(
                    "label", new Variant<>(item.label()),
                    "enabled", new Variant<>(item.enabled()),
                    "children-display", new Variant<>("submenu"));
        };
    }

    /** The subtree under {@code parentId}, {@code depth} levels deep (-1: all of it). */
    MenuLayout layout(int parentId, int depth) {
        List<Integer> childIds = parentId == ROOT ? top
                : nodes.containsKey(parentId) ? nodes.get(parentId).children()
                : List.of();
        List<Variant<?>> children = new ArrayList<>();
        if (depth != 0) {
            for (int child : childIds) {
                children.add(new Variant<>(layout(child, depth < 0 ? -1 : depth - 1), MenuLayout.SIGNATURE));
            }
        }
        return new MenuLayout(parentId, propsFor(parentId), List.copyOf(children));
    }
}
