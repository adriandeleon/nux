package com.nux.protocol;

/** One UPS as announced by {@code LIST UPS}: a name and a free-text description. */
public record UpsDevice(String name, String description) {}
