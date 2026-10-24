package com.nux.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus.Severity;

class RuntimeHealthTest {

    private static UpsSnapshot snapshot(Map<String, String> vars) {
        return new UpsSnapshot("cp", vars, Instant.EPOCH);
    }

    @Test
    void aHealthyRuntimeOnMainsIsNotAWarning() {
        // The regression: 27 minutes filled 45% of a 60-minute arc and showed amber.
        assertEquals(Severity.OK, RuntimeHealth.of(snapshot(Map.of()), 27 * 60));
    }

    @Test
    void judgedAgainstTheUpsOwnLowThreshold() {
        UpsSnapshot s = snapshot(Map.of("battery.runtime.low", "600"));
        assertEquals(Severity.CRITICAL, RuntimeHealth.of(s, 600));
        assertEquals(Severity.WARNING, RuntimeHealth.of(s, 1100));
        assertEquals(Severity.OK, RuntimeHealth.of(s, 1300));
    }

    @Test
    void withoutAThresholdFiveMinutesIsLow() {
        UpsSnapshot s = snapshot(Map.of("battery.runtime.low", "junk"));
        assertEquals(Severity.CRITICAL, RuntimeHealth.of(s, 240));
        assertEquals(Severity.WARNING, RuntimeHealth.of(s, 500));
        assertEquals(Severity.OK, RuntimeHealth.of(s, 700));
    }
}
