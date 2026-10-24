package com.nux.service;

import static com.nux.i18n.Messages.tr;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

import com.nux.protocol.NutException;

/**
 * Turns a connection failure into a sentence a user can act on. The dashboard used to
 * show raw exception text ("Connection refused", "upsd error: UNKNOWN-UPS"), which says
 * what broke but not where to look.
 */
public final class FailureText {

    private FailureText() {}

    public static String describe(Throwable e, String host, int port) {
        String where = host + ":" + port;
        if (e instanceof NutException nut) {
            return switch (nut.code()) {
                case "UNKNOWN-UPS" -> tr("error.conn.unknownUps");
                case "DATA-STALE" -> tr("error.conn.dataStale");
                case "DRIVER-NOT-CONNECTED" -> tr("error.conn.driverDown");
                case "ACCESS-DENIED" -> tr("error.conn.accessDenied");
                default -> nut.code();
            };
        }
        if (e instanceof UnknownHostException) {
            return tr("error.conn.unknownHost", host);
        }
        if (e instanceof ConnectException || e instanceof NoRouteToHostException) {
            return tr("error.conn.refused", where);
        }
        if (e instanceof SocketTimeoutException) {
            return tr("error.conn.timeout", where);
        }
        if (e instanceof SSLException) {
            return tr("error.conn.tls", String.valueOf(e.getMessage()));
        }
        if (e instanceof EOFException) {
            return tr("error.conn.closed");
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
