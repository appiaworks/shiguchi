package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import reactor.core.publisher.Flux;

/**
 * External records turned into push events: a Kafka topic, a backend's firehose stream, or a
 * bean of your own. The gateway subscribes to {@link #events()} and publishes each
 * {@link SourcedEvent} into its topic like any other event (history, archive, fan-out). A
 * {@linkplain #shared() shared} source runs on one gateway instance at a time (a lease in the
 * store, like an upstream-backed topic); when its stream ends or fails, it is reopened with a
 * backoff ({@code upstream.retry-backoff}). An event the gateway cannot publish (unknown
 * namespace, too large) goes to the dead-letter queue; a built-in source also sends there the
 * records it cannot map. Configure the built-in ones under {@code shiguchi.cursus.sources}.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public interface PushSource {

    /** The source's name in the admin views, the dead-letter queue and the {@code source} attribute. */
    String name();

    /** The events, mapped to their topics. Subscribed once per run; ending or failing triggers a reopen. */
    Flux<SourcedEvent> events();

    /** True (the default) when one gateway instance at a time should consume; false when every instance should. */
    default boolean shared() {
        return true;
    }

    /** What the admin views say about the source: its kind and where it reads from. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
