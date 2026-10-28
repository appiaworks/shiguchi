package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.bff.session.jdbc.JdbcBffSessionStore;
import dev.appiaworks.shiguchi.bff.session.redis.RedisBffSessionStore;
import javax.sql.DataSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Picks the {@link BffSessionStore} from {@code shiguchi.bff.session.store}. A user-provided
 * {@link BffSessionStore} bean wins over all of them. The Redis and JDBC stores live in nested
 * configurations guarded by {@code @ConditionalOnClass}, so the module works without Spring Data
 * Redis or Spring JDBC on the classpath; choosing {@code REDIS} or {@code JDBC} without the
 * dependency, or without the Redis template or {@code DataSource} bean, fails startup with a
 * message naming what to add.
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.bff", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BffProperties.class)
class BffStoreConfiguration {

    static final String MISSING_REDIS = "shiguchi.bff.session.store is REDIS but Spring Data Redis is not on the"
            + " classpath: add spring-boot-starter-data-redis-reactive and configure spring.data.redis.*";

    static final String MISSING_JDBC = "shiguchi.bff.session.store is JDBC but Spring JDBC is not on the"
            + " classpath: add spring-boot-starter-jdbc and your database driver, and configure spring.datasource.*";

    static final String NO_REDIS_TEMPLATE = "shiguchi.bff.session.store is REDIS but there is no"
            + " ReactiveStringRedisTemplate bean: add spring-boot-starter-data-redis-reactive and configure"
            + " spring.data.redis.*";

    static final String NO_DATA_SOURCE = "shiguchi.bff.session.store is JDBC but there is not exactly one"
            + " DataSource bean: add spring-boot-starter-jdbc and configure spring.datasource.*, or name one with"
            + " shiguchi.bff.session.jdbc.data-source";

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(BffSessionStore.class)
    @ConditionalOnProperty(
            prefix = "shiguchi.bff.session",
            name = "store",
            havingValue = "MEMORY",
            matchIfMissing = true)
    public BffSessionStore bffMemorySessionStore() {
        return new MemoryBffSessionStore();
    }

    /** Catches {@code store: REDIS} without Spring Data Redis on the classpath. */
    @Bean
    @ConditionalOnMissingBean(BffSessionStore.class)
    @ConditionalOnMissingClass("org.springframework.data.redis.core.ReactiveStringRedisTemplate")
    @ConditionalOnProperty(prefix = "shiguchi.bff.session", name = "store", havingValue = "REDIS")
    public BffSessionStore bffMissingRedisSessionStore() {
        throw new IllegalStateException(MISSING_REDIS);
    }

    /** Catches {@code store: JDBC} without Spring JDBC on the classpath. */
    @Bean
    @ConditionalOnMissingBean(BffSessionStore.class)
    @ConditionalOnMissingClass("org.springframework.jdbc.core.JdbcTemplate")
    @ConditionalOnProperty(prefix = "shiguchi.bff.session", name = "store", havingValue = "JDBC")
    public BffSessionStore bffMissingJdbcSessionStore() {
        throw new IllegalStateException(MISSING_JDBC);
    }

    /** Present only when Spring Data Redis is on the classpath. */
    @Configuration
    @ConditionalOnProperty(prefix = "shiguchi.bff", name = "enabled", havingValue = "true")
    @ConditionalOnClass(ReactiveStringRedisTemplate.class)
    static class Redis {

        @Bean
        @ConditionalOnMissingBean(BffSessionStore.class)
        @ConditionalOnProperty(prefix = "shiguchi.bff.session", name = "store", havingValue = "REDIS")
        public BffSessionStore bffRedisSessionStore(
                BffProperties properties, ObjectProvider<ReactiveStringRedisTemplate> templates) {
            ReactiveStringRedisTemplate template = templates.getIfAvailable();
            if (template == null) throw new IllegalStateException(NO_REDIS_TEMPLATE);
            return EncryptingBffSessionStore.wrap(
                    new RedisBffSessionStore(
                            template, properties.getSession().getRedis().getKeyPrefix()),
                    properties.getSession());
        }
    }

    /** Present only when Spring JDBC is on the classpath. */
    @Configuration
    @ConditionalOnProperty(prefix = "shiguchi.bff", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    static class Jdbc {

        @Bean
        @ConditionalOnMissingBean(BffSessionStore.class)
        @ConditionalOnProperty(prefix = "shiguchi.bff.session", name = "store", havingValue = "JDBC")
        public BffSessionStore bffJdbcSessionStore(
                BffProperties properties, ObjectProvider<DataSource> dataSources, BeanFactory beans) {
            BffProperties.Jdbc config = properties.getSession().getJdbc();
            return EncryptingBffSessionStore.wrap(
                    new JdbcBffSessionStore(dataSource(config, dataSources, beans), config), properties.getSession());
        }

        private static DataSource dataSource(
                BffProperties.Jdbc config, ObjectProvider<DataSource> dataSources, BeanFactory beans) {
            String name = config.getDataSource();
            if (name != null && !name.isBlank()) {
                if (!beans.containsBean(name.trim())) {
                    throw new IllegalStateException("shiguchi.bff.session.jdbc.data-source is '" + name.trim()
                            + "' but there is no bean of that name");
                }
                return beans.getBean(name.trim(), DataSource.class);
            }
            DataSource only = dataSources.getIfUnique();
            if (only == null) throw new IllegalStateException(NO_DATA_SOURCE);
            return only;
        }
    }
}
