package com.nux.autostart;

import java.util.List;

/**
 * The per-platform content and commands for a start-at-login entry. Pure, so the exact
 * bytes written into a user's login configuration are unit-tested rather than discovered
 * in production.
 */
public final class AutostartSpec {

    /** Registry value name / LaunchAgent label / desktop file base name. */
    public static final String NAME = "Nux";

    public static final String MAC_LABEL = "io.github.adriandeleon.nux";

    private AutostartSpec() {}

    /**
     * The XDG autostart entry. {@code Exec} is quoted because an installed launcher can
     * sit under a path containing spaces, and {@code StartupWMClass} keeps the icon
     * matching that the normal desktop entry sets up.
     */
    public static String linuxDesktopEntry(String execPath, boolean minimized) {
        return """
                [Desktop Entry]
                Type=Application
                Name=Nux
                Comment=NUT (Network UPS Tools) client
                Exec="%s"%s
                Terminal=false
                Categories=System;Monitor;Utility;
                StartupWMClass=com.nux.App
                X-GNOME-Autostart-enabled=true
                """
                .formatted(execPath, minimized ? " --minimized" : "");
    }

    /** A per-user LaunchAgent that runs the app once at login (not a kept-alive daemon). */
    public static String macLaunchAgent(String execPath, boolean minimized) {
        String extraArg = minimized ? "        <string>--minimized</string>\n" : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" \
                "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                %s    </array>
                    <key>RunAtLoad</key>
                    <true/>
                    <key>KeepAlive</key>
                    <false/>
                </dict>
                </plist>
                """
                .formatted(MAC_LABEL, execPath, extraArg);
    }

    /**
     * The argv that adds the HKCU Run value. Using {@code reg.exe} keeps this pure Java
     * with no JNI; the value is the quoted launcher path so spaces survive.
     */
    public static List<String> windowsAddArgv(String execPath, boolean minimized) {
        String command = "\"" + execPath + "\"" + (minimized ? " --minimized" : "");
        return List.of(
                "reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", NAME, "/t", "REG_SZ", "/d", command, "/f");
    }

    public static List<String> windowsRemoveArgv() {
        return List.of(
                "reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", NAME, "/f");
    }

    public static List<String> windowsQueryArgv() {
        return List.of(
                "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", NAME);
    }
}
