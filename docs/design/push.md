# Push: server-to-client messaging — design

Status: proposal, 2026-10-08 (Redis `history-ttl` is per event on 6.2+ standalone/Sentinel via `XTRIM MINID`; older Redis and Cluster expire the whole key only). Slices 1, 2, 3 (WebSocket `push.v1.json` and the gRPC
`PushService`, a server stream rather than the bidi sketched below; acks wait for persistent
sessions) and the admin half of 4 (section 14) are built: SSE, WebSocket and gRPC subscriptions,
publish over HTTP and gRPC, `PushService` hosted in-process as a STATIC service of the gateway's
own so gRPC-Web and the JSON transcoding at `<path>/v1/*` come for free, payloads as
`google.protobuf.Value` or an `Any` (rendered to JSON for the JSON transports, forwarded as
published to gRPC subscribers; one shared wire frame per event), the in-memory, Redis and Postgres
engines (Postgres: R2DBC inside the push module like Redis, one table with idempotent DDL at
startup rather than Liquibase, no partitioning or cursors yet), the archive (`PushArchive`
SPI; the Postgres table doubling as the archive with `archive.retention`, a per-instance ring
on MEMORY, Streams shared by every instance on REDIS (`RedisPushArchive`, no full text); search, `messages/{id}?topic=` (the topic as a parameter: topic names may contain `/`), republish one or a query's worth as a job,
tombstone, audit, the delivery log (`delivery-log` per namespace, counts always,
`messages/{id}/deliveries`) and the NDJSON export on the push path; no `_sys:audit` topic yet, no
leased archiver: the Postgres archive is the store's own row), the `gateway-push` endpoint and the console's Push
tab with the Messages and Audit views, the GraphQL subscription fields (section 10; the resume id
travels in the envelope, not in graphql-transport-ws extensions), the declarative
`(shiguchi.api.topic)` of section 9.4 (`topic.proto`: `pattern` and `mode:
SHARED_UPSTREAM`; one owner per topic through a store lease rather than Redis `SET NX` /
Postgres advisory locks specifically; `event_id_field` is built for the ids of the `MEMORY` and `REDIS` stores, the Postgres store records it as the `upstream.id` attribute because its ids are one table-wide sequence, and the `id:` line of plain streaming SSE routes is still open), the sources of 5.2
(`KAFKA` with a consumer of the gateway's own rather than Spring Kafka, `GRPC_STREAM`, the
`PushSource` SPI, one consumer per source through the same lease), the dead-letter queue (a
per-instance ring with retry and delete, not a store table yet), and the topic-activity and
connection hooks of 9.5 (`push_hooks.proto`: the topic and disconnect notifications, and the `OnConnect`,
`OnSubscribe` and `OnClientMessage` decisions with `hooks.fail-closed`), the persistent sessions of section 7 (a cursor
per session key and topic in the store, `PRINCIPAL` or `CLIENT` sessions, acks over WS, gRPC and
HTTP, `max-queued` with an `overflow` signal, expiry) and the consumer groups of 7.1
(`PushStore.claim` behind Redis consumer groups, Postgres `SKIP LOCKED` rows and an in-memory
map; `ack-deadline` redelivery, `max-deliveries` to the DLQ, `max-inflight`; `ordering` KEY
(Memory, Postgres) and STRICT, and `seek` of a group or a session, are built), the connection registry of section 11 and presence of 9.6 (`HasSubscribers`, `GetSubscribers`, `presence: true`, `cursus.instance-id`, device id, the cluster-wide `connections` view; `WatchTopicActivity` stays with the hooks), targeted `Send` and the `PushPeer` channel of section 11 (a status per connection, `require_ack`, the `_direct` namespace's publish rules, the admin send and the cross-instance disconnect; `user:{sub}` targeting stays a topic pattern), the JDBC store and archive of 5.1 (`store: JDBC`, the `jdbc` package of `shiguchi-cursus` behind `compileOnly` Spring JDBC rather than a `-jdbc` module: blocking JDBC on `boundedElastic`, ids from a counter table, claims by optimistic update, `LIKE` search; and its polling broker, `broker: JDBC`: every `poll-interval` each instance reads `id > last` from the store's table and fans out through an in-process broker, starting at the head; latency equals the poll interval; end-to-end tests and a benchmark trial run on H2), `AT_MOST_ONCE` / `AT_LEAST_ONCE` with resume, per-namespace
authorization (with `PRINCIPAL_MATCH` on a per-namespace `principal-claim`, and the principal name shared with the HTTP chain through one JWT converter), the close frame on shutdown; user guide in [../push.md](../push.md). The Redis
engine lives inside `shiguchi-cursus` behind `compileOnly` Spring Data Redis, the way the
rate limiter's does, rather than in a `-redis` module. The `cursus` command line of section 13 is built (`shiguchi-cursus-cli`: `sub`, `pub`, `ls`, `find`, `repub`, `rm`, `dlq`, `seek`, `send`, `bench`, profiles, auth, NDJSON, exit codes, a fat jar attached to each release, `bin/cursus` and a jbang catalog entry; user guide in [../push-cli.md](../push-cli.md)); echo suppression of 9.7 is built (the `push.origin` attribute, `origin` on subscribe, publish and `Send`); the `reset` signal for a resume point older than the history is built (`PushStore.trimmedThrough`: a ring watermark on `MEMORY`, out-of-history rows plus a `<table>_trim` watermark table on `JDBC` and `POSTGRES`; `reason` `history_gap` or `no_history`; the persistent session tests its stored cursor; `REDIS` too: an exact trim in the append script records the last removed id in `<prefix>trim:<topic>`, and a whole-key expiry answers the last appended id for seven days; not on Redis Cluster, which keeps the approximate single-key trim and logs one INFO line; consumer groups are not covered); the TypeScript client `@appiaworks/shiguchi-cursus-client` of section 13 is built (`shiguchi-cursus-client-js/`, experimental, published to npmjs.com by the release workflow; its frame fixture is checked against the handler by `PushClientProtocolFixtureTest`); the BSR SDKs and the Java publisher client remain plans. Where the table in section 13 differs, the guide is right (for example `rm ID --topic T`). Everything else here is still a plan. Module tree: `shiguchi-cursus/*`.

"Push" means in-app, server-to-client messaging over SSE, WebSocket, GraphQL subscriptions
and gRPC streams. It is not mobile push (APNs/FCM) and it is not a message broker.

## 1. Why, and why inside the gateway

Today every streaming surface is 1:1: one client stream is one upstream gRPC stream
(`ReactiveGrpcDispatcher.dispatchStreaming`, `DynamicGraphQlDataFetcher` returning a `Flux`,
gRPC-Web streaming). There is no fan-out, no cross-instance delivery, no `Last-Event-ID`
resume, no backend-to-client publish, no connection registry, no connection-lifetime control.

The module adds those as an optional feature. It lives in this repository, not in a separate
project, because its distinctive value only exists here:

- topics declared on proto methods, with one upstream gRPC stream shared by N subscribers;
- GraphQL subscriptions fanned out across instances through the same engine;
- authorization through the existing `GatewayAuthorizer` SPI, JWT resource-server setup and
  the per-surface security tiers;
- reuse of the SSE writer and keepalive, the optional-Redis wiring pattern of
  `RedisRateLimiter`, the `discovery:///` resolver, the `gateway-traffic` drain, Micrometer,
  the Boot 3 / Boot 4 duality, the API gate and the release train.

A standalone product would be "Centrifugo in Java": a losing comparison. The line is the wire
protocol. Push uses standard transports and ships no custom client SDK beyond generated gRPC
stubs. If a custom protocol with per-language SDKs is ever wanted, that is a separate product.

A standalone *deployment* is still possible: `samples/sample-push-gateway` runs the gateway
with only the push module enabled, the same way `sample-grpc-ui-standalone` does for the UI.

Every public type ships `@ApiStatus(EXPERIMENTAL)` until the shape settles, so the japicmp
gate does not bind.

## 2. Prior art

| System | Model | What we take |
| --- | --- | --- |
| Netflix Zuul Push (2018) | Netty edge servers hold WebSocket/SSE; backend → Push Library → Kafka → Message Processor → registry lookup → push server. Registry: local `PushConnectionRegistry` + global store with TTL (Dynomite over Redis/Cassandra, 1800 s). Max connection lifetime + jitter (`reconnect.dither.seconds` 180). Autoscale on open connections, not CPU. 5.5 M connections. | registry with TTL, lifetime + jitter, graceful close, connection-based scaling signal |
| Netflix Pushy (2024) | Same shape at hundreds of millions of connections, 200k–400k per node. Dynomite → KeyValue, Mantis → Spring Boot Kafka consumer. Direct push (library looks up registry, calls the owning instance, gets a status) now 160k msg/s vs 50k through Kafka. Heartbeats + idle cleanup → 99.999 % delivery. 30-minute staggered reconnect. Cached lookups p50 < 1 ms. | direct delivery with status, heartbeat/idle hygiene, cached registry lookups |
| Pushpin / GRIP | Proxy holds the connection; backend answers normal HTTP with `Grip-Hold`, `Grip-Channel`, then publishes to a control API; `prev-id` for gap-free resume; backend fully stateless. | the "proxy holds, backend stays stateless" contract |
| Mercure | SSE hub, topics are IRIs with URL-pattern matchers, JWS with `subscribe`/`publish` claim arrays, `Last-Event-ID` reconciliation, `private` updates, subscription/presence API, `Link rel=mercure` discovery beside REST. | topic auth claims, `Last-Event-ID`, `private`, REST co-location |
| nchan | nginx module, Redis-backed history with `Last-Event-ID`, up to 255 channels per connection, auth via subrequests, channel groups with quotas. | multiplexing, groups with limits |
| Centrifugo | WS/SSE/HTTP-stream/gRPC/WebTransport, JWT connection auth, namespaces with per-namespace policy, presence, history + recovery on Redis Streams, engines memory/Redis/NATS, proxy mode (connect/subscribe/publish/rpc hooks to the backend over HTTP or gRPC), server-side subscriptions. | namespaces, engine abstraction, proxy-mode hooks, history/recovery positions |
| AWS API Gateway WebSocket | `$connect`/`$disconnect`/`$default` routes, `connectionId` handed to the backend, backend pushes through the `@connections` callback; 10 min idle, 2 h max lifetime. | the stateless-backend callback contract |
| Reddit realtime (2021) | GraphQL subscriptions as the message schema over WebSocket. | GraphQL as a client contract for push |

Skipped on purpose: custom binary protocol and SDKs, WebTransport, device-to-device messaging,
delta compression, mobile push providers.

## 3. Vocabulary

- **Topic**: `namespace:path`, e.g. `orders:123`, `user:alice`, `tenant:acme/alerts`.
- **Namespace**: carries policy: delivery mode, history, auth mode, presence, client publish,
  limits. Mirrors Centrifugo namespaces.
- **Event**: one message on a topic, with an engine-assigned ordered id.
- **Subscriber**: a connection subscribed to one or more topics; on a persistent session the
  `session-key` (`PRINCIPAL`, `CONNECTION` or `GROUP`) identifies the mailbox.
- **Publisher**: a backend service (gRPC or HTTP), a source adapter, or, where the namespace
  allows, a client.

## 4. Modules

```
shiguchi-cursus/
  shiguchi-cursus                  core: SPIs, topic model, session state machine, SSE/WS/gRPC
                                     transports, publish API, admin report, metrics
  shiguchi-cursus-redis            PushBroker (Pub/Sub) + PushStore (Streams)
  shiguchi-cursus-postgres         PushStore + PushArchive (R2DBC), PushBroker (LISTEN/NOTIFY)
  shiguchi-cursus-jdbc             PushStore + PushArchive on any JDBC database, polling broker
  shiguchi-cursus-kafka            PushSource (consume business topics), replay-only store
  shiguchi-cursus-client           Java publisher helper (batching, retry), the "Push Library"
  shiguchi-cursus-cli              cursus command line
shiguchi-proto                   push.proto: options + PushService + PushHooks + PushFrame
spring-boot3/shiguchi-cursus-starter, spring-boot4/shiguchi-cursus-starter
shiguchi-ui-frontend                     Push tab, feature-detected
samples/sample-push-gateway, docs/push.md, samples/observability dashboard
```

Backends in scope now: **MEMORY, REDIS, POSTGRES/JDBC, KAFKA as source**. NATS, RabbitMQ,
Hazelcast, MongoDB and cloud brokers are explicitly out of the plan; the SPIs leave room.

Each backend module is optional on the classpath, wired like `RedisRateLimiter`: the core
depends only on the SPIs; a configured backend without its module fails startup with a plain
message.

## 5. SPIs

Three SPIs, because backends are good at different halves and Kafka is an ingest story:

```java
public interface PushBroker {            // live fan-out between gateway instances
    Mono<Void> publish(Topic topic, PushEvent event);
    Flux<PushEvent> subscribe(TopicPattern pattern);
    Capabilities capabilities();
}

public interface PushStore {             // history, durable cursors, registry, stats
    Mono<EventId> append(Topic topic, PushEvent event);
    Flux<PushEvent> range(Topic topic, @Nullable EventId after, int limit);
    Mono<Void> ack(SessionKey session, Topic topic, EventId upTo);
    Flux<PushEvent> pending(SessionKey session, Topic topic, int limit);
    Mono<Void> registerConnection(ConnectionInfo info, Duration ttl);
    Mono<Optional<ConnectionInfo>> lookup(ConnectionId id);
    Mono<Long> subscriberCount(Topic topic);
    Capabilities capabilities();
}

public interface PushSource {            // external records → topics
    Flux<SourcedEvent> events();         // (topic, event) after template/mapper
}

public interface PushArchive {           // optional cold store with an index
    Mono<Void> append(PushEvent event);
    Flux<PushEvent> search(EventQuery query);
    Mono<Void> tombstone(Topic topic, EventId id);
    SearchCapabilities capabilities();
}
```

`Capabilities` declares `history`, `persistentSessions`, `presence`, `orderingScope`, `idKind`; namespace
configuration is validated against it at startup (a persistent-session namespace on a store
without `persistentSessions` is a startup error). `EventId` is an opaque string with an engine-supplied comparator;
the core never parses it. One contract test per SPI runs in every backend module, the way the
serde parity test is the contract for the transcoding tiers.

Combinations are allowed: `broker: KAFKA, store: POSTGRES`.

### 5.1 Backends

| Backend | Broker | Store | Source | Latency |
| --- | --- | --- | --- | --- |
| MEMORY | `Sinks.many().multicast()` per topic | ring buffer per topic, maps for registry | — | µs; single node, tests |
| Redis | Pub/Sub, one pattern subscription per instance per namespace | Streams (`XADD` then an exact `MAXLEN history-size` trim recording a gap watermark, key `EXPIRE` refreshed on write), consumer groups for persistent sessions, hashes for registry and stats | — | < 1 ms. Pub/Sub is lossy; the store covers it |
| Postgres (R2DBC) | `LISTEN/NOTIFY` on one dedicated connection per instance; payload cap 8 000 B, so the notification carries `topic,id` and the event is read from the table | `push_event(topic, id bigserial, ts, type_url, payload bytea, attrs jsonb)` partitioned by day with a TTL job; `push_cursor(session, topic, last_ack_id)` for persistent sessions (a cursor is cheaper than a pending table); `push_connection` registry; Liquibase changelogs, append-only | — | 1–5 ms. Zero new infrastructure for most teams |
| JDBC (MySQL, SQL Server, Oracle, H2) | polling `id > last` every `poll-interval` (default 500 ms, at least 50 ms; built) | same tables | — | = poll interval (measured ≈ the interval on H2). Lowest bar; also the Docker-free test engine. A publish does not notify: the row is the message, so an event with no row (no history, archive off) reaches only the publishing instance |
| Kafka | one Kafka topic per namespace, push topic as record key (ordering per push topic kept); each instance `assign()`s all partitions, no consumer group, starts at latest | replay only: id = `partition-offset`, `seek` to resume within retention. No persistent sessions (a Kafka consumer group per user does not scale): pair with Redis or Postgres | yes, the main role: consume existing business topics (Pushy's indirect path) | 5–50 ms |

Ordering is guaranteed per topic on every backend and never across topics.

Pub/Sub on Redis carries the payload; the store is only for replay. If measurement shows
double-serialisation cost at high fan-out, switch to id-only notifications with `XREAD` on the
subscriber side. Decide with numbers, not up front.

### 5.2 Sources

```yaml
shiguchi.cursus.sources:
  - type: KAFKA
    topics: [order-events]
    topic-template: "orders:{key}"            # or header: x-push-topic
    payload-type: acme.OrderEvent             # proto type, so typed JSON and GraphQL work
  - type: GRPC_STREAM                          # backend firehose
    target: discovery:///order-service
    method: acme.OrderService/Events          # rpc Events(Empty) returns (stream Envelope)
```

Mapping is template-based (`topic-template`, header, key) with an optional `PushSourceMapper`
bean for custom record → event. Records that fail mapping, size, schema or authorization go to
the DLQ (section 12).

## 6. Configuration shape

```yaml
shiguchi:
  cursus:
    enabled: true
    path: /push
    broker: REDIS                 # MEMORY | REDIS | POSTGRES | JDBC | KAFKA
    store: POSTGRES               # NONE | REDIS | POSTGRES | JDBC
    archive: { type: POSTGRES, retention: 30d, payload: FULL }   # FULL | METADATA | NONE
    namespaces:
      ticker: { qos: AT_MOST_ONCE, client-queue-size: 256, auth: NONE }
      orders: { qos: AT_LEAST_ONCE, history-size: 500, history-ttl: 1h, auth: AUTHORIZER }
      inbox:  { qos: AT_LEAST_ONCE, session-key: PRINCIPAL, session-expiry: 7d,
                max-queued: 1000, auth: PRINCIPAL_MATCH }
      chat:   { qos: AT_MOST_ONCE, client-publish: true, client-mode: FANOUT, presence: true }
    connection:
      max-lifetime: 30m
      lifetime-jitter: 3m
      idle-timeout: 10m
      keepalive: 15s              # SSE keepalive comment / WS ping; same name as rest.streaming.sse-keepalive
      close-grace: 4s
      max-subscriptions: 255
      max-message-bytes: 65536
    ws:
      enabled: true
      allowed-origins: []         # empty = same origin only
      compression: false
      auth-timeout: 5s
    publish:
      http-enabled: true
      grpc-enabled: true
      trusted-publishers: []      # mTLS SANs or JWT azp values that skip per-event checks
    admin:
      role: PUSH_ADMIN
      read-role: PUSH_ADMIN
      payload-visibility: METADATA   # NONE | METADATA | FULL
      republish: { max-batch: 10000, rate: 500/s, namespaces: [orders, inbox] }
```

## 7. QoS and sessions

Naming follows MQTT: `qos` names the delivery guarantee, `session-expiry` names how long the
server remembers a subscriber that is gone. The two are orthogonal, which is why there are two
fields and not three modes.

| `qos` | `session-expiry` | Guarantee | Storage | Catch-up | Slow consumer | Fits |
| --- | --- | --- | --- | --- | --- | --- |
| `AT_MOST_ONCE` | n/a | only while connected | none | none | drop oldest in `client-queue-size` | prices, typing, cursors |
| `AT_LEAST_ONCE` | `0` (transient session) | at least once within the history window | stream per topic, `history-size` + `history-ttl` | `Last-Event-ID` / `after` → range read; a gap beyond the window sends `reset` (built for `MEMORY`, `JDBC`, `POSTGRES` and `REDIS`), the client refetches state over REST and keeps reading the retained events (the Mercure `Link last-event-id` pattern) | disconnect, client resumes | order status, dashboards |
| `AT_LEAST_ONCE` | `> 0` (persistent session) | at least once per session, queued while offline | cursor per `session-key`, `max-queued`, `max-inflight` | automatic on reconnect; needs ack | backpressure, then the queue cap drops the oldest and sends `overflow` | inbox, notifications, jobs |

`EXACTLY_ONCE` is not offered; the event id is the idempotency key for consumers that need it.
A subscription may ask for a lower `qos` than its namespace (`?qos=AT_MOST_ONCE`), never a
higher one.

Acks on a persistent session: WS `ack` frame, gRPC bidi `Subscribe` ack message; SSE has no
upstream, so the ack is implicit from `Last-Event-ID` on reconnect or `POST /push/ack`.
`session-expiry` bounds how long an offline session keeps its cursor and queue; after it both
are deleted (MQTT 5 "session expiry interval"). Streams are bounded by `history-size` and
`history-ttl`, so store memory follows configuration, not traffic.

Publisher side: `Publish` returns the id after the durable append; `fire_and_forget: true`
skips the wait on `AT_MOST_ONCE` topics. `attributes.priority` is reserved and has no
behaviour yet.

### 7.1 Consumer groups

A persistent-session namespace with `session-key: GROUP` turns a subscription into competing
consumers: N members share one session, each event is delivered to exactly one member, an ack
is required, an event unacked past `ack-deadline` is reclaimed and redelivered to another
member, and after `max-deliveries` it goes to the DLQ. This is for backend workers that only
speak gRPC or WebSocket, and for multi-replica internal services. End-user fan-out stays
broadcast.

```yaml
namespaces:
  jobs: { qos: AT_LEAST_ONCE, session-key: GROUP, session-expiry: 30d,
          ack-deadline: 30s, max-deliveries: 5, max-inflight: 64,
          ordering: KEY }    # NONE | KEY (event key hashed to a sticky member) | STRICT (one active member)
```

The subscribe request names the group: WS `sub{topics, group}`, gRPC `SubscribeRequest.group`,
SSE `?group=` (SSE cannot ack inline, so `POST /push/ack`; WS and gRPC are the intended
transports). The group name is authorized with the topic (`PUSH_SUBSCRIBE` carries `group`;
`CLAIM` mode reads `push.groups`).

Backends: Redis Streams natively (`XGROUP`, `XREADGROUP`, `XACK`, `XPENDING`, `XAUTOCLAIM`);
Postgres claims rows with `FOR UPDATE SKIP LOCKED`; JDBC claims by optimistic update, because `SKIP LOCKED` is not portable (a member selects candidate rows, takes each with an `UPDATE ... WHERE claimed_at = <the value it read>`, and the row count says whether it won); MEMORY round-robins; Kafka does not
support group mode (the gateway `assign()`s partitions for broadcast), so pair it with a Redis
or Postgres store.

Limits, on purpose: no partitions, no rebalance protocol beyond reclaim, at-least-once only.
Not a Kafka replacement; the docs say so.

### 7.2 Field names against prior art

Names were checked against MQTT, Centrifugo, Mercure, nchan, Google Pub/Sub, Kafka, NATS
JetStream and CloudEvents. Where a name is shared by two or more of them, the doc uses it.

| Ours | Same as | Different in | Decision |
| --- | --- | --- | --- |
| `topic` | MQTT, Kafka, Mercure, Pub/Sub | Centrifugo, nchan, Pusher say `channel` | keep `topic` |
| `namespace` | Centrifugo | nchan `channel group` | keep |
| `qos: AT_MOST_ONCE / AT_LEAST_ONCE` | MQTT QoS 0 / 1 (spelled out, like Pub/Sub docs) | former `delivery: LIVE / REPLAY / DURABLE` | renamed; no `EXACTLY_ONCE` |
| `session-expiry` | MQTT 5 session expiry interval | former `subscription-ttl` | renamed |
| `session-key: PRINCIPAL / CONNECTION / GROUP` | Centrifugo `user` / `client` | former `subscriber-key` | renamed; `PRINCIPAL` kept for consistency with the rate limiter |
| `history-size`, `history-ttl` | Centrifugo `history_size`, `history_ttl` | nchan `message_buffer_length`, `message_timeout` | `history` → `history-size` |
| `client-queue-size` | Centrifugo `client_queue_max_size` (bytes there, events here) | former `buffer` | renamed |
| `max-queued` | Mosquitto `max_queued_messages` | former `max-pending` | renamed |
| `max-inflight` | MQTT receive maximum, Kafka `max.in.flight` | new | added for groups |
| `ack-deadline` | Pub/Sub `ackDeadline` | NATS `ack_wait`, SQS visibility timeout; former `ack-timeout` | renamed |
| `max-deliveries` | NATS `max_deliver`, Pub/Sub `maxDeliveryAttempts` | SQS `maxReceiveCount` | keep |
| `group` | Kafka consumer group, NATS queue group | Pub/Sub `subscription` | keep |
| `ordering: KEY` + event `key` | Pub/Sub `orderingKey`, Kafka key | former `attributes.key` | `key` is now a first-class event field |
| `keepalive` | MQTT, graphql-ws, existing `rest.streaming.sse-keepalive` | Centrifugo `ping_interval`; former `heartbeat` | renamed |
| `max-message-bytes` | Kafka `max.message.bytes` | Centrifugo `websocket_message_size_limit`; former `max-frame-bytes` | renamed; applies to publish too |
| `max-subscriptions` | — | Centrifugo `client_channel_limit` | keep, plainer |
| `allowed-origins` | Centrifugo | — | keep |
| `client-publish` | Pusher client events | Centrifugo bare `publish` | keep, clearer |
| `presence` | Centrifugo, Pusher | Mercure subscription API | keep |
| `auth: PRINCIPAL_MATCH / CLAIM / AUTHORIZER` | — | Centrifugo `allow_user_limited_channels` / JWT `channels` / `proxy_subscribe` | keep ours; doc lists the equivalents |
| `after` (resume position) | — | Centrifugo `offset`+`epoch`, Mercure `lastEventID` | keep; SSE uses `Last-Event-ID` |
| event `attributes`, `time`, `private` | Pub/Sub `attributes`, CloudEvents `time`, Mercure `private` | Kafka `headers` | `time` added |
| `seek` | Pub/Sub seek, Kafka `--reset-offsets` | former `cursor reset` | renamed |
| `tombstone` | Kafka | — | keep |
| `dlq` | universal | — | keep |
| `sources` | Kafka Connect source | Centrifugo `consumers` | keep |
| `broker` / `store` | Centrifugo v5 `broker` (+ presence manager) | — | keep |
| `retention` | Kafka | — | keep |
| WS frames `connect / sub / unsub / pub / ack / ping` | Centrifugo `connect / subscribe / publish`, graphql-ws `connection_init / subscribe` | — | keep short forms |

Not adopted: CloudEvents as the envelope (an `attributes` prefix convention can map to it
later), Centrifugo `channel` wording, MQTT numeric QoS levels.

## 8. Authorization

Three checks, one SPI. `AuthorizationRequest` gains `operation` (`PUSH_SUBSCRIBE`,
`PUSH_PUBLISH`, `PUSH_ADMIN`), `topic` and `topicVars`; the existing OpenFGA and OPA samples
work unchanged.

1. **Subscribe (read).** Per namespace `auth`: `NONE`, `ROLE`, `PRINCIPAL_MATCH` (a topic
   variable must equal the JWT `sub`, e.g. `user:{sub}`), `CLAIM` (JWT `push.subscribe:
   ["orders:*", "tenant:acme/*"]`, Mercure style), `AUTHORIZER` (the SPI decides).
2. **Publish (write).** Backends authenticate like any gRPC caller to the gateway: mTLS SAN or a
   client-credentials JWT with the `push.publish` scope and allowed topic patterns in a claim.
   `trusted-publishers` skip per-event checks. Clients publish only where the namespace sets
   `client-publish: true`, through the same authorizer.
3. **Data in the topic (per-subscriber filtering)** for topics that mix audiences:

```proto
message PushEvent {
  string topic = 1;
  string id = 2;                          // engine-assigned, ordered per topic
  google.protobuf.Any payload = 3;        // typed; JSON accepted and validated when the type is declared
  map<string, string> attributes = 4;     // tenant, owner, classification, origin_id ...
  Audience audience = 5;                  // optional: principals, roles, claim equals
  bool private = 6;                       // only authorized subscribers, never auth: NONE ones
  string key = 7;                         // ordering key (Pub/Sub orderingKey, Kafka key); group ordering: KEY
  google.protobuf.Timestamp time = 8;     // publish time, set by the gateway
}
```

Delivery-time filter order: `audience` match in memory, then
`GatewayAuthorizer.filterEvent(subscriber, event)` (a `default` method returning
`Mono<Boolean>`, so the SPI stays compatible), its decision cached per
`(subscriber, topic, attribute hash)`. Guidance for users: design topics so the topic is the
authorization boundary (`tenant:{id}:orders`); reserve `audience` and the filter for mixed
topics, because an authorizer call per event at 100k msg/s is the thing to avoid.

Admin operations require `admin.role`; the actuator endpoint enforces it too, otherwise the UI
gate is theatre.

## 9. Transports and the publish API

One `PushSession` state machine serves every transport. SSE is its read-only projection.

| Surface | Endpoint |
| --- | --- |
| SSE | `GET /push/subscribe?topic=a&topic=b`, `Last-Event-ID` header or `lastEventId` query; frames carry `id:`, `event: <topic>`, `data:`; `event: reset`, `event: overflow` |
| WebSocket | `GET /push/ws`, subprotocols `push.v1.json` (text) and `push.v1.proto` (binary `PushFrame`) |
| gRPC | `PushService.Subscribe` (bidi: subscribe/ack frames up, events down), works over gRPC-Web too |
| GraphQL | subscription fields on the engine (section 10) |
| Publish | `PushService.Publish`, `PushService.Send` (targeted), `POST /push/publish`, `POST /push/topics/{topic}` (client publish) |
| Presence | `PushService.HasSubscribers`, `GetSubscribers`, `WatchTopicActivity` |

### 9.1 WebSocket protocol

STOMP is Servlet-only in Spring, Mercure is SSE-only and the Centrifugo protocol is SDK-bound,
so the gateway defines a small protocol. Implementation: a WebFlux `WebSocketHandler` on a
`SimpleUrlHandlerMapping`, the same wiring Spring GraphQL uses; identical on Boot 3 and 4.
graphql-transport-ws stays for GraphQL.

| Direction | Frame | Fields |
| --- | --- | --- |
| client → server | `connect` | `token?`, client info; must arrive within `auth-timeout` when the handshake carried no credentials |
| client → server | `sub` | `topics[]`, `after?` (id per topic), `group?`, `qos?` (downgrade only) |
| client → server | `unsub`, `pub`, `ack`, `refresh` (new token), `ping` | |
| server → client | `connected` | `connectionId`, limits, `keepalive` |
| server → client | `subscribed` | `topic`, `position`, `qos` in effect, or `error{code}` |
| server → client | `event` | `topic`, `id`, `data` (JSON) or `payload` (Any) |
| server → client | `reset{topic, reason}`, `overflow{topic}`, `pong` | |
| server → client | `close` | `code`, `reason`, `retryAfterMs`, then WS close 1012 (rebalance), 1008 (policy), 4401 (token expired), 4440 (BFF session ended) |

Authentication: `Authorization` header on the handshake for non-browser clients; browsers use
the HttpOnly cookie when a UI session exists, otherwise the token in the first `connect` frame
(the graphql-ws `connection_init` pattern). A query-parameter token is off by default. `refresh`
before `exp`, otherwise close 4401. **Decided (BFF G4):** a connection opened with the browser sign-in session
cookie needs no token and no `refresh`: it lives as long as the session and closes with 4440
(`session ended`; SSE: a final `close` event, reason `session ended`) within `session-check-interval` of the session ending.
A token in `connect` or `refresh` on such a connection closes it with 1008. See
[push.md](push.md#cookie-connections) and [bff.md](bff.md).

The origin check is mandatory (`ws.allowed-origins`, default same origin). CORS does not cover
WebSocket; cross-site WebSocket hijacking is the classic hole.

Lifecycle: WS ping at `keepalive`, idle close, max lifetime + jitter through `close{rebalance}`,
and the `gateway-traffic` drain sends the same close. Limits: `max-subscriptions`,
`max-message-bytes`, an outbound queue per `qos`, inbound rate through the rate-limit
module keyed `conn:{id}`. `permessage-deflate` is opt-in.

### 9.2 Client publishing

Off by default. Per namespace `client-publish: true` with `client-mode`:

- `FANOUT`: the frame goes straight into the topic (chat, typing);
- `FORWARD`: the gateway calls `PushHooks.OnClientMessage`, the backend decides what to publish
  (telemetry, commands). Default when hooks are configured.

### 9.3 Fan-out shapes

| Shape | Request | Delivery |
| --- | --- | --- |
| Topic fan-out | `Publish{topic}` | every subscriber of the topic on every instance |
| Multi-topic | `Publish{topics[]}` | one append per topic, one broker frame |
| Targeted | `Send{targets: [user:a, user:b, conn:x]}` | registry lookup → owning instances; per-target status |

Cross-instance cost is one broker message per gateway instance, not per client. An instance
serialises once per format and writes N times.

### 9.7 Echo suppression (origin)

A publisher can say which client an event came from, and a subscriber that names itself as that
client does not receive it, so consumers stop writing their own echo suppression. Built.

- **Origin is a declared id**, `[A-Za-z0-9_.:@-]{1,128}`, carried on the event as the reserved
  attribute `push.origin`. It is a value the client knows and a backend can pass on when it
  publishes for the client, not a gateway connection id, which an SSE client cannot even see. Not a
  secret and not checked: a subscriber may declare any origin and the only effect is that it
  misses events stamped with it.
- **Subscriber side:** SSE `?origin=`, WebSocket `connect{origin}` (connection default) and
  `sub{origin}`, gRPC `SubscribeRequest.origin`, GraphQL argument `origin`. Nothing declared, nothing
  changes. The filter runs in the gateway (`PushStreams`), on the output of replay plus live
  tail, so the resume position still moves past a suppressed replayed event; the transport never
  sees it, so the delivery log does not record it. Metric `shiguchi.cursus.suppressed{namespace}`.
- **Publisher side:** operator entry points (HTTP `/publish` field `origin`, `PublishRequest.origin`,
  `SendRequest.origin`, the admin `publish`) are trusted and refuse `push.*` attributes (400 /
  `INVALID_ARGUMENT`); client entry points (WebSocket `pub`, `POST /push/topics/{topic}?origin=`)
  stamp the origin the connection or request declared and silently drop `push.*` attributes and
  `from`. An admin republish keeps the original event's `push.origin`.
- **Groups** ignore it: a group delivers each event to one member, and skipping it for the
  publisher's own member would leave it unprocessed.
- **Persistent sessions** filter on the stream only; the cursor is a high-water mark of client
  acks, so acknowledging a skipped event from the stream could skip an earlier delivered but
  unacknowledged one. Instead `PushSessions.start` advances the cursor over the leading run of
  own-origin events (nothing unacknowledged precedes them), before `max-queued` is counted. An
  own-origin event behind an unacknowledged foreign one stays behind the cursor and still counts
  toward `max-queued`.
- **Direct sends** are not filtered on the stream: `PushConnections.Live` holds the connection's
  declared origin (`connect{origin}`, `?origin=`, `SubscribeRequest.origin`; a `sub` origin does not
  count) and `deliverDirect` answers `SUPPRESSED` for an event stamped with it.
- `from` is now stamped as documented: client publishes overwrite it, gRPC `Publish` and HTTP
  `/publish` set it.

### 9.4 Declarative topics on proto methods

```proto
service OrderService {
  rpc WatchOrder(WatchOrderRequest) returns (stream OrderEvent) {
    option (shiguchi.api.topic) = {
      pattern: "orders:{order_id}"      // request field → topic
      mode: SHARED_UPSTREAM             // one gRPC stream per topic, N subscribers
      event_id_field: "sequence"        // optional, otherwise the engine assigns
    };
  }
}
```

The gateway keeps a `topic → upstream stream` map: the first subscriber opens `WatchOrder`,
later ones attach through the engine, the last one closes it after `idle-linger`. Across
instances one owner per topic is elected with a lease (`SET NX EX` on Redis, an advisory lock
on Postgres); the owner republishes into the broker. The backend sees one stream per topic
instead of one per client. Declaring `event_id_field` makes the field the event id (see
[../push.md](../push.md)); it is meant to also give today's plain server-streaming SSE routes an
`id:` line, which waits for the Tier 0 streaming path to learn to read one field off the wire.

### 9.5 Backend hooks (proxy mode)

Implemented by the backend, called by the gateway, all optional:

```proto
service PushHooks {
  rpc OnConnect(ConnectionInfo) returns (ConnectDecision);       // may reject, may add server-side subscriptions
  rpc OnDisconnect(ConnectionInfo) returns (google.protobuf.Empty);
  rpc OnSubscribe(SubscribeHook) returns (SubscribeDecision);
  rpc OnClientMessage(ClientMessage) returns (ClientMessageResult);
  rpc OnTopicActive(TopicActivity) returns (google.protobuf.Empty);  // first subscriber
  rpc OnTopicIdle(TopicActivity) returns (google.protobuf.Empty);    // last one left, after idle-linger
}
```

Built (all six): the decisions wait up to `hooks.timeout`, the first reject wins, rewrites compose in bean
order, a timeout or failure allows unless `hooks.fail-closed`; an `Empty` reply to `OnConnect` is the default
allow, and `UNIMPLEMENTED` is cached per method. See `docs/push.md`, Hooks.

### 9.6 Asking who is listening

1. `HasSubscribers(topics[])`: one existence check per topic; use before producing expensive
   events.
2. `GetSubscribers(topic)`: count plus a page of `{connectionId, principal, deviceId, since}`
   when the namespace has `presence: true`.
Built: 1 and 2 (`GetSubscribers` lists members across instances only for a `presence: true`
namespace; otherwise it counts this instance's subscribers). Not built: 3.

3. `WatchTopicActivity(filter)` server stream, or the `OnTopicActive` / `OnTopicIdle` hooks:
   produce on demand. Transitions are debounced by `idle-linger` so reconnects do not flap.

## 10. GraphQL subscriptions on the engine

Today a subscription binds one client to one upstream stream on one instance. With push:

- any RPC with `(push.topic)` gets a subscription field whose fetcher subscribes to the engine
  (argument → topic), so multi-instance works and the upstream is shared;
- explicit-publish topics get a schema too when the payload type is declared, e.g.
  `Subscription.orderEvents(orderId: ID!): OrderEvent`; projection and field selection work on
  the typed payload;
- resume: graphql-transport-ws has no `Last-Event-ID`, so `extensions.lastEventId` on the
  `subscribe` frame and `extensions.eventId` on `next` carry it;
- federation of subscriptions is out of scope.

This lives in `shiguchi-graphql` behind `@ConditionalOnBean(PushBroker)`; no new module,
no hard dependency.

## 11. Connection registry and targeted delivery

Every connection gets a ULID `connectionId`, registered in the store with a TTL refreshed by
the keepalive: `conn:{id} → {instance, principal, deviceId, namespaces, since}` plus an index
`user:{sub} → set<connId>`. Instance identity is `cursus.instance-id` (default: hostname) with the
instance's own gRPC address, reachable through `discovery:///` or directly.

`Send{target, payload, require_ack}`: lookup → owning instances → local write or internal
`PushPeer.Deliver` call (same authentication as `gateway-traffic`) → `DeliveryStatus` per
target (`DELIVERED`, `NOT_CONNECTED`, `TIMEOUT`). This is Pushy's direct path.

The cheap alternative that covers most needs is a `user:{sub}` topic with `PRINCIPAL_MATCH`.
The registry earns its keep only for delivery status, device targeting and presence.

Built: the registry rows (store `register` / `refresh` / `unregister`, TTL twice the keepalive, one
rewrite tick per instance, off the subscribe path), `cursus.instance-id`, `cursus.peer.address` (carried
on each row) and the cluster-wide admin read; `Send` with `PushPeer.Deliver` / `Disconnect`. The peer
channel is a gRPC service on the native port (never in the HTTP surfaces), guarded by a shared
`cursus.peer.token` in `x-push-peer-token`; no token means local-only `Send` and a 409 for a remote
`DELETE`. A direct event's topic is `<send.namespace>:<connectionId>`, its id is shared by every
matched connection, and `require_ack` is answered by the ordinary ack paths (WS frame, `Ack`, `POST
/ack`) routed to the connection instead of a session cursor. SSE direct events carry no `id:` line
and cannot be acknowledged. Still a plan: the `user:{sub}` targeting.

Traps: a reconnect registers the new connection before the old TTL expires, so deliver to both
and dedupe on an idempotency key; an instance death leaves ghosts until the TTL, so keep the TTL
at about twice the keepalive; registry writes are on the connect hot path, so pipeline them.

## 12. Administration

### 12.1 Actuator endpoint `gateway-push`

Same shape as `gateway-traffic` and `gateway-cache`: a `PushReport` in the push module, an
`@Endpoint` wrapper per Boot line. Cluster-wide with a Redis or Postgres store (each instance
writes a `stats:{instance}` record with a TTL, the endpoint aggregates); local-only with
`store: NONE`, and the UI says so.

| Op | Path | Does |
| --- | --- | --- |
| read | `/actuator/gateway-push` | overview: connections by transport/namespace/instance, subscriptions, msg/s in/out, drops, engine health, history bytes |
| read | `.../namespaces` | effective config + counters |
| read | `.../topics?q=&page=` | subscriber count, history length, last id/time, active shared upstream |
| read | `.../topics/{t}/subscribers` | presence page |
| read | `.../connections?principal=&topic=&instance=` | list |
| write | `DELETE .../connections/{id}` | disconnect with reason |
| read | `.../messages?topic=&from=&to=&after=&attr=k:v&type=&publisher=&q=&limit=` | hot store when the range fits, else the archive; `q` only where the backend declares full text |
| read | `.../messages/{topic}/{id}` | event rendered through the proto JSON mapper, `payload-visibility` applied |
| read | `.../messages/{topic}/{id}/deliveries` | per-subscriber status when the namespace has `delivery-log: true`; delivered and dropped counts always |
| write | `POST .../messages/{topic}/{id}/republish` `{target?, dryRun}` | new event, new id, `attributes.origin_id`, `republished_by`, `reason`; clients dedupe on `origin_id` |
| write | `POST .../messages/republish` `{query, target?, dryRun}` | bulk, asynchronous job, throttled by `admin.republish.rate`, capped by `max-batch`, progress and cancel |
| write | `POST .../sessions/{key}/seek` `{topic, position}` | persistent session: move the cursor, the engine redelivers (Pub/Sub `seek`) |
| read/write | `.../groups`, `.../groups/{g}` , `POST .../groups/{g}/reclaim` | members, lag, pending per group; force a reclaim |
| write | `DELETE .../messages/{topic}/{id}` | tombstone in store and archive (redaction); history skips it |
| read/write | `.../dlq`, `POST .../dlq/{id}/retry`, `DELETE .../dlq/{id}` | events rejected on mapping, authorization, size or schema |
| read | `.../messages/export?…` | NDJSON stream of a query |
| read | `.../audit` | every admin write: principal, action, arguments, result, time; also published on `_sys:audit` |

Republish never mutates history. "Edit then republish" is a new event linked by `origin_id`.
Targeted republish bypasses fan-out but not the per-subscriber authorization filter. The UI
defaults to a dry run.

### 12.2 Search capability per backend

| Backend | Range | Attributes | Full text | Delivery log |
| --- | --- | --- | --- | --- |
| Redis Streams | id/time | scan within range, bounded by `max-scan` | no | counters only |
| Postgres | yes | GIN on `attrs jsonb` | `tsvector` over the JSON payload | table |
| JDBC | yes | `LIKE` over the JSON text (exact key and value) | payload substring `LIKE`, no index | table |
| Kafka | `offsetsForTimes` + scan | scan | no | no |

The archive is fed from the broker by one leased archiver per cluster, so publish latency is
unaffected. `store: POSTGRES` can double as the archive: one table, two retention windows.

### 12.3 Console: Push tab

A tab in the existing console (React 19, zustand, Tailwind, Monaco). It appears only when the
`gateway-push` endpoint answers, so `shiguchi-ui` keeps no compile dependency on the push module.
Live counters come from an internal `_sys:push-stats` topic the UI subscribes to over SSE.

1. Overview: counters, inline SVG sparklines, per-instance table, engine health.
2. Topics: search, counts, Tail (live events through the existing streaming response viewer).
3. Connections: filter, disconnect.
4. Messages: query builder, results, detail drawer (payload, attributes, deliveries), actions
   republish / republish-to / tombstone / copy as `grpcurl` (reuses `exportCommand`), export.
   Bulk: query or range → dry-run count → confirm → job panel.
5. Publish: form editor from the `PushEvent` schema, test send, delivery count.
6. Sessions and groups: cursor position, members, lag, queued, in flight, seek, reclaim.
7. DLQ and Audit lists.

Payload display follows `admin.payload-visibility`; the default `METADATA` makes seeing user
data a decision, not a default.

### 12.4 Metrics

Micrometer, with a Grafana dashboard JSON in `samples/observability` like the existing ones:
`shiguchi_cursus_connections{transport,namespace}`, `_subscriptions`,
`_events_published_total{namespace,source}`, `_events_delivered_total{transport}`,
`_events_dropped_total{reason}`, `_delivery_latency_seconds` (publish → socket write,
`percentiles-histogram` on), `_history_bytes`, `_replays_total`, `_engine_errors_total`,
`_rebalance_closes_total`. The HPA target is `_connections`.

## 13. Clients and the command line

The API is protos and standard transports, so clients come cheaply:

- `push.proto` ships in `shiguchi-proto` (Maven today). Publishing it to the Buf Schema
  Registry gives generated SDKs for npm, Go, PyPI, Maven, Swift and Kotlin with one `buf push`
  job on tag. Without BSR, `buf generate` in CI and self-published packages; more upkeep.
- Browsers need no SDK: `EventSource` has `Last-Event-ID` built in. For the WebSocket protocol
  there is a small helper, `@appiaworks/shiguchi-cursus-client` (`shiguchi-cursus-client-js/`: reconnect with
  jitter, resubscribe from the last id, token refresh, gap callbacks). Built and experimental;
  published to npm with each release.
- Java publishers get `shiguchi-cursus-client` (`PushClient.publish(topic, msg)`, batching,
  retry).

Step one is recipes in `docs/push.md`, no code:

```bash
curl -N -H "Authorization: Bearer $T" -H "Last-Event-ID: $ID" "https://gw/push/subscribe?topic=orders:123"
grpcurl -H "authorization: Bearer $T" -d '{"topic":"orders:123","payload":{...}}' gw:9090 dev.appiaworks.shiguchi.cursus.PushService/Publish
grpcurl -d '{"topics":["orders:123"]}' gw:9090 dev.appiaworks.shiguchi.cursus.PushService/Subscribe
websocat -H "Authorization: Bearer $T" wss://gw/push/ws
curl -u admin:... "https://gw/actuator/gateway-push/messages?topic=orders:*"
```

Step two is `cursus` (`shiguchi-cursus-cli`, Java + picocli, fat jar plus a `jbang` one-liner;
GraalVM native only if start-up time matters, since native builds are heavy on the CI pods):

Short verbs are the primary names; the long forms are picocli aliases.

| Command | Alias of | Does |
| --- | --- | --- |
| `cursus sub 'orders:*' [--after ID] [--mode live] [--group g] [--ws\|--sse\|--grpc]` | `subscribe`, `tail` | subscribe, NDJSON to stdout, auto-resume, acks on a persistent session |
| `cursus pub orders:123 --type acme.OrderEvent -d @file.json` (or NDJSON on stdin) | `publish` | typed through reflection, batch, `--fire-and-forget` |
| `cursus send --user alice -d ...` | | targeted delivery |
| `cursus ls [topics\|ns\|conns\|sessions\|groups]` | `topics`, `connections`, ... | admin reads, default `topics` |
| `cursus find --topic 'orders:*' --from 1h --attr tenant=acme` | `search` | admin search, NDJSON |
| `cursus repub --id ... \| --query ... --dry-run` | `republish` | admin republish with job progress |
| `cursus rm orders:123/ID` | `tombstone` | redact one event |
| `cursus dlq ls\|retry`, `cursus seek --session k \| --group g --to ID` | | |
| `cursus bench --publishers 4 --subscribers 200 --rate 1000` | | load and latency histogram |

Authentication: `--token`, `CURSUS_TOKEN`, `--token-cmd`, client credentials; profiles in
`~/.cursus/config.yml`. NDJSON output by default, `--pretty` for people, exit codes for scripts.

## 14. Sequencing

| Slice | Content |
| --- | --- |
| 1 | foundation (SPIs, topic model, namespaces), SSE, MEMORY engine, explicit publish (gRPC + HTTP), subscribe/publish authorization, `docs/push.md` with recipes |
| 2 | Redis broker + store, `AT_LEAST_ONCE` transient sessions, `Last-Event-ID`, connection lifecycle and drain, metrics, sample, bench numbers |
| 3 | WebSocket (JSON + proto), gRPC `Subscribe` bidi, ack plumbing |
| 4 | `gateway-push` endpoint, Push tab, Grafana dashboard; archive SPI, Postgres store + archive, Messages view, republish, audit |
| 5 | declarative `(push.topic)`, `SHARED_UPSTREAM`, Kafka and gRPC-stream sources, DLQ, topic-activity hooks, JDBC module |
| 6 | GraphQL subscriptions on the engine |
| 7 | registry, targeted `Send`, presence, `PushHooks`, persistent sessions incl. consumer groups |
| 8 | `cursus`, BSR SDKs, browser helper |

Each slice is one or more squash-merged PRs on `main`, Boot 3 and Boot 4 mirrored, README and
docs updated in the same PR.

## 15. Open decisions

- Payload as `google.protobuf.Any` (lean yes: typed JSON for free) versus bytes + type URL.
- Redis broker frames carry the payload or only the id (decide from slice-2 measurements).
- Browser WebSocket auth default: cookie when a UI session exists, `connect` frame otherwise. **Decided in BFF G4:** the cookie when the sign-in module has signed the user in; the `connect` frame otherwise.
- Admin tab inside `shiguchi-ui` (lean yes, feature-detected) versus a separate `grpc-push-ui`.
- BSR account: public or private, and its cost.
- Test infrastructure: no Docker on the development machine, so Testcontainers tests run on CI
  only; local runs use the JDBC engine on H2 and `spring-kafka-test`'s embedded broker.

## 16. Sources

- Netflix, Zuul Push: [InfoQ, 2018](https://www.infoq.com/news/2018/07/zuul-push-messaging/),
  [QCon NY slides](https://archive.qconnewyork.com/system/files/presentation-slides/final_qcon_ny_-_zuul_push_presentation.pdf),
  [Zuul wiki, Push Messaging](https://github.com/Netflix/zuul/wiki/Push-Messaging)
- Netflix, Pushy: [Pushy to the Limit, 2024](https://netflixtechblog.com/pushy-to-the-limit-evolving-netflixs-websocket-proxy-for-the-future-b468bc0ff658),
  [InfoQ summary](https://www.infoq.com/news/2024/09/netflix-pushy-websocket)
- [GRIP protocol](https://pushpin.org/docs/protocols/grip/),
  [Fanout: an HTTP reverse proxy for realtime](https://blog.fanout.io/2013/04/09/an-http-reverse-proxy-for-realtime/)
- [Mercure specification](https://mercure.rocks/spec)
- [nchan](https://nchan.io/)
- [Centrifugo comparisons](https://centrifugal.dev/docs/getting-started/comparisons)
- [AWS API Gateway WebSocket APIs](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-websocket-api-overview.html)
- [Reddit's new real-time service, 2021](https://www.reddit.com/r/RedditEng/comments/pfgz4r/reddits_new_realtime_service)
- [Spring SSE + Redis sample](https://github.com/vpavic/samples-spring-sse-redis)
