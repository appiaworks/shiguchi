package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.bff.session.jdbc.JdbcBffSessionStore;
import dev.appiaworks.shiguchi.bff.session.redis.RedisBffSessionStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import redis.embedded.RedisServer;

@DisplayName("EncryptingBffSessionStore: tokens are ciphertext in the store and plaintext for callers")
class EncryptingBffSessionStoreTest {

    private static final byte[] K1 = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] K2 = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final Duration LONG = Duration.ofMinutes(30);
    private static final String ACCESS = "access-token-value";
    private static final String REFRESH = "refresh-token-value";
    private static final String ID = "id-token-value";

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    private MemoryBffSessionStore memory;
    private BffSessionStore store;
    private Logger logger;
    private ListAppender<ILoggingEvent> logs;

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

    @BeforeEach
    void setUp() {
        memory = new MemoryBffSessionStore();
        store = new EncryptingBffSessionStore(memory, new BffTokenCipher(Map.of("k1", K1, "k2", K2), "k1"));
        logger = (Logger) LoggerFactory.getLogger(EncryptingBffSessionStore.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        memory.close();
    }

    private static BffSession session(String key, long version) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new BffSession(
                key,
                "sub-1",
                "idp",
                "web",
                "sid-1",
                "Ann",
                "ann@example.com",
                null,
                ACCESS,
                now.plusSeconds(600),
                REFRESH,
                ID,
                now,
                now,
                version);
    }

    @Test
    @DisplayName("GIVEN a saved session WHEN found SHOULD return plaintext tokens while the delegate holds ciphertext")
    void given_saved_session_when_found_should_return_plaintext() {
        store.save(session("k", 1), LONG).block();

        assertThat(store.find("k").block()).isEqualTo(session("k", 1));
        BffSession raw = memory.find("k").block();
        assertThat(raw.accessToken()).startsWith("v1.k1.").doesNotContain(ACCESS);
        assertThat(raw.refreshToken()).startsWith("v1.k1.").doesNotContain(REFRESH);
        assertThat(raw.idToken()).startsWith("v1.k1.").doesNotContain(ID);
        assertThat(raw.subject()).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("GIVEN a session without refresh token WHEN saved and found SHOULD keep it null")
    void given_no_refresh_token_when_saved_should_stay_null() {
        BffSession plain = session("k", 1);
        BffSession noRefresh = new BffSession(
                "k",
                plain.subject(),
                plain.provider(),
                plain.application(),
                plain.sid(),
                null,
                null,
                null,
                ACCESS,
                plain.accessTokenExpiresAt(),
                null,
                null,
                plain.created(),
                plain.lastSeen(),
                1);
        store.save(noRefresh, LONG).block();

        assertThat(store.find("k").block()).isEqualTo(noRefresh);
    }

    @Test
    @DisplayName("GIVEN a stored session WHEN replaced by version SHOULD swap the tokens and bump the version")
    void given_session_when_replaced_if_version_should_swap_tokens() {
        store.save(session("k", 1), LONG).block();
        BffSession next = session("k", 2);

        assertThat(store.replaceIfVersion(next, 1).block()).isTrue();
        assertThat(store.replaceIfVersion(next, 1).block()).isFalse();
        assertThat(store.find("k").block()).isEqualTo(next);
    }

    @Test
    @DisplayName("GIVEN a row with plaintext tokens WHEN found SHOULD read as absent and be skipped by findBySubject")
    void given_plaintext_row_when_found_should_read_as_absent() {
        memory.save(session("old", 1), LONG).block();
        store.save(session("new", 1), LONG).block();

        assertThat(store.find("old").blockOptional()).isEmpty();
        List<BffSession> found =
                store.findBySubject("idp", "sub-1").collectList().block();
        assertThat(found).extracting(BffSession::key).containsExactly("new");
    }

    @Test
    @DisplayName("GIVEN a row written under a key this ring lacks WHEN found SHOULD read as absent")
    void given_unknown_key_row_when_found_should_read_as_absent() {
        BffSessionStore other = new EncryptingBffSessionStore(memory, new BffTokenCipher(Map.of("k9", K2), "k9"));
        other.save(session("k", 1), LONG).block();

        assertThat(store.find("k").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN rows copied between keys WHEN found SHOULD read as absent")
    void given_row_copied_to_other_key_when_found_should_read_as_absent() {
        store.save(session("a", 1), LONG).block();
        BffSession raw = memory.find("a").block();
        memory.save(
                        new BffSession(
                                "b",
                                raw.subject(),
                                raw.provider(),
                                raw.application(),
                                raw.sid(),
                                null,
                                null,
                                null,
                                raw.accessToken(),
                                raw.accessTokenExpiresAt(),
                                raw.refreshToken(),
                                raw.idToken(),
                                raw.created(),
                                raw.lastSeen(),
                                1),
                        LONG)
                .block();

        assertThat(store.find("b").blockOptional()).isEmpty();
        assertThat(store.find("a").blockOptional()).isPresent();
    }

    @Test
    @DisplayName("GIVEN undecryptable rows WHEN read repeatedly SHOULD warn once per minute without any token or key")
    void given_undecryptable_rows_when_read_should_warn_once_and_not_leak() {
        AtomicLong now = new AtomicLong(1_000_000);
        BffTokenCipher cipher = new BffTokenCipher(Map.of("k1", K1), "k1");
        BffSessionStore timed = new EncryptingBffSessionStore(memory, cipher, now::get);
        memory.save(session("old", 1), LONG).block();
        BffSessionStore other = new EncryptingBffSessionStore(memory, new BffTokenCipher(Map.of("k9", K2), "k9"));
        other.save(session("foreign", 1), LONG).block();
        String foreignCiphertext = memory.find("foreign").block().accessToken();

        timed.find("old").block();
        timed.find("old").block();
        timed.find("foreign").block();
        now.addAndGet(61_000);
        timed.find("old").block();

        List<String> messages =
                logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).hasSize(3);
        assertThat(messages).anyMatch(m -> m.contains("key id k9"));
        assertThat(String.join("\n", messages))
                .doesNotContain(ACCESS)
                .doesNotContain(REFRESH)
                .doesNotContain(ID)
                .doesNotContain(foreignCiphertext)
                .doesNotContain(new String(K1, StandardCharsets.UTF_8))
                .doesNotContain(new String(K2, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("GIVEN a saved session WHEN deleted by sid SHOULD report its key without reading tokens")
    void given_saved_session_when_deleted_by_sid_should_report_key() {
        store.save(session("k", 1), LONG).block();

        assertThat(store.deleteBySid("idp", "sid-1").block()).containsExactly("k");
        assertThat(store.find("k").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN the Redis store WHEN a session is saved SHOULD hold ciphertext in the hash and return plaintext")
    void given_redis_store_when_saved_should_hold_ciphertext() {
        BffSessionStore encrypted = new EncryptingBffSessionStore(
                new RedisBffSessionStore(template, "enc:"), new BffTokenCipher(Map.of("k1", K1), "k1"));
        encrypted.save(session("rk", 1), LONG).block();

        for (String field : List.of("accessToken", "refreshToken", "idToken")) {
            Object value = template.opsForHash().get("enc:session:rk", field).block();
            assertThat(String.valueOf(value)).startsWith("v1.k1.").doesNotContain(ACCESS, REFRESH, ID);
        }
        assertThat(encrypted.find("rk").block()).isEqualTo(session("rk", 1));
        assertThat(encrypted.replaceIfVersion(session("rk", 2), 1).block()).isTrue();
        assertThat(encrypted.find("rk").block().version()).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN the JDBC store WHEN a session is saved SHOULD hold ciphertext in the columns and return plaintext")
    void given_jdbc_store_when_saved_should_hold_ciphertext() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:bff_enc_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        BffProperties.Jdbc config = new BffProperties.Jdbc();
        config.setSweepInterval(Duration.ZERO);
        JdbcBffSessionStore jdbcStore = new JdbcBffSessionStore(ds, config);
        EncryptingBffSessionStore encrypted =
                new EncryptingBffSessionStore(jdbcStore, new BffTokenCipher(Map.of("k1", K1), "k1"));
        try {
            encrypted.afterPropertiesSet();
            encrypted.save(session("jk", 1), LONG).block();

            Map<String, Object> row = new JdbcTemplate(ds)
                    .queryForMap("SELECT access_token, refresh_token, id_token FROM gw_bff_session"
                            + " WHERE session_key = 'jk'");
            row.values()
                    .forEach(v ->
                            assertThat(String.valueOf(v)).startsWith("v1.k1.").doesNotContain(ACCESS, REFRESH, ID));
            assertThat(encrypted.find("jk").block()).isEqualTo(session("jk", 1));
            assertThat(encrypted.replaceIfVersion(session("jk", 2), 1).block()).isTrue();
            assertThat(encrypted.find("jk").block().version()).isEqualTo(2);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            try {
                encrypted.destroy();
            } catch (Exception ignored) {
                // nothing to release beyond the sweep
            }
        }
    }
}
