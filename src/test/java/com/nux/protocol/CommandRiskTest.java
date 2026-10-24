package com.nux.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CommandRiskTest {

    @ParameterizedTest
    @CsvSource({
        // cutting the output, directly or per outlet, now or after the UPS's delay
        "load.off, CUTS_POWER",
        "load.off.delay, CUTS_POWER",
        "load.cycle, CUTS_POWER",
        "outlet.1.load.off, CUTS_POWER",
        "outlet.2.load.cycle, CUTS_POWER",
        "outlet.1.shutdown.return, CUTS_POWER",
        "shutdown.return, CUTS_POWER",
        "shutdown.stayoff, CUTS_POWER",
        "shutdown.reboot, CUTS_POWER",
        "shutdown.reboot.graceful, CUTS_POWER",
        "LOAD.OFF, CUTS_POWER",
        "driver.killpower, CUTS_POWER", // offered by real CyberPower units over usbhid-ups
        // running the battery down
        "calibrate.start, DRAINS_BATTERY",
        "test.battery.start.deep, DRAINS_BATTERY",
        "test.failure.start, DRAINS_BATTERY",
        "bypass.start, UNPROTECTED",
        // undoing something risky is safe
        "shutdown.stop, NONE",
        "calibrate.stop, NONE",
        "bypass.stop, NONE",
        "test.battery.stop, NONE",
        // everyday commands
        "beeper.toggle, NONE",
        "beeper.mute, NONE",
        "load.on, NONE",
        "load.on.delay, NONE",
        "test.battery.start.quick, NONE",
        "test.panel.start, NONE",
        "reset.input.minmax, NONE",
        "driver.reload, NONE",
        // matched by segment, never by substring
        "loadoff.thing, NONE",
        "noshutdown.x, NONE",
    })
    void classifiesByWhatTheCommandDoesToTheLoad(String command, CommandRisk expected) {
        assertEquals(expected, CommandRisk.of(command));
    }

    @ParameterizedTest
    @CsvSource({"CUTS_POWER", "DRAINS_BATTERY", "UNPROTECTED"})
    void everyRiskNamesItsConsequence(CommandRisk risk) {
        assertTrue(risk.bodyKey() != null && risk.bodyKey().startsWith("risk."));
    }
}
