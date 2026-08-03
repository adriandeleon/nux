package com.nux.tray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;

class TrayDisplayTest {

    private static UpsSnapshot snapshot(Map<String, String> vars) {
        return new UpsSnapshot("cp", vars, Instant.EPOCH);
    }

    @Test
    void showsRoundedChargeText() {
        TrayDisplay d = TrayDisplay.of(snapshot(Map.of("battery.charge", "86.6", "ups.status", "OL")));
        assertEquals("87", d.text());
        assertEquals(UpsStatus.Severity.OK, d.severity());
        assertTrue(d.connected());
        assertFalse(d.attention());
    }

    @Test
    void missingChargeFallsBackToBolt() {
        TrayDisplay d = TrayDisplay.of(snapshot(Map.of("ups.status", "OL")));
        assertNull(d.text());
    }

    @Test
    void metricSelectsWhichNumberShows() {
        UpsSnapshot s = snapshot(Map.of(
                "battery.charge", "87", "ups.load", "23", "battery.runtime", "2280", "ups.status", "OL"));
        assertEquals("87", TrayDisplay.of(s, TrayMetric.BATTERY).text());
        assertEquals("23", TrayDisplay.of(s, TrayMetric.LOAD).text());
        assertEquals("38m", TrayDisplay.of(s, TrayMetric.RUNTIME).text());
        assertNull(TrayDisplay.of(snapshot(Map.of("battery.charge", "87")), TrayMetric.LOAD).text());
    }

    @Test
    void metricParsesLenientlyAndDefaultsToBattery() {
        assertEquals(TrayMetric.RUNTIME, TrayMetric.from(" Runtime "));
        assertEquals(TrayMetric.BATTERY, TrayMetric.from("bogus"));
        assertEquals(TrayMetric.BATTERY, TrayMetric.from(null));
        assertEquals("load", TrayMetric.LOAD.key());
    }

    @Test
    void criticalStateDemandsAttention() {
        TrayDisplay d = TrayDisplay.of(snapshot(Map.of("battery.charge", "8", "ups.status", "OB LB")));
        assertEquals(UpsStatus.Severity.CRITICAL, d.severity());
        assertTrue(d.attention());
    }

    @Test
    void disconnectedNeverDemandsAttention() {
        TrayDisplay d = TrayDisplay.disconnected();
        assertFalse(d.connected());
        assertFalse(d.attention());
        assertNull(d.text());
    }

    @Test
    void tooltipJoinsAvailableParts() {
        String tip = TrayDisplay.tooltip(snapshot(Map.of(
                "device.mfr", "CyberPower",
                "device.model", "CP1500",
                "ups.status", "OL CHRG",
                "battery.charge", "87",
                "battery.runtime", "2280")));
        assertEquals("CyberPower CP1500 · Online, charging · 87% · 38m", tip);
    }

    @Test
    void tooltipFallsBackToUpsNameAndSkipsMissingParts() {
        assertEquals("cp · Online", TrayDisplay.tooltip(snapshot(Map.of("ups.status", "OL"))));
    }

    @Test
    void rendererProducesOpaquePixelsAtEverySize() {
        for (int size : new int[] {16, 24, 48}) {
            var img = TrayIconRenderer.render(TrayDisplay.of(snapshot(Map.of("battery.charge", "50"))), size);
            assertEquals(size, img.getWidth());
            boolean anyInk = false;
            for (int y = 0; y < size && !anyInk; y++) {
                for (int x = 0; x < size; x++) {
                    if ((img.getRGB(x, y) >>> 24) != 0) {
                        anyInk = true;
                        break;
                    }
                }
            }
            assertTrue(anyInk, "icon should draw something at " + size + "px");
        }
    }

    @Test
    void fittedFontNeverOverflowsTheSquare() {
        var img = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        for (int size : new int[] {16, 22, 24, 32, 48}) {
            for (String text : new String[] {"8", "87", "100"}) {
                var font = TrayIconRenderer.fitted(g, text, size);
                int width = g.getFontMetrics(font).stringWidth(text);
                assertTrue(width <= size, text + " at " + size + "px measures " + width);
            }
        }
        g.dispose();
    }

    @Test
    void menuModelDescribesTheFourItems() {
        assertEquals("separator", SniMenuModel.propsFor(SniMenuModel.SEPARATOR, "x")
                .get("type").getValue());
        assertEquals(false, SniMenuModel.propsFor(SniMenuModel.STATUS, "x")
                .get("enabled").getValue());
        assertEquals("Online · 87%", SniMenuModel.propsFor(SniMenuModel.STATUS, "Online · 87%")
                .get("label").getValue());
        assertEquals("submenu", SniMenuModel.propsFor(SniMenuModel.ROOT, "x")
                .get("children-display").getValue());
        var layout = SniMenuModel.layout("Online");
        assertEquals(SniMenuModel.ROOT, layout.id);
        assertEquals(SniMenuModel.ITEM_IDS.size(), layout.children.size());
    }

    @Test
    void pixmapEncodesArgbNetworkOrder() {
        var img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0x80FF2001);
        SniPixmap p = SniPixmap.of(img);
        assertEquals(1, p.width);
        assertEquals(1, p.height);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new byte[] {(byte) 0x80, (byte) 0xFF, (byte) 0x20, (byte) 0x01}, p.data);
    }
}
