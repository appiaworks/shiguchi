package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import dev.appiaworks.protobuf.extras.serde.ProjectionNode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.SelectedField;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link ProjectionNode} from the GraphQL selection set so the dispatch path
 * converts only the composite subtrees the query actually selected (scalar fields are
 * always converted — see ProtoMapConverter.projectToMap for the safety rationale).
 *
 * Remappings between the GraphQL shape and the proto response shape:
 *  - response pluck: the selection applies to a plucked sub-field, so the tree is wrapped
 *    under that field's JSON name;
 *  - Relay connections: {@code edges/node/*} selections are grafted onto the proto items
 *    field, and {@code page_info} is always included in full because
 *    RelayConnectionTransformer reads nextPageToken/totalCount from it.
 *
 * Disable with {@code shiguchi.graphql.selection-projection: false} to always convert
 * full response trees (pre-0.2 behaviour).
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GraphQlSelectionProjector {

    private final RelayConnectionTransformer relayTransformer;
    private final boolean enabled;

    @Autowired
    public GraphQlSelectionProjector(RelayConnectionTransformer relayTransformer, GatewayProperties properties) {
        this(
                relayTransformer,
                properties == null
                        || properties.getGraphql() == null
                        || properties.getGraphql().isSelectionProjection());
    }

    public GraphQlSelectionProjector(RelayConnectionTransformer relayTransformer, boolean enabled) {
        this.relayTransformer = relayTransformer;
        this.enabled = enabled;
    }

    /**
     * Returns the projection for this fetch, or null when projection is disabled or the
     * selection cannot be mapped safely (in which case the full tree is converted).
     */
    public ProjectionNode build(DataFetchingEnvironment env, RouteDefinition route, Descriptor outputDescriptor) {
        if (!enabled || outputDescriptor == null) return null;

        List<SelectedField> fields = env.getSelectionSet().getFields();
        if (fields.isEmpty()) return null;

        ProjectionNode selection = ProjectionNode.root();
        for (SelectedField field : fields) {
            selection.addPath(field.getQualifiedName());
        }

        // Pluck and connection transform are mutually exclusive in the fetcher: when a pluck
        // is configured it returns before the relay transform runs, and the selection then
        // describes the plucked sub-field's type.
        String pluck = route.getGraphQlResponsePluck();
        if (pluck != null && !pluck.isEmpty()) {
            ProjectionNode wrapped = ProjectionNode.root();
            wrapped.setChild(pluck, selection);
            return wrapped;
        }
        if (relayTransformer.isConnectionCandidate(outputDescriptor)) {
            return remapConnection(selection, outputDescriptor);
        }
        return selection;
    }

    /**
     * Connection selections navigate synthetic edges/node/pageInfo types; the proto response
     * has the items field and page_info. Graft node's subtree onto the items field and keep
     * page_info whole (the transform reads it even when the client selects nothing from it).
     */
    private ProjectionNode remapConnection(ProjectionNode selection, Descriptor outputDescriptor) {
        ProjectionNode remapped = ProjectionNode.root();

        ProjectionNode edges = selection.lookup("edges");
        ProjectionNode node = edges != null ? edges.lookup("node") : null;
        String itemsJsonName = toJsonName(relayTransformer.getEdgesFieldName(outputDescriptor));
        if (node != null) {
            remapped.setChild(itemsJsonName, node);
        } else if (edges != null) {
            // edges selected but only cursor/__typename: items still drive edge count
            remapped.setChild(itemsJsonName, ProjectionNode.root());
        }

        remapped.setChild("pageInfo", ProjectionNode.includeAll());
        return remapped;
    }

    private static String toJsonName(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
