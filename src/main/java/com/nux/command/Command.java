package com.nux.command;

/** One user-invokable action: a stable id, a human title, and what it does. */
public record Command(String id, String title, Runnable action) {}
