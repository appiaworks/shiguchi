package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.cli.Cursus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * The {@code cursus} command line (run in-process through picocli with captured writers) against
 * the Boot 4 gateway over a real port
 * (mirrors {@code PushCliEndToEndTest} in spring-boot3/shiguchi-cursus-starter), on the in-memory engine with the archive on: {@code pub}
 * answers the new event ids, {@code sub --after} resumes over SSE and WebSocket, {@code ls} reads
 * the admin endpoint and {@code find} searches the archive.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.archive.retention=1h",
            "shiguchi.cursus.admin.payload-visibility=FULL",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("cursus CLI: pub, sub, ls, find end to end (Boot 4)")
class PushCliEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private Environment environment;

    private record Result(int code, List<String> lines, String err) {}

    private Result cli(String... args) {
        String[] all = java.util.Arrays.copyOf(args, args.length + 2);
        all[args.length] = "--url";
        all[args.length + 1] = "http://localhost:" + environment.getRequiredProperty("local.server.port");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Cursus.execute(
                new PrintWriter(out),
                new PrintWriter(err),
                Map.of("CURSUS_CONFIG", "/nonexistent/cursus.yml"),
                new dev.appiaworks.shiguchi.cursus.cli.Lifecycle(),
                all);
        return new Result(code, out.toString().lines().toList(), err.toString());
    }

    @Test
    @DisplayName(
            "GIVEN a gateway with push on WHEN pub, sub --after, ls and find run SHOULD publish, resume over SSE and WebSocket, list namespaces and search the archive")
    void given_gateway_when_cliVerbsRun_should_workEndToEnd() throws Exception {
        Result first = cli("pub", "orders:cli", "-d", "{\"n\":1}", "--attr", "region=eu");
        Result second = cli("pub", "orders:cli", "-d", "{\"n\":2}", "--attr", "region=us");
        assertThat(first.code()).as(first.err()).isZero();
        assertThat(second.code()).as(second.err()).isZero();
        String firstId = JSON.readTree(first.lines().get(0)).get("id").asText();
        String secondId = JSON.readTree(second.lines().get(0)).get("id").asText();
        assertThat(secondId).isNotEqualTo(firstId);

        Result sse = cli("sub", "orders:cli", "--after", firstId, "-n", "1", "--timeout", "20");
        assertThat(sse.code()).as(sse.err()).isZero();
        assertThat(sse.lines()).hasSize(1);
        JsonNode event = JSON.readTree(sse.lines().get(0));
        assertThat(event.get("id").asText()).isEqualTo(secondId);
        assertThat(event.get("topic").asText()).isEqualTo("orders:cli");
        assertThat(event.get("data").get("n").asInt()).isEqualTo(2);

        Result ws = cli("sub", "orders:cli", "--ws", "--after", firstId, "-n", "1", "--timeout", "20");
        assertThat(ws.code()).as(ws.err()).isZero();
        assertThat(JSON.readTree(ws.lines().get(0)).get("id").asText()).isEqualTo(secondId);

        Result namespaces = cli("ls", "ns");
        assertThat(namespaces.code()).as(namespaces.err()).isZero();
        assertThat(namespaces.lines()).anySatisfy(line -> assertThat(line).contains("\"name\":\"orders\""));
        assertThat(cli("ls", "topics").code()).isZero();

        Result found = cli("find", "--topic", "orders:cli", "--attr", "region=eu");
        assertThat(found.code()).as(found.err()).isZero();
        assertThat(found.lines()).hasSize(1);
        assertThat(JSON.readTree(found.lines().get(0)).get("id").asText()).isEqualTo(firstId);
        assertThat(found.lines().get(0)).contains("\"data\":\"{\\\"n\\\":1}\"");
    }

    @Test
    @DisplayName("GIVEN a topic in an undeclared namespace WHEN pub runs SHOULD exit 3")
    void given_undeclaredNamespace_when_pub_should_exit3() {
        Result result = cli("pub", "nonesuch:x", "-d", "{}");

        assertThat(result.code()).as(result.err()).isEqualTo(3);
    }
}
