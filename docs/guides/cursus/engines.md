# Cursus: engines

Part of the [cursus guide](index.md).

## Engines

The in-memory engine is per instance: a subscriber on instance A does not see an event published
through instance B, and history is lost on restart. Fine for one instance and for tests.

The Redis engine shares both across instances:

```yaml
shiguchi:
  cursus:
    broker: REDIS
    store: REDIS
    redis:
      key-prefix: shiguchi:cursus:
spring:
  data:
    redis:
      host: redis
```

It needs `spring-boot-starter-data-redis-reactive` on the classpath and Boot's standard
`spring.data.redis.*` settings; the gateway uses Boot's `ReactiveStringRedisTemplate`. Startup
fails with a plain message when `REDIS` is set without it.

- **Broker**: one Pub/Sub channel per topic (`<prefix>ch:<topic>`), one subscription per topic per
  instance multiplexed on one connection, opened with the first local subscriber and closed with
  the last. The publishing instance gets its events back through Redis like any other, so every
  instance delivers in the same order. Pub/Sub is at most once: an instance disconnected from
  Redis at that moment misses the event.
- **Store**: one Stream per topic (`<prefix>stream:<topic>`), `XADD MAXLEN ~ history-size` and the
  key's `EXPIRE` refreshed to `history-ttl`, one script per append. The Stream id
  (`millis-seq`) is the event id and orders events across instances; a topic with no history
  gets a local id of the same shape. Memory is bounded by configuration: at most about
  `history-size` entries per topic, gone `history-ttl` after the last publish.
- `broker` and `store` are chosen separately; `broker: REDIS, store: MEMORY` is legal but gives
  each instance its own history, which is rarely what you want.

> **RESP2 needs Lettuce 7.7.0 or later.** Lettuce 7.1.0–7.6.x (Spring Boot 4.1.2 manages 7.5.2)
> merges the entries of a multi-entry stream read into one malformed message when the connection
> speaks RESP2 ([redis/lettuce#3800](https://github.com/redis/lettuce/issues/3800)). That is Redis
> older than 6, or a proxy or client setting that forces RESP2. A consumer-group claim then returns
> one event instead of several, and the rest are redelivered only after their ack deadline. On
> Redis 6+, where Lettuce negotiates RESP3, nothing changes. Otherwise raise Lettuce: on Maven, or
> Gradle with the dependency-management plugin, set `lettuce.version=7.7.0.RELEASE`, or declare
> `io.lettuce:lettuce-core:7.7.0.RELEASE` or later.

The Postgres engine does the same with a database most teams already run:

```yaml
shiguchi:
  cursus:
    broker: POSTGRES
    store: POSTGRES
    postgres:
      table: push_event            # created at startup unless create-schema: false
      channel: shiguchi_cursus
spring:
  r2dbc:
    url: r2dbc:postgresql://db:5432/app
    username: gateway
    password: ${DB_PASSWORD}
```

It needs `spring-boot-starter-data-r2dbc` and `org.postgresql:r2dbc-postgresql` on the classpath
and Boot's `spring.r2dbc.*` settings; the gateway uses Boot's `DatabaseClient` and
`ConnectionFactory`. Startup fails with a plain message when `POSTGRES` is set without them.

- **Broker**: `LISTEN/NOTIFY` on one channel, over one dedicated connection per instance, opened
  with the first local subscriber and kept for the instance's life (reconnecting with backoff).
  A publish is one `pg_notify`; the publishing instance receives its own events back like any
  other, so every instance delivers in the same order. Postgres caps a notification at 8000
  bytes: an event that fits (`notify-max-bytes`, default 7900) travels inline, a larger one is
  announced by id and read from the table, which needs the namespace to keep a history (a larger
  event on a topic without history is refused with 413). At most once, like Pub/Sub.
- **Store**: one table, `push_event` by default, created with `CREATE TABLE IF NOT EXISTS` at
  startup (`create-schema: false` leaves that to you; the statements are in
  `PostgresPushSchema`). An append is one `INSERT ... RETURNING id`; the `BIGSERIAL` id is the
  event id and orders events across instances. A topic's history is trimmed to `history-size`
  every `trim-every` appends (default 16), and rows past `history-ttl` are deleted every
  `sweep-interval` (default 1m); expired rows are never replayed in between. A topic with no
  history gets a local numeric id nobody resumes from.
- Expect 1–5 ms from publish to delivery on a database on the same network, against under a
  millisecond with Redis; the numbers are in [the benchmarks](../../../shiguchi-benchmarks/README.md#push-jdk-25-windows-11-loopback-2026-10-08).

Mixing is allowed (`broker: REDIS, store: POSTGRES`); with `broker: POSTGRES` and another store,
events above the notification cap cannot be read back and are refused.

### The JDBC engine

The JDBC engine puts the store and the archive on any SQL database through a plain
`javax.sql.DataSource`, which is the Docker-free way to run them: H2 in memory is a complete
engine. It is the Postgres store's model (one history table that doubles as the archive, the
same sessions, consumer groups, delivery log, leases and connection registry) in portable SQL.

```yaml
shiguchi:
  cursus:
    broker: JDBC                   # polls the store's table; one instance can use MEMORY, a cluster needs JDBC
    store: JDBC
    jdbc:
      table: push_event            # created at startup unless create-schema: false
      poll-interval: 500ms         # the broker's latency; at least 50ms
      # data-source: pushDb        # bean name; default: the application's single DataSource
      # dialect: POSTGRES          # forced instead of detected: H2 | POSTGRES | MYSQL | SQLSERVER | ORACLE | GENERIC
spring:
  datasource:
    url: jdbc:h2:mem:push;DB_CLOSE_DELAY=-1
```

It needs `org.springframework:spring-jdbc` (`spring-boot-starter-jdbc`) and your database's
driver on the classpath. Startup fails with a plain message when `JDBC` is set without them, or
without exactly one `DataSource` bean (name one with `jdbc.data-source`). `broker: JDBC` needs
`store: JDBC` too: it reads the table the store writes. Boot's `DataSource` auto-configuration
backs off when an R2DBC `ConnectionFactory` bean is present (an application that has both
R2DBC and JDBC on the classpath), so declare the `DataSource` as a bean of your own in that case.

- **The broker polls; latency is the poll interval.** There is no `LISTEN/NOTIFY` equivalent in
  portable SQL, so every `poll-interval` (default `500ms`, at least `50ms`) each instance reads
  the rows past the last id it saw (`id > last ORDER BY id`, up to 1000 per round, another round
  at once while a round comes back full) and hands them to its local subscribers. A publish does
  not notify anyone: the row the store wrote is the message, and the publishing instance reads it
  back like every other, so a subscriber on the same instance also sees the event after up to one
  poll interval, and every instance sees one order. The poller starts at the head of the table,
  so history is never replayed as live events (a subscriber resumes from the store with
  `Last-Event-ID`). An instance with no subscribers only reads the head id. Delivery is at most
  once: a round that fails (the database is down) is logged, counted and retried from the same
  id, and what was appended meanwhile is delivered when the database is back. Events that have
  no row, those of a namespace with `history-size: 0` while `archive.retention` is `0`, reach
  the subscribers of the publishing instance at once and no other instance: give a namespace
  that must fan out across instances a history or turn the archive on. Metrics:
  `shiguchi.cursus.jdbc.polls{outcome=ok|error}` and `shiguchi.cursus.jdbc.poll_lag` (the
  time between an event's creation and its delivery by the poller). Pick `poll-interval` by what
  latency you can afford: each instance runs one cheap query per interval.
- **Blocking JDBC, off the event loop.** Every store call runs on Reactor's `boundedElastic`
  scheduler, so nothing blocks a Netty thread; size the connection pool for your publish rate.
- **Ids.** An append takes the next id from a one-row counter table (`<table>_seq`) and inserts
  the event in the same transaction. The counter's row lock is held until commit, so ids commit
  in order across every instance: a reader that asks for `id > last` never skips a row. A topic
  with no history gets a local numeric id nobody resumes from, as on Postgres.
- **Tables.** `<table>` (events, and the archive when `archive.retention` is above zero),
  `_seq`, `_lease`, `_delivery`, `_session`, `_group`, `_connection`, `_trim`. Every time is epoch
  milliseconds in a `BIGINT`, never a database timestamp, so no dialect can drift by a time zone.
  The statements are in `JdbcPushSchema`, per dialect, for administrators who run them
  themselves (`create-schema: false`); where the database has no `IF NOT EXISTS` the runner
  ignores the "already exists" error instead. Names (topic, key, group, member) are stored in
  `VARCHAR(255)`.
- **Consumer groups claim by optimistic update**, not `FOR UPDATE SKIP LOCKED` (which is not
  portable). A member reads candidate rows, then takes each with an `UPDATE ... WHERE claimed_at =
  <the value it read>`; the row count says whether it won, so of two members racing for a row
  exactly one gets it. `ordering: KEY` works the same way and checks, after winning a row, that no
  other member holds a sibling with the same key live; two members racing for siblings both hand
  their claim back and retry on the next claim rather than break the order.
- **Archive search** is range (topic, id, time), attributes (`LIKE` over the stored JSON text, an
  exact key and value pair) and payload text (a case-insensitive substring `LIKE`, a table scan:
  there is no full-text index). It is meant for the retention windows an operator searches by
  hand; use the Postgres engine when you need word search over a large archive.
- `trim-every` and `sweep-interval` behave as on Postgres. `poll-interval` is read by the JDBC
  broker only.
- **Cost.** On an in-memory H2 (JDK 25, loopback, 50 ms poll interval) an append through
  `PushPublisher.publish` is about 40 µs, and an HTTP publish reaches an SSE subscriber in about
  51 ms, which is the poll interval: the engine is as fast as it is polled. See
  [the benchmarks](../../../shiguchi-benchmarks/README.md#push-jdk-25-windows-11-loopback-2026-10-08).

| Database | Dialect | Status |
| --- | --- | --- |
| H2 (native mode) | `H2` | tested in CI |
| PostgreSQL over pgjdbc | `POSTGRES` | tested in CI (against an embedded Postgres) |
| MySQL, MariaDB | `MYSQL` | best effort, not run in CI |
| SQL Server | `SQLSERVER` | best effort, not run in CI |
| Oracle | `ORACLE` | best effort, not run in CI |
| anything else | `GENERIC` | standard SQL (`OFFSET ... FETCH`), best effort |

Best effort means the SQL is written to be portable and the dialect differences (column types,
paging, "already exists" errors) live in one file, `JdbcDialect`, but no CI job runs those
databases (CI has no Docker, and the project does not add Testcontainers). If one misbehaves,
force a dialect or report the statement that failed.

What Redis costs, measured in [the benchmarks](../../../shiguchi-benchmarks/README.md#push-jdk-25-windows-11-loopback-2026-10-08).
