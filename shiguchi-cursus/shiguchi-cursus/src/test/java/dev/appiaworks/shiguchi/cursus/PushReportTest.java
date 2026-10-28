package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.Disposable;

@DisplayName("Push report")
class PushReportTest {

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushConnections connections = new PushConnections();
    private final PushReport report;

    PushReportTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(10);
        props.getNamespaces().put("orders", orders);
        props.getNamespaces().put("ticker", new PushProperties.Namespace());
        PushPublisher publisher = new PushPublisher(props, store, broker, metrics);
        report = new PushReport(props, broker, store, metrics, connections, publisher);
    }

    @Test
    @DisplayName("the overview lists instance, engine, totals and the endpoint path")
    void given_declared_namespaces_when_overview_requested_should_list_every_section() {
        Map<String, Object> overview = report.overview();

        assertThat(overview)
                .containsKeys(
                        "instance",
                        "connections",
                        "subscriptions",
                        "activeTopics",
                        "namespaces",
                        "engine",
                        "totals",
                        "perSecond",
                        "path")
                .containsEntry("namespaces", 2)
                .containsEntry("path", "/push");
        assertThat(overview.get("engine"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("broker", "MEMORY")
                .containsEntry("store", "MEMORY");
    }

    @Test
    @DisplayName("an open SSE connection with two subscriptions is counted in the overview")
    void given_sse_connection_with_two_subscriptions_when_overview_requested_should_count_them() {
        metrics.connectionOpened("sse", 2);

        Map<String, Object> overview = report.overview();

        assertThat(overview.get("connections"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("total", 1)
                .containsEntry("sse", 1);
        assertThat(overview).containsEntry("subscriptions", 2);
    }

    @Test
    @DisplayName("the namespaces list shows each declared namespace with its delivery and auth policy")
    void given_two_declared_namespaces_when_listed_should_report_policy() {
        List<Map<String, Object>> namespaces = report.namespaces();

        assertThat(namespaces.stream().map(m -> m.get("name")).toList()).containsExactlyInAnyOrder("orders", "ticker");
        Map<String, Object> orders = namespaces.stream()
                .filter(m -> "orders".equals(m.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(orders)
                .containsEntry("qos", "AT_LEAST_ONCE")
                .containsEntry("historySize", 10)
                .containsEntry("auth", "NONE")
                .containsEntry("publishAuth", "AUTHENTICATED");
    }

    @Test
    @DisplayName("with no subscribers the topic list is empty")
    void given_no_subscribers_when_topics_listed_should_be_empty() {
        assertThat(report.topics(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("a topic with a live subscriber is listed with its subscriber count and history size")
    void given_live_subscriber_when_topics_listed_should_report_subscribers_and_history() {
        Disposable subscriber = broker.subscribe(Topic.parse("orders:1")).subscribe();
        try {
            assertThat(report.topics(null, 10))
                    .containsExactly(Map.of("topic", "orders:1", "subscribers", 1L, "history", 0L));
        } finally {
            subscriber.dispose();
        }
    }

    @Test
    @DisplayName("the topic query keeps only topics whose name contains the substring")
    void given_subscribers_on_two_topics_when_queried_by_substring_should_return_matching_only() {
        Disposable orders = broker.subscribe(Topic.parse("orders:1")).subscribe();
        Disposable ticker = broker.subscribe(Topic.parse("ticker:btc")).subscribe();
        try {
            List<Object> names = report.topics("ticker", 10).stream()
                    .map(m -> m.get("topic"))
                    .toList();
            assertThat(names).containsExactly("ticker:btc");
        } finally {
            orders.dispose();
            ticker.dispose();
        }
    }

    @Test
    @DisplayName("without filters the connections listing shows every open connection")
    void given_two_open_connections_when_listed_without_filters_should_return_both() {
        connections.open("sse", authenticated("alice"));
        connections.open("ws", null);

        assertThat(report.connections(null, null, null)).hasSize(2);
    }

    @Test
    @DisplayName("filtering connections by principal keeps only that principal's connections")
    void given_connections_for_two_principals_when_filtered_by_principal_should_return_one() {
        connections.open("sse", authenticated("alice"));
        connections.open("sse", authenticated("bob"));

        assertThat(report.connections("alice", null, null))
                .extracting(PushConnections.Info::principal)
                .containsExactly("alice");
    }

    @Test
    @DisplayName("filtering connections by topic keeps only the connections listening to it")
    void given_connection_listening_to_topic_when_filtered_by_topic_should_return_only_listener() {
        PushConnections.Live listening = connections.open("sse", authenticated("alice"));
        listening.topics().add(Topic.parse("orders:1"));
        connections.open("sse", authenticated("bob"));

        assertThat(report.connections(null, "orders:1", null))
                .extracting(PushConnections.Info::id)
                .containsExactly(listening.id());
    }

    @Test
    @DisplayName("filtering connections by transport keeps only that transport")
    void given_sse_and_ws_connections_when_filtered_by_transport_should_return_only_that_transport() {
        connections.open("sse", authenticated("alice"));
        connections.open("ws", authenticated("bob"));

        assertThat(report.connections(null, null, "ws"))
                .extracting(PushConnections.Info::transport)
                .containsExactly("ws");
    }

    @Test
    @DisplayName(
            "GIVEN a live subscriber and no upstream attached WHEN topics are listed SHOULD report no upstream and zero upstream patterns")
    void given_liveSubscriberWithoutUpstreams_when_topicsListed_should_reportNullUpstreamAndZeroPatterns() {
        report.setUpstreams(null);
        Disposable subscriber = broker.subscribe(Topic.parse("orders:1")).subscribe();
        try {
            Map<String, Object> row = report.topics(null, 10).get(0);

            assertThat(row).doesNotContainKey("upstream");
            @SuppressWarnings("unchecked")
            Map<String, Object> engine = (Map<String, Object>) report.overview().get("engine");
            assertThat(engine.get("upstreams"))
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("patterns", 0);
        } finally {
            subscriber.dispose();
        }
    }

    private static Authentication authenticated(String name) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(name, "pwd");
        token.setAuthenticated(true);
        return token;
    }
}
