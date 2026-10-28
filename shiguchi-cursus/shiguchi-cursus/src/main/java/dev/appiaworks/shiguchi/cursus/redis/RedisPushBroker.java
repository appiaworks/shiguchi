package dev.appiaworks.shiguchi.cursus.redis;

import dev.appiaworks.shiguchi.cursus.PushBroker;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushEventCodec;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Fan-out through Redis Pub/Sub: one channel per topic ({@code <prefix>ch:<topic>}), one
 * subscription per topic per gateway instance, multiplexed on one listener connection, shared by
 * every local subscriber of that topic and dropped with the last. The publishing instance receives
 * its own events back through Redis like any other, so every instance sees the same order.
 *
 * <p>Pub/Sub is at most once: an instance that is disconnected at that moment misses the event.
 * The {@link RedisPushStore} is what a subscriber resumes from.
 */
public final class RedisPushBroker implements PushBroker, org.springframework.beans.factory.DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RedisPushBroker.class);

    private final ReactiveStringRedisTemplate redis;
    private final ReactiveRedisMessageListenerContainer container;
    private final String channelPrefix;
    private final ConcurrentHashMap<Topic, Flux<PushEvent>> shared = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Topic, AtomicInteger> subscribers = new ConcurrentHashMap<>();

    public RedisPushBroker(ReactiveStringRedisTemplate redis, String keyPrefix) {
        this.redis = redis;
        this.container = new ReactiveRedisMessageListenerContainer(redis.getConnectionFactory());
        this.channelPrefix = keyPrefix + "ch:";
    }

    @Override
    public Mono<Void> publish(PushEvent event) {
        return redis.convertAndSend(channelPrefix + event.topic(), PushEventCodec.encode(event))
                .then();
    }

    @Override
    public Flux<PushEvent> subscribe(Topic topic) {
        return Flux.defer(() -> shared.computeIfAbsent(topic, this::open)
                .doOnSubscribe(s -> subscribers
                        .computeIfAbsent(topic, t -> new AtomicInteger())
                        .incrementAndGet())
                .doFinally(s -> subscribers.computeIfPresent(topic, (t, n) -> n.decrementAndGet() <= 0 ? null : n)));
    }

    /**
     * The Redis subscription for one topic, shared by its local subscribers through
     * {@code refCount}: opened with the first, closed and forgotten with the last.
     */
    private Flux<PushEvent> open(Topic topic) {
        RedisSerializationContext.SerializationPair<String> strings =
                RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.string());
        return container
                .receive(List.of(ChannelTopic.of(channelPrefix + topic)), strings, strings)
                .map(ReactiveSubscription.Message::getMessage)
                .map(PushEventCodec::decode)
                .doOnError(e -> log.warn("Push: Redis subscription for {} failed: {}", topic, e.toString()))
                .doFinally(s -> shared.remove(topic))
                .publish()
                .refCount();
    }

    @Override
    public long subscriberCount(Topic topic) {
        AtomicInteger n = subscribers.get(topic);
        return n == null ? 0 : n.get();
    }

    @Override
    public java.util.Map<Topic, Long> subscriptions() {
        java.util.Map<Topic, Long> out = new java.util.HashMap<>();
        subscribers.forEach((t, n) -> {
            if (n.get() > 0) out.put(t, (long) n.get());
        });
        return out;
    }

    /** Topics this instance holds a Redis subscription for. */
    public int activeTopics() {
        return shared.size();
    }

    @Override
    public void destroy() {
        try {
            container.destroy();
        } catch (RuntimeException e) {
            // The container hands its connection back when the last subscription ends; with a
            // pooling provider, handing it back again on destroy is refused. Nothing is leaked.
            log.debug("Push: Redis listener container already released: {}", e.toString());
        }
    }
}
