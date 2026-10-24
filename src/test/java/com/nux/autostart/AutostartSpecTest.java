package com.nux.autostart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AutostartSpecTest {

    @Test
    void linuxEntryQuotesThePathAndKeepsIconMatching() {
        String entry = AutostartSpec.linuxDesktopEntry("/opt/nux/bin/Nux", true);
        assertTrue(entry.contains("Exec=\"/opt/nux/bin/Nux\" --minimized"));
        // Without StartupWMClass the autostarted window falls back to a generic icon.
        assertTrue(entry.contains("StartupWMClass=com.nux.App"));
        assertTrue(entry.startsWith("[Desktop Entry]"));
        assertFalse(AutostartSpec.linuxDesktopEntry("/opt/nux/bin/Nux", false).contains("--minimized"));
    }

    @Test
    void linuxEntrySurvivesAPathWithSpaces() {
        String entry = AutostartSpec.linuxDesktopEntry("/home/a b/Nux/bin/Nux", false);
        assertTrue(entry.contains("Exec=\"/home/a b/Nux/bin/Nux\""));
    }

    @Test
    void linuxExecFollowsTheDesktopEntryQuotingRules() {
        // Each reserved character gets the quoting backslash, and then every backslash is
        // doubled again by the string-value escape layer the spec applies first on read.
        assertEquals("\"/a\\\\\"b\"", AutostartSpec.desktopExecArg("/a\"b"));
        assertEquals("\"/a\\\\`b\"", AutostartSpec.desktopExecArg("/a`b"));
        assertEquals("\"/a\\\\$HOME\"", AutostartSpec.desktopExecArg("/a$HOME"));
        assertEquals("\"/a\\\\\\\\b\"", AutostartSpec.desktopExecArg("/a\\b"));
        // % starts a field code (%f, %u ...) even inside quotes.
        assertEquals("\"/opt/100%%/Nux\"", AutostartSpec.desktopExecArg("/opt/100%/Nux"));
        assertEquals("\"/a\\nb\"", AutostartSpec.desktopExecArg("/a\nb"));
        // Spaces and single quotes are fine as-is inside double quotes.
        assertEquals("\"/home/o'neil/my apps/Nux\"", AutostartSpec.desktopExecArg("/home/o'neil/my apps/Nux"));

        String entry = AutostartSpec.linuxDesktopEntry("/home/$x/100%/Nux", true);
        assertTrue(entry.contains("Exec=\"/home/\\\\$x/100%%/Nux\" --minimized\n"));
    }

    @Test
    void macAgentRunsOnceAtLoginRatherThanStayingAlive() {
        String plist = AutostartSpec.macLaunchAgent("/Applications/Nux.app/Contents/MacOS/Nux", true);
        assertTrue(plist.contains("<key>RunAtLoad</key>\n    <true/>"));
        // KeepAlive would relaunch the app every time the user quits it.
        assertTrue(plist.contains("<key>KeepAlive</key>\n    <false/>"));
        assertTrue(plist.contains("<string>--minimized</string>"));
        assertTrue(plist.contains(AutostartSpec.MAC_LABEL));
        assertFalse(AutostartSpec.macLaunchAgent("/x/Nux", false).contains("--minimized"));
    }

    @Test
    void macAgentXmlEscapesThePath() {
        String plist = AutostartSpec.macLaunchAgent("/Users/a&b/<Apps>/\"Q\"/it's/Nux", false);
        assertTrue(plist.contains(
                "<string>/Users/a&amp;b/&lt;Apps&gt;/&quot;Q&quot;/it&apos;s/Nux</string>"));
        assertFalse(plist.contains("a&b"), "a bare & makes the whole plist unparseable");
    }

    @Test
    void windowsRegFileQuotesTheLauncherAndEscapesForRegedit() {
        String reg = AutostartSpec.windowsRunRegFile("C:\\Program Files\\Nux\\Nux.exe", true);
        assertEquals(
                "Windows Registry Editor Version 5.00\r\n"
                        + "\r\n"
                        // HKCU, never HKLM: a login item is a per-user preference and needs no admin.
                        + "[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]\r\n"
                        // The stored value is: "C:\Program Files\Nux\Nux.exe" --minimized
                        + "\"Nux\"=\"\\\"C:\\\\Program Files\\\\Nux\\\\Nux.exe\\\" --minimized\"\r\n",
                reg);
        assertFalse(AutostartSpec.windowsRunRegFile("C:\\Nux\\Nux.exe", false).contains("--minimized"));
        assertEquals("a\\\\b\\\"c", AutostartSpec.regEscape("a\\b\"c"));
    }

    @Test
    void windowsArgvTargetsTheCurrentUserRunKey() {
        assertEquals(List.of("reg", "import", "C:\\Users\\A B\\x.reg"),
                AutostartSpec.windowsImportArgv("C:\\Users\\A B\\x.reg"));
        assertTrue(AutostartSpec.windowsRemoveArgv().contains("delete"));
        assertTrue(AutostartSpec.windowsQueryArgv().contains("query"));
        for (List<String> argv : List.of(AutostartSpec.windowsRemoveArgv(), AutostartSpec.windowsQueryArgv())) {
            assertTrue(argv.contains(AutostartSpec.NAME));
            assertTrue(argv.contains("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"));
            // No argument may carry its own quotes: the JDK would re-quote it on Windows.
            assertTrue(argv.stream().noneMatch(a -> a.contains("\"") || a.contains(" ")));
        }
        assertTrue(AutostartSpec.windowsRemoveArgv().contains("/f"), "remove without prompting");
    }

    @Test
    void autostartIsUnavailableOutsideAnInstalledBuild() {
        // Tests never run from a jpackage image, so this exercises the real gate.
        assertTrue(Autostart.launcherPath().isEmpty());
        assertFalse(Autostart.isSupported());
        assertFalse(Autostart.setEnabled(true, true), "must not write login items from a dev run");
        assertTrue(Autostart.unavailableReason().contains("installed build"));
    }
}
