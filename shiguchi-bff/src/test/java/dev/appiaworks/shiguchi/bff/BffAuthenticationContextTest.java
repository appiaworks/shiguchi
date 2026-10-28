package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

@DisplayName(
        "BffEndpoints: authentication context (acr, amr, auth_time), max_age and prompt=none against a stub issuer")
class BffAuthenticationContextTest {

    private static final String RETURN_TO = "https://eve.example.com/app";
    private static final HttpClient ISSUER_BROWSER =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private static StubIssuer issuer;

    private MemoryBffSessionStore store;
    private WebTestClient client;
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
        store = new MemoryBffSessionStore();
        BffProperties properties = BffTestFixtures.properties();
        BffProperties.Provider idp = properties.getProviders().get("idp");
        idp.setIssuerUri(issuer.issuer());
        idp.setAuthorizationUri(null);
        idp.setTokenUri(null);
        idp.setJwkSetUri(null);
        idp.setEndSessionUri(null);
        idp.setClientId(issuer.clientId());
        idp.setClientSecret("secret");
        BffEndpoints endpoints = new BffEndpoints(
                properties,
                new BffApplications(properties),
                new BffProviders(properties, WebClient.builder()),
                store,
                new BffCookies(properties),
                new JwtPrincipalConverter(null)::convert,
                java.time.Clock.systemUTC());
        client = WebTestClient.bindToRouterFunction(endpoints.routes())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://gw.local:8080")
                .build();
        logger = (Logger) LoggerFactory.getLogger(BffEndpoints.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        store.close();
    }

    /** Result of a callback: where it redirected, and the session cookie value it set (empty when none). */
    private record Callback(String location, String sessionCookie, String code) {}

    private Callback signIn(String extraQuery) throws Exception {
        EntityExchangeResult<byte[]> login = client.get()
                .uri(URI.create("http://gw.local:8080/bff/login/idp?return_to=" + RETURN_TO + extraQuery))
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .expectBody()
                .returnResult();
        String authorize = login.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        String loginCookie =
                login.getResponseCookies().getFirst("__Host-gw-login").getValue();
        HttpResponse<String> issuerReply = ISSUER_BROWSER.send(
                HttpRequest.newBuilder(URI.create(authorize)).GET().build(), HttpResponse.BodyHandlers.ofString());
        URI callback = URI.create(issuerReply.headers().firstValue("Location").orElseThrow());
        String code = callback.getRawQuery().replaceAll(".*code=([^&]*).*", "$1");
        EntityExchangeResult<byte[]> done = client.get()
                .uri(URI.create("http://gw.local:8080" + callback.getRawPath() + "?" + callback.getRawQuery()))
                .cookie("__Host-gw-login", loginCookie)
                .exchange()
                .expectBody()
                .returnResult();
        var session = done.getResponseCookies().getFirst("__Host-gw-session");
        return new Callback(
                done.getResponseHeaders().getFirst(HttpHeaders.LOCATION),
                session == null ? "" : session.getValue(),
                code);
    }

    @Test
    @DisplayName("GIVEN an ID token with acr, amr and auth_time WHEN signing in SHOULD record them on the session")
    void given_idTokenWithContext_when_signingIn_should_recordThem() throws Exception {
        issuer.amr(List.of("pwd", "otp")).authTimeAge(Duration.ofMinutes(2));

        Callback done = signIn("&acr_values=urn:example:mfa");

        assertThat(done.location()).isEqualTo(RETURN_TO);
        BffSession session =
                store.find(BffSessionKeys.key(done.sessionCookie())).block();
        assertThat(session.acr()).isEqualTo("urn:example:mfa");
        assertThat(session.amr()).containsExactly("pwd", "otp");
        assertThat(session.authTime())
                .isBetween(Instant.now().minusSeconds(150), Instant.now().minusSeconds(100));
    }

    @Test
    @DisplayName("GIVEN max_age and a fresh auth_time WHEN signing in SHOULD create the session")
    void given_maxAgeAndFreshAuthTime_when_signingIn_should_createSession() throws Exception {
        issuer.authTimeAge(Duration.ofSeconds(30));

        Callback done = signIn("&max_age=300");

        assertThat(done.location()).isEqualTo(RETURN_TO);
        assertThat(done.sessionCookie()).isNotEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN max_age and a stale auth_time WHEN signing in SHOULD fail with sign_in_failed, create no session and log no token or code")
    void given_maxAgeAndStaleAuthTime_when_signingIn_should_failWithoutLeaking() throws Exception {
        issuer.authTimeAge(Duration.ofMinutes(20)).honorReauth(false);

        Callback done = signIn("&max_age=60");

        assertThat(done.location()).isEqualTo(RETURN_TO + "?bff_error=sign_in_failed");
        assertThat(done.sessionCookie()).isEmpty();
        assertThat(logs.list)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("max_age"));
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(done.code()).doesNotContain("eyJ");
            assertThat(String.valueOf(event.getThrowableProxy())).doesNotContain(done.code());
        }
    }

    @Test
    @DisplayName("GIVEN max_age and an ID token without auth_time WHEN signing in SHOULD fail with sign_in_failed")
    void given_maxAgeAndNoAuthTime_when_signingIn_should_fail() throws Exception {
        issuer.omitAuthTime(true);

        Callback done = signIn("&max_age=60");

        assertThat(done.location()).isEqualTo(RETURN_TO + "?bff_error=sign_in_failed");
        assertThat(done.sessionCookie()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN no max_age and an ID token without auth_time WHEN signing in SHOULD still create the session")
    void given_noMaxAgeAndNoAuthTime_when_signingIn_should_createSession() throws Exception {
        issuer.omitAuthTime(true);

        Callback done = signIn("");

        assertThat(done.sessionCookie()).isNotEmpty();
        assertThat(store.find(BffSessionKeys.key(done.sessionCookie())).block().authTime())
                .isNull();
    }

    @Test
    @DisplayName(
            "GIVEN a signed-in browser WHEN the issuer answers a login with interaction_required or consent_required SHOULD redirect with bff_error=login_required and keep the session")
    void given_issuerNeedsInteraction_when_callback_should_reportLoginRequiredAndKeepSession() {
        String cookie = BffSessionKeys.newCookieValue();
        Instant now = Instant.now();
        store.save(
                        new BffSession(
                                BffSessionKeys.key(cookie),
                                "alice",
                                "idp",
                                "eve",
                                "sid-1",
                                null,
                                null,
                                null,
                                "access",
                                now.plusSeconds(300),
                                "refresh",
                                "idt",
                                now,
                                now,
                                0),
                        Duration.ofHours(1))
                .block();

        for (String error : List.of("login_required", "interaction_required", "consent_required")) {
            EntityExchangeResult<byte[]> login = client.get()
                    .uri(URI.create("http://gw.local:8080/bff/login/idp?return_to=" + RETURN_TO + "&prompt=none"))
                    .cookie("__Host-gw-session", cookie)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(302)
                    .expectBody()
                    .returnResult();
            String authorize = login.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
            String state = authorize.replaceAll(".*[?&]state=([^&]*).*", "$1");
            EntityExchangeResult<byte[]> callback = client.get()
                    .uri(URI.create("http://gw.local:8080/bff/callback/idp?error=" + error + "&state=" + state))
                    .cookie("__Host-gw-login", state)
                    .cookie("__Host-gw-session", cookie)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(302)
                    .expectBody()
                    .returnResult();

            assertThat(callback.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                    .isEqualTo(RETURN_TO + "?bff_error=login_required");
            assertThat(callback.getResponseCookies().getFirst("__Host-gw-session"))
                    .isNull();
            assertThat(store.find(BffSessionKeys.key(cookie)).block()).isNotNull();
        }
    }
}
