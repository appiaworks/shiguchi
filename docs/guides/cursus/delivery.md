# Cursus: delivery, sessions and presence

Part of the [cursus guide](index.md).

## Delivery guarantees

| `qos` | What it means | Needs |
| --- | --- | --- |
| `AT_MOST_ONCE` (default) | Only while connected. A client that is not reading loses the oldest queued events beyond `client-queue-size`. | nothing |
| `AT_LEAST_ONCE` | Events are kept per topic (`history-size`, `history-ttl`); a client resumes from `Last-Event-ID`. A client that falls behind is cut off with `overflow` and resumes. | `history-size` above 0 |
| `AT_LEAST_ONCE` + `session-expiry` | A persistent session (below): the gateway keeps the subscriber's cursor per topic; it resumes from it without naming a position, acks move it forward, `max-queued` bounds the catch-up. | `history-size` above 0, a store that keeps sessions |

A subscriber that resumes with an id (`Last-Event-ID`, `after`, or the stored cursor of a
persistent session) and has missed events the history no longer holds is told so, once, before
the replay of what is left: the `reset` signal. A subscriber that receives no `reset` may trust
that it missed nothing. The test is: the store knows the highest id it removed from the topic's
history (by `history-size` trim or `history-ttl` expiry), and the resume id is below it. A resume
id equal to it is not a gap (the client saw the last removed event); a redacted (tombstoned)
event is not counted, since redaction is intentional.

The signal has no event id, so it never becomes a resume point, and it carries no `push.origin`,
so echo suppression never drops it. Its frames:

| Transport | Frame |
| --- | --- |
| SSE | `event: reset`, `data: {"topic":"orders:42","reason":"history_gap"}`, no `id:` line |
| WebSocket | `{"type":"reset","topic":"orders:42","reason":"history_gap"}` |
| gRPC | a `PushEvent` with an empty `id` and the attributes `push.signal=reset`, `reason=history_gap` |
| GraphQL | none (like `overflow`, subscribers see only the metric) |

`reason` is `history_gap` (events were removed after the resume id), or `no_history` when the
namespace keeps no history (`AT_MOST_ONCE`, or `history-size` 0) and the client sent a resume id
anyway: the gateway cannot honour it and says so instead of ignoring it, then streams live. The
client should refetch state over REST (or resynchronise however its application does) and keep
reading: the retained events follow on the same stream. Metric
`shiguchi.cursus.resets{namespace, reason}`. Consumer groups have no per-subscriber resume point
and never send it.

What each engine detects:

| Engine | Detected | How |
| --- | --- | --- |
| `MEMORY` | yes | the per-topic ring remembers the id it last trimmed or expired |
| `JDBC` (H2, Postgres, ...) | yes | the table's out-of-history rows, plus a per-topic watermark table `<table>_trim` written before rows are physically deleted |
| `POSTGRES` | yes | the same, in one statement |
| `REDIS` (standalone, Sentinel) | yes | the append script trims exactly and records the last removed id in a per-topic hash `<prefix>trim:<topic>`; when the whole stream key expires (`history-ttl`) the hash's last-appended id stands in, for seven days past the stream's ttl, beyond that no signal |
| `REDIS` Cluster | no | detected at startup; the single-key append with the approximate trim (`MAXLEN ~`) is kept, since the watermark key would need the stream's hash slot. One INFO line is logged |

Redis history trimming is exact (`MAXLEN` without `~`), so a stream holds exactly `history-size` entries; the price is one `XLEN` per append and an `XRANGE` plus `XTRIM` only when over the limit, all inside the one append script. `history-ttl` is enforced per event on Redis 6.2+ (standalone and Sentinel): each append also runs `XTRIM MINID` against the server clock, records the last expired id in the same watermark, and the whole-key `EXPIRE` still drops idle topics. Redis older than 6.2 (a WARN naming the version is logged once at startup) and Redis Cluster have only that whole-key expiry, which every append refreshes, so a busy topic there keeps entries older than `history-ttl`. Events published with a caller-supplied id (`event_id_field`) are not age-trimmed, since such ids need not be timestamps.

On the SQL engines the watermark of a topic is forgotten after seven days without a trim once
the topic has no events left: a resume id older than that, on a topic that went fully empty, gets
no signal.

## Persistent sessions

An `AT_LEAST_ONCE` namespace with `session-expiry` above zero remembers each subscriber:

```yaml
shiguchi:
  cursus:
    namespaces:
      inbox:  { qos: AT_LEAST_ONCE, history-size: 1000, history-ttl: 7d,
                session-expiry: 7d, auth: PRINCIPAL_MATCH }              # session-key PRINCIPAL
      alerts: { qos: AT_LEAST_ONCE, history-size: 500, session-expiry: 1d,
                session-key: CLIENT, max-queued: 100 }                   # ?session= / connect{session}
```

- **The session** is named by `session-key`: `PRINCIPAL` (the authenticated subscriber; the
  namespace's `auth` cannot be `NONE`) or `CLIENT`, a session id the client chooses (`?session=`
  on SSE, `connect{session}` on WebSocket, `SubscribeRequest.session` on gRPC; letters, digits,
  `.`, `_`, `-`), scoped to the principal when there is one (`dev-7@alice`, `dev-7@-`).
- **The cursor** is the last event the session acknowledged on each topic, kept in the store
  (`MEMORY`: this instance, lost on restart; `REDIS`, `POSTGRES`, `JDBC`: shared). A subscription that
  names no position resumes right after the cursor; one that does (`Last-Event-ID`, `after`)
  acknowledges that position first. A new session starts at the topic's head: nothing published
  before it is replayed. Events the subscriber missed while away are replayed on reconnect, as
  far as the history keeps them (`history-size`, `history-ttl`); the history is the queue. When
  the stored cursor is older than what the history has dropped, the subscriber is sent `reset`
  (reason `history_gap`) first, then `overflow` if the catch-up also exceeds `max-queued`, then
  the retained events; see [Delivery guarantees](#delivery-guarantees).
- **Acknowledging** moves the cursor forward, never back: a WebSocket `ack{topic, id}` frame
  (answered `acked`), `PushService.Ack` (JSON at `POST <path>/v1/ack`), or `POST <path>/ack`
  with `{"topic", "id", "session"?}` for SSE clients. A client that never acks replays from the
  same cursor on every reconnect; a client that reconnects with its last id does not need to ack.
  Acks need the subscribe check of the topic.
- **`max-queued`** (default `history-size`) bounds what a resuming session may have pending:
  beyond it the oldest events are dropped, counted as `dropped{reason=session_overflow}`, and
  the subscriber is told first: SSE `event: overflow` with `{"reason":"session_overflow",
  "dropped":n}`, WebSocket `overflow{topic, reason, dropped}`, gRPC a `PushEvent` with an empty
  id and the attribute `push.signal=overflow`; GraphQL subscribers see only the metric.
- **Expiry**: every ack and resume renews the session for `session-expiry`; a session idle past
  it is forgotten and starts afresh at the head.
- **Operating them**: `GET .../sessions?q=<prefix>&limit=` lists cursors, `DELETE
  .../sessions/{session}` forgets one (its next subscription starts at the head);
  `shiguchi.cursus.sessions.acks` counts acks; the overview shows `engine.sessions`.
- **Seek**: `POST .../sessions/{session}/seek` with `{"topic", "to"}` moves a cursor to an
  id, forward or back (`to`: an event id, `first` or `head`); the next resume replays from there.

### Consumer groups

`session-key: GROUP` turns a namespace's subscribers into competing consumers:

```yaml
shiguchi:
  cursus:
    namespaces:
      jobs: { qos: AT_LEAST_ONCE, history-size: 10000, history-ttl: 1d, session-expiry: 30d,
              session-key: GROUP, ack-deadline: 30s, max-deliveries: 5, max-inflight: 64 }
```

- **Joining.** A subscriber names its group and itself: WebSocket `sub{topics, group, member}`
  (`member` defaults to the connection id), gRPC `SubscribeRequest.group` / `member`, SSE
  `?group=&member=` (both required; the member name is what the HTTP ack carries). A group
  namespace cannot be subscribed without a group, nor at a lower `qos`; `after` is ignored. A
  new group starts at the topic's head.
- **Exactly one member per event.** The store hands each event to one member
  (`PushStore.claim`: Redis consumer groups natively, Postgres rows claimed with
  `FOR UPDATE SKIP LOCKED`, JDBC rows claimed by optimistic update, the in-memory store per instance), up to `max-inflight`
  unacknowledged events per member. The member acks each event: WebSocket `ack{topic, id}`,
  `PushService.Ack` with `group` and `member`, or `POST <path>/ack` with
  `{"topic", "id", "group", "member"}`. An ack by a member that holds no such claim is refused
  (404 `not_found`; Redis does not enforce the member).
- **Redelivery.** An event unacknowledged past `ack-deadline` goes to another member, carrying
  `deliveries` (2, 3, …) in its attributes; a member that leaves releases its claims at once.
  Past `max-deliveries` the event is dead-lettered (source `group:<name>`, retry it from the
  dead-letter queue) and acknowledged on the group's behalf.
- **Ordering.** `ordering` per namespace (needs `session-key: GROUP`):

  | `ordering` | Guarantee | Stores |
  | --- | --- | --- |
  | `NONE` (default) | none across members: two members handle events of one topic concurrently | all |
  | `KEY` | events sharing a `key` are never in flight at two members and go out in id order; a key sticks to the member holding it until it acks (or the claim expires). Keyless events are never held back | all |
  | `STRICT` | one active member per group and topic (a lease renewed every claim tick); the others idle and take over within `ack-deadline / 2` when it leaves, or when its lease (at least 5 s) lapses after a crash | all |

  On `REDIS`, `KEY` runs as one Lua script per claim over the group's pending list (at most
  1000 entries): an event whose key another member holds is parked, left pending with its
  delivery count at 0, until the key frees. Under `KEY` an event behind a held key waits for it, so size `max-inflight`
  and `ack-deadline` for one slow key not to stall the rest. Postgres serialises claims per
  group with an advisory lock; a claim that loses the lock hands out nothing and retries on the
  next tick.
- **Operating them.** `GET .../groups?q=<prefix>&limit=` lists each group's pending claims,
  members and position per topic (`pending` counts staged, not yet delivered events too under
  `ordering: KEY`); `DELETE .../groups/{group}` forgets a group (its next member
  starts at the head). Metrics: `shiguchi.cursus.groups.deliveries{outcome=first|redelivery|dead_lettered}`,
  `shiguchi.cursus.groups.acks`, `shiguchi.cursus.groups.seeks{kind=group|session}`,
  `shiguchi.cursus.groups.strict.takeovers`; the overview shows `engine.groups`. An idle
  group's position expires with `session-expiry` (Redis groups stay until dropped).
- **Seek.** Move a group on a topic to an id, `first` or `head`; its claims are dropped, so it
  redelivers from there (members acking a dropped claim get a 404, harmless). Admin writes,
  recorded in the audit log; the console has a *seek…* action on each row:

  ```bash
  curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \n       -d '{"topic":"jobs:q","to":"first"}' http://localhost:8080/actuator/gateway-push/groups/workers/seek
  ```

## Presence and the connection registry

Every connection is registered in the store (`MEMORY`, `REDIS`, `POSTGRES` or `JDBC`) as a row naming its
id, the instance that holds it, the principal, the transport, its topics and when it opened. A
row lives for twice `connection.keepalive` and each instance rewrites its rows on a tick, so the
rows of an instance that dies lapse by themselves within that time; a closed connection removes
its row at once. Registering never delays a subscription: it happens off the subscribe path.

```yaml
shiguchi:
  cursus:
    instance-id: gw-a            # how this instance appears in the registry; default: host name plus a suffix
    peer:
      address: gw-a.internal:9090 # this instance's gRPC host:port for its peers (see Targeted delivery)
    namespaces:
      chat:
        presence: true           # list who listens to each topic of this namespace, cluster-wide
```

`instance-id` replaces `upstream.instance-id`, which still applies when `instance-id` is empty.

**Device id.** A client may name its device with the `X-Push-Device` header on the SSE or
WebSocket handshake, the `device` field of the WebSocket `connect` frame, or the `x-push-device`
gRPC metadata of a `Subscribe`. It is stored on the row and shown by the admin views.

**Who is listening.** `shiguchi.cursus.v1.PushService` has two calls, also at
`POST <cursus.path>/v1/has-subscribers` `{"topics": ["chat:room1"]}` and
`GET <cursus.path>/v1/subscribers?topic=chat:room1&page_size=50&page_token=`:

| Call | Answers |
| --- | --- |
| `HasSubscribers(topics[])` | for each topic, whether anyone is subscribed |
| `GetSubscribers(topic, page_size, page_token)` | the number of subscribers and a page of `{connectionId, principal, deviceId, since}` |

A namespace with `presence: true` answers from the registry across every instance, with the
members. Any other namespace answers for this instance only: a count, no members. Both calls need
the topic's subscribe permission, so a backend that may subscribe to a topic may ask who listens
to it.

**Administration.** The `connections` read of the `gateway-push` endpoint is cluster-wide when
the store keeps a registry: each entry carries `instance` and `deviceId`, and the console's
Connections view has an instance and a device column. `DELETE connections/{id}` of a connection
held by another instance is forwarded to it over the peer channel (see below); when no peer channel
reaches that instance it answers `409` naming it.
