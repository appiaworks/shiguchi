package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import dev.appiaworks.shiguchi.cursus.redis.RedisPushBroker;
import dev.appiaworks.shiguchi.cursus.redis.RedisPushStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Wires the push surface when {@code shiguchi.cursus.enabled=true}: the engine beans (a
 * user-defined {@link PushBroker} or {@link PushStore} replaces the in-memory default), the
 * authorizer bridge, the publisher, and the router serving {@code <path>/subscribe},
 * {@code <path>/publish} and {@code <path>/topics/{topic}}. Lives under {@code dev.appiaworks.shiguchi},
 * which every gateway auto-configuration scans, so the push starter needs no registration of its
 * own.
 *
 * <p>Startup checks: every namespace name is valid; {@code AT_LEAST_ONCE} has a history;
 * {@code session-expiry} is zero (persistent sessions are not available yet); {@code AUTHORIZER}
 * has a user-defined {@code GatewayAuthorizer} (the default scope authorizer only knows routes);
 * a namespace anyone may publish into is logged as a warning.
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.cursus", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PushProperties.class)
public class PushConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PushConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(PushBroker.class)
    @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "broker", havingValue = "MEMORY", matchIfMissing = true)
    public PushBroker pushMemoryBroker() {
        return new MemoryPushBroker();
    }

    @Bean
    @ConditionalOnMissingBean(PushStore.class)
    @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "store", havingValue = "MEMORY", matchIfMissing = true)
    public PushStore pushMemoryStore() {
        return new MemoryPushStore();
    }

    /** Catches {@code broker: REDIS} or {@code store: REDIS} without Spring Data Redis on the classpath. */
    @Bean
    @ConditionalOnMissingClass("org.springframework.data.redis.core.ReactiveStringRedisTemplate")
    @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "broker", havingValue = "REDIS")
    public PushBroker pushMissingRedisBroker() {
        throw new IllegalStateException(MISSING_REDIS);
    }

    @Bean
    @ConditionalOnMissingClass("org.springframework.data.redis.core.ReactiveStringRedisTemplate")
    @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "store", havingValue = "REDIS")
    public PushStore pushMissingRedisStore() {
        throw new IllegalStateException(MISSING_REDIS);
    }

    static final String MISSING_REDIS = "shiguchi.cursus.broker or .store is REDIS but Spring Data Redis is not on"
            + " the classpath: add spring-boot-starter-data-redis-reactive and configure spring.data.redis.*";

    /** Catches {@code broker: POSTGRES} or {@code store: POSTGRES} without R2DBC on the classpath. */
    @Bean
    @ConditionalOnMissingClass("org.springframework.r2dbc.core.DatabaseClient")
    @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "broker", havingValue = "POSTGRES")
    public PushBroker pushMissingPostgresBroker() {
        throw new IllegalStateException(MISSING_POSTGRES);
    }

    @Bean
    @ConditionalOnMissingClass("org.springframework.r2dbc.core.DatabaseClient")
    @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "store", havingValue = "POSTGRES")
    public PushStore pushMissingPostgresStore() {
        throw new IllegalStateException(MISSING_POSTGRES);
    }

    static final String MISSING_POSTGRES = "shiguchi.cursus.broker or .store is POSTGRES but R2DBC is not on the"
            + " classpath: add spring-boot-starter-data-r2dbc and r2dbc-postgresql, and configure spring.r2dbc.url";

    /** Catches {@code store: JDBC} without Spring JDBC on the classpath. */
    @Bean
    @ConditionalOnMissingClass("org.springframework.jdbc.core.JdbcTemplate")
    @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "store", havingValue = "JDBC")
    public PushStore pushMissingJdbcStore() {
        throw new IllegalStateException(MISSING_JDBC);
    }

    static final String MISSING_JDBC =
            "shiguchi.cursus.broker or .store is JDBC but Spring JDBC is not on the classpath:"
                    + " add spring-boot-starter-jdbc and your database driver, and configure spring.datasource.*";

    /** Catches {@code broker: JDBC} without Spring JDBC on the classpath. */
    @Bean
    @ConditionalOnMissingClass("org.springframework.jdbc.core.JdbcTemplate")
    @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "broker", havingValue = "JDBC")
    public PushBroker pushMissingJdbcBroker() {
        throw new IllegalStateException(MISSING_JDBC);
    }

    @Bean
    public PushShutdown pushShutdown() {
        return new PushShutdown();
    }

    /** Delivery counts for recent events, and the delivery log of the namespaces that ask for one. */
    @Bean
    public PushDeliveries pushDeliveries(PushProperties properties, PushStore store) {
        return new PushDeliveries(properties, store);
    }

    /** The cluster-wide connection registry: who is connected where, and who listens to a topic. */
    @Bean(initMethod = "start", destroyMethod = "close")
    public PushRegistry pushRegistry(PushProperties properties, PushStore store, PushConnections connections) {
        return new PushRegistry(properties, store, connections);
    }

    /** Targeted delivery: {@code Send} to a principal, a connection or a device, here or on another instance. */
    @Bean
    public PushSender pushSender(
            PushProperties properties,
            PushConnections connections,
            PushRegistry registry,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            ObjectProvider<PushPeerClient> peers,
            PushSessions sessions) {
        PushSender sender = new PushSender(properties, connections, registry, authorizer, metrics);
        sender.setPeers(peers.getIfAvailable());
        sessions.setDirect(connections, properties.getSend().getNamespace());
        return sender;
    }

    /** The channel to other instances, on when {@code cursus.peer.token} is set. */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${shiguchi.cursus.peer.token:}' != ''")
    public PushPeerClient pushPeerClient(PushProperties properties) {
        return new PushPeerClient(properties.getPeer().getToken());
    }

    /** What other instances call: needs the native gRPC port ({@code shiguchi.grpc.enabled}) and the peer token. */
    @Bean
    @ConditionalOnExpression("'${shiguchi.cursus.peer.token:}' != '' and '${shiguchi.grpc.enabled:false}' == 'true'")
    public PushPeerService pushPeerService(
            PushProperties properties,
            PushConnections connections,
            ObjectProvider<dev.appiaworks.shiguchi.routing.GrpcRouteRegistry> registries) {
        return new PushPeerService(
                connections, registries.getIfAvailable(), properties.getPeer().getToken());
    }

    @Bean
    public PushConnections pushConnections(PushDeliveries deliveries) {
        PushConnections connections = new PushConnections();
        connections.setDeliveries(deliveries);
        return connections;
    }

    /** {@code GET <path>/export}: an archive query as NDJSON, for operators. */
    @Bean
    public PushExportHandler pushExportHandler(PushProperties properties, PushReport report) {
        return new PushExportHandler(properties, report);
    }

    /** The one-call subscription the GraphQL fields (and any transport of your own) are built on. */
    @Bean
    public PushSubscriptions pushSubscriptions(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushShutdown shutdown,
            TopicAuthorizer authorizer,
            PushConnections connections,
            PushMetrics metrics,
            PushUpstreams upstreams,
            PushSessions sessions) {
        return new PushSubscriptions(
                properties, broker, store, shutdown, authorizer, connections, metrics, upstreams, sessions);
    }

    @Bean
    public PushAudit pushAudit() {
        return new PushAudit();
    }

    @Bean
    public PushRepublishJobs pushRepublishJobs() {
        return new PushRepublishJobs();
    }

    @Bean
    public PushReport pushReport(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushMetrics metrics,
            PushConnections connections,
            PushPublisher publisher,
            PushArchive archive,
            PushRepublishJobs jobs,
            PushAudit audit,
            PushUpstreams upstreams,
            PushDeliveries deliveries,
            PushHooks hooks,
            PushSources sources,
            PushDlq dlq,
            PushSessions sessions,
            PushGroups groups,
            PushRegistry registry,
            PushSender sender) {
        PushReport report =
                new PushReport(properties, broker, store, metrics, connections, publisher, archive, jobs, audit);
        report.setSessions(sessions);
        report.setRegistry(registry);
        report.setSender(sender);
        report.setGroups(groups);
        report.setUpstreams(upstreams);
        report.setDeliveries(deliveries);
        report.setHooks(hooks);
        report.setSources(sources);
        report.setDlq(dlq);
        return report;
    }

    /** The {@code gateway-push} actuator endpoint, when Actuator is on the classpath. */
    @Configuration
    // Nested configurations are also found by the gateway's package scan on their own, so each repeats the outer
    // switch.
    @ConditionalOnExpression("${shiguchi.cursus.enabled:false}")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    static class Admin {
        @Bean
        public PushEndpoint pushEndpoint(PushReport report, PushProperties properties) {
            return new PushEndpoint(report, properties);
        }
    }

    /** Present only when Spring Data Redis is on the classpath; the {@code REDIS} engine needs it. */
    @Configuration
    @ConditionalOnClass(ReactiveStringRedisTemplate.class)
    static class Redis {

        @Bean
        @ConditionalOnMissingBean(PushBroker.class)
        @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "broker", havingValue = "REDIS")
        public PushBroker pushRedisBroker(
                PushProperties properties, ObjectProvider<ReactiveStringRedisTemplate> templates) {
            return new RedisPushBroker(
                    template(templates), properties.getRedis().getKeyPrefix());
        }

        @Bean
        @ConditionalOnMissingBean(PushStore.class)
        @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "store", havingValue = "REDIS")
        public PushStore pushRedisStore(
                PushProperties properties, ObjectProvider<ReactiveStringRedisTemplate> templates) {
            return new RedisPushStore(template(templates), properties.getRedis().getKeyPrefix());
        }

        private static ReactiveStringRedisTemplate template(ObjectProvider<ReactiveStringRedisTemplate> templates) {
            ReactiveStringRedisTemplate template = templates.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("shiguchi.cursus.broker or .store is REDIS but there is no"
                        + " ReactiveStringRedisTemplate bean: add spring-boot-starter-data-redis-reactive and"
                        + " configure spring.data.redis.*");
            }
            return template;
        }
    }

    /** Present only when R2DBC and the Postgres driver are on the classpath; the {@code POSTGRES} engine needs them. */
    @Configuration
    @ConditionalOnClass(
            name = {"org.springframework.r2dbc.core.DatabaseClient", "io.r2dbc.postgresql.api.PostgresqlConnection"})
    static class Postgres {

        @Bean
        @ConditionalOnMissingBean(PushStore.class)
        @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "store", havingValue = "POSTGRES")
        public dev.appiaworks.shiguchi.cursus.postgres.PostgresPushStore pushPostgresStore(
                PushProperties properties, ObjectProvider<org.springframework.r2dbc.core.DatabaseClient> clients) {
            return new dev.appiaworks.shiguchi.cursus.postgres.PostgresPushStore(
                    client(clients), properties.getPostgres(), properties.getArchive());
        }

        @Bean
        @ConditionalOnMissingBean(PushBroker.class)
        @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "broker", havingValue = "POSTGRES")
        public PushBroker pushPostgresBroker(
                PushProperties properties,
                ObjectProvider<io.r2dbc.spi.ConnectionFactory> factories,
                ObjectProvider<org.springframework.r2dbc.core.DatabaseClient> clients,
                ObjectProvider<dev.appiaworks.shiguchi.cursus.postgres.PostgresPushStore> stores) {
            io.r2dbc.spi.ConnectionFactory factory = factories.getIfAvailable();
            if (factory == null) throw new IllegalStateException(NO_R2DBC);
            return new dev.appiaworks.shiguchi.cursus.postgres.PostgresPushBroker(
                    factory, client(clients), properties.getPostgres(), stores::getIfAvailable);
        }

        private static org.springframework.r2dbc.core.DatabaseClient client(
                ObjectProvider<org.springframework.r2dbc.core.DatabaseClient> clients) {
            org.springframework.r2dbc.core.DatabaseClient client = clients.getIfAvailable();
            if (client == null) throw new IllegalStateException(NO_R2DBC);
            return client;
        }

        static final String NO_R2DBC = "shiguchi.cursus.broker or .store is POSTGRES but there is no R2DBC"
                + " connection: add spring-boot-starter-data-r2dbc and r2dbc-postgresql, and configure spring.r2dbc.url";
    }

    /** Present only when Spring JDBC is on the classpath; the {@code JDBC} engine needs it. */
    @Configuration
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    static class Jdbc {

        @Bean
        @ConditionalOnMissingBean(PushStore.class)
        @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "store", havingValue = "JDBC")
        public dev.appiaworks.shiguchi.cursus.jdbc.JdbcPushStore pushJdbcStore(
                PushProperties properties,
                ObjectProvider<javax.sql.DataSource> dataSources,
                org.springframework.beans.factory.BeanFactory beans) {
            return new dev.appiaworks.shiguchi.cursus.jdbc.JdbcPushStore(
                    dataSource(properties.getJdbc(), dataSources, beans),
                    properties.getJdbc(),
                    properties.getArchive());
        }

        /**
         * Polls the table the JDBC store writes, so the store must be the JDBC one; the store bean
         * is requested here so its table exists before the first poll.
         */
        @Bean
        @ConditionalOnMissingBean(PushBroker.class)
        @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "broker", havingValue = "JDBC")
        public PushBroker pushJdbcBroker(
                PushProperties properties,
                ObjectProvider<javax.sql.DataSource> dataSources,
                org.springframework.beans.factory.BeanFactory beans,
                ObjectProvider<dev.appiaworks.shiguchi.cursus.jdbc.JdbcPushStore> stores,
                ObjectProvider<MeterRegistry> registries) {
            if (stores.getIfAvailable() == null && properties.getStore() != PushProperties.Engine.JDBC) {
                throw new IllegalStateException(JDBC_BROKER_NEEDS_STORE);
            }
            return new dev.appiaworks.shiguchi.cursus.jdbc.JdbcPushBroker(
                    dataSource(properties.getJdbc(), dataSources, beans),
                    properties.getJdbc(),
                    registries.getIfAvailable());
        }

        static final String JDBC_BROKER_NEEDS_STORE = "shiguchi.cursus.broker is JDBC, which polls the table of the"
                + " JDBC store: set shiguchi.cursus.store to JDBC too";

        private static javax.sql.DataSource dataSource(
                PushProperties.Jdbc config,
                ObjectProvider<javax.sql.DataSource> dataSources,
                org.springframework.beans.factory.BeanFactory beans) {
            String name = config.getDataSource();
            if (name != null && !name.isBlank()) {
                if (!beans.containsBean(name.trim())) {
                    throw new IllegalStateException("shiguchi.cursus.jdbc.data-source is '" + name.trim()
                            + "' but there is no bean of that name");
                }
                return beans.getBean(name.trim(), javax.sql.DataSource.class);
            }
            javax.sql.DataSource only = dataSources.getIfUnique();
            if (only == null) throw new IllegalStateException(NO_DATA_SOURCE);
            return only;
        }

        static final String NO_DATA_SOURCE = "shiguchi.cursus.broker or .store is JDBC but there is not exactly one"
                + " DataSource bean: configure spring.datasource.*, or name one with shiguchi.cursus.jdbc.data-source";
    }

    @Bean
    public PushMetrics pushMetrics(ObjectProvider<MeterRegistry> registries) {
        MeterRegistry registry = registries.getIfAvailable();
        return new PushMetrics(registry != null ? registry : new SimpleMeterRegistry());
    }

    /**
     * Topics backed by upstream streams, read from the served methods' options once the routes
     * are loaded. Built before the authorizer, whose creation validates the namespaces: the
     * namespaces an upstream pattern declares must exist by then.
     */
    @Bean
    @org.springframework.context.annotation.DependsOn({
        "dynamicRouteLoader",
        "staticRouteLoader",
        "restDownstreamRouteLoader"
    })
    public PushUpstreams pushUpstreams(
            PushProperties properties,
            dev.appiaworks.shiguchi.routing.GrpcRouteRegistry registry,
            PushStore store,
            PushPublisher publisher,
            PushMetrics metrics,
            dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher dispatcher) {
        return new PushUpstreams(properties, registry, store, publisher, metrics, PushUpstreams.opener(dispatcher));
    }

    /** The persistent sessions: cursors per session and topic in the store. */
    @Bean
    public PushSessions pushSessions(PushProperties properties, PushStore store, PushMetrics metrics) {
        return new PushSessions(properties, store, metrics);
    }

    /** The consumer groups: competing members per {@code session-key: GROUP} namespace. */
    @Bean
    public PushGroups pushGroups(
            PushProperties properties, PushStore store, PushMetrics metrics, PushDlq dlq, PushSessions sessions) {
        PushGroups groups = new PushGroups(properties, store, metrics, dlq);
        sessions.setGroups(groups);
        return groups;
    }

    /** {@code POST <path>/ack}: the acknowledgement over HTTP. */
    @Bean
    public PushAckHandler pushAckHandler(PushSessions sessions, TopicAuthorizer authorizer) {
        return new PushAckHandler(sessions, authorizer);
    }

    /** The dead-letter queue of the sources. */
    @Bean
    public PushDlq pushDlq(PushProperties properties, PushMetrics metrics) {
        PushDlq dlq = new PushDlq(properties.getDlq().getMaxEntries());
        metrics.registerDlq(dlq);
        return dlq;
    }

    /**
     * The hooks: every {@code PushHook} bean plus, with {@code hooks.grpc-service}, the served
     * service implementing the PushHooks contract. After the route loaders so that service is known.
     */
    @Bean
    @org.springframework.context.annotation.DependsOn({
        "dynamicRouteLoader",
        "staticRouteLoader",
        "restDownstreamRouteLoader"
    })
    public PushHooks pushHooks(
            PushProperties properties,
            ObjectProvider<PushHook> hookBeans,
            PushMetrics metrics,
            PushUpstreams upstreams,
            PushConnections connections,
            dev.appiaworks.shiguchi.routing.GrpcRouteRegistry registry,
            dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher dispatcher) {
        java.util.List<PushHook> hooks = new java.util.ArrayList<>();
        String service = properties.getHooks().getGrpcService();
        if (service != null && !service.isBlank()) {
            hooks.add(new GrpcPushHook(registry, service, GrpcPushHook.caller(dispatcher)));
        }
        hookBeans.orderedStream().forEach(hooks::add);
        PushHooks all = new PushHooks(properties, hooks, metrics, upstreams.holder());
        upstreams.setHooks(all);
        connections.setHooks(all);
        return all;
    }

    /**
     * The sources: {@code shiguchi.cursus.sources} (Kafka, gRPC streams) and every
     * {@code PushSource} bean, run under a lease each. After the route loaders so a gRPC stream's
     * service and a Kafka source's payload type resolve; declares the templates' namespaces.
     */
    @Bean
    @org.springframework.context.annotation.DependsOn({
        "dynamicRouteLoader",
        "staticRouteLoader",
        "restDownstreamRouteLoader"
    })
    public PushSources pushSources(
            PushProperties properties,
            ObjectProvider<PushSource> sourceBeans,
            PushStore store,
            PushPublisher publisher,
            PushMetrics metrics,
            PushDlq dlq,
            PushUpstreams upstreams,
            dev.appiaworks.shiguchi.routing.GrpcRouteRegistry registry,
            dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher dispatcher) {
        java.util.List<PushSource> sources = new java.util.ArrayList<>();
        int i = 0;
        for (PushProperties.Source cfg : properties.getSources()) {
            String name = cfg.getName() == null || cfg.getName().isBlank()
                    ? cfg.getType().name().toLowerCase(java.util.Locale.ROOT) + "-" + i
                    : cfg.getName().trim();
            i++;
            switch (cfg.getType()) {
                case KAFKA -> {
                    if (!org.springframework.util.ClassUtils.isPresent(
                            "org.apache.kafka.clients.consumer.KafkaConsumer",
                            getClass().getClassLoader())) {
                        throw new IllegalStateException("shiguchi.cursus.sources[" + name
                                + "]: type KAFKA needs org.apache.kafka:kafka-clients on the classpath");
                    }
                    com.google.protobuf.Descriptors.Descriptor type = null;
                    com.google.protobuf.util.JsonFormat.TypeRegistry types = null;
                    String typeName = cfg.getPayloadType();
                    if (typeName != null && !typeName.isBlank()) {
                        for (dev.appiaworks.shiguchi.routing.ServiceEndpointContext ctx : registry.allContexts()) {
                            for (com.google.protobuf.Descriptors.FileDescriptor fd :
                                    ctx.getFileDescriptors().values()) {
                                com.google.protobuf.Descriptors.Descriptor d = fd.findMessageTypeByName(typeName.trim()
                                        .substring(typeName.trim().lastIndexOf('.') + 1));
                                if (d != null && d.getFullName().equals(typeName.trim())) {
                                    type = d;
                                    types = ctx.getTypeRegistry();
                                }
                            }
                        }
                        if (type == null) {
                            throw new IllegalStateException("shiguchi.cursus.sources[" + name + "]: payload-type '"
                                    + typeName + "' is not a message of any served service");
                        }
                    }
                    sources.add(new dev.appiaworks.shiguchi.cursus.kafka.KafkaPushSource(name, cfg, type, types, dlq));
                    declare(
                            properties,
                            name,
                            TopicTemplate.parseTarget(cfg.getTopicTemplate()).namespace(),
                            typeName);
                }
                case GRPC_STREAM -> {
                    GrpcStreamPushSource s =
                            new GrpcStreamPushSource(name, cfg, registry, GrpcStreamPushSource.opener(dispatcher), dlq);
                    sources.add(s);
                    declare(properties, name, s.template().namespace(), s.payloadType());
                }
            }
        }
        sourceBeans.orderedStream().forEach(sources::add);
        return new PushSources(properties, store, publisher, metrics, dlq, sources, upstreams.holder());
    }

    /** The namespace a source targets exists (declared when it was not) and carries the source's payload type. */
    private static void declare(PushProperties properties, String source, String namespace, String payloadType) {
        PushProperties.Namespace ns = properties.getNamespaces().get(namespace);
        if (ns == null) {
            ns = new PushProperties.Namespace();
            properties.getNamespaces().put(namespace, ns);
            log.info("Push: namespace '{}' declared by source {} with default settings", namespace, source);
        }
        if (payloadType != null
                && !payloadType.isBlank()
                && (ns.getPayloadType() == null || ns.getPayloadType().isBlank())) {
            ns.setPayloadType(payloadType.trim());
        }
    }

    @Bean
    public TopicAuthorizer pushTopicAuthorizer(
            PushProperties properties,
            ObjectProvider<GatewayAuthorizer> authorizers,
            PushUpstreams upstreams,
            PushSources sources) {
        GatewayAuthorizer authorizer = authorizers.getIfAvailable();
        GatewayAuthorizer custom = authorizer instanceof ScopeGatewayAuthorizer ? null : authorizer;
        validate(properties, custom != null);
        return new TopicAuthorizer(custom);
    }

    @Bean
    public PushPublisher pushPublisher(
            PushProperties properties, PushStore store, PushBroker broker, PushMetrics metrics, PushArchive archive) {
        return new PushPublisher(properties, store, broker, metrics, archive);
    }

    /**
     * The archive behind the admin message search, when {@code archive.retention} is above zero:
     * the store itself when it is one ({@code POSTGRES}), a per-instance ring for {@code MEMORY},
     * Redis Streams shared by every instance for {@code REDIS}; any other store needs a {@code PushArchive} bean of your own. {@link PushArchive#none()}
     * when the retention is zero.
     */
    @Bean
    @ConditionalOnMissingBean(PushArchive.class)
    public PushArchive pushArchive(PushProperties properties, PushStore store) {
        PushProperties.Archive archive = properties.getArchive();
        java.time.Duration retention = archive.getRetention();
        if (retention == null || retention.isZero() || retention.isNegative()) return PushArchive.none();
        if (store instanceof PushArchive own) return own;
        if (store instanceof MemoryPushStore) return new MemoryPushArchive(archive.getMemoryMaxEvents(), retention);
        if (store instanceof RedisPushStore redis) return redis.archive(retention, archive.getMaxScan());
        throw new IllegalStateException("shiguchi.cursus.archive.retention is " + retention + " but the "
                + properties.getStore()
                + " store keeps no archive: use store POSTGRES, JDBC, REDIS or MEMORY, or define a"
                + " PushArchive bean");
    }

    /**
     * Binds cookie connections to their BFF session. Always present; without a
     * {@link dev.appiaworks.shiguchi.security.CookieSessions} bean it binds nothing.
     */
    @Bean
    public PushCookieSessions pushCookieSessions(
            PushProperties properties,
            PushMetrics metrics,
            ObjectProvider<dev.appiaworks.shiguchi.security.CookieSessions> sessions) {
        return new PushCookieSessions(sessions.getIfAvailable(), properties.getSessionCheckInterval(), metrics);
    }

    @Bean
    public PushSubscribeHandler pushSubscribeHandler(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            PushShutdown shutdown,
            PushConnections connections,
            PushUpstreams upstreams,
            PushSessions sessions,
            PushCookieSessions cookies) {
        PushSubscribeHandler handler = new PushSubscribeHandler(
                properties, broker, store, authorizer, metrics, shutdown, connections, upstreams, sessions);
        handler.setCookieSessions(cookies);
        return handler;
    }

    @Bean
    public PushPublishHandler pushPublishHandler(
            PushProperties properties, PushPublisher publisher, TopicAuthorizer authorizer) {
        return new PushPublishHandler(properties, publisher, authorizer);
    }

    @Bean
    public PushTokenAuthenticator pushPushTokenAuthenticator(
            ObjectProvider<org.springframework.security.oauth2.jwt.ReactiveJwtDecoder> decoders,
            ObjectProvider<
                            org.springframework.security.oauth2.server.resource.authentication
                                    .ReactiveJwtAuthenticationConverter>
                    reactiveConverters,
            ObjectProvider<
                            org.springframework.core.convert.converter.Converter<
                                    org.springframework.security.oauth2.jwt.Jwt,
                                    ? extends
                                            reactor.core.publisher.Mono<
                                                    ? extends
                                                            org.springframework.security.authentication
                                                                    .AbstractAuthenticationToken>>>
                    converters) {
        // The HTTP chain resolves the same converter, so both name the caller the same way.
        return new PushTokenAuthenticator(
                decoders.getIfAvailable(),
                dev.appiaworks.shiguchi.security.JwtPrincipalConverter.resolve(reactiveConverters, converters));
    }

    /** The WebSocket transport, mounted at {@code <path>/ws} when {@code cursus.ws.enabled}. */
    @Configuration
    // Nested configurations are also found by the gateway's package scan on their own, so each repeats the outer
    // switch.
    @ConditionalOnExpression("${shiguchi.cursus.enabled:false}")
    @ConditionalOnProperty(prefix = "shiguchi.cursus.ws", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class WebSocket {

        @Bean
        public PushWebSocketHandler pushWebSocketHandler(
                PushProperties properties,
                PushBroker broker,
                PushStore store,
                PushPublisher publisher,
                TopicAuthorizer authorizer,
                PushMetrics metrics,
                PushShutdown shutdown,
                PushTokenAuthenticator tokens,
                PushConnections connections,
                PushUpstreams upstreams,
                PushSessions sessions,
                PushCookieSessions cookies) {
            PushWebSocketHandler handler = new PushWebSocketHandler(
                    properties,
                    broker,
                    store,
                    publisher,
                    authorizer,
                    metrics,
                    shutdown,
                    tokens,
                    connections,
                    upstreams,
                    sessions);
            handler.setCookieSessions(cookies);
            return handler;
        }

        @Bean
        public org.springframework.web.reactive.HandlerMapping pushWebSocketMapping(
                PushProperties properties, PushWebSocketHandler handler) {
            var mapping = new org.springframework.web.reactive.handler.SimpleUrlHandlerMapping();
            mapping.setUrlMap(Map.of(normalizePath(properties.getPath()) + "/ws", handler));
            // Before the RouterFunctionMapping (-1) that serves the other push endpoints.
            mapping.setOrder(-2);
            return mapping;
        }

        /**
         * WebFlux needs one of these to dispatch to a {@code WebSocketHandler}; Boot defines none,
         * Spring GraphQL's WebSocket support defines its own, which then serves this handler too.
         */
        @Bean
        @ConditionalOnMissingBean(org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter.class)
        public org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
                pushWebSocketHandlerAdapter() {
            return new org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter();
        }
    }

    /**
     * The hosted {@code shiguchi.cursus.v1.PushService}, when {@code cursus.grpc.enabled} (the
     * default): declared to the gateway as a STATIC service over an in-process channel, so the
     * REST transcoding, gRPC-Web and the native gRPC port all reach it.
     */
    @Configuration
    // Nested configurations are also found by the gateway's package scan on their own, so each repeats the outer
    // switch.
    @ConditionalOnExpression("${shiguchi.cursus.enabled:false}")
    @ConditionalOnProperty(
            prefix = "shiguchi.cursus.grpc",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class Grpc {

        /** Static: a {@code BeanPostProcessor} must exist before the beans it watches. */
        @Bean
        public static PushServiceRegistration pushServiceRegistration(
                org.springframework.core.env.Environment environment) {
            return new PushServiceRegistration(environment);
        }

        @Bean
        public PushGrpcService pushGrpcService(
                PushProperties properties,
                PushBroker broker,
                PushStore store,
                PushPublisher publisher,
                TopicAuthorizer authorizer,
                PushMetrics metrics,
                PushShutdown shutdown,
                PushTokenAuthenticator tokens,
                PushConnections connections,
                ObjectProvider<dev.appiaworks.shiguchi.routing.GrpcRouteRegistry> registries,
                PushUpstreams upstreams,
                PushSessions sessions,
                PushRegistry registry,
                PushSender sender) {
            PushGrpcService service = new PushGrpcService(
                    properties,
                    broker,
                    store,
                    publisher,
                    authorizer,
                    metrics,
                    shutdown,
                    tokens,
                    connections,
                    registries.getIfAvailable(),
                    upstreams,
                    sessions);
            service.setRegistry(registry);
            service.setSender(sender);
            return service;
        }

        @Bean
        public PushGrpcServer pushGrpcServer(PushServiceRegistration registration, PushGrpcService service) {
            return new PushGrpcServer(registration, service);
        }
    }

    @Bean
    public RouterFunction<ServerResponse> pushRouterFunction(
            PushProperties properties,
            PushSubscribeHandler subscribe,
            PushPublishHandler publish,
            PushExportHandler export,
            PushAckHandler ack) {
        String path = normalizePath(properties.getPath());
        RouterFunctions.Builder routes = RouterFunctions.route()
                .GET(path + "/subscribe", subscribe::subscribe)
                .GET(path + "/export", export::export)
                .POST(path + "/ack", ack::ack)
                .POST(path + "/topics/{topic}", publish::clientPublish);
        if (properties.getPublish().isHttpEnabled()) {
            routes.POST(path + "/publish", publish::publish);
        }
        log.info(
                "Push on at {}: {} namespace(s) {}, broker {}, store {}, publish over HTTP {}, WebSocket {}, PushService {}",
                path,
                properties.getNamespaces().size(),
                properties.getNamespaces().keySet(),
                properties.getBroker(),
                properties.getStore(),
                properties.getPublish().isHttpEnabled() ? "on" : "off",
                properties.getWs().isEnabled() ? "on" : "off",
                properties.getGrpc().isEnabled() ? "on" : "off");
        return routes.build();
    }

    static String normalizePath(String path) {
        String p = path == null || path.isBlank() ? "/push" : path.trim();
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    static void validate(PushProperties properties, boolean customAuthorizer) {
        List<String> problems = new ArrayList<>();
        List<String> open = new ArrayList<>();
        if (properties.getSessionCheckInterval() == null
                || properties.getSessionCheckInterval().compareTo(java.time.Duration.ofSeconds(5)) < 0) {
            problems.add("session-check-interval must be at least 5s");
        }
        if (properties.getNamespaces().isEmpty()) {
            problems.add("no namespaces declared under shiguchi.cursus.namespaces");
        }
        for (Map.Entry<String, PushProperties.Namespace> e :
                properties.getNamespaces().entrySet()) {
            String name = e.getKey();
            PushProperties.Namespace ns = e.getValue();
            try {
                new Topic(name, "x");
            } catch (IllegalArgumentException ex) {
                problems.add("namespace '" + name + "': " + ex.getMessage());
                continue;
            }
            if (ns.getQos() == Qos.AT_LEAST_ONCE && ns.getHistorySize() <= 0) {
                problems.add("namespace '" + name + "': qos AT_LEAST_ONCE needs history-size above 0");
            }
            if (ns.getSessionExpiry() != null && ns.getSessionExpiry().isNegative()) {
                problems.add("namespace '" + name + "': session-expiry cannot be negative");
            }
            if (PushGroups.grouped(ns)) {
                if (ns.getQos() != Qos.AT_LEAST_ONCE) {
                    problems.add("namespace '" + name + "': session-key GROUP needs qos AT_LEAST_ONCE");
                }
                if (ns.getSessionExpiry() == null
                        || ns.getSessionExpiry().isZero()
                        || ns.getSessionExpiry().isNegative()) {
                    problems.add("namespace '" + name
                            + "': session-key GROUP needs session-expiry above 0 (how long an idle group is kept)");
                }
                if (ns.getAckDeadline() == null
                        || ns.getAckDeadline().isZero()
                        || ns.getAckDeadline().isNegative()) {
                    problems.add("namespace '" + name + "': ack-deadline must be above 0");
                }
                if (ns.getMaxDeliveries() < 1)
                    problems.add("namespace '" + name + "': max-deliveries must be at least 1");
                if (ns.getMaxInflight() < 1) problems.add("namespace '" + name + "': max-inflight must be at least 1");
            }
            if (ns.getOrdering() == null) {
                problems.add("namespace '" + name + "': ordering cannot be null");
            } else if (ns.getOrdering() != PushProperties.Ordering.NONE && !PushGroups.grouped(ns)) {
                problems.add("namespace '" + name + "': ordering " + ns.getOrdering() + " needs session-key GROUP");
            }
            if (PushSessions.persistent(ns)) {
                if (ns.getQos() != Qos.AT_LEAST_ONCE) {
                    problems.add("namespace '" + name + "': session-expiry above 0 needs qos AT_LEAST_ONCE");
                }
                if (ns.getSessionKey() == PushProperties.SessionKey.PRINCIPAL
                        && ns.getAuth() == PushProperties.SubscribeAuth.NONE) {
                    problems.add(
                            "namespace '" + name
                                    + "': session-key PRINCIPAL needs an authenticated subscriber (auth is NONE); use session-key CLIENT for anonymous sessions");
                }
                if (ns.getMaxQueued() < 0) {
                    problems.add("namespace '" + name + "': max-queued cannot be negative");
                }
                if (ns.getMaxQueued() > ns.getHistorySize()) {
                    problems.add("namespace '" + name + "': max-queued (" + ns.getMaxQueued()
                            + ") cannot exceed history-size (" + ns.getHistorySize() + "): the history is the queue");
                }
            }
            if (ns.getClientQueueSize() <= 0) {
                problems.add("namespace '" + name + "': client-queue-size must be above 0");
            }
            if (ns.getAuth() == PushProperties.SubscribeAuth.ROLE
                    && ns.getRoles().isEmpty()) {
                problems.add("namespace '" + name + "': auth ROLE needs roles");
            }
            if (ns.getPrincipalClaim() != null
                    && !ns.getPrincipalClaim().isBlank()
                    && ns.getAuth() != PushProperties.SubscribeAuth.PRINCIPAL_MATCH) {
                problems.add("namespace '" + name + "': principal-claim needs auth PRINCIPAL_MATCH (auth is "
                        + ns.getAuth() + ")");
            }
            if (ns.getPublishAuth() == PushProperties.PublishAuth.ROLE
                    && ns.getPublishRoles().isEmpty()) {
                problems.add("namespace '" + name + "': publish-auth ROLE needs publish-roles");
            }
            boolean needsAuthorizer = ns.getAuth() == PushProperties.SubscribeAuth.AUTHORIZER
                    || ns.getPublishAuth() == PushProperties.PublishAuth.AUTHORIZER;
            if (needsAuthorizer && !customAuthorizer) {
                problems.add("namespace '" + name + "': AUTHORIZER needs a GatewayAuthorizer bean of your own"
                        + " (the default scope authorizer only knows routes)");
            }
            if (ns.getPublishAuth() == PushProperties.PublishAuth.NONE) open.add(name);
            String payloadType = ns.getPayloadType();
            if (payloadType != null
                    && !payloadType.isBlank()
                    && !payloadType.trim().matches("[A-Za-z_][\\w.]*")) {
                problems.add("namespace '" + name + "': payload-type must be a protobuf message's full name, got '"
                        + payloadType + "'");
            }
            String field = ns.graphqlFieldOr(name);
            if (!field.matches("[_A-Za-z][_0-9A-Za-z]*")) {
                problems.add("namespace '" + name + "': graphql-field '" + field + "' is not a GraphQL name");
            }
        }
        if (properties.getConnection().getMaxMessageBytes() <= 0) {
            problems.add("connection.max-message-bytes must be above 0");
        }
        PushProperties.Republish republish = properties.getAdmin().getRepublish();
        if (republish.getMaxBatch() <= 0) problems.add("admin.republish.max-batch must be above 0");
        if (republish.getRatePerSecond() <= 0) problems.add("admin.republish.rate-per-second must be above 0");
        for (String ns : republish.getNamespaces()) {
            if (!properties.getNamespaces().containsKey(ns)) {
                problems.add("admin.republish.namespaces names '" + ns + "', which is not declared");
            }
        }
        if (properties.getArchive().getMemoryMaxEvents() <= 0) {
            problems.add("archive.memory-max-events must be above 0");
        }
        if (properties.getArchive().getMaxScan() <= 0) {
            problems.add("archive.max-scan must be above 0");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("shiguchi.cursus: " + String.join("; ", problems));
        }
        if (!open.isEmpty()) {
            log.warn(
                    "Push namespaces {} accept events from anyone (publish-auth: NONE); set publish-auth"
                            + " or front the gateway with something that authenticates publishers",
                    open);
        }
    }
}
