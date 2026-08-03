package com.nux;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Identity of this build. The version is read from a Maven-filtered resource rather than
 * a constant, so {@code pom.xml} stays the single source of truth and a release bump
 * cannot leave a stale number behind in the UI.
 */
public final class AppInfo {

    public static final String NAME = "Nux";
    public static final String AUTHOR = "Adrián Arturo De León Saldivar";
    public static final String COPYRIGHT = "Copyright © 2026 " + AUTHOR;
    public static final String LICENSE = "MIT License";
    public static final String REPOSITORY = "https://github.com/adriandeleon/nux";
    public static final String LICENSE_URL = REPOSITORY + "/blob/master/LICENSE";
    public static final String ISSUES_URL = REPOSITORY + "/issues";
    /** The protocol and server Nux is a client for. */
    public static final String NUT_URL = "https://networkupstools.org/";

    public static final String VERSION = load("version", "0.0.0");
    public static final String BUILD_TIME = load("buildTime", "");

    private AppInfo() {}

    /** True for a development build; the About window says so rather than implying a release. */
    public static boolean isSnapshot() {
        return VERSION.endsWith("-SNAPSHOT");
    }

    /** A paste-ready summary for bug reports. Pure. */
    public static String supportSummary(String configDir) {
        return String.join(
                System.lineSeparator(),
                NAME + " " + VERSION + (BUILD_TIME.isEmpty() ? "" : " (built " + BUILD_TIME + ")"),
                "Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                "JavaFX: " + System.getProperty("javafx.runtime.version", "unknown"),
                "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " ("
                        + System.getProperty("os.arch") + ")",
                "Config: " + configDir);
    }

    private static String load(String key, String fallback) {
        try (InputStream in = AppInfo.class.getResourceAsStream("/com/nux/build-info.properties")) {
            if (in == null) {
                return fallback;
            }
            Properties p = new Properties();
            p.load(in);
            String value = p.getProperty(key, fallback);
            // An unfiltered run (IDE, raw classpath) leaves the placeholder behind.
            return value.startsWith("${") ? fallback : value;
        } catch (IOException e) {
            return fallback;
        }
    }
}
