# Cursus: command line and operations

Part of the [cursus guide](index.md).

## Command line

`cursus` ([push-cli.md](../../reference/push-cli.md)) subscribes, publishes, lists and searches from a terminal, with
NDJSON output for scripts:

```bash
cursus sub 'orders:*' --after "$ID"
cursus pub orders:123 -d '{"status":"SHIPPED"}'
cursus ls topics && cursus find --topic 'orders:*' --from 1h
```

## Operations

Metrics (Micrometer): `shiguchi.cursus.connections{transport=sse|ws|grpc}` and `.subscriptions` (gauges),
`.events.published`, `.events.delivered`, `.events.dropped{reason}` (counters), `.publish`
(timer), `.upstreams` and `.sources` (gauges: streams and sources this instance owns),
`.sources.events{source, outcome}`, `.hooks{hook, outcome}`, `.sessions.acks`,
`.groups.deliveries{outcome}`, `.groups.acks` (counters), `.dlq` (gauge). Scale on connections, not CPU.

Startup fails with a plain message when a namespace is misconfigured: `AT_LEAST_ONCE` without
history, `ROLE` without roles, `AUTHORIZER` without a `GatewayAuthorizer` bean of your own,
`session-expiry` above zero without `AT_LEAST_ONCE`, a store that keeps no sessions, or an
anonymous namespace with `session-key: PRINCIPAL`, `max-queued` above `history-size`, a
`GROUP` namespace without `AT_LEAST_ONCE`, `session-expiry` or a valid `ack-deadline`, an invalid
namespace name.

On shutdown every open stream ends with `event: close` and `{"reason":"shutdown"}` before the
server stops accepting, so clients reconnect to a live instance at once. `EventSource` does that
on its own.

## Administration

The `gateway-push` actuator endpoint shows this instance's push surface and lets an operator act
on it. Expose it like any actuator endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,gateway-push
shiguchi:
  cursus:
    admin:
      role: PUSH_ADMIN        # optional: the role the writes need
```

| Call | Answers |
| --- | --- |
| `GET /actuator/gateway-push` | overview: connections per transport, subscriptions, active topics, engine, totals and per-second rates of published / delivered / dropped |
| `GET .../namespaces` | each namespace's policy and its live topics and subscribers |
| `GET .../topics?q=` | topics with a subscriber on this instance, most subscribed first, with the history length |
| `GET .../connections?principal=&topic=&transport=` | the open connections, cluster-wide when the store keeps the registry: id, transport, principal, topics, since, `instance`, `deviceId` |
| `DELETE .../connections/{id}` | ends one connection; it gets `event: close` / `{"reason":"admin"}` (or the WebSocket `close` frame) first |
| `POST .../publish` `{"topic": "orders:1", "data": "{\"n\":1}"}` | publishes a test event as the operator, with `from=admin`, bypassing the namespace's publish check |
| `GET .../messages?topic=&from=&to=&after=&before=&attr=k:v,k2:v2&type=&q=&limit=` | the archive (below): events newest first, `topic` exact or a pattern (`orders:*`, `orders`), `from` / `to` ISO instants, `attr` every pair must match, `type` a type URL or `json`, `q` words in the payload, `limit` up to 1000 (default 100); 404 `no_archive` when the archive is off |
| `GET .../messages/{id}?topic=` | one archived event, as much of it as `admin.payload-visibility` allows; the topic is a parameter because a topic name may contain `/` |
| `POST .../messages/{id}/republish` `{"topic", "target"?, "reason"?, "dryRun"?}` | a new event on `target` (the same topic when absent) with the payload, key and type of the original and its attributes plus `origin_id`, `origin_topic`, `republished_by`, `reason`; `dryRun: true` answers without sending |
| `POST .../messages/republish` `{the search fields…, "target"?, "reason"?, "dryRun"?}` | every event the query matches, as a job at `admin.republish.rate-per-second`, at most `max-batch`; `dryRun` (the default) answers `matched` and `capped` |
| `GET .../jobs`, `GET .../jobs/{id}`, `DELETE .../jobs/{id}` | the bulk republish jobs on this instance: state, matched, published, failed; cancel one |
| `DELETE .../messages/{id}?topic=` | tombstone: the payload becomes `null`, attributes and time stay, history skips it, a republish of it is refused (409) |
| `GET .../messages/{id}/deliveries?topic=&limit=` | what happened to one event: `delivered` and `dropped` counts, and for a namespace with `delivery-log: true` the per-connection `log` (connection id, principal, transport, time, outcome) from the store, cluster-wide (`scope: cluster`); otherwise the counts of this instance's recent events only (`scope: instance`) |
| `GET <cursus.path>/export?topic=&from=&to=&after=&before=&attr=&type=&q=&limit=` | the archive query as NDJSON, one event per line as the message views render it, `Content-Disposition: attachment`; `limit` default 10 000, at most 100 000. On the push path, not the actuator (whose layer collects a stream into one body); the same gate as the writes: an authenticated caller with `admin.role` when set; recorded in the audit log |
| `GET .../audit?limit=` | the last writes made through the endpoint on this instance: principal, action, arguments, result |
| `GET .../groups?q=<prefix>&limit=`, `DELETE .../groups/{group}` | consumer groups per topic: pending claims, members, position; forget one |
| `POST .../groups/{group}/seek`, `POST .../sessions/{session}/seek` `{"topic", "to"}` | move a group's position or a session's cursor to an id, `first` or `head` (404 for an unknown group or session); audited |
| `GET .../sessions?q=<prefix>&limit=`, `DELETE .../sessions/{session}` | persistent sessions' cursors (session, topic, cursor, expiry) whose name starts with `q`; forget one, so it starts at the head next time |
| `GET .../sources` | the sources: name, kind, `shared`, state (`owner`, `waiting`, `running`), published and failed counts, last event, last error |
| `GET .../dlq?q=<source>&limit=`, `GET .../dlq/{id}` | the dead-letter queue on this instance, newest first: source, reason, intended topic, size, type; key and attributes under `METADATA` visibility, the payload under `FULL` |
| `POST .../dlq/{id}/retry` `{"topic"?}` | publishes the record again, to its intended topic or the one given (needed when mapping failed), stamped `retried_by` and `dlq_id`; the entry leaves the queue once published |
| `DELETE .../dlq/{id}` | drops the entry |

Reads follow the actuator's own exposure and the gateway's security mode. Writes need an
authenticated caller (the gateway's chain gates them like `gateway-traffic`'s) and, when
`admin.role` is set, that role (`PUSH_ADMIN`, `ROLE_PUSH_ADMIN` or `SCOPE_PUSH_ADMIN` all
count). The view is per instance: with the Redis engine every instance delivers every event, but
each one reports only its own connections.

The web console gets a **Push** button when the push surface is on: the same views (overview
and namespaces, topics with a live tail over the WebSocket transport, connections with
disconnect, messages with search, detail with its deliveries, republish, tombstone and an
**export NDJSON** button for the current query, a test publish, the sources with the dead-letter
queue and its retry and delete, the audit log) over the endpoint, using the console's auth token
for the writes. The live tail runs on [`@appiaworks/shiguchi-cursus-client`](../../../shiguchi-cursus-client-js/README.md): it
reconnects, resumes from the last id and marks gaps (`overflow`, `reset`) as separate entries.

### Archive: search, republish, tombstone

Every published event can be kept in an archive for `archive.retention`, searchable through the
`messages` calls above and the console's **messages** view, and acted on: republish one (to its
topic or another), republish a query's worth as a throttled job, or tombstone one.

```yaml
shiguchi:
  cursus:
    store: POSTGRES
    archive:
      retention: 30d
    admin:
      payload-visibility: METADATA     # NONE | METADATA | FULL
      republish:
        max-batch: 10000
        rate-per-second: 500
        namespaces: [orders]           # empty: any declared namespace
```

- **Where it lives.** With `store: POSTGRES` the history table is the archive: one row per
  event, two windows (`history-size` / `history-ttl` for the clients, `archive.retention` for
  the operator), the row deleted by the sweeper once it is out of both. Turning the archive on
  adds three indexes (time, a GIN over the attributes, a GIN over a `tsvector` of the payload);
  a topic without history gets a row too, so its events are searchable and travel by id over
  the notification channel when large. The indexes cost about 50 µs per publish on loopback
  (156 → 209 µs on the engine alone; numbers in the benchmarks README).
  With `store: MEMORY` the archive is a per-instance ring of `archive.memory-max-events`. The
  `REDIS` store keeps one archive for the cluster, in Redis itself: a Stream per topic
  (`<key-prefix>archive:stream:<topic>`) holding each event under its own id, a sorted set of the
  topics written (`archive:topics`) and a hash of tombstones per topic (`archive:tomb:<topic>`).
  Each append drops that topic's entries older than `archive.retention` and every key expires
  that long after its last write, so no sweeper runs. A search filters by topic pattern, id
  range, time, attributes and type over at most `archive.max-scan` entries (newest first, so a
  very selective search over a busy topic can return fewer than its `limit`); `q` (full text) is
  not available, and the console hides it. Tombstoning also deletes the event from the live
  history stream. The delivery log stays counters only. Any other store needs a `PushArchive`
  bean of your own (the SPI is `dev.appiaworks.shiguchi.cursus.PushArchive`, `@ApiStatus(EXPERIMENTAL)`).
- **What an operator sees.** `admin.payload-visibility` is `METADATA` by default: topic, id,
  time, size, type, key and attributes, no payload. Seeing user data is a decision: set `FULL`
  for the payload, `NONE` to hide the attributes and key too.
- **Republish never edits history.** A republished event is a new event with a new id, linked
  to the original by `origin_id` and `origin_topic`, stamped `republished_by` (the operator's
  principal) and `reason`; clients that must not act twice dedupe on `origin_id`. The target
  namespace must be declared and, when `admin.republish.namespaces` is set, listed. The console
  and the endpoint default a bulk republish to a dry run; a query matching more than
  `max-batch` is refused until narrowed. Jobs are per instance.
- **Tombstone is redaction.** The payload is replaced by `null` in the archive (and the typed
  payload dropped), the metadata stays so the record of the event remains, and the event leaves
  the history clients resume from: a `Last-Event-ID` resume skips it. On Postgres that is one
  `UPDATE`; on the in-memory engine the history entry is removed.
- **Delivery log.** Every transport counts what it wrote and what it dropped per event, so
  `messages/{id}/deliveries` always answers `delivered` / `dropped` for this instance's recent
  events. A namespace with `delivery-log: true` goes further: one record per event and
  connection (connection id, principal, transport, time, `delivered` or `dropped:queue_full`),
  written to the store in batches (`MEMORY`: a bounded map, `POSTGRES`: the `<table>_delivery`
  table, swept with the event; `REDIS` keeps none, so the setting is refused at startup), and the
  call answers from the store across the cluster. One row per subscriber per event: turn it on for
  the namespaces where "did alice get order 42?" is a question operators ask, not for a ticker.
- **Export.** `GET <cursus.path>/export` streams a query's worth of archived events as NDJSON for
  a replay elsewhere, an incident file or a spreadsheet; `admin.payload-visibility` applies to
  the lines exactly as to the message views. The console's **export NDJSON** button downloads the
  current search.
- **Audit.** Every write through the endpoint (disconnect, publish, republish, bulk republish,
  cancel, tombstone, dead-letter retry and delete) and every export is recorded with the
  principal, the arguments and the result; the last thousand per instance are at
  `GET .../audit`.
