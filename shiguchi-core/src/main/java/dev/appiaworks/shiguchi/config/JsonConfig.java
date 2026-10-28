package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * How proto values that have no JSON primitive of their own are written and read on the REST
 * and GraphQL surfaces ({@code shiguchi.json.*}).
 *
 * <p>{@code google.protobuf.Timestamp} is always an RFC 3339 string, as the proto3 JSON mapping
 * says. The common {@code google.type} messages are not well-known types, so the mapping renders
 * them as plain objects ({@code {"year":2026,"month":9,"day":22}}); {@link #scalarTypes} turns
 * them into ISO strings instead. That departs from what {@code JsonFormat} produces, which is the
 * point: a date should look like a date to a REST client. Inbound, the object form is still
 * accepted, so switching a type on never breaks a client that sends the old shape.
 *
 * <p>The settings are process-wide: one gateway speaks one JSON dialect on every service. The
 * exception is {@link #print}, the response layout, which a service can override
 * ({@code services[].json-print}) so a legacy backend's clients keep the field names they know.
 */
@Data
@NoArgsConstructor
public class JsonConfig {

    /** Which {@code google.type} messages travel as ISO strings instead of objects. */
    private ScalarTypes scalarTypes = new ScalarTypes();

    /** {@code google.protobuf.Duration} text forms. */
    private DurationJson duration = new DurationJson();

    /** {@code google.protobuf.Timestamp} inbound leniency. */
    private TimestampJson timestamp = new TimestampJson();

    /**
     * How 64-bit integers ({@code int64}, {@code uint64}, their fixed/signed variants and the
     * 64-bit wrappers) are written: {@code STRING}, the proto3 form ({@code "42"}), or
     * {@code NUMBER} ({@code 42}). A JSON number above 2^53 loses precision in JavaScript and
     * other clients that parse numbers as doubles, so switch this on only when the values stay
     * below that (ids from a database sequence, counts) or every client parses 64-bit integers.
     * Input accepts both forms either way.
     */
    private Int64Format int64As = Int64Format.STRING;

    /**
     * Refuse a request value that does not fit its field instead of coercing it: {@code "abc"},
     * {@code 1.5} or {@code 3000000000} for an {@code int32}, a number for a {@code string}, an
     * object for a list. The caller gets HTTP 400 ({@code INVALID_ARGUMENT}) naming the field, its
     * type and the value, on REST bodies and GraphQL arguments alike. Off by default for
     * compatibility: the compiled parser has always turned such values into the field's default
     * ({@code 0}, {@code false}), so switching this on can start rejecting requests that were
     * accepted. Unknown fields and unknown enum names are still ignored either way.
     */
    private boolean strictInput = false;

    /**
     * Layout of REST response bodies — field naming, enum form, whether defaults are printed.
     * The defaults are the gateway's historical output. A service overrides any of them with
     * {@code services[].json-print}.
     */
    private PrintOptions print = new PrintOptions();

    /** Response layout options. */
    @Data
    @NoArgsConstructor
    public static class PrintOptions {
        /** Field names as declared in the proto ({@code display_name}) instead of {@code displayName}. */
        private boolean preserveProtoFieldNames = false;

        /** Enum values as numbers instead of names. */
        private boolean enumsAsInts = false;

        /**
         * Leave out fields without presence that hold their default ({@code 0}, {@code ""},
         * {@code false}, empty lists and maps). Off by default: every field is printed, so a
         * client never has to tell a missing field from a zero one.
         */
        private boolean omitDefaultValues = false;

        /**
         * Indent JSON response bodies — for humans reading curl
         * output. Applied to whole-body responses (unary, {@code response_body}, merged pages);
         * NDJSON and SSE stay one message per line. Costs a re-write of every body, so leave it off
         * for machine clients.
         */
        private boolean prettyPrint = false;
    }

    /** JSON form of a 64-bit integer. */
    public enum Int64Format {
        /** The proto3 JSON form: a string, {@code "42"}. Exact in every client. */
        STRING,
        /** A JSON number, {@code 42}. Exact only up to 2^53 in clients that parse doubles. */
        NUMBER
    }

    @Data
    @NoArgsConstructor
    public static class ScalarTypes {
        /**
         * {@code google.type.Date} as {@code "2026-09-22"}. Partial dates keep their ISO forms:
         * {@code "2026-09"} (no day), {@code "2026"} (year only), {@code "--09-22"} (no year).
         */
        private boolean date = true;

        /** {@code google.type.TimeOfDay} as {@code "14:03:11.123"}. */
        private boolean timeOfDay = true;

        /**
         * {@code google.type.DateTime} as {@code "2026-09-22T14:03:11-04:00"}; with a time zone,
         * {@code "2026-09-22T14:03:11-04:00[America/New_York]"}; with neither, local
         * {@code "2026-09-22T14:03:11"}.
         */
        private boolean dateTime = true;

        /** {@code google.type.Decimal} as {@code "12.34"} — a string, so no precision is lost. */
        private boolean decimal = true;

        /**
         * {@code google.type.Money} as {@code {"currency":"USD","amount":"12.34"}} instead of
         * {@code currencyCode}/{@code units}/{@code nanos}. Off by default: money has no standard
         * scalar form, and the flattened object is still an object.
         */
        private boolean money = false;
    }

    @Data
    @NoArgsConstructor
    public static class DurationJson {
        /** Text written for a Duration: {@code PROTO} ({@code "5400s"}) or {@code ISO_8601} ({@code "PT1H30M"}). */
        private DurationFormat output = DurationFormat.PROTO;

        /** Also accept ISO-8601 text ({@code "PT1H30M"}) inbound, next to the proto form. */
        private boolean acceptIso8601 = true;
    }

    /** Text form of a {@code google.protobuf.Duration}. */
    public enum DurationFormat {
        /** The proto3 JSON form: seconds with an {@code s} suffix, {@code "5400s"}. */
        PROTO,
        /** ISO-8601, as {@code java.time.Duration} prints it: {@code "PT1H30M"}. */
        ISO_8601
    }

    @Data
    @NoArgsConstructor
    public static class TimestampJson {
        /** Also accept a JSON number of epoch milliseconds inbound, next to RFC 3339 text. */
        private boolean acceptEpochMillis = false;
    }
}
