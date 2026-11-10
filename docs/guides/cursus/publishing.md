# Cursus: publishing

Part of the [cursus guide](index.md).

## Publishing

```bash
curl -X POST https://gw/push/publish -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
  -d '{"topic":"orders:123","data":{"orderId":"123","status":"SHIPPED"},"attributes":{"tenant":"acme"}}'
# {"topic":"orders:123","id":"1759953600000000042"}
```

- `data` is any JSON value; it is delivered byte for byte as the `data:` line.
- `topics: [...]` publishes the same event on several topics and answers `{"results": [...]}`.
- `attributes` (string values) and `key` (an ordering key) travel with the event for engines and
  modes that use them; the SSE frame carries `data` only.
- `origin` (optional, a string) says who the event came from; it is stored as the `push.origin`
  attribute and a subscriber that declared the same origin does not receive the event (see "Echo
  suppression"). Grammar `[A-Za-z0-9_.:@-]{1,128}`, else 400. gRPC: `PublishRequest.origin`.
- The `push.` attribute prefix is reserved for the gateway (`push.origin`, `push.signal`,
  `push.direct`): an operator publish carrying such a key is refused with 400 /
  `INVALID_ARGUMENT`.
- The event carries `attributes.from` = the publisher's principal name when the publisher is
  authenticated, on HTTP and gRPC alike; a `from` in `attributes` is replaced.
- Limits: `connection.max-message-bytes` (413 beyond it); an undeclared namespace is 404; a body
  that is not JSON is 400.

Clients may publish too, where the namespace sets `client-publish: true`:
`POST /push/topics/chat:room1` with the payload as the body (and `?origin=<id>` to stamp the
event's origin). The caller must pass the namespace's subscribe check; the event carries
`attributes.from` = the principal's name. A client never supplies attributes: over WebSocket
(`pub`) every `push.*` attribute and any `from` in the frame is dropped silently, and the gateway
stamps `from` and the connection's `connect` origin itself, so a client cannot forge either.

## Echo suppression

A client that publishes an event usually subscribes to the same topic, so it receives its own
event back. Declare an origin and the gateway leaves those events out:

```bash
# the subscriber says who it is
curl -N "https://gw/push/subscribe?topic=orders:123&origin=client-7"
# a publisher names the origin the event came from
curl -X POST https://gw/push/publish -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
  -d '{"topic":"orders:123","data":{"status":"SHIPPED"},"origin":"client-7"}'
```

The subscriber of origin `client-7` does not receive that event; everyone else does. Transports:
SSE `?origin=`, WebSocket `connect{origin}` (the connection default) and `sub{origin}` (this
subscription only), gRPC `SubscribeRequest.origin`, GraphQL `origin` argument. The metric
`shiguchi.cursus.suppressed{namespace}` counts the events left out. A suppressed event is never
written to the connection, so the delivery log does not record it.

- **A backend publishing for a client.** The origin is a value the client knows and the backend
  can pass on, so it is not tied to a gateway connection id. The recipe: the client picks a stable
  id (one per app instance) and declares it when it subscribes; it sends the same id to the
  backend with its request (a header or a field); the backend publishes with `origin` set to it.
  A WebSocket client that publishes itself needs nothing more than the `connect` origin: the
  gateway stamps it.
- **An origin is not secret and not an identity.** A subscriber may declare any origin; the only
  effect is that it misses events stamped with that value, so no authorization is involved. The
  `push.origin` attribute reaches every other WebSocket, gRPC and GraphQL subscriber with the event.
- **No origin, no change.** A subscriber that declares none receives everything; a WebSocket
  connection's id is not used implicitly.
- **Consumer groups ignore it.** A group hands each event to one member; skipping it for the
  publisher's own member would leave it unprocessed. `origin` together with `group` is accepted
  and ignored on the stream. (A connection's declared origin still applies to `Send`, below.)
- **Persistent sessions.** The stream skips the events, and when a session starts or resumes the
  gateway moves its cursor over the leading run of the subscriber's own events (the ones nothing
  unacknowledged precedes), so a reconnect after a stretch of echoes is not an overflow. An own
  event that comes after an unacknowledged foreign one is skipped on the stream but cannot be
  acknowledged on the subscriber's behalf (that could skip the foreign event), so it stays behind
  the cursor and still counts toward `max-queued` at the next resume.
- **Direct sends.** `Send` carries `SendRequest.origin`; a connection whose declared origin
  (SSE `?origin=`, `SubscribeRequest.origin`, the WebSocket `connect` origin; a `sub` origin does
  not count) equals it is not sent the event and gets the delivery status `SUPPRESSED`.

## Targeted delivery

`Send` writes an event to chosen connections instead of to a topic's subscribers: to every
connection of a principal, to one connection, or to one device of a principal. It is the call for
"tell this user" (a notification, a command to a signed-in app) without inventing a topic per
user, and it answers per connection whether the event arrived. It needs the connection registry
above, so the store must keep one.

```bash
curl -X POST localhost:8080/push/v1/send -H 'content-type: application/json' -H 'authorization: Bearer …' \
  -d '{"principal":"alice","json":{"kind":"password-changed"},"requireAck":true,"timeoutMs":5000}'
# {"id":"6f1e…","deliveries":[{"connectionId":"c3a…","instance":"gw-b","status":"DELIVERED"}]}
```

`PushService.Send(SendRequest)` is the same call over gRPC. The target is one of `principal`,
`connection_id` or `device {principal, device_id}`; the payload is `json` or `message`, as for
`Publish`; `attributes` ride along. Every connection that matched receives the **same event id**,
and the answer has one entry per connection:

| Status | Meaning |
| --- | --- |
| `DELIVERED` | the gateway wrote the event to the connection (with `require_ack`: and the client acknowledged it) |
| `NOT_CONNECTED` | nothing matched (one entry, empty `connection_id`), or the instance holding the connection cannot be reached |
| `TIMEOUT` | `require_ack` was set and no acknowledgement came within `timeout_ms` |
| `REJECTED` | the connection's queue is full, it closed first, or it cannot acknowledge (SSE with `require_ack`) |
| `SUPPRESSED` | the connection declared the event's `origin` (`SendRequest.origin`), so it was not sent |

**What the client sees.** The event arrives on the connection the target named, with topic
`<send.namespace>:<connectionId>` (`_direct:c3a…` by default) and the attribute `push.direct=true`,
in the same frame shape as a published event: an `event` frame on a WebSocket, a `PushEvent` on a
gRPC stream, an SSE event named after the topic. An SSE direct event carries **no `id:` line**, so it
never becomes the browser's `Last-Event-ID`; it also cannot be acknowledged. A client acknowledges a
direct event like any other, naming the topic and id it received: the WebSocket `ack` frame, the
`Ack` call, or `POST <path>/ack`. An acknowledgement must reach the instance holding the connection,
which is why the WebSocket and gRPC streams (the connection itself) are the way to send one.

**A reconnecting client is registered twice** until the old row's time to live lapses, so a `Send` by
principal can reach both connections. Clients dedupe on the event id.

**Who may send.** A targeted message has no topic, so the `publish-auth` rules of one namespace decide:
`cursus.send.namespace` (default `_direct`). Declare it, or `Send` is refused:

```yaml
shiguchi:
  cursus:
    send:
      namespace: _direct
      ack-timeout: 5s         # how long require_ack waits when the request names no timeout_ms
    namespaces:
      _direct:
        publish-auth: ROLE
        publish-roles: [notifier]
```

**Between instances.** The connections of this instance are written to directly. A connection held
by another instance is reached over the **peer channel**, a gRPC service (`PushPeer`, in
`push_peer.proto`) served on the native gRPC port of every instance and never through the HTTP
surfaces or JSON transcoding. Turn it on by giving every instance the same token and its own address:

```yaml
shiguchi:
  grpc:
    enabled: true            # the peer channel rides on the native gRPC port
  cursus:
    peer:
      token: ${PUSH_PEER_TOKEN}    # shared secret, sent as the x-push-peer-token metadata
      address: gw-a.internal:9090  # this instance's gRPC host:port as its peers reach it
```

Each instance publishes its address with its registry rows, and a `Send` makes one `Deliver` call per
owning instance, in parallel. The channel is plaintext and authenticated by the shared token alone:
run it on a private network or behind mutual TLS from a mesh. The native port's own `grpc.rate-limit`, when on, counts peer calls like any other. Without a token (or an address) the
channel is off: a `Send` still reaches this instance's own connections, and the ones held elsewhere
answer `NOT_CONNECTED` (one warning in the log). The same channel lets the admin `DELETE
connections/{id}` end a connection held by another instance.

**Administration.** The console's Connections view has a `send…` button on each row, and the endpoint
has `POST connections/{id}/send` with `{"data": "<json>", "requireAck": false, "timeoutMs": 5000}`: an
operator send to one connection, wherever it lives, gated and audited like the other writes and not
subject to the namespace's `publish-auth`. Metric: `shiguchi.cursus.send{status}`, one count per
matched connection.
