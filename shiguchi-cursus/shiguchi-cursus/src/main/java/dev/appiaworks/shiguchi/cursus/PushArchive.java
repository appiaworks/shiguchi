package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The cold store behind the admin endpoint's message search: every published event, kept for
 * {@code archive.retention}, searchable by topic, time, attributes, type and text; one event by
 * id for the detail view and republish; a tombstone that redacts an event's payload (metadata
 * stays) and takes it out of the history clients resume from.
 *
 * <p>The {@code POSTGRES} store doubles as the archive (one table, two retention windows); its
 * {@link #append} is a no-op because the history row is the archive row. The {@code REDIS} store
 * gets Streams shared by every instance, the {@code MEMORY} store a per-instance ring. A
 * {@code PushArchive} bean of your own replaces any of them.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public interface PushArchive {

    /**
     * Keeps a published event (it has its id). A no-op when the archive is the store itself, which
     * already holds the row.
     */
    Mono<Void> append(PushEvent event);

    /** Events matching {@code query}, newest first, at most {@code query.limit()}. */
    Flux<Entry> search(Query query);

    /** One kept event, or empty when the archive has no event with that id on that topic. */
    Mono<Entry> get(Topic topic, String id);

    /**
     * Redacts an event: its payload becomes {@code null} JSON (the typed payload is dropped), its
     * attributes, key and time stay, and history skips it from now on. Returns true when an event
     * was redacted by this call, false when there was none or it was already a tombstone.
     */
    Mono<Boolean> tombstone(Topic topic, String id, String by);

    SearchCapabilities searchCapabilities();

    /** An archive that keeps nothing; what the gateway uses when {@code archive.retention} is zero. */
    static PushArchive none() {
        return None.INSTANCE;
    }

    /**
     * What an archive can search by, so the endpoint and the console can say which filters apply.
     *
     * @param enabled false for {@link #none()}
     * @param attributes the {@code attr=k:v} filter is honoured
     * @param fullText the {@code q=} filter is honoured (a text search of the JSON payload)
     * @param retention how long an event stays
     */
    record SearchCapabilities(boolean enabled, boolean attributes, boolean fullText, Duration retention) {}

    /**
     * A kept event. {@code tombstonedAt} is null unless the event was redacted; a tombstone's
     * {@link PushEvent#data()} is the four bytes {@code null}.
     */
    record Entry(PushEvent event, Instant tombstonedAt, String tombstonedBy) {
        public Entry {
            if (event == null) throw new IllegalArgumentException("event is required");
        }

        public boolean tombstoned() {
            return tombstonedAt != null;
        }
    }

    /**
     * What to search for. Every field is optional but {@code limit}; a null field does not
     * filter.
     *
     * @param topic an exact topic ({@code orders:1}) or a {@link TopicPattern} ({@code orders:*},
     *     {@code orders}); null: every topic
     * @param from events accepted at or after this instant
     * @param to events accepted before this instant
     * @param afterId events with an id above this one (exclusive)
     * @param beforeId events with an id below this one (exclusive); page older results with it
     * @param attributes every entry must be present with that value on the event
     * @param typeUrl the typed payload's type URL; {@code json} for events without one
     * @param text words to find in the JSON payload, where {@link SearchCapabilities#fullText()}
     * @param limit at most this many, newest first
     */
    record Query(
            String topic,
            Instant from,
            Instant to,
            String afterId,
            String beforeId,
            Map<String, String> attributes,
            String typeUrl,
            String text,
            int limit) {
        public Query {
            if (limit <= 0) throw new IllegalArgumentException("limit must be above 0");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            topic = blankToNull(topic);
            afterId = blankToNull(afterId);
            beforeId = blankToNull(beforeId);
            typeUrl = blankToNull(typeUrl);
            text = blankToNull(text);
        }

        /** The topic filter as a pattern; null when there is none. */
        public TopicPattern topicPattern() {
            return topic == null ? null : TopicPattern.parse(topic);
        }

        /** True when the topic filter names one topic exactly (no wildcard). */
        public boolean exactTopic() {
            return topic != null && topic.indexOf(':') > 0 && topic.indexOf('*') < 0 && topic.indexOf('{') < 0;
        }

        public Query withLimit(int newLimit) {
            return new Query(topic, from, to, afterId, beforeId, attributes, typeUrl, text, newLimit);
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    /** The disabled archive. */
    enum None implements PushArchive {
        INSTANCE;

        @Override
        public Mono<Void> append(PushEvent event) {
            return Mono.empty();
        }

        @Override
        public Flux<Entry> search(Query query) {
            return Flux.empty();
        }

        @Override
        public Mono<Entry> get(Topic topic, String id) {
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> tombstone(Topic topic, String id, String by) {
            return Mono.just(false);
        }

        @Override
        public SearchCapabilities searchCapabilities() {
            return new SearchCapabilities(false, false, false, Duration.ZERO);
        }
    }
}
