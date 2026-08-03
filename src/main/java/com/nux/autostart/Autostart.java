package com.nux.autostart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Start-at-login, per user and per platform: an XDG autostart entry on Linux, a
 * LaunchAgent on macOS, and an HKCU {@code Run} value on Windows.
 *
 * <p>Deliberately <em>not</em> done by the installers. Installers run as root/admin and
 * would either impose autostart on every account or need per-user hooks; login items are
 * a per-user preference, so the app owns them and the OS state is the single source of
 * truth (there is no config key to drift out of sync).
 *
 * <p>Only available when running from an installed package: jpackage exports the
 * launcher path as {@code jpackage.app-path}, and autostarting a Maven dev run would be
 * meaningless.
 */
public final class Autostart {

    private static final System.Logger LOG = System.getLogger(Autostart.class.getName());

    private Autostart() {}

    /** The installed launcher, present only when running from a jpackage image. */
    public static Optional<String> launcherPath() {
        String path = System.getProperty("jpackage.app-path");
        return path == null || path.isBlank() ? Optional.empty() : Optional.of(path);
    }

    public static boolean isSupported() {
        return launcherPath().isPresent() && os() != Os.OTHER;
    }

    /** Why the toggle is unavailable, for the settings UI to explain itself. */
    public static String unavailableReason() {
        if (launcherPath().isEmpty()) {
            return "Available only in an installed build (not when running from source).";
        }
        return "Not supported on this desktop.";
    }

    public static boolean isEnabled() {
        return switch (os()) {
            case LINUX -> Files.isRegularFile(linuxEntry());
            case MAC -> Files.isRegularFile(macEntry());
            case WINDOWS -> run(AutostartSpec.windowsQueryArgv()) == 0;
            case OTHER -> false;
        };
    }

    /** Returns whether the requested state was achieved. */
    public static boolean setEnabled(boolean enabled, boolean minimized) {
        Optional<String> exec = launcherPath();
        if (exec.isEmpty()) {
            return false;
        }
        try {
            return switch (os()) {
                case LINUX -> writeOrDelete(
                        linuxEntry(), enabled, AutostartSpec.linuxDesktopEntry(exec.get(), minimized));
                case MAC -> {
                    boolean ok = writeOrDelete(
                            macEntry(), enabled, AutostartSpec.macLaunchAgent(exec.get(), minimized));
                    // Best-effort: the agent is picked up at next login regardless, but
                    // loading it now makes the toggle take effect without a logout.
                    run(List.of("launchctl", enabled ? "load" : "unload", macEntry().toString()));
                    yield ok;
                }
                case WINDOWS -> run(enabled
                                ? AutostartSpec.windowsAddArgv(exec.get(), minimized)
                                : AutostartSpec.windowsRemoveArgv())
                        == 0;
                case OTHER -> false;
            };
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not update autostart entry", e);
            return false;
        }
    }

    private static boolean writeOrDelete(Path file, boolean enabled, String content) throws IOException {
        if (enabled) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(file);
        }
        return true;
    }

    private static Path linuxEntry() {
        String base = System.getenv("XDG_CONFIG_HOME");
        Path config = base == null || base.isBlank() ? Path.of(home(), ".config") : Path.of(base);
        return config.resolve("autostart").resolve("nux.desktop");
    }

    private static Path macEntry() {
        return Path.of(home(), "Library", "LaunchAgents", AutostartSpec.MAC_LABEL + ".plist");
    }

    private static String home() {
        return System.getProperty("user.home", ".");
    }

    /** Runs a short command, returning its exit code (-1 when it could not run). */
    private static int run(List<String> argv) {
        try {
            Process process = new ProcessBuilder(argv)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private enum Os {
        LINUX,
        MAC,
        WINDOWS,
        OTHER
    }

    private static Os os() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("linux")) {
            return Os.LINUX;
        }
        if (name.contains("mac")) {
            return Os.MAC;
        }
        return name.contains("win") ? Os.WINDOWS : Os.OTHER;
    }
}
