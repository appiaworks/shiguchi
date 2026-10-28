package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.reactive.function.client.WebClient;

@DisplayName("BffSessions and its actuator endpoint: ending a user's sessions from the application's side")
class BffSessionsTest {

    private MemoryBffSessionStore memory;
    private SimpleMeterRegistry meters;
    private List<String> ended;
    private BffProperties properties;
    private BffSessions sessions;

    @BeforeEach
    void setUp() {
        memory = new MemoryBffSessionStore();
        meters = new SimpleMeterRegistry();
        ended = new CopyOnWriteArrayList<>();
        properties = BffTestFixtures.properties();
        sessions = new BffSessions(
                properties,
                new BffEndedSessionStore(memory, e -> ended.add(e.handle())),
                new BffLogoutMeter(meters),
                new BffRevocation(new BffProviders(properties, WebClient.builder()), meters));
        save("k1", "idp", "alice");
        save("k2", "idp", "alice");
        save("k3", "idp", "bob");
        save("k4", "other", "alice");
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    private void save(String key, String provider, String subject) {
        Instant now = Instant.now();
        memory.save(
                        new BffSession(
                                key,
                                subject,
                                provider,
                                "eve",
                                "sid-" + key,
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

    private static SecurityContext caller(String... authorities) {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getPrincipal()).thenReturn(new TestingAuthenticationToken("ops", "n/a", authorities));
        return ctx;
    }

    @Test
    @DisplayName(
            "GIVEN a user with two sessions WHEN revoking the subject SHOULD end both, count 2 and publish both events")
    void given_two_sessions_when_revoking_subject_should_end_both() {
        Integer count = sessions.revokeSubject("idp", "alice").block();

        assertThat(count).isEqualTo(2);
        assertThat(memory.find("k1").blockOptional()).isEmpty();
        assertThat(memory.find("k2").blockOptional()).isEmpty();
        assertThat(memory.find("k3").blockOptional()).isPresent();
        assertThat(memory.find("k4").blockOptional()).isPresent();
        assertThat(ended).containsExactlyInAnyOrder("k1", "k2");
        assertThat(meters.get("gateway.bff.logout")
                        .tag("kind", "admin")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("GIVEN a provider that is not configured WHEN revoking SHOULD fail naming it")
    void given_unknown_provider_when_revoking_should_fail() {
        assertThatThrownBy(() -> sessions.revokeSubject("nobody", "alice").block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nobody");
    }

    @Test
    @DisplayName("GIVEN a handle WHEN revoking it SHOULD end that session once and answer false for a missing one")
    void given_handle_when_revoking_should_end_that_session() {
        assertThat(sessions.revoke("k3").block()).isTrue();
        assertThat(sessions.revoke("k3").block()).isFalse();
        assertThat(sessions.revoke(null).block()).isFalse();

        assertThat(memory.find("k3").blockOptional()).isEmpty();
        assertThat(ended).containsExactly("k3");
    }

    @Test
    @DisplayName("GIVEN the endpoint WHEN reading a subject SHOULD show the count and times but no tokens or handles")
    void given_endpoint_when_reading_should_show_count_and_times_only() {
        properties.getAdmin().setRole("ops");
        BffSessionsEndpoint endpoint = new BffSessionsEndpoint(sessions, properties);

        WebEndpointResponse<Map<String, Object>> response = endpoint.read("idp", "alice", caller("ROLE_ops"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("count", 2).containsEntry("subject", "alice");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) response.getBody().get("sessions");
        assertThat(rows).hasSize(2);
        assertThat(rows)
                .allSatisfy(row -> assertThat(row.keySet()).containsExactly("application", "created", "lastSeen"));
    }

    @Test
    @DisplayName("GIVEN the endpoint WHEN deleting a subject SHOULD revoke all of its sessions and answer the count")
    void given_endpoint_when_deleting_should_revoke_all() {
        properties.getAdmin().setRole("ops");
        BffSessionsEndpoint endpoint = new BffSessionsEndpoint(sessions, properties);

        WebEndpointResponse<Map<String, Object>> response = endpoint.revoke("idp", "alice", caller("ROLE_ops"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("revoked", 2);
        assertThat(memory.find("k1").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN the endpoint and an unknown provider WHEN reading or deleting SHOULD answer 404")
    void given_endpoint_and_unknown_provider_should_answer_404() {
        properties.getAdmin().setRole("ops");
        BffSessionsEndpoint endpoint = new BffSessionsEndpoint(sessions, properties);

        assertThat(endpoint.read("nobody", "alice", caller("ROLE_ops")).getStatus())
                .isEqualTo(404);
        assertThat(endpoint.revoke("nobody", "alice", caller("ROLE_ops")).getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName(
            "GIVEN admin.role is not set WHEN any caller, authenticated or not, reads or deletes SHOULD answer 403 and end nothing")
    void given_no_admin_role_when_any_caller_reads_or_deletes_should_answer_403() {
        properties.getAdmin().setRole("");
        BffSessionsEndpoint endpoint = new BffSessionsEndpoint(sessions, properties);

        for (SecurityContext ctx : new SecurityContext[] {caller("ROLE_ops", "SCOPE_admin"), caller(), null}) {
            WebEndpointResponse<Map<String, Object>> read = endpoint.read("idp", "alice", ctx);
            WebEndpointResponse<Map<String, Object>> revoke = endpoint.revoke("idp", "alice", ctx);

            assertThat(read.getStatus()).isEqualTo(403);
            assertThat(read.getBody()).containsEntry("error", "admin role not configured");
            assertThat(revoke.getStatus()).isEqualTo(403);
            assertThat(revoke.getBody()).containsEntry("error", "admin role not configured");
        }
        assertThat(memory.find("k1").blockOptional()).isPresent();
        assertThat(memory.find("k2").blockOptional()).isPresent();
        assertThat(ended).isEmpty();
    }

    @Test
    @DisplayName("GIVEN admin.role is set WHEN a caller without it reads or deletes SHOULD answer 403 and end nothing")
    void given_admin_role_when_caller_lacks_it_should_answer_403() {
        properties.getAdmin().setRole("ops");
        BffSessionsEndpoint endpoint = new BffSessionsEndpoint(sessions, properties);

        assertThat(endpoint.read("idp", "alice", caller("ROLE_user")).getStatus())
                .isEqualTo(403);
        assertThat(endpoint.revoke("idp", "alice", caller("ROLE_user")).getStatus())
                .isEqualTo(403);
        assertThat(endpoint.revoke("idp", "alice", null).getStatus()).isEqualTo(403);
        assertThat(memory.find("k1").blockOptional()).isPresent();
    }

    @Test
    @DisplayName("GIVEN admin.role is set WHEN a caller holding it as ROLE_ or SCOPE_ deletes SHOULD revoke")
    void given_admin_role_when_caller_holds_it_should_revoke() {
        properties.getAdmin().setRole("ops");
        BffSessionsEndpoint endpoint = new BffSessionsEndpoint(sessions, properties);

        assertThat(endpoint.revoke("idp", "alice", caller("ROLE_ops")).getStatus())
                .isEqualTo(200);
        assertThat(endpoint.revoke("idp", "bob", caller("SCOPE_ops")).getStatus())
                .isEqualTo(200);
        assertThat(memory.find("k3").blockOptional()).isEmpty();
    }
}
