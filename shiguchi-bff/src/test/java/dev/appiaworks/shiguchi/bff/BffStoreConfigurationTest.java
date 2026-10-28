package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.bff.session.jdbc.JdbcBffSessionStore;
import dev.appiaworks.shiguchi.bff.session.redis.RedisBffSessionStore;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

@DisplayName("BffStoreConfiguration")
class BffStoreConfigurationTest {

    private static final String PLAINTEXT = "shiguchi.bff.session.encryption.disabled=true";

    private static final String ENC_ACTIVE = "shiguchi.bff.session.encryption.active=k1";

    private static final String ENC_KEY =
            "shiguchi.bff.session.encryption.keys.k1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BffStoreConfiguration.class)
            .withPropertyValues("shiguchi.bff.enabled=true");

    private static DataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:bff_wiring_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    private static ReactiveStringRedisTemplate redisTemplate() {
        // Not connected: Lettuce connects on first use, and these tests never use it.
        return new ReactiveStringRedisTemplate(new LettuceConnectionFactory("localhost", 1));
    }

    @Test
    @DisplayName("GIVEN no store property WHEN the context starts SHOULD use the memory store")
    void given_no_store_property_when_started_should_use_memory_store() {
        runner.run(ctx -> assertThat(ctx).getBean(BffSessionStore.class).isInstanceOf(MemoryBffSessionStore.class));
    }

    @Test
    @DisplayName("GIVEN the module off WHEN the context starts SHOULD create no store")
    void given_module_off_when_started_should_create_no_store() {
        new ApplicationContextRunner()
                .withUserConfiguration(BffStoreConfiguration.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BffSessionStore.class));
    }

    @Test
    @DisplayName("GIVEN store REDIS and a Redis template WHEN the context starts SHOULD use the Redis store")
    void given_redis_and_template_when_started_should_use_redis_store() {
        runner.withPropertyValues("shiguchi.bff.session.store=REDIS", PLAINTEXT)
                .withBean(ReactiveStringRedisTemplate.class, BffStoreConfigurationTest::redisTemplate)
                .run(ctx -> assertThat(ctx).getBean(BffSessionStore.class).isInstanceOf(RedisBffSessionStore.class));
    }

    @Test
    @DisplayName("GIVEN store REDIS and no Redis template WHEN the context starts SHOULD fail naming what to add")
    void given_redis_without_template_when_started_should_fail_naming_dependency() {
        runner.withPropertyValues("shiguchi.bff.session.store=REDIS", PLAINTEXT)
                .run(ctx -> assertThat(ctx)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("shiguchi.bff.session.store is REDIS")
                        .hasMessageContaining("ReactiveStringRedisTemplate")
                        .hasMessageContaining("spring-boot-starter-data-redis-reactive"));
    }

    @Test
    @DisplayName("GIVEN store JDBC and one DataSource WHEN the context starts SHOULD use the JDBC store")
    void given_jdbc_and_data_source_when_started_should_use_jdbc_store() {
        runner.withPropertyValues("shiguchi.bff.session.store=JDBC", PLAINTEXT)
                .withBean(DataSource.class, BffStoreConfigurationTest::h2)
                .run(ctx -> assertThat(ctx).getBean(BffSessionStore.class).isInstanceOf(JdbcBffSessionStore.class));
    }

    @Test
    @DisplayName("GIVEN store REDIS with keys WHEN the context starts SHOULD wrap the store in the encrypting one")
    void given_redis_with_keys_when_started_should_wrap_in_encrypting_store() {
        runner.withPropertyValues("shiguchi.bff.session.store=REDIS", ENC_ACTIVE, ENC_KEY)
                .withBean(ReactiveStringRedisTemplate.class, BffStoreConfigurationTest::redisTemplate)
                .run(ctx ->
                        assertThat(ctx).getBean(BffSessionStore.class).isInstanceOf(EncryptingBffSessionStore.class));
    }

    @Test
    @DisplayName("GIVEN store JDBC with keys WHEN the context starts SHOULD wrap the store in the encrypting one")
    void given_jdbc_with_keys_when_started_should_wrap_in_encrypting_store() {
        runner.withPropertyValues("shiguchi.bff.session.store=JDBC", ENC_ACTIVE, ENC_KEY)
                .withBean(DataSource.class, BffStoreConfigurationTest::h2)
                .run(ctx ->
                        assertThat(ctx).getBean(BffSessionStore.class).isInstanceOf(EncryptingBffSessionStore.class));
    }

    @Test
    @DisplayName(
            "GIVEN store REDIS without keys and not disabled WHEN the context starts SHOULD fail naming the settings")
    void given_redis_without_keys_when_started_should_fail() {
        runner.withPropertyValues("shiguchi.bff.session.store=REDIS")
                .withBean(ReactiveStringRedisTemplate.class, BffStoreConfigurationTest::redisTemplate)
                .run(ctx -> assertThat(ctx)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("session.encryption.keys")
                        .hasMessageContaining("disabled: true"));
    }

    @Test
    @DisplayName("GIVEN store MEMORY with keys WHEN the context starts SHOULD keep the unencrypted memory store")
    void given_memory_with_keys_when_started_should_not_encrypt() {
        runner.withPropertyValues(ENC_ACTIVE, ENC_KEY)
                .run(ctx -> assertThat(ctx).getBean(BffSessionStore.class).isInstanceOf(MemoryBffSessionStore.class));
    }

    @Test
    @DisplayName("GIVEN store JDBC and no DataSource WHEN the context starts SHOULD fail naming what to add")
    void given_jdbc_without_data_source_when_started_should_fail_naming_dependency() {
        runner.withPropertyValues("shiguchi.bff.session.store=JDBC", PLAINTEXT)
                .run(ctx -> assertThat(ctx)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("shiguchi.bff.session.store is JDBC")
                        .hasMessageContaining("DataSource")
                        .hasMessageContaining("spring-boot-starter-jdbc"));
    }

    @Test
    @DisplayName("GIVEN store JDBC and a named DataSource that does not exist WHEN started SHOULD fail naming it")
    void given_jdbc_with_unknown_data_source_name_when_started_should_fail_naming_it() {
        runner.withPropertyValues(
                        "shiguchi.bff.session.store=JDBC", "shiguchi.bff.session.jdbc.data-source=sessions", PLAINTEXT)
                .withBean(DataSource.class, BffStoreConfigurationTest::h2)
                .run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("data-source is 'sessions'"));
    }

    @Test
    @DisplayName("GIVEN store JDBC and two DataSources WHEN the named one is configured SHOULD use it")
    void given_two_data_sources_when_one_is_named_should_use_it() {
        runner.withPropertyValues(
                        "shiguchi.bff.session.store=JDBC", "shiguchi.bff.session.jdbc.data-source=sessions", PLAINTEXT)
                .withBean("sessions", DataSource.class, BffStoreConfigurationTest::h2)
                .withBean("other", DataSource.class, BffStoreConfigurationTest::h2)
                .run(ctx -> assertThat(ctx).getBean(BffSessionStore.class).isInstanceOf(JdbcBffSessionStore.class));
    }

    @Test
    @DisplayName("GIVEN store REDIS or JDBC and a user store bean WHEN the context starts SHOULD keep the user bean")
    void given_user_store_bean_when_started_should_keep_it() {
        BffSessionStore mine = new BffSessionStore() {
            @Override
            public Mono<Void> save(BffSession session, Duration ttl) {
                return Mono.empty();
            }

            @Override
            public Mono<BffSession> find(String key) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> delete(String key) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> saveLogin(BffLogin login, Duration ttl) {
                return Mono.empty();
            }

            @Override
            public Mono<BffLogin> takeLogin(String state) {
                return Mono.empty();
            }
        };
        for (String kind : new String[] {"REDIS", "JDBC"}) {
            runner.withPropertyValues("shiguchi.bff.session.store=" + kind)
                    .withBean(BffSessionStore.class, () -> mine)
                    .run(ctx -> assertThat(ctx).getBean(BffSessionStore.class).isSameAs(mine));
        }
    }
}
