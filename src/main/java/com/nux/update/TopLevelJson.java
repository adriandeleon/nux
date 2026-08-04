package com.nux.update;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts the <em>top-level</em> scalar fields of a JSON object. Nux has no JSON library
 * and this is the only place it would be needed, so rather than take a dependency for four
 * fields we read them with a small depth-aware scanner.
 *
 * <p>Depth-awareness is the whole point, not a nicety: a GitHub release payload repeats the
 * very keys we want inside nested objects — {@code author.html_url}, {@code assets[].name} —
 * so anything that simply searched the text would happily return an asset's name as the
 * release name. Only keys at depth 1 are recorded.
 *
 * <p>Deliberately limited: scalars only (strings, numbers, booleans, null). Nested objects
 * and arrays are skipped wholesale, which is exactly what the caller wants.
 */
final class TopLevelJson {

    private TopLevelJson() {}

    /** Top-level key → raw scalar value (strings unescaped). Returns empty for malformed input. */
    static Map<String, String> scalars(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        int i = 0;
        int n = json.length();
        // Find the opening brace of the root object.
        while (i < n && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= n || json.charAt(i) != '{') {
            return out;
        }
        i++;
        while (i < n) {
            i = skipWhitespace(json, i);
            if (i >= n || json.charAt(i) == '}') {
                return out;
            }
            if (json.charAt(i) == ',') {
                i++;
                continue;
            }
            if (json.charAt(i) != '"') {
                return out; // not a key where one belongs: give up rather than guess
            }
            StringBuilder key = new StringBuilder();
            i = readString(json, i, key);
            if (i < 0) {
                return out;
            }
            i = skipWhitespace(json, i);
            if (i >= n || json.charAt(i) != ':') {
                return out;
            }
            i = skipWhitespace(json, i + 1);
            if (i >= n) {
                return out;
            }
            char c = json.charAt(i);
            if (c == '"') {
                StringBuilder value = new StringBuilder();
                i = readString(json, i, value);
                if (i < 0) {
                    return out;
                }
                out.put(key.toString(), value.toString());
            } else if (c == '{' || c == '[') {
                i = skipContainer(json, i); // a nested object/array: skip it entirely
                if (i < 0) {
                    return out;
                }
            } else {
                int start = i;
                while (i < n && ",}]".indexOf(json.charAt(i)) < 0 && !Character.isWhitespace(json.charAt(i))) {
                    i++;
                }
                out.put(key.toString(), json.substring(start, i).trim());
            }
        }
        return out;
    }

    static String string(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null ? "" : value.trim();
    }

    static boolean bool(Map<String, String> fields, String key) {
        return "true".equalsIgnoreCase(string(fields, key));
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Reads a quoted string starting at {@code i}; appends the unescaped value. Returns the index after it. */
    private static int readString(String s, int i, StringBuilder out) {
        i++; // opening quote
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    return -1;
                }
                char esc = s.charAt(i + 1);
                switch (esc) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (i + 5 >= n) {
                            return -1;
                        }
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        } catch (NumberFormatException e) {
                            return -1;
                        }
                        i += 4;
                    }
                    default -> out.append(esc); // covers \" \\ \/
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            out.append(c);
            i++;
        }
        return -1;
    }

    /** Skips a balanced object/array, honouring strings so a brace inside one can't unbalance it. */
    private static int skipContainer(String s, int i) {
        int depth = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                i = readString(s, i, new StringBuilder());
                if (i < 0) {
                    return -1;
                }
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return -1;
    }
}
