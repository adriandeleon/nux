package com.nux.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class UiLocaleTest {

    @Test
    void theSystemRegionSurvivesWhenTheLanguageMatches() {
        // A German UI on an Austrian system keeps Austrian number and date formats.
        assertEquals(Locale.forLanguageTag("de-AT"), Messages.uiLocale("de", Locale.forLanguageTag("de-AT")));
        assertEquals(Locale.forLanguageTag("pt-BR"), Messages.uiLocale("pt", Locale.forLanguageTag("pt-BR")));
    }

    @Test
    void anExplicitlyChosenOtherLanguageIsUsedBare() {
        // The system region belongs to a different language; don't invent fr-AT.
        assertEquals(Locale.FRENCH, Messages.uiLocale("fr", Locale.forLanguageTag("de-AT")));
        assertEquals(Locale.ENGLISH, Messages.uiLocale("en", null));
    }
}
