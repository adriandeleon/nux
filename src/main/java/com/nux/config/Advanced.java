package com.nux.config;

/**
 * The settings added by the analytics/automation features, grouped so {@link Config}'s
 * component list stays readable. Persisted under an {@code adv.} prefix.
 */
public record Advanced(
        int historyDays,
        double nominalWatts,
        double tariffPerKwh,
        String currency,
        boolean selfTestEnabled,
        int selfTestIntervalDays,
        String selfTestTime,
        long selfTestLastRun,
        String selfTestCommand) {

    public static final String DEFAULT_TEST_COMMAND = "test.battery.start.quick";

    public static Advanced defaults() {
        return new Advanced(30, 0, 0, "$", false, 30, "03:00", 0, DEFAULT_TEST_COMMAND);
    }

    public Advanced withSelfTestLastRun(long epochSecond) {
        return new Advanced(
                historyDays, nominalWatts, tariffPerKwh, currency, selfTestEnabled, selfTestIntervalDays,
                selfTestTime, epochSecond, selfTestCommand);
    }
}
