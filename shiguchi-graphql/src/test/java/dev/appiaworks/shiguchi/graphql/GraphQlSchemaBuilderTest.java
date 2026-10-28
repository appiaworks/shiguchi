package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.protobuf.extras.ScalarSchema;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.proto.api.GraphQlFieldRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BDD-style unit tests for SDL companion-resolver field emission in GraphQlSchemaBuilder.
 *
 * <p>The {@code commonTypeNamePrefix} private static method is tested via reflection to precisely
 * document the algorithm's boundary conditions (including the exact trim-back behavior). The SDL
 * output is exercised by invoking the private {@code emitObjectType} method directly, avoiding
 * full route-registry wiring.
 */
@ExtendWith(MockitoExtension.class)
class GraphQlSchemaBuilderTest {

    @Mock
    private GrpcRouteRegistry registry;

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    // -------------------------------------------------------------------------
    // commonTypeNamePrefix — private static, tested via reflection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Given an empty name list, commonTypeNamePrefix should return empty string")
    void given_emptyNameList_when_computingCommonTypeNamePrefix_should_returnEmpty() throws Exception {
        Method method = reflectCommonTypeNamePrefix();
        String prefix = (String) method.invoke(null, List.of());
        assertThat(prefix).isEmpty();
    }

    @Test
    @DisplayName(
            "Given two types whose first characters differ, commonTypeNamePrefix should return empty string (no shared chars)")
    void given_twoTypesWithNoSharedFirstChar_when_computingCommonTypeNamePrefix_should_returnEmpty() throws Exception {
        // "Publisher" starts with 'P', "Review" starts with 'R' — no shared chars → "".
        Method method = reflectCommonTypeNamePrefix();
        String prefix = (String) method.invoke(null, List.of("Publisher", "Review"));
        assertThat(prefix).isEmpty();
    }

    @Test
    @DisplayName(
            "Given 'BookAuthor' and 'BookAuthorRef' sharing 10 chars, commonTypeNamePrefix returns 'Book' (last inner CamelCase boundary 'A' at index 4)")
    void given_twoTypesWhereShorterIsSubstringOfLonger_when_computingCommonTypeNamePrefix_should_trimToLastUppercase()
            throws Exception {
        // Shared = 10 ("BookAuthor"). Scan i=1..9: 'A' at 4 → wordBoundary=4.
        // Returns first.substring(0, 4) = "Book".
        Method method = reflectCommonTypeNamePrefix();
        String prefix = (String) method.invoke(null, List.of("BookAuthor", "BookAuthorRef"));
        assertThat(prefix).isEqualTo("Book");
    }

    @Test
    @DisplayName(
            "Given single type 'FooB', commonTypeNamePrefix returns 'Foo' (last inner CamelCase boundary 'B' at index 3)")
    void given_singleTypeEndingInUppercase_when_computingCommonTypeNamePrefix_should_returnPrefixBeforeLastWord()
            throws Exception {
        // "FooB" (4 chars), shared=4. Scan i=1..3: 'B' at 3 → wordBoundary=3. Returns "Foo".
        Method method = reflectCommonTypeNamePrefix();
        String prefix = (String) method.invoke(null, List.of("FooB"));
        assertThat(prefix).isEqualTo("Foo");
    }

    // -------------------------------------------------------------------------
    // emitObjectType SDL output — single resolves_type companion field
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given a type with field 'typeId' annotated with single resolves_type 'Book', emitObjectType should emit the companion field 'type: Book'")
    void given_singleResolvesType_when_emittingObjectType_should_includeCompanionFieldDerivedFromJsonName()
            throws Exception {
        // Arrange — proto field name "type_id" → jsonName "typeId" → strip "Id" → "type"
        GraphQlFieldRule fieldAnnotation =
                GraphQlFieldRule.newBuilder().addResolvesType("Book").build();
        FieldOptions fieldOptions = FieldOptions.newBuilder()
                .setExtension(GraphQlProto.graphqlField, fieldAnnotation)
                .build();

        FileDescriptor fileDescriptor =
                buildFileDescriptor("shop.proto", "ShopItem", "type_id", Type.TYPE_INT64, fieldOptions);
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("ShopItem");

        GraphQlSchemaBuilder builder = createBuilder();
        Map<String, String> typeDefs = new HashMap<>();

        // Act — call emitObjectType directly to isolate the SDL companion-field emission
        Method emitMethod = reflectEmitObjectType();
        emitMethod.invoke(builder, descriptor, typeDefs, new HashSet<>(), 0, Map.of());

        // Assert
        String emittedSdl = typeDefs.get("ShopItem");
        assertThat(emittedSdl)
                .as("SDL type block should contain companion resolver 'type: Book'")
                .contains("type: Book");
    }

    @Test
    @DisplayName(
            "Given a type with field 'location' (no Id suffix) annotated with single resolves_type, emitObjectType should emit the companion field using the field's JSON name unchanged")
    void given_singleResolvesTypeOnNonIdField_when_emittingObjectType_should_useJsonNameDirectly() throws Exception {
        // Arrange — proto field name "location" → jsonName "location" → no "Id" suffix to strip
        GraphQlFieldRule fieldAnnotation =
                GraphQlFieldRule.newBuilder().addResolvesType("Location").build();
        FieldOptions fieldOptions = FieldOptions.newBuilder()
                .setExtension(GraphQlProto.graphqlField, fieldAnnotation)
                .build();

        FileDescriptor fileDescriptor =
                buildFileDescriptor("route.proto", "Shelf", "location", Type.TYPE_STRING, fieldOptions);
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("Shelf");

        GraphQlSchemaBuilder builder = createBuilder();
        Map<String, String> typeDefs = new HashMap<>();

        // Act
        Method emitMethod = reflectEmitObjectType();
        emitMethod.invoke(builder, descriptor, typeDefs, new HashSet<>(), 0, Map.of());

        // Assert
        String emittedSdl = typeDefs.get("Shelf");
        assertThat(emittedSdl)
                .as("SDL should contain companion resolver 'location: Location'")
                .contains("location: Location");
    }

    // -------------------------------------------------------------------------
    // emitObjectType SDL output — multi resolves_type companion fields
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given multi resolves_type with no common prefix, emitObjectType should lower-case the full type name for each companion field")
    void
            given_multiResolvesTypeWithNoCommonPrefix_when_emittingObjectType_should_emitLowercasedTypeNamesAsCompanionFields()
                    throws Exception {
        // Arrange — "Alpha" and "Beta" share zero chars → prefix = "".
        // Companion fields: "Alpha" → "alpha", "Beta" → "beta".
        GraphQlFieldRule fieldAnnotation = GraphQlFieldRule.newBuilder()
                .addResolvesType("Alpha")
                .addResolvesType("Beta")
                .build();
        FieldOptions fieldOptions = FieldOptions.newBuilder()
                .setExtension(GraphQlProto.graphqlField, fieldAnnotation)
                .build();

        FileDescriptor fileDescriptor =
                buildFileDescriptor("killmail.proto", "Killmail", "entity_id", Type.TYPE_INT64, fieldOptions);
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("Killmail");

        GraphQlSchemaBuilder builder = createBuilder();
        Map<String, String> typeDefs = new HashMap<>();

        // Act
        Method emitMethod = reflectEmitObjectType();
        emitMethod.invoke(builder, descriptor, typeDefs, new HashSet<>(), 0, Map.of());

        // Assert
        String emittedSdl = typeDefs.get("Killmail");
        assertThat(emittedSdl).as("SDL should contain 'alpha: Alpha'").contains("alpha: Alpha");
        assertThat(emittedSdl).as("SDL should contain 'beta: Beta'").contains("beta: Beta");
    }

    @Test
    @DisplayName(
            "Given 'EveChar' and 'EveRef' sharing prefix 'Eve', emitObjectType should produce companion fields 'char: EveChar' and 'ref: EveRef'")
    void given_multiResolvesTypeWithTrimmedPrefix_when_emittingObjectType_should_stripComputedPrefixFromEachTypeName()
            throws Exception {
        // "EveChar" and "EveRef" share 3 chars ("Eve"); 'C' != 'R' at index 3.
        // Scan i=1..2: 'v','e' → no inner uppercase → wordBoundary=-1 → prefix = "Eve".
        // Stripped: "EveChar"[3] = "Char" → "char", "EveRef"[3] = "Ref" → "ref".
        GraphQlFieldRule fieldAnnotation = GraphQlFieldRule.newBuilder()
                .addResolvesType("EveChar")
                .addResolvesType("EveRef")
                .build();
        FieldOptions fieldOptions = FieldOptions.newBuilder()
                .setExtension(GraphQlProto.graphqlField, fieldAnnotation)
                .build();

        FileDescriptor fileDescriptor =
                buildFileDescriptor("entity.proto", "EveItem", "ref_id", Type.TYPE_INT64, fieldOptions);
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("EveItem");

        GraphQlSchemaBuilder builder = createBuilder();
        Map<String, String> typeDefs = new HashMap<>();

        // Act
        Method emitMethod = reflectEmitObjectType();
        emitMethod.invoke(builder, descriptor, typeDefs, new HashSet<>(), 0, Map.of());

        // Assert — prefix "Eve" stripped from each type name
        String emittedSdl = typeDefs.get("EveItem");
        assertThat(emittedSdl)
                .as("SDL should contain 'char: EveChar' (prefix 'Eve' stripped)")
                .contains("char: EveChar");
        assertThat(emittedSdl)
                .as("SDL should contain 'ref: EveRef' (prefix 'Eve' stripped)")
                .contains("ref: EveRef");
    }

    // -------------------------------------------------------------------------
    // google.type.* scalar mapping — SDL declares LocalDate, LocalTime, Decimal, etc.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given a message with google.type.Date field with DEFAULTS mapping, emitObjectType should declare scalar LocalDate in SDL and type field as LocalDate")
    void given_googleTypeDateFieldWithDefaults_when_emittingObjectType_should_declareLocalDateScalar()
            throws Exception {
        // ARRANGE — Message with Date field; DEFAULTS keeps mapping active
        FileDescriptor fileDescriptor = buildFileDescriptorWithGoogleTypeField(
                "event.proto", "Event", "day", com.google.type.Date.getDescriptor());
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("Event");

        GraphQlSchemaBuilder builder = createBuilder();
        Map<String, String> typeDefs = new HashMap<>();

        // ACT — Emit SDL with DEFAULTS active (date=true)
        Method emitMethod = reflectEmitObjectType();
        emitMethod.invoke(builder, descriptor, typeDefs, new HashSet<>(), 0, Map.of());

        // ASSERT — SDL should reference LocalDate scalar
        String emittedSdl = typeDefs.get("Event");
        assertThat(emittedSdl)
                .as("SDL should declare day field as LocalDate scalar when google.type.Date is mapped")
                .contains("day: LocalDate");
    }

    @Test
    @DisplayName(
            "Given an application codec for a field's message, emitObjectType should type the field by the codec's JSON shape")
    void given_customCodec_when_emittingObjectType_should_typeFieldByJsonShape() throws Exception {
        FileDescriptor fileDescriptor = buildFileDescriptorWithGoogleTypeField(
                "event.proto", "Event", "day", com.google.type.Date.getDescriptor());
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("Event");
        Method emitMethod = reflectEmitObjectType();

        Map<dev.appiaworks.protobuf.extras.ProtoScalarCodec.JsonShape, String> expected = Map.of(
                dev.appiaworks.protobuf.extras.ProtoScalarCodec.JsonShape.STRING, "day: String",
                dev.appiaworks.protobuf.extras.ProtoScalarCodec.JsonShape.NUMBER, "day: Float",
                dev.appiaworks.protobuf.extras.ProtoScalarCodec.JsonShape.BOOLEAN, "day: Boolean",
                dev.appiaworks.protobuf.extras.ProtoScalarCodec.JsonShape.ANY, "day: JSON");

        for (var entry : expected.entrySet()) {
            ProtoJsonMapping.install(
                    ProtoJsonMapping.DEFAULTS,
                    java.util.List.of(dev.appiaworks.protobuf.extras.ProtoScalarCodec.of(
                            com.google.type.Date.getDefaultInstance(),
                            entry.getKey(),
                            com.google.type.Date::getYear,
                            json -> com.google.type.Date.getDefaultInstance())));
            Map<String, String> typeDefs = new HashMap<>();

            emitMethod.invoke(createBuilder(), descriptor, typeDefs, new HashSet<>(), 0, Map.of());

            assertThat(typeDefs.get("Event")).as("shape %s", entry.getKey()).contains(entry.getValue());
        }
    }

    @Test
    @DisplayName(
            "Given a codec whose schema is INTEGER or names a scalar, emitObjectType should type the field Int for int32, Float otherwise, or by the name")
    void given_codecSchema_when_emittingObjectType_should_typeFieldByHint() throws Exception {
        FileDescriptor fileDescriptor = buildFileDescriptorWithGoogleTypeField(
                "event.proto", "Event", "day", com.google.type.Date.getDescriptor());
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("Event");
        Method emitMethod = reflectEmitObjectType();

        Map<ScalarSchema, String> expected = Map.of(
                ScalarSchema.integer().withFormat("int32"), "day: Int",
                ScalarSchema.integer(), "day: Float",
                ScalarSchema.string().withGraphQlName("Year"), "day: Year");

        for (var entry : expected.entrySet()) {
            ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, java.util.List.of(yearCodec(entry.getKey())));
            Map<String, String> typeDefs = new HashMap<>();

            emitMethod.invoke(createBuilder(), descriptor, typeDefs, new HashSet<>(), 0, Map.of());

            assertThat(typeDefs.get("Event")).as("schema %s", entry.getKey()).contains(entry.getValue());
        }
    }

    @Test
    @DisplayName(
            "Given a codec that names a GraphQL scalar, the generated SDL should declare it once and graphql-java should accept it with the scalar wired")
    void given_namedCodecScalar_when_sdlBuilt_should_declareItAndWireIntoAnExecutableSchema() {
        ScalarSchema year = ScalarSchema.string().withGraphQlName("Year").withDescription("A calendar year");
        ProtoJsonMapping.install(
                ProtoJsonMapping.DEFAULTS,
                java.util.List.of(
                        yearCodec(year),
                        // A second message sharing the name and the schema: still one scalar.
                        dev.appiaworks.protobuf.extras.ProtoScalarCodec.ofString(
                                        com.google.type.LatLng.getDefaultInstance(),
                                        ll -> "",
                                        text -> com.google.type.LatLng.getDefaultInstance())
                                .withSchema(year)));
        GraphQlSchemaBuilder builder = createBuilder();

        builder.rebuild();
        String sdl = builder.getGeneratedSdl();

        assertThat(sdl).containsOnlyOnce("scalar Year\n");
        graphql.schema.idl.TypeDefinitionRegistry types = new graphql.schema.idl.SchemaParser()
                .parse(sdl + "\ntype Probe { year: Year }\nextend type Query { probe: Probe }\n");
        graphql.schema.idl.RuntimeWiring.Builder wiring = graphql.schema.idl.RuntimeWiring.newRuntimeWiring()
                .scalar(graphql.scalars.ExtendedScalars.Json)
                .scalar(graphql.scalars.ExtendedScalars.DateTime);
        new dev.appiaworks.shiguchi.graphql.scalars.GatewayCodecScalars(
                        new org.springframework.beans.factory.support.StaticListableBeanFactory()
                                .getBeanProvider(dev.appiaworks.protobuf.extras.ProtoScalarCodec.class))
                .addScalars(wiring);
        wiring.scalar(graphql.schema.GraphQLScalarType.newScalar()
                .name("Void")
                .coercing(graphql.scalars.ExtendedScalars.Json.getCoercing())
                .build());
        wiring.scalar(dev.appiaworks.shiguchi.graphql.scalars.GatewayGoogleTypeScalars.localDate())
                .scalar(dev.appiaworks.shiguchi.graphql.scalars.GatewayGoogleTypeScalars.localTime())
                .scalar(dev.appiaworks.shiguchi.graphql.scalars.GatewayGoogleTypeScalars.decimal());

        graphql.schema.GraphQLSchema schema =
                new graphql.schema.idl.SchemaGenerator().makeExecutableSchema(types, wiring.build());

        assertThat(((graphql.schema.GraphQLScalarType) schema.getType("Year")).getDescription())
                .isEqualTo("A calendar year");
    }

    private static dev.appiaworks.protobuf.extras.ProtoScalarCodec yearCodec(ScalarSchema schema) {
        return dev.appiaworks.protobuf.extras.ProtoScalarCodec.of(
                        com.google.type.Date.getDefaultInstance(),
                        schema.shape(),
                        com.google.type.Date::getYear,
                        json -> com.google.type.Date.getDefaultInstance())
                .withSchema(schema);
    }

    @Test
    @DisplayName(
            "Given a message with google.type.Date field with PROTO3 mapping, emitObjectType should emit Date as object type with year/month/day")
    void given_googleTypeDateFieldWithProto3_when_emittingObjectType_should_emitDateObjectType() throws Exception {
        // ARRANGE — Switch to PROTO3 (all mappings off)
        ProtoJsonMapping.install(ProtoJsonMapping.PROTO3);

        FileDescriptor fileDescriptor = buildFileDescriptorWithGoogleTypeField(
                "event.proto", "Event", "day", com.google.type.Date.getDescriptor());
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("Event");

        GraphQlSchemaBuilder builder = createBuilder();
        Map<String, String> typeDefs = new HashMap<>();

        // ACT — Emit SDL with PROTO3 mapping (date=false)
        Method emitMethod = reflectEmitObjectType();
        emitMethod.invoke(builder, descriptor, typeDefs, new HashSet<>(), 0, Map.of());

        // ASSERT — SDL should emit Date object type with nested fields
        String emittedSdl = typeDefs.get("Event");
        assertThat(emittedSdl)
                .as("SDL should reference Date object type when google.type.Date is not mapped")
                .contains("day: Date");
        assertThat(typeDefs).containsKey("Date");
        String dateSdl = typeDefs.get("Date");
        assertThat(dateSdl).as("Date type should have year, month, day fields").contains("year:", "month:", "day:");
    }

    @Test
    @DisplayName(
            "Given a message with google.type.TimeOfDay field with DEFAULTS, emitObjectType should declare LocalTime scalar")
    void given_googleTypeTimeOfDayFieldWithDefaults_when_emittingObjectType_should_declareLocalTimeScalar()
            throws Exception {
        // ARRANGE
        FileDescriptor fileDescriptor = buildFileDescriptorWithGoogleTypeField(
                "event.proto", "Event", "opens", com.google.type.TimeOfDay.getDescriptor());
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("Event");

        GraphQlSchemaBuilder builder = createBuilder();
        Map<String, String> typeDefs = new HashMap<>();

        // ACT
        Method emitMethod = reflectEmitObjectType();
        emitMethod.invoke(builder, descriptor, typeDefs, new HashSet<>(), 0, Map.of());

        // ASSERT
        String emittedSdl = typeDefs.get("Event");
        assertThat(emittedSdl)
                .as("SDL should declare opens field as LocalTime scalar when google.type.TimeOfDay is mapped")
                .contains("opens: LocalTime");
    }

    @Test
    @DisplayName(
            "Given a message with google.type.Decimal field with DEFAULTS, emitObjectType should declare Decimal scalar")
    void given_googleTypeDecimalFieldWithDefaults_when_emittingObjectType_should_declareDecimalScalar()
            throws Exception {
        // ARRANGE
        FileDescriptor fileDescriptor = buildFileDescriptorWithGoogleTypeField(
                "product.proto", "Product", "price", com.google.type.Decimal.getDescriptor());
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("Product");

        GraphQlSchemaBuilder builder = createBuilder();
        Map<String, String> typeDefs = new HashMap<>();

        // ACT
        Method emitMethod = reflectEmitObjectType();
        emitMethod.invoke(builder, descriptor, typeDefs, new HashSet<>(), 0, Map.of());

        // ASSERT
        String emittedSdl = typeDefs.get("Product");
        assertThat(emittedSdl)
                .as("SDL should declare price field as Decimal scalar when google.type.Decimal is mapped")
                .contains("price: Decimal");
    }

    @Test
    @DisplayName(
            "Given a message with google.type.DateTime field with DEFAULTS, emitObjectType should declare it as String scalar")
    void given_googleTypeDateTimeFieldWithDefaults_when_emittingObjectType_should_declareStringScalar()
            throws Exception {
        // ARRANGE
        FileDescriptor fileDescriptor = buildFileDescriptorWithGoogleTypeField(
                "event.proto", "Event", "startsAt", com.google.type.DateTime.getDescriptor());
        Descriptor descriptor = fileDescriptor.findMessageTypeByName("Event");

        GraphQlSchemaBuilder builder = createBuilder();
        Map<String, String> typeDefs = new HashMap<>();

        // ACT
        Method emitMethod = reflectEmitObjectType();
        emitMethod.invoke(builder, descriptor, typeDefs, new HashSet<>(), 0, Map.of());

        // ASSERT — DateTime maps to String scalar (not a named scalar type)
        String emittedSdl = typeDefs.get("Event");
        assertThat(emittedSdl)
                .as("SDL should declare startsAt field as String scalar when google.type.DateTime is mapped")
                .contains("startsAt: String");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static Method reflectCommonTypeNamePrefix() throws Exception {
        Method m = GraphQlSchemaBuilder.class.getDeclaredMethod("commonTypeNamePrefix", List.class);
        m.setAccessible(true);
        return m;
    }

    /**
     * Reflects the private {@code emitObjectType} method.
     * Signature: (Descriptor, Map, Set, int, Map)
     */
    private static Method reflectEmitObjectType() throws Exception {
        Method m = GraphQlSchemaBuilder.class.getDeclaredMethod(
                "emitObjectType",
                com.google.protobuf.Descriptors.Descriptor.class,
                Map.class,
                java.util.Set.class,
                int.class,
                Map.class);
        m.setAccessible(true);
        return m;
    }

    /** A contributor that declares one subscription field and adds the same type definition twice. */
    private static GraphQlContributor extraFieldContributor() {
        return new GraphQlContributor() {
            @Override
            public void contributeSchema(GraphQlContributor.Schema schema) {
                schema.type("Extra", "type Extra {\n  a: String\n}\n");
                schema.type("Extra", "type Extra {\n  a: String\n}\n");
                schema.subscriptionField("extra(topic: String!): Extra");
            }

            @Override
            public void contributeDataFetchers(graphql.schema.GraphQLCodeRegistry.Builder registry) {}
        };
    }

    @Test
    @DisplayName(
            "Given a contributor declaring a subscription field, when the SDL is built, the field should appear under type Subscription")
    void given_contributorSubscriptionField_when_sdlBuilt_should_declareFieldUnderSubscription() {
        GraphQlSchemaBuilder builder = createBuilder();
        builder.setContributors(List.of(extraFieldContributor()));

        builder.rebuild();

        assertThat(builder.getGeneratedSdl()).contains("type Subscription {\n  extra(topic: String!): Extra\n}\n");
    }

    @Test
    @DisplayName(
            "Given a contributor adding the same type twice, when the SDL is built, the type should be declared exactly once")
    void given_contributorAddingSameTypeTwice_when_sdlBuilt_should_declareTypeExactlyOnce() {
        GraphQlSchemaBuilder builder = createBuilder();
        builder.setContributors(List.of(extraFieldContributor()));

        builder.rebuild();

        assertThat(builder.getGeneratedSdl()).containsOnlyOnce("type Extra {\n  a: String\n}\n");
    }

    private GraphQlSchemaBuilder createBuilder() {
        return new GraphQlSchemaBuilder(
                registry,
                new RelayConnectionTransformer(),
                new GraphQlPaginationArgInjector(),
                new GatewayProperties());
    }

    /**
     * Builds a single-message FileDescriptor containing one field with the given FieldOptions.
     * Uses graphql.proto as a dependency for the (shiguchi.api.graphql_field) extension at field number 50101.
     */
    private static FileDescriptor buildFileDescriptor(
            String fileName, String messageName, String fieldName, Type fieldType, FieldOptions fieldOptions)
            throws Exception {

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName(fileName)
                .setSyntax("proto3")
                .addDependency("shiguchi/api/graphql.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName(messageName)
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setType(fieldType)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setOptions(fieldOptions)
                                .build())
                        .build())
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[] {GraphQlProto.getDescriptor()});
    }

    /**
     * Builds a single-message FileDescriptor with a field of a google.type.* message type.
     * Declares the appropriate google.type proto as a dependency.
     */
    private static FileDescriptor buildFileDescriptorWithGoogleTypeField(
            String fileName,
            String messageName,
            String fieldName,
            com.google.protobuf.Descriptors.Descriptor googleTypeDescriptor)
            throws Exception {

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName(fileName)
                .setSyntax("proto3")
                .addDependency("shiguchi/api/graphql.proto")
                .addDependency(googleTypeDescriptor.getFile().getName())
                .addMessageType(DescriptorProto.newBuilder()
                        .setName(messageName)
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(googleTypeDescriptor.getFullName())
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .build();

        return FileDescriptor.buildFrom(
                fileProto, new FileDescriptor[] {GraphQlProto.getDescriptor(), googleTypeDescriptor.getFile()});
    }
}
