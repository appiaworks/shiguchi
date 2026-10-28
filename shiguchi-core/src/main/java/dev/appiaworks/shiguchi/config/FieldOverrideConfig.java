package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-field GraphQL schema hints configurable from YAML without changing proto files.
 * All fields are optional — unset fields fall through to proto annotation defaults.
 */
@Data
@NoArgsConstructor
public class FieldOverrideConfig {
    /** Emit Type! (non-nullable) for this field. */
    private boolean required;
    /** Use GraphQL ID scalar instead of the inferred type. */
    private boolean id;
    /** Alternative field name in the GraphQL schema (aliasing). */
    private String name;
    /** Default value for this field in input types. */
    private String defaultValue;
    /** Exclude this field entirely from the GraphQL schema. */
    private boolean omit;
    /** Human-readable description emitted as a GraphQL doc-string. */
    private String description;
    /** Emits @deprecated(reason: "...") on the field when non-empty. */
    private String deprecationReason;
    /** Fully-qualified RPC name that resolves this field via a nested call. */
    private String resolver;
}
