package dev.appiaworks.shiguchi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;

@DisplayName("GatewayPropertiesValidator")
class GatewayPropertiesValidatorTest {

    private static ServiceDefinition service(String name, GatewayMode mode) {
        var s = new ServiceDefinition();
        s.setName(name);
        s.setMode(mode);
        return s;
    }

    private static ServiceDefinition grpc(String name, String host, int port) {
        var s = service(name, GatewayMode.DYNAMIC);
        s.setHost(host);
        s.setPort(port);
        return s;
    }

    private static List<String> problems(ServiceDefinition... services) {
        var props = new GatewayProperties();
        props.getServices().addAll(List.of(services));
        return GatewayPropertiesValidator.problems(props);
    }

    @Test
    @DisplayName("GIVEN a well-formed configuration WHEN validated SHOULD report no problem")
    void given_validConfig_when_validated_should_reportNothing() {
        var rest = service("inventory", GatewayMode.REST_DOWNSTREAM);
        rest.setOpenapiUrl("http://inventory:8081/v3/api-docs");
        var pb = service("catalog", GatewayMode.PB_FILE);
        pb.setDiscoveryName("catalog");
        pb.setPbDescriptorPath("/etc/catalog.pb");
        var target = service("orders", GatewayMode.DYNAMIC);
        target.setTarget("dns:///orders.ns.svc.cluster.local:9090");

        assertThat(problems(grpc("library", "localhost", 9090), rest, pb, target))
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN the defaults and no service WHEN validated SHOULD report no problem")
    void given_defaults_when_validated_should_reportNothing() {
        assertThat(GatewayPropertiesValidator.problems(new GatewayProperties())).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a zero or negative client.http-timeout WHEN validated SHOULD reject it")
    void given_nonPositiveHttpTimeout_when_validated_should_reject() {
        for (Duration bad : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            var props = new GatewayProperties();
            props.getClient().setHttpTimeout(bad);

            assertThat(GatewayPropertiesValidator.problems(props))
                    .singleElement()
                    .asString()
                    .startsWith("shiguchi.client.http-timeout: ")
                    .contains("positive");
        }
    }

    @Test
    @DisplayName("GIVEN an operation-timeouts entry that is zero or negative WHEN validated SHOULD reject it")
    void given_nonPositiveOperationTimeout_when_validated_should_reject() {
        var rest = service("inventory", GatewayMode.REST_DOWNSTREAM);
        rest.setOpenapiUrl("http://inventory:8081/v3/api-docs");
        rest.getOperationTimeouts().put("getItem", Duration.ZERO);
        rest.getOperationTimeouts().put("slowReport", Duration.ofSeconds(-5));
        rest.getOperationTimeouts().put("ok", Duration.ofSeconds(2));

        assertThat(problems(rest))
                .hasSize(2)
                .allSatisfy(p -> assertThat(p)
                        .startsWith("shiguchi.services[0].operation-timeouts: ")
                        .contains("positive"))
                .anySatisfy(p -> assertThat(p).contains("getItem"))
                .anySatisfy(p -> assertThat(p).contains("slowReport"));
    }

    @Test
    @DisplayName("GIVEN positive operation-timeouts WHEN validated SHOULD report no problem")
    void given_positiveOperationTimeouts_when_validated_should_reportNothing() {
        var rest = service("inventory", GatewayMode.REST_DOWNSTREAM);
        rest.setOpenapiUrl("http://inventory:8081/v3/api-docs");
        rest.getOperationTimeouts().put("getItem", Duration.ofMillis(500));

        assertThat(problems(rest)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a service without name, host, discovery-name or target WHEN validated SHOULD name both problems with their property paths")
    void given_serviceWithoutNameOrAddress_when_validated_should_reportPaths() {
        assertThat(problems(new ServiceDefinition()))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].name: "))
                .anySatisfy(p -> assertThat(p)
                        .startsWith("shiguchi.services[0].host: ")
                        .contains("host + port, discovery-name or target"));
    }

    @Test
    @DisplayName("GIVEN two services with the same name WHEN validated SHOULD reject the second")
    void given_duplicateNames_when_validated_should_rejectSecond() {
        assertThat(problems(grpc("a", "h", 1), grpc("a", "h", 2)))
                .singleElement()
                .asString()
                .startsWith("shiguchi.services[1].name: ")
                .contains("more than once");
    }

    @Test
    @DisplayName("GIVEN a host with a missing or out-of-range port WHEN validated SHOULD reject the port")
    void given_hostWithBadPort_when_validated_should_rejectPort() {
        assertThat(problems(grpc("a", "h", 0))).singleElement().asString().startsWith("shiguchi.services[0].port: ");
        assertThat(problems(grpc("a", "h", 70000)))
                .singleElement()
                .asString()
                .startsWith("shiguchi.services[0].port: ");
    }

    @Test
    @DisplayName(
            "GIVEN PB_FILE without a descriptor path and STATIC without stubs or auto-discovery WHEN validated SHOULD reject each")
    void given_modeSpecificOmissions_when_validated_should_rejectEach() {
        var pb = service("pb", GatewayMode.PB_FILE);
        pb.setHost("h");
        pb.setPort(1);
        var st = service("st", GatewayMode.STATIC);
        st.setHost("h");
        st.setPort(1);

        assertThat(problems(pb, st))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].pb-descriptor-path: "))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[1].static-service-classes: "));

        st.getAutoDiscover().setClasspath(true);
        pb.setPbDescriptorPath("x.pb");
        assertThat(problems(pb, st)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN REST_DOWNSTREAM without or with a relative openapi-url WHEN validated SHOULD reject the url")
    void given_restDownstreamWithBadUrl_when_validated_should_rejectUrl() {
        var none = service("r", GatewayMode.REST_DOWNSTREAM);
        assertThat(problems(none)).singleElement().asString().startsWith("shiguchi.services[0].openapi-url: ");

        var relative = service("r", GatewayMode.REST_DOWNSTREAM);
        relative.setOpenapiUrl("/v3/api-docs");
        assertThat(problems(relative)).singleElement().asString().contains("absolute http(s) URL");

        var badBase = service("r", GatewayMode.REST_DOWNSTREAM);
        badBase.setOpenapiUrl("http://x/v3/api-docs");
        badBase.setBaseUrl("x:8080");
        assertThat(problems(badBase)).singleElement().asString().startsWith("shiguchi.services[0].base-url: ");
    }

    @Test
    @DisplayName("GIVEN a TLS block with a cert chain but no private key WHEN validated SHOULD reject it")
    void given_halfMtls_when_validated_should_reject() {
        var s = grpc("a", "h", 1);
        s.setTls(new ServiceDefinition.TlsConfig());
        s.getTls().setCertChain("client.pem");

        assertThat(problems(s)).singleElement().asString().startsWith("shiguchi.services[0].tls.cert-chain: ");

        s.getTls().setPrivateKey("client.key");
        assertThat(problems(s)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a negative deadline, a path prefix without slash and Avro CONFLUENT without a registry WHEN validated SHOULD reject all three")
    void given_miscServiceMistakes_when_validated_should_rejectEach() {
        var s = grpc("a", "h", 1);
        s.setDeadline(Duration.ofSeconds(-1));
        s.setPathPrefix("api");
        s.getAvro().setWireFormat(ServiceDefinition.AvroWireFormat.CONFLUENT);

        assertThat(problems(s))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].deadline: "))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].path-prefix: "))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].avro.schema-registry-url: "));
    }

    @Test
    @DisplayName("GIVEN a negative sseKeepalive duration WHEN validated SHOULD reject with property path")
    void given_negativeSseKeepalive_when_validated_should_reject() {
        var props = new GatewayProperties();
        props.getRest().getStreaming().setSseKeepalive(Duration.ofSeconds(-1));

        assertThat(GatewayPropertiesValidator.problems(props))
                .singleElement()
                .asString()
                .startsWith("shiguchi.rest.streaming.sse-keepalive: ")
                .contains("zero (disabled) or positive");
    }

    @Test
    @DisplayName("GIVEN bad global settings WHEN validated SHOULD reject each with its property path")
    void given_badGlobals_when_validated_should_rejectEach() {
        var props = new GatewayProperties();
        props.getClient().setKeepAliveTime(Duration.ZERO);
        props.getClient().setMaxInboundMessageSize(0);
        props.getClient().setDiscoveryRefresh(Duration.ofMillis(10));
        props.getGrpc().setEnabled(true);
        props.getGrpc().setPort(0);
        props.getRefresh().setEnabled(true);
        props.getRefresh().setIntervalSeconds(0);
        props.getStartup().setRetryInterval(Duration.ofSeconds(-5));
        props.getUi().setPath("grpc-ui");
        props.getMerge().setMaxSources(0);
        props.getRest().setMaxRequestBody(org.springframework.util.unit.DataSize.ofBytes(0));
        props.getGraphql().getLimits().setMaxDepth(-1);
        props.getGraphql().getLimits().setMaxComplexity(-2);
        props.getGrpcWeb().setPathPrefix("grpc-web");

        assertThat(GatewayPropertiesValidator.problems(props))
                .map(p -> p.substring(0, p.indexOf(':')))
                .containsExactlyInAnyOrder(
                        "shiguchi.client.keep-alive-time",
                        "shiguchi.client.max-inbound-message-size",
                        "shiguchi.client.discovery-refresh",
                        "shiguchi.grpc.port",
                        "shiguchi.refresh.interval-seconds",
                        "shiguchi.startup.retry-interval",
                        "shiguchi.ui.path",
                        "shiguchi.merge.max-sources",
                        "shiguchi.rest.max-request-body",
                        "shiguchi.graphql.limits.max-depth",
                        "shiguchi.graphql.limits.max-complexity",
                        "shiguchi.grpc-web.path-prefix");
    }

    @Test
    @DisplayName("GIVEN an invalid configuration WHEN validate is called SHOULD throw listing every problem")
    void given_invalidConfig_when_validate_should_throwListingProblems() {
        var props = new GatewayProperties();
        props.getServices().add(new ServiceDefinition());

        assertThatThrownBy(() -> GatewayPropertiesValidator.validate(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid shiguchi configuration")
                .hasMessageContaining("services[0].name")
                .hasMessageContaining("services[0].host");
    }

    @Test
    @DisplayName(
            "GIVEN the Spring Validator contract WHEN asked SHOULD support GatewayProperties only and reject onto field paths")
    void given_springValidator_when_used_should_supportPropertiesAndRejectFields() {
        var validator = new GatewayPropertiesValidator();
        assertThat(validator.supports(GatewayProperties.class)).isTrue();
        assertThat(validator.supports(Object.class)).isFalse();

        var props = new GatewayProperties();
        props.getServices().add(grpc("a", "h", 0));
        var errors = new BeanPropertyBindingResult(props, "shiguchi");
        validator.validate(props, errors);

        assertThat(errors.getFieldErrors()).singleElement().satisfies(fe -> {
            assertThat(fe.getField()).isEqualTo("services[0].port");
            assertThat(fe.getRejectedValue()).isEqualTo(0);
        });
    }

    @Test
    @DisplayName(
            "GIVEN a retry block gRPC would reject WHEN validated SHOULD name every bad value with its property path")
    void given_badRetryConfig_when_validated_should_reportPaths() {
        var svc = grpc("orders", "localhost", 9090);
        var retry = svc.getRetry();
        retry.setEnabled(true);
        retry.setMaxAttempts(1);
        retry.setInitialBackoff(java.time.Duration.ZERO);
        retry.setBackoffMultiplier(0);
        retry.setRetryableStatusCodes(new java.util.ArrayList<>(java.util.List.of("UNAVAILBLE", "OK")));

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).contains("retry.max-attempts", "at least 2"))
                .anySatisfy(p -> assertThat(p).contains("retry.initial-backoff", "positive"))
                .anySatisfy(p -> assertThat(p).contains("retry.backoff-multiplier", "positive"))
                .anySatisfy(p -> assertThat(p).contains("'UNAVAILBLE' is not a gRPC status code"))
                .anySatisfy(p -> assertThat(p).contains("OK is not a failure"));
    }

    @Test
    @DisplayName(
            "GIVEN max-backoff below initial-backoff WHEN validated SHOULD reject it, since the backoff could never grow")
    void given_maxBackoffBelowInitial_when_validated_should_reject() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(true);
        svc.getRetry().setInitialBackoff(java.time.Duration.ofSeconds(2));
        svc.getRetry().setMaxBackoff(java.time.Duration.ofMillis(100));

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).contains("retry.max-backoff", "at least initial-backoff"));
    }

    @Test
    @DisplayName("GIVEN retry is off WHEN its values are nonsense SHOULD report nothing, because nothing uses them")
    void given_retryDisabled_when_valuesAreBad_should_reportNothing() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setMaxAttempts(0);
        svc.getRetry().setRetryableStatusCodes(new java.util.ArrayList<>(java.util.List.of("NONSENSE")));

        assertThat(problems(svc)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a circuit breaker with impossible thresholds WHEN validated SHOULD name each problem")
    void given_badCircuitBreaker_when_validated_should_reportPaths() {
        var svc = grpc("orders", "localhost", 9090);
        var breaker = svc.getCircuitBreaker();
        breaker.setEnabled(true);
        breaker.setFailureThreshold(0);
        breaker.setHalfOpenCalls(0);
        breaker.setOpenDuration(java.time.Duration.ZERO);
        breaker.setFailureStatusCodes(new java.util.ArrayList<>());

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).contains("circuit-breaker.failure-threshold", "at least 1"))
                .anySatisfy(p -> assertThat(p).contains("circuit-breaker.open-duration", "positive"))
                .anySatisfy(p -> assertThat(p).contains("circuit-breaker.half-open-calls", "at least 1"))
                .anySatisfy(p -> assertThat(p).contains("circuit-breaker.failure-status-codes"));
    }

    @Test
    @DisplayName(
            "GIVEN retry enabled with throttling max-tokens 0 WHEN validated SHOULD reject with retry.throttling.maxTokens path")
    void given_retryThrottlingMaxTokensZero_when_validated_should_rejectMaxTokens() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(true);
        svc.getRetry().getThrottling().setMaxTokens(0);

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).contains("retry.throttling.max-tokens", "between 1 and 1000"));
    }

    @Test
    @DisplayName(
            "GIVEN retry enabled with throttling max-tokens 1001 WHEN validated SHOULD reject with retry.throttling.maxTokens path")
    void given_retryThrottlingMaxTokensOverLimit_when_validated_should_rejectMaxTokens() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(true);
        svc.getRetry().getThrottling().setMaxTokens(1001);

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).contains("retry.throttling.max-tokens", "between 1 and 1000"));
    }

    @Test
    @DisplayName(
            "GIVEN retry enabled with throttling token-ratio 0 WHEN validated SHOULD reject with retry.throttling.tokenRatio path")
    void given_retryThrottlingTokenRatioZero_when_validated_should_rejectTokenRatio() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(true);
        svc.getRetry().getThrottling().setTokenRatio(0);

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).contains("retry.throttling.token-ratio", "must be positive"));
    }

    @Test
    @DisplayName(
            "GIVEN retry enabled with invalid maxTokens but throttling disabled WHEN validated SHOULD not reject the invalid maxTokens")
    void given_retryEnabledThrottlingDisabledWithBadMaxTokens_when_validated_should_notRejectThrottlingFields() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(true);
        svc.getRetry().getThrottling().setEnabled(false);
        svc.getRetry().getThrottling().setMaxTokens(0);

        assertThat(problems(svc)).noneMatch(p -> p.contains("throttling"));
    }

    @Test
    @DisplayName("GIVEN retry enabled with default throttling values WHEN validated SHOULD report no throttling error")
    void given_retryEnabledWithDefaultThrottling_when_validated_should_reportNoThrottlingError() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(true);

        assertThat(problems(svc)).noneMatch(p -> p.contains("throttling"));
    }

    @Test
    @DisplayName(
            "GIVEN both retry and hedging enabled WHEN validated SHOULD reject with hedging path, since only one is allowed")
    void given_bothRetryAndHedgingEnabled_when_validated_should_rejectHedging() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(true);
        svc.getHedging().setEnabled(true);

        assertThat(problems(svc)).singleElement().asString().contains("hedging").contains("mutually exclusive");
    }

    @Test
    @DisplayName("GIVEN hedging enabled with maxAttempts 1 WHEN validated SHOULD reject with hedging.maxAttempts path")
    void given_hedgingMaxAttempts1_when_validated_should_rejectMaxAttempts() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        svc.getHedging().setMaxAttempts(1);

        assertThat(problems(svc))
                .singleElement()
                .asString()
                .contains("hedging.max-attempts")
                .contains("at least 2");
    }

    @Test
    @DisplayName(
            "GIVEN hedging enabled with negative hedging-delay WHEN validated SHOULD reject with hedging.hedgingDelay path")
    void given_hedgingWithNegativeDelay_when_validated_should_rejectDelay() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        svc.getHedging().setHedgingDelay(java.time.Duration.ofMillis(-1));

        assertThat(problems(svc))
                .singleElement()
                .asString()
                .contains("hedging.hedging-delay")
                .contains("zero or positive");
    }

    @Test
    @DisplayName(
            "GIVEN hedging enabled with invalid non-fatal status codes WHEN validated SHOULD reject with their names")
    void given_hedgingWithBadStatusCodes_when_validated_should_rejectCodes() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        svc.getHedging().setNonFatalStatusCodes(new java.util.ArrayList<>(java.util.List.of("NOPE", "OK")));

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).contains("'NOPE' is not a gRPC status code"))
                .anySatisfy(p -> assertThat(p).contains("OK is not a failure"));
    }

    @Test
    @DisplayName("GIVEN hedging enabled alone with defaults WHEN validated SHOULD report no hedging errors")
    void given_hedgingEnabledWithDefaults_when_validated_should_reportNothing() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);

        assertThat(problems(svc)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN hedging disabled with invalid values WHEN validated SHOULD report no hedging errors, because nothing uses them")
    void given_hedgingDisabled_when_valuesAreBad_should_reportNothing() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(false);
        svc.getHedging().setMaxAttempts(0);
        svc.getHedging().setHedgingDelay(java.time.Duration.ofMillis(-1));
        svc.getHedging().setNonFatalStatusCodes(new java.util.ArrayList<>(java.util.List.of("NONSENSE")));

        assertThat(problems(svc)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN hedging enabled with throttling max-tokens 0 WHEN validated SHOULD reject with hedging.throttling.maxTokens path")
    void given_hedgingThrottlingMaxTokensZero_when_validated_should_rejectMaxTokens() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        svc.getHedging().getThrottling().setMaxTokens(0);

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).contains("hedging.throttling.max-tokens", "between 1 and 1000"));
    }

    @Test
    @DisplayName(
            "GIVEN hedging enabled with throttling max-tokens 1001 WHEN validated SHOULD reject with hedging.throttling.maxTokens path")
    void given_hedgingThrottlingMaxTokensOverLimit_when_validated_should_rejectMaxTokens() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        svc.getHedging().getThrottling().setMaxTokens(1001);

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).contains("hedging.throttling.max-tokens", "between 1 and 1000"));
    }

    @Test
    @DisplayName(
            "GIVEN hedging enabled with throttling token-ratio 0 WHEN validated SHOULD reject with hedging.throttling.tokenRatio path")
    void given_hedgingThrottlingTokenRatioZero_when_validated_should_rejectTokenRatio() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        svc.getHedging().getThrottling().setTokenRatio(0);

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).contains("hedging.throttling.token-ratio", "must be positive"));
    }

    @Test
    @DisplayName(
            "GIVEN hedging enabled with invalid maxTokens but throttling disabled WHEN validated SHOULD not reject the invalid maxTokens")
    void given_hedgingEnabledThrottlingDisabledWithBadMaxTokens_when_validated_should_notRejectThrottlingFields() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        svc.getHedging().getThrottling().setEnabled(false);
        svc.getHedging().getThrottling().setMaxTokens(0);

        assertThat(problems(svc)).noneMatch(p -> p.contains("throttling"));
    }

    @Test
    @DisplayName(
            "GIVEN hedging enabled with default throttling values WHEN validated SHOULD report no throttling error")
    void given_hedgingEnabledWithDefaultThrottling_when_validated_should_reportNoThrottlingError() {
        var svc = grpc("orders", "localhost", 9090);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);

        assertThat(problems(svc)).noneMatch(p -> p.contains("throttling"));
    }

    @Test
    @DisplayName("GIVEN services[].channels outside 1..64 WHEN validated SHOULD reject the channels value")
    void given_channelsOutsideRange_when_validated_should_rejectChannels() {
        var svc0 = grpc("a", "h", 1);
        svc0.setChannels(0);
        var svc65 = grpc("b", "h", 1);
        svc65.setChannels(65);

        assertThat(problems(svc0))
                .singleElement()
                .asString()
                .startsWith("shiguchi.services[0].channels: ")
                .contains("between 1 and 64");

        assertThat(problems(svc65))
                .singleElement()
                .asString()
                .startsWith("shiguchi.services[0].channels: ")
                .contains("between 1 and 64");
    }

    @Test
    @DisplayName("GIVEN negative maxConcurrentCalls WHEN validated SHOULD reject it")
    void given_negativeMaxConcurrentCalls_when_validated_should_reject() {
        var svc = grpc("orders", "localhost", 9090);
        svc.setMaxConcurrentCalls(-1);

        assertThat(problems(svc))
                .singleElement()
                .asString()
                .startsWith("shiguchi.services[0].max-concurrent-calls: ")
                .contains("zero (no limit) or positive");
    }

    @Test
    @DisplayName("GIVEN cache rule without methods WHEN validated SHOULD reject it")
    void given_cacheRuleWithoutMethods_when_validated_should_reject() {
        var svc = grpc("orders", "localhost", 9090);
        var rule = new ServiceDefinition.CacheRule();
        rule.setTtl(java.time.Duration.ofSeconds(60));
        rule.setMethods(new java.util.ArrayList<>());
        svc.setCache(java.util.List.of(rule));

        assertThat(problems(svc))
                .singleElement()
                .asString()
                .startsWith("shiguchi.services[0].cache[0].methods: ")
                .contains("at least one method");
    }

    @Test
    @DisplayName("GIVEN cache rule with negative ttl WHEN validated SHOULD reject it")
    void given_cacheRuleWithNegativeTtl_when_validated_should_reject() {
        var svc = grpc("orders", "localhost", 9090);
        var rule = new ServiceDefinition.CacheRule();
        rule.setMethods(java.util.List.of("*"));
        rule.setTtl(java.time.Duration.ofSeconds(-1));
        svc.setCache(java.util.List.of(rule));

        assertThat(problems(svc))
                .singleElement()
                .asString()
                .startsWith("shiguchi.services[0].cache[0].ttl: ")
                .contains("zero (uncacheable) or positive");
    }

    @Test
    @DisplayName("GIVEN rest.response-cache enabled with non-positive max-size WHEN validated SHOULD reject it")
    void given_responseCacheWithNegativeMaxSize_when_validated_should_reject() {
        var props = new GatewayProperties();
        props.getRest().getResponseCache().setEnabled(true);
        props.getRest().getResponseCache().setMaxSize(org.springframework.util.unit.DataSize.ofBytes(0));

        assertThat(GatewayPropertiesValidator.problems(props))
                .singleElement()
                .asString()
                .startsWith("shiguchi.rest.response-cache.max-size: ")
                .contains("positive size");
    }

    @Test
    @DisplayName("GIVEN rest.response-cache enabled with non-positive max-entry-size WHEN validated SHOULD reject it")
    void given_responseCacheWithNegativeMaxEntrySize_when_validated_should_reject() {
        var props = new GatewayProperties();
        props.getRest().getResponseCache().setEnabled(true);
        props.getRest().getResponseCache().setMaxEntrySize(org.springframework.util.unit.DataSize.ofBytes(-1));

        assertThat(GatewayPropertiesValidator.problems(props))
                .singleElement()
                .asString()
                .startsWith("shiguchi.rest.response-cache.max-entry-size: ")
                .contains("positive size");
    }

    @Test
    @DisplayName("GIVEN graphql.document-cache enabled with maxEntries 0 WHEN validated SHOULD reject it")
    void given_documentCacheWithZeroMaxEntries_when_validated_should_reject() {
        var props = new GatewayProperties();
        props.getGraphql().getDocumentCache().setEnabled(true);
        props.getGraphql().getDocumentCache().setMaxEntries(0);

        assertThat(GatewayPropertiesValidator.problems(props))
                .singleElement()
                .asString()
                .startsWith("shiguchi.graphql.document-cache.max-entries: ")
                .contains("at least 1");
    }

    @Test
    @DisplayName("GIVEN outlier detection with its defaults WHEN validated SHOULD report no problem")
    void given_outlierDetectionDefaults_when_validated_should_reportNothing() {
        var svc = grpc("orders", "orders", 9090);
        svc.getOutlierDetection().setEnabled(true);

        assertThat(problems(svc)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN outlier detection with out-of-range percentages and a max ejection shorter than the base WHEN validated SHOULD name each property")
    void given_badOutlierDetection_when_validated_should_reportEachProperty() {
        var svc = grpc("orders", "orders", 9090);
        var od = svc.getOutlierDetection();
        od.setEnabled(true);
        od.setMaxEjectionPercent(101);
        od.setBaseEjectionTime(java.time.Duration.ofMinutes(10));
        od.setMaxEjectionTime(java.time.Duration.ofMinutes(1));
        od.getFailurePercentage().setThreshold(-1);
        od.getFailurePercentage().setRequestVolume(0);

        assertThat(problems(svc))
                .anySatisfy(
                        p -> assertThat(p).startsWith("shiguchi.services[0].outlier-detection.max-ejection-percent: "))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].outlier-detection.max-ejection-time: "))
                .anySatisfy(p -> assertThat(p)
                        .startsWith("shiguchi.services[0].outlier-detection.failure-percentage.threshold: "))
                .anySatisfy(p -> assertThat(p)
                        .startsWith("shiguchi.services[0].outlier-detection.failure-percentage.request-volume: "));
    }

    @Test
    @DisplayName(
            "GIVEN outlier detection with both algorithms off WHEN validated SHOULD say nothing would ever be ejected")
    void given_outlierDetectionWithoutAlgorithm_when_validated_should_report() {
        var svc = grpc("orders", "orders", 9090);
        svc.getOutlierDetection().setEnabled(true);
        svc.getOutlierDetection().getFailurePercentage().setEnabled(false);

        assertThat(problems(svc)).anySatisfy(p -> assertThat(p).contains("nothing is ever ejected"));
    }

    @Test
    @DisplayName("GIVEN a bad outlier-detection block that is disabled WHEN validated SHOULD not check it")
    void given_disabledBadOutlierDetection_when_validated_should_ignoreIt() {
        var svc = grpc("orders", "orders", 9090);
        svc.getOutlierDetection().setMaxEjectionPercent(500);

        assertThat(problems(svc)).isEmpty();
    }

    private static ServiceDefinition.TrafficSplit split(String version, int weight) {
        var split = new ServiceDefinition.TrafficSplit();
        split.setVersion(version);
        split.setWeight(weight);
        return split;
    }

    @Test
    @DisplayName("GIVEN a valid traffic split on a discovery service WHEN validated SHOULD report no problem")
    void given_validSplit_when_validated_should_reportNothing() {
        var svc = service("catalog", GatewayMode.DYNAMIC);
        svc.setDiscoveryName("catalog");
        svc.getVersions().setTraffic(List.of(split("1.4.0", 90), split("1.10.0", 10)));
        svc.getVersions().setStickyHeader("x-principal");

        assertThat(problems(svc)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a split with all weights zero, a duplicated version and a negative weight WHEN validated SHOULD name each")
    void given_badSplit_when_validated_should_reportEach() {
        var svc = service("catalog", GatewayMode.DYNAMIC);
        svc.setDiscoveryName("catalog");
        svc.getVersions().setTraffic(List.of(split("1.4.0", 0), split("1.4.0", -5)));

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].versions.traffic: "))
                .anySatisfy(p -> assertThat(p)
                        .startsWith("shiguchi.services[0].versions.traffic[1].version: ")
                        .contains("twice"))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].versions.traffic[1].weight: "));
    }

    @Test
    @DisplayName("GIVEN a split on a host/port service WHEN validated SHOULD say versions need discovery")
    void given_splitWithoutDiscovery_when_validated_should_report() {
        var svc = grpc("catalog", "catalog", 9090);
        svc.getVersions().setTraffic(List.of(split("1", 100)));

        assertThat(problems(svc)).anySatisfy(p -> assertThat(p).contains("needs discovery-name"));
    }

    @Test
    @DisplayName("GIVEN routing headers that cannot be gRPC metadata WHEN validated SHOULD reject them")
    void given_badRoutingHeaders_when_validated_should_reject() {
        var svc = service("catalog", GatewayMode.DYNAMIC);
        svc.setDiscoveryName("catalog");
        svc.getVersions().setStickyHeader("grpc-timeout");
        svc.getVersions().setPreviewHeader("x version");

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].versions.sticky-header: "))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].versions.preview-header: "));
    }

    @Test
    @DisplayName(
            "GIVEN the default rollout settings switched on for a discovery service WHEN validated SHOULD report no problem")
    void given_defaultRollout_when_validated_should_reportNothing() {
        var svc = service("catalog", GatewayMode.DYNAMIC);
        svc.setDiscoveryName("catalog");
        svc.getVersions().getRollout().setEnabled(true);

        assertThat(problems(svc)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN rollout steps out of order or short of 100, a zero interval, a rate above 1 and an unknown code WHEN validated SHOULD name each")
    void given_badRollout_when_validated_should_reportEach() {
        var svc = service("catalog", GatewayMode.DYNAMIC);
        svc.setDiscoveryName("catalog");
        var rollout = svc.getVersions().getRollout();
        rollout.setEnabled(true);
        rollout.setSteps(List.of(10, 5, 100));
        rollout.setInterval(java.time.Duration.ZERO);
        rollout.setMinCalls(0);
        rollout.setMaxErrorRate(1.5);
        rollout.setErrorCodes(List.of("UNAVAILABLE", "BROKEN"));

        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].versions.rollout.steps[1]: "))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].versions.rollout.interval: "))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].versions.rollout.min-calls: "))
                .anySatisfy(p -> assertThat(p).startsWith("shiguchi.services[0].versions.rollout.max-error-rate: "))
                .anySatisfy(p -> assertThat(p)
                        .startsWith("shiguchi.services[0].versions.rollout.error-codes[1]: ")
                        .contains("BROKEN"));

        rollout.setSteps(List.of(10, 50));
        assertThat(problems(svc))
                .anySatisfy(p -> assertThat(p)
                        .startsWith("shiguchi.services[0].versions.rollout.steps: ")
                        .contains("100"));
    }

    @Test
    @DisplayName("GIVEN a rollout on a host/port service WHEN validated SHOULD say versions need discovery")
    void given_rolloutWithoutDiscovery_when_validated_should_report() {
        var svc = grpc("catalog", "catalog", 9090);
        svc.getVersions().getRollout().setEnabled(true);

        assertThat(problems(svc)).anySatisfy(p -> assertThat(p).contains("needs discovery-name"));
    }
}
