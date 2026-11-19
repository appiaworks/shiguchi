package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.Objects;

/**
 * A batch loader declared as a Spring bean, for data the gateway cannot reach itself: a custom
 * Jackson or REST client, a database, an in-process service. It is a third source of the
 * {@link BatchLoaderPipeline} next to the gRPC and REST loaders, so it runs behind the same
 * de-duplication, {@code max-batch-size} chunking, bounded concurrency, error handling and (for a
 * by-id loader) cache, and it serves Federation {@code _entities} like any other loader.
 *
 * <p>Two shapes, built with the factories:
 * <ul>
 *   <li>{@link #byId(String, BatchLoader)}: the entities of {@code type} by id, a {@link BatchLoader}.
 *       The loader's name is the type.
 *   <li>{@link #grouped(String, String, GroupedBatchLoader.ItemSource)}: the children of {@code type}
 *       of many parents in one call. The source returns one flat list of child maps; each child carries
 *       its parent's key in the {@code groupBy} field and the pipeline groups them (a parent with no
 *       children gets an empty list, {@code max-children-per-key} applies). The loader's name is
 *       {@code Type#by:camel(groupBy)}, like the gRPC and REST one-to-many loaders; it has no entities by
 *       id, so it stays out of {@code _entities} and the caches.
 * </ul>
 *
 * <p>The maps are the JSON form of the entity ({@code Map<String, Object>} with the JSON field
 * names), exactly what a Jackson client reads. The GraphQL type must already exist: it comes from a
 * gRPC route or a REST service of the gateway. A bean supplies the data and, with a
 * {@link RelationshipDefinition}, the relationships; it does not derive a type from a Java class
 * or a JSON schema.
 *
 * <p>If the type is also served by a gRPC or REST loader the bean wins, with a warning logged once,
 * unless {@code shiguchi.graphql.data-loader.types.<Type>.endpoint} pins the type to an endpoint:
 * the pin names a service, so the service's loader is used and the bean is ignored.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public final class BatchLoaderDefinition {

    private final String type;
    private final String groupBy;
    private final BatchLoader loader;
    private final GroupedBatchLoader.ItemSource items;

    private BatchLoaderDefinition(
            String type, String groupBy, BatchLoader loader, GroupedBatchLoader.ItemSource items) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("A batch loader needs a type");
        this.type = type.trim();
        this.groupBy = groupBy;
        this.loader = loader;
        this.items = items;
    }

    /** The entities of {@code type} by id; missing ids are absent from the loader's map. */
    public static BatchLoaderDefinition byId(String type, BatchLoader loader) {
        return new BatchLoaderDefinition(type, null, Objects.requireNonNull(loader, "loader"), null);
    }

    /**
     * The children of {@code type} of many parents in one call. {@code groupBy} is the child field
     * that holds the parent's key (proto or JSON spelling); each child map must carry it.
     */
    public static BatchLoaderDefinition grouped(String type, String groupBy, GroupedBatchLoader.ItemSource items) {
        if (groupBy == null || groupBy.isBlank()) throw new IllegalArgumentException("A grouped loader needs groupBy");
        return new BatchLoaderDefinition(type, groupBy.trim(), null, Objects.requireNonNull(items, "items"));
    }

    /** The GraphQL type the loader serves. */
    public String type() {
        return type;
    }

    /** True for a one-to-many loader. */
    public boolean isGrouped() {
        return items != null;
    }

    /** The group field as the child maps spell it (camelCase), or null for a by-id loader. */
    public String groupByJsonName() {
        return groupBy == null ? null : GrpcBatchDataLoaderRegistrar.snakeToCamel(groupBy);
    }

    /** The name the pipeline knows it by: the type, or {@code Type#by:groupField}. */
    public String name() {
        return isGrouped() ? GrpcBatchDataLoaderRegistrar.groupedName(type, groupBy) : type;
    }

    /** The by-id loader; null for a grouped one. */
    BatchLoader loader() {
        return loader;
    }

    /** The flat child source; null for a by-id one. */
    GroupedBatchLoader.ItemSource items() {
        return items;
    }
}
