package dev.appiaworks.shiguchi.traffic;

import dev.appiaworks.shiguchi.routing.GatewayTrafficReport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Actuator endpoint for moving traffic between versions of a backend at runtime.
 *
 * <p>{@code GET /actuator/gateway-traffic[/{service}]} — per version-routed service, the split in
 * effect and, per version, weight, instances, calls in flight, calls and failures, and whether it
 * is {@code SERVING}, {@code DRAINING} or {@code DRAINED} ({@code safeToStop} lists the last).
 * {@code POST /actuator/gateway-traffic/{service}} with {@code {"weights": "1.4.0=75,1.10.0=25"}}
 * changes the split at once, on the same connections; {@code DELETE} puts the configured one back.
 * The weights are one string because Actuator binds a request body as flat string values.
 *
 * <p>For a service with {@code versions.rollout}, {@code GET} also shows the rollout, and
 * {@code POST /actuator/gateway-traffic/{service}/{action}} acts on it: {@code pause},
 * {@code resume}, {@code abort} (roll back now) or {@code promote} (the new version takes every
 * call now).
 *
 * <p>The change is held by this gateway process only: with several replicas, post to each, or
 * change {@code versions.traffic} and restart. Exposed only when
 * {@code management.endpoints.web.exposure.include} names {@code gateway-traffic}; the write
 * operations move production traffic, so keep it off public exposure.
 */
@Component
@Endpoint(id = "gateway-traffic")
public class GatewayTrafficEndpoint {

    private final GatewayTrafficReport report;

    public GatewayTrafficEndpoint(GatewayTrafficReport report) {
        this.report = report;
    }

    @ReadOperation
    public Map<String, Object> traffic() {
        return report.report();
    }

    /** One service; {@code null} (a 404 from Actuator) when it does not route by version. */
    @ReadOperation
    public Map<String, Object> service(@Selector String service) {
        return report.service(service);
    }

    @WriteOperation
    public Map<String, Object> setWeights(@Selector String service, String weights) {
        try {
            return report.setWeights(service, numbers(weights));
        } catch (IllegalArgumentException e) {
            throw new InvalidEndpointRequestException(e.getMessage(), e.getMessage());
        }
    }

    @WriteOperation
    public Map<String, Object> rollout(@Selector String service, @Selector String action) {
        try {
            return report.rollout(service, action);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new InvalidEndpointRequestException(e.getMessage(), e.getMessage());
        }
    }

    @DeleteOperation
    public Map<String, Object> reset(@Selector String service) {
        try {
            return report.reset(service);
        } catch (IllegalArgumentException e) {
            throw new InvalidEndpointRequestException(e.getMessage(), e.getMessage());
        }
    }

    /** {@code "1.4.0=75,1.10.0=25"} as version → weight, in order. */
    static Map<String, Number> numbers(String weights) {
        if (weights == null || weights.isBlank()) throw new IllegalArgumentException("weights must name a version");
        Map<String, Number> out = new LinkedHashMap<>();
        for (String pair : weights.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("expected version=weight, got '" + pair.trim() + "'");
            String version = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            try {
                if (out.put(version, Double.parseDouble(value)) != null) {
                    throw new IllegalArgumentException(version + " is listed twice");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("weight of " + version + " is not a number: " + value);
            }
        }
        return out;
    }
}
