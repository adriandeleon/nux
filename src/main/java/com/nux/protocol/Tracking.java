package com.nux.protocol;

/**
 * NUT 2.8 command tracking. Without it an {@code OK} only means upsd accepted the
 * request; with it, upsd returns an id whose result can be polled until the driver
 * reports what actually happened.
 */
public final class Tracking {

    public enum Status {
        /** The driver hasn't reported yet. */
        PENDING,
        /** The UPS carried the command out. */
        SUCCESS,
        /** The driver reported a failure; {@link Result#detail()} carries the NUT code. */
        FAILED,
        /** The id is not (or no longer) known to the server. */
        UNKNOWN
    }

    public record Result(Status status, String detail) {
        public boolean done() {
            return status != Status.PENDING;
        }
    }

    private Tracking() {}

    /** The id in an {@code OK TRACKING <id>} reply, or "" for a plain {@code OK}. */
    public static String idOf(String okReply) {
        if (okReply == null) {
            return "";
        }
        String prefix = "OK TRACKING ";
        return okReply.startsWith(prefix) ? okReply.substring(prefix.length()).trim() : "";
    }

    /**
     * Parses a {@code GET TRACKING <id>} reply. Servers answer with a bare status word,
     * but the status is read as the last token so a {@code TRACKING <id> <status>}
     * shape parses identically — the two forms are not worth a version check.
     */
    public static Result parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return new Result(Status.UNKNOWN, "");
        }
        String trimmed = reply.trim();
        if (NutParser.isErr(trimmed)) {
            String code = NutParser.errCode(trimmed);
            // Only a driver-reported error means the command failed. "No such id" and
            // "tracking isn't configured here" both mean we cannot tell — reporting
            // either as a failure would blame the command for the server's bookkeeping.
            return switch (code) {
                case "UNKNOWN", "FEATURE-NOT-CONFIGURED", "FEATURE-NOT-SUPPORTED" -> new Result(Status.UNKNOWN, code);
                default -> new Result(Status.FAILED, code);
            };
        }
        String[] tokens = trimmed.split("\\s+");
        String last = tokens[tokens.length - 1].toUpperCase(java.util.Locale.ROOT);
        return switch (last) {
            case "SUCCESS" -> new Result(Status.SUCCESS, "");
            case "PENDING" -> new Result(Status.PENDING, "");
            default -> new Result(Status.UNKNOWN, trimmed);
        };
    }
}
