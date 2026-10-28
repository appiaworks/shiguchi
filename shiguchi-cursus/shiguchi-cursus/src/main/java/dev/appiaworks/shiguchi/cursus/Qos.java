package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;

/** The delivery guarantee of a namespace, in MQTT's terms. */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public enum Qos {
    /** Only while connected; a slow subscriber loses the oldest queued events. */
    AT_MOST_ONCE,
    /**
     * At least once within the history window: events are kept per topic ({@code history-size},
     * {@code history-ttl}) and a subscriber resumes from its last id. A subscriber that falls
     * behind is disconnected and resumes.
     */
    AT_LEAST_ONCE
}
