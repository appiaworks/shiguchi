package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What every {@link PushStore} flavour must do for echo suppression: the {@code push.origin}
 * attribute survives {@code append} and {@code range} (the replay path), and a persistent session
 * that declares the origin has its cursor moved over the leading run of own-origin events only.
 * Each store test class calls {@link #verify(PushStore)} against its own engine.
 */
public final class PushOriginStoreContract {

    private static final Duration HOUR = Duration.ofHours(1);

    private PushOriginStoreContract() {}

    public static void verify(PushStore store) {
        Topic topic = Topic.parse("inbox:alice");
        PushProperties properties = new PushProperties();
        PushProperties.Namespace inbox = new PushProperties.Namespace();
        inbox.setQos(Qos.AT_LEAST_ONCE);
        inbox.setHistorySize(20);
        inbox.setSessionExpiry(HOUR);
        inbox.setSessionKey(PushProperties.SessionKey.PRINCIPAL);
        properties.getNamespaces().put("inbox", inbox);
        PushSessions sessions = new PushSessions(properties, store, new PushMetrics(new SimpleMeterRegistry()));

        PushEvent seen = append(store, topic, Map.of());
        store.ack("alice", topic, seen.id(), HOUR).block();
        PushEvent echo1 = append(store, topic, Map.of(PushOrigin.ATTRIBUTE, "dev1"));
        PushEvent echo2 = append(store, topic, Map.of(PushOrigin.ATTRIBUTE, "dev1", "tag", "t"));
        PushEvent foreign = append(store, topic, Map.of(PushOrigin.ATTRIBUTE, "dev2"));
        PushEvent echo3 = append(store, topic, Map.of(PushOrigin.ATTRIBUTE, "dev1"));

        List<PushEvent> replayed =
                store.range(topic, seen.id(), 10).collectList().block(Duration.ofSeconds(10));
        assertThat(replayed)
                .extracting(PushEvent::id)
                .containsExactly(echo1.id(), echo2.id(), foreign.id(), echo3.id());
        assertThat(replayed.get(0).attributes()).containsEntry(PushOrigin.ATTRIBUTE, "dev1");
        assertThat(replayed.get(1).attributes())
                .containsEntry(PushOrigin.ATTRIBUTE, "dev1")
                .containsEntry("tag", "t");
        assertThat(replayed.get(2).attributes()).containsEntry(PushOrigin.ATTRIBUTE, "dev2");

        PushSessions.Start start =
                sessions.start(inbox, "alice", topic, null, "dev1", null).block(Duration.ofSeconds(10));

        assertThat(start.dropped()).isZero();
        assertThat(start.afterId()).isEqualTo(echo2.id());
        assertThat(store.cursor("alice", topic).block(Duration.ofSeconds(10))).isEqualTo(echo2.id());
        List<PushEvent> pending =
                store.range(topic, start.afterId(), 10).collectList().block(Duration.ofSeconds(10));
        assertThat(pending).extracting(PushEvent::id).containsExactly(foreign.id(), echo3.id());
    }

    private static PushEvent append(PushStore store, Topic topic, Map<String, String> attributes) {
        return store.append(new PushEvent(topic, null, "{}".getBytes(), attributes, null, null), 20, HOUR)
                .block(Duration.ofSeconds(10));
    }
}
