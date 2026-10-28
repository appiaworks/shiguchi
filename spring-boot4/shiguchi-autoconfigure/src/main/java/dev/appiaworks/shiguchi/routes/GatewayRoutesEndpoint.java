package dev.appiaworks.shiguchi.routes;

import dev.appiaworks.shiguchi.routing.GatewayRoutesReport;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

/**
 * Read-only actuator endpoint listing what the gateway is currently serving.
 *
 * <p>{@code GET /actuator/gateway-routes} — every configured backend, whether its schema loaded,
 * and the routes it contributes. {@code GET /actuator/gateway-routes/{service}} narrows it to one.
 * Exposed only when {@code management.endpoints.web.exposure.include} names {@code gateway-routes}.
 *
 * <p>This is the answer to "the call 404s and I do not know whether the route exists": the
 * OpenAPI document shows what should be there, this shows what is. The shape is built by
 * {@link GatewayRoutesReport} in {@code shiguchi-rest}, shared with the other Boot line.
 *
 * <p>Read-only by construction — there is no write operation — but it still describes internal
 * topology, so treat it like any other actuator endpoint and keep it off public exposure.
 */
@Component
@Endpoint(id = "gateway-routes")
public class GatewayRoutesEndpoint {

    private final GatewayRoutesReport report;

    public GatewayRoutesEndpoint(GatewayRoutesReport report) {
        this.report = report;
    }

    @ReadOperation
    public Map<String, Object> routes() {
        return report.report();
    }

    /** One service by name; {@code null} (a 404 from Actuator) when nothing is configured under it. */
    @ReadOperation
    public Map<String, Object> service(@Selector String name) {
        return report.service(name);
    }
}
