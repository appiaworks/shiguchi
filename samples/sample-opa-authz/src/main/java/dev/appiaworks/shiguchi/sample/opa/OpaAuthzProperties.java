package dev.appiaworks.shiguchi.sample.opa;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the OPA authorizer. See {@code application.yml} for a worked example. */
@ConfigurationProperties(prefix = "sample.opa")
public class OpaAuthzProperties {

    /** Master switch. When false the gateway falls back to the built-in scope check alone. */
    private boolean enabled = false;

    /** Base URL of the OPA server, e.g. {@code http://localhost:8181}. */
    private String url = "http://localhost:8181";

    /**
     * Policy decision path, as it appears under OPA's data API. {@code gateway/authz/allow} maps to
     * {@code POST /v1/data/gateway/authz/allow} and to {@code package gateway.authz} +
     * {@code allow} in the Rego source.
     */
    private String decisionPath = "gateway/authz/allow";

    /**
     * Per-decision timeout. A policy engine on the request hot path must never hang the gateway;
     * on timeout the {@link #failOpen} decision applies.
     */
    private Duration timeout = Duration.ofMillis(300);

    /**
     * What to do when OPA is unreachable, errors, or times out.
     *
     * <p>Defaults to false — fail closed. Failing open turns a policy-engine outage into a silent
     * authorization bypass, so it is opt-in.
     */
    private boolean failOpen = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDecisionPath() {
        return decisionPath;
    }

    public void setDecisionPath(String decisionPath) {
        this.decisionPath = decisionPath;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }
}
