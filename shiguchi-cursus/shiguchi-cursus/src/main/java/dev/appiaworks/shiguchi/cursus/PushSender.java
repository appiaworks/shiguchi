package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.proto.cursus.DeliverRequest;
import dev.appiaworks.shiguchi.proto.cursus.DeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Targeted delivery ({@code Send}): an event to a principal's connections, to one connection or to
 * one device, instead of to a topic's subscribers. The connections of this instance are written to
 * directly; the ones other instances hold (found in the registry) are reached over the peer
 * channel, one {@code Deliver} call per owning instance. A target that matches nothing, or whose
 * instance cannot be reached, answers {@code NOT_CONNECTED}.
 *
 * <p>Who may send is decided by the {@code publish-auth} rules of the namespace named by {@code
 * cursus.send.namespace}; without that namespace declared, {@code Send} is refused.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration PEER_MARGIN = Duration.ofSeconds(2);

    /** How one connection fared. */
    public enum Status {
        DELIVERED,
        NOT_CONNECTED,
        TIMEOUT,
        REJECTED,
        /** The connection declared the event's {@code push.origin}, so it was not sent. */
        SUPPRESSED
    }

    /** Who a {@code Send} is for: a principal, a connection id, or a device of a principal. */
    public record Target(String principal, String connectionId, String deviceId) {
        public static Target ofPrincipal(String principal) {
            return new Target(principal, null, null);
        }

        public static Target ofConnection(String connectionId) {
            return new Target(null, connectionId, null);
        }

        public static Target ofDevice(String principal, String deviceId) {
            return new Target(principal, null, deviceId);
        }
    }

    /** One matched connection and what happened to the event on it; {@code connectionId} is empty when none matched. */
    public record Delivery(String connectionId, String instance, Status status) {}

    /** The id every matched connection received, and each connection's outcome. */
    public record Result(String id, List<Delivery> deliveries) {}

    private final PushProperties properties;
    private final PushConnections connections;
    private final PushRegistry registry;
    private final TopicAuthorizer authorizer;
    private final PushMetrics metrics;
    private volatile PushPeerClient peers;
    private final AtomicBoolean warnedNoPeer = new AtomicBoolean();

    public PushSender(
            PushProperties properties,
            PushConnections connections,
            PushRegistry registry,
            TopicAuthorizer authorizer,
            PushMetrics metrics) {
        this.properties = properties;
        this.connections = connections;
        this.registry = registry;
        this.authorizer = authorizer;
        this.metrics = metrics;
    }

    /** The channel to other instances; without it a {@code Send} reaches this instance's connections only. */
    public void setPeers(PushPeerClient peers) {
        this.peers = peers;
    }

    private String namespaceName() {
        return properties.getSend().getNamespace();
    }

    /** Refuses unless {@code cursus.send.namespace} is declared and {@code auth} may publish to it. */
    public Mono<Void> authorize(Authentication auth) {
        PushProperties.Namespace ns = properties.getNamespaces().get(namespaceName());
        if (ns == null) {
            return Mono.error(PushException.badRequest("targeted Send is off: declare the namespace '" + namespaceName()
                    + "' (shiguchi.cursus.send.namespace) and its publish-auth"));
        }
        return authorizer.authorizePublish(auth, new Topic(namespaceName(), "send"), ns);
    }

    /**
     * Sends to {@code target}. {@code timeout} (null: {@code cursus.send.ack-timeout}) bounds the wait
     * for each connection to write the event and, with {@code requireAck}, to acknowledge it.
     */
    public Mono<Result> send(
            Authentication auth,
            Target target,
            PushEventProtos.Payload payload,
            Map<String, String> attributes,
            boolean requireAck,
            Duration timeout) {
        return authorize(auth).then(dispatch(target, payload, attributes, requireAck, timeout));
    }

    /**
     * Like {@link #send}, for the operator's admin surface: the endpoint's own gate has already decided
     * who may, so the namespace's {@code publish-auth} is not consulted (and need not be declared).
     */
    public Mono<Result> sendAsOperator(
            Target target,
            PushEventProtos.Payload payload,
            Map<String, String> attributes,
            boolean requireAck,
            Duration timeout) {
        return dispatch(target, payload, attributes, requireAck, timeout);
    }

    private Mono<Result> dispatch(
            Target target,
            PushEventProtos.Payload payload,
            Map<String, String> attributes,
            boolean requireAck,
            Duration timeout) {
        return Mono.defer(() -> {
                    int max = properties.getConnection().getMaxMessageBytes();
                    if (payload.json().length > max)
                        return Mono.error(PushException.tooLarge(payload.json().length, max));
                    Duration wait = timeout == null || timeout.isZero() || timeout.isNegative()
                            ? properties.getSend().getAckTimeout()
                            : timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
                    String id = UUID.randomUUID().toString();
                    List<PushConnections.Live> local = local(target);
                    return remote(target).collectList().flatMap(remoteRows -> {
                        List<Mono<List<Delivery>>> work = new ArrayList<>();
                        for (PushConnections.Live live : local) {
                            PushEvent event = event(live.id(), id, payload, attributes);
                            work.add(live.deliverDirect(event, requireAck, wait)
                                    .map(s -> List.of(new Delivery(live.id(), instance(), s))));
                        }
                        work.addAll(remoteWork(remoteRows, id, payload, attributes, requireAck, wait));
                        if (work.isEmpty()) {
                            return Mono.just(new Result(id, List.of(new Delivery("", null, Status.NOT_CONNECTED))));
                        }
                        return Flux.merge(work)
                                .flatMapIterable(l -> l)
                                .collectList()
                                .map(list -> new Result(id, list));
                    });
                })
                .doOnSuccess(r -> {
                    if (r != null)
                        for (Delivery d : r.deliveries())
                            metrics.send(d.status().name().toLowerCase());
                });
    }

    private String instance() {
        return registry == null ? properties.resolvedInstanceId() : registry.instance();
    }

    private PushEvent event(
            String connectionId, String id, PushEventProtos.Payload payload, Map<String, String> attributes) {
        Map<String, String> attrs = new HashMap<>(attributes == null ? Map.of() : attributes);
        attrs.put(PushConnections.DIRECT, "true");
        return new PushEvent(
                new Topic(namespaceName(), connectionId),
                id,
                payload.json(),
                attrs,
                null,
                Instant.now(),
                payload.typeUrl(),
                payload.proto());
    }

    private List<PushConnections.Live> local(Target t) {
        List<PushConnections.Live> out = new ArrayList<>();
        if (t.connectionId() != null) {
            PushConnections.Live l = connections.find(t.connectionId());
            if (l != null) out.add(l);
            return out;
        }
        for (PushConnections.Live l : connections.liveConnections()) {
            if (t.principal() == null || !t.principal().equals(l.principal())) continue;
            if (t.deviceId() != null && !t.deviceId().equals(l.deviceId())) continue;
            out.add(l);
        }
        return out;
    }

    private Flux<PushStore.ConnectionRecord> remote(Target t) {
        if (registry == null || !registry.enabled()) return Flux.empty();
        return registry.remote(t);
    }

    private List<Mono<List<Delivery>>> remoteWork(
            List<PushStore.ConnectionRecord> rows,
            String id,
            PushEventProtos.Payload payload,
            Map<String, String> attributes,
            boolean requireAck,
            Duration wait) {
        // One Deliver per owning instance, the instances in parallel.
        Map<String, List<PushStore.ConnectionRecord>> byInstance = new LinkedHashMap<>();
        for (PushStore.ConnectionRecord r : rows) {
            byInstance
                    .computeIfAbsent(r.instance() + "|" + r.peerAddress(), k -> new ArrayList<>())
                    .add(r);
        }
        List<Mono<List<Delivery>>> work = new ArrayList<>();
        for (List<PushStore.ConnectionRecord> group : byInstance.values()) {
            PushStore.ConnectionRecord first = group.get(0);
            PushPeerClient client = peers;
            if (client == null
                    || first.peerAddress() == null
                    || first.peerAddress().isBlank()) {
                if (warnedNoPeer.compareAndSet(false, true)) {
                    log.warn(
                            "Push: Send matched connections held by instance '{}' but no peer channel reaches it"
                                    + " (set shiguchi.cursus.peer.token and .address on every instance, and enable"
                                    + " shiguchi.grpc); they are reported NOT_CONNECTED",
                            first.instance());
                }
                List<Delivery> out = new ArrayList<>();
                for (PushStore.ConnectionRecord r : group) {
                    out.add(new Delivery(r.id(), r.instance(), Status.NOT_CONNECTED));
                }
                work.add(Mono.just(out));
                continue;
            }
            DeliverRequest.Builder req = DeliverRequest.newBuilder()
                    .setEvent(PushEventProtos.toProto(event("-", id, payload, attributes)))
                    .setRequireAck(requireAck)
                    .setTimeoutMs((int) wait.toMillis());
            for (PushStore.ConnectionRecord r : group) req.addConnectionIds(r.id());
            Mono<List<Delivery>> call = client.deliver(first.peerAddress(), req.build(), wait.plus(PEER_MARGIN))
                    .map(statuses -> {
                        List<Delivery> out = new ArrayList<>();
                        for (int i = 0; i < group.size(); i++) {
                            DeliveryStatus.Status s = i < statuses.size()
                                    ? statuses.get(i).getStatus()
                                    : DeliveryStatus.Status.NOT_CONNECTED;
                            out.add(new Delivery(group.get(i).id(), first.instance(), fromProto(s)));
                        }
                        return out;
                    })
                    .onErrorResume(e -> {
                        log.debug("Push: peer {} could not deliver: {}", first.peerAddress(), e.toString());
                        List<Delivery> out = new ArrayList<>();
                        for (PushStore.ConnectionRecord r : group) {
                            out.add(new Delivery(r.id(), r.instance(), Status.NOT_CONNECTED));
                        }
                        return Mono.just(out);
                    });
            work.add(call);
        }
        return work;
    }

    /**
     * Ends connection {@code id} wherever it lives: here, or on the instance the registry names, over
     * the peer channel. False when it is nowhere or its instance cannot be reached.
     */
    public Mono<Boolean> disconnect(String id, String reason) {
        if (connections.disconnect(id, reason)) return Mono.just(true);
        PushPeerClient client = peers;
        if (client == null || registry == null) return Mono.just(false);
        return registry.find(id)
                .filter(r -> r.peerAddress() != null && !r.peerAddress().isBlank())
                .filter(r -> !instance().equals(r.instance()))
                .flatMap(r -> client.disconnect(r.peerAddress(), id, reason, Duration.ofSeconds(5)))
                .onErrorResume(e -> {
                    log.debug("Push: peer disconnect of {} failed: {}", id, e.toString());
                    return Mono.just(false);
                })
                .defaultIfEmpty(false);
    }

    static Status fromProto(DeliveryStatus.Status s) {
        return switch (s) {
            case DELIVERED -> Status.DELIVERED;
            case TIMEOUT -> Status.TIMEOUT;
            case REJECTED -> Status.REJECTED;
            case SUPPRESSED -> Status.SUPPRESSED;
            default -> Status.NOT_CONNECTED;
        };
    }

    static DeliveryStatus.Status toProto(Status s) {
        return switch (s) {
            case DELIVERED -> DeliveryStatus.Status.DELIVERED;
            case TIMEOUT -> DeliveryStatus.Status.TIMEOUT;
            case REJECTED -> DeliveryStatus.Status.REJECTED;
            case NOT_CONNECTED -> DeliveryStatus.Status.NOT_CONNECTED;
            case SUPPRESSED -> DeliveryStatus.Status.SUPPRESSED;
        };
    }
}
