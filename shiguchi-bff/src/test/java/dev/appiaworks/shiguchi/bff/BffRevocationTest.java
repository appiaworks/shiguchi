package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

@DisplayName("BffRevocation: revoking the tokens of an ended session at the issuer (RFC 7009)")
class BffRevocationTest {

    private static final String SESSION_COOKIE = "__Host-gw-session";
    private static final String REFRESH = "refresh-token-value-123";
    private static final String ACCESS = "access-token-value-456";

    private static StubIssuer issuer;

    private BffProperties properties;
    private SimpleMeterRegistry meters;
    private MemoryBffSessionStore memory;
    private Logger logger;
    private ListAppender<ILoggingEvent> logs;

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
        meters = new SimpleMeterRegistry();
        memory = new MemoryBffSessionStore();
        properties = discoveryProperties();
        logger = (Logger) LoggerFactory.getLogger(BffRevocation.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        memory.close();
    }

    private static BffProperties discoveryProperties() {
        BffProperties props = BffTestFixtures.properties();
        BffProperties.Provider idp = props.getProviders().get("idp");
        idp.setIssuerUri(issuer.issuer());
        idp.setAuthorizationUri(null);
        idp.setTokenUri(null);
        idp.setJwkSetUri(null);
        idp.setEndSessionUri(null);
        idp.setClientId(issuer.clientId());
        idp.setClientSecret("secret");
        return props;
    }

    private BffProviders providers() {
        return new BffProviders(properties, WebClient.builder());
    }

    private BffRevocation revocation(BffProviders providers) {
        return new BffRevocation(providers, meters);
    }

    private static BffSession session(String key, String subject, String access, String refresh) {
        Instant now = Instant.now();
        return new BffSession(
                key,
                subject,
                "idp",
                "eve",
                "sid-" + key,
                null,
                null,
                null,
                access,
                now.plusSeconds(600),
                refresh,
                "idt",
                now,
                now,
                0);
    }

    private double count(String outcome) {
        var counter = meters.find(BffRevocation.NAME).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private void assertLogsNeverContain(String... secrets) {
        for (ILoggingEvent event : logs.list) {
            for (String secret : secrets) {
                assertThat(event.getMessage()).doesNotContain(secret);
                assertThat(event.getFormattedMessage()).doesNotContain(secret);
                assertThat(String.valueOf(event.getThrowableProxy())).doesNotContain(secret);
            }
        }
    }

    // ---- what is sent ------------------------------------------------------------------------

    @Test
    @DisplayName("GIVEN a session with a refresh token WHEN revoking SHOULD send it with the refresh_token hint")
    void given_refreshToken_when_revoking_should_sendItWithRefreshHint() {
        revocation(providers()).revoke(session("k", "alice", ACCESS, REFRESH)).block();

        assertThat(issuer.revocationCalls()).isEqualTo(1);
        StubIssuer.RevocationRequest request = issuer.lastRevocation();
        assertThat(request.token()).isEqualTo(REFRESH);
        assertThat(request.hint()).isEqualTo("refresh_token");
        assertThat(issuer.revokedTokens()).containsExactly(REFRESH);
        assertThat(count("revoked")).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN client_secret_basic WHEN revoking SHOULD authenticate with the Basic header")
    void given_basicClientAuthentication_when_revoking_should_useBasicHeader() {
        revocation(providers()).revoke(session("k", "alice", ACCESS, REFRESH)).block();

        assertThat(issuer.lastRevocation().basicAuth()).isTrue();
        assertThat(issuer.lastRevocation().formAuth()).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN client_secret_post WHEN revoking SHOULD authenticate with client_id and client_secret in the form")
    void given_postClientAuthentication_when_revoking_should_useFormCredentials() {
        properties
                .getProviders()
                .get("idp")
                .setClientAuthenticationMethod(BffProperties.ClientAuthenticationMethod.POST);

        revocation(providers()).revoke(session("k", "alice", ACCESS, REFRESH)).block();

        assertThat(issuer.lastRevocation().basicAuth()).isFalse();
        assertThat(issuer.lastRevocation().formAuth()).isTrue();
        assertThat(issuer.revokedTokens()).containsExactly(REFRESH);
    }

    @Test
    @DisplayName("GIVEN a session without a refresh token WHEN revoking SHOULD send the access token with its hint")
    void given_noRefreshToken_when_revoking_should_sendAccessToken() {
        revocation(providers()).revoke(session("k", "alice", ACCESS, null)).block();

        assertThat(issuer.lastRevocation().token()).isEqualTo(ACCESS);
        assertThat(issuer.lastRevocation().hint()).isEqualTo("access_token");
    }

    @Test
    @DisplayName("GIVEN a provider with explicit endpoints and a revocation-uri WHEN revoking SHOULD call that URI")
    void given_explicitEndpointsWithRevocationUri_when_revoking_should_callIt() {
        BffProperties.Provider idp = properties.getProviders().get("idp");
        idp.setAuthorizationUri(issuer.issuer() + "/authorize");
        idp.setTokenUri(issuer.issuer() + "/token");
        idp.setJwkSetUri(issuer.issuer() + "/jwks");
        idp.setRevocationUri(issuer.issuer() + "/revoke");
        issuer.revocationAdvertised(false);

        revocation(providers()).revoke(session("k", "alice", ACCESS, REFRESH)).block();

        assertThat(issuer.discoveryCalls()).isZero();
        assertThat(issuer.revokedTokens()).containsExactly(REFRESH);
    }

    @Test
    @DisplayName("GIVEN a revocation-uri property WHEN discovery advertises none SHOULD use the property")
    void given_propertyOverride_when_discoveryHasNone_should_useTheProperty() {
        issuer.revocationAdvertised(false);
        properties.getProviders().get("idp").setRevocationUri(issuer.issuer() + "/revoke");

        revocation(providers()).revoke(session("k", "alice", ACCESS, REFRESH)).block();

        assertThat(issuer.revokedTokens()).containsExactly(REFRESH);
    }

    @Test
    @DisplayName("GIVEN an issuer with no revocation endpoint WHEN revoking SHOULD call nothing and count skipped")
    void given_noRevocationEndpoint_when_revoking_should_skip() {
        issuer.revocationAdvertised(false);

        revocation(providers()).revoke(session("k", "alice", ACCESS, REFRESH)).block();

        assertThat(issuer.revocationCalls()).isZero();
        assertThat(count("skipped")).isEqualTo(1);
        assertThat(count("revoked")).isZero();
    }

    // ---- failures ----------------------------------------------------------------------------

    @Test
    @DisplayName("GIVEN an issuer answering 500 WHEN revoking in the background SHOULD count failed and log no token")
    void given_issuerError_when_revokingDetached_should_countFailedAndNeverLogToken() {
        issuer.revocationStatus(500);

        revocation(providers()).revokeDetached(session("k", "alice", ACCESS, REFRESH));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count("failed")).isEqualTo(1));
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("idp").contains("500");
        });
        assertLogsNeverContain(REFRESH, ACCESS, "secret");
    }

    @Test
    @DisplayName(
            "GIVEN an issuer slower than the timeout WHEN revoking SHOULD error, and count failed in the background")
    void given_slowIssuer_when_revoking_should_timeOutAndCountFailed() {
        issuer.revocationDelay(Duration.ofSeconds(3));
        BffRevocation revocation = revocation(providers().revocationTimeout(Duration.ofMillis(300)));

        assertThatThrownBy(() -> revocation
                        .revoke(session("k", "alice", ACCESS, REFRESH))
                        .block())
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        revocation.revokeDetached(session("k2", "alice", ACCESS, REFRESH));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count("failed")).isEqualTo(1));
        assertLogsNeverContain(REFRESH, ACCESS);
    }

    @Test
    @DisplayName("GIVEN a client the issuer rejects WHEN revoking in the background SHOULD count failed")
    void given_rejectedClient_when_revokingDetached_should_countFailed() {
        properties.getProviders().get("idp").setClientSecret("wrong");

        revocation(providers()).revokeDetached(session("k", "alice", ACCESS, REFRESH));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count("failed")).isEqualTo(1));
        assertThat(issuer.revokedTokens()).isEmpty();
        assertLogsNeverContain(REFRESH, "wrong");
    }

    // ---- call sites --------------------------------------------------------------------------

    private WebTestClient logoutClient(BffRevocation revocation) {
        BffEndpoints endpoints = new BffEndpoints(
                        properties,
                        new BffApplications(properties),
                        new BffProviders(properties, WebClient.builder()),
                        memory,
                        new BffCookies(properties),
                        new JwtPrincipalConverter(null)::convert,
                        java.time.Clock.systemUTC())
                .revocation(revocation);
        return WebTestClient.bindToRouterFunction(endpoints.routes())
                .configureClient()
                .baseUrl("http://gw.local:8080")
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    @DisplayName("GIVEN a signed-in user WHEN logging out SHOULD revoke the refresh token at the issuer")
    void given_signedInUser_when_loggingOut_should_revokeRefreshToken() {
        String cookie = BffSessionKeys.newCookieValue();
        memory.save(session(BffSessionKeys.key(cookie), "alice", ACCESS, REFRESH), Duration.ofMinutes(30))
                .block();

        logoutClient(revocation(providers()))
                .post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, "https://eve.example.com")
                .cookie(SESSION_COOKIE, cookie)
                .exchange()
                .expectStatus()
                .isOk();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(issuer.revokedTokens()).containsExactly(REFRESH));
        assertThat(memory.find(BffSessionKeys.key(cookie)).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an issuer answering 500 WHEN logging out SHOULD still answer 200 and count failed")
    void given_issuerError_when_loggingOut_should_stillAnswer200() {
        issuer.revocationStatus(500);
        String cookie = BffSessionKeys.newCookieValue();
        memory.save(session(BffSessionKeys.key(cookie), "alice", ACCESS, REFRESH), Duration.ofMinutes(30))
                .block();

        logoutClient(revocation(providers()))
                .post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, "https://eve.example.com")
                .cookie(SESSION_COOKIE, cookie)
                .exchange()
                .expectStatus()
                .isOk();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count("failed")).isEqualTo(1));
        assertThat(memory.find(BffSessionKeys.key(cookie)).blockOptional()).isEmpty();
        assertLogsNeverContain(REFRESH, ACCESS);
    }

    @Test
    @DisplayName("GIVEN a cookie without a session WHEN logging out SHOULD call no revocation endpoint")
    void given_noSession_when_loggingOut_should_notRevoke() {
        logoutClient(revocation(providers()))
                .post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, "https://eve.example.com")
                .cookie(SESSION_COOKIE, BffSessionKeys.newCookieValue())
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(issuer.revocationCalls()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN several sessions of a user WHEN revoking the subject SHOULD revoke exactly the ended sessions' tokens")
    void given_sessions_when_revokingSubject_should_revokeTheirTokens() {
        memory.save(session("k1", "alice", "a1", "rt-1"), Duration.ofMinutes(30))
                .block();
        memory.save(session("k2", "alice", "a2", "rt-2"), Duration.ofMinutes(30))
                .block();
        memory.save(session("k3", "bob", "a3", "rt-3"), Duration.ofMinutes(30)).block();
        BffSessions sessions = new BffSessions(properties, memory, new BffLogoutMeter(meters), revocation(providers()));

        Integer ended = sessions.revokeSubject("idp", "alice").block();

        assertThat(ended).isEqualTo(2);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(issuer.revokedTokens()).containsExactlyInAnyOrder("rt-1", "rt-2"));
        assertThat(issuer.revocationCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a handle WHEN revoking one session SHOULD revoke its refresh token")
    void given_handle_when_revokingSession_should_revokeItsToken() {
        memory.save(session("k1", "alice", "a1", "rt-1"), Duration.ofMinutes(30))
                .block();
        BffSessions sessions = new BffSessions(properties, memory, new BffLogoutMeter(meters), revocation(providers()));

        assertThat(sessions.revoke("k1").block()).isTrue();
        assertThat(sessions.revoke("k1").block()).isFalse();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(issuer.revokedTokens()).containsExactly("rt-1"));
        assertThat(issuer.revocationCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a failing issuer WHEN revoking the subject SHOULD still end every session")
    void given_failingIssuer_when_revokingSubject_should_stillEndEverySession() {
        issuer.revocationStatus(500);
        memory.save(session("k1", "alice", "a1", "rt-1"), Duration.ofMinutes(30))
                .block();
        memory.save(session("k2", "alice", "a2", "rt-2"), Duration.ofMinutes(30))
                .block();
        BffSessions sessions = new BffSessions(properties, memory, new BffLogoutMeter(meters), revocation(providers()));

        assertThat(sessions.revokeSubject("idp", "alice").block()).isEqualTo(2);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count("failed")).isEqualTo(2));
        assertThat(List.of(memory.find("k1").blockOptional(), memory.find("k2").blockOptional()))
                .allSatisfy(found -> assertThat(found).isEmpty());
    }
}
