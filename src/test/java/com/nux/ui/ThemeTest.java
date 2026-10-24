package com.nux.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.application.ColorScheme;

import org.junit.jupiter.api.Test;

class ThemeTest {

    @Test
    void anExplicitChoiceWinsOverTheDesktop() {
        assertTrue(Theme.isDark("dark", ColorScheme.LIGHT));
        assertFalse(Theme.isDark("light", ColorScheme.DARK));
    }

    @Test
    void systemFollowsTheDesktopAndDefaultsToDark() {
        assertFalse(Theme.isDark("system", ColorScheme.LIGHT));
        assertTrue(Theme.isDark("system", ColorScheme.DARK));
        assertTrue(Theme.isDark("system", null), "a desktop that doesn't say gets the original dark look");
        assertTrue(Theme.isDark("bogus", ColorScheme.DARK));
    }

    @Test
    void thePalettesDifferWhereItMatters() {
        assertTrue(Theme.DARK.dark() && !Theme.LIGHT.dark());
        assertFalse(Theme.DARK.text().equals(Theme.LIGHT.text()), "text must contrast with each background");
    }
}
