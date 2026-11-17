package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.util.List;
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
 * BDD-style unit tests for GatewayDataFetcherRegistrar, registering relationship fetchers from the
 * schema builder's relationship list, by spying on the GraphQLCodeRegistry.Builder and capturing
 * the registered FieldCoordinates.
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

    @Mock
    private GraphQlSchemaBuilder schemaBuilder;

    private GatewayProperties props;

    @BeforeEach
    void setUp() {
        props = new GatewayProperties();
        registrar = new GatewayDataFetcherRegistrar(registry, dataFetcher, props, schemaBuilder);
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

    private static final String SDL = """
            type Query { ping: String }
            type Order { id: ID  customerId: ID  customer: Customer }
            type Customer { id: ID  regionId: ID  region: Region }
            type Region { id: ID }
            """;

    private List<String> register(TypeDefinitionRegistry types, List<GraphQlSchemaBuilder.Relationship> rels) {
        when(schemaBuilder.getRelationships()).thenReturn(rels);
        GraphQLCodeRegistry.Builder spyBuilder = spy(GraphQLCodeRegistry.newCodeRegistry());
        registrar.register(spyBuilder, types);
        verify(spyBuilder, atLeastOnce()).dataFetcher(coordinatesCaptor.capture(), fetcherCaptor.capture());
        return coordinatesCaptor.getAllValues().stream()
                .map(c -> c.getTypeName() + "." + c.getFieldName())
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName(
            "Given relationships on a root-level and a nested message, when registered, should wire a fetcher for each parent type")
    void given_relationshipsOnRootAndNestedTypes_when_registered_should_wireEachOne() {
        TypeDefinitionRegistry types = new SchemaParser().parse(SDL);

        List<String> coordinates = register(
                types,
                List.of(
                        new GraphQlSchemaBuilder.Relationship("Order", "customer", "customerId", "Customer"),
                        new GraphQlSchemaBuilder.Relationship("Customer", "region", "regionId", "Region")));

        assertThat(coordinates).contains("Order.customer", "Customer.region");
        assertThat(fetcherCaptor.getAllValues()).anyMatch(GrpcRelationshipDataFetcher.class::isInstance);
    }

    @Test
    @DisplayName(
            "Given a relationship whose field is not in the schema being built, when registered, should skip it without failing")
    void given_relationshipMissingFromSchema_when_registered_should_skipIt() {
        TypeDefinitionRegistry types = new SchemaParser().parse(SDL);

        List<String> coordinates = register(
                types,
                List.of(
                        new GraphQlSchemaBuilder.Relationship("Order", "ghost", "ghostId", "Customer"),
                        new GraphQlSchemaBuilder.Relationship("Missing", "customer", "customerId", "Customer"),
                        new GraphQlSchemaBuilder.Relationship("Order", "customer", "customerId", "Customer")));

        assertThat(coordinates).contains("Order.customer").doesNotContain("Order.ghost", "Missing.customer");
    }

    @Test
    @DisplayName("Given on-error NULL, when registered, the relationship fetcher should resolve a failed load to null")
    void given_onErrorNull_when_registered_should_passModeToFetcher() throws Exception {
        props.getGraphql().getDataLoader().setOnError(DataLoaderConfig.OnError.NULL);
        TypeDefinitionRegistry types = new SchemaParser().parse(SDL);

        register(types, List.of(new GraphQlSchemaBuilder.Relationship("Order", "customer", "customerId", "Customer")));

        Object mode = ReflectionTestUtils.getField(fetcherCaptor.getValue(), "onError");
        assertThat(mode).isEqualTo(DataLoaderConfig.OnError.NULL);
    }
}
