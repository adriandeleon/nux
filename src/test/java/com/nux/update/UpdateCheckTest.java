package com.nux.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateCheckTest {

    /**
     * A realistic release payload. The nested {@code author} and {@code assets} objects
     * repeat the very keys we read — this is the shape that would defeat any extraction
     * that isn't depth-aware.
     */
    private static final String REAL_SHAPE =
            """
            {
              "url": "https://api.github.com/repos/adriandeleon/nux/releases/1",
              "html_url": "https://github.com/adriandeleon/nux/releases/tag/v0.9.0",
              "author": {
                "login": "adriandeleon",
                "html_url": "https://github.com/adriandeleon",
                "name": "not the release name"
              },
              "tag_name": "v0.9.0",
              "name": "Nux 0.9.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name": "Nux-0.9.0-linux-x64.deb", "html_url": "https://example.invalid/asset"}
              ],
              "body": "Notes with a } brace and a \\"quote\\" inside"
            }
            """;

    @Test
    void parsesTheReleaseAndIgnoresNestedDecoyKeys() {
        ReleaseInfo info = UpdateCheck.parseLatest(REAL_SHAPE);
        assertEquals("0.9.0", info.version());
        assertEquals("https://github.com/adriandeleon/nux/releases/tag/v0.9.0", info.url());
        // Not the author's name, and not the first asset's name.
        assertEquals("Nux 0.9.0", info.name());
    }

    @Test
    void refusesDraftsAndPreReleases() {
        assertNull(UpdateCheck.parseLatest(REAL_SHAPE.replace("\"draft\": false", "\"draft\": true")));
        assertNull(UpdateCheck.parseLatest(REAL_SHAPE.replace("\"prerelease\": false", "\"prerelease\": true")));
    }

    @Test
    void refusesPayloadsWithoutATag() {
        assertNull(UpdateCheck.parseLatest("{\"name\": \"no tag here\"}"));
        assertNull(UpdateCheck.parseLatest("not json at all"));
        assertNull(UpdateCheck.parseLatest(""));
        assertNull(UpdateCheck.parseLatest(null));
    }

    @Test
    void stripsTheLeadingVFromTags() {
        assertEquals("1.2.3", UpdateCheck.normalizeVersion("v1.2.3"));
        assertEquals("1.2.3", UpdateCheck.normalizeVersion("V1.2.3"));
        assertEquals("1.2.3", UpdateCheck.normalizeVersion(" 1.2.3 "));
    }

    @Test
    void versionsCompareNumericallyNotLexicographically() {
        // The comparison a string sort gets backwards, and the reason this isn't compareTo.
        assertTrue(UpdateCheck.compareVersions("0.10.0", "0.9.0") > 0);
        assertTrue(UpdateCheck.compareVersions("1.0.0", "0.99.99") > 0);
        assertEquals(0, UpdateCheck.compareVersions("0.4.0", "0.4.0"));
        // Missing components read as zero.
        assertEquals(0, UpdateCheck.compareVersions("1.2", "1.2.0"));
    }

    @Test
    void aPreReleaseSortsBelowItsOwnRelease() {
        assertTrue(UpdateCheck.compareVersions("0.5.0", "0.5.0-SNAPSHOT") > 0);
        assertTrue(UpdateCheck.compareVersions("0.5.0-rc1", "0.5.0") < 0);
    }

    @Test
    void isNewerDrivesTheNotice() {
        assertTrue(UpdateCheck.isNewer("0.4.0", "0.4.1"));
        assertFalse(UpdateCheck.isNewer("0.4.0", "0.4.0"));
        assertFalse(UpdateCheck.isNewer("0.5.0", "0.4.0"), "never offer a downgrade");
        // A dev build should still be told about the release it precedes.
        assertTrue(UpdateCheck.isNewer("0.4.1-SNAPSHOT", "0.4.1"));
        // The unfiltered-build fallback counts as older than anything real.
        assertTrue(UpdateCheck.isNewer("0.0.0", "0.1.0"));
        assertFalse(UpdateCheck.isNewer("0.4.0", ""));
    }

    @Test
    void throttleAllowsAFirstCheckThenWaits() {
        long day = UpdateCheck.DEFAULT_INTERVAL_MS;
        assertTrue(UpdateCheck.isDue(0, 1_000_000, day), "never checked");
        assertFalse(UpdateCheck.isDue(1_000_000, 1_000_000 + day / 2, day));
        assertTrue(UpdateCheck.isDue(1_000_000, 1_000_000 + day, day));
        // Clock moved back: check rather than lock the user out until it catches up.
        assertTrue(UpdateCheck.isDue(5_000_000, 1_000_000, day));
    }
}
