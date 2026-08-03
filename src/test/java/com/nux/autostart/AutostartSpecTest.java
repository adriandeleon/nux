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
    void windowsArgvTargetsTheCurrentUserAndQuotesThePath() {
        List<String> add = AutostartSpec.windowsAddArgv("C:\\Program Files\\Nux\\Nux.exe", true);
        assertEquals("reg", add.getFirst());
        // HKCU, never HKLM: a login item is a per-user preference and needs no admin.
        assertTrue(add.contains("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"));
        assertTrue(add.contains("\"C:\\Program Files\\Nux\\Nux.exe\" --minimized"));
        assertTrue(add.contains("/f"), "overwrite without prompting");

        assertTrue(AutostartSpec.windowsRemoveArgv().contains("delete"));
        assertTrue(AutostartSpec.windowsQueryArgv().contains("query"));
        for (List<String> argv : List.of(AutostartSpec.windowsRemoveArgv(), AutostartSpec.windowsQueryArgv())) {
            assertTrue(argv.contains(AutostartSpec.NAME));
        }
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
