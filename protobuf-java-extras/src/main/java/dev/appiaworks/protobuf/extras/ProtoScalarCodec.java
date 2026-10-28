package dev.appiaworks.protobuf.extras;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.Objects;
import java.util.function.Function;

/**
 * Writes one proto message type as a single JSON value instead of the object proto3 JSON gives
 * it, and reads that value back — the way the gateway writes {@code google.type.Date} as
 * {@code "2026-09-22"}.
 *
 * <p>A codec is keyed by the message's full proto name and applies wherever a field of that type
 * appears: REST bodies, query parameters and path variables, GraphQL, the Avro surface. In a
 * Spring application, declare it as a bean and the gateway registers it at startup.
 *
 * <p>The JSON side is a plain Java value: a {@code String}, a {@code Number}, a {@code Boolean},
 * a {@code Map<String, Object>}, a {@code List<Object>} or {@code null}. It is not a streaming
 * API because the same value also arrives from a query string and from a GraphQL argument, where
 * there is no JSON document to parse.
 *
 * <p>The message handed to {@link #toJson} is usually a {@code DynamicMessage}: the gateway works
 * from descriptors, not generated classes. {@link #ofString} and {@link #of} hide that and let a
 * codec be written against the generated class.
 *
 * <p>The well-known types in {@code google.protobuf} cannot be given a codec: proto3 JSON fixes
 * their form.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.8.0")
public interface ProtoScalarCodec {

    /** The kind of JSON value a codec writes, for the schemas the gateway publishes. */
    enum JsonShape {
        STRING,
        /** A whole number. */
        INTEGER,
        /** Any number, fractions included. */
        NUMBER,
        BOOLEAN,
        /** An object, an array, or a value whose kind varies. */
        ANY
    }

    /** The full proto name of the message this codec handles, e.g. {@code acme.catalog.Sku}. */
    String fullName();

    /** The JSON value of the message. */
    Object toJson(Message message);

    /**
     * The message for a JSON value. The result may be a generated message or a
     * {@code DynamicMessage}; either way it must be of the type {@code descriptor} describes.
     * Throw {@link IllegalArgumentException} for a value that cannot become the type: the caller
     * gets {@code INVALID_ARGUMENT} naming the type and the value.
     *
     * @param json never null — JSON null means the field is unset and does not reach the codec
     * @param descriptor the descriptor of the field's message type, which may come from gRPC
     *     reflection rather than a generated class
     */
    Message fromJson(Object json, Descriptor descriptor);

    /**
     * What {@link #toJson} returns, so OpenAPI, GraphQL and Avro can describe the field. Unless
     * this is {@link JsonShape#ANY}, a JSON object on input is still read as the message's plain
     * proto3 form, so clients that send the object keep working.
     *
     * <p>Only {@link #schema()}'s default reads this. Override one or the other: where both are
     * overridden, {@code schema().shape()} is what counts.
     */
    default JsonShape jsonShape() {
        return JsonShape.STRING;
    }

    /**
     * How the published schemas describe the value: its {@linkplain #jsonShape() shape}, and
     * optionally a format, a pattern, a description, an example and a GraphQL scalar name. See
     * {@link ScalarSchema}. The default is the shape alone.
     */
    @ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.9.0")
    default ScalarSchema schema() {
        return ScalarSchema.of(jsonShape());
    }

    /**
     * This codec, described by {@code schema} instead.
     *
     * <pre>{@code
     * ProtoScalarCodec.ofString(Sku.getDefaultInstance(), Sku::getCode, Skus::parse)
     *         .withSchema(ScalarSchema.string().withPattern("[A-Z]{2}-\\d+").withExample("AB-1"))
     * }</pre>
     */
    @ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.9.0")
    default ProtoScalarCodec withSchema(ScalarSchema schema) {
        Objects.requireNonNull(schema, "schema");
        ProtoScalarCodec codec = this;
        return new ProtoScalarCodec() {
            @Override
            public String fullName() {
                return codec.fullName();
            }

            @Override
            public Object toJson(Message message) {
                return codec.toJson(message);
            }

            @Override
            public Message fromJson(Object json, Descriptor descriptor) {
                return codec.fromJson(json, descriptor);
            }

            @Override
            public JsonShape jsonShape() {
                return schema.shape();
            }

            @Override
            public ScalarSchema schema() {
                return schema;
            }

            @Override
            public String toString() {
                return codec.toString();
            }
        };
    }

    /**
     * A codec that writes a message as text, written against its generated class.
     *
     * <pre>{@code
     * ProtoScalarCodec.ofString(Sku.getDefaultInstance(), Sku::getCode, code -> Sku.newBuilder().setCode(code).build())
     * }</pre>
     *
     * @param prototype any instance of the generated class, typically {@code getDefaultInstance()}
     * @param print message to text
     * @param parse text to message; throw {@link IllegalArgumentException} on malformed text
     */
    static <M extends Message> ProtoScalarCodec ofString(
            M prototype, Function<? super M, String> print, Function<String, ? extends M> parse) {
        Objects.requireNonNull(print, "print");
        Objects.requireNonNull(parse, "parse");
        return of(prototype, JsonShape.STRING, print, json -> {
            if (!(json instanceof CharSequence text)) throw new IllegalArgumentException("expected a string");
            return parse.apply(text.toString());
        });
    }

    /**
     * A codec written against the generated class, for a JSON value of any shape.
     *
     * @param prototype any instance of the generated class, typically {@code getDefaultInstance()}
     * @param shape the kind of value {@code toJson} returns
     * @param toJson message to JSON value
     * @param fromJson JSON value to message; throw {@link IllegalArgumentException} when it cannot be
     */
    static <M extends Message> ProtoScalarCodec of(
            M prototype, JsonShape shape, Function<? super M, ?> toJson, Function<Object, ? extends M> fromJson) {
        Objects.requireNonNull(prototype, "prototype");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(toJson, "toJson");
        Objects.requireNonNull(fromJson, "fromJson");
        String fullName = prototype.getDescriptorForType().getFullName();
        return new ProtoScalarCodec() {
            @Override
            public String fullName() {
                return fullName;
            }

            @Override
            public Object toJson(Message message) {
                return toJson.apply(ProtoTypes.as(message, prototype));
            }

            @Override
            public Message fromJson(Object json, Descriptor descriptor) {
                return fromJson.apply(json);
            }

            @Override
            public JsonShape jsonShape() {
                return shape;
            }

            @Override
            public String toString() {
                return "ProtoScalarCodec[" + fullName + "]";
            }
        };
    }
}
