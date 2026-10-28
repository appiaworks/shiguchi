package dev.appiaworks.shiguchi.serde;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.protobuf.extras.serde.ProjectionNode;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.TypedMessageWriter;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Routes proto serialization to the fastest available tier for a given Descriptor.
 *
 *   Tier 1 — a TypedMessageWriter compiled for the stub class, reading its generated getters
 *            (≈2.5× JsonFormat, EndToEndDispatchBenchmark);
 *            used on the REST byte path when the dispatcher parsed a typed response
 *   Tier 2 — generated DynSerializer over DynamicMessage (~2-3× JsonFormat)
 *   Tier 3 — JsonFormat fallback (baseline); a message that reaches a {@code google.type}
 *            type the {@link ProtoJsonMapping} writes as text takes the reflective
 *            {@link ProtoMapConverter} instead, since JsonFormat cannot be taught the mapping —
 *            unless it also reaches google.protobuf.Any, which only JsonFormat can print
 */
@Component
@org.springframework.context.annotation.DependsOn(ProtoJsonMappingConfigurer.BEAN_NAME)
public class SerdeSelector {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** A body that writes one JSON value to a generator. */
    @FunctionalInterface
    private interface JsonWrite {
        void write(JsonGenerator gen) throws IOException;
    }

    private final StaticSerdeDetector tier1;
    private final DynamicProtoSerde tier3;

    public SerdeSelector(StaticSerdeDetector tier1, DynamicProtoSerde tier3) {
        this.tier1 = tier1;
        this.tier3 = tier3;
    }

    /** {@link #serializeToBytes} as text: a DynamicMessage takes Tier 2 or Tier 3, never Tier 1. */
    public String serialize(
            DynamicMessage message,
            Descriptor descriptor,
            ServiceEndpointContext ctx,
            JsonFormat.TypeRegistry typeRegistry)
            throws Exception {
        return new String(serializeToBytes(message, descriptor, ctx, typeRegistry), StandardCharsets.UTF_8);
    }

    /**
     * True when JsonFormat would write this message differently from the installed
     * {@link ProtoJsonMapping} and the reflective converter can write it instead.
     */
    private static boolean tier3NeedsMapping(Descriptor descriptor) {
        return ProtoJsonMapping.needsGatewaySerde(descriptor) && !DynamicSerializerFactory.containsAny(descriptor);
    }

    /**
     * Serialises straight to UTF-8 bytes for the HTTP response path.
     *
     * A typed (non-Dynamic) message means the dispatcher parsed the response with the
     * generated stub class — Tier 1: the writer compiled for that class emits the JSON through
     * its getters. DynamicMessage responses go through Tier 2 (generated) or Tier 3 (JsonFormat).
     * All tiers write through Jackson's UTF-8 generator into a byte builder — no
     * intermediate String or char[] — so the bytes can be wrapped into a DataBuffer as-is.
     */
    public byte[] serializeToBytes(
            com.google.protobuf.Message message,
            Descriptor descriptor,
            ServiceEndpointContext ctx,
            JsonFormat.TypeRegistry typeRegistry)
            throws Exception {

        JsonPrintOptions options = printOptionsOf(ctx);
        if (!options.isDefault()) {
            return printWithOptions(message, descriptor, typeRegistry, options);
        }

        if (!(message instanceof DynamicMessage dynamicMessage)) {
            // Tier 1
            TypedMessageWriter writer = tier1.writerFor(descriptor);
            if (writer != null) return utf8(gen -> writer.write(message, gen));
            // A typed message no writer serves: through its dynamic form, like any other.
            return serializeToBytes(
                    DynamicMessage.parseFrom(descriptor, message.toByteString()), descriptor, ctx, typeRegistry);
        }

        Optional<AbstractDynamicSerializer> tier2 = ctx.serializerFor(descriptor);
        if (tier2.isPresent()) {
            return utf8(gen -> tier2.get().serialize(dynamicMessage, gen));
        }

        // Tier 3
        if (tier3NeedsMapping(descriptor)) {
            return utf8(gen -> ProtoMapConverter.writeMessageJson(gen, dynamicMessage));
        }
        return tier3.responseToJson(dynamicMessage, typeRegistry).getBytes(StandardCharsets.UTF_8);
    }

    /** One JSON value as UTF-8 bytes, written through Jackson's generator into a byte builder. */
    private static byte[] utf8(JsonWrite body) throws IOException {
        ByteArrayBuilder bytes = new ByteArrayBuilder();
        try (JsonGenerator gen = JSON_FACTORY.createGenerator((OutputStream) bytes, JsonEncoding.UTF8)) {
            body.write(gen);
        }
        byte[] result = bytes.toByteArray();
        bytes.release();
        return result;
    }

    /** The service's print options; the defaults for a missing (or mocked) context. */
    private static JsonPrintOptions printOptionsOf(ServiceEndpointContext ctx) {
        JsonPrintOptions options = ctx == null ? null : ctx.getPrintOptions();
        return options == null ? JsonPrintOptions.DEFAULT : options;
    }

    /**
     * A service that changes {@code json.print} options: Tiers 0–2 bake the default layout into
     * generated code, so its responses are printed by the reflective writer instead — or by
     * {@code JsonFormat} when the type reaches {@code Any}, which only JsonFormat can print. The
     * reflective writer also applies the {@link ProtoJsonMapping} (google.type as text, int64 as
     * numbers), so the output differs from the default only in what the options change.
     */
    private byte[] printWithOptions(
            com.google.protobuf.Message message,
            Descriptor descriptor,
            JsonFormat.TypeRegistry typeRegistry,
            JsonPrintOptions options)
            throws Exception {
        DynamicMessage dynamic = message instanceof DynamicMessage d
                ? d
                // A typed message reaches here only if a caller parsed with the compiled stub anyway.
                : DynamicMessage.parseFrom(descriptor, message.toByteString());
        if (DynamicSerializerFactory.containsAny(descriptor)) {
            return tier3.responseToJson(dynamic, typeRegistry, options).getBytes(StandardCharsets.UTF_8);
        }
        return utf8(gen -> ProtoMapConverter.writeMessageJson(gen, dynamic, options));
    }

    /**
     * The compiled default instance for a response type when Tier 1 can serve it end to end,
     * or null. The dispatcher uses this to switch the gRPC response marshaller to the
     * generated parser.
     */
    public com.google.protobuf.Message tier1ResponseInstance(Descriptor descriptor) {
        return tier1.defaultInstanceFor(descriptor);
    }

    /**
     * {@link #tier1ResponseInstance(Descriptor)} for a service: null when the service changes the
     * print options, since Tier 1 writes only the default layout and parsing into the compiled
     * type would just be converted back.
     */
    public com.google.protobuf.Message tier1ResponseInstance(Descriptor descriptor, ServiceEndpointContext ctx) {
        if (!printOptionsOf(ctx).isDefault()) return null;
        return tier1.defaultInstanceFor(descriptor);
    }

    public DynamicMessage deserialize(
            String json, Descriptor descriptor, ServiceEndpointContext ctx, JsonFormat.TypeRegistry typeRegistry)
            throws Exception {

        // Tier 2 and 3 both use DynamicMessage — skip Tier 1 (requires concrete class)
        Optional<AbstractDynamicSerializer> tier2 = ctx.serializerFor(descriptor);
        if (tier2.isPresent()) {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
            try (var parser = JSON_FACTORY.createParser(json)) {
                parser.nextToken(); // START_OBJECT
                return tier2.get().deserialize(parser, builder);
            }
        }

        // Tier 3
        if (tier3NeedsMapping(descriptor)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            return ProtoMapConverter.dynamicFromMap(map, descriptor);
        }
        return tier3.jsonToRequest(descriptor, typeRegistry, json);
    }

    /**
     * Converts a DynamicMessage to Map<String,Object> without JSON. Used by the GraphQL dispatch path.
     * Tier 2 uses the generated toMap(); Tier 3 falls back to ProtoMapConverter.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> serializeToMap(DynamicMessage message, Descriptor descriptor, ServiceEndpointContext ctx)
            throws Exception {

        Optional<AbstractDynamicSerializer> tier2 = ctx.serializerFor(descriptor);
        if (tier2.isPresent()) {
            return (Map<String, Object>) tier2.get().toMap(message);
        }
        return ProtoMapConverter.dynamicToMap(message);
    }

    /**
     * Selection-aware variant of {@link #serializeToMap}: converts only the composite subtrees
     * the GraphQL query selected (scalars always convert — see
     * {@link ProtoMapConverter#projectToMap}). A null projection falls back to the full
     * Tier 2/Tier 3 conversion.
     */
    public Map<String, Object> serializeToMapProjected(
            DynamicMessage message, Descriptor descriptor, ServiceEndpointContext ctx, ProjectionNode projection)
            throws Exception {

        if (projection == null || projection.isIncludeAll()) {
            return serializeToMap(message, descriptor, ctx);
        }
        Optional<AbstractDynamicSerializer> tier2 = ctx.serializerFor(descriptor);
        if (tier2.isPresent()) {
            return (Map<String, Object>) tier2.get().toMap(message, projection);
        }
        return ProtoMapConverter.projectToMap(message, projection);
    }

    /**
     * Converts a Map<String,Object> to DynamicMessage without JSON. Used by the GraphQL dispatch path.
     * Tier 2 uses the generated fromMap(); Tier 3 falls back to ProtoMapConverter.
     */
    @SuppressWarnings("unchecked")
    public DynamicMessage deserializeFromMap(Map<String, Object> map, Descriptor descriptor, ServiceEndpointContext ctx)
            throws Exception {

        Optional<AbstractDynamicSerializer> tier2 = ctx.serializerFor(descriptor);
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
        if (tier2.isPresent()) {
            return tier2.get().fromMap(map, builder);
        }
        return ProtoMapConverter.dynamicFromMap(map, descriptor);
    }
}
