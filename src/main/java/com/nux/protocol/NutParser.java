package com.nux.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure line-level parsing of the NUT network protocol.
 *
 * <p>upsd replies are whitespace-separated tokens where a token may be wrapped in double
 * quotes; inside quotes, {@code \"} and {@code \\} are escapes. Example:
 * {@code VAR cyberpower ups.model "Value 2200E \"rev B\""}.
 */
public final class NutParser {

    private NutParser() {}

    /** Splits a protocol line into tokens, honoring quotes and backslash escapes. */
    public static List<String> tokens(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        boolean started = false; // distinguishes an empty quoted token "" from no token
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                cur.append(c);
                escaped = false;
                continue;
            }
            if (inQuotes && c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                started = true;
                continue;
            }
            if (!inQuotes && (c == ' ' || c == '\t')) {
                if (started || cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    started = false;
                }
                continue;
            }
            cur.append(c);
            started = true;
        }
        if (started || cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** True when the line is an {@code ERR ...} reply. */
    public static boolean isErr(String line) {
        return line.startsWith("ERR ") || line.equals("ERR");
    }

    /** The error code of an {@code ERR ...} line ({@code UNKNOWN} when absent). */
    public static String errCode(String line) {
        List<String> t = tokens(line);
        return t.size() >= 2 ? t.get(1) : "UNKNOWN";
    }

    /** Parses a {@code UPS <name> "<description>"} line; null when the line isn't one. */
    public static UpsDevice parseUpsLine(String line) {
        List<String> t = tokens(line);
        if (t.size() < 2 || !t.get(0).equals("UPS")) {
            return null;
        }
        return new UpsDevice(t.get(1), t.size() >= 3 ? t.get(2) : "");
    }

    /** Parses a {@code VAR <ups> <name> "<value>"} line; null when the line isn't one. */
    public static UpsVar parseVarLine(String line) {
        List<String> t = tokens(line);
        if (t.size() < 4 || !t.get(0).equals("VAR")) {
            return null;
        }
        return new UpsVar(t.get(1), t.get(2), t.get(3));
    }

    /**
     * A request argument as upsd's tokenizer will read it back: bare when it is a plain
     * word, else double-quoted with {@code \\} and {@code \"} escaped. Sent bare, a
     * password containing a space split into two arguments and one containing a quote
     * or backslash was rewritten by upsd's parser.
     */
    public static String quoteIfNeeded(String value) {
        if (!value.isEmpty() && value.chars().allMatch(c -> c > ' ' && c != '"' && c != '\\' && c != 127)) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
