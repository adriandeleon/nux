package com.nux.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RwVariableTest {

    @Test
    void classifiesTypeFlags() {
        assertEquals(RwVariable.Kind.ENUM, RwVariable.kindOf(List.of("ENUM")));
        assertEquals(RwVariable.Kind.RANGE, RwVariable.kindOf(List.of("NUMBER", "RANGE")));
        assertEquals(RwVariable.Kind.NUMBER, RwVariable.kindOf(List.of("NUMBER")));
        assertEquals(RwVariable.Kind.STRING, RwVariable.kindOf(List.of("STRING:8")));
        assertEquals(RwVariable.Kind.STRING, RwVariable.kindOf(List.of()));
    }

    @Test
    void readsStringLengthCap() {
        assertEquals(8, RwVariable.maxLengthOf(List.of("STRING:8")));
        assertEquals(0, RwVariable.maxLengthOf(List.of("NUMBER")));
        assertEquals(0, RwVariable.maxLengthOf(List.of("STRING:oops")));
    }

    @Test
    void enumOnlyAcceptsItsChoices() {
        RwVariable v = new RwVariable(
                "beeper.status", "enabled", RwVariable.Kind.ENUM, List.of("enabled", "disabled"), null, null, 0);
        assertTrue(v.accepts("disabled"));
        assertFalse(v.accepts("muted"));
    }

    @Test
    void rangeChecksBounds() {
        RwVariable v = new RwVariable(
                "battery.charge.low", "20", RwVariable.Kind.RANGE, List.of(), 10.0, 50.0, 0);
        assertTrue(v.accepts("20"));
        assertTrue(v.accepts("10"));
        assertFalse(v.accepts("9"));
        assertFalse(v.accepts("51"));
        assertFalse(v.accepts("abc"));
    }

    @Test
    void stringRespectsLengthCap() {
        assertTrue(RwVariable.string("ups.id", "x", 4).accepts("abcd"));
        assertFalse(RwVariable.string("ups.id", "x", 4).accepts("abcde"));
        assertTrue(RwVariable.string("ups.id", "x", 0).accepts("anything at all"));
        assertFalse(RwVariable.string("ups.id", "x", 4).accepts(null));
    }
}
