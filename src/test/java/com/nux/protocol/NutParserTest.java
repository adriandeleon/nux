package com.nux.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NutParserTest {

    @Test
    void tokensSplitOnWhitespace() {
        assertEquals(List.of("GET", "VAR", "ups", "battery.charge"), NutParser.tokens("GET VAR ups battery.charge"));
    }

    @Test
    void tokensHonorQuotes() {
        assertEquals(
                List.of("VAR", "cp", "ups.model", "Value 2200E"),
                NutParser.tokens("VAR cp ups.model \"Value 2200E\""));
    }

    @Test
    void tokensHonorEscapesInsideQuotes() {
        assertEquals(
                List.of("VAR", "cp", "x", "say \"hi\" \\ done"),
                NutParser.tokens("VAR cp x \"say \\\"hi\\\" \\\\ done\""));
    }

    @Test
    void tokensKeepEmptyQuotedToken() {
        assertEquals(List.of("VAR", "cp", "ups.alarm", ""), NutParser.tokens("VAR cp ups.alarm \"\""));
    }

    @Test
    void tokensCollapseRepeatedWhitespace() {
        assertEquals(List.of("a", "b"), NutParser.tokens("  a \t b  "));
    }

    @Test
    void parsesUpsLine() {
        UpsDevice d = NutParser.parseUpsLine("UPS cyberpower \"Desk UPS\"");
        assertEquals("cyberpower", d.name());
        assertEquals("Desk UPS", d.description());
    }

    @Test
    void parsesUpsLineWithoutDescription() {
        UpsDevice d = NutParser.parseUpsLine("UPS cyberpower");
        assertEquals("cyberpower", d.name());
        assertEquals("", d.description());
    }

    @Test
    void parsesVarLine() {
        UpsVar v = NutParser.parseVarLine("VAR cyberpower battery.charge \"100\"");
        assertEquals("cyberpower", v.ups());
        assertEquals("battery.charge", v.name());
        assertEquals("100", v.value());
    }

    @Test
    void rejectsNonMatchingLines() {
        assertNull(NutParser.parseVarLine("UPS cyberpower \"Desk UPS\""));
        assertNull(NutParser.parseUpsLine("VAR cp battery.charge \"100\""));
        assertNull(NutParser.parseVarLine("BEGIN LIST VAR cyberpower"));
    }

    @Test
    void recognizesErrors() {
        assertTrue(NutParser.isErr("ERR UNKNOWN-UPS"));
        assertEquals("UNKNOWN-UPS", NutParser.errCode("ERR UNKNOWN-UPS"));
        assertEquals("DATA-STALE", NutParser.errCode("ERR DATA-STALE extra"));
        assertFalse(NutParser.isErr("VAR cp battery.charge \"100\""));
        assertEquals("UNKNOWN", NutParser.errCode("ERR"));
    }

    @Test
    void quotedArgumentsRoundTripThroughTheTokenizer() {
        for (String value : List.of("plain", "with space", "q\"uote", "back\\slash", "")) {
            String quoted = NutParser.quoteIfNeeded(value);
            assertEquals(List.of("PASSWORD", value), NutParser.tokens("PASSWORD " + quoted), quoted);
        }
        assertEquals("plain", NutParser.quoteIfNeeded("plain"), "a plain word goes bare, as before");
    }
}
