package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Consumer groups: a namespace with {@code session-key: GROUP} turns a subscription into
 * competing consumers. Members name their group (and themselves) when subscribing; the store
 * hands each event to exactly one member ({@link PushStore#claim}); a member acknowledges what
 * it handled; an event unacknowledged past {@code ack-deadline} is reclaimed by another member
 * and redelivered (the event carries {@code deliveries}); past {@code max-deliveries} it goes to
 * the dead-letter queue; {@code max-inflight} caps what one member holds unacknowledged. A new
 * group starts at the topic's head. Ordering across members is the namespace's {@code ordering}:
 * none ({@code NONE}), per key at one member ({@code KEY}), or a single active member ({@code STRICT}).
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class PushGroups {

    private static final Logger log = LoggerFactory.getLogger(PushGroups.class);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** One member of one group on one topic, as long as its subscription lives. */
    private static final class Member {
        /** The event ids this member holds unacknowledged; a redelivery of one of them does not grow the window. */
        final java.util.Set<String> held = ConcurrentHashMap.newKeySet();

        final Sinks.Many<Long> wake = Sinks.many().multicast().onBackpressureBuffer();

        /** STRICT: holds the group's active lease right now. */
        volatile boolean active;

        /** STRICT: a lease attempt failed since this member last held it, so the next win is a takeover. */
        volatile boolean waited;
    }

    private static String leaseKey(String group, Topic topic) {
        return "group:" + group + ":" + topic + ":active";
    }

    private static Duration leaseTtl(PushProperties.Namespace ns) {
        return ns.getAckDeadline().compareTo(Duration.ofSeconds(5)) < 0 ? Duration.ofSeconds(5) : ns.getAckDeadline();
    }

    private final PushProperties properties;
    private final PushStore store;
    private final PushMetrics metrics;
    private final PushDlq dlq;
    private final ConcurrentHashMap<String, Member> members = new ConcurrentHashMap<>();

    public PushGroups(PushProperties properties, PushStore store, PushMetrics metrics, PushDlq dlq) {
        this.properties = properties;
        this.store = store;
        this.metrics = metrics;
        this.dlq = dlq;
        List<String> keyed = properties.getNamespaces().entrySet().stream()
                .filter(e -> grouped(e.getValue()) && e.getValue().getOrdering() == PushProperties.Ordering.KEY)
                .map(Map.Entry::getKey)
                .toList();
        if (!keyed.isEmpty() && !store.keyOrdering()) {
            throw new IllegalStateException(
                    "shiguchi.cursus: namespace(s) " + keyed
                            + " set ordering KEY but the " + properties.getStore()
                            + " store cannot keep one key at one member; use the MEMORY, POSTGRES or JDBC store, or ordering STRICT");
        }
        List<String> grouped = namespaces();
        if (!grouped.isEmpty() && !store.consumerGroups()) {
            throw new IllegalStateException("shiguchi.cursus: namespace(s) " + grouped
                    + " set session-key GROUP but the " + properties.getStore() + " store keeps no consumer groups");
        }
    }

    /** True when {@code ns} is a consumer-group namespace. */
    public static boolean grouped(PushProperties.Namespace ns) {
        return ns != null && ns.getSessionKey() == PushProperties.SessionKey.GROUP;
    }

    /** The consumer-group namespaces, for the overview. */
    public List<String> namespaces() {
        return properties.getNamespaces().entrySet().stream()
                .filter(e -> grouped(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** A group or member name as a client sent it, validated. */
    static String name(String what, String value) {
        if (value == null || value.isBlank()) throw PushException.badRequest(what + " is required");
        String v = value.trim();
        if (!NAME.matcher(v).matches()) {
            throw PushException.badRequest(
                    what + " must be 1-128 letters, digits, '.', '_' or '-', got '" + value + "'");
        }
        return v;
    }

    /** How many members are subscribed on this instance. */
    public int members() {
        return members.size();
    }

    /**
     * {@code member}'s share of {@code topic} in {@code group}: events the store hands to it, claimed
     * on subscribe, on every live event ({@code live} only triggers the claim), on a timer for the
     * reclaim of others' expired claims, and when an ack frees a slot. Ends when the subscriber
     * leaves; its unacknowledged claims are released for the others.
     */
    Flux<PushEvent> memberStream(
            Topic topic, PushProperties.Namespace ns, String group, String member, Flux<PushEvent> live) {
        return Flux.defer(() -> {
            String key = memberKey(group, topic, member);
            Member m = new Member();
            Member previous = members.put(key, m);
            if (previous != null) previous.wake.tryEmitComplete();
            Duration deadline = ns.getAckDeadline();
            Duration reclaimEvery = deadline.dividedBy(2).compareTo(Duration.ofMillis(250)) < 0
                    ? Duration.ofMillis(250)
                    : deadline.dividedBy(2);
            Flux<Long> triggers = Flux.merge(
                            Flux.just(0L),
                            live.map(e -> 1L).onErrorResume(e -> Flux.empty()),
                            Flux.interval(reclaimEvery, reclaimEvery, Schedulers.parallel()),
                            m.wake.asFlux())
                    .onBackpressureLatest();
            return triggers.concatMap(t -> claim(topic, ns, group, member, m), 1)
                    .doFinally(s -> {
                        members.remove(key, m);
                        m.wake.tryEmitComplete();
                        if (ns.getOrdering() == PushProperties.Ordering.STRICT && m.active) {
                            m.active = false;
                            store.release(leaseKey(group, topic), member).subscribe(v -> {}, e -> {});
                        }
                        store.releaseClaims(group, topic, member)
                                .subscribe(
                                        n -> {
                                            if (n > 0)
                                                log.debug("Push: {} released {} claim(s) on {}", member, n, topic);
                                        },
                                        e -> log.warn(
                                                "Push: claims of {} on {} not released: {}",
                                                member,
                                                topic,
                                                e.toString()));
                    });
        });
    }

    private Flux<PushEvent> claim(Topic topic, PushProperties.Namespace ns, String group, String member, Member m) {
        if (ns.getOrdering() != PushProperties.Ordering.STRICT) return claimEvents(topic, ns, group, member, m);
        return store.lease(leaseKey(group, topic), member, leaseTtl(ns))
                .onErrorReturn(false)
                .flatMapMany(won -> {
                    if (!won) {
                        m.active = false;
                        m.waited = true;
                        return Flux.<PushEvent>empty();
                    }
                    if (!m.active && m.waited) {
                        metrics.strictTakeover();
                        log.info("Push: {} took over group {} on {}", member, group, topic);
                    }
                    m.active = true;
                    m.waited = false;
                    return claimEvents(topic, ns, group, member, m);
                });
    }

    private Flux<PushEvent> claimEvents(
            Topic topic, PushProperties.Namespace ns, String group, String member, Member m) {
        int room = ns.getMaxInflight() - m.held.size();
        if (room <= 0) return Flux.empty();
        return store.claim(group, topic, member, room, ns.getAckDeadline(), ns.getSessionExpiry(), ns.getOrdering())
                .concatMap(c -> {
                    if (c.deliveries() > ns.getMaxDeliveries()) {
                        PushEvent e = c.event();
                        dlq.add(
                                "group:" + group,
                                "max-deliveries " + ns.getMaxDeliveries() + " exceeded (" + (c.deliveries() - 1)
                                        + " deliveries unacknowledged)",
                                e.topic().toString(),
                                e.key(),
                                e.data(),
                                e.attributes(),
                                e.typeUrl(),
                                e.proto());
                        metrics.groupDelivery("dead_lettered");
                        log.warn(
                                "Push: group {} dead-lettered {} on {} after {} deliveries",
                                group,
                                e.id(),
                                topic,
                                c.deliveries() - 1);
                        return store.ackGroup(group, topic, member, e.id()).then(Mono.<PushEvent>empty());
                    }
                    m.held.add(c.event().id());
                    metrics.groupDelivery(c.deliveries() > 1 ? "redelivery" : "first");
                    return Mono.just(c.deliveries() > 1 ? withDeliveries(c.event(), c.deliveries()) : c.event());
                })
                .onErrorResume(e -> {
                    log.warn("Push: claim for {} in group {} on {} failed: {}", member, group, topic, e.toString());
                    return Flux.empty();
                });
    }

    private static PushEvent withDeliveries(PushEvent e, int deliveries) {
        Map<String, String> attributes = new LinkedHashMap<>(e.attributes());
        attributes.put("deliveries", Integer.toString(deliveries));
        return new PushEvent(e.topic(), e.id(), e.data(), attributes, e.key(), e.time(), e.typeUrl(), e.proto());
    }

    /**
     * {@code member} acknowledges {@code id} on {@code topic} in {@code group}: the claim is released
     * for good and the member may take another event. Refused when the namespace is not a group
     * namespace, the names are bad, the caller may not subscribe, or the member holds no such claim.
     */
    public Mono<Void> ack(
            Authentication auth, String group, String member, String topicName, String id, TopicAuthorizer authorizer) {
        Topic topic;
        String g;
        String mb;
        try {
            topic = Topic.parse(topicName);
            g = name("group", group);
            mb = name("member", member);
        } catch (IllegalArgumentException e) {
            return Mono.error(PushException.badRequest(e.getMessage()));
        } catch (PushException e) {
            return Mono.error(e);
        }
        PushProperties.Namespace ns = properties.namespaceOf(topic);
        if (ns == null) return Mono.error(PushException.unknownNamespace(topic));
        if (!grouped(ns)) {
            return Mono.error(PushException.badRequest("namespace '" + topic.namespace()
                    + "' is not a consumer-group namespace (session-key is not GROUP)"));
        }
        if (id == null || id.isBlank()) return Mono.error(PushException.badRequest("id is required"));
        return authorizer
                .authorizeSubscribe(auth, topic, ns)
                .then(store.ackGroup(g, topic, mb, id))
                .flatMap(acked -> {
                    if (!acked) {
                        // The claim is gone (expired, taken over or dropped by an admin seek): stop counting it.
                        Member gone = members.get(memberKey(g, topic, mb));
                        if (gone != null && gone.held.remove(id)) gone.wake.tryEmitNext(1L);
                        log.debug("Push: {} of group {} acked {} on {} without a claim", mb, g, id, topic);
                        return Mono.error(PushException.notFound(
                                "member " + mb + " of group " + g + " holds no claim of " + id + " on " + topic));
                    }
                    metrics.groupAck();
                    Member m = members.get(memberKey(g, topic, mb));
                    if (m != null) {
                        m.held.remove(id);
                        m.wake.tryEmitNext(1L);
                    }
                    return Mono.empty();
                });
    }

    private static String memberKey(String group, Topic topic, String member) {
        return group + "|" + topic + "|" + member;
    }
}
