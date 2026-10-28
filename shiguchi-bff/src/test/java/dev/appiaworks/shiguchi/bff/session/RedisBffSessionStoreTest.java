package dev.appiaworks.shiguchi.bff.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.session.redis.RedisBffSessionStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

/** The contract on a real Redis. Redis expires keys on its own clock, so expiry cases sleep with short ttls. */
@DisplayName("RedisBffSessionStore (real Redis)")
class RedisBffSessionStoreTest extends BffSessionStoreContract {

    private static final AtomicInteger PREFIXES = new AtomicInteger();

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    @BeforeAll
    static void startRedis() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        redis = RedisServer.newRedisServer().port(port).build();
        redis.start();
        connections = new LettuceConnectionFactory("localhost", port);
        connections.afterPropertiesSet();
        template = new ReactiveStringRedisTemplate(connections);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        if (connections != null) connections.destroy();
        if (redis != null) redis.stop();
    }

    @Override
    BffSessionStore store() {
        return new RedisBffSessionStore(template, "test" + PREFIXES.incrementAndGet() + ":");
    }

    @Override
    Duration ttl() {
        return Duration.ofSeconds(3);
    }

    @Override
    void advance(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("GIVEN a saved session WHEN its key is read directly SHOULD be a hash under the prefix with a ttl")
    void given_saved_session_when_key_read_should_be_hash_with_ttl() {
        RedisBffSessionStore store = new RedisBffSessionStore(template, "layout:");
        store.save(session("k"), LONG).block();

        assertThat(template.type("layout:session:k").block().code()).isEqualTo("hash");
        assertThat(template.getExpire("layout:session:k").block()).isBetween(Duration.ofMinutes(29), LONG);
        assertThat(template.opsForHash().hasKey("layout:session:k", "picture").block())
                .isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN a hash written before the authentication fields existed WHEN found SHOULD read as a session without them")
    void given_hash_without_authentication_fields_when_found_should_read_without_them() {
        RedisBffSessionStore store = new RedisBffSessionStore(template, "legacy:");
        template.opsForHash()
                .putAll(
                        "legacy:session:old",
                        java.util.Map.of(
                                "key", "old",
                                "subject", "sub-1",
                                "provider", "google",
                                "application", "web",
                                "accessToken", "at",
                                "created", "1",
                                "lastSeen", "1",
                                "version", "3"))
                .block();

        BffSession found = store.find("old").block();
        assertThat(found).isNotNull();
        assertThat(found.acr()).isNull();
        assertThat(found.amr()).isEmpty();
        assertThat(found.authTime()).isNull();
        assertThat(found.claims()).isEmpty();
        assertThat(found.userAgent()).isNull();
    }

    @Test
    @DisplayName("GIVEN a hash whose claims field is damaged WHEN found SHOULD read as a session without claims")
    void given_hash_with_damaged_claims_when_found_should_read_without_claims() {
        RedisBffSessionStore store = new RedisBffSessionStore(template, "legacy:");
        template.opsForHash()
                .putAll(
                        "legacy:session:bad",
                        java.util.Map.of(
                                "key", "bad",
                                "subject", "sub-1",
                                "provider", "google",
                                "application", "web",
                                "accessToken", "at",
                                "created", "1",
                                "lastSeen", "1",
                                "claims", "{not json"))
                .block();

        BffSession found = store.find("bad").block();
        assertThat(found).isNotNull();
        assertThat(found.claims()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a login hash written before max_age existed WHEN taken SHOULD read as a login without max_age")
    void given_login_hash_without_max_age_when_taken_should_read_without_it() {
        RedisBffSessionStore store = new RedisBffSessionStore(template, "legacy:");
        template.opsForHash()
                .putAll(
                        "legacy:login:state-1",
                        java.util.Map.of(
                                "state", "state-1",
                                "codeVerifier", "v",
                                "nonce", "n",
                                "provider", "google",
                                "application", "web",
                                "returnTo", "https://app/",
                                "redirectUri", "https://gw/cb",
                                "created", "1"))
                .block();

        BffLogin taken = store.takeLogin("state-1").block();
        assertThat(taken).isNotNull();
        assertThat(taken.maxAge()).isNull();
    }

    @Test
    @DisplayName("GIVEN a saved login WHEN its key is read directly SHOULD carry the login timeout")
    void given_saved_login_when_key_read_should_carry_ttl() {
        RedisBffSessionStore store = new RedisBffSessionStore(template, "layout:");
        store.saveLogin(login("st"), Duration.ofMinutes(5)).block();

        assertThat(template.getExpire("layout:login:st").block())
                .isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));
    }

    @Test
    @DisplayName(
            "GIVEN an index member whose session key was deleted directly WHEN looking up or ending by subject SHOULD ignore and prune it")
    void given_stale_index_member_when_looked_up_should_be_pruned_and_not_counted() {
        RedisBffSessionStore store = new RedisBffSessionStore(template, "stale:");
        store.save(session("live"), LONG).block();
        store.save(session("gone"), LONG).block();
        String subject = store.find("live").block().subject();
        String index = "stale:sub:" + store.find("live").block().provider() + ":" + subject;
        assertThat(template.opsForSet().members(index).collectList().block()).contains("live", "gone");
        template.delete("stale:session:gone").block();

        assertThat(store.findBySubject(store.find("live").block().provider(), subject)
                        .map(BffSession::key)
                        .collectList()
                        .block())
                .containsExactly("live");
        assertThat(template.opsForSet().members(index).collectList().block()).containsExactly("live");

        template.opsForSet().add(index, "ghost").block();
        assertThat(store.deleteBySubject(store.find("live").block().provider(), subject)
                        .block())
                .containsExactly("live");
        assertThat(template.opsForSet().members(index).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a saved session WHEN reading its subject index SHOULD hold the key with an expiry at least the session's")
    void given_saved_session_when_reading_index_should_hold_key_with_session_ttl() {
        RedisBffSessionStore store = new RedisBffSessionStore(template, "idx:");
        BffSession s = session("k");
        store.save(s, LONG).block();

        assertThat(template.getExpire("idx:sub:" + s.provider() + ":" + s.subject())
                        .block())
                .isBetween(Duration.ofMinutes(29), LONG);
    }
}
