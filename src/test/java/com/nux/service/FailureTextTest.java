package com.nux.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;

import com.nux.protocol.NutException;

class FailureTextTest {

    private static String describe(Throwable e) {
        return FailureText.describe(e, "nas.lan", 3493);
    }

    @Test
    void socketFailuresSayWhereToLook() {
        assertTrue(describe(new ConnectException("Connection refused")).contains("nas.lan:3493"));
        assertTrue(describe(new ConnectException("Connection refused")).contains("LISTEN"));
        assertTrue(describe(new UnknownHostException("nas.lan")).contains("Unknown host nas.lan"));
        assertTrue(describe(new SocketTimeoutException()).contains("did not answer"));
        assertTrue(describe(new EOFException()).contains("closed the connection"));
        assertTrue(describe(new SSLHandshakeException("PKIX path building failed")).contains("PKIX"));
    }

    @Test
    void upsdRefusalsAreExplained() {
        assertTrue(describe(new NutException("UNKNOWN-UPS")).contains("ups.conf"));
        assertTrue(describe(new NutException("DATA-STALE")).contains("stale"));
        assertTrue(describe(new NutException("DRIVER-NOT-CONNECTED")).contains("driver"));
        assertEquals("SOMETHING-NEW", describe(new NutException("SOMETHING-NEW")), "an unknown code shows as-is");
    }

    @Test
    void anythingElseKeepsItsOwnMessage() {
        assertEquals("port out of range:99999", describe(new IllegalArgumentException("port out of range:99999")));
        assertEquals("IOException", describe(new IOException()));
    }
}
