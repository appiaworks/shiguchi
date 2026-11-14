package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@DisplayName("BffTokenExchanger: RFC 8693 token exchange for a per-backend token")
class BffTokenExchangerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String SUBJECT_TOKEN = "subject-token-value-123";
    private static final String REFRESH_TOKEN = "refresh-token-value-456";
    private static final String SERVICE = "acme.orders.v1.OrderService";

    private static StubIssuer issuer;

    private BffProperties properties;
    private BffTokenExchanger exchanger;
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
        properties = BffTestFixtures.properties();
        BffProperties.Provider idp = properties.getProviders().get("idp");
        idp.setIssuerUri(issuer.issuer());
        idp.setAuthorizationUri(null);
        idp.setTokenUri(null);
        idp.setJwkSetUri(null);
        idp.setEndSessionUri(null);
        idp.setClientId(issuer.clientId());
        idp.setClientSecret("secret");
        idp.setUpstreamTokenMode(BffProperties.UpstreamTokenMode.EXCHANGE);
        BffProperties.ExchangeTarget orders = new BffProperties.ExchangeTarget();
        orders.setAudience("orders-api");
        orders.setScope("orders.read");
        idp.getExchange().getTargets().put(SERVICE, orders);
        properties.setRefreshSkew(Duration.ofSeconds(60));
        exchanger = new BffTokenExchanger(new BffProviders(properties, WebClient.builder()), properties);
        logger = (Logger) LoggerFactory.getLogger(BffTokenExchanger.class);
        logger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private static BffSession session(String key, long version) {
        Instant now = Instant.now();
        return new BffSession(
                key,
                "alice",
                "idp",
                "eve",
                "sid",
                null,
                null,
                null,
                SUBJECT_TOKEN,
                now.plusSeconds(600),
                REFRESH_TOKEN,
                "id-token",
                now,
                now,
                version);
    }

    private String exchange(BffSession session, String service) {
        return exchanger.exchange(session, service).block(TIMEOUT);
    }

    @Test
    @DisplayName("GIVEN a mapped service WHEN exchanging SHOULD send the RFC 8693 form and return the issued token")
    void given_mapped_service_when_exchanging_should_send_rfc8693_form() {
        String token = exchange(session("k", 0), SERVICE);

        assertThat(token).isNotBlank().isNotEqualTo(SUBJECT_TOKEN);
        assertThat(issuer.exchangeCalls()).isEqualTo(1);
        assertThat(issuer.lastExchangeRequestBody())
                .contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange")
                .contains("subject_token=" + SUBJECT_TOKEN)
                .contains("subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aaccess_token")
                .contains("requested_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aaccess_token")
                .contains("audience=orders-api")
                .contains("scope=orders.read");
    }

    @Test
    @DisplayName("GIVEN extra parameters WHEN exchanging SHOULD add them to the form")
    void given_extra_parameters_when_exchanging_should_add_them() {
        properties.getProviders().get("idp").getExchange().setExtraParameters(Map.of("resource", "https://orders"));

        exchange(session("k", 0), SERVICE);

        assertThat(issuer.lastExchangeRequestBody()).contains("resource=https%3A%2F%2Forders");
    }

    @Test
    @DisplayName("GIVEN an unmapped service and a default target WHEN exchanging SHOULD use the default")
    void given_unmapped_service_with_default_when_exchanging_should_use_default() {
        BffProperties.ExchangeTarget fallback = new BffProperties.ExchangeTarget();
        fallback.setAudience("default-api");
        properties.getProviders().get("idp").getExchange().setDefaultTarget(fallback);

        exchange(session("k", 0), "other.Service");

        assertThat(issuer.lastExchangeRequestBody())
                .contains("audience=default-api")
                .doesNotContain("scope=");
    }

    @Test
    @DisplayName("GIVEN an unmapped service and no default WHEN exchanging SHOULD refuse without calling the issuer")
    void given_unmapped_service_without_default_when_exchanging_should_refuse() {
        assertThatThrownBy(() -> exchange(session("k", 0), "other.Service"))
                .isInstanceOf(BffTokenExchanger.ExchangeRefused.class);
        assertThat(issuer.exchangeCalls()).isZero();
    }

    @Test
    @DisplayName("GIVEN the issuer answers invalid_target WHEN exchanging SHOULD be refused")
    void given_invalid_target_when_exchanging_should_be_refused() {
        issuer.exchangeStatus(400).exchangeError("invalid_target");

        assertThatThrownBy(() -> exchange(session("k", 0), SERVICE))
                .isInstanceOf(BffTokenExchanger.ExchangeRefused.class)
                .hasMessageContaining("invalid_target");
    }

    @Test
    @DisplayName("GIVEN the issuer answers access_denied with 403 WHEN exchanging SHOULD be refused")
    void given_access_denied_when_exchanging_should_be_refused() {
        issuer.exchangeStatus(403).exchangeError("access_denied");

        assertThatThrownBy(() -> exchange(session("k", 0), SERVICE))
                .isInstanceOf(BffTokenExchanger.ExchangeRefused.class);
    }

    @Test
    @DisplayName("GIVEN the issuer answers 503 WHEN exchanging SHOULD be unavailable and not cached")
    void given_server_error_when_exchanging_should_be_unavailable_and_not_cached() {
        issuer.exchangeStatus(503).exchangeError("temporarily_unavailable");
        BffSession session = session("k", 0);

        assertThatThrownBy(() -> exchange(session, SERVICE)).isInstanceOf(BffTokenExchanger.ExchangeUnavailable.class);
        issuer.exchangeStatus(0);

        assertThat(exchange(session, SERVICE)).isNotBlank();
        assertThat(issuer.exchangeCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN the issuer answers invalid_client WHEN exchanging SHOULD be unavailable, not refused")
    void given_invalid_client_when_exchanging_should_be_unavailable() {
        issuer.exchangeStatus(401).exchangeError("invalid_client");

        assertThatThrownBy(() -> exchange(session("k", 0), SERVICE))
                .isInstanceOf(BffTokenExchanger.ExchangeUnavailable.class);
    }

    @Test
    @DisplayName("GIVEN a token_type of N_A WHEN exchanging SHOULD be unavailable")
    void given_token_type_n_a_when_exchanging_should_be_unavailable() {
        issuer.exchangeTokenType("N_A");

        assertThatThrownBy(() -> exchange(session("k", 0), SERVICE))
                .isInstanceOf(BffTokenExchanger.ExchangeUnavailable.class);
    }

    @Test
    @DisplayName("GIVEN a lower-case bearer token_type WHEN exchanging SHOULD accept it")
    void given_lower_case_bearer_when_exchanging_should_accept() {
        issuer.exchangeTokenType("bearer");

        assertThat(exchange(session("k", 0), SERVICE)).isNotBlank();
    }

    @Test
    @DisplayName("GIVEN successes and failures WHEN exchanging SHOULD never put a token in a message or log line")
    void given_exchanges_when_succeeding_or_failing_should_never_expose_tokens() {
        String issued = exchange(session("ok", 0), SERVICE);
        List<Throwable> failures = new ArrayList<>();
        for (int status : new int[] {400, 401, 403, 500}) {
            issuer.exchangeStatus(status).exchangeError(status == 500 ? "server_error" : "invalid_target");
            try {
                exchange(session("bad" + status, 0), SERVICE);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }

        assertThat(failures).hasSize(4);
        for (Throwable failure : failures) {
            assertThat(String.valueOf(failure.getMessage()))
                    .doesNotContain(SUBJECT_TOKEN)
                    .doesNotContain(REFRESH_TOKEN)
                    .doesNotContain(issued);
        }
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage())
                    .doesNotContain(SUBJECT_TOKEN)
                    .doesNotContain(REFRESH_TOKEN)
                    .doesNotContain(issued);
            assertThat(String.valueOf(event.getThrowableProxy())).doesNotContain(SUBJECT_TOKEN);
        }
    }

    @Test
    @DisplayName("GIVEN a cached exchange WHEN the same session asks again SHOULD not call the issuer")
    void given_cached_exchange_when_asking_again_should_not_call_issuer() {
        BffSession session = session("k", 0);

        String first = exchange(session, SERVICE);
        String second = exchange(session, SERVICE);

        assertThat(second).isEqualTo(first);
        assertThat(issuer.exchangeCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a cached exchange WHEN the session version changes SHOULD exchange again")
    void given_cached_exchange_when_session_version_changes_should_exchange_again() {
        exchange(session("k", 0), SERVICE);

        exchange(session("k", 1), SERVICE);

        assertThat(issuer.exchangeCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN two sessions WHEN each exchanges SHOULD not share a cached token")
    void given_two_sessions_when_exchanging_should_not_share() {
        exchange(session("a", 0), SERVICE);
        exchange(session("b", 0), SERVICE);

        assertThat(issuer.exchangeCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a token that lives less than the refresh skew WHEN asked twice SHOULD exchange twice")
    void given_short_lived_token_when_asked_twice_should_exchange_twice() {
        issuer.exchangeExpiresIn(30L);
        BffSession session = session("k", 0);

        exchange(session, SERVICE);
        exchange(session, SERVICE);

        assertThat(issuer.exchangeCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN concurrent callers for one key WHEN exchanging SHOULD make one request")
    void given_concurrent_callers_when_exchanging_should_make_one_request() {
        BffSession session = session("k", 0);

        Flux.range(0, 8)
                .flatMap(i -> exchanger.exchange(session, SERVICE), 8)
                .collectList()
                .block(TIMEOUT);

        assertThat(issuer.exchangeCalls()).isEqualTo(1);
    }
}
