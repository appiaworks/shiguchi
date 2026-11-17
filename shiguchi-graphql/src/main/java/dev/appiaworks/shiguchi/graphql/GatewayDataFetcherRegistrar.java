package dev.appiaworks.shiguchi.graphql;

import com.netflix.graphql.dgs.DgsCodeRegistry;
import com.netflix.graphql.dgs.DgsComponent;
import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Registers DynamicGraphQlDataFetcher with DGS for every GraphQL field produced by
 * the gateway's route registry, and registers relationship resolvers for fields annotated
 * with resolves_type.
 *
 * DGS discovers @DgsCodeRegistry methods at schema-build time and calls them after
 * @DgsTypeDefinitionRegistry, so all routes are already loaded when this runs.
 * Without this registration DGS uses its default trivial fetcher, which returns null.
 *
 * The relationship resolvers come from {@link GraphQlSchemaBuilder#getRelationships()}, so every
 * {@code resolves_type} field the schema declares gets one, nested types included. They delegate to DGS DataLoaders for batch loading of referenced types.
 */
@DgsComponent
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewayDataFetcherRegistrar {

    private static final Logger log = LoggerFactory.getLogger(GatewayDataFetcherRegistrar.class);

    private final GrpcRouteRegistry registry;
    private final DynamicGraphQlDataFetcher dataFetcher;
    private final GatewayProperties gatewayProperties;
    private final GraphQlSchemaBuilder schemaBuilder;

    /** Beans that add fields of their own (the push module's subscription fields); optional. */
    @Autowired(required = false)
    private List<GraphQlContributor> contributors;

    public GatewayDataFetcherRegistrar(
            GrpcRouteRegistry registry,
            DynamicGraphQlDataFetcher dataFetcher,
            GatewayProperties gatewayProperties,
            GraphQlSchemaBuilder schemaBuilder) {
        this.schemaBuilder = schemaBuilder;
        this.registry = registry;
        this.dataFetcher = dataFetcher;
        this.gatewayProperties = gatewayProperties;
    }

    @DgsCodeRegistry
    public GraphQLCodeRegistry.Builder register(
            GraphQLCodeRegistry.Builder codeRegistryBuilder, TypeDefinitionRegistry typeDefinitionRegistry) {

        int count = 0;
        for (RouteDefinition route : registry.allRoutes()) {
            if (!route.hasGraphQlBinding()) continue;
            String parentType = switch (route.getGraphQlOperationType()) {
                case QUERY -> "Query";
                case MUTATION -> "Mutation";
                case SUBSCRIPTION -> "Subscription";
                case RESOLVER -> {
                    String pt = route.getGraphQlParentType();
                    if (pt == null || pt.isBlank()) {
                        log.warn(
                                "RESOLVER route '{}' has no parent_type; registering on Query", route.getGraphQlName());
                        yield "Query";
                    }
                    yield pt;
                }
            };
            codeRegistryBuilder.dataFetcher(
                    FieldCoordinates.coordinates(parentType, route.getGraphQlName()), dataFetcher);
            count++;
        }
        log.info("Registered {} GraphQL data fetchers", count);

        if (contributors != null) {
            for (GraphQlContributor contributor : contributors) contributor.contributeDataFetchers(codeRegistryBuilder);
        }

        // DataLoader-backed resolvers for the resolves_type companion fields the schema builder
        // emitted, on nested types too. A relationship whose type or field the schema being built
        // lacks (the registry is newer or older than the builder's snapshot) is skipped.
        int relCount = 0;
        DataLoaderConfig.OnError onError =
                gatewayProperties.getGraphql().getDataLoader().getOnError();
        for (GraphQlSchemaBuilder.Relationship rel : schemaBuilder.getRelationships()) {
            var parent = typeDefinitionRegistry.getType(rel.parentType()).orElse(null);
            boolean present = parent instanceof ObjectTypeDefinition object
                    && object.getFieldDefinitions().stream()
                            .anyMatch(f -> f.getName().equals(rel.fieldName()));
            if (!present) {
                log.debug(
                        "Skipping relationship {}.{}: not in the schema being built",
                        rel.parentType(),
                        rel.fieldName());
                continue;
            }
            codeRegistryBuilder.dataFetcher(
                    FieldCoordinates.coordinates(rel.parentType(), rel.fieldName()),
                    new GrpcRelationshipDataFetcher(rel.idJsonName(), rel.loaderName(), onError, rel.many()));
            relCount++;
            log.debug(
                    "Registered relationship resolver: {}.{} -> DataLoader[{}]",
                    rel.parentType(),
                    rel.fieldName(),
                    rel.loaderName());
        }
        if (relCount > 0) log.info("Registered {} GraphQL relationship resolvers", relCount);

        return codeRegistryBuilder;
    }
}
