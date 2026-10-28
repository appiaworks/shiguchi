package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.nimbusds.jose.util.JSONObjectUtils;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@DisplayName("BffBackchannelLogout: OpenID Connect Back-Channel Logout 1.0 receiver")
class BffBackchannelLogoutTest {

    private static final Pattern NUMBER_PLACEHOLDER = Pattern.compile("\"\\$\\{now([+-]\\d+)?}\"");

    private static StubIssuer issuer;

    private MemoryBffSessionStore memory;
    private BffSessionStore store;
    private SimpleMeterRegistry meters;
    private List<String> ended;
    private WebTestClient client;

    @BeforeAll
    static void startIssuer() {
        issuer = StubIssuer.start();
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @BeforeEach
    void setUp() {
        issuer.reset();
        memory = new MemoryBffSessionStore();
        meters = new SimpleMeterRegistry();
        ended = new CopyOnWriteArrayList<>();
        store = memory;
        client = clientFor(store);
        seed("s1", "idp", "alice", "sid-a");
        seed("s2", "idp", "alice", "sid-b");
        seed("s3", "idp", "bob", "sid-c");
        seed("s4", "other", "alice", "sid-a");
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    private WebTestClient clientFor(BffSessionStore backing) {
        BffProperties properties = BffTestFixtures.properties();
        properties.setPath("/bff");
        BffProperties.Provider idp = properties.getProviders().get("idp");
        idp.setIssuerUri(issuer.issuer());
        idp.setAuthorizationUri(null);
        idp.setTokenUri(null);
        idp.setJwkSetUri(null);
        idp.setEndSessionUri(null);
        idp.setClientId(issuer.clientId());
        BffSessionStore wrapped = new BffEndedSessionStore(backing, e -> ended.add(e.handle()));
        BffBackchannelLogout handler = new BffBackchannelLogout(
                properties,
                new BffProviders(properties, WebClient.builder()),
                wrapped,
                Clock.systemUTC(),
                new BffLogoutMeter(meters));
        return WebTestClient.bindToRouterFunction(handler.routes())
                .configureClient()
                .baseUrl("http://gw.local:8080")
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private void seed(String key, String provider, String subject, String sid) {
        Instant now = Instant.now();
        memory.save(
                        new BffSession(
                                key,
                                subject,
                                provider,
                                "eve",
                                sid,
                                null,
                                null,
                                null,
                                "at",
                                now.plusSeconds(600),
                                "rt",
                                "idt",
                                now,
                                now,
                                0),
                        Duration.ofMinutes(30))
                .block();
    }

    /** A fixture's claims with its placeholders filled: issuer, client id, a fresh jti and times relative to now. */
    private static Map<String, Object> claims(String fixture) {
        String text = fixtureText(fixture)
                .replace("${issuer}", issuer.issuer())
                .replace("${clientId}", issuer.clientId())
                .replace("${jti}", UUID.randomUUID().toString());
        long now = Instant.now().getEpochSecond();
        Matcher m = NUMBER_PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            long offset = m.group(1) == null ? 0 : Long.parseLong(m.group(1).replace("+", ""));
            m.appendReplacement(out, Long.toString(now + offset));
        }
        m.appendTail(out);
        try {
            return JSONObjectUtils.parse(out.toString());
        } catch (ParseException e) {
            throw new IllegalStateException(fixture, e);
        }
    }

    private static String fixtureText(String fixture) {
        try (InputStream in = BffBackchannelLogoutTest.class.getResourceAsStream("/bcl/" + fixture)) {
            if (in == null) throw new IllegalStateException("no fixture bcl/" + fixture);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private WebTestClient.ResponseSpec post(String provider, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (token != null) form.add("logout_token", token);
        return client.post()
                .uri("/bff/backchannel-logout/{provider}", provider)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchange();
    }

    private List<String> surviving() {
        return Stream.of("s1", "s2", "s3", "s4")
                .filter(k -> memory.find(k).blockOptional().isPresent())
                .collect(Collectors.toList());
    }

    private void expectAccepted(WebTestClient.ResponseSpec spec) {
        spec.expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private void expectInvalid(WebTestClient.ResponseSpec spec) {
        spec.expectStatus()
                .isBadRequest()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("invalid_request");
    }

    // ---- the validation table ---------------------------------------------------------------------

    static Stream<Arguments> accepted() {
        return Stream.of(
                Arguments.of("valid-sid.json", "logout+jwt", List.of("s2", "s3", "s4")),
                Arguments.of("valid-sid-only.json", "logout+jwt", List.of("s1", "s3", "s4")),
                Arguments.of("valid-sub.json", "logout+jwt", List.of("s3", "s4")),
                Arguments.of("valid-sub.json", null, List.of("s3", "s4")),
                Arguments.of("valid-aud-array.json", "logout+jwt", List.of("s3", "s4")),
                Arguments.of("valid-unknown-sid.json", "logout+jwt", List.of("s1", "s2", "s3", "s4")));
    }

    @ParameterizedTest(name = "{0} typ={1}")
    @MethodSource("accepted")
    @DisplayName("GIVEN a valid logout token WHEN posted SHOULD answer 200 and end only the sessions it names")
    void given_valid_logout_token_when_posted_should_end_only_the_named_sessions(
            String fixture, String typ, List<String> expectedSurvivors) {
        expectAccepted(post("idp", issuer.signAs(claims(fixture), typ)));

        assertThat(surviving()).containsExactlyElementsOf(expectedSurvivors);
    }

    @Test
    @DisplayName(
            "GIVEN a valid logout token WHEN posted SHOULD end the sessions without calling the revocation endpoint")
    void given_valid_logout_token_when_posted_should_notCallRevocationEndpoint() throws Exception {
        expectAccepted(post("idp", issuer.signAs(claims("valid-sid.json"), "logout+jwt")));

        Thread.sleep(300);
        assertThat(issuer.revocationCalls()).isZero();
    }

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("wrong-iss.json"),
                Arguments.of("wrong-aud.json"),
                Arguments.of("expired.json"),
                Arguments.of("future-iat.json"),
                Arguments.of("missing-events.json"),
                Arguments.of("wrong-event.json"),
                Arguments.of("with-nonce.json"),
                Arguments.of("no-sid-no-sub.json"),
                Arguments.of("no-exp.json"),
                Arguments.of("no-iat.json"),
                Arguments.of("no-jti.json"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    @DisplayName("GIVEN a logout token failing one rule WHEN posted SHOULD answer 400 invalid_request and end nothing")
    void given_logout_token_failing_a_rule_when_posted_should_end_nothing(String fixture) {
        expectInvalid(post("idp", issuer.signAs(claims(fixture), "logout+jwt")));

        assertThat(surviving()).containsExactly("s1", "s2", "s3", "s4");
        assertThat(ended).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a logout token signed with another key under the issuer's kid WHEN posted SHOULD answer 400")
    void given_bad_signature_when_posted_should_answer_400() {
        expectInvalid(post("idp", issuer.signForeign(claims("valid-sub.json"), "logout+jwt")));

        assertThat(surviving()).containsExactly("s1", "s2", "s3", "s4");
    }

    @Test
    @DisplayName("GIVEN an unsecured logout token (alg none) WHEN posted SHOULD answer 400")
    void given_alg_none_when_posted_should_answer_400() {
        expectInvalid(post("idp", issuer.unsigned(claims("valid-sub.json"))));

        assertThat(surviving()).containsExactly("s1", "s2", "s3", "s4");
    }

    @Test
    @DisplayName("GIVEN a logout token with typ JWT WHEN posted SHOULD answer 400: only logout+jwt or no typ")
    void given_wrong_typ_when_posted_should_answer_400() {
        expectInvalid(post("idp", issuer.signAs(claims("valid-sub.json"), "JWT")));

        assertThat(surviving()).containsExactly("s1", "s2", "s3", "s4");
    }

    @Test
    @DisplayName("GIVEN a token that is not a JWT, no token, or an unknown provider WHEN posted SHOULD answer 400")
    void given_garbage_when_posted_should_answer_400() {
        expectInvalid(post("idp", "not-a-jwt"));
        expectInvalid(post("idp", null));
        expectInvalid(post("nobody", issuer.signAs(claims("valid-sub.json"), "logout+jwt")));

        assertThat(surviving()).containsExactly("s1", "s2", "s3", "s4");
    }

    @Test
    @DisplayName(
            "GIVEN a logout token already accepted WHEN posted again SHOULD answer 400 and not end sessions created since")
    void given_replayed_jti_when_posted_again_should_answer_400() {
        String token = issuer.signAs(claims("valid-sid.json"), "logout+jwt");
        expectAccepted(post("idp", token));
        seed("s1", "idp", "alice", "sid-a");

        expectInvalid(post("idp", token));

        assertThat(surviving()).containsExactly("s1", "s2", "s3", "s4");
    }

    // ---- effects --------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a logout token by subject WHEN posted SHOULD publish one session-ended event per session and count them")
    void given_logout_by_subject_when_posted_should_publish_events_and_count() {
        expectAccepted(post("idp", issuer.signAs(claims("valid-sub.json"), "logout+jwt")));

        assertThat(ended).containsExactlyInAnyOrder("s1", "s2");
        assertThat(meters.get("gateway.bff.logout")
                        .tag("kind", "backchannel")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("GIVEN a logout token naming no session WHEN posted SHOULD answer 200 and publish nothing")
    void given_logout_naming_no_session_when_posted_should_answer_200_and_publish_nothing() {
        expectAccepted(post("idp", issuer.signAs(claims("valid-unknown-sid.json"), "logout+jwt")));

        assertThat(ended).isEmpty();
    }

    // ---- failures the issuer should retry ---------------------------------------------------------

    @Test
    @DisplayName("GIVEN the provider's key set is unreachable WHEN a logout token is posted SHOULD answer 503, not 400")
    void given_unreachable_key_set_when_posted_should_answer_503() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        BffProperties properties = BffTestFixtures.properties();
        properties.getProviders().get("idp").setJwkSetUri("http://localhost:" + closedPort + "/jwks");
        BffBackchannelLogout handler = new BffBackchannelLogout(
                properties,
                new BffProviders(properties, WebClient.builder()),
                memory,
                Clock.systemUTC(),
                new BffLogoutMeter(null));
        WebTestClient unreachable = WebTestClient.bindToRouterFunction(handler.routes())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        Map<String, Object> claims = claims("valid-sub.json");
        claims.put("iss", "https://id.example.com");

        unreachable
                .post()
                .uri("/bff/backchannel-logout/idp")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("logout_token", issuer.signAs(claims, "logout+jwt")))
                .exchange()
                .expectStatus()
                .isEqualTo(503);

        assertThat(surviving()).containsExactly("s1", "s2", "s3", "s4");
    }

    @Test
    @DisplayName(
            "GIVEN the store fails deleting WHEN the same token is posted twice SHOULD answer 503 then end the sessions")
    void given_store_failure_when_token_retried_should_answer_503_then_succeed() {
        BffSessionStore flaky = spy(memory);
        doReturn(Mono.error(new IllegalStateException("db down")))
                .doCallRealMethod()
                .when(flaky)
                .deleteBySubject(any(), any());
        client = clientFor(flaky);
        String token = issuer.signAs(claims("valid-sub.json"), "logout+jwt");

        post("idp", token).expectStatus().isEqualTo(503);
        assertThat(surviving()).containsExactly("s1", "s2", "s3", "s4");

        expectAccepted(post("idp", token));
        assertThat(surviving()).containsExactly("s3", "s4");
    }

    @Test
    @DisplayName("GIVEN a store without the lifecycle methods WHEN a valid token is posted SHOULD answer 501")
    void given_store_without_lifecycle_when_posted_should_answer_501() {
        BffSessionStore bare = new BffSessionStore() {
            @Override
            public Mono<Void> save(BffSession session, Duration ttl) {
                return Mono.empty();
            }

            @Override
            public Mono<BffSession> find(String key) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> delete(String key) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> saveLogin(dev.appiaworks.shiguchi.bff.session.BffLogin login, Duration ttl) {
                return Mono.empty();
            }

            @Override
            public Mono<dev.appiaworks.shiguchi.bff.session.BffLogin> takeLogin(String state) {
                return Mono.empty();
            }
        };
        client = clientFor(bare);

        post("idp", issuer.signAs(claims("valid-sub.json"), "logout+jwt"))
                .expectStatus()
                .isEqualTo(501);
    }
}
