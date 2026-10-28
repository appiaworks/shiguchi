package dev.appiaworks.shiguchi.cursus.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("cursus command line parsing, settings and exit code 2")
class CursusParseTest {

    @Test
    @DisplayName("GIVEN no arguments WHEN run SHOULD exit 2 with a usage message")
    void given_noArguments_when_run_should_exitWithUsage() {
        CliRun.Result result = CliRun.run();

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("Usage").contains("sub");
    }

    @Test
    @DisplayName("GIVEN --help WHEN run SHOULD exit 0 and list the verbs and exit codes")
    void given_help_when_run_should_listVerbs() {
        CliRun.Result result = CliRun.run("--help");

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("sub", "pub", "ls", "find", "Exit codes");
    }

    @Test
    @DisplayName("GIVEN sub without a topic WHEN run SHOULD exit 2")
    void given_subWithoutTopic_when_run_should_exitWithUsage() {
        assertThat(CliRun.run("sub").code()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN two transports WHEN run SHOULD exit 2 before connecting")
    void given_twoTransports_when_run_should_exitWithUsage() {
        CliRun.Result result = CliRun.run("sub", "orders:1", "--sse", "--ws");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("choose one of");
    }

    @Test
    @DisplayName("GIVEN an unknown option WHEN run SHOULD exit 2")
    void given_unknownOption_when_run_should_exitWithUsage() {
        assertThat(CliRun.run("sub", "orders:1", "--nonsense").code()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN an unknown ls target WHEN run SHOULD exit 2")
    void given_unknownLsTarget_when_run_should_exitWithUsage() {
        assertThat(CliRun.run("ls", "bogus").code()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a payload that is not JSON WHEN pub runs SHOULD exit 2 without a request")
    void given_invalidPayload_when_pub_should_exitWithUsage() {
        CliRun.Result result = CliRun.run("pub", "orders:1", "-d", "{not json", "--url", "http://localhost:1");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("not valid JSON");
    }

    @Test
    @DisplayName("GIVEN a malformed --attr WHEN pub runs SHOULD exit 2")
    void given_malformedAttr_when_pub_should_exitWithUsage() {
        assertThat(CliRun.run("pub", "orders:1", "-d", "{}", "--attr", "novalue")
                        .code())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a profile that does not exist WHEN run SHOULD exit 2")
    void given_unknownProfile_when_run_should_exitWithUsage() {
        CliRun.Result result = CliRun.run("ls", "--profile", "nope");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("nope");
    }

    @Test
    @DisplayName(
            "GIVEN flag, environment and profile WHEN settings resolve SHOULD prefer flag over environment over profile")
    void given_threeSources_when_settingsResolve_should_followPrecedence(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.yml");
        Files.writeString(config, """
                profiles:
                  staging:
                    url: https://staging.example.com/
                    actuator-url: https://staging.example.com/ops
                    token: from-profile
                    insecure: true
                    grpc-target: grpc.example.com:443
                """);
        Map<String, String> env = Map.of("CURSUS_CONFIG", config.toString(), "CURSUS_TOKEN", "from-env");

        Settings fromEnv = Settings.resolve(flags("staging", null, null), env);
        Settings fromFlag = Settings.resolve(flags("staging", "http://flag:1", "from-flag"), env);

        assertThat(fromEnv.url()).isEqualTo("https://staging.example.com");
        assertThat(fromEnv.adminBase()).isEqualTo("https://staging.example.com/ops/gateway-push");
        assertThat(fromEnv.token().current()).isEqualTo("from-env");
        assertThat(fromEnv.insecure()).isTrue();
        assertThat(fromEnv.grpcAuthority()).isEqualTo("grpc.example.com:443");
        assertThat(fromFlag.url()).isEqualTo("http://flag:1");
        assertThat(fromFlag.token().current()).isEqualTo("from-flag");
    }

    @Test
    @DisplayName("GIVEN no configuration WHEN settings resolve SHOULD default to localhost and never insecure")
    void given_nothing_when_settingsResolve_should_useDefaults() {
        Settings settings = Settings.resolve(flags(null, null, null), Map.of("CURSUS_CONFIG", "/nonexistent/c.yml"));

        assertThat(settings.url()).isEqualTo("http://localhost:8080");
        assertThat(settings.pushBase()).isEqualTo("http://localhost:8080/push");
        assertThat(settings.adminBase()).isEqualTo("http://localhost:8080/actuator/gateway-push");
        assertThat(settings.insecure()).isFalse();
        assertThat(settings.grpcAuthority()).isEqualTo("localhost:9090");
    }

    @Test
    @DisplayName("GIVEN a relative time WHEN find converts it SHOULD answer an instant in the past")
    void given_relativeTime_when_converted_should_beInThePast() {
        String converted = FindCommand.instant("1h");

        assertThat(java.time.Instant.parse(converted))
                .isBefore(java.time.Instant.now().minusSeconds(3500));
        assertThat(FindCommand.instant("2026-10-01T00:00:00Z")).isEqualTo("2026-10-01T00:00:00Z");
    }

    @Test
    @DisplayName("GIVEN growing attempts WHEN the backoff is computed SHOULD grow, stay under the cap and keep jitter")
    void given_attempts_when_backoffComputed_should_growWithinCap() {
        Subscriber.Options options = new Subscriber.Options(0, -1, 500, 4000);
        Random random = new Random(7);

        long first = Subscriber.delay(1, options, random);
        long third = Subscriber.delay(3, options, random);
        long huge = Subscriber.delay(40, options, random);

        assertThat(first).isBetween(250L, 500L);
        assertThat(third).isBetween(1000L, 2000L);
        assertThat(huge).isBetween(2000L, 4000L);
    }

    private static Settings.Flags flags(String profile, String url, String token) {
        return new Settings.Flags(profile, url, null, null, null, token, null, null, null);
    }
}
