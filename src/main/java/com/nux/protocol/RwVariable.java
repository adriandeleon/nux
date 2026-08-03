package com.nux.protocol;

import java.util.List;

/**
 * A server-writable variable (NUT {@code LIST RW}) with the type information needed to
 * edit it safely: an enumeration's choices, a numeric range, or a string length cap.
 */
public record RwVariable(String name, String value, Kind kind, List<String> options, Double min, Double max, int maxLength) {

    public enum Kind {
        ENUM,
        RANGE,
        NUMBER,
        STRING
    }

    public static RwVariable string(String name, String value, int maxLength) {
        return new RwVariable(name, value, Kind.STRING, List.of(), null, null, maxLength);
    }

    /**
     * Classifies a {@code GET TYPE} reply's flag tokens. NUT may report several
     * ({@code "ENUM"}, {@code "STRING:8"}, {@code "NUMBER RANGE"}); the most specific
     * editable form wins, since that's what decides which control to show.
     */
    public static Kind kindOf(List<String> typeTokens) {
        boolean number = false;
        for (String token : typeTokens) {
            String t = token.toUpperCase(java.util.Locale.ROOT);
            if (t.equals("ENUM")) {
                return Kind.ENUM;
            }
            if (t.equals("RANGE")) {
                return Kind.RANGE;
            }
            if (t.equals("NUMBER")) {
                number = true;
            }
        }
        return number ? Kind.NUMBER : Kind.STRING;
    }

    /** The {@code STRING:<n>} length cap, or 0 when unspecified. */
    public static int maxLengthOf(List<String> typeTokens) {
        for (String token : typeTokens) {
            String t = token.toUpperCase(java.util.Locale.ROOT);
            if (t.startsWith("STRING:")) {
                try {
                    return Integer.parseInt(t.substring("STRING:".length()).trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** Whether {@code candidate} is acceptable for this variable (client-side pre-check). */
    public boolean accepts(String candidate) {
        if (candidate == null) {
            return false;
        }
        return switch (kind) {
            case ENUM -> options.contains(candidate);
            case RANGE, NUMBER -> {
                try {
                    double v = Double.parseDouble(candidate.trim());
                    yield (min == null || v >= min) && (max == null || v <= max);
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            case STRING -> maxLength <= 0 || candidate.length() <= maxLength;
        };
    }
}
