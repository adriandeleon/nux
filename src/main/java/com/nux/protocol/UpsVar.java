package com.nux.protocol;

/** One variable of a UPS, e.g. {@code battery.charge = "100"}. */
public record UpsVar(String ups, String name, String value) {}
