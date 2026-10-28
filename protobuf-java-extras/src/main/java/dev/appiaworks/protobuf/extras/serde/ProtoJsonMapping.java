package dev.appiaworks.protobuf.extras.serde;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The JSON dialect for types the proto3 mapping leaves as objects or fixes to one text form. In
 * the gateway it is the process-wide view of {@code shiguchi.json.*}.
 *
 * <p>Process-wide because {@link ProtoWkt} is static and called from the gateway's generated
 * serializers, and because the answer to "is this type a scalar?" is baked into its compiled
 * serializers when they are built. The gateway installs the configured mapping before any of
 * them exist; changing it afterwards does not rebuild them.
 *
 * @param date {@code google.type.Date} as ISO text
 * @param timeOfDay {@code google.type.TimeOfDay} as ISO text
 * @param dateTime {@code google.type.DateTime} as ISO text
 * @param decimal {@code google.type.Decimal} as its decimal string
 * @param money {@code google.type.Money} as {@code {currency, amount}}
 * @param durationOutput the text a Duration is written as
 * @param acceptIso8601Duration whether ISO-8601 Duration text is accepted inbound
 * @param acceptEpochMillis whether a JSON number is accepted for a Timestamp inbound
 * @param int64AsNumber whether 64-bit integers are written as JSON numbers instead of strings
 */
public record ProtoJsonMapping(
        boolean date,
        boolean timeOfDay,
        boolean dateTime,
        boolean decimal,
        boolean money,
        DurationFormat durationOutput,
        boolean acceptIso8601Duration,
        boolean acceptEpochMillis,
        boolean int64AsNumber) {

    public static final String DATE = "google.type.Date";
    public static final String TIME_OF_DAY = "google.type.TimeOfDay";
    public static final String DATE_TIME = "google.type.DateTime";
    public static final String DECIMAL = "google.type.Decimal";
    public static final String MONEY = "google.type.Money";

    /** The text a {@code google.protobuf.Duration} is written as. */
    public enum DurationFormat {
        /** Seconds with an {@code s} suffix, as proto3 JSON writes it: {@code "5400s"}. */
        PROTO,
        /** ISO-8601: {@code "PT1H30M"}. */
        ISO_8601
    }

    /**
     * The gateway's defaults: Date, TimeOfDay, DateTime and Decimal as text, Money as an object,
     * Durations in the proto form with ISO-8601 accepted on input, 64-bit integers as strings.
     */
    public static final ProtoJsonMapping DEFAULTS =
            new ProtoJsonMapping(true, true, true, true, false, DurationFormat.PROTO, true, false, false);

    /** Proto3 JSON as {@code JsonFormat} writes it: every {@code google.type} message stays an object. */
    public static final ProtoJsonMapping PROTO3 =
            new ProtoJsonMapping(false, false, false, false, false, DurationFormat.PROTO, false, false, false);

    /** The process-wide dialect: what is in force wherever no {@link JsonDialect#call} is. */
    private static volatile JsonDialect installed = JsonDialect.of(DEFAULTS, List.of());

    /** The dialect in force on this thread: a scoped one when a call is inside it, else the installed one. */
    private static JsonDialect active() {
        JsonDialect scoped = JsonDialect.scoped();
        return scoped != null ? scoped : installed;
    }

    /** Descriptor → whether it transitively reaches a mapped type; cleared when the mapping changes. */
    private static final Map<Descriptor, Boolean> REACHES_MAPPED = new ConcurrentHashMap<>();

    /** Descriptor → whether it transitively reaches a 64-bit integer field. */
    private static final Map<Descriptor, Boolean> REACHES_INT64 = new ConcurrentHashMap<>();

    public ProtoJsonMapping {
        if (durationOutput == null) durationOutput = DurationFormat.PROTO;
    }

    /** A mapping that writes 64-bit integers as strings, the proto3 default. */
    public ProtoJsonMapping(
            boolean date,
            boolean timeOfDay,
            boolean dateTime,
            boolean decimal,
            boolean money,
            DurationFormat durationOutput,
            boolean acceptIso8601Duration,
            boolean acceptEpochMillis) {
        this(
                date,
                timeOfDay,
                dateTime,
                decimal,
                money,
                durationOutput,
                acceptIso8601Duration,
                acceptEpochMillis,
                false);
    }

    /** The mapping in force. */
    public static ProtoJsonMapping current() {
        return active().mapping();
    }

    /**
     * Installs a mapping. Call it before descriptors are compiled (the gateway does at startup);
     * tests that change it must restore {@link #DEFAULTS}.
     */
    public static void install(ProtoJsonMapping mapping) {
        install(mapping, List.of());
    }

    /**
     * Installs a mapping together with the application's own scalar codecs, replacing any installed
     * before. A codec for a {@code google.type} message the mapping also switches on takes its
     * place.
     *
     * @throws IllegalArgumentException when two codecs name the same message, or one names a
     *     {@code google.protobuf} well-known type, whose JSON form proto3 fixes
     */
    public static void install(ProtoJsonMapping mapping, Collection<? extends ProtoScalarCodec> scalarCodecs) {
        install(mapping, scalarCodecs, false);
    }

    /**
     * {@link #install(ProtoJsonMapping, Collection)}, reading input strictly or not (see
     * {@link JsonDialect#strictInput()}). Like the mapping, strictness is baked into compiled
     * parsers, so install it before they are built.
     */
    public static void install(
            ProtoJsonMapping mapping, Collection<? extends ProtoScalarCodec> scalarCodecs, boolean strictInput) {
        installed = JsonDialect.of(mapping, scalarCodecs, strictInput);
        REACHES_MAPPED.clear();
    }

    /** Whether the dialect in force reads input strictly: see {@link JsonDialect#strictInput()}. */
    public static boolean strictInput() {
        return active().strictInput();
    }

    /** The codec that writes this message as one JSON value under the mapping in force, or null. */
    public static ProtoScalarCodec codecFor(Descriptor d) {
        return active().codecs().get(d.getFullName());
    }

    /**
     * {@link #codecFor} when the codec is the application's own, null when the message is unmapped
     * or one of the {@code google.type} messages this mapping switches on. For the schema builders,
     * which describe those by name and anything else by {@link ProtoScalarCodec#schema()}.
     */
    public static ProtoScalarCodec customCodecFor(Descriptor d) {
        ProtoScalarCodec codec = active().codecs().get(d.getFullName());
        return codec instanceof BuiltInScalarCodecs.BuiltIn ? null : codec;
    }

    /**
     * The application's own codecs under the mapping in force, without the {@code google.type}
     * ones the mapping switches on. For the GraphQL schema, which declares a scalar per
     * {@link dev.appiaworks.protobuf.extras.ScalarSchema#graphQlName()}.
     */
    public static List<ProtoScalarCodec> customCodecs() {
        return active().codecs().values().stream()
                .filter(c -> !(c instanceof BuiltInScalarCodecs.BuiltIn))
                .sorted(Comparator.comparing(ProtoScalarCodec::fullName))
                .toList();
    }

    /**
     * True when a serializer that only knows proto3 JSON ({@code JsonFormat})
     * would write this message differently from the mapping in force:
     * it reaches a mapped {@code google.type} message, or a 64-bit integer while those are
     * written as numbers.
     */
    public static boolean needsGatewaySerde(Descriptor d) {
        return reachesMapped(d) || (current().int64AsNumber() && reachesInt64(d));
    }

    private static boolean reachesInt64(Descriptor d) {
        Boolean cached = REACHES_INT64.get(d);
        if (cached != null) return cached;
        boolean result = reachesInt64(d, new HashSet<>());
        REACHES_INT64.put(d, result);
        return result;
    }

    private static boolean reachesInt64(Descriptor d, Set<Descriptor> visited) {
        if (!visited.add(d)) return false;
        String name = d.getFullName();
        if ("google.protobuf.Int64Value".equals(name) || "google.protobuf.UInt64Value".equals(name)) return true;
        for (FieldDescriptor fd : d.getFields()) {
            switch (fd.getType()) {
                case INT64, SINT64, SFIXED64, UINT64, FIXED64 -> {
                    return true;
                }
                case MESSAGE -> {
                    if (reachesInt64(fd.getMessageType(), visited)) return true;
                }
                default -> {}
            }
        }
        return false;
    }

    /** True when the message, by full name, is a {@code google.type} message this mapping renders specially. */
    public boolean maps(String fullName) {
        return switch (fullName) {
            case DATE -> date;
            case TIME_OF_DAY -> timeOfDay;
            case DATE_TIME -> dateTime;
            case DECIMAL -> decimal;
            case MONEY -> money;
            default -> false;
        };
    }

    /** True when a codec writes this message as one JSON value: {@link #maps(String)} on the mapping in force, or a codec of the application's. */
    public static boolean isMapped(Descriptor d) {
        return active().codecs().containsKey(d.getFullName());
    }

    /**
     * True when a field of the message — at any depth — is a mapped message. Serializers that cannot be taught the mapping ({@code JsonFormat})
     * must not serve such a message.
     */
    public static boolean reachesMapped(Descriptor d) {
        // The cache answers for the installed dialect only.
        if (JsonDialect.scoped() != null) return reachesMapped(d, new HashSet<>());
        Boolean cached = REACHES_MAPPED.get(d);
        if (cached != null) return cached;
        boolean result = reachesMapped(d, new HashSet<>());
        REACHES_MAPPED.put(d, result);
        return result;
    }

    private static boolean reachesMapped(Descriptor d, Set<Descriptor> visited) {
        if (!visited.add(d)) return false;
        if (isMapped(d)) return true;
        for (FieldDescriptor fd : d.getFields()) {
            if (fd.getType() == FieldDescriptor.Type.MESSAGE && reachesMapped(fd.getMessageType(), visited)) {
                return true;
            }
        }
        return false;
    }
}
