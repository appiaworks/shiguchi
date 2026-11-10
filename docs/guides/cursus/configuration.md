# Cursus: configuration

Part of the [cursus guide](index.md).

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `shiguchi.cursus.enabled` | `false` | Mount the endpoints |
| `shiguchi.cursus.path` | `/push` | Base path: `<path>/subscribe`, `<path>/publish`, `<path>/topics/{topic}` |
| `shiguchi.cursus.broker` / `store` | `MEMORY` | `MEMORY` (this instance), `REDIS` (shared; needs `spring-boot-starter-data-redis-reactive`) or `POSTGRES` (shared; needs `spring-boot-starter-data-r2dbc` + `r2dbc-postgresql`) or `JDBC` (shared, polling; needs `spring-jdbc` and a driver, and `broker: JDBC` needs `store: JDBC`; see [the JDBC engine](engines.md#the-jdbc-engine)) |
| `shiguchi.cursus.jdbc.*` | `""` / detected / `push_event` / `true` / `1m` / `16` / `500ms` | The JDBC engine: `data-source` (bean name; empty: the single `DataSource`), `dialect` (`H2`, `POSTGRES`, `MYSQL`, `SQLSERVER`, `ORACLE`, `GENERIC`; empty: detected), `table`, `create-schema`, `sweep-interval`, `trim-every`, `poll-interval` (the JDBC broker's poll interval; at least `50ms`) |
| `shiguchi.cursus.redis.key-prefix` | `shiguchi:cursus:` | Prefix of every key and channel |
| `shiguchi.cursus.postgres.table` / `channel` | `push_event` / `shiguchi_cursus` | The history table and the notification channel |
| `shiguchi.cursus.postgres.create-schema` | `true` | Run `CREATE TABLE IF NOT EXISTS` at startup |
| `shiguchi.cursus.postgres.sweep-interval` / `trim-every` | `1m` / `16` | How often expired rows are deleted; appends per topic between trims to `history-size` |
| `shiguchi.cursus.postgres.notify-max-bytes` | `7900` | Largest event sent inline in a notification; above it the event is announced by id |
| `shiguchi.cursus.publish.http-enabled` | `true` | Serve `POST <path>/publish` |
| `shiguchi.cursus.session-check-interval` | `30s` | How often a connection opened with the session cookie checks that its session is still active (at least `5s`); the longest it outlives a logout on another instance |
| `shiguchi.cursus.ws.enabled` | `true` | Serve `GET <path>/ws` |
| `shiguchi.cursus.ws.allowed-origins` | `[]` | Browser origins allowed besides the gateway's own; `*` for any |
| `shiguchi.cursus.admin.role` | `` | Role the `gateway-push` writes need; empty: any authenticated caller |
| `shiguchi.cursus.admin.payload-visibility` | `METADATA` | What the message views show of an archived event: `NONE`, `METADATA` (no payload), `FULL` |
| `shiguchi.cursus.admin.republish.max-batch` / `rate-per-second` / `namespaces` | `10000` / `500` / `[]` | Bulk republish: most events per job, events per second, namespaces a republish may target (empty: any) |
| `shiguchi.cursus.archive.retention` | `0` | How long every event stays searchable and republishable; `0` turns the archive off. Needs store `POSTGRES` or `JDBC` (its table), `REDIS` (Streams shared by every instance) or `MEMORY` (a per-instance ring), or a `PushArchive` bean |
| `shiguchi.cursus.archive.memory-max-events` | `10000` | Most events the `MEMORY` archive keeps per instance |
| `shiguchi.cursus.archive.max-scan` | `10000` | Most entries one search reads from the `REDIS` archive; filters run over what was read |
| `shiguchi.cursus.upstream.idle-linger` | `10s` | How long an upstream-backed topic keeps its stream after its last subscriber left |
| `shiguchi.cursus.upstream.lease-ttl` | `15s` | How long an instance owns a topic's stream without renewal (renewed at a third of it) |
| `shiguchi.cursus.upstream.retry-backoff` / `max-retry-backoff` | `1s` / `30s` | First and largest wait before reopening a stream that ended or failed |
| `shiguchi.cursus.upstream.instance-id` | `` | This instance's name in the leases and the hook calls; empty: host name plus a random suffix |
| `shiguchi.cursus.sources[i].name` / `type` | `<type>-<i>` / `KAFKA` | A source: `KAFKA` or `GRPC_STREAM` |
| `shiguchi.cursus.sources[i].topic-template` | | `namespace:template` over the record's variables (Kafka: `{key}`, `{topic}`, `{partition}`, `{header.<name>}`; a stream: the message's scalar fields); a fixed `namespace:name` is allowed |
| `shiguchi.cursus.sources[i].payload-type` / `payload-format` | `` / `PROTO` | Kafka: the message type of the values, in protobuf or `JSON`, so events are typed |
| `shiguchi.cursus.sources[i].key-field` / `attributes` / `shared` | `` / `{}` / `true` | A stream's field that becomes the event key; attributes every event carries; one instance consumes (lease) or every one |
| `shiguchi.cursus.sources[i].kafka.*` | | `bootstrap-servers`, `topics`, `start` (`LATEST`, `EARLIEST`), `poll-timeout` (`500ms`), `properties` (any consumer property) |
| `shiguchi.cursus.sources[i].grpc.*` | | `service`, `method` (server-streaming), `request` (JSON) |
| `shiguchi.cursus.hooks.grpc-service` | `` | A served service implementing `shiguchi.cursus.v1.PushHooks` (any subset of its methods) |
| `shiguchi.cursus.hooks.idle-linger` / `timeout` | `10s` / `5s` | How long after the last subscriber left the idle hook fires; how long a hook call may take |
| `shiguchi.cursus.hooks.fail-closed` | `false` | A decision hook that times out or fails: `false` allows the client, `true` rejects it |
| `shiguchi.cursus.dlq.max-entries` | `1000` | Most dead-lettered records kept per instance |
| `shiguchi.cursus.grpc.enabled` | `true` | Host `shiguchi.cursus.v1.PushService`: native gRPC, gRPC-Web, `<path>/v1/publish`, `<path>/v1/subscribe` |
| `shiguchi.cursus.grpc.service-name` | `push` | Its name in the service list, routes report and metrics |
| `shiguchi.cursus.namespaces.<name>.qos` | `AT_MOST_ONCE` | `AT_MOST_ONCE` or `AT_LEAST_ONCE` |
| `…namespaces.<name>.history-size` / `history-ttl` | `0` / `1h` | Events kept per topic, and for how long |
| `…namespaces.<name>.session-expiry` | `0` | Above zero: a persistent session per subscriber, remembered that long since its last ack or resume |
| `…namespaces.<name>.session-key` / `max-queued` | `PRINCIPAL` / `0` | What names the session (`PRINCIPAL`, `CLIENT`, `GROUP` for competing consumers); most events a resuming session may have pending (`0`: `history-size`) |
| `…namespaces.<name>.ack-deadline` / `max-deliveries` / `max-inflight` | `30s` / `5` / `64` | Consumer groups: how long a member may hold an event unacknowledged, deliveries before the dead-letter queue, unacknowledged events per member |
| `…namespaces.<name>.ordering` | `NONE` | Consumer groups: `NONE`, `KEY` (per-key order, a key at one member; `MEMORY`/`POSTGRES`/`JDBC` stores) or `STRICT` (one active member) |
| `…namespaces.<name>.client-queue-size` | `256` | Events queued for a connection that is not reading |
| `…namespaces.<name>.auth` / `roles` / `claim` | `NONE` / `[]` / `push.subscribe` | Who may subscribe |
| `…namespaces.<name>.publish-auth` / `publish-roles` / `publish-claim` | `AUTHENTICATED` / `[]` / `push.publish` | Who may publish |
| `…namespaces.<name>.payload-type` | `` | A served service's message (full name) every event carries; gives the GraphQL field a typed `payload` |
| `…namespaces.<name>.graphql` / `graphql-field` | `true` / `` | Give the namespace a `Subscription` field, and its name (default `<name>Events`) |
| `…namespaces.<name>.delivery-log` | `false` | Record every delivery and drop of every event to every connection in the store (`MEMORY`, `POSTGRES`, `JDBC`), for `messages/{id}/deliveries` across the cluster |
| `…namespaces.<name>.client-publish` | `false` | Allow `POST <path>/topics/{topic}` from subscribers |
| `shiguchi.cursus.connection.max-lifetime` / `lifetime-jitter` | `30m` / `3m` | Close and let the client reconnect; `0` disables |
| `shiguchi.cursus.connection.keepalive` | `15s` | Idle keepalive comment; `0` disables |
| `shiguchi.cursus.connection.max-subscriptions` | `255` | Topics per connection |
| `shiguchi.cursus.connection.max-message-bytes` | `65536` | Largest payload |

Extension points: a `PushBroker` or `PushStore` bean of your own replaces the in-memory one.
Both SPIs are `@ApiStatus(EXPERIMENTAL)`.
