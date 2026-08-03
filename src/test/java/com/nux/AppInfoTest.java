package com.nux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppInfoTest {

    @Test
    void versionComesFromTheBuildRatherThanAConstant() {
        // Maven filters build-info.properties; an unreplaced placeholder or a missing
        // resource must fall back, never surface "${project.version}" in the UI.
        assertFalse(AppInfo.VERSION.isBlank());
        assertFalse(AppInfo.VERSION.startsWith("${"));
        assertTrue(AppInfo.VERSION.matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"), "unexpected: " + AppInfo.VERSION);
    }

    @Test
    void snapshotBuildsAreIdentifiable() {
        assertEquals(AppInfo.VERSION.endsWith("-SNAPSHOT"), AppInfo.isSnapshot());
    }

    @Test
    void attributionAndLinksArePresent() {
        assertTrue(AppInfo.COPYRIGHT.contains("Adrián Arturo De León Saldivar"));
        assertEquals("MIT License", AppInfo.LICENSE);
        assertTrue(AppInfo.REPOSITORY.startsWith("https://github.com/"));
        assertTrue(AppInfo.LICENSE_URL.startsWith(AppInfo.REPOSITORY));
        assertTrue(AppInfo.ISSUES_URL.startsWith(AppInfo.REPOSITORY));
        assertTrue(AppInfo.NUT_URL.startsWith("https://"));
    }

    @Test
    void supportSummaryCarriesWhatABugReportNeeds() {
        String summary = AppInfo.supportSummary("/home/someone/.nux");
        assertTrue(summary.contains(AppInfo.VERSION));
        assertTrue(summary.contains("Java:"));
        assertTrue(summary.contains("OS:"));
        assertTrue(summary.contains("/home/someone/.nux"));
    }
}
