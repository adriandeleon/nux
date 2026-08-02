package com.nux.service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort desktop notifications with zero dependencies. Linux tries
 * {@code notify-send} (libnotify) and falls back to calling the freedesktop
 * notification service directly via {@code gdbus} (ships with GLib, so it's
 * present on virtually every desktop even when libnotify-bin isn't). macOS uses
 * {@code osascript}; elsewhere it's a no-op. Always runs off the calling thread;
 * failures are swallowed — a notification must never break the app.
 */
public final class Notifier {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nux-notify");
        t.setDaemon(true);
        return t;
    });

    private Notifier() {}

    public static void send(String title, String message, boolean critical) {
        EXEC.execute(() -> deliver(title, message, critical));
    }

    private static void deliver(String title, String message, boolean critical) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            if (!run(notifySendArgv(title, message, critical))) {
                run(gdbusNotifyArgv(title, message, critical));
            }
        } else if (os.contains("mac")) {
            String script = "display notification \"" + appleScriptEscape(message)
                    + "\" with title \"" + appleScriptEscape(title) + "\"";
            run(List.of("osascript", "-e", script));
        }
        // Windows toast support is a future item
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
