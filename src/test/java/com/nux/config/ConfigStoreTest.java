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
    void roundTripsThroughProperties() {
        Config original = Config.defaults()
                .withLast("nas.local", 3493, "rack", true)
                .withAutoConnect(true)
                .withTrayMetric("runtime")
                .withAdvanced(
                        new Advanced(14, 900, 0.28, "€", true, 7, "02:30", 1785700000L, "test.battery.start.deep", false, "es"))
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
        assertFalse(c.autoConnect());
        assertEquals(List.of(), c.profiles());
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
}
