package com.nux.protocol;

/** How to secure the upsd connection. */
public enum TlsMode {
    /** Plain TCP (upsd's default; fine on a trusted LAN). */
    DISABLED,
    /** STARTTLS with normal certificate + hostname verification. */
    REQUIRED,
    /**
     * STARTTLS accepting any certificate — encrypts the wire but doesn't authenticate
     * the server. For the common self-signed upsd cert; enabled only by hand-editing
     * the config file, never from the UI.
     */
    REQUIRED_TRUST_ALL
}
