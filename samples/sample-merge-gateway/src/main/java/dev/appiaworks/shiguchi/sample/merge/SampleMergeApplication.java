package dev.appiaworks.shiguchi.sample.merge;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

/**
 * Merging three backends into one sorted, paged endpoint.
 *
 * <p>Starts two gRPC order services in-process — {@code orders-eu} on 9101 and {@code orders-us}
 * on 9102 — and one plain HTTP one, {@code legacy} on 9103, each holding a slice of the same
 * logical table. The gateway exposes them as a single {@code GET /v1/orders}.
 *
 * <p>The three are deliberately not equivalent: {@code orders-eu} honours {@code sort_fields}, so
 * the gateway pushes the caller's sort down and streams it; {@code orders-us} ignores it, so the
 * gateway reads that source in full and sorts it in memory; {@code legacy} is not even gRPC — a
 * JSON array behind an OpenAPI document, registered as {@code REST_DOWNSTREAM} and read whole
 * like {@code orders-us}. One merged endpoint, three costs behind it. See README.md and
 * docs/merging.md.
 */
@SpringBootApplication
public class SampleMergeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleMergeApplication.class, args);
    }

    /** Runs the two backends for the lifetime of the app. */
    @Component
    public static class Backends {

        private final List<OrderServiceServer> servers = new ArrayList<>();
        private LegacyOrderHttpServer legacy;

        @PostConstruct
        public void start() throws Exception {
            // Even ids in EU, odd in US, so a merged listing visibly interleaves the two rather
            // than showing one region's block followed by the other's. The legacy backend takes
            // the even ids after EU's range.
            var eu = new OrderServiceServer(9101, "orders-eu", true, OrderBackend.seed("EU", 12, 1000));
            var us = new OrderServiceServer(9102, "orders-us", false, OrderBackend.seed("US", 12, 1001));
            eu.start();
            us.start();
            servers.add(eu);
            servers.add(us);
            legacy = new LegacyOrderHttpServer(9103, OrderBackend.seed("LEGACY", 6, 1024));
            legacy.start();
        }

        @PreDestroy
        public void stop() {
            servers.forEach(OrderServiceServer::stop);
            if (legacy != null) legacy.stop();
        }
    }
}
