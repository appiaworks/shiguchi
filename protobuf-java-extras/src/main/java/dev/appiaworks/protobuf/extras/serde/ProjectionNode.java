package dev.appiaworks.protobuf.extras.serde;

import java.util.HashMap;
import java.util.Map;

/**
 * Selection tree for projected DynamicMessage → Map conversion (GraphQL path).
 *
 * Keys are JSON (camelCase) field names, matching the keys produced by toMap()/dynamicToMap().
 * A node's children name the composite fields to descend into; {@link #includeAll()} marks a
 * subtree that must be converted in full (e.g. pagination metadata a later transform reads).
 *
 * Projection semantics are defined by {@link ProtoMapConverter#projectToMap}: scalar-typed
 * fields are always converted (they are cheap and may feed type resolvers or federation batch
 * keys), while composite fields — nested messages, repeated messages, and map fields — are
 * converted only when present in the tree.
 */
public final class ProjectionNode {

    private static final ProjectionNode ALL = new ProjectionNode(true);

    private final Map<String, ProjectionNode> children;
    private final boolean includeAll;

    private ProjectionNode(boolean includeAll) {
        this.includeAll = includeAll;
        this.children = includeAll ? Map.of() : new HashMap<>();
    }

    /** New empty node — children added via {@link #child(String)}. */
    public static ProjectionNode root() {
        return new ProjectionNode(false);
    }

    /** Marker node: convert the whole subtree, no pruning. */
    public static ProjectionNode includeAll() {
        return ALL;
    }

    /** Returns the existing or newly-added child for the given JSON field name. */
    public ProjectionNode child(String jsonName) {
        if (includeAll) return this;
        return children.computeIfAbsent(jsonName, k -> new ProjectionNode(false));
    }

    /** Replaces/sets a child subtree (used to graft remapped selections, e.g. Relay edges→items). */
    public void setChild(String jsonName, ProjectionNode node) {
        if (!includeAll) children.put(jsonName, node);
    }

    /** Child subtree for the field, or null when the field is not selected. */
    public ProjectionNode lookup(String jsonName) {
        if (includeAll) return this;
        return children.get(jsonName);
    }

    public boolean isIncludeAll() {
        return includeAll;
    }

    public boolean isEmpty() {
        return !includeAll && children.isEmpty();
    }

    /** Adds a '/'-separated qualified path (as produced by GraphQL selection sets). */
    public void addPath(String qualifiedPath) {
        if (includeAll) return;
        ProjectionNode current = this;
        for (String segment : qualifiedPath.split("/")) {
            if (segment.isEmpty()) continue;
            current = current.child(segment);
        }
    }

    @Override
    public String toString() {
        return includeAll ? "*" : children.toString();
    }
}
