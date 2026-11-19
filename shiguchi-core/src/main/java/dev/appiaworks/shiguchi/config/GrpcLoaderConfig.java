package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Declares a gRPC batch loader in YAML: the YAML equivalent of the {@code loader_for},
 * {@code loader_ids_field}, {@code loader_result_field} and {@code group_by} method options. It
 * wins over the proto options on the same method.
 *
 * <p>Example:
 * <pre>
 * loaders:
 *   - type: Customer
 *     rpc: customers.CustomerService/BatchGetCustomers
 *     ids-field: ids
 *     result-field: customers
 *   - type: Order
 *     rpc: orders.OrderService/ListOrdersByCustomers
 *     ids-field: customer_ids
 *     result-field: orders
 *     group-by: customer_id
 * </pre>
 */
@Data
@NoArgsConstructor
public class GrpcLoaderConfig {
    /** GraphQL type the loader returns. */
    private String type;

    /** Fully-qualified {@code package.Service/Method}. */
    private String rpc;

    /** Repeated request field carrying the keys. Inferred when blank. */
    private String idsField;

    /** Repeated response field carrying the entities. Inferred when blank. */
    private String resultField;

    /** Child field holding the parent key; set for a grouped (one-to-many) loader. */
    private String groupBy;
}
