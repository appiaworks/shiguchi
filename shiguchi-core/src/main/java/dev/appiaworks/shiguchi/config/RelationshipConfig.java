package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Declares a GraphQL relationship in YAML, for users who cannot edit the protos. It is the
 * YAML equivalent of the {@code resolves_type} and {@code resolves_many} field options and wins
 * over them when both name the same field.
 *
 * <p>Example:
 * <pre>
 * relationships:
 *   - from: Order.customer_id      # "Type.proto_field", keyed like fieldOverrides
 *     to: Customer                 # child GraphQL type
 *     field: customer              # optional companion field name
 *   - from: Customer.id
 *     to: Order
 *     many: true
 *     group-by: customer_id        # child field holding the parent key
 * </pre>
 */
@Data
@NoArgsConstructor
public class RelationshipConfig {
    /**
     * Parent field as {@code Type.proto_field}. For REST services the type is the prefixed GraphQL
     * type name.
     */
    private String from;

    /** Child GraphQL type the relationship resolves to. */
    private String to;

    /** Companion field name on the parent type. Defaults to the source field name sans {@code _id}. */
    private String field;

    /** True for a one-to-many relationship; requires {@link #groupBy}. */
    private boolean many;

    /** Child field that holds the parent key (snake_case proto name). Used when {@link #many}. */
    private String groupBy;
}
