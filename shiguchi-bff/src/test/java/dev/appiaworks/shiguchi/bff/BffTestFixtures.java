package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Shared builders for the bff unit tests. */
final class BffTestFixtures {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    private BffTestFixtures() {}

    /**
     * One provider {@code idp} with explicit endpoints (no discovery); applications {@code eve}
     * (return-to {@code https://eve.example.com/app}) and {@code fin} ({@code https://fin.example.com/}).
     */
    static BffProperties properties() {
        BffProperties properties = new BffProperties();
        properties.setExternalBaseUrl("https://gw.example.com");
        BffProperties.Provider idp = new BffProperties.Provider();
        idp.setIssuerUri("https://id.example.com");
        idp.setClientId("gateway");
        idp.setClientSecret("s3cret");
        idp.setAuthorizationUri("https://id.example.com/authorize");
        idp.setTokenUri("https://id.example.com/token");
        idp.setJwkSetUri("https://id.example.com/jwks");
        idp.setEndSessionUri("https://id.example.com/logout");
        properties.getProviders().put("idp", idp);
        BffProperties.Provider other = new BffProperties.Provider();
        other.setIssuerUri("https://other.example.com");
        other.setClientId("gateway");
        other.setClientSecret("s3cret");
        other.setDisplayName("Other IdP");
        other.setAuthorizationUri("https://other.example.com/authorize");
        other.setTokenUri("https://other.example.com/token");
        other.setJwkSetUri("https://other.example.com/jwks");
        properties.getProviders().put("other", other);
        properties
                .getApplications()
                .put("eve", application("https://eve.example.com", "https://eve.example.com/app", "idp"));
        properties
                .getApplications()
                .put("fin", application("https://fin.example.com", "https://fin.example.com/", "idp", "other"));
        return properties;
    }

    static BffProperties.Application application(String origin, String returnTo, String... providers) {
        BffProperties.Application app = new BffProperties.Application();
        app.setOrigins(List.of(origin));
        app.setReturnTo(List.of(returnTo));
        app.setProviders(List.of(providers));
        return app;
    }

    /** A session created at {@code created}, last seen at {@code lastSeen}, with an access token valid until {@code tokenExp}. */
    static BffSession session(String cookieValue, Instant created, Instant lastSeen, Instant tokenExp) {
        return new BffSession(
                BffSessionKeys.key(cookieValue),
                "alice",
                "idp",
                "eve",
                "sid-1",
                "Alice",
                "alice@example.com",
                null,
                "access-token",
                tokenExp,
                "refresh-token",
                "id-token",
                created,
                lastSeen,
                0);
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
