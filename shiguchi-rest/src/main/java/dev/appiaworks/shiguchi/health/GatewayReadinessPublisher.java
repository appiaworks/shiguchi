package dev.appiaworks.shiguchi.health;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ReadinessPolicy;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Makes Spring Boot's readiness state follow the backends, per
 * {@code shiguchi.health.readiness}: {@code ALL_LOADED} (default) keeps the application
 * {@code REFUSING_TRAFFIC} while any configured service has not loaded, {@code ANY_LOADED} while
 * none has, {@code IGNORE} never touches it. Readiness is what {@code /actuator/health/readiness}
 * and the Kubernetes readiness probe of the Helm chart read, so a gateway whose routes are
 * missing is taken out of rotation instead of answering 404 for them.
 *
 * <p>Spring Boot publishes {@code ACCEPTING_TRAFFIC} once the application is ready, after every
 * ready-event listener ran; this component answers that event with {@code REFUSING_TRAFFIC} when
 * the policy is not met, and flips back to {@code ACCEPTING_TRAFFIC} as soon as a later load (the
 * {@link dev.appiaworks.shiguchi.routing.PendingLoadRetrier}, a refresh) satisfies it.
 */
@Component
public class GatewayReadinessPublisher {

    private static final Logger log = LoggerFactory.getLogger(GatewayReadinessPublisher.class);

    private final GatewayProperties properties;
    private final GrpcRouteRegistry registry;
    private final ServiceLoadStatus status;
    private final ApplicationEventPublisher publisher;

    /** Set once Boot has declared the application ready; before that, nothing is published. */
    private final AtomicBoolean started = new AtomicBoolean();

    /** Whether this component is the reason the application is currently refusing traffic. */
    private final AtomicBoolean refusing = new AtomicBoolean();

    public GatewayReadinessPublisher(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            ServiceLoadStatus status,
            ApplicationEventPublisher publisher) {
        this.properties = properties;
        this.registry = registry;
        this.status = status;
        this.publisher = publisher;
        status.addListener(this::reevaluate);
    }

    @EventListener
    public void onAvailabilityChange(AvailabilityChangeEvent<?> event) {
        if (!(event.getState() instanceof ReadinessState state)) return;
        if (state == ReadinessState.ACCEPTING_TRAFFIC && !refusing.get()) {
            started.set(true);
            if (!ready()) {
                List<String> missing = status.notLoaded(properties, registry);
                log.warn(
                        "Refusing traffic: readiness policy {} and {} service(s) not loaded: {}",
                        properties.getHealth().getReadiness(),
                        missing.size(),
                        missing);
                refusing.set(true);
                AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC);
            }
        }
    }

    /** Whether the backends satisfy the readiness policy right now. */
    public boolean ready() {
        ReadinessPolicy policy = properties.getHealth().getReadiness();
        if (policy == null || policy == ReadinessPolicy.IGNORE) return true;
        int configured = (int) properties.getServices().stream()
                .filter(s -> s.getName() != null)
                .count();
        int missing = status.notLoaded(properties, registry).size();
        return switch (policy) {
            case ALL_LOADED -> missing == 0;
            case ANY_LOADED -> configured == 0 || missing < configured;
            case IGNORE -> true;
        };
    }

    private void reevaluate() {
        if (!started.get()) return;
        boolean ready = ready();
        if (ready && refusing.compareAndSet(true, false)) {
            log.info("Accepting traffic: the readiness policy is satisfied");
            AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        } else if (!ready && refusing.compareAndSet(false, true)) {
            log.warn("Refusing traffic: the readiness policy is no longer satisfied");
            AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }
}
