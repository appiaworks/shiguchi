package dev.appiaworks.shiguchi.cursus;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Accepts one event: the namespace must be declared, the payload must be one JSON value within
 * {@code max-message-bytes}; the store assigns the id and keeps history where the namespace asks,
 * then the broker fans it out. The publisher's own authorization happens before this is called.
 */
public final class PushPublisher {

    private static final JsonFactory JSON = new JsonFactory();

    private final PushProperties properties;
    private final PushStore store;
    private final PushBroker broker;
    private final PushMetrics metrics;
    private final PushArchive archive;

    public PushPublisher(PushProperties properties, PushStore store, PushBroker broker, PushMetrics metrics) {
        this(properties, store, broker, metrics, PushArchive.none());
    }

    /**
     * With an archive: every accepted event is appended to it after the store assigned its id,
     * unless the archive is the store itself (one write holds both).
     */
    public PushPublisher(
            PushProperties properties, PushStore store, PushBroker broker, PushMetrics metrics, PushArchive archive) {
        this.properties = properties;
        this.store = store;
        this.broker = broker;
        this.metrics = metrics;
        this.archive = archive == null || archive == store ? PushArchive.none() : archive;
    }

    public Mono<PushEvent> publish(Topic topic, byte[] data, Map<String, String> attributes, String key) {
        return publish(topic, data, attributes, key, null, null);
    }

    /**
     * A client's publish (a WebSocket {@code pub}, {@code POST <path>/topics/{topic}}): attributes
     * with the reserved {@code push.} prefix are dropped, then {@code push.origin} (the origin the
     * connection or request declared, null for none) and {@code from} (the publisher's name, null
     * when anonymous) are stamped, whatever the client sent for them.
     */
    public Mono<PushEvent> publishFromClient(
            Topic topic, byte[] data, Map<String, String> attributes, String key, String origin, String from) {
        return Mono.defer(() -> publish(topic, data, PushOrigin.forClient(attributes, origin, from), key));
    }

    /**
     * An operator's publish (HTTP {@code /publish}, gRPC {@code Publish}): attributes with the
     * reserved {@code push.} prefix are refused with a 400, the optional {@code origin} is trusted
     * (validated against its grammar) and stored as {@code push.origin}, and {@code from} (the
     * publisher's name, null when anonymous) is stamped over any {@code from} sent.
     */
    public Mono<PushEvent> publishAsOperator(
            Topic topic,
            byte[] data,
            Map<String, String> attributes,
            String key,
            String typeUrl,
            byte[] proto,
            String origin,
            String from) {
        return Mono.defer(
                () -> publish(topic, data, PushOrigin.forOperator(attributes, origin, from), key, typeUrl, proto));
    }

    /**
     * Accepts an event that a typed publisher sent as a protobuf message: {@code data} is its JSON
     * rendering, {@code typeUrl} and {@code proto} the message itself, kept so gRPC subscribers
     * receive it as sent. Both forms are held to {@code max-message-bytes}.
     */
    public Mono<PushEvent> publish(
            Topic topic, byte[] data, Map<String, String> attributes, String key, String typeUrl, byte[] proto) {
        return publish(topic, data, attributes, key, typeUrl, proto, null);
    }

    /**
     * Like the typed publish, with the event id the upstream's own sequence names. A store that
     * keeps caller ids ({@link PushStore#explicitIds()}) uses it as the event id; one that refuses
     * it (the topic already holds an id at or above it) gets the event under an engine id with
     * {@code upstream.id-rejected=true}, never dropped. A store without caller ids assigns its own.
     * The caller records the value as the {@code upstream.id} attribute.
     */
    public Mono<PushEvent> publish(
            Topic topic,
            byte[] data,
            Map<String, String> attributes,
            String key,
            String typeUrl,
            byte[] proto,
            Long explicitId) {
        PushProperties.Namespace ns = properties.namespaceOf(topic);
        if (ns == null) return Mono.error(PushException.unknownNamespace(topic));
        int max = properties.getConnection().getMaxMessageBytes();
        if (data.length > max) return Mono.error(PushException.tooLarge(data.length, max));
        if (proto != null && proto.length > max) return Mono.error(PushException.tooLarge(proto.length, max));
        String problem = validateJson(data);
        if (problem != null) return Mono.error(PushException.badRequest("data is not a JSON value: " + problem));
        long start = System.nanoTime();
        PushEvent event = new PushEvent(topic, null, data, attributes, key, null, typeUrl, proto);
        return appended(event, ns, explicitId)
                .flatMap(stored -> archive.append(stored).thenReturn(stored))
                .flatMap(stored -> broker.publish(stored).thenReturn(stored))
                .doOnSuccess(stored -> {
                    metrics.published();
                    metrics.publishTimer().record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
                });
    }

    private Mono<PushEvent> appended(PushEvent event, PushProperties.Namespace ns, Long explicitId) {
        if (explicitId == null) return store.append(event, ns.getHistorySize(), ns.getHistoryTtl());
        if (!store.explicitIds()) {
            metrics.upstreamId("attribute");
            return store.append(event, ns.getHistorySize(), ns.getHistoryTtl());
        }
        return store.appendWithId(event, explicitId, ns.getHistorySize(), ns.getHistoryTtl())
                .doOnSuccess(stored -> metrics.upstreamId("kept"))
                .onErrorResume(PushException.class, e -> {
                    if (e.status() != org.springframework.http.HttpStatus.CONFLICT) return Mono.error(e);
                    metrics.upstreamId("rejected");
                    Map<String, String> marked =
                            new java.util.LinkedHashMap<>(event.attributes() == null ? Map.of() : event.attributes());
                    marked.put("upstream.id-rejected", "true");
                    return store.append(
                            new PushEvent(
                                    event.topic(),
                                    null,
                                    event.data(),
                                    marked,
                                    event.key(),
                                    null,
                                    event.typeUrl(),
                                    event.proto()),
                            ns.getHistorySize(),
                            ns.getHistoryTtl());
                });
    }

    /** Null when {@code data} is exactly one JSON value, else what is wrong with it. */
    static String validateJson(byte[] data) {
        try (JsonParser p = JSON.createParser(data)) {
            JsonToken first = p.nextToken();
            if (first == null) return "empty";
            p.skipChildren();
            if (p.nextToken() != null) return "more than one value";
            return null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return e.getOriginalMessage();
        } catch (IOException e) {
            return e.getMessage();
        }
    }
}
