package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

@DisplayName("Push export handler: NDJSON archive export gated by role, validated and audited")
class PushExportHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushConnections connections = new PushConnections();
    private final MemoryPushArchive archive = new MemoryPushArchive(100, Duration.ofHours(1));
    private final PushPublisher publisher;
    private final PushReport report;

    PushExportHandlerTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(10);
        props.getNamespaces().put("orders", orders);
        props.getNamespaces().put("ticker", new PushProperties.Namespace());
        props.getAdmin().setRole("PUSH_ADMIN");
        publisher = new PushPublisher(props, store, broker, metrics, archive);
        report = new PushReport(
                props,
                broker,
                store,
                metrics,
                connections,
                publisher,
                archive,
                new PushRepublishJobs(),
                new PushAudit());
    }

    @Test
    @DisplayName("GIVEN an anonymous caller WHEN export is requested SHOULD respond 401")
    void given_anonymous_caller_when_export_requested_should_respond_unauthorized() {
        ServerResponse response =
                handler(report).export(request(null, "topic", "orders:*")).block();

        assertThat(response.statusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("GIVEN an authenticated caller without the admin role WHEN export is requested SHOULD respond 403")
    void given_caller_without_admin_role_when_export_requested_should_respond_forbidden() {
        ServerResponse response = handler(report)
                .export(request(caller("read"), "topic", "orders:*"))
                .block();

        assertThat(response.statusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("GIVEN no archive configured WHEN an admin exports SHOULD respond 404")
    void given_no_archive_when_admin_exports_should_respond_not_found() {
        PushReport plain = new PushReport(props, broker, store, metrics, connections, publisher);

        ServerResponse response = handler(plain)
                .export(request(caller("SCOPE_PUSH_ADMIN"), "topic", "orders:*"))
                .block();

        assertThat(response.statusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName(
            "GIVEN events on orders and ticker WHEN an admin exports orders:* SHOULD stream the two orders lines and audit the export")
    void given_archived_events_when_admin_exports_topic_should_stream_matching_lines_and_audit_export() {
        publish("orders:a");
        publish("orders:a");
        publish("ticker:b");

        ServerResponse response = handler(report)
                .export(request(caller("SCOPE_PUSH_ADMIN"), "topic", "orders:*"))
                .block();

        assertThat(response.statusCode().value()).isEqualTo(200);
        assertThat(response.headers().getContentType()).isEqualTo(NDJSON);
        assertThat(response.headers().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("push-export.ndjson");
        List<JsonNode> lines = body(response)
                .lines()
                .filter(line -> !line.isBlank())
                .map(PushExportHandlerTest::json)
                .toList();
        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.get("topic").asText()).startsWith("orders:");
            assertThat(line.get("id").asText()).isNotBlank();
        });
        assertThat(report.audit().list(10))
                .extracting(PushAudit.Entry::action, PushAudit.Entry::principal)
                .contains(tuple("export", "ops"));
    }

    @Test
    @DisplayName("GIVEN a limit that is not a number WHEN an admin exports SHOULD respond 400")
    void given_non_numeric_limit_when_admin_exports_should_respond_bad_request() {
        ServerResponse response = handler(report)
                .export(request(caller("SCOPE_PUSH_ADMIN"), "topic", "orders:*", "limit", "abc"))
                .block();

        assertThat(response.statusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("GIVEN a limit of zero WHEN an admin exports SHOULD respond 400")
    void given_zero_limit_when_admin_exports_should_respond_bad_request() {
        ServerResponse response = handler(report)
                .export(request(caller("SCOPE_PUSH_ADMIN"), "topic", "orders:*", "limit", "0"))
                .block();

        assertThat(response.statusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("GIVEN a limit above the maximum WHEN the query is built SHOULD cap the limit at 100000")
    void given_limit_above_maximum_when_query_built_should_cap_limit_at_maximum() {
        ServerRequest request = request(caller("SCOPE_PUSH_ADMIN"), "limit", "100001");

        assertThat(PushExportHandler.query(request).limit()).isEqualTo(100_000);
    }

    @Test
    @DisplayName("GIVEN no limit WHEN the query is built SHOULD default the limit to 10000")
    void given_no_limit_when_query_built_should_default_limit() {
        ServerRequest request = request(caller("SCOPE_PUSH_ADMIN"), "topic", "orders:*");

        assertThat(PushExportHandler.query(request).limit()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("GIVEN no admin role configured WHEN any authenticated caller exports SHOULD respond 200")
    void given_no_admin_role_configured_when_any_authenticated_caller_exports_should_respond_ok() {
        props.getAdmin().setRole(null);

        ServerResponse response = handler(report)
                .export(request(caller("read"), "topic", "orders:*"))
                .block();

        assertThat(response.statusCode().value()).isEqualTo(200);
    }

    private void publish(String topic) {
        publisher
                .publish(Topic.parse(topic), "{}".getBytes(StandardCharsets.UTF_8), Map.of(), null)
                .block();
    }

    private PushExportHandler handler(PushReport target) {
        return new PushExportHandler(props, target);
    }

    /** Query parameters as name, value pairs; a null caller is anonymous. */
    private static ServerRequest request(Authentication caller, String... nameValues) {
        MockServerRequest.Builder builder =
                MockServerRequest.builder().method(HttpMethod.GET).uri(URI.create("http://localhost/push/export"));
        for (int i = 0; i < nameValues.length; i += 2) {
            builder.queryParam(nameValues[i], nameValues[i + 1]);
        }
        if (caller != null) {
            builder.principal(caller);
        }
        return builder.build();
    }

    private static Authentication caller(String authority) {
        return new UsernamePasswordAuthenticationToken("ops", "n/a", List.of(new SimpleGrantedAuthority(authority)));
    }

    /** Writes the response into a mock exchange and reads the body back. */
    private static String body(ServerResponse response) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/push/export").build());
        response.writeTo(exchange, context()).block();
        return exchange.getResponse().getBodyAsString().block();
    }

    private static ServerResponse.Context context() {
        HandlerStrategies strategies = HandlerStrategies.withDefaults();
        return new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return strategies.messageWriters();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return strategies.viewResolvers();
            }
        };
    }

    private static JsonNode json(String line) {
        try {
            return JSON.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("export line is not JSON: " + line, e);
        }
    }
}
