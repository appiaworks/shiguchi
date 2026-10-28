package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.BffTestFixtures.MutableClock;
import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@DisplayName("BffEndpoints: the sign-in endpoints that need no issuer")
class BffEndpointsTest {

    private static final String SESSION_COOKIE = "__Host-gw-session";

    private MutableClock clock;
    private MemoryBffSessionStore store;
    private BffProperties properties;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BffTestFixtures.T0);
        store = new MemoryBffSessionStore(clock);
        properties = BffTestFixtures.properties();
        properties.getProviders().get("idp").setHintParameter("idp_hint");
        properties.getProviders().get("idp").setScopes(List.of("openid", "profile"));
        client = clientFor(properties);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private WebTestClient clientFor(BffProperties props) {
        return clientFor(props, store, null);
    }

    private WebTestClient clientFor(BffProperties props, BffSessionStore sessionStore, BffLoginLimiter limiter) {
        BffApplications applications = new BffApplications(props);
        BffEndpoints endpoints = new BffEndpoints(
                        props,
                        applications,
                        new BffProviders(props, WebClient.builder()),
                        sessionStore,
                        new BffCookies(props),
                        new JwtPrincipalConverter(null)::convert,
                        clock)
                .loginLimiter(limiter);
        return WebTestClient.bindToRouterFunction(endpoints.routes())
                .configureClient()
                .baseUrl("http://gw.local:8080")
                .build();
    }

    private String signedIn(String application, Duration sinceCreated) {
        String cookie = BffSessionKeys.newCookieValue();
        BffSession session = new BffSession(
                BffSessionKeys.key(cookie),
                "alice",
                "idp",
                application,
                "sid-1",
                "Alice",
                "alice@example.com",
                null,
                "access-token",
                clock.instant().plus(Duration.ofMinutes(5)),
                "refresh",
                "the-id-token",
                clock.instant().minus(sinceCreated),
                clock.instant(),
                0);
        store.save(session, Duration.ofDays(100)).block();
        return cookie;
    }

    private static MultiValueMap<String, String> query(String location) {
        return UriComponentsBuilder.fromUri(URI.create(location)).build().getQueryParams();
    }

    // ---- login -------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a valid return_to and hint WHEN logging in SHOULD redirect to the issuer with PKCE, state, nonce and the hint under its configured name")
    void given_validLogin_when_loggingIn_should_redirectToIssuerWithAllParameters() {
        var result = client.get()
                .uri("/bff/login/idp?return_to={r}&hint=google", "https://eve.example.com/app/home?tab=1")
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .returnResult(String.class);

        String location = result.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(location).startsWith("https://id.example.com/authorize?");
        MultiValueMap<String, String> q = query(location);
        assertThat(q.getFirst("response_type")).isEqualTo("code");
        assertThat(q.getFirst("client_id")).isEqualTo("gateway");
        assertThat(q.getFirst("redirect_uri")).isEqualTo("https%3A%2F%2Fgw.example.com%2Fbff%2Fcallback%2Fidp");
        assertThat(q.getFirst("scope")).isEqualTo("openid%20profile");
        assertThat(q.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(q.getFirst("idp_hint")).isEqualTo("google");
        assertThat(q.containsKey("provider")).isFalse();
        String state = q.getFirst("state");
        String nonce = q.getFirst("nonce");
        assertThat(state).hasSize(43);
        assertThat(nonce).hasSize(43);

        BffLogin login = store.takeLogin(state).block();
        assertThat(login).isNotNull();
        assertThat(login.nonce()).isEqualTo(nonce);
        assertThat(login.provider()).isEqualTo("idp");
        assertThat(login.application()).isEqualTo("eve");
        assertThat(login.returnTo()).isEqualTo("https://eve.example.com/app/home?tab=1");
        assertThat(login.redirectUri()).isEqualTo("https://gw.example.com/bff/callback/idp");
        assertThat(BffSessionKeys.challenge(login.codeVerifier())).isEqualTo(q.getFirst("code_challenge"));
    }

    @Test
    @DisplayName("GIVEN a login WHEN redirecting SHOULD set the per-login cookie with the state as value")
    void given_login_when_redirecting_should_setLoginCookie() {
        var result = client.get()
                .uri("/bff/login/idp?return_to=https://eve.example.com/app")
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .returnResult(String.class);

        String state = query(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                .getFirst("state");
        ResponseCookie cookie = result.getResponseCookies().getFirst("__Host-gw-login");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo(state);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("GIVEN no external-base-url WHEN logging in SHOULD build the redirect_uri from the request")
    void given_noExternalBaseUrl_when_loggingIn_should_useRequestBase() {
        properties.setExternalBaseUrl(null);
        WebTestClient local = clientFor(properties);

        var result = local.get()
                .uri("/bff/login/idp?return_to=https://eve.example.com/app")
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .returnResult(String.class);

        assertThat(query(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                        .getFirst("redirect_uri"))
                .isEqualTo("http%3A%2F%2Fgw.local%3A8080%2Fbff%2Fcallback%2Fidp");
    }

    @Test
    @DisplayName("GIVEN an unknown provider WHEN logging in SHOULD answer 404")
    void given_unknownProvider_when_loggingIn_should_return404() {
        client.get()
                .uri("/bff/login/nope?return_to=https://eve.example.com/app")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("GIVEN a provider the application does not list WHEN logging in SHOULD answer 403")
    void given_providerNotListedForApplication_when_loggingIn_should_return403() {
        client.get()
                .uri("/bff/login/other?return_to=https://eve.example.com/app")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName("GIVEN a provider the application lists WHEN logging in from the other application SHOULD succeed")
    void given_providerListedForOtherApplication_when_loggingIn_should_redirect() {
        client.get()
                .uri("/bff/login/other?return_to=https://fin.example.com/x")
                .exchange()
                .expectStatus()
                .isEqualTo(302);
    }

    @Test
    @DisplayName("GIVEN a foreign, relative or smuggled return_to WHEN logging in SHOULD answer 400")
    void given_badReturnTo_when_loggingIn_should_return400() {
        for (String bad : List.of(
                "https://eve.example.com.evil.com/app",
                "https://eve.example.com@evil.com/app",
                "/app",
                "https://eve.example.com/app-evil")) {
            client.get()
                    .uri("/bff/login/idp?return_to={r}", bad)
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        }
        client.get().uri("/bff/login/idp").exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("GIVEN a hint with forbidden characters WHEN logging in SHOULD answer 400")
    void given_badHint_when_loggingIn_should_return400() {
        client.get()
                .uri("/bff/login/idp?return_to=https://eve.example.com/app&hint={h}", "a b&c=d")
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.get()
                .uri("/bff/login/idp?return_to=https://eve.example.com/app&hint={h}", "x".repeat(65))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName("GIVEN static authorize-parameters WHEN logging in SHOULD add them to the authorize request")
    void given_authorizeParameters_when_loggingIn_should_addThemToAuthorizeRequest() {
        properties.getProviders().get("idp").setAuthorizeParameters(Map.of("provider", "google", "audience", "an api"));
        client = clientFor(properties);
        String location = client.get()
                .uri("/bff/login/idp?return_to=https://eve.example.com/app")
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst(HttpHeaders.LOCATION);

        MultiValueMap<String, String> q = query(location);
        assertThat(q.get("provider")).containsExactly("google");
        assertThat(q.get("audience")).containsExactly("an%20api");
        assertThat(q.get("client_id")).containsExactly("gateway");
        assertThat(q.get("response_type")).containsExactly("code");
    }

    @Test
    @DisplayName(
            "GIVEN a static authorize parameter under the hint's name WHEN logging in with a hint SHOULD send the hint once")
    void given_staticParameterUnderHintName_when_loggingInWithHint_should_sendHintOnce() {
        BffProperties.Provider idp = properties.getProviders().get("idp");
        idp.setHintParameter("provider");
        idp.setAuthorizeParameters(Map.of("provider", "google"));
        client = clientFor(properties);
        String location = client.get()
                .uri("/bff/login/idp?return_to=https://eve.example.com/app&hint=github")
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst(HttpHeaders.LOCATION);

        assertThat(query(location).get("provider")).containsExactly("github");
    }

    private String loginLocation(String extraQuery) {
        return client.get()
                .uri(URI.create(
                        "http://gw.local:8080/bff/login/idp?return_to=https://eve.example.com/app" + extraQuery))
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst(HttpHeaders.LOCATION);
    }

    @Test
    @DisplayName(
            "GIVEN allow-listed request parameters WHEN logging in SHOULD forward them and remember max_age on the login")
    void given_passThroughParameters_when_loggingIn_should_forwardThem() {
        String location = loginLocation(
                "&prompt=login%20consent&max_age=120&acr_values=urn:a%20urn:b&login_hint=ann@example.com&ui_locales=es");

        MultiValueMap<String, String> q = query(location);
        assertThat(q.getFirst("prompt")).isEqualTo("login%20consent");
        assertThat(q.getFirst("max_age")).isEqualTo("120");
        assertThat(q.getFirst("acr_values")).isEqualTo("urn%3Aa%20urn%3Ab");
        assertThat(q.getFirst("login_hint")).isEqualTo("ann%40example.com");
        assertThat(q.getFirst("ui_locales")).isEqualTo("es");
        assertThat(store.takeLogin(q.getFirst("state")).block().maxAge()).isEqualTo(120L);
    }

    @Test
    @DisplayName("GIVEN a parameter outside the allow-list WHEN logging in SHOULD not forward it")
    void given_parameterOutsideAllowList_when_loggingIn_should_notForwardIt() {
        properties.getProviders().get("idp").setPassThroughParameters(List.of("login_hint"));
        client = clientFor(properties);

        MultiValueMap<String, String> q = query(loginLocation("&prompt=none&login_hint=ann&max_age=5&foo=bar"));

        assertThat(q.getFirst("login_hint")).isEqualTo("ann");
        assertThat(q.containsKey("prompt")).isFalse();
        assertThat(q.containsKey("max_age")).isFalse();
        assertThat(q.containsKey("foo")).isFalse();
        assertThat(store.takeLogin(q.getFirst("state")).block().maxAge()).isNull();
    }

    @Test
    @DisplayName("GIVEN a passed parameter named like a static one WHEN logging in SHOULD send the passed value once")
    void given_passedParameterNamedLikeStaticOne_when_loggingIn_should_sendPassedValueOnce() {
        properties.getProviders().get("idp").setAuthorizeParameters(Map.of("prompt", "login"));
        client = clientFor(properties);

        assertThat(query(loginLocation("&prompt=none")).get("prompt")).containsExactly("none");
        assertThat(query(loginLocation("")).get("prompt")).containsExactly("login");
    }

    @Test
    @DisplayName(
            "GIVEN an invalid parameter value WHEN logging in SHOULD answer 400 invalid_parameter, store no login and not echo it")
    void given_invalidParameterValue_when_loggingIn_should_return400WithoutEchoingIt() {
        for (String bad : List.of(
                "prompt=bogus-value-1",
                "prompt=none%20login",
                "prompt=",
                "prompt",
                "max_age",
                "max_age=-5",
                "max_age=12x",
                "max_age=9999999999",
                "login_hint=" + "x".repeat(257),
                "ui_locales=a%0Ab",
                "acr_values=a&acr_values=b")) {
            byte[] body = client.get()
                    .uri(URI.create("http://gw.local:8080/bff/login/idp?return_to=https://eve.example.com/app&" + bad))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectHeader()
                    .doesNotExist(HttpHeaders.LOCATION)
                    .expectBody()
                    .jsonPath("$.error")
                    .isEqualTo("invalid_parameter")
                    .returnResult()
                    .getResponseBody();
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            String value = bad.substring(bad.indexOf('=') + 1);
            if (bad.contains("=") && value.length() > 3) assertThat(text).doesNotContain(value);
        }
    }

    // ---- callback ----------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a callback without a login cookie WHEN called SHOULD answer 400 login_expired and set no cookie")
    void given_callbackWithoutLoginCookie_when_called_should_returnLoginExpired() {
        var result = client.get()
                .uri("/bff/callback/idp?code=abc&state=abcdefghijklmnop")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("login_expired")
                .returnResult();

        assertThat(result.getResponseCookies()).isEmpty();
    }

    private String startLogin(WebTestClient web) {
        var result = web.get()
                .uri("/bff/login/idp?return_to=https://eve.example.com/app")
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .returnResult(String.class);
        String state = query(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                .getFirst("state");
        assertThat(result.getResponseCookies().getFirst("__Host-gw-login").getValue())
                .isEqualTo(state);
        return state;
    }

    @Test
    @DisplayName(
            "GIVEN two logins in the same browser WHEN the first callback arrives SHOULD answer login_expired, and the second SHOULD proceed")
    void given_twoLogins_when_callbacks_should_failFirstAndAcceptSecond() {
        String first = startLogin(client);
        String second = startLogin(client);

        // The browser holds the second login's cookie: the jar has one login cookie, overwritten.
        client.get()
                .uri("/bff/callback/idp?error=access_denied&state={s}", first)
                .cookie("__Host-gw-login", second)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("login_expired");
        var accepted = client.get()
                .uri("/bff/callback/idp?error=access_denied&state={s}", second)
                .cookie("__Host-gw-login", second)
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .returnResult(String.class);

        assertThat(accepted.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("https://eve.example.com/app?bff_error=access_denied");
        assertThat(accepted.getResponseCookies().getFirst("__Host-gw-login").getMaxAge())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("GIVEN cookie.secure false WHEN logging in SHOULD set the login cookie as gw-login")
    void given_insecureCookies_when_loggingIn_should_nameLoginCookieWithoutPrefix() {
        properties.getCookie().setSecure(false);
        properties.getCookie().setName("gw-session");
        var result = clientFor(properties)
                .get()
                .uri("/bff/login/idp?return_to=https://eve.example.com/app")
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .returnResult(String.class);

        assertThat(result.getResponseCookies().getFirst("gw-login")).isNotNull();
        assertThat(result.getResponseCookies().getFirst("gw-login").isSecure()).isFalse();
    }

    // ---- login rate limit ----------------------------------------------------------------------

    /** Counts {@code saveLogin} calls; everything else goes to the delegate. */
    private static BffSessionStore counting(BffSessionStore delegate, AtomicInteger saves) {
        return (BffSessionStore) Proxy.newProxyInstance(
                BffSessionStore.class.getClassLoader(),
                new Class<?>[] {BffSessionStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("saveLogin")) saves.incrementAndGet();
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    @DisplayName(
            "GIVEN rate 10 and burst 20 WHEN one client starts 21 logins at once SHOULD answer the 21st 429 with Retry-After, store nothing and set no cookie")
    void given_loginRateLimit_when_burstExceeded_should_return429WithoutStoringLogin() {
        BffProperties.LoginRateLimit limit = properties.getLoginRateLimit();
        limit.setRequestsPerSecond(10);
        limit.setBurst(20);
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicInteger saves = new AtomicInteger();
        WebTestClient limited = clientFor(properties, counting(store, saves), new BffLoginLimiter(limit, nanos::get));

        for (int i = 0; i < 20; i++) startLogin(limited);
        var refused = limited.get()
                .uri("/bff/login/idp?return_to=https://eve.example.com/app")
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("too_many_requests")
                .returnResult();

        assertThat(refused.getResponseCookies()).isEmpty();
        assertThat(saves).hasValue(20);

        nanos.addAndGet(200_000_000L); // two tokens refill in 200 ms at 10/s
        startLogin(limited);
        assertThat(saves).hasValue(21);
    }

    @Test
    @DisplayName("GIVEN the default rate of 0 WHEN many logins start SHOULD never answer 429")
    void given_limitOff_when_manyLogins_should_neverReturn429() {
        for (int i = 0; i < 50; i++) startLogin(client);
    }

    @Test
    @DisplayName("GIVEN a login cookie but no stored login WHEN the callback arrives SHOULD answer 400")
    void given_loginCookieButNoStoredLogin_when_callbackArrives_should_return400() {
        client.get()
                .uri("/bff/callback/idp?code=abc&state=abcdefghijklmnop")
                .cookie("__Host-gw-login", "abcdefghijklmnop")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName(
            "GIVEN a login started for another provider WHEN its callback arrives on this provider SHOULD answer 400")
    void given_loginForOtherProvider_when_callbackArrives_should_return400() {
        BffLogin login = new BffLogin(
                "abcdefghijklmnop", "v", "n", "idp", "eve", "https://eve.example.com/app", "r", clock.instant());
        store.saveLogin(login, Duration.ofMinutes(5)).block();

        client.get()
                .uri("/bff/callback/other?code=abc&state=abcdefghijklmnop")
                .cookie("__Host-gw-login", "abcdefghijklmnop")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName(
            "GIVEN an issuer error WHEN the callback arrives SHOULD redirect to return_to with only a safe bff_error code")
    void given_issuerError_when_callbackArrives_should_redirectWithErrorCode() {
        BffLogin login = new BffLogin(
                "abcdefghijklmnop", "v", "n", "idp", "eve", "https://eve.example.com/app?x=1", "r", clock.instant());
        store.saveLogin(login, Duration.ofMinutes(5)).block();

        var result = client.get()
                .uri("/bff/callback/idp?error=access_denied&error_description=secret+details&state=abcdefghijklmnop")
                .cookie("__Host-gw-login", "abcdefghijklmnop")
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .returnResult(String.class);

        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("https://eve.example.com/app?x=1&bff_error=access_denied");
        assertThat(result.getResponseCookies().getFirst("__Host-gw-login").getMaxAge())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName(
            "GIVEN an issuer error code with odd characters WHEN the callback arrives SHOULD report the generic code")
    void given_oddIssuerErrorCode_when_callbackArrives_should_reportGenericCode() {
        BffLogin login = new BffLogin(
                "abcdefghijklmnop", "v", "n", "idp", "eve", "https://eve.example.com/app", "r", clock.instant());
        store.saveLogin(login, Duration.ofMinutes(5)).block();

        var result = client.get()
                .uri("/bff/callback/idp?error={e}&state=abcdefghijklmnop", "<script>")
                .cookie("__Host-gw-login", "abcdefghijklmnop")
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .returnResult(String.class);

        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("https://eve.example.com/app?bff_error=error");
    }

    // ---- session -----------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a signed-in session WHEN asking for the session SHOULD describe it without tokens and omit absent claims")
    void given_signedIn_when_askingForSession_should_describeIt() {
        String cookie = signedIn("eve", Duration.ZERO);

        client.get()
                .uri("/bff/session")
                .cookie(SESSION_COOKIE, cookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.authenticated")
                .isEqualTo(true)
                .jsonPath("$.subject")
                .isEqualTo("alice")
                .jsonPath("$.provider")
                .isEqualTo("idp")
                .jsonPath("$.application")
                .isEqualTo("eve")
                .jsonPath("$.name")
                .isEqualTo("Alice")
                .jsonPath("$.email")
                .isEqualTo("alice@example.com")
                .jsonPath("$.picture")
                .doesNotExist()
                .jsonPath("$.expiresAt")
                .isEqualTo("2026-10-17T10:30:00Z")
                .jsonPath("$.accessToken")
                .doesNotExist();
    }

    @Test
    @DisplayName(
            "GIVEN a session close to its absolute timeout WHEN asking SHOULD report the earlier of idle and absolute expiry")
    void given_sessionNearAbsoluteTimeout_when_asking_should_reportEarlierExpiry() {
        String cookie = signedIn("eve", Duration.ofHours(11).plusMinutes(50));

        client.get()
                .uri("/bff/session")
                .cookie(SESSION_COOKIE, cookie)
                .exchange()
                .expectBody()
                .jsonPath("$.expiresAt")
                .isEqualTo("2026-10-17T10:10:00Z");
    }

    @Test
    @DisplayName(
            "GIVEN no cookie WHEN asking for the session SHOULD answer 200 authenticated false, with no-store and no Set-Cookie")
    void given_noCookie_when_askingForSession_should_returnUnauthenticated() {
        client.get()
                .uri("/bff/session")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader()
                .doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody()
                .json("{\"authenticated\":false}", true);
    }

    @Test
    @DisplayName(
            "GIVEN a cookie for a session that is gone WHEN asking SHOULD answer authenticated false and clear the cookie")
    void given_cookieForGoneSession_when_askingForSession_should_clearCookie() {
        var result = client.get()
                .uri("/bff/session")
                .cookie(SESSION_COOKIE, "stale")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("{\"authenticated\":false}", true)
                .returnResult();

        assertThat(result.getResponseCookies().getFirst(SESSION_COOKIE).getMaxAge())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("GIVEN a session idle past its timeout WHEN asking SHOULD answer authenticated false and delete it")
    void given_idleSession_when_asking_should_deleteAndReportUnauthenticated() {
        String cookie = signedIn("eve", Duration.ZERO);
        clock.advance(Duration.ofMinutes(31));

        client.get()
                .uri("/bff/session")
                .cookie(SESSION_COOKIE, cookie)
                .exchange()
                .expectBody()
                .json("{\"authenticated\":false}", true);

        assertThat(store.find(BffSessionKeys.key(cookie)).block()).isNull();
    }

    // ---- logout ------------------------------------------------------------------------------

    @Test
    @DisplayName("GIVEN no Origin header WHEN logging out SHOULD answer 403 and keep the session")
    void given_noOrigin_when_loggingOut_should_return403() {
        String cookie = signedIn("eve", Duration.ZERO);

        client.post()
                .uri("/bff/logout")
                .cookie(SESSION_COOKIE, cookie)
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(store.find(BffSessionKeys.key(cookie)).block()).isNotNull();
    }

    @Test
    @DisplayName("GIVEN an Origin of another application WHEN logging out a session SHOULD answer 403")
    void given_foreignOrigin_when_loggingOut_should_return403() {
        String cookie = signedIn("eve", Duration.ZERO);

        client.post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, "https://fin.example.com")
                .cookie(SESSION_COOKIE, cookie)
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                .cookie(SESSION_COOKIE, cookie)
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(store.find(BffSessionKeys.key(cookie)).block()).isNotNull();
    }

    @Test
    @DisplayName("GIVEN no session WHEN logging out from a known origin SHOULD answer 200 with an empty object")
    void given_noSession_when_loggingOut_should_return200Empty() {
        client.post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, "https://eve.example.com")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("{}", true);
    }

    @Test
    @DisplayName("GIVEN no session WHEN logging out from an unknown origin SHOULD answer 403")
    void given_noSessionAndUnknownOrigin_when_loggingOut_should_return403() {
        client.post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName(
            "GIVEN a session and an end-session endpoint WHEN logging out SHOULD delete it, clear the cookie and return the encoded logout URL")
    void given_session_when_loggingOut_should_returnLogoutUrl() {
        properties.getApplications().get("eve").setPostLogoutRedirect("https://eve.example.com/bye?a=b&c=d");
        WebTestClient local = clientFor(properties);
        String cookie = signedIn("eve", Duration.ZERO);

        var result = local.post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, "https://eve.example.com")
                .cookie(SESSION_COOKIE, cookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult();

        String url = (String) result.getResponseBody().get("logoutUrl");
        assertThat(url).startsWith("https://id.example.com/logout?");
        MultiValueMap<String, String> q = query(url);
        assertThat(q.getFirst("id_token_hint")).isEqualTo("the-id-token");
        assertThat(q.getFirst("post_logout_redirect_uri"))
                .isEqualTo("https%3A%2F%2Feve.example.com%2Fbye%3Fa%3Db%26c%3Dd");
        assertThat(q.getFirst("client_id")).isEqualTo("gateway");
        assertThat(store.find(BffSessionKeys.key(cookie)).block()).isNull();
        assertThat(result.getResponseCookies().getFirst(SESSION_COOKIE).getMaxAge())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("GIVEN a provider without an end-session endpoint WHEN logging out SHOULD answer an empty object")
    void given_noEndSessionEndpoint_when_loggingOut_should_returnEmptyObject() {
        String cookie = signedIn("fin", Duration.ZERO);
        properties.getProviders().get("idp").setEndSessionUri(null);
        WebTestClient local = clientFor(properties);

        local.post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, "https://fin.example.com")
                .cookie(SESSION_COOKIE, cookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("{}", true);
        assertThat(store.find(BffSessionKeys.key(cookie)).block()).isNull();
    }

    // ---- providers ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN an app parameter WHEN listing providers SHOULD list that application's providers with display names")
    void given_appParameter_when_listingProviders_should_listThem() {
        client.get()
                .uri("/bff/providers?app=fin")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].id")
                .isEqualTo("idp")
                .jsonPath("$[0].displayName")
                .isEqualTo("idp")
                .jsonPath("$[1].id")
                .isEqualTo("other")
                .jsonPath("$[1].displayName")
                .isEqualTo("Other IdP");
    }

    @Test
    @DisplayName("GIVEN an unknown app parameter WHEN listing providers SHOULD answer 404")
    void given_unknownApp_when_listingProviders_should_return404() {
        client.get().uri("/bff/providers?app=nope").exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("GIVEN no app parameter and an Origin WHEN listing providers SHOULD resolve the application by Origin")
    void given_originOnly_when_listingProviders_should_resolveByOrigin() {
        client.get()
                .uri("/bff/providers")
                .header(HttpHeaders.ORIGIN, "https://eve.example.com")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN neither app nor a known Origin and several applications WHEN listing providers SHOULD answer 400")
    void given_ambiguous_when_listingProviders_should_return400() {
        client.get().uri("/bff/providers").exchange().expectStatus().isBadRequest();
        client.get()
                .uri("/bff/providers")
                .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName("GIVEN a single application and no parameters WHEN listing providers SHOULD use it")
    void given_singleApplication_when_listingProviders_should_useIt() {
        properties.getApplications().remove("fin");
        WebTestClient local = clientFor(properties);

        local.get().uri("/bff/providers").exchange().expectStatus().isOk();
    }
}
