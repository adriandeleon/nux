package com.nux.protocol;

import java.io.IOException;

/** An {@code ERR <code>} reply from upsd (e.g. UNKNOWN-UPS, DATA-STALE, ACCESS-DENIED). */
public class NutException extends IOException {
    private final String code;

    public NutException(String code) {
        super("upsd error: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * True for the codes that mean the credentials (or what they are allowed to do) are
     * wrong — the only failures where asking for them again can help.
     */
    public static boolean isAuthFailure(String code) {
        return switch (code) {
            case "ACCESS-DENIED", "INVALID-USERNAME", "INVALID-PASSWORD", "USERNAME-REQUIRED", "PASSWORD-REQUIRED" ->
                true;
            default -> false;
        };
    }
}
