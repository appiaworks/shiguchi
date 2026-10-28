package dev.appiaworks.shiguchi.sample.opa;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * A {@link GatewayAuthorizer} backed by Open Policy Agent.
 *
 * <h2>How this differs from the OpenFGA sample</h2>
 *
 * Both implement the same SPI, but they use it in opposite ways, which is the useful thing this
 * sample demonstrates:
 *
 * <ul>
 *   <li><b>OpenFGA</b> answers a narrow, fixed question — {@code check(user, relation, object)}.
 *       That sample resolves each {@code authz} rule into a concrete pair and makes one call per
 *       rule, so <em>the gateway</em> decides what is being asked.</li>
 *   <li><b>OPA</b> evaluates arbitrary Rego over an arbitrary {@code input} document and returns
 *       whatever the policy computes. One call carries the whole request, and <em>the policy</em>
 *       decides what matters — including rules the gateway knows nothing about (time of day, IP,
 *       tenant, request body).</li>
 * </ul>
 *
 * So this authorizer sends one document per request rather than N tuple checks, and reads the
 * route's {@code authz} rules structurally instead of flattening them through
 * {@code AuthzRuleResolver}. See {@link OpaInputBuilder} for why.
 *
 * <h2>Contract with the policy</h2>
 *
 * The decision path must yield a boolean. Anything else — a missing {@code result} key (which is
 * what OPA returns when the rule is undefined), a non-boolean, an error, or a timeout — is treated
 * as <b>not allowed</b>, subject to {@code fail-open}. An undefined policy must never read as
 * permission.
 */
public class OpaGatewayAuthorizer implements GatewayAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(OpaGatewayAuthorizer.class);

    private final ScopeGatewayAuthorizer scopeAuthorizer;
    private final WebClient webClient;
    private final OpaAuthzProperties props;
    private final String decisionUri;

    public OpaGatewayAuthorizer(
            ScopeGatewayAuthorizer scopeAuthorizer, WebClient.Builder webClientBuilder, OpaAuthzProperties props) {
        this.scopeAuthorizer = scopeAuthorizer;
        this.props = props;
        this.webClient = webClientBuilder.baseUrl(props.getUrl()).build();
        this.decisionUri = "/v1/data/" + props.getDecisionPath().replaceAll("^/+", "");
    }

    @Override
    public Mono<Void> authorize(AuthorizationRequest request) {
        // Scope gate first: in-process, and it rejects callers who fail the coarse check before
        // spending a network round trip on the policy engine.
        return scopeAuthorizer.authorize(request).then(Mono.defer(() -> policyCheck(request)));
    }

    private Mono<Void> policyCheck(AuthorizationRequest request) {
        Map<String, Object> input = OpaInputBuilder.build(request);

        return webClient
                .post()
                .uri(decisionUri)
                .bodyValue(Map.of("input", input))
                .retrieve()
                .bodyToMono(OpaResponse.class)
                .timeout(props.getTimeout())
                // An undefined rule yields {} with no "result" — that is NOT permission.
                .map(response -> Boolean.TRUE.equals(response.result()))
                .onErrorResume(error -> {
                    log.warn(
                            "OPA decision failed for {}/{} — failing {}",
                            request.route().getFullServiceName(),
                            request.route().getMethodName(),
                            props.isFailOpen() ? "OPEN" : "CLOSED",
                            error);
                    return Mono.just(props.isFailOpen());
                })
                .flatMap(allowed -> {
                    if (allowed) {
                        log.debug(
                                "OPA allow: {}/{}",
                                request.route().getFullServiceName(),
                                request.route().getMethodName());
                        return Mono.empty();
                    }
                    log.debug(
                            "OPA deny: {}/{}",
                            request.route().getFullServiceName(),
                            request.route().getMethodName());
                    return Mono.error(new AccessDeniedException("Denied by policy " + props.getDecisionPath()));
                });
    }

    /**
     * OPA's data API response. {@code result} is absent when the queried rule is undefined, so the
     * boxed {@link Boolean} is deliberate — null must not unbox into anything.
     */
    record OpaResponse(Boolean result) {}
}
