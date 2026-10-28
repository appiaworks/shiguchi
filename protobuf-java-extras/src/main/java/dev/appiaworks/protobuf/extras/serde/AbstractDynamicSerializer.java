package dev.appiaworks.protobuf.extras.serde;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.google.protobuf.DynamicMessage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for the Tier 2 generated serializers.
 *
 * DynamicSerializerFactory generates a concrete subclass per Descriptor at startup:
 *  - static final FieldDescriptor constants for every proto field (pre-baked lookup)
 *  - serialize(): writes directly to Jackson JsonGenerator (REST path, no intermediate String)
 *  - deserialize(): reads from Jackson JsonParser and fills a DynamicMessage.Builder
 *  - toMap(): converts DynamicMessage → Map<String,Object> with zero JSON allocation (GraphQL path)
 *  - fromMap(): converts Map<String,Object> → DynamicMessage with zero JSON allocation (GraphQL path)
 *
 * Eliminates JsonFormat.printer()'s per-call descriptor.getFields() loop and
 * string field-name map lookups (~2-3x faster than Tier 3 JsonFormat baseline).
 * toMap()/fromMap() additionally eliminate the JSON string entirely on the GraphQL path.
 *
 * Nested MESSAGE fields delegate to their own compiled serializer (also Tier 2) when available,
 * falling back to ProtoMapConverter (Tier 3) otherwise.
 */
public abstract class AbstractDynamicSerializer {

    /**
     * Reads the JSON value the parser is on into plain Java, for generated subclasses (WKT and
     * repeated-WKT reads in deserialize()). Streaming parser only: no jackson-databind.
     */
    protected static Object readTree(JsonParser p) throws IOException {
        return JsonTrees.read(p);
    }

    /** {@link #readTree} for a nested message or map field, which must be a JSON object. */
    protected static Map<String, Object> readObjectTree(JsonParser p) throws IOException {
        return JsonTrees.readObject(p);
    }

    private final Map<String, AbstractDynamicSerializer> nestedSerializers = new HashMap<>();

    public final void setNested(String fieldFullName, AbstractDynamicSerializer ser) {
        nestedSerializers.put(fieldFullName, ser);
        nestedBound(fieldFullName, ser);
    }

    /**
     * Wiring hook: generated subclasses override this to assign the serializer into
     * pre-baked per-field instance fields, so the hot path reads a field instead of
     * doing a String-keyed HashMap lookup per nested value.
     */
    protected void nestedBound(String typeFullName, AbstractDynamicSerializer ser) {}

    protected final AbstractDynamicSerializer nestedSerializer(String fieldFullName) {
        return nestedSerializers.get(fieldFullName);
    }

    public abstract void serialize(DynamicMessage message, JsonGenerator generator) throws IOException;

    public abstract DynamicMessage deserialize(JsonParser parser, DynamicMessage.Builder builder) throws IOException;

    /** Converts a DynamicMessage to a Map without going through JSON. Used by the GraphQL dispatch path. */
    @SuppressWarnings("rawtypes")
    public abstract Map toMap(DynamicMessage message);

    /**
     * Selection-projected {@link #toMap(DynamicMessage)}: scalars always convert, composite
     * fields (nested messages, repeated messages, maps) only when {@code projection} selects
     * them — the contract of {@link ProtoMapConverter#projectToMap}, compiled. Generated
     * subclasses override this; the default here is the reflective walker, so a hand-written
     * subclass stays correct.
     */
    @SuppressWarnings("rawtypes")
    public Map toMap(DynamicMessage message, ProjectionNode projection) {
        if (projection == null || projection.isIncludeAll()) return toMap(message);
        return ProtoMapConverter.projectToMap(message, projection);
    }

    /** Converts a Map to a DynamicMessage without going through JSON. Used by the GraphQL dispatch path. */
    @SuppressWarnings("rawtypes")
    public abstract DynamicMessage fromMap(Map map, DynamicMessage.Builder builder);
}
