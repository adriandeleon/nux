package com.nux.update;

import java.util.Locale;
import java.util.Map;

/**
 * Pure core of the update check: parse a GitHub {@code /releases/latest} payload, decide
 * whether it is newer than the running version, and throttle how often the background
 * check runs. Every decision is testable without a network; the fetching lives in
 * {@link UpdateService}.
 */
public final class UpdateCheck {

    /** Background checks run at most once a day. */
    public static final long DEFAULT_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private UpdateCheck() {}

    /**
     * Parses a releases-API payload, or returns null when it isn't a usable release —
     * a draft, a pre-release, or anything without a tag. We never nudge a user toward
     * a release the author hasn't actually published.
     */
    public static ReleaseInfo parseLatest(String json) {
        Map<String, String> fields = TopLevelJson.scalars(json);
        if (fields.isEmpty()) {
            return null;
        }
        if (TopLevelJson.bool(fields, "draft") || TopLevelJson.bool(fields, "prerelease")) {
            return null;
        }
        String tag = TopLevelJson.string(fields, "tag_name");
        if (tag.isEmpty()) {
            return null;
        }
        return new ReleaseInfo(
                normalizeVersion(tag), TopLevelJson.string(fields, "html_url"), TopLevelJson.string(fields, "name"));
    }

    /** Strips a leading {@code v}/{@code V} from a tag ({@code v0.4.0} → {@code 0.4.0}). */
    public static String normalizeVersion(String tag) {
        String t = tag == null ? "" : tag.strip();
        if (!t.isEmpty() && (t.charAt(0) == 'v' || t.charAt(0) == 'V')) {
            return t.substring(1);
        }
        return t;
    }

    /**
     * Compares dotted versions numerically per component, so {@code 0.10.0 > 0.9.0} — the
     * comparison a lexicographic one gets backwards. A {@code -SNAPSHOT}/{@code -rc1}
     * suffix sorts <em>below</em> the same release, so a dev build is offered the release
     * it is a pre-version of.
     */
    public static int compareVersions(String a, String b) {
        String an = a == null ? "" : a.strip();
        String bn = b == null ? "" : b.strip();
        String[] as = core(an).split("\\.");
        String[] bs = core(bn).split("\\.");
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            String x = i < as.length && !as[i].isBlank() ? as[i] : "0";
            String y = i < bs.length && !bs[i].isBlank() ? bs[i] : "0";
            Integer xi = tryInt(x);
            Integer yi = tryInt(y);
            int cmp = xi != null && yi != null
                    ? Integer.compare(xi, yi)
                    : x.toLowerCase(Locale.ROOT).compareTo(y.toLowerCase(Locale.ROOT));
            if (cmp != 0) {
                return cmp < 0 ? -1 : 1;
            }
        }
        String ap = preRelease(an);
        String bp = preRelease(bn);
        if (ap.isEmpty() && bp.isEmpty()) {
            return 0;
        }
        if (ap.isEmpty()) {
            return 1; // a release outranks its own pre-release
        }
        if (bp.isEmpty()) {
            return -1;
        }
        int cmp = ap.toLowerCase(Locale.ROOT).compareTo(bp.toLowerCase(Locale.ROOT));
        return Integer.signum(cmp);
    }

    /**
     * Whether {@code latest} is strictly newer than {@code current}. An unknown current
     * version (the {@code 0.0.0} fallback of an unfiltered build) counts as older, so a
     * dev run still surfaces the real latest release.
     */
    public static boolean isNewer(String current, String latest) {
        if (latest == null || latest.isBlank()) {
            return false;
        }
        return compareVersions(latest.strip(), current == null ? "" : current.strip()) > 0;
    }

    /**
     * Whether a background check is due: never checked, or the interval has elapsed. A
     * timestamp in the future (the clock moved back) also counts as due, rather than
     * locking the check out until the clock catches up.
     */
    public static boolean isDue(long lastCheckMs, long nowMs, long intervalMs) {
        if (lastCheckMs <= 0) {
            return true;
        }
        long elapsed = nowMs - lastCheckMs;
        return elapsed < 0 || elapsed >= intervalMs;
    }

    private static String core(String v) {
        int dash = v.indexOf('-');
        return dash < 0 ? v : v.substring(0, dash);
    }

    private static String preRelease(String v) {
        int dash = v.indexOf('-');
        return dash < 0 || dash + 1 >= v.length() ? "" : v.substring(dash + 1);
    }

    private static Integer tryInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
