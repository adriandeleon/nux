package com.nux.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nux.protocol.TlsMode;

class ConfigStoreTest {

    @Test
    void enablingTheSelfTestArmsItsSchedule() {
        Advanced off = Advanced.defaults();
        Advanced on = new Advanced(30, 0, 0, "$", true, 30, "03:00", 0, 0, Advanced.DEFAULT_TEST_COMMAND, true, "", true, 0, "", Advanced.THEME_SYSTEM, java.util.Map.of(), false);
        assertEquals(1000L, on.armSelfTest(off, 1000).selfTestArmed());
    }

    @Test
    void anArmedScheduleKeepsItsAnchorUnlessItsTimeChanges() {
        Advanced armed = new Advanced(30, 0, 0, "$", true, 30, "03:00", 0, 500, Advanced.DEFAULT_TEST_COMMAND, true, "", true, 0, "", Advanced.THEME_SYSTEM, java.util.Map.of(), false);
        // Saving Settings again (or changing the interval) must not push the first run back.
        assertEquals(500L, armed.armSelfTest(armed, 1000).selfTestArmed());
        Advanced newTime = new Advanced(30, 0, 0, "$", true, 30, "04:00", 0, 500, Advanced.DEFAULT_TEST_COMMAND, true, "", true, 0, "", Advanced.THEME_SYSTEM, java.util.Map.of(), false);
        assertEquals(1000L, newTime.armSelfTest(armed, 1000).selfTestArmed());
    }

    @Test
    void anEnabledScheduleFromBeforeArmingExistedIsArmedOnSight() {
        Advanced legacy = new Advanced(30, 0, 0, "$", true, 30, "03:00", 0, 0, Advanced.DEFAULT_TEST_COMMAND, true, "", true, 0, "", Advanced.THEME_SYSTEM, java.util.Map.of(), false);
        assertEquals(1000L, legacy.armSelfTest(legacy, 1000).selfTestArmed());
    }

    @Test
    void aDisabledScheduleIsNeverArmed() {
        Advanced off = Advanced.defaults();
        assertEquals(0L, off.armSelfTest(off, 1000).selfTestArmed());
    }

    @Test
    void roundTripsThroughProperties() {
        Config original = Config.defaults()
                .withLast("nas.local", 3493, "rack", true)
                .withAutoConnect(true)
                .withTrayMetric("runtime")
                .withAdvanced(
                        new Advanced(14, 900, 0.28, "€", true, 7, "02:30", 1785700000L, 1785600000L, "test.battery.start.deep", false, "es", false, 1785700000000L, "0.3.0", "light",
                                java.util.Map.of("nas.local:3494/rack", NotifyRule.NONE, "localhost:3493/ups", NotifyRule.CRITICAL),
                                true))
                .withProfileSaved(new Profile("desk", "localhost", 3493, "cyberpower", false))
                .withProfileSaved(new Profile("nas", "nas.local", 3494, "", true));
        Config parsed = ConfigStore.parse(ConfigStore.format(original));
        assertEquals(original, parsed);
    }

    @Test
    void advancedFallsBackToDefaultsWhenAbsentOrJunk() {
        Properties p = new Properties();
        p.setProperty("adv.historyDays", "oops");
        p.setProperty("adv.tariffPerKwh", "");
        Advanced a = ConfigStore.parse(p).advanced();
        assertEquals(Advanced.defaults().historyDays(), a.historyDays());
        assertEquals(0.0, a.tariffPerKwh());
        assertEquals(Advanced.DEFAULT_TEST_COMMAND, a.selfTestCommand());
        assertFalse(a.selfTestEnabled());
    }

    @Test
    void parseFallsBackOnMissingOrMalformedValues() {
        Properties p = new Properties();
        p.setProperty("lastPort", "not-a-number");
        Config c = ConfigStore.parse(p);
        assertEquals("localhost", c.lastHost());
        assertEquals(3493, c.lastPort());
        assertFalse(c.lastTls());
        assertFalse(c.tlsTrustAll());
        // On by default: the Settings checkbox is now its only writer, so a fresh install
        // has to start from "reconnect on launch" rather than earning it on first connect.
        assertTrue(c.autoConnect());
        assertEquals(List.of(), c.profiles());
    }

    @Test
    void parseClampsHandEditedNumbersIntoRange() {
        Properties p = new Properties();
        p.setProperty("lastPort", "99999");
        p.setProperty("profiles", "x");
        p.setProperty("profile.x.host", "h");
        p.setProperty("profile.x.port", "0");
        p.setProperty("adv.historyDays", "0"); // would make prune delete everything
        p.setProperty("adv.selfTestIntervalDays", "-3");
        Config c = ConfigStore.parse(p);
        assertEquals(3493, c.lastPort());
        assertEquals(3493, c.profiles().getFirst().port());
        assertEquals(1, c.advanced().historyDays());
        assertEquals(1, c.advanced().selfTestIntervalDays());
    }

    @Test
    void parseSkipsProfileEntriesWithoutHost() {
        Properties p = new Properties();
        p.setProperty("profiles", "good,broken");
        p.setProperty("profile.good.host", "h1");
        // profile.broken.* is absent entirely
        Config c = ConfigStore.parse(p);
        assertEquals(1, c.profiles().size());
        assertEquals("good", c.profiles().getFirst().name());
    }

    @Test
    void loadOfMissingFileYieldsDefaults(@TempDir Path dir) {
        assertEquals(Config.defaults(), ConfigStore.load(dir.resolve("nope.properties")));
    }

    @Test
    void saveThenLoadRoundTrips(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub").resolve("config.properties");
        Config original = Config.defaults()
                .withLast("h", 1234, "u", true)
                .withProfileSaved(new Profile("p", "h", 1234, "u", true));
        ConfigStore.save(file, original);
        assertEquals(original, ConfigStore.load(file));
    }

    @Test
    void withProfileSavedReplacesByName() {
        Config c = Config.defaults()
                .withProfileSaved(new Profile("desk", "old", 1, "", false))
                .withProfileSaved(new Profile("desk", "new", 2, "ups", true));
        assertEquals(1, c.profiles().size());
        assertEquals("new", c.profiles().getFirst().host());
        assertTrue(c.profiles().getFirst().tls());
    }

    @Test
    void withProfileRemovedDropsIt() {
        Config c = Config.defaults()
                .withProfileSaved(new Profile("desk", "h", 1, "", false))
                .withProfileRemoved("desk");
        assertTrue(c.profiles().isEmpty());
    }

    @Test
    void tlsModeHonorsTrustAllEscapeHatch() {
        Config plain = Config.defaults();
        assertEquals(TlsMode.DISABLED, plain.tlsModeFor(false));
        assertEquals(TlsMode.REQUIRED, plain.tlsModeFor(true));

        Config trustAll = ConfigStore.parse(propertiesOf("tlsTrustAll", "true"));
        assertEquals(TlsMode.REQUIRED_TRUST_ALL, trustAll.tlsModeFor(true));
        assertEquals(TlsMode.DISABLED, trustAll.tlsModeFor(false)); // trust-all never forces TLS on
    }

    @Test
    void sanitizeNameStripsSeparatorsAndCase() {
        assertEquals("my-desk-ups", Profile.sanitizeName("  My Desk,UPS "));
        assertEquals("", Profile.sanitizeName(null));
        assertEquals("", Profile.sanitizeName("   "));
    }

    private static Properties propertiesOf(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        return p;
    }

    @Test
    void notifyRulesListOnlyTheExceptionsAndDefaultToAll() {
        Advanced a = Advanced.defaults()
                .withNotifyRule("nas:3493/lab", NotifyRule.NONE)
                .withNotifyRule("nas:3493/desk", NotifyRule.CRITICAL);
        assertEquals(NotifyRule.NONE, a.notifyRule("nas:3493/lab"));
        assertEquals(NotifyRule.ALL, a.notifyRule("nas:3493/other"), "unset means notify");
        Advanced back = a.withNotifyRule("nas:3493/lab", NotifyRule.ALL);
        assertEquals(java.util.Set.of("nas:3493/desk"), back.notifyRules().keySet(), "ALL removes the entry");
        assertTrue(NotifyRule.CRITICAL.allows(true));
        assertFalse(NotifyRule.CRITICAL.allows(false));
        assertFalse(NotifyRule.NONE.allows(true));
        assertEquals(NotifyRule.ALL, NotifyRule.from("bogus"));
    }
}
