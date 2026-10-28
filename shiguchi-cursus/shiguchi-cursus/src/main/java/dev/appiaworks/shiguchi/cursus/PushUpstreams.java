package dev.appiaworks.shiguchi.cursus;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.proto.api.TopicProto;
import dev.appiaworks.shiguchi.proto.api.UpstreamTopic;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.ProtoJsonText;
import io.grpc.Metadata;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Topics backed by an upstream server stream, declared with {@code (shiguchi.api.topic)}
 * on a method of a served service (see {@code topic.proto}). For a topic such a method's
 * pattern matches, the first local subscriber on any transport attaches the topic here: this
 * instance takes the topic's lease in the store and, as its owner, opens the upstream stream with
 * a request built from the pattern's variables and publishes every message into the topic as a
 * typed event; an instance that does not get the lease waits and retries, its subscribers fed
 * through the engine by the owner. The last local subscriber leaving closes the stream after
 * {@code upstream.idle-linger}; a stream that ends or fails is reopened with a backoff while
 * subscribers remain; a lost lease stops the stream. On startup every such namespace is declared
 * when it is not already, with the method's response as its {@code payload-type}.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class PushUpstreams implements SchemaReloadListener, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PushUpstreams.class);
    static final String LEASE_PREFIX = "upstream:";

    /** Opens the upstream stream of an {@link Upstream}; the gateway's dispatcher in production. */
    public interface Opener {
        Flux<DynamicMessage> open(Upstream upstream, DynamicMessage request);
    }

    /**
     * One declared method: its pattern and where to call it. {@code eventId} is the chain of
     * response fields {@code event_id_field} names (empty: the store assigns the ids).
     */
    public record Upstream(
            TopicTemplate template,
            ServiceEndpointContext context,
            String serviceFqn,
            String methodName,
            Descriptor input,
            Descriptor output,
            List<FieldDescriptor> eventId) {
        public Upstream(
                TopicTemplate template,
                ServiceEndpointContext context,
                String serviceFqn,
                String methodName,
                Descriptor input,
                Descriptor output) {
            this(template, context, serviceFqn, methodName, input, output, List.of());
        }

        public String method() {
            return serviceFqn + "/" + methodName;
        }
    }

    private final PushProperties properties;
    private final GrpcRouteRegistry registry;
    private final PushStore store;
    private final PushPublisher publisher;
    private final PushMetrics metrics;
    private final Opener opener;
    private final String holder;
    private volatile List<Upstream> upstreams = List.of();
    private volatile PushHooks hooks;

    /** The hooks that follow every topic's activity; set by the configuration. */
    public void setHooks(PushHooks hooks) {
        this.hooks = hooks;
    }

    private final ConcurrentHashMap<Topic, Share> shares = new ConcurrentHashMap<>();

    public PushUpstreams(
            PushProperties properties,
            GrpcRouteRegistry registry,
            PushStore store,
            PushPublisher publisher,
            PushMetrics metrics,
            Opener opener) {
        this.properties = properties;
        this.registry = registry;
        this.store = store;
        this.publisher = publisher;
        this.metrics = metrics;
        this.opener = opener;
        this.holder = properties.resolvedInstanceId();
        refresh();
    }

    /** The opener over the gateway's dispatcher. */
    public static Opener opener(dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher dispatcher) {
        return (upstream, request) -> dispatcher.dispatchStreamingRequest(
                upstream.context(), upstream.serviceFqn(), upstream.methodName(), request, new Metadata());
    }

    /** This instance's name in the leases. */
    public String holder() {
        return holder;
    }

    /** The declared upstreams, as of the last scan. */
    public List<Upstream> upstreams() {
        return upstreams;
    }

    /** The upstream whose pattern matches {@code topic}, or null. */
    public Upstream upstreamFor(Topic topic) {
        for (Upstream u : upstreams) if (u.template().match(topic) != null) return u;
        return null;
    }

    /** {@code owner} when this instance runs the topic's stream, {@code waiting} when another does, null when the topic is not shared here. */
    public String status(Topic topic) {
        Share share = shares.get(topic);
        return share == null ? null : share.owner ? "owner" : "waiting";
    }

    /** Topics with a local subscriber that this instance currently owns the stream of. */
    public List<String> owned() {
        List<String> out = new ArrayList<>();
        shares.forEach((t, s) -> {
            if (s.owner) out.add(t.toString());
        });
        return out;
    }

    /** Rescans the served services' descriptors; the registry calls it after a reload. */
    @Override
    public void onSchemaReloaded() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("Push: upstream topics not refreshed after a schema reload: {}", e.getMessage());
        }
    }

    /** Scans the descriptors for {@code (shiguchi.api.topic)} and declares the namespaces it needs. */
    public void refresh() {
        List<Upstream> found = new ArrayList<>();
        Set<String> patterns = new HashSet<>();
        for (ServiceEndpointContext ctx : registry.allContexts()) {
            for (FileDescriptor fd : ctx.getFileDescriptors().values()) {
                for (ServiceDescriptor service : fd.getServices()) {
                    for (MethodDescriptor method : service.getMethods()) {
                        UpstreamTopic option = method.getOptions().getExtension(TopicProto.topic);
                        if (option == null || option.getPattern().isBlank()) continue;
                        found.add(upstream(ctx, service, method, option, patterns));
                    }
                }
            }
        }
        for (Upstream u : found) declare(u);
        this.upstreams = List.copyOf(found);
        if (!found.isEmpty()) {
            log.info(
                    "Push: {} upstream-backed topic pattern(s): {}",
                    found.size(),
                    found.stream().map(u -> u.template() + " <- " + u.method()).toList());
        }
    }

    private static Upstream upstream(
            ServiceEndpointContext ctx,
            ServiceDescriptor service,
            MethodDescriptor method,
            UpstreamTopic option,
            Set<String> patterns) {
        String where = service.getFullName() + "/" + method.getName();
        if (!method.isServerStreaming() || method.isClientStreaming()) {
            throw new IllegalStateException("(shiguchi.api.topic) on " + where
                    + ": the method must be server-streaming (a unary or client-streaming method cannot feed a topic)");
        }
        TopicTemplate template;
        try {
            template = TopicTemplate.parse(option.getPattern());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("(shiguchi.api.topic) on " + where + ": " + e.getMessage());
        }
        if (!patterns.add(template.toString())) {
            throw new IllegalStateException(
                    "(shiguchi.api.topic) on " + where + ": pattern '" + template + "' is declared twice");
        }
        for (String variable : template.variables()) {
            FieldDescriptor field = method.getInputType().findFieldByName(variable);
            if (field == null) {
                throw new IllegalStateException("(shiguchi.api.topic) on " + where + ": {" + variable
                        + "} is not a field of " + method.getInputType().getFullName());
            }
            if (field.isRepeated() || !isScalar(field)) {
                throw new IllegalStateException("(shiguchi.api.topic) on " + where + ": {" + variable
                        + "} must be a singular string, number, bool or enum field");
            }
        }
        List<FieldDescriptor> eventId = eventIdPath(where, method.getOutputType(), option.getEventIdField());
        return new Upstream(
                template,
                ctx,
                service.getFullName(),
                method.getName(),
                method.getInputType(),
                method.getOutputType(),
                eventId);
    }

    /** The response fields {@code path} walks (dots reach into nested messages); the last is an integer or a string. */
    static List<FieldDescriptor> eventIdPath(String where, Descriptor output, String path) {
        if (path == null || path.isBlank()) return List.of();
        List<FieldDescriptor> chain = new ArrayList<>();
        Descriptor at = output;
        String[] names = path.trim().split("\\.", -1);
        for (int i = 0; i < names.length; i++) {
            FieldDescriptor field = at.findFieldByName(names[i]);
            String problem = null;
            if (field == null) {
                problem = "'" + names[i] + "' is not a field of " + at.getFullName();
            } else if (field.isRepeated()) {
                problem = "'" + names[i] + "' is repeated";
            } else if (i < names.length - 1 && field.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                problem = "'" + names[i] + "' is not a message, so '" + names[i + 1] + "' cannot be read from it";
            } else if (i == names.length - 1 && !isIdType(field)) {
                problem = "'" + names[i] + "' must be an integer field or a string holding a decimal integer";
            }
            if (problem != null) {
                throw new IllegalStateException(
                        "(shiguchi.api.topic) on " + where + ": event_id_field '" + path + "': " + problem);
            }
            chain.add(field);
            if (i < names.length - 1) at = field.getMessageType();
        }
        return List.copyOf(chain);
    }

    private static boolean isIdType(FieldDescriptor field) {
        return switch (field.getJavaType()) {
            case INT, LONG, STRING -> true;
            default -> false;
        };
    }

    /**
     * The event id {@code message} carries along {@code upstream}'s {@code event_id_field}, or null
     * when none is configured or the value is missing: unset, zero or negative, above
     * {@code Long.MAX_VALUE}, or a string that is not a decimal integer.
     */
    static Long eventId(Upstream upstream, com.google.protobuf.MessageOrBuilder message) {
        List<FieldDescriptor> chain = upstream.eventId();
        if (chain.isEmpty()) return null;
        com.google.protobuf.MessageOrBuilder at = message;
        for (int i = 0; i < chain.size() - 1; i++) {
            FieldDescriptor f = chain.get(i);
            if (!at.hasField(f)) return null;
            at = (com.google.protobuf.MessageOrBuilder) at.getField(f);
        }
        FieldDescriptor last = chain.get(chain.size() - 1);
        Object v = at.getField(last);
        long id;
        switch (last.getType()) {
            case STRING -> {
                try {
                    id = Long.parseLong(((String) v).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            case UINT32, FIXED32 -> id = Integer.toUnsignedLong((Integer) v);
            case INT32, SINT32, SFIXED32 -> id = (Integer) v;
            default -> id = (Long) v;
        }
        return id > 0 ? id : null;
    }

    private static boolean isScalar(FieldDescriptor field) {
        return switch (field.getJavaType()) {
            case STRING, INT, LONG, BOOLEAN, ENUM, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    /** The namespace exists (declared when it was not) and carries the response type as its payload type. */
    private void declare(Upstream u) {
        String name = u.template().namespace();
        PushProperties.Namespace ns = properties.getNamespaces().get(name);
        if (ns == null) {
            ns = new PushProperties.Namespace();
            properties.getNamespaces().put(name, ns);
            log.info(
                    "Push: namespace '{}' declared by (shiguchi.api.topic) on {} with default settings",
                    name,
                    u.method());
        }
        String type = u.output().getFullName();
        if (ns.getPayloadType() == null || ns.getPayloadType().isBlank()) {
            ns.setPayloadType(type);
        } else if (!ns.getPayloadType().trim().equals(type)) {
            log.warn(
                    "Push: namespace '{}' has payload-type {} but {} streams {}; its GraphQL field keeps the configured type",
                    name,
                    ns.getPayloadType(),
                    u.method(),
                    type);
        }
    }

    // ---- the per-topic share ----

    /** The live stream of a shared topic with the upstream attached: opened on the first local subscriber, closed after the last. */
    Flux<PushEvent> attach(Topic topic, Flux<PushEvent> live) {
        Upstream upstream = upstreamFor(topic);
        Flux<PushEvent> out = upstream == null
                ? live
                : live.doOnSubscribe(s -> acquire(topic, upstream)).doFinally(s -> release(topic));
        PushHooks h = hooks;
        return h == null ? out : h.track(topic, out);
    }

    private final class Share {
        final Topic topic;
        final Upstream upstream;
        final Backoff backoff = new Backoff(
                properties.getUpstream().getRetryBackoff(),
                properties.getUpstream().getMaxRetryBackoff());
        int refs;
        Disposable run;
        Disposable linger;
        volatile boolean owner;

        Share(Topic topic, Upstream upstream) {
            this.topic = topic;
            this.upstream = upstream;
        }
    }

    private synchronized void acquire(Topic topic, Upstream upstream) {
        Share share = shares.computeIfAbsent(topic, t -> new Share(t, upstream));
        share.refs++;
        if (share.linger != null) {
            share.linger.dispose();
            share.linger = null;
        }
        if (share.run == null) {
            share.run = loop(share)
                    .subscribe(v -> {}, e -> log.warn("Push: upstream loop of {} ended: {}", topic, e.toString()));
        }
    }

    private synchronized void release(Topic topic) {
        Share share = shares.get(topic);
        if (share == null) return;
        share.refs--;
        if (share.refs > 0) return;
        Duration linger = properties.getUpstream().getIdleLinger();
        if (linger == null || linger.isZero() || linger.isNegative()) {
            stop(share);
            return;
        }
        share.linger = Mono.delay(linger, Schedulers.parallel()).subscribe(t -> {
            synchronized (PushUpstreams.this) {
                if (share.refs == 0) stop(share);
            }
        });
    }

    private void stop(Share share) {
        shares.remove(share.topic, share);
        if (share.run != null) share.run.dispose();
        if (share.owner) {
            share.owner = false;
            metrics.upstreamClosed();
            store.release(LEASE_PREFIX + share.topic, holder)
                    .subscribe(
                            v -> {}, e -> log.debug("Push: lease of {} not released: {}", share.topic, e.toString()));
        }
        log.debug("Push: upstream of {} closed", share.topic);
    }

    /** Lease, stream while owner, otherwise wait; forever, with a backoff after a failure or an ended stream. */
    private Mono<Void> loop(Share share) {
        Duration ttl = properties.getUpstream().getLeaseTtl();
        String key = LEASE_PREFIX + share.topic;
        return Flux.defer(() -> store.lease(key, holder, ttl)
                        .flatMap(acquired -> acquired
                                ? owned(share, key, ttl).then(Mono.delay(share.backoff.next(), Schedulers.parallel()))
                                : Mono.delay(ttl, Schedulers.parallel()))
                        .then())
                .onErrorResume(e -> {
                    Duration wait = share.backoff.next();
                    log.warn(
                            "Push: upstream of {} ({}) failed, retrying in {}: {}",
                            share.topic,
                            share.upstream.method(),
                            wait,
                            e.toString());
                    return Mono.delay(wait, Schedulers.parallel()).then();
                })
                .repeat()
                .then();
    }

    /** This instance owns the topic: stream the upstream into it until it ends, the lease is lost, or the share is stopped. */
    private Flux<PushEvent> owned(Share share, String key, Duration ttl) {
        DynamicMessage request = request(share.upstream, share.topic);
        Duration renewEvery = Duration.ofMillis(Math.max(100, ttl.toMillis() / 3));
        Mono<Void> leaseLost = Flux.interval(renewEvery, renewEvery, Schedulers.parallel())
                .concatMap(t -> store.lease(key, holder, ttl).onErrorReturn(false))
                .filter(ok -> !ok)
                .next()
                .doOnNext(lost -> log.warn("Push: lease of {} lost; closing its upstream stream", share.topic))
                .then();
        AtomicLong floor = new AtomicLong();
        return head(share)
                .doOnNext(floor::set)
                .thenMany(Flux.defer(() -> {
                    share.owner = true;
                    metrics.upstreamOpened();
                    log.info("Push: {} opens {} for {}", holder, share.upstream.method(), share.topic);
                    return opener.open(share.upstream, request);
                }))
                .concatMap(message -> publish(share, message, floor).onErrorResume(e -> {
                    log.warn(
                            "Push: event from {} on {} not published: {}",
                            share.upstream.method(),
                            share.topic,
                            e.toString());
                    return Mono.empty();
                }))
                .doOnNext(e -> share.backoff.reset())
                .takeUntilOther(leaseLost)
                .doFinally(signal -> {
                    if (share.owner) {
                        share.owner = false;
                        metrics.upstreamClosed();
                    }
                });
    }

    /**
     * With {@code event_id_field}: the {@code upstream.id} of the newest event the topic holds, so a
     * stream this owner opens after a handoff (the upstream replays) skips what the previous owner
     * already published. Empty when nothing is kept, which includes a topic without history.
     */
    private Mono<Long> head(Share share) {
        if (share.upstream.eventId().isEmpty()) return Mono.empty();
        return store.headEvent(share.topic)
                .flatMap(e -> {
                    String value =
                            e.attributes() == null ? null : e.attributes().get("upstream.id");
                    try {
                        return value == null ? Mono.<Long>empty() : Mono.just(Long.parseLong(value));
                    } catch (NumberFormatException ex) {
                        return Mono.<Long>empty();
                    }
                })
                .onErrorResume(e -> {
                    log.warn("Push: head of {} not read, no duplicate check: {}", share.topic, e.toString());
                    return Mono.empty();
                });
    }

    private Mono<PushEvent> publish(Share share, DynamicMessage message, AtomicLong floor) {
        Upstream u = share.upstream;
        Long id = eventId(u, message);
        if (!u.eventId().isEmpty()) {
            if (id == null) {
                metrics.upstreamId("missing");
            } else if (id <= floor.get()) {
                metrics.upstreamId("duplicate");
                return Mono.empty();
            }
        }
        byte[] proto = message.toByteArray();
        byte[] json;
        try {
            JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
            if (u.context().getTypeRegistry() != null)
                printer = printer.usingTypeRegistry(u.context().getTypeRegistry());
            json = ProtoJsonText.printUtf8(
                    printer, message, u.context().getPrintOptions().numberFormat());
        } catch (Exception e) {
            return Mono.error(e);
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("from", "upstream");
        attributes.put("source", u.method());
        if (id != null) attributes.put("upstream.id", Long.toString(id));
        return publisher
                .publish(
                        share.topic,
                        json,
                        attributes,
                        null,
                        "type.googleapis.com/" + u.output().getFullName(),
                        proto,
                        id)
                .doOnSuccess(e -> {
                    if (id != null) floor.accumulateAndGet(id, Math::max);
                });
    }

    /** The request for {@code topic}: the pattern's variables set on the input message. */
    static DynamicMessage request(Upstream upstream, Topic topic) {
        Map<String, String> variables = upstream.template().match(topic);
        if (variables == null) throw new IllegalStateException(topic + " does not match " + upstream.template());
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(upstream.input());
        for (Map.Entry<String, String> v : variables.entrySet()) {
            FieldDescriptor field = upstream.input().findFieldByName(v.getKey());
            builder.setField(field, value(field, v.getValue()));
        }
        return builder.build();
    }

    private static Object value(FieldDescriptor field, String text) {
        return switch (field.getJavaType()) {
            case STRING -> text;
            case INT -> Integer.parseInt(text);
            case LONG -> Long.parseLong(text);
            case BOOLEAN -> Boolean.parseBoolean(text);
            case FLOAT -> Float.parseFloat(text);
            case DOUBLE -> Double.parseDouble(text);
            case ENUM -> {
                var value = field.getEnumType().findValueByName(text);
                if (value == null)
                    throw new IllegalArgumentException("'" + text + "' is not a value of "
                            + field.getEnumType().getFullName());
                yield value;
            }
            default -> throw new IllegalArgumentException("field " + field.getName() + " cannot be set from a topic");
        };
    }

    @Override
    public synchronized void destroy() {
        for (Share share : List.copyOf(shares.values())) stop(share);
    }

    static String defaultHolder() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException e) {
            host = "gateway";
        }
        return host + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFL);
    }

    /** Doubling wait between attempts, reset once a stream delivers. */
    static final class Backoff {
        private final Duration initial;
        private final Duration max;
        private Duration current;

        Backoff(Duration initial, Duration max) {
            this.initial = initial == null || initial.isNegative() ? Duration.ofSeconds(1) : initial;
            this.max = max == null || max.compareTo(this.initial) < 0 ? this.initial : max;
            this.current = this.initial;
        }

        synchronized Duration next() {
            Duration d = current;
            Duration doubled = current.multipliedBy(2);
            current = doubled.compareTo(max) > 0 ? max : doubled;
            return d;
        }

        synchronized void reset() {
            current = initial;
        }
    }
}
