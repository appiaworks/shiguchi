package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Declares a REST batch loader: maps a GraphQL type name to a REST endpoint that can
 * resolve entities by ID for Apollo Federation _entities queries.
 *
 * <p>Two strategies are supported:
 * <ul>
 *   <li><b>Batch</b> (preferred): a single GET to {@code endpoint?{idsParam}=id1,id2,...}
 *       returns a JSON array or a wrapper object containing the array at {@code resultField}.</li>
 *   <li><b>Parallel</b> (fallback when batchEndpoint is false): one GET per ID to
 *       {@code endpoint/{id}}; results are assembled from individual responses.</li>
 * </ul>
 *
 * <p>Example YAML:
 * <pre>
 * restBatchLoaders:
 *   - type: Product
 *     endpoint: /products
 *     idsParam: ids          # GET /products?ids=1,2,3
 *     keyField: id           # field in each entity used as map key
 *     resultField: items     # wrapping field; omit if response is a bare array
 *   - type: Category
 *     endpoint: /categories/{id}
 *     batchEndpoint: false   # parallel single-GET strategy
 *     keyField: id
 * </pre>
 */
@Data
@NoArgsConstructor
public class RestBatchLoaderConfig {
    /** GraphQL type name this loader resolves (e.g. "Product"). */
    private String type;

    /**
     * REST path relative to the service base URL.
     * For batch strategy: "/products" → GET /products?ids=1,2,3
     * For single strategy: "/products/{id}" → GET /products/123
     */
    private String endpoint;

    /**
     * Query parameter name used to pass a comma-separated list of IDs.
     * Only used when {@link #batchEndpoint} is true.
     * Default: "ids".
     */
    private String idsParam = "ids";

    /**
     * JSON field in each returned entity object used as the map key.
     * Must match the value passed as ID in the representations.
     * Default: "id".
     */
    private String keyField = "id";

    /**
     * When the batch response wraps the array in a field (e.g. {@code {"items": [...]} }),
     * set this to the wrapping field name. Leave blank when the response is a bare array.
     */
    private String resultField;

    /**
     * When true (default), uses the batch strategy: one request with comma-separated IDs.
     * When false, fires N parallel GET requests (one per ID) using the {id} template in endpoint.
     */
    private boolean batchEndpoint = true;

    /**
     * Timeout in seconds for each HTTP call. Default: 10.
     */
    private int timeoutSeconds = 10;
}
