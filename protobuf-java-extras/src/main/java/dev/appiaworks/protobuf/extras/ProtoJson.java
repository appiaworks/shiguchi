package dev.appiaworks.protobuf.extras;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.DynamicSerializerCompiler;
import dev.appiaworks.protobuf.extras.serde.JsonDialect;
import dev.appiaworks.protobuf.extras.serde.JsonNumberFormat;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.protobuf.extras.serde.JsonTrees;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import dev.appiaworks.protobuf.extras.serde.SerializerCompilation;
import dev.appiaworks.protobuf.extras.serde.TypedMessageWriter;
import dev.appiaworks.protobuf.extras.serde.TypedWriters;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads and writes proto messages as JSON in one dialect: proto3 JSON, with the
 * {@code google.type} messages as ISO text and your own {@link ProtoScalarCodec}s on top.
 *
 * <pre>{@code
 * ProtoJson json = ProtoJson.builder()
 *         .scalar(skuCodec)
 *         .int64AsNumber(true)
 *         .build();
 *
 * String text = json.print(order);
 * Order back  = json.parse(text, Order.getDefaultInstance());
 * }</pre>
 *
 * <p>An instance is immutable and thread-safe; build it once and share it. Instances are
 * independent: two with different dialects work side by side, and neither affects, nor is
 * affected by, a gateway running in the same JVM.
 *
 * <p>{@link #defaults()} is the dialect the gateway speaks out of the box, so a service that
 * prints with it produces what the gateway in front of it would. {@link #proto3()} is plain
 * proto3 JSON, as {@code JsonFormat} writes it.
 *
 * <p>A value that cannot become its field's type throws {@link ProtoJsonException}, whatever else
 * is on the classpath. {@code google.protobuf.Any} is not unpacked: it is read and written as its
 * raw {@code typeUrl} and {@code value} fields.
 *
 * <p>A message whose JSON form is a single value — a Timestamp, a wrapper, a
 * {@code google.type.Date}, anything with a codec — prints and parses as that value at the top
 * level too: {@code print(timestamp)} is {@code "2026-09-22T14:03:11Z"}, quotes included.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class ProtoJson {

    /** The text a {@code google.protobuf.Duration} is written as. */
    public enum DurationFormat {
        /** Seconds with an {@code s} suffix, as proto3 JSON writes it: {@code "5400s"}. */
        PROTO,
        /** ISO-8601: {@code "PT1H30M"}. */
        ISO_8601
    }

    private static final JsonFactory JSON = new JsonFactory();

    private static final ProtoJson DEFAULTS = builder().build();
    private static final ProtoJson PROTO3 = builder()
            .dateAsText(false)
            .timeOfDayAsText(false)
            .dateTimeAsText(false)
            .decimalAsText(false)
            .acceptIso8601Duration(false)
            .build();

    /** Past this many compiled message types an instance stops compiling and uses the other paths. */
    private static final int MAX_COMPILED_TYPES = 2_000;

    private final JsonDialect dialect;
    private final JsonPrintOptions print;

    /** Wire bytes straight to JSON. Its programs bake in this dialect's scalars, hence one per instance. */
    private final WireJsonTranscoder transcoder = new WireJsonTranscoder();

    private final boolean compile;

    /** Compiled serializer per message type, empty when the type cannot be compiled. */
    private final ConcurrentHashMap<Descriptor, Optional<AbstractDynamicSerializer>> serializers =
            new ConcurrentHashMap<>();

    /** Generated writer per generated class, reading its getters; wired through {@link TypedWriters}. */
    private final ConcurrentHashMap<Class<? extends Message>, TypedMessageWriter> typedWriters =
            new ConcurrentHashMap<>();

    /** Generated classes no writer can be built for: they print from their bytes. */
    private final Set<Class<? extends Message>> untyped = ConcurrentHashMap.newKeySet();

    private ProtoJson(JsonDialect dialect, JsonPrintOptions print, boolean compile) {
        this.dialect = dialect;
        this.print = print;
        this.compile = compile && DynamicSerializerCompiler.available();
    }

    /**
     * Whether this instance generates a serializer per message type, and a writer per generated
     * class: true on Java 25+ with {@code protobuf-java-extras-classfile} on the classpath, unless
     * the builder switched it off. Either way the output is the same.
     */
    public boolean usesCompiledSerializers() {
        return compile;
    }

    /**
     * The gateway's default dialect: Date, TimeOfDay, DateTime and Decimal as text, Money as its
     * proto object, Durations as {@code "5400s"} with ISO-8601 also accepted, 64-bit integers as
     * strings.
     */
    public static ProtoJson defaults() {
        return DEFAULTS;
    }

    /** Plain proto3 JSON: every {@code google.type} message an object, nothing extra accepted. */
    public static ProtoJson proto3() {
        return PROTO3;
    }

    /** A builder starting from {@link #defaults()}. */
    public static Builder builder() {
        return new Builder();
    }

    // ── Write ────────────────────────────────────────────────────────────────────────

    /** The message as JSON text. */
    public String print(Message message) {
        return new String(printUtf8(message), StandardCharsets.UTF_8);
    }

    /** The message as JSON, UTF-8 encoded: {@link #print(Message)} without the String in between. */
    public byte[] printUtf8(Message message) {
        Descriptor descriptor = message.getDescriptorForType();
        if (!(message instanceof DynamicMessage)) {
            // A generated message prints fastest through a writer over its own getters, and
            // otherwise from its bytes: no second message is built either way.
            TypedMessageWriter writer = typedWriterFor(message);
            if (writer != null) return writeTypedToBytes(writer, message);
            byte[] json = transcodeOrNull(message.toByteArray(), descriptor);
            if (json != null) return json;
        }
        return writeToBytes(dynamic(message));
    }

    /**
     * Writes the message as JSON text. The writer is flushed, not closed.
     *
     * @throws java.io.UncheckedIOException when the writer fails
     */
    public void print(Message message, Writer out) {
        try {
            JsonGenerator gen = JSON.createGenerator(out);
            gen.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            write(message, gen);
            gen.close();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Writes the message as one JSON value to a generator you own, for embedding in a larger document. */
    public void write(Message message, JsonGenerator gen) throws IOException {
        if (!(message instanceof DynamicMessage)) {
            // As in printUtf8: through a writer over the getters, else from its bytes.
            TypedMessageWriter writer = typedWriterFor(message);
            if (writer != null) {
                dialect.<Void, IOException>call(() -> {
                    writer.write(message, gen);
                    return null;
                });
                return;
            }
            byte[] json = transcodeOrNull(message.toByteArray(), message.getDescriptorForType());
            if (json != null) {
                gen.writeRawValue(new String(json, StandardCharsets.UTF_8));
                return;
            }
        }
        writeDynamic(dynamic(message), gen);
    }

    /**
     * Serialized proto bytes as JSON text, without building the message: for a consumer that holds
     * the wire form already, such as a Kafka record or a proxied gRPC response.
     *
     * @param wire one serialized message of the type {@code descriptor} describes
     * @throws IllegalArgumentException when the bytes are not such a message
     */
    public String print(byte[] wire, Descriptor descriptor) {
        return new String(transcode(wire, descriptor), StandardCharsets.UTF_8);
    }

    /** {@link #print(byte[], Descriptor)} with the JSON as UTF-8 bytes: proto bytes in, JSON bytes out. */
    public byte[] transcode(byte[] wire, Descriptor descriptor) {
        Objects.requireNonNull(wire, "wire");
        Objects.requireNonNull(descriptor, "descriptor");
        byte[] json = transcodeOrNull(wire, descriptor);
        if (json != null) return json;
        // A single-value type, one the transcoder does not cover, or an encoding it will not stream.
        DynamicMessage dynamic;
        try {
            dynamic = DynamicMessage.parseFrom(descriptor, wire);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Not a serialized " + descriptor.getFullName(), e);
        }
        return writeToBytes(dynamic);
    }

    /** Tier 0, or null when it does not apply: the caller then goes through a message. */
    private byte[] transcodeOrNull(byte[] wire, Descriptor descriptor) {
        return dialect.call(() -> {
            if (ProtoWkt.isWkt(descriptor)) return null;
            WireJsonTranscoder.Program program = transcoder.programFor(descriptor);
            return program == null ? null : transcoder.transcode(program, wire, print);
        });
    }

    private byte[] writeTypedToBytes(TypedMessageWriter writer, Message message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try (JsonGenerator gen = JSON.createGenerator(out, JsonEncoding.UTF8)) {
            dialect.<Void, IOException>call(() -> {
                writer.write(message, gen);
                return null;
            });
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * The writer for the message's class: the one it carries when protoc-gen-java-extras built
     * it, else one generated on first use with the writers of every generated class its fields
     * reach; null when the layout is not the default (the writers print that one only), the
     * generator is off and the class carries none, or the class cannot be served.
     */
    private TypedMessageWriter typedWriterFor(Message message) {
        if (!print.isDefault()) return null;
        // A class that writes its own JSON (protoc-gen-java-extras) needs nothing generated.
        if (!compile && !(message instanceof JsonWritable)) return null;
        Class<? extends Message> type = message.getClass();
        TypedMessageWriter known = typedWriters.get(type);
        if (known != null) return known;
        return untyped.contains(type) ? null : compileTypedWriter(message);
    }

    private synchronized TypedMessageWriter compileTypedWriter(Message prototype) {
        Class<? extends Message> type = prototype.getClass();
        TypedMessageWriter raced = typedWriters.get(type);
        if (raced != null) return raced;
        if (untyped.contains(type) || typedWriters.size() >= MAX_COMPILED_TYPES) return null;
        try {
            // Inside the dialect's scope: the writers bake in the mapping in force when built.
            return dialect.<TypedMessageWriter, Exception>call(() -> TypedWriters.compile(prototype, typedWriters));
        } catch (Exception | LinkageError e) {
            // Reaches google.protobuf.Any, an accessor is missing, or the class cannot be defined:
            // this class prints from its bytes.
            untyped.add(type);
            return null;
        }
    }

    private byte[] writeToBytes(DynamicMessage dynamic) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try (JsonGenerator gen = JSON.createGenerator(out, JsonEncoding.UTF8)) {
            writeDynamic(dynamic, gen);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private void writeDynamic(DynamicMessage dynamic, JsonGenerator gen) throws IOException {
        Descriptor descriptor = dynamic.getDescriptorForType();
        dialect.<Void, IOException>call(() -> {
            if (ProtoWkt.isWkt(descriptor)) {
                // A Timestamp, a wrapper, a codec-mapped message: its JSON is one value, not an object.
                ProtoWkt.writeJson(gen, dynamic, print.numberFormat());
                return null;
            }
            // The compiled serializers print the default layout only.
            AbstractDynamicSerializer serializer = print.isDefault() ? serializerFor(descriptor) : null;
            if (serializer != null) {
                serializer.serialize(dynamic, gen);
            } else {
                ProtoMapConverter.writeMessageJson(gen, dynamic, print);
            }
            return null;
        });
    }

    /** The compiled serializer for the type, or null. Must run inside the dialect's scope. */
    private AbstractDynamicSerializer serializerFor(Descriptor descriptor) {
        if (!compile) return null;
        Optional<AbstractDynamicSerializer> known = serializers.get(descriptor);
        return known != null ? known.orElse(null) : compileWithNested(descriptor);
    }

    /**
     * Compiles the type and every message type it reaches, wires each to its nested serializers,
     * and only then publishes them: a serializer another thread can see is never still being wired.
     */
    private synchronized AbstractDynamicSerializer compileWithNested(Descriptor root) {
        Optional<AbstractDynamicSerializer> raced = serializers.get(root);
        if (raced != null) return raced.orElse(null);

        List<Descriptor> reachable = SerializerCompilation.reachable(root);
        boolean room = serializers.size() + reachable.size() <= MAX_COMPILED_TYPES;
        Map<Descriptor, AbstractDynamicSerializer> compiled = room
                // A type reaching google.protobuf.Any, or one that does not generate: the reflective converter serves
                // it.
                ? SerializerCompilation.compile(reachable, this::compiledOrNull, (type, e) -> {})
                : Map.of();
        for (Descriptor type : reachable) {
            if (!serializers.containsKey(type)) serializers.put(type, Optional.ofNullable(compiled.get(type)));
        }
        return compiledOrNull(root);
    }

    /** The compiled serializer this instance holds for the type, or null. */
    private AbstractDynamicSerializer compiledOrNull(Descriptor type) {
        Optional<AbstractDynamicSerializer> known = serializers.get(type);
        return known == null ? null : known.orElse(null);
    }

    /**
     * The message as the tree its JSON would parse to: maps, lists, strings, numbers and
     * booleans. Field names are lowerCamelCase and no field is omitted, whatever the builder's
     * print options say; those shape {@link #print} only.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(Message message) {
        DynamicMessage dynamic = dynamic(message);
        return dialect.call(() -> {
            if (!ProtoWkt.isWkt(dynamic.getDescriptorForType())) return ProtoMapConverter.dynamicToMap(dynamic);
            Object value = ProtoWkt.toJsonValue(dynamic);
            if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
            throw new IllegalArgumentException(dynamic.getDescriptorForType().getFullName()
                    + " is written as a single JSON value, not an object; use print");
        });
    }

    // ── Read ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses JSON text into the generated class of {@code prototype}. Fields may be named in
     * lowerCamelCase or as the proto declares them; unknown fields are ignored; JSON null leaves a
     * field unset.
     *
     * @param prototype any instance of the generated class, typically {@code getDefaultInstance()}
     * @throws ProtoJsonException when the text is not a JSON object or a value does not fit its field
     */
    @SuppressWarnings("unchecked")
    public <M extends Message> M parse(String json, M prototype) {
        Objects.requireNonNull(json, "json");
        Descriptor descriptor = prototype.getDescriptorForType();
        // A class built with protoc-gen-java-extras reads itself: no DynamicMessage, no re-read
        // from bytes. Whether the type is a single value depends on the dialect: ask inside it.
        Message.Builder builder = dialect.call(() -> ProtoWkt.isWkt(descriptor) ? null : prototype.newBuilderForType());
        if (builder instanceof JsonReadable readable) {
            Message parsed = parseReadable(json, descriptor, readable);
            if (parsed != null) return (M) parsed;
        }
        return ProtoTypes.as(parse(json, descriptor), prototype);
    }

    /** {@link #parse(String, Message)} for code that works from descriptors. */
    public DynamicMessage parse(String json, Descriptor descriptor) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(descriptor, "descriptor");
        if (compile) {
            // Whether the type is a single value depends on the dialect: ask inside it.
            AbstractDynamicSerializer serializer =
                    dialect.call(() -> ProtoWkt.isWkt(descriptor) ? null : serializerFor(descriptor));
            if (serializer != null) return parseCompiled(json, descriptor, serializer);
        }
        return parseTree(json, descriptor);
    }

    /**
     * Streams the text into a Builder that reads itself, under the same strict rules as the
     * compiled parser; null when the text is not an object, which the tree reader then reports
     * the way it always has.
     */
    private Message parseReadable(String json, Descriptor descriptor, JsonReadable readable) {
        try (JsonParser parser = JSON.createParser(json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new ProtoJsonException(descriptor.getFullName(), "", "expected a JSON value, got nothing", null);
            }
            if (first != JsonToken.START_OBJECT) return null;
            Message message = dialect.<Message, IOException>call(
                    () -> readable.mergeJson(parser).build());
            if (parser.nextToken() != null) {
                throw new ProtoJsonException(
                        descriptor.getFullName(), abbreviate(json), "unexpected content after the JSON value", null);
            }
            return message;
        } catch (IOException e) {
            throw new ProtoJsonException(
                    descriptor.getFullName(), abbreviate(json), "malformed JSON: " + e.getMessage(), e);
        } catch (ProtoJsonException e) {
            throw e;
        } catch (RuntimeException e) {
            String reason =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new ProtoJsonException(descriptor.getFullName(), abbreviate(json), reason, e);
        }
    }

    /** {@link #parse(String, Descriptor)} through a tree and the reflective converter. */
    private DynamicMessage parseTree(String json, Descriptor descriptor) {
        Object value = readDocument(json, descriptor);
        return dialect.call(() -> {
            if (ProtoWkt.isWkt(descriptor)) {
                // One JSON value, as written: "2026-09-22T14:03:11Z" for a Timestamp.
                DynamicMessage message = ProtoWkt.fromJava(value, descriptor);
                return message != null ? message : DynamicMessage.getDefaultInstance(descriptor);
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw new ProtoJsonException(
                        descriptor.getFullName(), abbreviate(json), "expected a JSON object", null);
            }
            return objectFromMap(map, descriptor);
        });
    }

    /** Builds the generated class of {@code prototype} from a tree, the reverse of {@link #toMap}. */
    public <M extends Message> M fromMap(Map<String, ?> map, M prototype) {
        return ProtoTypes.as(fromMap(map, prototype.getDescriptorForType()), prototype);
    }

    /** {@link #fromMap(Map, Message)} for code that works from descriptors. */
    public DynamicMessage fromMap(Map<String, ?> map, Descriptor descriptor) {
        Objects.requireNonNull(map, "map");
        return dialect.call(() -> {
            if (ProtoWkt.isWkt(descriptor)) return ProtoWkt.fromJava(map, descriptor);
            return objectFromMap(map, descriptor);
        });
    }

    // ── Internals ────────────────────────────────────────────────────────────────────

    private static DynamicMessage dynamic(Message message) {
        return ProtoTypes.dynamic(message, message.getDescriptorForType());
    }

    /** Must run inside the dialect's scope. */
    @SuppressWarnings("unchecked")
    private static DynamicMessage objectFromMap(Map<?, ?> map, Descriptor descriptor) {
        try {
            return ProtoMapConverter.dynamicFromMap((Map<String, Object>) map, descriptor);
        } catch (ProtoJsonException e) {
            throw e;
        } catch (RuntimeException e) {
            // A plain scalar the converter could not coerce: "abc" for an int32, a list for a string.
            String reason =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new ProtoJsonException(descriptor.getFullName(), map, reason, e);
        }
    }

    /**
     * Streams the text through the type's compiled parser: no tree in between. It reads under the
     * same strict rules as the reflective converter, so the result and every refusal are the same.
     */
    private DynamicMessage parseCompiled(String json, Descriptor descriptor, AbstractDynamicSerializer serializer) {
        try (JsonParser parser = JSON.createParser(json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new ProtoJsonException(descriptor.getFullName(), "", "expected a JSON value, got nothing", null);
            }
            if (first != JsonToken.START_OBJECT) {
                // Not an object: let the tree reader report it the way it always has.
                return parseTree(json, descriptor);
            }
            DynamicMessage message =
                    dialect.call(() -> serializer.deserialize(parser, DynamicMessage.newBuilder(descriptor)));
            if (parser.nextToken() != null) {
                throw new ProtoJsonException(
                        descriptor.getFullName(), abbreviate(json), "unexpected content after the JSON value", null);
            }
            return message;
        } catch (IOException e) {
            throw new ProtoJsonException(
                    descriptor.getFullName(), abbreviate(json), "malformed JSON: " + e.getMessage(), e);
        } catch (ProtoJsonException e) {
            throw e;
        } catch (RuntimeException e) {
            String reason =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new ProtoJsonException(descriptor.getFullName(), abbreviate(json), reason, e);
        }
    }

    /** The one JSON value the text holds: a Map, a List, a String, a Number, a Boolean or null. */
    private static Object readDocument(String json, Descriptor descriptor) {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() == null) {
                throw new ProtoJsonException(descriptor.getFullName(), "", "expected a JSON value, got nothing", null);
            }
            Object value = JsonTrees.read(parser);
            if (parser.nextToken() != null) {
                throw new ProtoJsonException(
                        descriptor.getFullName(), abbreviate(json), "unexpected content after the JSON value", null);
            }
            return value;
        } catch (IOException e) {
            throw new ProtoJsonException(
                    descriptor.getFullName(), abbreviate(json), "malformed JSON: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String json) {
        String trimmed = json.strip();
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 40) + "…";
    }

    /** Builds a {@link ProtoJson}; every setting starts at the value {@link ProtoJson#defaults()} has. */
    public static final class Builder {

        private boolean date = true;
        private boolean timeOfDay = true;
        private boolean dateTime = true;
        private boolean decimal = true;
        private boolean money;
        private DurationFormat durationFormat = DurationFormat.PROTO;
        private boolean acceptIso8601Duration = true;
        private boolean acceptEpochMillisTimestamp;
        private boolean int64AsNumber;
        private boolean preserveProtoFieldNames;
        private boolean enumsAsInts;
        private boolean omitDefaultValues;
        private JsonNumberFormat numberFormat = JsonNumberFormat.JAVA;
        private boolean compiledSerializers = true;
        private final List<ProtoScalarCodec> codecs = new ArrayList<>();

        private Builder() {}

        /** {@code google.type.Date} as {@code "2026-09-22"} (default) or as its proto object. */
        public Builder dateAsText(boolean enabled) {
            this.date = enabled;
            return this;
        }

        /** {@code google.type.TimeOfDay} as {@code "14:03:11"} (default) or as its proto object. */
        public Builder timeOfDayAsText(boolean enabled) {
            this.timeOfDay = enabled;
            return this;
        }

        /** {@code google.type.DateTime} as ISO-8601 text (default) or as its proto object. */
        public Builder dateTimeAsText(boolean enabled) {
            this.dateTime = enabled;
            return this;
        }

        /** {@code google.type.Decimal} as {@code "12.34"} (default) or as its proto object. */
        public Builder decimalAsText(boolean enabled) {
            this.decimal = enabled;
            return this;
        }

        /**
         * {@code google.type.Money} as {@code {"currency":"USD","amount":"12.34"}} instead of its
         * proto object with {@code units} and {@code nanos}. Off by default.
         */
        public Builder moneyAsAmount(boolean enabled) {
            this.money = enabled;
            return this;
        }

        /** How a Duration is written. {@link DurationFormat#PROTO} by default. */
        public Builder durationFormat(DurationFormat format) {
            this.durationFormat = Objects.requireNonNull(format, "format");
            return this;
        }

        /** Whether {@code "PT1H30M"} is read as a Duration beside {@code "5400s"}. On by default. */
        public Builder acceptIso8601Duration(boolean enabled) {
            this.acceptIso8601Duration = enabled;
            return this;
        }

        /** Whether a JSON number is read as a Timestamp in epoch milliseconds. Off by default. */
        public Builder acceptEpochMillisTimestamp(boolean enabled) {
            this.acceptEpochMillisTimestamp = enabled;
            return this;
        }

        /**
         * 64-bit integers as JSON numbers instead of strings. Off by default, as proto3 JSON has
         * it: a JavaScript client reads numbers above 2^53 inexactly.
         */
        public Builder int64AsNumber(boolean enabled) {
            this.int64AsNumber = enabled;
            return this;
        }

        /** Print field names as the proto declares them ({@code display_name}) instead of lowerCamelCase. */
        public Builder preserveProtoFieldNames(boolean enabled) {
            this.preserveProtoFieldNames = enabled;
            return this;
        }

        /** Print enum values as their numbers instead of their names. */
        public Builder enumsAsInts(boolean enabled) {
            this.enumsAsInts = enabled;
            return this;
        }

        /** Leave out scalar fields holding their default value and empty lists and maps, as {@code JsonFormat} does. */
        public Builder omitDefaultValues(boolean enabled) {
            this.omitDefaultValues = enabled;
            return this;
        }

        /**
         * How {@code double} and {@code float} values are laid out: Java text ({@code 1.0}, the default) or
         * the ECMAScript layout ({@code 1}). Like any print option, a non-default value is served by the
         * option-aware writers, not the compiled serializers.
         */
        public Builder numberFormat(JsonNumberFormat format) {
            this.numberFormat = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Whether to generate a serializer per message type, and a writer per generated class,
         * when the generator is available (Java 25+ with {@code protobuf-java-extras-classfile}).
         * On by default; switch it off where generating classes at run time is unwelcome. The
         * output is the same either way.
         */
        public Builder compiledSerializers(boolean enabled) {
            this.compiledSerializers = enabled;
            return this;
        }

        /** Adds a codec for a message type of your own; one for a {@code google.type} message replaces the built-in form. */
        public Builder scalar(ProtoScalarCodec codec) {
            codecs.add(Objects.requireNonNull(codec, "codec"));
            return this;
        }

        /**
         * @throws IllegalArgumentException when two codecs name the same message, or one names a
         *     {@code google.protobuf} well-known type, whose JSON form proto3 fixes
         */
        public ProtoJson build() {
            ProtoJsonMapping mapping = new ProtoJsonMapping(
                    date,
                    timeOfDay,
                    dateTime,
                    decimal,
                    money,
                    durationFormat == DurationFormat.ISO_8601
                            ? ProtoJsonMapping.DurationFormat.ISO_8601
                            : ProtoJsonMapping.DurationFormat.PROTO,
                    acceptIso8601Duration,
                    acceptEpochMillisTimestamp,
                    int64AsNumber);
            return new ProtoJson(
                    // ProtoJson promises to refuse a value that does not fit its field, on every path.
                    JsonDialect.of(mapping, codecs, true),
                    new JsonPrintOptions(preserveProtoFieldNames, enumsAsInts, omitDefaultValues, numberFormat),
                    compiledSerializers);
        }
    }
}
