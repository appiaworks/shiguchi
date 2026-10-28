package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Auto-injects first/after/sort SDL args for queries whose input Descriptor has
 * page_request / sort_fields fields, and emits the shared SortFieldInput / SortOrder types.
 *
 * Called by GraphQlSchemaBuilder during SDL generation.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GraphQlPaginationArgInjector {

    private static final String SORT_ORDER_ENUM = """
            enum SortOrder {
              SORT_ORDER_UNSPECIFIED
              SORT_ORDER_ASC
              SORT_ORDER_DESC
            }
            """;

    private static final String SORT_FIELD_INPUT = """
            input SortFieldInput {
              field: String!
              sortOrder: SortOrder
            }
            """;

    public boolean hasPagination(Descriptor inputDescriptor) {
        return inputDescriptor.findFieldByName("page_request") != null;
    }

    public boolean hasSorting(Descriptor inputDescriptor) {
        return inputDescriptor.findFieldByName("sort_fields") != null;
    }

    /** Returns the SDL arg fragment to append to a paginated query/mutation field declaration. */
    public String paginationArgs(Descriptor inputDescriptor) {
        StringBuilder args = new StringBuilder();
        if (hasPagination(inputDescriptor)) {
            args.append("first: Int, after: String");
        }
        if (hasSorting(inputDescriptor)) {
            if (!args.isEmpty()) args.append(", ");
            args.append("sort: [SortFieldInput]");
        }
        return args.toString();
    }

    /** Shared SDL type definitions — emitted once at the top of the schema. */
    public String sharedTypeDefs() {
        return SORT_ORDER_ENUM + "\n" + SORT_FIELD_INPUT;
    }
}
