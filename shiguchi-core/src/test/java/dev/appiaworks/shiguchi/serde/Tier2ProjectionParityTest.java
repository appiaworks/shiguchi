package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.ProjectionNode;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The compiled projected conversion ({@code AbstractDynamicSerializer.toMap(message, projection)})
 * must prune exactly what the reflective one ({@link ProtoMapConverter#projectToMap}) prunes —
 * same keys at every level, recursion through nested and repeated messages with the child
 * projection — while every value it keeps equals what the plain compiled {@code toMap} produces.
 * Values are compared against the compiled full conversion rather than the reflective one because
 * the two tiers already differ in how they represent well-known types on the Map path (Tier 2
 * hands GraphQL an {@code OffsetDateTime} for a Timestamp, the reflective walker a string), and
 * projection must not change which of the two a query gets. Random messages and random
 * projections over the parity kitchen-sink message, so every field shape is covered.
 */
@DisplayName("Tier 2 projected toMap parity with ProtoMapConverter.projectToMap")
class Tier2ProjectionParityTest {

    private static Descriptor allTypes;
    private static AbstractDynamicSerializer serializer;

    @BeforeAll
    static void compileSerializers() throws Exception {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
        // randomMessage builds against SerdeTierParityTest's own descriptor instances, so the
        // serializer under test must be compiled from those same instances, not a fresh file.
        SerdeTierParityTest.compileSerializers();
        allTypes = SerdeTierParityTest.randomMessage(new Random(0)).getDescriptorForType();
        Descriptor inner = allTypes.getFile().findMessageTypeByName("Inner");
        DynamicSerializerFactory factory = new DynamicSerializerFactory();
        serializer = factory.compileDescriptor(allTypes);
        serializer.setNested("parity.Inner", factory.compileDescriptor(inner));
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 2584, 4181, 6765, 10946})
    @DisplayName(
            "GIVEN a random message and a random projection WHEN Tier 2 projects SHOULD equal the reflective projection")
    void given_randomMessageAndProjection_when_tier2Projects_should_matchReflectiveProjection(long seed) {
        Random random = new Random(seed);
        DynamicMessage message = SerdeTierParityTest.randomMessage(random);
        ProjectionNode projection = randomProjection(allTypes, random, 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> compiled = serializer.toMap(message, projection);
        Map<String, Object> reflective = ProtoMapConverter.projectToMap(message, projection);
        @SuppressWarnings("unchecked")
        Map<String, Object> full = serializer.toMap(message);

        assertThat(compiled).isEqualTo(shapedLike(full, reflective));
    }

    @Test
    @DisplayName("GIVEN a projection selecting only scalars WHEN Tier 2 projects SHOULD drop every composite field")
    void given_scalarsOnlyProjection_when_tier2Projects_should_dropComposites() {
        DynamicMessage message = SerdeTierParityTest.randomMessage(new Random(7));
        ProjectionNode projection = ProjectionNode.root();
        projection.addPath("i32");

        @SuppressWarnings("unchecked")
        Map<String, Object> compiled = serializer.toMap(message, projection);

        for (FieldDescriptor fd : allTypes.getFields()) {
            if (isComposite(fd)) {
                assertThat(compiled).as("composite %s pruned", fd.getJsonName()).doesNotContainKey(fd.getJsonName());
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> full = serializer.toMap(message);
        assertThat(compiled).isEqualTo(shapedLike(full, ProtoMapConverter.projectToMap(message, projection)));
    }

    @Test
    @DisplayName("GIVEN an include-all projection WHEN Tier 2 projects SHOULD equal the plain compiled toMap")
    void given_includeAllProjection_when_tier2Projects_should_equalPlainToMap() {
        DynamicMessage message = SerdeTierParityTest.randomMessage(new Random(11));

        @SuppressWarnings("unchecked")
        Map<String, Object> projected = serializer.toMap(message, ProjectionNode.includeAll());
        @SuppressWarnings("unchecked")
        Map<String, Object> plain = serializer.toMap(message);

        assertThat(projected).isEqualTo(plain);
    }

    /** Selects each composite field with probability 1/2, recursing into messages; scalars are implied. */
    private static ProjectionNode randomProjection(Descriptor descriptor, Random random, int depth) {
        ProjectionNode node = ProjectionNode.root();
        for (FieldDescriptor fd : descriptor.getFields()) {
            if (!isComposite(fd)) continue;
            if (random.nextBoolean()) continue;
            if (fd.isMapField() || depth > 2) {
                node.child(fd.getJsonName());
            } else {
                node.setChild(fd.getJsonName(), randomProjection(fd.getMessageType(), random, depth + 1));
            }
        }
        // A projection with nothing selected still has to prune everything composite.
        return node;
    }

    /**
     * The compiled full conversion cut down to the keys the reflective projection kept, at every
     * level: what a correct compiled projection must produce.
     */
    @SuppressWarnings("unchecked")
    private static Object shapedLike(Object full, Object shape) {
        if (full instanceof Map<?, ?> fullMap && shape instanceof Map<?, ?> shapeMap) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<String, Object>) fullMap).entrySet()) {
                if (shapeMap.containsKey(e.getKey())) {
                    out.put((String) e.getKey(), shapedLike(e.getValue(), shapeMap.get(e.getKey())));
                }
            }
            return out;
        }
        if (full instanceof java.util.List<?> fullList && shape instanceof java.util.List<?> shapeList) {
            java.util.List<Object> out = new java.util.ArrayList<>(fullList.size());
            for (int i = 0; i < fullList.size(); i++) {
                out.add(shapedLike(fullList.get(i), i < shapeList.size() ? shapeList.get(i) : null));
            }
            return out;
        }
        return full;
    }

    private static boolean isComposite(FieldDescriptor fd) {
        return fd.isMapField()
                || (fd.getType() == FieldDescriptor.Type.MESSAGE && !ProtoWkt.isWkt(fd.getMessageType()));
    }
}
