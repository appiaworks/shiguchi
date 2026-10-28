package dev.appiaworks.shiguchi.gateway;

import org.junit.jupiter.api.DisplayName;
import reactor.core.publisher.Mono;

@DisplayName("UpstreamCredentialSource contract: a fixed value")
class FixedUpstreamCredentialSourceTest extends UpstreamCredentialSourceContract {

    @Override
    protected UpstreamCredentialSource source() {
        return (request, authentication) -> Mono.just("Bearer session-token-" + authentication.getName());
    }
}
