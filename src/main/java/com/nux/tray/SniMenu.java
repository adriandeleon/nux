package com.nux.tray;

import static com.nux.i18n.Messages.tr;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * The exported com.canonical.dbusmenu object backing the SNI tray menu — the protocol
 * KDE and GNOME's AppIndicator extension pull menus through. Layout: a disabled live
 * status line, then the app's {@link TrayMenu} — quick actions, submenus, Quit (see
 * {@link SniMenuModel}).
 */
@DBusInterfaceName("com.canonical.dbusmenu")
public interface SniMenu extends DBusInterface {

    String PATH = "/MenuBar";

    MenuPair<UInt32, MenuLayout> GetLayout(int parentId, int recursionDepth, List<String> propertyNames);

    List<MenuItemEntry> GetGroupProperties(List<Integer> ids, List<String> propertyNames);

    Variant<?> GetProperty(int id, String name);

    void Event(int id, String eventId, Variant<?> data, UInt32 timestamp);

    List<Integer> EventGroup(List<MenuEventEntry> events);

    boolean AboutToShow(int id);

    MenuPair<List<Integer>, List<Integer>> AboutToShowGroup(List<Integer> ids);

    class LayoutUpdated extends DBusSignal {
        private final UInt32 revision;
        private final int parent;

        public LayoutUpdated(String path, UInt32 revision, int parent) throws DBusException {
            super(path, revision, parent);
            this.revision = revision;
            this.parent = parent;
        }

        public UInt32 getRevision() {
            return revision;
        }

        public int getParent() {
            return parent;
        }
    }

    /** The exported implementation; thread-safe (dbus calls arrive on dbus-java's threads). */
    final class Impl implements SniMenu, Properties {

        private final Runnable onOpen;
        private final Runnable onQuit;
        private final Consumer<String> onAction;
        private final AtomicInteger revision = new AtomicInteger(1);
        private String statusLine = tr("tray.notConnected"); // guarded by this
        private TrayMenu menu = TrayMenu.minimal(); // guarded by this
        private volatile SniMenuModel model = new SniMenuModel(statusLine, menu);

        Impl(Runnable onOpen, Runnable onQuit, Consumer<String> onAction) {
            this.onOpen = onOpen;
            this.onQuit = onQuit;
            this.onAction = onAction;
        }

        /** Returns the new revision when the status line changed, else -1. */
        synchronized int setStatusLine(String line) {
            if (statusLine.equals(line)) {
                return -1;
            }
            statusLine = line;
            model = new SniMenuModel(statusLine, menu);
            return revision.incrementAndGet();
        }

        /** Returns the new revision when the actions changed, else -1. */
        synchronized int setMenu(TrayMenu next) {
            if (menu.equals(next)) {
                return -1;
            }
            menu = next;
            model = new SniMenuModel(statusLine, menu);
            return revision.incrementAndGet();
        }

        int revision() {
            return revision.get();
        }

        @Override
        public String getObjectPath() {
            return PATH;
        }

        @Override
        public MenuPair<UInt32, MenuLayout> GetLayout(int parentId, int recursionDepth, List<String> propertyNames) {
            return new MenuPair<>(new UInt32(revision.get()), model.layout(parentId, recursionDepth));
        }

        @Override
        public List<MenuItemEntry> GetGroupProperties(List<Integer> ids, List<String> propertyNames) {
            SniMenuModel current = model;
            List<Integer> wanted = ids == null || ids.isEmpty() ? current.ids() : ids;
            return wanted.stream()
                    .map(id -> new MenuItemEntry(id, current.propsFor(id)))
                    .toList();
        }

        @Override
        public Variant<?> GetProperty(int id, String name) {
            Variant<?> value = model.propsFor(id).get(name);
            return value != null ? value : new Variant<>("");
        }

        @Override
        public void Event(int id, String eventId, Variant<?> data, UInt32 timestamp) {
            if (!"clicked".equals(eventId)) {
                return;
            }
            String action = model.actionFor(id);
            if (action == null) {
                return;
            }
            switch (action) {
                case TrayMenu.OPEN -> onOpen.run();
                case TrayMenu.QUIT -> onQuit.run();
                default -> onAction.accept(action);
            }
        }

        @Override
        public List<Integer> EventGroup(List<MenuEventEntry> events) {
            if (events != null) {
                for (MenuEventEntry e : events) {
                    Event(e.id, e.eventId, e.data, e.timestamp);
                }
            }
            return List.of();
        }

        @Override
        public boolean AboutToShow(int id) {
            return false;
        }

        @Override
        public MenuPair<List<Integer>, List<Integer>> AboutToShowGroup(List<Integer> ids) {
            return new MenuPair<>(List.of(), List.of());
        }

        @SuppressWarnings("unchecked")
        @Override
        public <A> A Get(String interfaceName, String propertyName) {
            return (A) GetAll(interfaceName).get(propertyName);
        }

        @Override
        public <A> void Set(String interfaceName, String propertyName, A value) {}

        @Override
        public Map<String, Variant<?>> GetAll(String interfaceName) {
            return Map.of(
                    "Version", new Variant<>(new UInt32(3)),
                    "Status", new Variant<>("normal"),
                    "TextDirection", new Variant<>("ltr"),
                    "IconThemePath", new Variant<>(List.<String>of(), "as"));
        }
    }
}
