package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.proto.api.GraphQlFieldRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * BDD-style unit tests for GatewayDataFetcherRegistrar, focusing on the resolver field-name
 * derivation logic introduced by the multi-type resolves_type feature.
 *
 * <p>The {@code commonTypePrefix} private static helper is tested via reflection for its
 * algorithmic boundary conditions. The {@code register()} method is tested by spying on the
 * GraphQLCodeRegistry.Builder and capturing registered FieldCoordinates — asserting field names
 * without requiring a fully built GraphQL schema.
 */
@ExtendWith(MockitoExtension.class)
class GatewayDataFetcherRegistrarTest {

    @Mock
    private GrpcRouteRegistry registry;

    @Mock
    private DynamicGraphQlDataFetcher dataFetcher;

    private GatewayDataFetcherRegistrar registrar;

    @Captor
    private ArgumentCaptor<FieldCoordinates> coordinatesCaptor;

    @Captor
    private ArgumentCaptor<DataFetcher<?>> fetcherCaptor;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties();
        registrar = new GatewayDataFetcherRegistrar(registry, dataFetcher, props);
    }

    @Test
    @DisplayName(
            "Given a registrar with a push-style contributor, when register is called, the contributor should add its data fetchers once")
    void given_contributorRegistered_when_registered_should_delegateDataFetcherContributionOnce() {
        GraphQlContributor contributor = mock(GraphQlContributor.class);
        ReflectionTestUtils.setField(registrar, "contributors", List.of(contributor));
        GraphQLCodeRegistry.Builder spyBuilder = spy(GraphQLCodeRegistry.newCodeRegistry());

        registrar.register(spyBuilder, new TypeDefinitionRegistry());

        verify(contributor).contributeDataFetchers(spyBuilder);
    }

    private void stubEmptyRoutes() {
        when(registry.allRoutes()).thenReturn(List.of());
    }

    // -------------------------------------------------------------------------
    // commonTypePrefix — tested via reflection (private static, pure algorithm)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Given an empty type list, commonTypePrefix should return empty string")
    void given_emptyTypeList_when_computingCommonPrefix_should_returnEmptyString() throws Exception {
        Method method = reflectCommonTypePrefix();
        String prefix = (String) method.invoke(null, List.of());
        assertThat(prefix).isEmpty();
    }

    @Test
    @DisplayName("Given a single type 'FooB', commonTypePrefix returns 'Foo' (last inner CamelCase boundary at 'B')")
    void given_singleTypeEndingInUppercase_when_computingCommonPrefix_should_returnPrefixBeforeLastWord()
            throws Exception {
        // "FooB" (4 chars): shared=4. Scan i=1..3: 'o','o','B'(upper) → wordBoundary=3.
        // Returns "Foo". (Single-type never uses this path in practice, but the algorithm is correct.)
        Method method = reflectCommonTypePrefix();

        String prefix = (String) method.invoke(null, List.of("FooB"));

        assertThat(prefix).isEqualTo("Foo");
    }

    @Test
    @DisplayName("Given two types whose first characters differ, commonTypePrefix should return empty string")
    void given_twoTypesWithNoSharedFirstChar_when_computingCommonPrefix_should_returnEmptyString() throws Exception {
        Method method = reflectCommonTypePrefix();
        // "Publisher" and "Review" share zero characters, so len=0 throughout.
        // The while-loop immediately exits (len=0), guard does not fire, returns "".
        String prefix = (String) method.invoke(null, List.of("Publisher", "Review"));
        assertThat(prefix).isEmpty();
    }

    @Test
    @DisplayName(
            "Given 'BookAuthor' and 'BookAuthorRef' where all chars of the shorter are shared, commonTypePrefix returns 'Book' (last inner CamelCase boundary)")
    void given_twoTypesWhereShorterIsSubstringOfLonger_when_computingCommonPrefix_should_trimToLastUppercase()
            throws Exception {
        Method method = reflectCommonTypePrefix();

        // "BookAuthor" (10 chars) and "BookAuthorRef" share all 10 chars.
        // Scan i=1..9 for uppercase: 'A' at 4 → wordBoundary=4.
        // Returns first.substring(0, 4) = "Book".
        String prefix = (String) method.invoke(null, List.of("BookAuthor", "BookAuthorRef"));
        assertThat(prefix).isEqualTo("Book");
    }

    @Test
    @DisplayName(
            "Given two types sharing no characters, commonTypePrefix should return empty string (no shared prefix to strip)")
    void given_typesWithZeroCommonChars_when_computingCommonPrefix_should_returnEmpty() throws Exception {
        Method method = reflectCommonTypePrefix();
        // "Alpha" starts with 'A', "Beta" starts with 'B' — zero shared characters.
        String prefix = (String) method.invoke(null, List.of("Alpha", "Beta"));
        assertThat(prefix).isEmpty();
    }

    // -------------------------------------------------------------------------
    // register() — single-type resolves_type
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given field 'type_id' with single resolves_type 'Book', register() should wire a fetcher at coordinate TestMsg.type")
    void given_fieldWithSingleResolvesType_when_registered_should_useProtoFieldNameDerivedResolverName()
            throws Exception {
        // Arrange — "type_id" → strip "_id" → "type_" → camelCase → "type"
        GraphQlFieldRule fieldAnnotation =
                GraphQlFieldRule.newBuilder().addResolvesType("Book").build();
        FieldOptions fieldOptions = FieldOptions.newBuilder()
                .setExtension(GraphQlProto.graphqlField, fieldAnnotation)
                .build();

        FileDescriptor fileDescriptor =
                buildSingleFieldDescriptor("test.proto", "TestMsg", "type_id", Type.TYPE_INT64, fieldOptions);

        ServiceEndpointContext ctx = stubContextWithFile("test.proto", fileDescriptor);
        stubEmptyRoutes();
        when(registry.allContexts()).thenReturn(List.of(ctx));

        GraphQLCodeRegistry.Builder spyBuilder = spy(GraphQLCodeRegistry.newCodeRegistry());

        // Act
        registrar.register(spyBuilder, new TypeDefinitionRegistry());

        // Assert
        verify(spyBuilder, atLeastOnce()).dataFetcher(coordinatesCaptor.capture(), fetcherCaptor.capture());
        List<String> registeredCoordinates = coordinatesCaptor.getAllValues().stream()
                .map(c -> c.getTypeName() + "." + c.getFieldName())
                .collect(Collectors.toList());
        assertThat(registeredCoordinates)
                .as("resolver for single resolves_type should be at 'TestMsg.type'")
                .contains("TestMsg.type");
    }

    @Test
    @DisplayName(
            "Given field 'location' (no _id suffix) with single resolves_type, register() should use the field name unchanged as the resolver name")
    void given_fieldWithoutIdSuffix_when_registered_should_useFieldNameAsResolverName() throws Exception {
        // Arrange — no "_id" suffix: snakeToCamel("location") stays "location"
        GraphQlFieldRule fieldAnnotation =
                GraphQlFieldRule.newBuilder().addResolvesType("Publisher").build();
        FieldOptions fieldOptions = FieldOptions.newBuilder()
                .setExtension(GraphQlProto.graphqlField, fieldAnnotation)
                .build();

        FileDescriptor fileDescriptor =
                buildSingleFieldDescriptor("loc.proto", "KillMail", "location", Type.TYPE_STRING, fieldOptions);

        ServiceEndpointContext ctx = stubContextWithFile("loc.proto", fileDescriptor);
        stubEmptyRoutes();
        when(registry.allContexts()).thenReturn(List.of(ctx));

        GraphQLCodeRegistry.Builder spyBuilder = spy(GraphQLCodeRegistry.newCodeRegistry());

        // Act
        registrar.register(spyBuilder, new TypeDefinitionRegistry());

        // Assert
        verify(spyBuilder, atLeastOnce()).dataFetcher(coordinatesCaptor.capture(), fetcherCaptor.capture());
        List<String> registeredCoordinates = coordinatesCaptor.getAllValues().stream()
                .map(c -> c.getTypeName() + "." + c.getFieldName())
                .collect(Collectors.toList());
        assertThat(registeredCoordinates)
                .as("resolver should be at 'KillMail.location' — no _id stripping")
                .contains("KillMail.location");
    }

    // -------------------------------------------------------------------------
    // register() — multi-type resolves_type (no common prefix → safest case)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given multi-type resolves_type with no common prefix, register() should lower-case the full type name for each resolver field")
    void given_multiTypesWithNoCommonPrefix_when_registered_should_lowercaseFullTypeNameForEachResolver()
            throws Exception {
        // Arrange — "Alpha" and "Beta" share zero characters → prefix = "".
        // Stripped values are the full type names; resolver names are fully lowercased first-char:
        // "Alpha" → "alpha", "Beta" → "beta".
        GraphQlFieldRule fieldAnnotation = GraphQlFieldRule.newBuilder()
                .addResolvesType("Alpha")
                .addResolvesType("Beta")
                .build();
        FieldOptions fieldOptions = FieldOptions.newBuilder()
                .setExtension(GraphQlProto.graphqlField, fieldAnnotation)
                .build();

        FileDescriptor fileDescriptor =
                buildSingleFieldDescriptor("multi.proto", "Route", "ref_id", Type.TYPE_INT64, fieldOptions);

        ServiceEndpointContext ctx = stubContextWithFile("multi.proto", fileDescriptor);
        stubEmptyRoutes();
        when(registry.allContexts()).thenReturn(List.of(ctx));

        GraphQLCodeRegistry.Builder spyBuilder = spy(GraphQLCodeRegistry.newCodeRegistry());

        // Act
        registrar.register(spyBuilder, new TypeDefinitionRegistry());

        // Assert
        verify(spyBuilder, atLeastOnce()).dataFetcher(coordinatesCaptor.capture(), fetcherCaptor.capture());
        List<String> registeredCoordinates = coordinatesCaptor.getAllValues().stream()
                .map(c -> c.getTypeName() + "." + c.getFieldName())
                .collect(Collectors.toList());

        assertThat(registeredCoordinates)
                .as("resolver for Alpha should be 'Route.alpha'")
                .contains("Route.alpha");
        assertThat(registeredCoordinates)
                .as("resolver for Beta should be 'Route.beta'")
                .contains("Route.beta");
    }

    @Test
    @DisplayName(
            "Given 'EveChar' and 'EveRef' sharing prefix 'Eve', register() should produce resolvers 'char' and 'ref'")
    void given_multiTypesWithCommonPrefix_when_registered_should_stripPrefixAndLowercaseForEachResolver()
            throws Exception {
        // "EveChar" and "EveRef" share 3 chars ("Eve"); 'C' != 'R' at index 3.
        // Scan i=1..2: 'v','e' → no inner uppercase → wordBoundary=-1 → prefix = "Eve".
        // Stripped: "EveChar".substring(3) = "Char" → "char"
        //           "EveRef".substring(3) = "Ref" → "ref"
        GraphQlFieldRule fieldAnnotation = GraphQlFieldRule.newBuilder()
                .addResolvesType("EveChar")
                .addResolvesType("EveRef")
                .build();
        FieldOptions fieldOptions = FieldOptions.newBuilder()
                .setExtension(GraphQlProto.graphqlField, fieldAnnotation)
                .build();

        FileDescriptor fileDescriptor =
                buildSingleFieldDescriptor("eve.proto", "Transaction", "entity_id", Type.TYPE_INT64, fieldOptions);

        ServiceEndpointContext ctx = stubContextWithFile("eve.proto", fileDescriptor);
        stubEmptyRoutes();
        when(registry.allContexts()).thenReturn(List.of(ctx));

        GraphQLCodeRegistry.Builder spyBuilder = spy(GraphQLCodeRegistry.newCodeRegistry());

        // Act
        registrar.register(spyBuilder, new TypeDefinitionRegistry());

        // Assert — prefix "Eve" stripped: "EveChar" → "char", "EveRef" → "ref"
        verify(spyBuilder, atLeastOnce()).dataFetcher(coordinatesCaptor.capture(), fetcherCaptor.capture());
        List<String> registeredCoordinates = coordinatesCaptor.getAllValues().stream()
                .map(c -> c.getTypeName() + "." + c.getFieldName())
                .collect(Collectors.toList());

        assertThat(registeredCoordinates)
                .as("resolver for EveChar should be 'Transaction.char' (prefix 'Eve' stripped)")
                .contains("Transaction.char");
        assertThat(registeredCoordinates)
                .as("resolver for EveRef should be 'Transaction.ref' (prefix 'Eve' stripped)")
                .contains("Transaction.ref");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Method reflectCommonTypePrefix() throws Exception {
        Method m = GatewayDataFetcherRegistrar.class.getDeclaredMethod("commonTypePrefix", List.class);
        m.setAccessible(true);
        return m;
    }

    private static ServiceEndpointContext stubContextWithFile(String fileName, FileDescriptor fileDescriptor) {
        ServiceEndpointContext ctx = mock(ServiceEndpointContext.class);
        when(ctx.getFileDescriptors()).thenReturn(Map.of(fileName, fileDescriptor));
        return ctx;
    }

    /**
     * Programmatically builds a FileDescriptor containing one message with one field.
     * Registers {@code graphql.proto} as a dependency so the (shiguchi.api.graphql_field) extension
     * at field number 50101 is properly resolved when the registrar reads field options.
     */
    private static FileDescriptor buildSingleFieldDescriptor(
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

        FileDescriptor graphqlDep = GraphQlProto.getDescriptor();
        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[] {graphqlDep});
    }
}
