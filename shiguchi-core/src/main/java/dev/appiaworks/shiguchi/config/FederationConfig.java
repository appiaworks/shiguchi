package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Apollo Federation support on the GraphQL surface, which makes the gateway a subgraph a router
 * can compose with others rather than a standalone endpoint.
 */
@Data
@NoArgsConstructor
public class FederationConfig {
    /**
     * Enable Apollo Federation v1/v2 subgraph protocol:
     *  - Emits @key directives on types with batch_key fields
     *  - Exposes _service { sdl } query for router schema fetching
     *  - Exposes _entities(representations) query for cross-service entity resolution
     */
    private boolean enabled = false;
}
