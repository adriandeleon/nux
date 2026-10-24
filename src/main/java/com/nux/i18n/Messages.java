package com.nux.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The UI string catalog. Loaded once at startup from
 * {@code resources/com/nux/i18n/messages[_<lang>].properties}.
 *
 * <p>Read with {@code getResourceAsStream} and an explicit UTF-8 reader rather than
 * {@link java.util.ResourceBundle}: we are reading our own module's resources, so no
 * {@code module-info} opens are needed and the encoding is never in doubt.
 *
 * <p>A missing key resolves to English and then to the key itself — a gap is visible in
 * the UI but never throws.
 */
public final class Messages {

    /** Bundled languages, by ISO code, labelled in their own language. */
    public static final Map<String, String> LANGUAGES = Map.of(
            "en", "English",
            "es", "Español",
            "fr", "Français",
            "de", "Deutsch",
            "pt", "Português",
            "it", "Italiano");

    private static final String BASE = "/com/nux/i18n/messages";

    private static Properties active = new Properties();
    private static Properties fallback = new Properties();
    private static String activeLanguage = "en";
    private static boolean loaded;

    private Messages() {}

    /** Loads the catalog for {@code language}; call once, before any UI is built. */
    public static synchronized void init(String language) {
        loaded = true;
        fallback = load("");
        activeLanguage = language == null || language.isBlank() ? "en" : language;
        active = "en".equals(activeLanguage) ? fallback : load("_" + activeLanguage);
    }

    public static String activeLanguage() {
        return activeLanguage;
    }

    public static Set<String> available() {
        return LANGUAGES.keySet();
    }

    /**
     * Picks the language to use: an explicit preference if bundled, else the system
     * language if bundled, else English. Pure, so the precedence is testable.
     */
    public static String resolve(String preference, Set<String> bundled, String systemLanguage) {
        if (preference != null && !preference.isBlank() && bundled.contains(preference)) {
            return preference;
        }
        if (systemLanguage != null && bundled.contains(systemLanguage)) {
            return systemLanguage;
        }
        return "en";
    }

    /**
     * The default locale for a UI in {@code language}: the system locale itself when it
     * speaks that language, so its region survives (German on a de-AT system formats
     * numbers the Austrian way), otherwise the bare language. Pure, for testing.
     */
    public static Locale uiLocale(String language, Locale system) {
        if (system != null && system.getLanguage().equals(language)) {
            return system;
        }
        return Locale.forLanguageTag(language);
    }

    /**
     * Loads the English catalogue if nothing has initialised one yet, so a lookup that
     * happens before {@link #init} — a static initialiser, a test — still yields real
     * text instead of a raw key.
     */
    private static synchronized void ensureLoaded() {
        if (!loaded) {
            init("en");
        }
    }

    /** The translation for {@code key}; falls back to English, then to the key itself. */
    public static String tr(String key) {
        ensureLoaded();
        String value = active.getProperty(key);
        if (value == null) {
            value = fallback.getProperty(key);
        }
        return value == null ? key : value;
    }

    /** Formats a parameterised message, e.g. {@code "Saved {0}"}. */
    public static String tr(String key, Object... args) {
        String pattern = tr(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            return pattern; // a malformed pattern must not break the UI
        }
    }

    /** The display names of the bundled languages, English first then alphabetical. */
    public static Map<String, String> languageChoices() {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("en", LANGUAGES.get("en"));
        LANGUAGES.entrySet().stream()
                .filter(e -> !"en".equals(e.getKey()))
                .sorted(Map.Entry.comparingByValue())
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered;
    }

    private static Properties load(String suffix) {
        Properties properties = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream(BASE + suffix + ".properties")) {
            if (in != null) {
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // an empty catalog degrades to keys rather than failing startup
        }
        return properties;
    }
}
