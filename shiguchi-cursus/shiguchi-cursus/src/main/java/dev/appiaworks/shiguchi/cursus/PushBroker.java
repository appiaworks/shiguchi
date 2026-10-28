package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Live fan-out of events to every subscriber of a topic, across gateway instances when the
 * implementation is shared. Delivery here is best effort: what survives a disconnect is the
 * {@link PushStore}'s business.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public interface PushBroker {

    /** Hands an event (already carrying its id) to every current subscriber of its topic. */
    Mono<Void> publish(PushEvent event);

    /**
     * Every event published on {@code topic} from the moment of subscription. No buffering for a
     * slow subscriber: the caller applies its own queue policy.
     */
    Flux<PushEvent> subscribe(Topic topic);

    /** Subscribers of {@code topic} on this instance; a shared broker may answer for the cluster. */
    long subscriberCount(Topic topic);

    /** Every topic with a subscriber on this instance and how many, for the admin surface. */
    default java.util.Map<Topic, Long> subscriptions() {
        return java.util.Map.of();
    }
}
