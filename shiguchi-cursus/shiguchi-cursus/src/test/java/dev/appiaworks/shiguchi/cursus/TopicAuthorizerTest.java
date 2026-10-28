package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.test.StepVerifier;

@DisplayName("Topic authorization")
class TopicAuthorizerTest {

    private final Topic topic = Topic.parse("orders:123");
    private final TopicAuthorizer authorizer = new TopicAuthorizer(null);

    @Test
    @DisplayName("NONE auth allows an anonymous caller")
    void given_none_auth_when_checked_should_allow_anonymous() {
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.NONE);

        StepVerifier.create(authorizer.authorizeSubscribe(null, topic, ns)).verifyComplete();
    }

    @Test
    @DisplayName("AUTHENTICATED with null auth errors with UNAUTHORIZED")
    void given_authenticated_auth_and_no_principal_when_checked_should_error_unauthorized() {
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.AUTHENTICATED);

        StepVerifier.create(authorizer.authorizeSubscribe(null, topic, ns))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(401);
                })
                .verify();
    }

    @Test
    @DisplayName("AUTHENTICATED with authenticated principal succeeds")
    void given_authenticated_auth_and_principal_when_checked_should_succeed() {
        var auth = new TestingAuthenticationToken("user", "pwd", "ROLE_USER");
        auth.setAuthenticated(true);

        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.AUTHENTICATED);

        StepVerifier.create(authorizer.authorizeSubscribe(auth, topic, ns)).verifyComplete();
    }

    @Test
    @DisplayName("ROLE accepts authority with matching role")
    void given_role_auth_with_matching_role_when_checked_should_succeed() {
        var auth = new TestingAuthenticationToken("user", "pwd", List.of(new SimpleGrantedAuthority("ROLE_ops")));
        auth.setAuthenticated(true);

        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.ROLE);
        ns.setRoles(List.of("ops"));

        StepVerifier.create(authorizer.authorizeSubscribe(auth, topic, ns)).verifyComplete();
    }

    @Test
    @DisplayName("ROLE rejects when role is missing")
    void given_role_auth_without_matching_role_when_checked_should_error_forbidden() {
        var auth = new TestingAuthenticationToken("user", "pwd", List.of(new SimpleGrantedAuthority("ROLE_user")));
        auth.setAuthenticated(true);

        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.ROLE);
        ns.setRoles(List.of("ops"));

        StepVerifier.create(authorizer.authorizeSubscribe(auth, topic, ns))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    @DisplayName("PRINCIPAL_MATCH allows user:alice for principal alice")
    void given_principal_match_when_topic_matches_principal_should_succeed() {
        var auth = new TestingAuthenticationToken("alice", "pwd");
        auth.setAuthenticated(true);

        Topic aliceTopic = Topic.parse("user:alice");
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.PRINCIPAL_MATCH);

        StepVerifier.create(authorizer.authorizeSubscribe(auth, aliceTopic, ns)).verifyComplete();
    }

    @Test
    @DisplayName("PRINCIPAL_MATCH allows user:alice/inbox for principal alice")
    void given_principal_match_with_slash_when_topic_starts_with_principal_should_succeed() {
        var auth = new TestingAuthenticationToken("alice", "pwd");
        auth.setAuthenticated(true);

        Topic inboxTopic = Topic.parse("user:alice/inbox");
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.PRINCIPAL_MATCH);

        StepVerifier.create(authorizer.authorizeSubscribe(auth, inboxTopic, ns)).verifyComplete();
    }

    @Test
    @DisplayName("PRINCIPAL_MATCH rejects user:bob for principal alice")
    void given_principal_match_when_topic_does_not_match_principal_should_error_forbidden() {
        var auth = new TestingAuthenticationToken("alice", "pwd");
        auth.setAuthenticated(true);

        Topic bobTopic = Topic.parse("user:bob");
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.PRINCIPAL_MATCH);

        StepVerifier.create(authorizer.authorizeSubscribe(auth, bobTopic, ns))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    @DisplayName("CLAIM allows when JWT claim matches pattern")
    void given_claim_auth_with_matching_claim_when_checked_should_succeed() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .claim("push", Map.of("subscribe", List.of("orders:*")))
                .build();
        var auth = new JwtAuthenticationToken(jwt, List.of()); // the authorities form is the authenticated one

        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.CLAIM);
        ns.setClaim("push.subscribe");

        StepVerifier.create(authorizer.authorizeSubscribe(auth, topic, ns)).verifyComplete();
    }

    @Test
    @DisplayName("CLAIM rejects when JWT claim does not match")
    void given_claim_auth_without_matching_claim_when_checked_should_error_forbidden() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .claim("push", Map.of("subscribe", List.of("users:*")))
                .build();
        var auth = new JwtAuthenticationToken(jwt, List.of()); // the authorities form is the authenticated one

        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.CLAIM);
        ns.setClaim("push.subscribe");

        StepVerifier.create(authorizer.authorizeSubscribe(auth, topic, ns))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    @DisplayName("AUTHORIZER with custom GatewayAuthorizer delegates decision")
    void given_authorizer_auth_with_custom_authorizer_when_checked_should_delegate() {
        GatewayAuthorizer customAuthorizer = (req) -> reactor.core.publisher.Mono.empty();
        TopicAuthorizer topicAuth = new TopicAuthorizer(customAuthorizer);

        var auth = new TestingAuthenticationToken("user", "pwd");
        auth.setAuthenticated(true);

        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.AUTHORIZER);

        StepVerifier.create(topicAuth.authorizeSubscribe(auth, topic, ns)).verifyComplete();
    }

    @Test
    @DisplayName("AUTHORIZER with null authorizer errors with IllegalStateException")
    void given_authorizer_auth_without_custom_authorizer_when_checked_should_error() {
        var auth = new TestingAuthenticationToken("user", "pwd");
        auth.setAuthenticated(true);

        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.AUTHORIZER);

        StepVerifier.create(authorizer.authorizeSubscribe(auth, topic, ns))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(IllegalStateException.class);
                    assertThat(e.getMessage()).contains("no GatewayAuthorizer bean");
                })
                .verify();
    }

    @Test
    @DisplayName("authorizePublish with NONE allows anyone")
    void given_publish_none_auth_when_checked_should_allow_anyone() {
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setPublishAuth(PushProperties.PublishAuth.NONE);

        StepVerifier.create(authorizer.authorizePublish(null, topic, ns)).verifyComplete();
    }

    @Test
    @DisplayName("authorizePublish with AUTHENTICATED rejects anonymous")
    void given_publish_authenticated_auth_without_principal_when_checked_should_error() {
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setPublishAuth(PushProperties.PublishAuth.AUTHENTICATED);

        StepVerifier.create(authorizer.authorizePublish(null, topic, ns))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(401);
                })
                .verify();
    }

    private static JwtAuthenticationToken jwtAuth(String subject, Map<String, Object> claims) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject);
        claims.forEach(jwt::claim);
        return new JwtAuthenticationToken(jwt.build(), List.of());
    }

    private static PushProperties.Namespace principalMatchOn(String principalClaim) {
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setAuth(PushProperties.SubscribeAuth.PRINCIPAL_MATCH);
        ns.setPrincipalClaim(principalClaim);
        return ns;
    }

    private static void assertForbidden(reactor.core.publisher.Mono<Void> check) {
        StepVerifier.create(check)
                .expectErrorSatisfies(
                        e -> assertThat(((PushException) e).status().value()).isEqualTo(403))
                .verify();
    }

    @Test
    @DisplayName("GIVEN PRINCIPAL_MATCH on preferred_username WHEN the topic is that claim's value SHOULD allow it")
    void given_principalClaim_when_topicIsClaimValue_should_allow() {
        var auth = jwtAuth("f81d4fae", Map.of("preferred_username", "alice"));

        StepVerifier.create(authorizer.authorizeSubscribe(
                        auth, Topic.parse("user:alice/inbox"), principalMatchOn("preferred_username")))
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN PRINCIPAL_MATCH on preferred_username WHEN the topic is the subject SHOULD refuse it")
    void given_principalClaim_when_topicIsSubject_should_refuse() {
        var auth = jwtAuth("f81d4fae", Map.of("preferred_username", "alice"));

        assertForbidden(authorizer.authorizeSubscribe(
                auth, Topic.parse("user:f81d4fae"), principalMatchOn("preferred_username")));
    }

    @Test
    @DisplayName("GIVEN PRINCIPAL_MATCH on a nested claim WHEN the topic is its value SHOULD allow it")
    void given_nestedPrincipalClaim_when_topicIsClaimValue_should_allow() {
        var auth = jwtAuth("f81d4fae", Map.of("account", Map.of("id", "acct-7")));

        StepVerifier.create(
                        authorizer.authorizeSubscribe(auth, Topic.parse("user:acct-7"), principalMatchOn("account.id")))
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN PRINCIPAL_MATCH on a claim WHEN the token lacks it SHOULD refuse with 403")
    void given_principalClaim_when_claimMissing_should_refuse() {
        var auth = jwtAuth("alice", Map.of());

        assertForbidden(
                authorizer.authorizeSubscribe(auth, Topic.parse("user:alice"), principalMatchOn("preferred_username")));
    }

    @Test
    @DisplayName("GIVEN PRINCIPAL_MATCH on a claim WHEN the claim is not a string SHOULD refuse with 403")
    void given_principalClaim_when_claimNotString_should_refuse() {
        var auth = jwtAuth("alice", Map.of("preferred_username", List.of("alice")));

        assertForbidden(
                authorizer.authorizeSubscribe(auth, Topic.parse("user:alice"), principalMatchOn("preferred_username")));
    }

    @Test
    @DisplayName("GIVEN PRINCIPAL_MATCH with a blank principal claim WHEN checked SHOULD match the principal's name")
    void given_blankPrincipalClaim_when_checked_should_useName() {
        var auth = jwtAuth("alice", Map.of("preferred_username", "bob"));

        StepVerifier.create(authorizer.authorizeSubscribe(auth, Topic.parse("user:alice"), principalMatchOn(" ")))
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a principal a topic cannot carry WHEN checked SHOULD refuse with 403")
    void given_principalOutsideTopicAlphabet_when_checked_should_refuse() {
        var auth = jwtAuth("auth0|abc", Map.of());

        assertForbidden(authorizer.authorizeSubscribe(auth, Topic.parse("user:auth0"), principalMatchOn(null)));
    }
}
