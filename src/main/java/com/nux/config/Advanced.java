package com.nux.config;

import java.util.Map;
import java.util.TreeMap;

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
        String dismissedUpdateVersion,
        String theme,
        Map<String, NotifyRule> notifyRules,
        boolean watchProfiles) {

    public static final String DEFAULT_TEST_COMMAND = "test.battery.start.quick";

    /** "system" follows the desktop's light/dark preference; "dark" and "light" pin one. */
    public static final String THEME_SYSTEM = "system";

    public Advanced {
        theme = theme == null || theme.isBlank() ? THEME_SYSTEM : theme;
        notifyRules = notifyRules == null ? Map.of() : Map.copyOf(notifyRules);
    }

    public static Advanced defaults() {
        return new Advanced(30, 0, 0, "$", false, 30, "03:00", 0, 0, DEFAULT_TEST_COMMAND, true, "", true, 0, "",
                THEME_SYSTEM, Map.of(), false);
    }

    public Advanced withUpdateState(long lastCheckMs, String dismissedVersion) {
        return new Advanced(
                historyDays, nominalWatts, tariffPerKwh, currency, selfTestEnabled, selfTestIntervalDays,
                selfTestTime, selfTestLastRun, selfTestArmed, selfTestCommand, singleInstance, uiLanguage,
                updateCheck, lastCheckMs, dismissedVersion, theme, notifyRules, watchProfiles);
    }

    public Advanced withSelfTestLastRun(long epochSecond) {
        return new Advanced(
                historyDays, nominalWatts, tariffPerKwh, currency, selfTestEnabled, selfTestIntervalDays,
                selfTestTime, epochSecond, selfTestArmed, selfTestCommand, singleInstance, uiLanguage,
                updateCheck, lastUpdateCheckMs, dismissedUpdateVersion, theme, notifyRules, watchProfiles);
    }

    public Advanced withSelfTestArmed(long epochSecond) {
        return new Advanced(
                historyDays, nominalWatts, tariffPerKwh, currency, selfTestEnabled, selfTestIntervalDays,
                selfTestTime, selfTestLastRun, epochSecond, selfTestCommand, singleInstance, uiLanguage,
                updateCheck, lastUpdateCheckMs, dismissedUpdateVersion, theme, notifyRules, watchProfiles);
    }

    /** The notification rule for one UPS, keyed {@code host:port/ups}; ALL when none is set. */
    public NotifyRule notifyRule(String upsKey) {
        return notifyRules.getOrDefault(upsKey, NotifyRule.ALL);
    }

    /** Sets one UPS's rule; ALL removes the entry, so the file only lists exceptions. */
    public Advanced withNotifyRule(String upsKey, NotifyRule rule) {
        Map<String, NotifyRule> next = new TreeMap<>(notifyRules);
        if (rule == NotifyRule.ALL) {
            next.remove(upsKey);
        } else {
            next.put(upsKey, rule);
        }
        return new Advanced(
                historyDays, nominalWatts, tariffPerKwh, currency, selfTestEnabled, selfTestIntervalDays,
                selfTestTime, selfTestLastRun, selfTestArmed, selfTestCommand, singleInstance, uiLanguage,
                updateCheck, lastUpdateCheckMs, dismissedUpdateVersion, theme, next, watchProfiles);
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
