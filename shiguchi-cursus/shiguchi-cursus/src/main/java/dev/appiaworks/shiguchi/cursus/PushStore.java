package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Assigns event ids and keeps the history a namespace asks for, so a subscriber can resume from
 * its last id. Ids are opaque strings; only {@link #compareIds} knows their order.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public interface PushStore {

    /**
     * Assigns the next id to {@code event} and, when {@code historySize} is above zero, keeps it
     * for replay. Returns the event with its id.
     */
    Mono<PushEvent> append(PushEvent event, int historySize, java.time.Duration historyTtl);

    /**
     * True when {@link #appendWithId} keeps the id a caller names (the backend's own sequence of an
     * upstream-backed topic with {@code event_id_field}). Ids are per topic there; a store whose ids
     * are one table-wide sequence (the {@code POSTGRES} store) says false and the id stays an
     * attribute of the event.
     */
    default boolean explicitIds() {
        return false;
    }

    /**
     * Like {@link #append} but the id is {@code id}, a positive integer the caller owns. Fails with
     * a {@link PushException} (409) when the topic already holds an id at or above it, so a replay
     * can never reorder history; an engine {@link #append} afterwards may mint an id above it.
     * Only for stores that say {@link #explicitIds()}.
     */
    default Mono<PushEvent> appendWithId(PushEvent event, long id, int historySize, java.time.Duration historyTtl) {
        return Mono.error(new UnsupportedOperationException("this store does not keep caller-chosen ids"));
    }

    /** The newest kept event on {@code topic}; empty when nothing is kept. */
    default Mono<PushEvent> headEvent(Topic topic) {
        return Mono.empty();
    }

    /**
     * Events kept on {@code topic} with an id after {@code afterId} (null: from the oldest kept),
     * oldest first, at most {@code limit}.
     */
    Flux<PushEvent> range(Topic topic, String afterId, int limit);

    /**
     * The highest event id this store removed from {@code topic}'s history by size trim or by
     * expiry; empty when none was removed or when the store cannot tell (the default, which means
     * "no signal"). A redacted (tombstoned) event does not count: redaction is intentional, not a
     * gap. A resume id below it has missed events that are gone.
     */
    default Mono<String> trimmedThrough(Topic topic) {
        return Mono.empty();
    }

    /** Orders two ids; negative when {@code a} is older than {@code b}. */
    int compareIds(String a, String b);

    /** Number of events kept on {@code topic}. */
    long historyLength(Topic topic);

    /**
     * Takes an event out of the history clients resume from, after an admin tombstone. The
     * {@code POSTGRES} store needs nothing here (its archive row is its history row); the default
     * does nothing and returns false, which the {@code REDIS} store keeps: a redacted event stays
     * in its Streams until the history trims it.
     */
    default Mono<Boolean> redact(Topic topic, String id) {
        return Mono.just(false);
    }

    /**
     * Takes or renews a lease: true when {@code holder} now holds {@code key} for {@code ttl}
     * (it was free, expired, or already its own). The upstream-backed topics elect their one
     * owner per topic with it. A store shared by several instances must implement it; the
     * default answers true, which is right only for a store that one instance uses.
     */
    default Mono<Boolean> lease(String key, String holder, java.time.Duration ttl) {
        return Mono.just(true);
    }

    /** Gives a lease back early; nothing happens when {@code holder} does not hold it. */
    default Mono<Void> release(String key, String holder) {
        return Mono.empty();
    }

    /** One session's position on one topic, as the admin views list it. */
    record SessionCursor(String session, Topic topic, String cursor, java.time.Instant expiresAt) {}

    /** True when this store keeps session cursors; {@code session-expiry} above zero needs one. */
    default boolean persistentSessions() {
        return false;
    }

    /** The id before every event: a cursor set to it replays the whole history. */
    default String firstId() {
        return "0";
    }

    /** The newest kept id on {@code topic}; empty when nothing is kept. */
    default Mono<String> headId(Topic topic) {
        return Mono.empty();
    }

    /** {@code session}'s cursor on {@code topic}; empty when it has none or it expired. */
    default Mono<String> cursor(String session, Topic topic) {
        return Mono.empty();
    }

    /** Moves {@code session}'s cursor on {@code topic} forward to {@code id} (never back) and renews its {@code expiry}. */
    default Mono<Void> ack(String session, Topic topic, String id, java.time.Duration expiry) {
        return Mono.empty();
    }

    /** Live sessions whose name starts with {@code prefix} (null or empty: all), at most {@code limit} cursors. */
    default Flux<SessionCursor> sessions(String prefix, int limit) {
        return Flux.empty();
    }

    /** Forgets {@code session} on every topic; how many cursors went. */
    default Mono<Long> dropSession(String session) {
        return Mono.just(0L);
    }

    /** Deletes the cursors past their expiry; how many went. */
    default Mono<Long> sweepSessions() {
        return Mono.just(0L);
    }

    /** One event the store handed to a group member, and how many times it has been handed out (this one included). */
    record Claimed(PushEvent event, int deliveries) {}

    /** One consumer group's state on one topic. */
    record GroupStatus(String group, Topic topic, long pending, long members, String cursor) {}

    /** True when this store runs consumer groups; {@code session-key: GROUP} needs one. */
    default boolean consumerGroups() {
        return false;
    }

    /**
     * Hands {@code member} up to {@code max} events of {@code topic} that no other member of
     * {@code group} holds: first the claims idle past {@code ackDeadline} (redeliveries), then
     * events never handed out, oldest first. A new group starts at the topic's head. {@code expiry}
     * renews the group's record.
     */
    default Flux<Claimed> claim(
            String group,
            Topic topic,
            String member,
            int max,
            java.time.Duration ackDeadline,
            java.time.Duration expiry) {
        return Flux.empty();
    }

    /** True when {@link #claim(String, Topic, String, int, java.time.Duration, java.time.Duration, PushProperties.Ordering)} honours {@code KEY}. */
    default boolean keyOrdering() {
        return false;
    }

    /**
     * Like {@link #claim(String, Topic, String, int, java.time.Duration, java.time.Duration)} under a
     * group's {@code ordering}: with {@code KEY} no event whose key another member holds is handed
     * out, and the events of one key go out in id order. {@code NONE} and {@code STRICT} (one
     * active member, elected by {@link #lease}) are the plain claim.
     */
    default Flux<Claimed> claim(
            String group,
            Topic topic,
            String member,
            int max,
            java.time.Duration ackDeadline,
            java.time.Duration expiry,
            PushProperties.Ordering ordering) {
        return claim(group, topic, member, max, ackDeadline, expiry);
    }

    /**
     * Moves {@code group}'s position on {@code topic} to {@code id} (a real id; callers resolve
     * {@code first} / {@code head}) and drops every claim, so the group redelivers from there.
     * False when the group is unknown.
     */
    default Mono<Boolean> seekGroup(String group, Topic topic, String id) {
        return Mono.just(false);
    }

    /**
     * Sets {@code session}'s cursor on {@code topic} to {@code id}, forward or back, keeping its
     * expiry. False when the session has no such cursor.
     */
    default Mono<Boolean> seekSession(String session, Topic topic, String id) {
        return Mono.just(false);
    }

    /** {@code member} is done with {@code id}: true when it held the claim. */
    default Mono<Boolean> ackGroup(String group, Topic topic, String member, String id) {
        return Mono.just(false);
    }

    /** {@code member} left: its unacknowledged claims become reclaimable at once; how many. */
    default Mono<Long> releaseClaims(String group, Topic topic, String member) {
        return Mono.just(0L);
    }

    /** The groups whose name starts with {@code prefix} (null or empty: all), at most {@code limit} rows. */
    default Flux<GroupStatus> groups(String prefix, int limit) {
        return Flux.empty();
    }

    /** Forgets {@code group} on every topic (claims and position); how many topics it was on. */
    default Mono<Long> dropGroup(String group) {
        return Mono.just(0L);
    }

    /**
     * Writes a batch of delivery records for the namespaces with {@code delivery-log: true}.
     * The default drops them; {@link #deliveryLog()} says whether a store keeps them.
     */
    default Mono<Void> recordDeliveries(java.util.List<PushDelivery> deliveries) {
        return Mono.empty();
    }

    /** The recorded deliveries of one event, oldest first, at most {@code limit}. */
    default Flux<PushDelivery> deliveries(Topic topic, String eventId, int limit) {
        return Flux.empty();
    }

    /** Recorded deliveries of one event counted per outcome ({@code delivered}, {@code dropped:queue_full}). */
    default Mono<java.util.Map<String, Long>> deliveryCounts(Topic topic, String eventId) {
        return Mono.just(java.util.Map.of());
    }

    /** True when this store keeps a delivery log; {@code delivery-log: true} on a namespace needs one. */
    default boolean deliveryLog() {
        return false;
    }

    /**
     * One live connection as the registry keeps it: where it is, who it is, what it listens to.
     * {@code presenceTopics} are the topics (of {@code presence: true} namespaces) the store also
     * indexes so {@link #connectionsOn} can answer who listens. {@code peerAddress} is the gRPC
     * {@code host:port} of the holding instance's peer channel, or null when it has none.
     */
    record ConnectionRecord(
            String id,
            String instance,
            String principal,
            String transport,
            String deviceId,
            java.time.Instant since,
            java.util.List<String> topics,
            java.util.List<String> presenceTopics,
            String peerAddress) {
        public ConnectionRecord(
                String id,
                String instance,
                String principal,
                String transport,
                String deviceId,
                java.time.Instant since,
                java.util.List<String> topics,
                java.util.List<String> presenceTopics) {
            this(id, instance, principal, transport, deviceId, since, topics, presenceTopics, null);
        }
    }

    /** True when this store keeps the cluster-wide connection registry; the default keeps none. */
    default boolean connectionRegistry() {
        return false;
    }

    /**
     * Upserts the rows of the connections of one instance; each lives {@code ttl} unless written
     * again (the keepalive tick rewrites them, so a dead instance's rows lapse by themselves).
     */
    default Mono<Void> registerConnections(java.util.List<ConnectionRecord> records, java.time.Duration ttl) {
        return Mono.empty();
    }

    /** Removes one connection's row (and its presence index entries). */
    default Mono<Void> unregisterConnection(String id) {
        return Mono.empty();
    }

    /** The live rows, oldest first; {@code principal} null means every connection. */
    default Flux<ConnectionRecord> connections(String principal, int limit) {
        return Flux.empty();
    }

    /** One live row by connection id; empty when it is gone or never was. */
    default Mono<ConnectionRecord> connection(String id) {
        return Mono.empty();
    }

    /** One page of the connections listening to {@code topic} (only topics of presence namespaces are indexed). */
    default Flux<ConnectionRecord> connectionsOn(Topic topic, int offset, int limit) {
        return Flux.empty();
    }

    /** How many connections listen to {@code topic}; 0 for a topic that is not indexed. */
    default Mono<Long> countConnectionsOn(Topic topic) {
        return Mono.just(0L);
    }

    Capabilities capabilities();

    /** What a store can do; namespace settings are checked against it at startup. */
    record Capabilities(boolean history, boolean persistentSessions) {}
}
