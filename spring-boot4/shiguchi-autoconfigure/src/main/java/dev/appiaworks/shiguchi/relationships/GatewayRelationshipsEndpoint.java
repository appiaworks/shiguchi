package dev.appiaworks.shiguchi.relationships;

import dev.appiaworks.shiguchi.graphql.RelationshipReport;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Read-only actuator endpoint: the GraphQL relationships the gateway resolved, where each was declared
 * ({@code annotation}, {@code extension}, {@code yaml}, {@code bean}), the loader that serves it, the
 * batch keys, and heuristic suggestions for REST_DOWNSTREAM documents (labelled {@code suggested} and
 * {@code wired: false}). {@code GET /actuator/gateway-relationships}, or
 * {@code /actuator/gateway-relationships?type=ShopOrder} for one parent type.
 * Present only with the GraphQL module on the classpath and {@code shiguchi.graphql.enabled} not
 * {@code false}, and exposed only when {@code management.endpoints.web.exposure.include} names
 * {@code gateway-relationships}. It describes internal topology: keep it off public exposure.
 */
@Component
@Endpoint(id = "gateway-relationships")
@ConditionalOnClass(name = "dev.appiaworks.shiguchi.graphql.RelationshipReport")
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewayRelationshipsEndpoint {

    private final RelationshipReport report;

    public GatewayRelationshipsEndpoint(RelationshipReport report) {
        this.report = report;
    }

    @ReadOperation
    public Map<String, Object> relationships(@Nullable String type) {
        return report.report(type);
    }
}
