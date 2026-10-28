package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PendingLoadRetrier")
class PendingLoadRetrierTest {

    private final GrpcRouteRegistry registry = new GrpcRouteRegistry();
    private final ServiceLoadStatus status = new ServiceLoadStatus();
    private final AtomicInteger reloads = new AtomicInteger();
    private final CountDownLatch reloaded = new CountDownLatch(1);
    private PendingLoadRetrier retrier;

    private static GatewayProperties props(String... names) {
        var props = new GatewayProperties();
        for (String n : names) {
            var s = new ServiceDefinition();
            s.setName(n);
            s.setHost("h");
            s.setPort(1);
            props.getServices().add(s);
        }
        return props;
    }

    /** A loader that fails until {@code up} is set, then builds an empty context. */
    private static ServiceLoader loader(AtomicBoolean up, AtomicInteger builds) {
        return new ServiceLoader() {
            @Override
            public boolean supports(ServiceDefinition service) {
                return true;
            }

            @Override
            public ServiceEndpointContext buildContext(ServiceDefinition service) throws Exception {
                builds.incrementAndGet();
                if (!up.get()) throw new IllegalStateException("UNAVAILABLE: " + service.getName() + " is down");
                return new ServiceEndpointContext(service, null);
            }
        };
    }

    private PendingLoadRetrier retrier(GatewayProperties props, ServiceLoader loader) {
        SchemaReloadListener onReload = () -> {
            reloads.incrementAndGet();
            reloaded.countDown();
        };
        retrier = new PendingLoadRetrier(
                props, registry, Optional.empty(), () -> List.of(loader), () -> List.of(onReload), status);
        return retrier;
    }

    @AfterEach
    void stop() {
        if (retrier != null) retrier.stop();
    }

    @Test
    @DisplayName(
            "GIVEN a backend that is down WHEN attempted SHOULD record the failure, and once it is up SHOULD load it, register it and rebuild the documents")
    void given_backendDownThenUp_when_attempted_should_recordThenLoad() {
        var up = new AtomicBoolean(false);
        var builds = new AtomicInteger();
        var r = retrier(props("orders"), loader(up, builds));

        assertThat(r.attempt("retry")).isEmpty();
        assertThat(r.pending()).containsExactly("orders");
        assertThat(status.failure("orders")).get().satisfies(f -> {
            assertThat(f.error()).contains("orders is down");
            assertThat(f.phase()).isEqualTo("retry");
        });
        assertThat(reloads.get()).isZero();

        up.set(true);
        assertThat(r.attempt("retry")).containsExactly("orders");
        assertThat(r.pending()).isEmpty();
        assertThat(registry.contextByServiceName("orders")).isPresent();
        assertThat(status.failure("orders")).isEmpty();
        assertThat(reloads.get()).isEqualTo(1);
        assertThat(builds.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a service already registered WHEN attempted SHOULD not touch it")
    void given_alreadyLoaded_when_attempted_should_skip() {
        var props = props("orders");
        registry.registerContext(new ServiceEndpointContext(props.getServices().get(0), null));
        var builds = new AtomicInteger();
        var r = retrier(props, loader(new AtomicBoolean(true), builds));

        assertThat(r.attempt("retry")).isEmpty();
        assertThat(builds.get()).isZero();
        assertThat(r.pending()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN fail-fast and a backend still down WHEN the application is ready SHOULD abort with every service and its last error")
    void given_failFast_when_ready_should_abortNamingServices() {
        var props = props("orders", "billing");
        props.getStartup().setFailFast(true);
        var r = retrier(props, loader(new AtomicBoolean(false), new AtomicInteger()));

        assertThatThrownBy(r::onApplicationReady)
                .isInstanceOf(PendingLoadRetrier.GatewayStartupException.class)
                .hasMessageContaining("fail-fast")
                .hasMessageContaining("orders: UNAVAILABLE: orders is down")
                .hasMessageContaining("billing: UNAVAILABLE: billing is down")
                .extracting(t -> ((PendingLoadRetrier.GatewayStartupException) t).getServices())
                .isEqualTo(List.of("orders", "billing"));
        assertThat(r.isRetrying()).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN a retry interval WHEN the application is ready with a backend down SHOULD keep retrying in the background until it loads, then stop")
    void given_retryInterval_when_ready_should_retryUntilLoadedThenStop() throws Exception {
        var props = props("orders");
        props.getStartup().setRetryInterval(Duration.ofMillis(50));
        var up = new AtomicBoolean(false);
        var builds = new AtomicInteger();
        var r = retrier(props, loader(up, builds));

        r.onApplicationReady();
        assertThat(r.isRetrying()).isTrue();
        assertThat(r.pending()).containsExactly("orders");

        up.set(true);
        assertThat(reloaded.await(30, TimeUnit.SECONDS))
                .as("loaded by the background retry and the documents rebuilt")
                .isTrue();
        assertThat(registry.contextByServiceName("orders")).isPresent();
        assertThat(reloads.get()).isEqualTo(1);
        assertThat(builds.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN a zero retry interval WHEN the application is ready with a backend down SHOULD retry once and not schedule anything")
    void given_zeroInterval_when_ready_should_notSchedule() {
        var props = props("orders");
        props.getStartup().setRetryInterval(Duration.ZERO);
        var builds = new AtomicInteger();
        var r = retrier(props, loader(new AtomicBoolean(false), builds));

        r.onApplicationReady();

        assertThat(builds.get()).isEqualTo(1);
        assertThat(r.isRetrying()).isFalse();
        assertThat(r.pending()).containsExactly("orders");
    }

    @Test
    @DisplayName("GIVEN nothing pending WHEN the application is ready SHOULD do nothing")
    void given_nothingPending_when_ready_should_doNothing() {
        var builds = new AtomicInteger();
        var r = retrier(props(), loader(new AtomicBoolean(true), builds));

        r.onApplicationReady();

        assertThat(builds.get()).isZero();
        assertThat(r.isRetrying()).isFalse();
    }
}
