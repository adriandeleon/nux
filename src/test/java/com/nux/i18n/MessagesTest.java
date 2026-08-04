package com.nux.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/** Guards the catalogues against drift — a missing key is a visible bug in the UI. */
class MessagesTest {

    private static Properties catalogue(String suffix) {
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream("/com/nux/i18n/messages" + suffix + ".properties")) {
            assertTrue(in != null, "missing catalogue: messages" + suffix + ".properties");
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("could not read messages" + suffix, e);
        }
        return p;
    }

    private static Set<String> keys(Properties p) {
        return new TreeSet<>(p.stringPropertyNames());
    }

    @Test
    void everyBundledLanguageHasExactlyTheBaseKeySet() {
        Set<String> base = keys(catalogue(""));
        assertFalse(base.isEmpty());
        for (String language : Messages.LANGUAGES.keySet()) {
            if ("en".equals(language)) {
                continue;
            }
            Set<String> other = keys(catalogue("_" + language));
            Set<String> missing = new TreeSet<>(base);
            missing.removeAll(other);
            Set<String> extra = new TreeSet<>(other);
            extra.removeAll(base);
            assertTrue(missing.isEmpty(), language + " is missing keys: " + missing);
            assertTrue(extra.isEmpty(), language + " has keys not in the base catalogue: " + extra);
        }
    }

    @Test
    void noTranslationIsLeftEmpty() {
        for (String language : Messages.LANGUAGES.keySet()) {
            Properties p = catalogue("en".equals(language) ? "" : "_" + language);
            for (String key : p.stringPropertyNames()) {
                assertFalse(p.getProperty(key).isBlank(), language + " has a blank value for " + key);
            }
        }
    }

    @Test
    void parameterisedMessagesKeepTheirPlaceholdersInEveryLanguage() {
        // A translation that drops {0} silently loses the UPS name, the count, the reason…
        Properties base = catalogue("");
        for (String key : base.stringPropertyNames()) {
            if (!base.getProperty(key).contains("{0}")) {
                continue;
            }
            for (String language : Messages.LANGUAGES.keySet()) {
                if ("en".equals(language)) {
                    continue;
                }
                String value = catalogue("_" + language).getProperty(key);
                assertTrue(value.contains("{0}"), language + " dropped {0} from " + key + ": " + value);
            }
        }
    }

    @Test
    void parameterisedMessagesFormatWithoutStrayQuoting() {
        // MessageFormat treats a lone apostrophe as an escape, so a French or Italian
        // string like "l'onduleur" would swallow its own placeholder unless doubled.
        List<String> broken = new ArrayList<>();
        for (String language : Messages.LANGUAGES.keySet()) {
            Properties p = catalogue("en".equals(language) ? "" : "_" + language);
            for (String key : p.stringPropertyNames()) {
                String value = p.getProperty(key);
                if (!value.contains("{0}")) {
                    continue;
                }
                String formatted = MessageFormat.format(value, "AAA", "BBB");
                if (!formatted.contains("AAA")) {
                    broken.add(language + ":" + key + " → " + formatted);
                }
            }
        }
        assertTrue(broken.isEmpty(), "placeholders lost to quoting: " + broken);
    }

    @Test
    void resolvePrefersTheExplicitChoiceThenTheSystemThenEnglish() {
        Set<String> bundled = Messages.LANGUAGES.keySet();
        assertEquals("es", Messages.resolve("es", bundled, "fr"));
        assertEquals("fr", Messages.resolve("", bundled, "fr"), "no preference falls back to the system language");
        assertEquals("fr", Messages.resolve(null, bundled, "fr"));
        assertEquals("en", Messages.resolve("", bundled, "ja"), "an unbundled system language falls back to English");
        assertEquals("en", Messages.resolve("kl", bundled, "ja"), "an unbundled preference is ignored");
    }

    @Test
    void lookupsFallBackToEnglishAndThenToTheKey() {
        Messages.init("es");
        assertEquals("Perfiles", Messages.tr("toolbar.profiles"));
        assertEquals("no.such.key", Messages.tr("no.such.key"), "a gap shows the key, never an exception");
        Messages.init("en");
        assertEquals("Profiles", Messages.tr("toolbar.profiles"));
    }

    @Test
    void argumentsAreSubstituted() {
        Messages.init("en");
        assertEquals("3 events", Messages.tr("events.count", 3));
        assertTrue(Messages.tr("alert.contactLost", "timeout").contains("timeout"));
    }

    @Test
    void englishIsListedFirstInThePicker() {
        assertEquals("English", Messages.languageChoices().values().iterator().next());
        assertEquals(Messages.LANGUAGES.size(), Messages.languageChoices().size());
    }
}
