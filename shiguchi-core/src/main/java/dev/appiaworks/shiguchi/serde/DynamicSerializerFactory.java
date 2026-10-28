package dev.appiaworks.shiguchi.serde;

import com.google.protobuf.Descriptors.Descriptor;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.DynamicSerializerCompiler;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import dev.appiaworks.protobuf.extras.serde.SerializerCompilation;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tier 2 serde: generates a lightweight serializer class per message type.
 *
 * For each Descriptor, a class with the FieldDescriptors pre-resolved as fields and the five
 * methods below unrolled per field is built with the Class-File API and defined as a hidden
 * class (Java 25+; on an older JVM nothing is generated and the reflective converter serves).
 *
 * The resulting AbstractDynamicSerializer is cached in ServiceEndpointContext.
 *
 * Generated methods:
 *  serialize()   — DynamicMessage → JsonGenerator (REST path, no intermediate String)
 *  deserialize() — JsonParser → DynamicMessage.Builder (REST request path)
 *  toMap()       — DynamicMessage → Map (GraphQL path, zero JSON allocation)
 *  fromMap()     — Map → DynamicMessage (GraphQL path, zero JSON allocation)
 *
 * JSON output follows the proto3 JSON mapping and matches
 * {@code JsonFormat.printer().includingDefaultValueFields()}:
 *  - map fields render as JSON objects, well-known types as their scalar/JSON forms
 *    (delegated to {@link ProtoWkt} / {@link ProtoMapConverter} helpers),
 *  - int64/uint64 render as strings, uint32/uint64 as unsigned values,
 *  - fields with explicit presence (message, proto3 optional, oneof) are omitted when
 *    unset; all other scalars and repeated/map fields are always emitted.
 *
 * The class is built by {@link DynamicSerializerCompiler} and wired to its nested serializers by
 * {@link SerializerCompilation}, both in protobuf-java-extras; this class decides what to compile
 * for a service and caches the result in its context.
 *
 * Messages that (transitively) contain google.protobuf.Any are not compiled — correct Any
 * JSON needs a TypeRegistry — and fall back to Tier 3 JsonFormat.
 */
@Component
@org.springframework.context.annotation.DependsOn(ProtoJsonMappingConfigurer.BEAN_NAME)
public class DynamicSerializerFactory {

    private static final Logger log = LoggerFactory.getLogger(DynamicSerializerFactory.class);

    /**
     * Compiles a serializer for every message type the context's files declare and wires each
     * to the serializers of the types its fields reach. Every type is compiled, whether or not
     * Tier 1 has a typed writer for it: Tier 1 covers the REST byte path for compiled stubs,
     * but the GraphQL Map path still needs the generated toMap()/fromMap().
     */
    public void precompile(ServiceEndpointContext ctx) {
        List<Descriptor> targets = new ArrayList<>();
        for (var fd : ctx.getFileDescriptors().values()) {
            targets.addAll(SerializerCompilation.declared(fd));
        }
        Map<Descriptor, AbstractDynamicSerializer> compiled = SerializerCompilation.compile(
                targets,
                descriptor -> ctx.serializerFor(descriptor).orElse(null),
                (descriptor, e) -> log.warn(
                        "Tier2 compile failed for {} (will fallback to Tier3): {}",
                        descriptor.getFullName(),
                        e.getMessage()));
        compiled.forEach((descriptor, serializer) -> {
            ctx.putSerializer(descriptor, serializer);
            log.debug("Tier2 serde compiled: {}", descriptor.getFullName());
        });
    }

    /** Public entry point used by tests and JMH benchmarks to compile a single descriptor. */
    public AbstractDynamicSerializer compileDescriptor(Descriptor descriptor) throws Exception {
        return DynamicSerializerCompiler.compile(descriptor);
    }

    /** True when the message reaches {@code google.protobuf.Any} at any depth. */
    public static boolean containsAny(Descriptor d) {
        return DynamicSerializerCompiler.containsAny(d);
    }
}
