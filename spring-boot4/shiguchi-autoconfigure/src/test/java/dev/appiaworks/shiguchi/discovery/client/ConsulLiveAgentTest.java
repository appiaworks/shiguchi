package dev.appiaworks.shiguchi.discovery.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerAutoConfiguration;
import org.springframework.cloud.client.CommonsClientAutoConfiguration;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistrationAutoConfiguration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistryAutoConfiguration;
import org.springframework.cloud.commons.util.UtilAutoConfiguration;
import org.springframework.cloud.consul.ConsulAutoConfiguration;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryClientConfiguration;
import org.springframework.cloud.consul.serviceregistry.ConsulAutoServiceRegistrationAutoConfiguration;
import org.springframework.cloud.consul.serviceregistry.ConsulServiceRegistryAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Drives a real Boot app against a live Consul agent on {@code localhost:8500} (Spring Cloud Consul's
 * default). Skipped when no agent answers, so it only runs where one is up, for example
 * {@code podman run --rm -p 8500:8500 hashicorp/consul:1.19 agent -dev -client=0.0.0.0}. The mock-based
 * tests cannot see what the agent rejects, such as a dotted meta key.
 */
class ConsulLiveAgentTest {

    @ImportAutoConfiguration({
        UtilAutoConfiguration.class,
        ServiceRegistryAutoConfiguration.class,
        AutoServiceRegistrationAutoConfiguration.class,
        CommonsClientAutoConfiguration.class,
        ConsulAutoConfiguration.class,
        ConsulServiceRegistryAutoConfiguration.class,
        ConsulAutoServiceRegistrationAutoConfiguration.class,
        ConsulDiscoveryClientConfiguration.class,
        NettyReactiveWebServerAutoConfiguration.class,
        ConsulGrpcPortAutoConfiguration.class
    })
    // Deliberately not a @Configuration: other tests component-scan this package tree.
    static class App {
        @org.springframework.context.annotation.Bean
        org.springframework.http.server.reactive.HttpHandler httpHandler() {
            return (req, res) -> res.setComplete();
        }
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String get(String path) throws Exception {
        return HTTP.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:8500" + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static boolean agentUp() {
        try {
            return !get("/v1/status/leader").isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName(
            "GIVEN a live Consul agent WHEN a late gRPC port is published SHOULD be accepted and show in the agent's meta")
    void GIVEN_live_consul_WHEN_publishing_late_port_SHOULD_update_the_agent() throws Exception {
        assumeTrue(agentUp(), "no Consul agent on localhost:8500");
        String id = "shiguchi-live-" + System.nanoTime();
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                        "server.port=0",
                        "spring.application.name=shiguchi-live",
                        "spring.cloud.consul.host=localhost",
                        "spring.cloud.consul.port=8500",
                        "spring.cloud.consul.config.enabled=false",
                        "spring.cloud.consul.discovery.instance-id=" + id,
                        "spring.cloud.consul.discovery.register-health-check=false",
                        "spring.cloud.consul.discovery.ip-address=127.0.0.1",
                        "shiguchi.discovery.client.expose-grpc-port=true")
                // The test resources switch Consul off for the other tests; this one needs it.
                .run("--spring.cloud.consul.enabled=true")) {
            ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);

            boolean published = publisher.publish(9595);

            assertThat(published).isTrue();
            String service = get("/v1/agent/service/" + id).replaceAll("\s+", "");
            assertThat(service).contains("\"grpc-port\":\"9595\"").contains("\"shiguchi_type\":\"grpc\"");
        }
    }
}
