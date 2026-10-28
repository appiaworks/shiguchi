package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.Disposable;

@DisplayName("Push actuator groups view: lists consumer groups, filters by prefix and drops groups")
class PushEndpointGroupsTest {

    private static final String GROUP = "workers";
    private static final Topic TOPIC = Topic.parse("jobs:q");

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushDlq dlq = new PushDlq(100);
    private final PushPublisher publisher;
    private final PushGroups groups;
    private final PushEndpoint endpoint;
    private final PushReport report;
    private final List<Disposable> streams = new ArrayList<>();
    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
    private final SecurityContext ctx = context(admin);

    PushEndpointGroupsTest() {
        PushProperties.Namespace jobs = new PushProperties.Namespace();
        jobs.setQos(Qos.AT_LEAST_ONCE);
        jobs.setHistorySize(100);
        jobs.setSessionExpiry(Duration.ofHours(1));
        jobs.setSessionKey(PushProperties.SessionKey.GROUP);
        jobs.setAckDeadline(Duration.ofMillis(300));
        jobs.setMaxDeliveries(2);
        jobs.setMaxInflight(2);
        props.getNamespaces().put("jobs", jobs);

        this.publisher = new PushPublisher(props, store, broker, metrics);
        this.groups = new PushGroups(props, store, metrics, dlq);
        this.report = new PushReport(
                props,
                broker,
                store,
                metrics,
                new PushConnections(),
                publisher,
                PushArchive.none(),
                new PushRepublishJobs(),
                new PushAudit());
        report.setGroups(groups);
        this.endpoint = new PushEndpoint(report, props);
    }

    @AfterEach
    void disposeMembers() {
        streams.forEach(Disposable::dispose);
    }

    @Test
    @DisplayName(
            "GIVEN a member that claimed two events WHEN the groups section is read SHOULD respond 200 with one row carrying the group, topic, pending count, members and cursor")
    void given_member_claimed_two_events_when_groups_read_should_respond_ok_with_one_row() {
        claimTwoEvents();

        WebEndpointResponse<Object> response = groupsSection(null);

        assertThat(response.getStatus()).isEqualTo(200);
        List<Map<String, Object>> rows = rows(response);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("group", GROUP)
                .containsEntry("topic", "jobs:q")
                .containsEntry("pending", 2L)
                .containsEntry("members", 1L);
        assertThat(rows.get(0).get("cursor")).isNotNull();
    }

    @Test
    @DisplayName(
            "GIVEN a group named workers WHEN the groups section is read with a matching prefix SHOULD respond 200 with that group")
    void given_group_named_workers_when_groups_read_with_matching_prefix_should_respond_ok_with_row() {
        claimTwoEvents();

        WebEndpointResponse<Object> response = groupsSection("wor");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(rows(response)).extracting(row -> row.get("group")).containsExactly(GROUP);
    }

    @Test
    @DisplayName(
            "GIVEN a group named workers WHEN the groups section is read with a prefix it does not match SHOULD respond 200 with no rows")
    void given_group_named_workers_when_groups_read_with_other_prefix_should_respond_ok_with_no_rows() {
        claimTwoEvents();

        WebEndpointResponse<Object> response = groupsSection("zzz");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(rows(response)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a group with claimed events WHEN an admin deletes it SHOULD respond 200 with the topics dropped and no longer list it")
    void given_group_with_claims_when_deleted_should_respond_ok_and_stop_listing_it() {
        claimTwoEvents();

        WebEndpointResponse<Object> deleted = endpoint.delete("groups", GROUP, null, ctx);

        assertThat(deleted.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) deleted.getBody();
        assertThat(body).containsEntry("group", GROUP);
        assertThat((Long) body.get("dropped")).isGreaterThanOrEqualTo(1L);
        assertThat(rows(groupsSection(null))).isEmpty();
    }

    @Test
    @DisplayName("GIVEN no group with that name WHEN an admin deletes it SHOULD respond 404")
    void given_unknown_group_when_deleted_should_respond_not_found() {
        WebEndpointResponse<Object> response = endpoint.delete("groups", "nobody", null, ctx);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("GIVEN a group with claims WHEN an admin seeks it to first SHOULD respond 200 and audit the seek")
    void given_group_with_claims_when_seeked_should_respond_ok_and_audit_it() {
        claimTwoEvents();

        WebEndpointResponse<Object> response =
                endpoint.republish("groups", GROUP, "seek", "jobs:q", null, null, null, "first", null, null, null, ctx);

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("seeked", true).containsEntry("to", "first");
        assertThat(report.auditLog(10)).anyMatch(e -> "seek_group".equals(e.get("action")));
    }

    @Test
    @DisplayName("GIVEN no group with that name WHEN an admin seeks it SHOULD respond 404")
    void given_unknown_group_when_seeked_should_respond_not_found() {
        WebEndpointResponse<Object> response = endpoint.republish(
                "groups", "nobody", "seek", "jobs:q", null, null, null, "head", null, null, null, ctx);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("GIVEN a seek without a target id WHEN an admin posts it SHOULD respond 400")
    void given_seek_without_target_when_posted_should_respond_bad_request() {
        WebEndpointResponse<Object> response =
                endpoint.republish("groups", GROUP, "seek", "jobs:q", null, null, null, null, null, null, null, ctx);

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("GIVEN the endpoint's sections WHEN they are listed SHOULD include groups")
    void given_endpoint_sections_when_listed_should_include_groups() {
        assertThat(PushEndpoint.sections()).contains("groups");
    }

    /** One member subscribed (a new group starts at the head), then two events published and claimed by it. */
    private void claimTwoEvents() {
        List<PushEvent> received = new CopyOnWriteArrayList<>();
        streams.add(groups.memberStream(TOPIC, props.getNamespaces().get("jobs"), GROUP, "m1", broker.subscribe(TOPIC))
                .subscribe(received::add));
        publish("{\"n\":1}");
        publish("{\"n\":2}");
        assertThat(awaitTrue(() -> received.size() == 2, Duration.ofSeconds(2))).isTrue();
    }

    private void publish(String json) {
        publisher
                .publish(TOPIC, json.getBytes(StandardCharsets.UTF_8), Map.of(), null)
                .block();
    }

    /** The groups section with a name prefix (q) and every other filter, limit included, left unset. */
    private WebEndpointResponse<Object> groupsSection(String prefix) {
        return endpoint.section("groups", prefix, null, null, null, null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(WebEndpointResponse<Object> response) {
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getBody()).get("groups");
    }

    private static boolean awaitTrue(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** A security context whose principal is the given authentication. */
    private static SecurityContext context(Authentication auth) {
        return new SecurityContext() {
            @Override
            public Principal getPrincipal() {
                return auth;
            }

            @Override
            public boolean isUserInRole(String role) {
                return false;
            }
        };
    }
}
