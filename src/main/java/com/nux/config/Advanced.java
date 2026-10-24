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
        long selfTestArmed,
        String selfTestCommand,
        boolean singleInstance,
        String uiLanguage,
        boolean updateCheck,
        long lastUpdateCheckMs,
        String dismissedUpdateVersion) {

    public static final String DEFAULT_TEST_COMMAND = "test.battery.start.quick";

    public static Advanced defaults() {
        return new Advanced(30, 0, 0, "$", false, 30, "03:00", 0, 0, DEFAULT_TEST_COMMAND, true, "", true, 0, "");
    }

    public Advanced withUpdateState(long lastCheckMs, String dismissedVersion) {
        return new Advanced(
                historyDays, nominalWatts, tariffPerKwh, currency, selfTestEnabled, selfTestIntervalDays,
                selfTestTime, selfTestLastRun, selfTestArmed, selfTestCommand, singleInstance, uiLanguage,
                updateCheck, lastCheckMs, dismissedVersion);
    }

    public Advanced withSelfTestLastRun(long epochSecond) {
        return new Advanced(
                historyDays, nominalWatts, tariffPerKwh, currency, selfTestEnabled, selfTestIntervalDays,
                selfTestTime, epochSecond, selfTestArmed, selfTestCommand, singleInstance, uiLanguage,
                updateCheck, lastUpdateCheckMs, dismissedUpdateVersion);
    }

    public Advanced withSelfTestArmed(long epochSecond) {
        return new Advanced(
                historyDays, nominalWatts, tariffPerKwh, currency, selfTestEnabled, selfTestIntervalDays,
                selfTestTime, selfTestLastRun, epochSecond, selfTestCommand, singleInstance, uiLanguage,
                updateCheck, lastUpdateCheckMs, dismissedUpdateVersion);
    }

    /**
     * Arms the self-test schedule at {@code nowEpoch} when it has just been switched on,
     * its time of day changed, or it is enabled but was never armed (a config written
     * before arming existed). The first run then lands on the next occurrence of the
     * configured time. Anything else keeps the existing anchor.
     */
    public Advanced armSelfTest(Advanced previous, long nowEpoch) {
        boolean arm = selfTestEnabled
                && (!previous.selfTestEnabled()
                        || !selfTestTime.equals(previous.selfTestTime())
                        || selfTestArmed <= 0);
        return arm ? withSelfTestArmed(nowEpoch) : this;
    }
}
