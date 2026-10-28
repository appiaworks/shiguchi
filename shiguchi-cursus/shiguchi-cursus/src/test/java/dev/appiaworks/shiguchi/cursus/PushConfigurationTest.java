package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@DisplayName("Push configuration validation")
class PushConfigurationTest {

    @Test
    @DisplayName("no namespaces throws IllegalStateException")
    void given_no_namespaces_when_validated_should_throw() {
        PushProperties properties = new PushProperties();

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no namespaces");
    }

    @Test
    @DisplayName("GIVEN principal-claim on a namespace whose auth is not PRINCIPAL_MATCH WHEN validated SHOULD fail")
    void given_principalClaim_without_principalMatch_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.AUTHENTICATED);
        ns.setPrincipalClaim("preferred_username");
        properties.getNamespaces().put("user", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("principal-claim needs auth PRINCIPAL_MATCH");

        ns.setAuth(PushProperties.SubscribeAuth.PRINCIPAL_MATCH);
        PushConfiguration.validate(properties, false);
    }

    @Test
    @DisplayName("GIVEN a session-check-interval under 5 seconds WHEN validated SHOULD fail, and 5 seconds is accepted")
    void given_short_session_check_interval_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        properties.getNamespaces().put("orders", new PushProperties.Namespace());
        properties.setSessionCheckInterval(Duration.ofSeconds(4));

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-check-interval must be at least 5s");

        properties.setSessionCheckInterval(Duration.ofSeconds(5));
        PushConfiguration.validate(properties, false);
    }

    @Test
    @DisplayName("AT_LEAST_ONCE with history-size 0 throws IllegalStateException")
    void given_at_least_once_without_history_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_LEAST_ONCE);
        ns.setHistorySize(0);
        properties.getNamespaces().put("orders", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AT_LEAST_ONCE needs history-size");
    }

    @Test
    @DisplayName(
            "GIVEN session-expiry above zero on an AT_MOST_ONCE anonymous namespace WHEN validated SHOULD name the qos and the session-key problems")
    void given_session_expiry_nonzero_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setSessionExpiry(Duration.ofMinutes(1));
        properties.getNamespaces().put("orders", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-expiry above 0 needs qos AT_LEAST_ONCE")
                .hasMessageContaining("session-key PRINCIPAL needs an authenticated subscriber");
    }

    @Test
    @DisplayName(
            "GIVEN a persistent AT_LEAST_ONCE namespace with a history and a CLIENT session key WHEN validated SHOULD pass, and max-queued above history-size SHOULD not")
    void given_persistent_namespace_when_validated_should_checkMaxQueued() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_LEAST_ONCE);
        ns.setHistorySize(100);
        ns.setSessionExpiry(Duration.ofHours(1));
        ns.setSessionKey(PushProperties.SessionKey.CLIENT);
        properties.getNamespaces().put("alerts", ns);

        PushConfiguration.validate(properties, false);

        ns.setMaxQueued(500);
        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-queued (500) cannot exceed history-size (100)");
    }

    @Test
    @DisplayName("AUTHORIZER without custom authorizer throws IllegalStateException")
    void given_authorizer_auth_without_custom_authorizer_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.AUTHORIZER);
        properties.getNamespaces().put("orders", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTHORIZER needs a GatewayAuthorizer");
    }

    @Test
    @DisplayName("AUTHORIZER with custom authorizer succeeds")
    void given_authorizer_auth_with_custom_authorizer_when_validated_should_pass() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.AUTHORIZER);
        properties.getNamespaces().put("orders", ns);

        // Should not throw
        PushConfiguration.validate(properties, true);
    }

    @Test
    @DisplayName("invalid namespace name throws IllegalStateException")
    void given_invalid_namespace_name_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        properties.getNamespaces().put("bad name", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid topic namespace");
    }

    @Test
    @DisplayName("valid config passes")
    void given_valid_config_when_validated_should_pass() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_MOST_ONCE);
        ns.setAuth(PushProperties.SubscribeAuth.NONE);
        ns.setPublishAuth(PushProperties.PublishAuth.AUTHENTICATED);
        properties.getNamespaces().put("orders", ns);

        // Should not throw
        PushConfiguration.validate(properties, false);
    }

    @Test
    @DisplayName("ROLE auth without roles throws IllegalStateException")
    void given_role_auth_without_roles_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.ROLE);
        ns.setRoles(java.util.List.of());
        properties.getNamespaces().put("orders", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROLE needs roles");
    }

    @Test
    @DisplayName("PUBLISH_ROLE without publish-roles throws IllegalStateException")
    void given_publish_role_auth_without_publish_roles_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setPublishAuth(PushProperties.PublishAuth.ROLE);
        ns.setPublishRoles(java.util.List.of());
        properties.getNamespaces().put("orders", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROLE needs publish-roles");
    }

    @Test
    @DisplayName("max-message-bytes must be above 0")
    void given_max_message_bytes_zero_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        properties.getNamespaces().put("orders", ns);
        properties.getConnection().setMaxMessageBytes(0);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-message-bytes must be above 0");
    }

    @Test
    @DisplayName("client-queue-size must be above 0")
    void given_client_queue_size_zero_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setClientQueueSize(0);
        properties.getNamespaces().put("orders", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-queue-size must be above 0");
    }

    @Test
    @DisplayName("normalizePath null returns /push")
    void given_null_path_when_normalized_should_return_default() {
        assertThat(PushConfiguration.normalizePath(null)).isEqualTo("/push");
    }

    @Test
    @DisplayName("normalizePath 'push/' returns /push")
    void given_path_with_trailing_slash_when_normalized_should_strip_slash() {
        assertThat(PushConfiguration.normalizePath("push/")).isEqualTo("/push");
    }

    @Test
    @DisplayName("normalizePath '/x//' returns /x")
    void given_path_with_double_slash_when_normalized_should_strip_trailing_slashes() {
        assertThat(PushConfiguration.normalizePath("/x//")).isEqualTo("/x");
    }

    @Test
    @DisplayName("normalizePath without leading slash adds it")
    void given_path_without_leading_slash_when_normalized_should_add_slash() {
        assertThat(PushConfiguration.normalizePath("api/push")).isEqualTo("/api/push");
    }

    @Test
    @DisplayName("normalizePath with leading slash preserves it")
    void given_path_with_leading_slash_when_normalized_should_preserve() {
        assertThat(PushConfiguration.normalizePath("/api/push")).isEqualTo("/api/push");
    }

    @Test
    @DisplayName("GIVEN retention of zero WHEN the archive bean is built SHOULD be the disabled archive")
    void given_zero_retention_when_archive_built_should_be_disabled_archive() {
        PushProperties properties = validProperties();
        properties.getArchive().setRetention(Duration.ZERO);

        PushArchive archive = new PushConfiguration().pushArchive(properties, new MemoryPushStore());

        assertThat(archive).isSameAs(PushArchive.none());
    }

    @Test
    @DisplayName(
            "GIVEN retention above zero on the memory store WHEN the archive bean is built SHOULD be a memory archive")
    void given_retention_on_memory_store_when_archive_built_should_be_memory_archive() {
        PushProperties properties = validProperties();
        properties.getArchive().setRetention(Duration.ofHours(1));

        PushArchive archive = new PushConfiguration().pushArchive(properties, new MemoryPushStore());

        assertThat(archive).isInstanceOf(MemoryPushArchive.class);
    }

    @Test
    @DisplayName(
            "GIVEN retention above zero on the Redis store WHEN the archive bean is built SHOULD be the Redis archive")
    void given_retention_on_redis_store_when_archive_built_should_be_redis_archive() {
        PushProperties properties = validProperties();
        properties.getArchive().setRetention(Duration.ofHours(1));
        dev.appiaworks.shiguchi.cursus.redis.RedisPushStore store =
                new dev.appiaworks.shiguchi.cursus.redis.RedisPushStore(
                        org.mockito.Mockito.mock(org.springframework.data.redis.core.ReactiveStringRedisTemplate.class),
                        "p:");

        PushArchive archive = new PushConfiguration().pushArchive(properties, store);

        assertThat(archive).isInstanceOf(dev.appiaworks.shiguchi.cursus.redis.RedisPushArchive.class);
    }

    @Test
    @DisplayName(
            "GIVEN retention above zero on a store without an archive WHEN the archive bean is built SHOULD throw naming PushArchive")
    void given_retention_on_store_without_archive_when_archive_built_should_throw() {
        PushProperties properties = validProperties();
        properties.getArchive().setRetention(Duration.ofHours(1));

        assertThatThrownBy(() -> new PushConfiguration().pushArchive(properties, new NoArchiveStore()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PushArchive");
    }

    @Test
    @DisplayName("GIVEN a republish batch limit of zero WHEN validated SHOULD throw IllegalStateException")
    void given_zero_max_batch_when_validated_should_throw() {
        PushProperties properties = validProperties();
        properties.getAdmin().getRepublish().setMaxBatch(0);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin.republish.max-batch must be above 0");
    }

    @Test
    @DisplayName("GIVEN a republish rate of zero WHEN validated SHOULD throw IllegalStateException")
    void given_zero_rate_per_second_when_validated_should_throw() {
        PushProperties properties = validProperties();
        properties.getAdmin().getRepublish().setRatePerSecond(0);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin.republish.rate-per-second must be above 0");
    }

    @Test
    @DisplayName("GIVEN a republish namespace that is not declared WHEN validated SHOULD throw IllegalStateException")
    void given_undeclared_republish_namespace_when_validated_should_throw() {
        PushProperties properties = validProperties();
        properties.getAdmin().getRepublish().setNamespaces(List.of("ticker"));

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'ticker', which is not declared");
    }

    @Test
    @DisplayName("GIVEN an archive memory cap of zero WHEN validated SHOULD throw IllegalStateException")
    void given_zero_memory_max_events_when_validated_should_throw() {
        PushProperties properties = validProperties();
        properties.getArchive().setMemoryMaxEvents(0);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archive.memory-max-events must be above 0");
    }

    @Test
    @DisplayName("GIVEN a payload-type that is not a proto full name WHEN validated SHOULD throw naming payload-type")
    void given_payload_type_with_space_when_validated_should_throw() {
        PushProperties properties = validProperties();
        properties.getNamespaces().get("orders").setPayloadType("has space");

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload-type");
    }

    @Test
    @DisplayName("GIVEN a graphql-field that is not a GraphQL name WHEN validated SHOULD throw naming graphql-field")
    void given_graphql_field_with_dash_when_validated_should_throw() {
        PushProperties properties = validProperties();
        properties.getNamespaces().get("orders").setGraphqlField("bad-name");

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("graphql-field");
    }

    @Test
    @DisplayName("GIVEN a valid payload-type and graphql-field WHEN validated SHOULD pass")
    void given_valid_payload_type_and_graphql_field_when_validated_should_pass() {
        PushProperties properties = validProperties();
        properties.getNamespaces().get("orders").setPayloadType("acme.OrderEvent");
        properties.getNamespaces().get("orders").setGraphqlField("orderEvents");

        // Should not throw
        PushConfiguration.validate(properties, false);
    }

    @Test
    @DisplayName("GIVEN no graphql-field WHEN the field name is resolved SHOULD default to the namespace plus Events")
    void given_blank_graphql_field_when_resolved_should_default_to_namespace_events() {
        PushProperties.Namespace ns = new PushProperties.Namespace();

        assertThat(ns.graphqlFieldOr("orders")).isEqualTo("ordersEvents");
    }

    @Test
    @DisplayName("GIVEN a configured graphql-field WHEN the field name is resolved SHOULD return the configured name")
    void given_configured_graphql_field_when_resolved_should_return_configured_name() {
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setGraphqlField("orderEvents");

        assertThat(ns.graphqlFieldOr("orders")).isEqualTo("orderEvents");
    }

    @Test
    @DisplayName(
            "GIVEN a namespace with ordering KEY but no session-key GROUP WHEN validated SHOULD say ordering needs GROUP")
    void given_ordering_without_group_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setOrdering(PushProperties.Ordering.KEY);
        properties.getNamespaces().put("jobs", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ordering KEY needs session-key GROUP");
    }

    @Test
    @DisplayName(
            "GIVEN a GROUP namespace with AT_MOST_ONCE and no session-expiry WHEN validated SHOULD name the qos and the session-expiry problems")
    void given_group_namespace_without_at_least_once_or_expiry_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_MOST_ONCE);
        ns.setSessionKey(PushProperties.SessionKey.GROUP);
        ns.setSessionExpiry(Duration.ZERO);
        properties.getNamespaces().put("jobs", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-key GROUP needs qos AT_LEAST_ONCE")
                .hasMessageContaining("session-key GROUP needs session-expiry above 0");
    }

    @Test
    @DisplayName(
            "GIVEN a GROUP namespace with max-deliveries 0 WHEN validated SHOULD throw IllegalStateException naming max-deliveries")
    void given_group_namespace_with_zero_max_deliveries_when_validated_should_throw() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_LEAST_ONCE);
        ns.setHistorySize(10);
        ns.setSessionExpiry(Duration.ofHours(1));
        ns.setSessionKey(PushProperties.SessionKey.GROUP);
        ns.setMaxDeliveries(0);
        properties.getNamespaces().put("jobs", ns);

        assertThatThrownBy(() -> PushConfiguration.validate(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-deliveries must be at least 1");
    }

    /** One valid namespace, so each test trips only the rule it names. */
    private static PushProperties validProperties() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_MOST_ONCE);
        ns.setAuth(PushProperties.SubscribeAuth.NONE);
        ns.setPublishAuth(PushProperties.PublishAuth.AUTHENTICATED);
        properties.getNamespaces().put("orders", ns);
        return properties;
    }

    /** A store that keeps history but has no archive of its own. */
    private static final class NoArchiveStore implements PushStore {

        @Override
        public Mono<PushEvent> append(PushEvent event, int historySize, Duration historyTtl) {
            return Mono.just(event);
        }

        @Override
        public Flux<PushEvent> range(Topic topic, String afterId, int limit) {
            return Flux.empty();
        }

        @Override
        public int compareIds(String a, String b) {
            return a.compareTo(b);
        }

        @Override
        public long historyLength(Topic topic) {
            return 0;
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, false);
        }
    }
}
