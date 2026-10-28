package dev.appiaworks.shiguchi.cursus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything under {@code shiguchi.cursus}: the server-to-client messaging surface. Off unless
 * {@code enabled: true}. Topics are {@code namespace:name}; every namespace a client or publisher
 * may use must be declared under {@code namespaces}, and the namespace carries the policy.
 *
 * <pre>
 * shiguchi:
 *   push:
 *     enabled: true
 *     namespaces:
 *       ticker: { qos: AT_MOST_ONCE, auth: NONE, publish-auth: NONE }
 *       orders: { qos: AT_LEAST_ONCE, history-size: 500, history-ttl: 1h, auth: AUTHENTICATED }
 *       user:   { auth: PRINCIPAL_MATCH }
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "shiguchi.cursus")
public class PushProperties {

    /** Mount the push endpoints. */
    private boolean enabled = false;

    /** Base path of the endpoints: {@code <path>/subscribe}, {@code <path>/publish}, {@code <path>/topics/{topic}}. */
    private String path = "/push";

    /**
     * Live fan-out between subscribers. {@code MEMORY}: this instance only. {@code REDIS}: Pub/Sub
     * through Boot's {@code ReactiveStringRedisTemplate}, so every instance sees every event.
     */
    private Engine broker = Engine.MEMORY;

    /**
     * Event ids and history. {@code MEMORY}: this instance only, lost on restart. {@code REDIS}: a
     * Stream per topic, shared by every instance and kept across restarts.
     */
    private Engine store = Engine.MEMORY;

    private Redis redis = new Redis();

    private Postgres postgres = new Postgres();

    private Jdbc jdbc = new Jdbc();

    /** Policy per namespace, keyed by namespace name. */
    private Map<String, Namespace> namespaces = new LinkedHashMap<>();

    private Connection connection = new Connection();

    /**
     * How often a connection opened with the BFF session cookie checks that its session is still
     * active, which is the longest a socket outlives a logout on another instance; at least 5 s.
     */
    private Duration sessionCheckInterval = Duration.ofSeconds(30);

    private Ws ws = new Ws();

    private Admin admin = new Admin();

    private Archive archive = new Archive();

    private Upstream upstream = new Upstream();

    private Hooks hooks = new Hooks();

    /** External records turned into events: Kafka topics, backend streams. */
    private List<Source> sources = new ArrayList<>();

    private Dlq dlq = new Dlq();

    private Publish publish = new Publish();

    private Grpc grpc = new Grpc();

    private Peer peer = new Peer();

    private Send send = new Send();

    /**
     * This instance's name in leases and in the connection registry. Empty: the host name plus a
     * random suffix. Replaces {@code upstream.instance-id}, which still applies when this is empty.
     */
    private String instanceId = "";

    /** The name this instance goes by: {@code instance-id}, else {@code upstream.instance-id}, else generated once. */
    public String resolvedInstanceId() {
        String id = instanceId == null ? "" : instanceId.trim();
        if (id.isEmpty())
            id = upstream.getInstanceId() == null
                    ? ""
                    : upstream.getInstanceId().trim();
        if (id.isEmpty()) {
            synchronized (this) {
                if (generatedInstanceId == null) generatedInstanceId = PushUpstreams.defaultHolder();
                id = generatedInstanceId;
            }
        }
        return id;
    }

    private transient String generatedInstanceId;

    public enum Engine {
        MEMORY,
        REDIS,
        POSTGRES,
        JDBC
    }

    /** How a consumer group orders what it hands out. */
    public enum Ordering {
        /** No order across or within members: the default, the fastest. */
        NONE,
        /** Events sharing a {@code key} are never in flight at two members and go out in id order. */
        KEY,
        /** One active member per group and topic; the others idle and take over when it leaves. */
        STRICT
    }

    /** What names a persistent session. */
    public enum SessionKey {
        PRINCIPAL,
        CLIENT,
        /** Competing consumers: members name a {@code group}, each event goes to one of them, acks required. */
        GROUP
    }

    /** Who may subscribe to a namespace's topics. */
    public enum SubscribeAuth {
        /** Anyone, including anonymous callers. */
        NONE,
        /** Any authenticated principal. */
        AUTHENTICATED,
        /** An authenticated principal holding one of {@code roles}. */
        ROLE,
        /**
         * The topic name is the principal's name, or starts with it followed by {@code /}: only
         * {@code alice} may subscribe to {@code user:alice} and {@code user:alice/inbox}.
         */
        PRINCIPAL_MATCH,
        /** A JWT whose {@code claim} lists a pattern matching the topic. */
        CLAIM,
        /** The {@code GatewayAuthorizer} bean decides. */
        AUTHORIZER
    }

    /** Who may publish into a namespace. */
    public enum PublishAuth {
        /** Anyone. Logged as a warning at startup. */
        NONE,
        /** Any authenticated principal. */
        AUTHENTICATED,
        /** An authenticated principal holding one of {@code publish-roles}. */
        ROLE,
        /** A JWT whose {@code publish-claim} lists a pattern matching the topic. */
        CLAIM,
        /** The {@code GatewayAuthorizer} bean decides. */
        AUTHORIZER
    }

    @Data
    public static class Namespace {
        /** Delivery guarantee. {@code AT_LEAST_ONCE} needs {@code history-size} above zero. */
        private Qos qos = Qos.AT_MOST_ONCE;

        /** Events kept per topic for resume; 0 keeps none. */
        private int historySize = 0;

        /** How long a kept event stays replayable. */
        private Duration historyTtl = Duration.ofHours(1);

        /**
         * How long the server remembers a subscriber that is gone. Zero: a transient session that
         * resumes from the history window only. Above zero: a persistent session (needs
         * {@code AT_LEAST_ONCE}, a history, and a store that keeps sessions): the subscriber's
         * cursor is kept per topic and it resumes from there, acknowledging as it goes.
         */
        private Duration sessionExpiry = Duration.ZERO;

        /**
         * What names a persistent session: the principal ({@code PRINCIPAL}, needs an authenticated
         * subscriber), or a client-chosen {@code session} id scoped to the principal ({@code CLIENT}:
         * SSE {@code ?session=}, WebSocket {@code connect{session}}, gRPC {@code SubscribeRequest.session}).
         */
        private SessionKey sessionKey = SessionKey.PRINCIPAL;

        /**
         * Most events a persistent session may have pending when it resumes; beyond it the oldest
         * are dropped and the subscriber told. Zero: {@code history-size}, which bounds it anyway.
         */
        private int maxQueued = 0;

        /** Consumer groups: how long a member may hold an event unacknowledged before another member gets it. */
        private Duration ackDeadline = Duration.ofSeconds(30);

        /** Consumer groups: deliveries of one event before it goes to the dead-letter queue. */
        private int maxDeliveries = 5;

        /** Consumer groups: most events one member holds unacknowledged at a time. */
        private int maxInflight = 64;

        /**
         * Index who listens to each topic of this namespace in the store, so {@code GetSubscribers}
         * lists members across every instance. Off: only a count of this instance's subscribers.
         */
        private boolean presence = false;

        /** Consumer groups: {@code NONE}, {@code KEY} (per-key order, sticky member) or {@code STRICT} (one active member). */
        private Ordering ordering = Ordering.NONE;

        /** Events queued for one connection that is not reading; the oldest is dropped beyond it ({@code AT_MOST_ONCE}). */
        private int clientQueueSize = 256;

        private SubscribeAuth auth = SubscribeAuth.NONE;

        /** Authorities accepted under {@code auth: ROLE}, with or without the {@code ROLE_} / {@code SCOPE_} prefix. */
        private List<String> roles = new ArrayList<>();

        /** JWT claim (dotted path) holding topic patterns, for {@code auth: CLAIM}. */
        private String claim = "push.subscribe";

        /**
         * For {@code auth: PRINCIPAL_MATCH}: the JWT claim (dotted path) whose string value the
         * topic name must match, instead of the principal's name. Only the topic check moves:
         * session keys and the {@code from} attribute keep the principal's name. Unset: the name.
         */
        private String principalClaim;

        private PublishAuth publishAuth = PublishAuth.AUTHENTICATED;

        private List<String> publishRoles = new ArrayList<>();

        private String publishClaim = "push.publish";

        /**
         * Let a subscriber publish through {@code POST <path>/topics/{topic}}; the caller must pass
         * the namespace's subscribe check. Off by default.
         */
        private boolean clientPublish = false;

        /**
         * The protobuf message every event on this namespace carries, as its full name
         * ({@code acme.OrderEvent}), from a service the gateway serves. It gives the namespace's
         * GraphQL subscription field a typed {@code payload} (field selection works on it) instead
         * of {@code JSON}; events published as JSON are parsed into it, typed ones used as sent.
         * Empty: untyped.
         */
        private String payloadType = "";

        /** Give this namespace a GraphQL subscription field when the GraphQL surface is on. */
        private boolean graphql = true;

        /**
         * Record every delivery (and drop) of every event to every connection in the store, so
         * {@code GET .../messages/{id}/deliveries} answers per subscriber across the cluster. One
         * row per event and subscriber: for a topic with many subscribers, that is many rows.
         * Needs a store with a delivery log ({@code MEMORY}, {@code POSTGRES}).
         */
        private boolean deliveryLog = false;

        /**
         * The name of that field under {@code Subscription}. Empty: the namespace's name followed
         * by {@code Events} ({@code orders} gives {@code ordersEvents}).
         */
        private String graphqlField = "";

        /** The subscription field's name: {@code graphql-field}, or the default built from the namespace's name. */
        public String graphqlFieldOr(String namespace) {
            return graphqlField == null || graphqlField.isBlank() ? namespace + "Events" : graphqlField.trim();
        }
    }

    @Data
    public static class Connection {
        /** A connection is closed after this long (plus up to {@code lifetime-jitter}) so reconnects spread; 0 disables. */
        private Duration maxLifetime = Duration.ofMinutes(30);

        private Duration lifetimeJitter = Duration.ofMinutes(3);

        /** An SSE comment after this long without an event, so idle-timeout proxies keep the connection; 0 disables. */
        private Duration keepalive = Duration.ofSeconds(15);

        /** Topics one connection may subscribe to. */
        private int maxSubscriptions = 255;

        /** Largest accepted payload, in bytes. */
        private int maxMessageBytes = 65_536;
    }

    /** How instances reach each other. */
    @Data
    public static class Peer {
        /** This instance's own gRPC {@code host:port} as its peers reach it; empty: not reachable by peers. */
        private String address = "";

        /**
         * The shared secret instances present to each other (the {@code x-push-peer-token} metadata)
         * on the peer channel. Empty: the peer channel is off, and a targeted {@code Send} reaches
         * this instance's own connections only.
         */
        private String token = "";
    }

    /** Targeted delivery: {@code Send} to a principal, a connection or a device. */
    @Data
    public static class Send {
        /**
         * The namespace whose {@code publish-auth} rules decide who may {@code Send} (a targeted
         * message has no topic of its own). It must be declared under {@code namespaces}; without
         * it {@code Send} is refused.
         */
        private String namespace = "_direct";

        /** How long a {@code Send} with {@code require_ack} waits for the acknowledgement when the request names none. */
        private Duration ackTimeout = Duration.ofSeconds(5);
    }

    @Data
    public static class Redis {
        /** Prefix of every key and channel the engine uses. */
        private String keyPrefix = "shiguchi:cursus:";
    }

    /** The POSTGRES engine: one table for history, {@code LISTEN/NOTIFY} for fan-out. */
    @Data
    public static class Postgres {
        /** Table holding the kept events; created at startup unless {@code create-schema} is off. */
        private String table = "push_event";

        /** The notification channel every instance listens on. */
        private String channel = "shiguchi_cursus";

        /** Run {@code CREATE TABLE IF NOT EXISTS} (and its indexes) at startup. */
        private boolean createSchema = true;

        /** How often expired history is deleted. */
        private Duration sweepInterval = Duration.ofMinutes(1);

        /** Trim a topic's history to {@code history-size} every this many appends to it. */
        private int trimEvery = 16;

        /**
         * Largest notification payload sent inline; Postgres caps a notification at 8000 bytes.
         * A larger event is announced by id and read from the table, which needs the namespace to
         * keep a history.
         */
        private int notifyMaxBytes = 7900;
    }

    /**
     * The JDBC engine: the store and archive on any SQL database through a blocking {@code DataSource}
     * (H2, PostgreSQL, MySQL/MariaDB, SQL Server, Oracle). Live fan-out by polling comes with the
     * JDBC broker; until then pair it with {@code broker: MEMORY} or {@code REDIS}.
     */
    @Data
    public static class Jdbc {
        /**
         * Bean name of the {@code DataSource} to use. Empty: the application's single
         * {@code DataSource}.
         */
        private String dataSource = "";

        /** Force a dialect instead of reading it from the database; empty: detected. */
        private dev.appiaworks.shiguchi.cursus.jdbc.JdbcDialect dialect;

        /** Table holding the kept events; created at startup unless {@code create-schema} is off. */
        private String table = "push_event";

        /** Run {@code CREATE TABLE} (and its indexes) at startup, ignoring objects that already exist. */
        private boolean createSchema = true;

        /** How often expired history is deleted. */
        private Duration sweepInterval = Duration.ofMinutes(1);

        /** Trim a topic's history to {@code history-size} every this many appends to it. */
        private int trimEvery = 16;

        /** How often the JDBC broker polls for new events (at least 50 ms). Read by the broker only. */
        private Duration pollInterval = Duration.ofMillis(500);
    }

    @Data
    public static class Ws {
        /** Serve {@code GET <path>/ws}. */
        private boolean enabled = true;

        /**
         * Browser origins allowed to open a WebSocket, as {@code scheme://host[:port]} or {@code *}.
         * Empty: only the gateway's own origin (the {@code Origin} header must match {@code Host}).
         * Clients that send no {@code Origin} header (anything but a browser) are never checked.
         */
        private List<String> allowedOrigins = new ArrayList<>();
    }

    @Data
    public static class Admin {
        /**
         * Role the writes of the {@code gateway-push} actuator endpoint need (disconnect, test
         * publish, republish, tombstone), with or without the {@code ROLE_} prefix. Empty: any
         * authenticated caller the security chain lets through.
         */
        private String role = "";

        /**
         * How much of an archived event the message views show. {@code METADATA} (the default)
         * shows topic, id, time, size, key, type and attributes but no payload: seeing user data is
         * a decision, not a default. {@code FULL} adds the payload; {@code NONE} hides the
         * attributes and key too.
         */
        private PayloadVisibility payloadVisibility = PayloadVisibility.METADATA;

        private Republish republish = new Republish();
    }

    public enum PayloadVisibility {
        NONE,
        METADATA,
        FULL
    }

    /** Limits on the admin republish, single and bulk. */
    @Data
    public static class Republish {
        /** Most events one bulk republish may send; a query matching more is refused. */
        private int maxBatch = 10_000;

        /** Events per second a bulk republish sends. */
        private int ratePerSecond = 500;

        /** Namespaces a republish may target. Empty: any declared namespace. */
        private List<String> namespaces = new ArrayList<>();
    }

    /**
     * The archive behind the admin message search: every published event kept for
     * {@code retention}, searchable, with republish and redaction. Off at the default
     * {@code retention} of zero. The {@code POSTGRES} store is its own archive (one table, two
     * retention windows); the {@code MEMORY} store gets a per-instance ring; the {@code REDIS}
     * store gets a cluster-wide archive in Redis Streams; any other store needs a
     * {@code PushArchive} bean of your own.
     */
    @Data
    public static class Archive {
        /** How long an event stays searchable; zero turns the archive off. */
        private Duration retention = Duration.ZERO;

        /** Most events the {@code MEMORY} archive keeps per instance. */
        private int memoryMaxEvents = 10_000;

        /**
         * Most entries one search reads from the {@code REDIS} archive; filters on attributes,
         * type and time run over what was read, so a selective search may return fewer than its
         * limit.
         */
        private int maxScan = 10_000;
    }

    @Data
    public static class Publish {
        /** Serve {@code POST <path>/publish}. */
        private boolean httpEnabled = true;
    }

    /**
     * The {@code shiguchi.cursus.v1.PushService} the gateway hosts itself, registered as a STATIC
     * service over an in-process channel: callable over native gRPC on the proxy port, gRPC-Web, and
     * JSON at {@code <path>/v1/publish} and {@code <path>/v1/subscribe} through the gateway's own
     * transcoding.
     */
    @Data
    public static class Grpc {
        private boolean enabled = true;

        /** The service's name in the gateway's service list, routes report and metrics. */
        private String serviceName = "push";
    }

    /**
     * Topics backed by an upstream server stream: a method carrying
     * {@code (shiguchi.api.topic)}. The first subscriber of a topic on any transport
     * opens the stream, one gateway instance owns it (a lease in the store), every instance's
     * subscribers get its events through the engine, the last subscriber leaving closes it after
     * {@code idle-linger}.
     */
    @Data
    public static class Upstream {
        /** How long a topic keeps its upstream stream open after its last subscriber left. */
        private Duration idleLinger = Duration.ofSeconds(10);

        /** How long an instance's ownership of a topic lasts without renewal; renewed at a third of it. */
        private Duration leaseTtl = Duration.ofSeconds(15);

        /** First wait before reopening an upstream stream that ended or failed; doubles up to {@code max-retry-backoff}. */
        private Duration retryBackoff = Duration.ofSeconds(1);

        private Duration maxRetryBackoff = Duration.ofSeconds(30);

        /** Deprecated: use {@code shiguchi.cursus.instance-id}. */
        private String instanceId = "";
    }

    /**
     * Backend hooks: {@code PushHook} beans and, with {@code grpc-service}, a served service
     * implementing {@code shiguchi.cursus.v1.PushHooks} that the gateway calls about topic
     * activity and connections.
     */
    @Data
    public static class Hooks {
        /** The full name of a served service implementing the PushHooks contract (any subset of its methods). Empty: none. */
        private String grpcService = "";

        /** How long after the last subscriber left a topic the idle hook fires, unless one returns. */
        private Duration idleLinger = Duration.ofSeconds(10);

        /** How long a hook call may take before it is counted as failed. Zero: no limit. */
        private Duration timeout = Duration.ofSeconds(5);

        /**
         * What a decision hook that times out or fails means: false (the default) allows the client
         * through, true rejects it. Notifications are unaffected.
         */
        private boolean failClosed = false;
    }

    public enum SourceType {
        /** Kafka topics through a consumer of the gateway's own; needs {@code kafka-clients}. */
        KAFKA,
        /** A served service's server-streaming method, opened through the gateway. */
        GRPC_STREAM
    }

    public enum PayloadFormat {
        /** The record's value is the protobuf encoding of {@code payload-type}. */
        PROTO,
        /** The record's value is the JSON rendering of {@code payload-type}. */
        JSON
    }

    public enum KafkaStart {
        LATEST,
        EARLIEST
    }

    /** One entry of {@code shiguchi.cursus.sources}. */
    @Data
    public static class Source {
        /** The source's name in the views, the dead-letter queue and the {@code source} attribute. Default: {@code <type>-<index>}. */
        private String name = "";

        private SourceType type = SourceType.KAFKA;

        /**
         * The push topic of each record: {@code namespace:template}. Kafka offers {@code {key}},
         * {@code {topic}}, {@code {partition}} and {@code {header.<name>}}; a gRPC stream its
         * message's scalar fields. A fixed {@code namespace:name} is allowed.
         */
        private String topicTemplate = "";

        /** For Kafka: the message type of the values (a type a served backend declares), so events are typed. Empty: JSON values as they are. */
        private String payloadType = "";

        private PayloadFormat payloadFormat = PayloadFormat.PROTO;

        /** For a gRPC stream: the message field whose value becomes the event key. */
        private String keyField = "";

        /** Attributes every event of this source carries. */
        private Map<String, String> attributes = new LinkedHashMap<>();

        /** One gateway instance at a time consumes (a lease in the store). False: every instance does. */
        private boolean shared = true;

        private SourceKafka kafka = new SourceKafka();

        private SourceGrpc grpc = new SourceGrpc();
    }

    @Data
    public static class SourceKafka {
        private String bootstrapServers = "";

        /** The Kafka topics to read; every partition of each. */
        private List<String> topics = new ArrayList<>();

        /** Where reading starts when the source opens. */
        private KafkaStart start = KafkaStart.LATEST;

        private Duration pollTimeout = Duration.ofMillis(500);

        /** Any other consumer property (security, SASL, fetch sizes). */
        private Map<String, String> properties = new LinkedHashMap<>();
    }

    @Data
    public static class SourceGrpc {
        /** The served service's full name. */
        private String service = "";

        /** Its server-streaming method. */
        private String method = "";

        /** The request, as JSON. Empty: the default message. */
        private String request = "";
    }

    /** The dead-letter queue of records a source could not map and events the gateway could not publish. */
    @Data
    public static class Dlq {
        /** Most entries kept per instance; the oldest leave first. */
        private int maxEntries = 1000;
    }

    /** The namespace of {@code topic}, or null when it is not declared. */
    public Namespace namespaceOf(Topic topic) {
        return namespaces.get(topic.namespace());
    }
}
