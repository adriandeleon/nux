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

    static final String WINDOWS_RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

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
                Exec=%s%s
                Terminal=false
                Categories=System;Monitor;Utility;
                StartupWMClass=com.nux.App
                X-GNOME-Autostart-enabled=true
                """
                .formatted(desktopExecArg(execPath), minimized ? " --minimized" : "");
    }

    /**
     * One quoted {@code Exec} argument, per the Desktop Entry spec. Two layers apply, in
     * this order when reading: the string-value escapes ({@code \\}, {@code \n}, ...) and
     * then the quoting rules, where {@code "} {@code `} {@code $} {@code \} need a
     * backslash. So a literal backslash ends up as four, and {@code $} as {@code \\$}.
     * {@code %} introduces a field code anywhere in the line and is doubled.
     */
    static String desktopExecArg(String path) {
        StringBuilder quoted = new StringBuilder();
        for (char c : path.toCharArray()) {
            if (c == '"' || c == '`' || c == '$' || c == '\\') {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : quoted.toString().toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                case '%' -> out.append("%%");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
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
                .formatted(MAC_LABEL, xmlEscape(execPath), extraArg);
    }

    static String xmlEscape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A {@code .reg} file that sets the HKCU Run value, for {@code reg import}.
     *
     * <p>Not {@code reg add /d "\"C:\...\Nux.exe\" --minimized"}: that argument contains a
     * space and quotes of its own, so the JDK's Windows process launcher re-quotes it and
     * reg.exe receives a mangled value. A file sidesteps command-line quoting entirely;
     * its only escapes are a backslash before {@code \} and {@code "}. The value quotes
     * the launcher path so a space in it (Program Files) still runs one program.
     * Lines end in CRLF; the caller writes it as UTF-16LE with a BOM, the encoding regedit
     * itself exports, so a non-ASCII user name in the path survives.
     */
    public static String windowsRunRegFile(String execPath, boolean minimized) {
        String command = "\"" + execPath + "\"" + (minimized ? " --minimized" : "");
        return "Windows Registry Editor Version 5.00\r\n"
                + "\r\n"
                + "[" + WINDOWS_RUN_KEY.replace("HKCU\\", "HKEY_CURRENT_USER\\") + "]\r\n"
                + "\"" + regEscape(NAME) + "\"=\"" + regEscape(command) + "\"\r\n";
    }

    static String regEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Applies a file from {@link #windowsRunRegFile}; plain path argument, no embedded quotes. */
    public static List<String> windowsImportArgv(String regFile) {
        return List.of("reg", "import", regFile);
    }

    public static List<String> windowsRemoveArgv() {
        return List.of("reg", "delete", WINDOWS_RUN_KEY, "/v", NAME, "/f");
    }

    public static List<String> windowsQueryArgv() {
        return List.of("reg", "query", WINDOWS_RUN_KEY, "/v", NAME);
    }
}
