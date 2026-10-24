package com.nux.ui;

import java.util.Locale;

import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.paint.Color;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;

import com.nux.config.Advanced;

/**
 * Light or dark: the AtlantaFX user-agent stylesheet for the controls, plus the
 * {@link Palette} the Canvas-drawn gauges, sparklines and charts use (CSS can't reach a
 * Canvas). "system" follows the desktop's light/dark preference, live. FX thread only.
 */
public final class Theme {

    /** The colours Canvas drawing needs; each canvas reads them at draw time. */
    public record Palette(
            boolean dark,
            Color text,
            Color muted,
            Color track,
            Color line,
            Color axis,
            Color scale,
            Color ok,
            Color warn,
            Color bad,
            Color onBattery,
            Color readoutBg,
            Color readoutFg) {}

    /** GitHub Primer's dark scale, matching AtlantaFX PrimerDark. */
    public static final Palette DARK = new Palette(
            true,
            Color.web("#c9d1d9"),
            Color.web("#8b949e"),
            Color.web("#6e7781", 0.25),
            Color.web("#58a6ff"),
            Color.web("#6e7781", 0.5),
            Color.web("#8b949e", 0.8),
            Color.web("#2da44e"),
            Color.web("#d4a72c"),
            Color.web("#cf222e"),
            Color.web("#d4a72c", 0.16),
            Color.web("#161b22", 0.92),
            Color.web("#c9d1d9"));

    /** GitHub Primer's light scale, matching AtlantaFX PrimerLight. */
    public static final Palette LIGHT = new Palette(
            false,
            Color.web("#1f2328"),
            Color.web("#59636e"),
            Color.web("#818b98", 0.25),
            Color.web("#0969da"),
            Color.web("#818b98", 0.55),
            Color.web("#59636e", 0.9),
            Color.web("#1a7f37"),
            Color.web("#9a6700"),
            Color.web("#cf222e"),
            Color.web("#bf8700", 0.14),
            Color.web("#ffffff", 0.95),
            Color.web("#1f2328"));

    private static final SimpleObjectProperty<Palette> PALETTE = new SimpleObjectProperty<>(DARK);
    private static String mode = Advanced.THEME_SYSTEM;
    private static boolean followingSystem;

    private Theme() {}

    public static Palette palette() {
        return PALETTE.get();
    }

    /** Canvases listen here to repaint when the theme changes. */
    public static ReadOnlyObjectProperty<Palette> paletteProperty() {
        return PALETTE;
    }

    /** Applies "system", "dark" or "light" (anything else is "system"). */
    public static void apply(String requested) {
        mode = requested == null ? Advanced.THEME_SYSTEM : requested.toLowerCase(Locale.ROOT);
        if (!followingSystem) {
            followingSystem = true;
            // A desktop switching light/dark (e.g. at sunset) re-applies "system" live.
            Platform.getPreferences().colorSchemeProperty().addListener((o, was, now) -> {
                if (Advanced.THEME_SYSTEM.equals(mode)) {
                    show(isDark(mode, now));
                }
            });
        }
        show(isDark(mode, Platform.getPreferences().getColorScheme()));
    }

    /** Pure: whether {@code mode} means dark, given the desktop's scheme. Unknown modes follow it. */
    static boolean isDark(String mode, ColorScheme system) {
        return switch (mode == null ? "" : mode) {
            case "dark" -> true;
            case "light" -> false;
            default -> system != ColorScheme.LIGHT; // dark when the desktop doesn't say
        };
    }

    private static void show(boolean dark) {
        Application.setUserAgentStylesheet(
                dark ? new PrimerDark().getUserAgentStylesheet() : new PrimerLight().getUserAgentStylesheet());
        PALETTE.set(dark ? DARK : LIGHT);
    }
}
