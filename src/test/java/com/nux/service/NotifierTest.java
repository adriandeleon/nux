package com.nux.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NotifierTest {

    @Test
    void windowsHasNoNativeToolSoItMustUseTheTray() {
        // A UPS monitor that cannot warn about a power cut on a supported platform is
        // missing its whole point; Windows notifications go through TrayIcon instead.
        assertFalse(Notifier.hasNativeTool("windows 11"));
        assertTrue(Notifier.hasNativeTool("linux"));
        assertTrue(Notifier.hasNativeTool("mac os x"));
    }

    @Test
    void escapesAppleScriptStringLiterals() {
        assertEquals("plain", Notifier.appleScriptEscape("plain"));
        assertEquals("say \\\"hi\\\"", Notifier.appleScriptEscape("say \"hi\""));
        assertEquals("back\\\\slash", Notifier.appleScriptEscape("back\\slash"));
    }

    @Test
    void notifySendCarriesUrgency() {
        assertTrue(Notifier.notifySendArgv("t", "m", true).contains("--urgency=critical"));
        assertTrue(Notifier.notifySendArgv("t", "m", false).contains("--urgency=normal"));
    }

    @Test
    void gdbusArgvPassesTitleAndBodyAsSeparateArgs() {
        // argv elements are handed to the process directly (no shell), so text with
        // spaces/quotes must survive as single elements
        List<String> argv = Notifier.gdbusNotifyArgv("Power lost", "say \"hi\" & run", false);
        assertTrue(argv.contains("Power lost"));
        assertTrue(argv.contains("say \"hi\" & run"));
        assertTrue(argv.contains("{}"));
        assertTrue(Notifier.gdbusNotifyArgv("t", "m", true).contains("{'urgency': <byte 2>}"));
    }
}
