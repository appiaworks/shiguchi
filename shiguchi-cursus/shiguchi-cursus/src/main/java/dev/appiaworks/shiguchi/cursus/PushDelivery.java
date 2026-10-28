package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Instant;

/**
 * One event reaching, or failing to reach, one connection: what the delivery log records for a
 * namespace with {@code delivery-log: true}.
 *
 * @param topic the event's topic
 * @param eventId the event's id
 * @param connectionId the connection, as the admin views list it
 * @param principal the connection's principal, or null when anonymous
 * @param transport {@code sse}, {@code ws}, {@code grpc} or {@code graphql}
 * @param time when the outcome happened
 * @param outcome {@code delivered}, or {@code dropped:<reason>} ({@code dropped:queue_full})
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public record PushDelivery(
        Topic topic,
        String eventId,
        String connectionId,
        String principal,
        String transport,
        Instant time,
        String outcome) {

    public static final String DELIVERED = "delivered";

    public boolean delivered() {
        return DELIVERED.equals(outcome);
    }
}
