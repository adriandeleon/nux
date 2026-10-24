package com.nux.tray;

import static com.nux.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Test;

import com.nux.tray.TrayMenu.Item;
import com.nux.tray.TrayMenu.Kind;

class TrayMenuTest {

    private static List<String> labels(List<Item> items) {
        return items.stream().map(i -> i.kind() == Kind.SEPARATOR ? "---" : i.label()).toList();
    }

    @Test
    void disconnectedItOffersOnlyOpenConnectAndQuit() {
        TrayMenu menu = TrayMenu.quickActions(false, List.of("a", "b"), "a", List.of("beeper.toggle"), "test.battery.start.quick");
        assertEquals(List.of(tr("tray.open"), tr("toolbar.connect"), "---", tr("tray.quit")), labels(menu.items()));
    }

    @Test
    void connectedItOffersSwitchingTheSelfTestAndEveryCommand() {
        TrayMenu menu = TrayMenu.quickActions(
                true, List.of("a", "b"), "b", List.of("beeper.toggle", "test.battery.start.quick"),
                "test.battery.start.quick");
        assertEquals(
                List.of(tr("tray.open"), tr("toolbar.disconnect"), tr("tray.switchUps"), tr("command.selfTest"),
                        tr("toolbar.commands"), "---", tr("tray.quit")),
                labels(menu.items()));
        Item switchUps = menu.items().get(2);
        assertEquals(List.of(false, true), switchUps.children().stream().map(Item::checked).toList(), "b is ticked");
        assertEquals("b", TrayMenu.upsOf(switchUps.children().get(1).id()));
        Item commands = menu.items().get(4);
        assertEquals("beeper.toggle", TrayMenu.commandOf(commands.children().getFirst().id()));
    }

    @Test
    void aSingleUpsServerHasNoSwitchSubmenuAndNoTestWithoutTheCommand() {
        TrayMenu menu = TrayMenu.quickActions(true, List.of("a"), "a", List.of("beeper.toggle"), "test.battery.start.quick");
        assertTrue(menu.items().stream().noneMatch(i -> tr("tray.switchUps").equals(i.label())));
        assertTrue(menu.items().stream().noneMatch(i -> TrayMenu.SELF_TEST.equals(i.id())));
    }

    @Test
    void dbusmenuGetsNestedIdsTheStatusLineAndRadioState() {
        TrayMenu menu = TrayMenu.quickActions(true, List.of("a", "b"), "a", List.of("beeper.toggle"), "x");
        SniMenuModel model = new SniMenuModel("Online · 87%", menu);

        MenuLayout root = model.layout(SniMenuModel.ROOT, -1);
        assertEquals(1 + menu.items().size(), root.children.size(), "status line + the actions");
        MenuLayout status = (MenuLayout) root.children.getFirst().getValue();
        assertEquals("Online · 87%", status.properties.get("label").getValue());
        assertEquals(TrayMenu.OPEN, model.actionFor(SniMenuModel.STATUS), "clicking the status opens Nux");

        MenuLayout switchUps = (MenuLayout) root.children.get(3).getValue();
        assertEquals("submenu", switchUps.properties.get("children-display").getValue());
        assertEquals(2, switchUps.children.size());
        MenuLayout a = (MenuLayout) switchUps.children.getFirst().getValue();
        Map<String, Variant<?>> props = model.propsFor(a.id);
        assertEquals("radio", props.get("toggle-type").getValue());
        assertEquals(1, props.get("toggle-state").getValue());
        assertEquals("a", TrayMenu.upsOf(model.actionFor(a.id)));

        assertNull(model.actionFor(switchUps.id), "a submenu is not an action");
        assertEquals(0, model.layout(SniMenuModel.ROOT, 0).children.size(), "depth 0: the node alone");
        assertEquals(model.ids().size(), 1 + countNodes(menu.items()));
    }

    private static int countNodes(List<Item> items) {
        return items.stream().mapToInt(i -> 1 + countNodes(i.children())).sum();
    }
}
