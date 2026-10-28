package dev.appiaworks.shiguchi.routing;

import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.shiguchi.proto.api.AuthzRule;
import dev.appiaworks.shiguchi.proto.api.MethodProto;
import dev.appiaworks.shiguchi.proto.api.MethodRule;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies a method's {@code (shiguchi.api.method)} settings (auth_scopes, cache_ttl_seconds, authz) onto its route.
 *
 * <p>The rules gate every surface the method is reachable on, so they are read on their own:
 * whether or not GraphQL is on for the service, and whether or not the method has a GraphQL binding.
 * Internal to the gateway; not a supported extension point.
 */
public final class ProtoAccessRules {

    private static final Logger log = LoggerFactory.getLogger(ProtoAccessRules.class);

    private ProtoAccessRules() {}

    public static RouteDefinition.Builder apply(RouteDefinition.Builder builder, MethodDescriptor md) {
        MethodRule annotation = md.getOptions().getExtension(MethodProto.method);
        if (!annotation.getAuthScopesList().isEmpty()) builder.authScopes(annotation.getAuthScopesList());
        if (annotation.getCacheTtlSeconds() > 0) builder.cacheTtlSeconds(annotation.getCacheTtlSeconds());
        if (!annotation.getAuthzList().isEmpty()) builder.authzRules(toAuthzRules(annotation, md));
        return builder;
    }

    /**
     * Resolves {@code (shiguchi.api.method).authz} into transport-neutral rules. Rules missing a
     * required part are dropped with a WARN rather than failing the whole route: a malformed rule
     * must not take a service offline, and a dropped rule is visible in the log.
     */
    private static List<AuthzRuleDefinition> toAuthzRules(MethodRule annotation, MethodDescriptor md) {
        List<AuthzRuleDefinition> rules = new ArrayList<>();
        for (AuthzRule rule : annotation.getAuthzList()) {
            if (rule.getObjectType().isEmpty()
                    || rule.getRelation().isEmpty()
                    || rule.getIdField().isEmpty()) {
                log.warn(
                        "[authz] {}/{} declares an incomplete authz rule (object_type='{}', relation='{}', "
                                + "id_field='{}') — all three are required; rule ignored",
                        md.getService().getFullName(),
                        md.getName(),
                        rule.getObjectType(),
                        rule.getRelation(),
                        rule.getIdField());
                continue;
            }
            rules.add(new AuthzRuleDefinition(rule.getObjectType(), rule.getRelation(), rule.getIdField()));
        }
        return rules;
    }
}
