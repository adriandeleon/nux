package com.nux.update;

/**
 * A published release from the GitHub releases API: the normalised {@code version} (the tag
 * with any leading {@code v} stripped), the human-facing release page {@code url}, and the
 * release {@code name} (may be blank). Pure data, toolkit-free.
 */
public record ReleaseInfo(String version, String url, String name) {}
