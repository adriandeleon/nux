package com.nux.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NotifierTest {

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
