package dev.appiaworks.shiguchi.bff;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counts sessions ended, as {@code gateway.bff.logout} tagged with {@code kind}:
 * {@code backchannel} (an issuer's logout token), {@code admin} (revoke through {@link BffSessions}
 * or the actuator endpoint), {@code user} (the browser's own logout) or {@code refresh-rejected}
 * (the issuer refused a refresh token, so the session was deleted). Counts nothing without a registry.
 */
final class BffLogoutMeter {

    static final String NAME = "gateway.bff.logout";

    private final MeterRegistry registry;

    BffLogoutMeter(MeterRegistry registry) {
        this.registry = registry;
    }

    void count(String kind, long sessions) {
        if (registry == null || sessions <= 0) return;
        Counter.builder(NAME)
                .description("Sessions ended, by how")
                .tag("kind", kind)
                .register(registry)
                .increment(sessions);
    }
}
