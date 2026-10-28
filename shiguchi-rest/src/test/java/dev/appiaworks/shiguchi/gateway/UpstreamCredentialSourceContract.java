package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.scheduler.Schedulers;

/**
 * What every {@link UpstreamCredentialSource} must do. Each shipped implementation gets a subclass
 * that supplies the source and a caller it can speak for.
 */
public abstract class UpstreamCredentialSourceContract {

    /** The implementation under test. */
    protected abstract UpstreamCredentialSource source();

    /** A request as the populator hands it over: no Authorization header. */
    protected ServerHttpRequest request() {
        return MockServerHttpRequest.get("/pkg.Svc/Get").build();
    }

    /** An authenticated caller the source has a credential for. */
    protected Authentication caller() {
        TestingAuthenticationToken caller = new TestingAuthenticationToken("alice", null, List.of());
        caller.setAuthenticated(true);
        return caller;
    }

    @Test
    @DisplayName("GIVEN a caller it knows WHEN asked SHOULD return a header value carrying its scheme")
    void given_knownCaller_when_asked_should_returnValueWithScheme() {
        String value = source().authorization(request(), caller()).block(Duration.ofSeconds(5));

        assertThat(value).isNotBlank().matches("[A-Za-z][A-Za-z0-9._~+/-]* .+");
    }

    @Test
    @DisplayName("GIVEN the same caller twice WHEN asked SHOULD return the same value")
    void given_sameCaller_when_askedTwice_should_returnSameValue() {
        UpstreamCredentialSource source = source();

        assertThat(source.authorization(request(), caller()).block(Duration.ofSeconds(5)))
                .isEqualTo(source.authorization(request(), caller()).block(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("GIVEN a non-blocking thread WHEN asked SHOULD answer there without blocking")
    void given_nonBlockingThread_when_asked_should_notBlock() {
        String value = source().authorization(request(), caller())
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(5));

        assertThat(value).isNotBlank();
    }
}
