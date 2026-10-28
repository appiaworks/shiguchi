package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.cli.Cursus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * The admin verbs of {@code cursus} (run in-process through picocli) against the Boot 4 gateway
 * over a real port (mirrors {@code PushCliAdminEndToEndTest} in spring-boot3/shiguchi-cursus-starter), on the in-memory engine with the archive on: {@code repub} dry-runs and runs a
 * job to completion, {@code rm} redacts an archived event, {@code dlq ls}, {@code send} to an
 * unknown connection and a one-second {@code bench}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.archive.retention=1h",
            "shiguchi.cursus.admin.role=PUSH_ADMIN",
            "shiguchi.cursus.admin.payload-visibility=FULL",
            "shiguchi.cursus.admin.republish.rate-per-second=1000",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.namespaces.bench.history-size=10",
            "shiguchi.cursus.namespaces.bench.publish-auth=NONE",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("cursus CLI: repub, rm, dlq, send, bench end to end (Boot 4)")
class PushCliAdminEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        ReactiveJwtDecoder fixedTokens() {
            return token -> ADMIN_TOKEN.equals(token)
                    ? Mono.just(Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject("ops")
                            .claim("scope", "PUSH_ADMIN")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(3600))
                            .build())
                    : Mono.error(new BadJwtException("unknown token"));
        }
    }

    static final String ADMIN_TOKEN = "admin-token";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private Environment environment;

    private record Result(int code, List<String> lines, String err) {}

    private Result cli(String... args) {
        String[] all = java.util.Arrays.copyOf(args, args.length + 4);
        all[args.length] = "--url";
        all[args.length + 1] = "http://localhost:" + environment.getRequiredProperty("local.server.port");
        all[args.length + 2] = "--token";
        all[args.length + 3] = ADMIN_TOKEN;
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

    private String publish(String topic, int n) throws Exception {
        Result r = cli("pub", topic, "-d", "{\"n\":" + n + "}", "--attr", "region=eu");
        assertThat(r.code()).as(r.err()).isZero();
        return JSON.readTree(r.lines().get(0)).get("id").asText();
    }

    @Test
    @DisplayName(
            "GIVEN archived events WHEN repub dry-runs, then republishes by query and rm redacts one SHOULD count, run the job to DONE and tombstone")
    void given_archive_when_repubAndRm_should_workEndToEnd() throws Exception {
        String first = publish("orders:adm", 1);
        publish("orders:adm", 2);

        Result dry = cli("repub", "--topic", "orders:adm", "--id", first, "--dry-run");
        assertThat(dry.code()).as(dry.err()).isZero();
        assertThat(JSON.readTree(dry.lines().get(0)).get("dryRun").asBoolean()).isTrue();

        Result counted = cli("repub", "--topic", "orders:adm", "--from", "1h", "--dry-run");
        assertThat(counted.code()).as(counted.err()).isZero();
        assertThat(JSON.readTree(counted.lines().get(0)).get("matched").asInt()).isEqualTo(2);

        Result job =
                cli("repub", "--topic", "orders:adm", "--from", "1h", "--target", "orders:copy", "--reason", "e2e");
        assertThat(job.code()).as(job.err()).isZero();
        JsonNode last = JSON.readTree(job.lines().get(job.lines().size() - 1));
        assertThat(last.get("state").asText()).isEqualTo("DONE");
        assertThat(last.get("published").asInt()).isEqualTo(2);
        assertThat(cli("find", "--topic", "orders:copy").lines()).hasSize(2);

        Result rm = cli("rm", first, "--topic", "orders:adm");
        assertThat(rm.code()).as(rm.err()).isZero();
        Result found = cli("find", "--topic", "orders:adm");
        assertThat(found.lines()).anySatisfy(line -> assertThat(line).contains("\"tombstoned\":true"));
    }

    @Test
    @DisplayName(
            "GIVEN no dead letters and an unknown connection WHEN dlq ls and send run SHOULD list nothing and exit 1")
    void given_noDeadLetters_when_dlqAndSend_should_behave() {
        Result dlq = cli("dlq", "ls");
        assertThat(dlq.code()).as(dlq.err()).isZero();
        assertThat(dlq.lines()).isEmpty();

        Result send = cli("send", "--conn", "no-such-connection", "-d", "{}");
        assertThat(send.code()).as(send.err()).isEqualTo(1);
        assertThat(send.lines()).anySatisfy(line -> assertThat(line).contains("NOT_CONNECTED"));
    }

    @Test
    @DisplayName(
            "GIVEN a live gateway WHEN bench runs one second SHOULD deliver what it published and print the percentiles")
    void given_gateway_when_bench_should_receiveWhatItSent() throws Exception {
        Result bench = cli(
                "bench",
                "--topic",
                "bench:smoke",
                "--rate",
                "20",
                "--duration",
                "1",
                "--subscribers",
                "1",
                "--drain-ms",
                "500");

        assertThat(bench.code()).as(bench.err()).isZero();
        JsonNode summary = JSON.readTree(bench.lines().get(0));
        assertThat(summary.get("sent").asInt()).isGreaterThan(0);
        assertThat(summary.get("publishFailures").asInt()).isZero();
        assertThat(summary.get("received").asInt()).isGreaterThan(0);
        assertThat(summary.at("/latencyMs/p50").isNumber()).isTrue();
    }
}
