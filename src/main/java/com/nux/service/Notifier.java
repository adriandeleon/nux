package com.nux.service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Best-effort desktop notifications with zero dependencies. Linux tries
 * {@code notify-send} (libnotify) and falls back to calling the freedesktop
 * notification service directly via {@code gdbus} (ships with GLib, so it's
 * present on virtually every desktop even when libnotify-bin isn't). macOS uses
 * {@code osascript}. Windows has no comparable command-line tool, so it goes
 * through the tray icon, which raises a native notification — see
 * {@link #setTraySink}. Always runs off the calling thread; failures are
 * swallowed — a notification must never break the app.
 */
public final class Notifier {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nux-notify");
        t.setDaemon(true);
        return t;
    });

    /** Set by the tray backend when it can raise notifications itself. */
    private static volatile BiConsumer<String, String> traySink;

    private Notifier() {}

    /**
     * Registers the tray as a notification channel (title, message). Windows relies on
     * it entirely; other platforms prefer their native tool and only fall back to it.
     */
    public static void setTraySink(BiConsumer<String, String> sink) {
        traySink = sink;
    }

    public static void send(String title, String message, boolean critical) {
        EXEC.execute(() -> deliver(title, message, critical));
    }

    private static void deliver(String title, String message, boolean critical) {
        if (delivered(os(), title, message, critical)) {
            return;
        }
        // Last resort on any platform: the tray raises it if one is running.
        BiConsumer<String, String> sink = traySink;
        if (sink != null) {
            try {
                sink.accept(title, message);
            } catch (RuntimeException ignored) {
                // the in-app status pill already shows the state
            }
        }
    }

    /**
     * Whether this platform ships a command-line notification tool. Windows does not —
     * nothing built in produces a modern toast — so it relies on the tray sink instead.
     */
    static boolean hasNativeTool(String os) {
        return os.contains("linux") || os.contains("mac");
    }

    /** Returns whether a platform-native tool handled it. */
    private static boolean delivered(String os, String title, String message, boolean critical) {
        if (!hasNativeTool(os)) {
            return false;
        }
        if (os.contains("linux")) {
            return run(notifySendArgv(title, message, critical)) || run(gdbusNotifyArgv(title, message, critical));
        }
        String script = "display notification \"" + appleScriptEscape(message)
                + "\" with title \"" + appleScriptEscape(title) + "\"";
        return run(List.of("osascript", "-e", script));
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    /** Launches argv and waits briefly; false when the tool is missing, hangs, or fails. */
    private static boolean run(List<String> argv) {
        try {
            Process process = new ProcessBuilder(argv).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false; // tool not installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static List<String> notifySendArgv(String title, String message, boolean critical) {
        return List.of(
                "notify-send",
                "--app-name=Nux",
                "--urgency=" + (critical ? "critical" : "normal"),
                title,
                message);
    }

    /** org.freedesktop.Notifications.Notify(app, replaces_id, icon, summary, body, actions, hints, timeout). */
    static List<String> gdbusNotifyArgv(String title, String message, boolean critical) {
        return List.of(
                "gdbus",
                "call",
                "--session",
                "--dest",
                "org.freedesktop.Notifications",
                "--object-path",
                "/org/freedesktop/Notifications",
                "--method",
                "org.freedesktop.Notifications.Notify",
                "Nux",
                "0",
                "",
                title,
                message,
                "[]",
                critical ? "{'urgency': <byte 2>}" : "{}",
                "-1");
    }

    /** Escapes a string for embedding in a double-quoted AppleScript literal. */
    static String appleScriptEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
