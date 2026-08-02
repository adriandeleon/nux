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
}
