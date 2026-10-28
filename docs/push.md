# Cursus: server-to-client push messaging

Experimental. The shape may change in a minor release; changes are called out in
the changelog.

Push lets a backend send events to connected clients without a request from them: a browser
watching an order, a dashboard, a worker waiting for jobs. Clients subscribe to **topics** over
Server-Sent Events; backends publish over HTTP. The gateway fans each event out to every
subscriber of its topic and, where the namespace asks for it, keeps a history so a client that
drops resumes without a gap.

What ships now: SSE and WebSocket subscriptions, HTTP publishing, the `PushService` gRPC service
(native gRPC, gRPC-Web, and typed JSON through the gateway's own transcoding), the in-memory
engine (one instance) and the Redis and Postgres engines (every instance sees every event,
history kept across restarts), `AT_MOST_ONCE` and `AT_LEAST_ONCE` delivery with resume, per-namespace authorization, a
close frame on shutdown, the `gateway-push` endpoint and the console's Push tab. The design in
[design/push.md](design/push.md) covers what follows: GraphQL subscriptions, the Postgres engine
and message search, persistent sessions and groups, the command line.

It is in-app messaging, not mobile push (APNs/FCM), and not a message broker.

## Turning it on

Add the push starter next to the gateway starter, Boot 3 or Boot 4:

```kotlin
implementation("io.github.appiaworks:shiguchi-cursus-spring-boot3-starter")   // or -boot4-
```

```yaml
shiguchi:
  cursus:
    enabled: true
    namespaces:
      ticker: { auth: NONE, publish-auth: NONE }
      orders: { qos: AT_LEAST_ONCE, history-size: 500, history-ttl: 1h, auth: AUTHENTICATED }
      user:   { auth: PRINCIPAL_MATCH }
```

A topic is `namespace:name`: `orders:123`, `user:alice`, `tenant:acme/alerts`. The namespace must
be declared; it carries the policy. The name is free within it (letters, digits, `_ . / : @ -`).

## Subscribing

```bash
curl -N "https://gw/push/subscribe?topic=orders:123&topic=ticker:btc"
```

```
id: 1759953600000000042
event: orders:123
data: {"orderId":"123","status":"SHIPPED"}

: keepalive

event: close
data: {"reason":"rebalance"}
```

- Several `topic` parameters (or one comma-separated) share the connection, up to
  `connection.max-subscriptions`. The `event:` field names the topic.
- `id:` is the event id, ordered per instance. A browser's `EventSource` sends it back as
  `Last-Event-ID` when it reconnects; the gateway replays what the client missed on
  `AT_LEAST_ONCE` topics. Non-browser clients send the header, or `?lastEventId=`.
- `: keepalive` comments go out after `connection.keepalive` of silence so idle-timeout proxies
  keep the connection.
- `event: close` ends the stream after `connection.max-lifetime` plus a random share of
  `lifetime-jitter`, so reconnects spread over time instead of arriving together after a deploy.
  `EventSource` reconnects on its own.
- `event: overflow` ends an `AT_LEAST_ONCE` stream whose client stopped reading and whose queue
  filled; reconnect with `Last-Event-ID` to resume.
- `?qos=AT_MOST_ONCE` asks for less than the namespace offers (no replay); a subscription never
  gets more.
- `?group=<name>&member=<name>` joins a consumer group on a `session-key: GROUP` namespace (see
  "Consumer groups"); ack over `POST /push/ack`.
- `?session=<id>` names the client's session on a namespace with `session-key: CLIENT` (see
  "Persistent sessions"); a principal's session needs nothing. `event: overflow` with
  `"reason":"session_overflow"` precedes the events when a resuming session lost its oldest
  pending ones; acknowledge with `POST /push/ack`.
- `?origin=<id>` declares the subscriber's own origin: events published with the same origin are
  not delivered to it (see "Echo suppression"). Grammar `[A-Za-z0-9_.:@-]{1,128}`, else 400.

## WebSocket

`GET /push/ws`, subprotocol `push.v1.json`, JSON text frames both ways. One socket carries any
number of topics, and a client can publish and refresh its token on it. Use it where SSE's one
direction and one-connection-per-URL are in the way; everything SSE delivers, this delivers the
same.

```js
const ws = new WebSocket("wss://gw/push/ws", "push.v1.json");
ws.onopen = () => {
  ws.send(JSON.stringify({ type: "connect", token }));               // browsers cannot set headers
  ws.send(JSON.stringify({ type: "sub", topics: ["orders:123"], after: lastId }));
};
ws.onmessage = ({ data }) => {
  const f = JSON.parse(data);
  if (f.type === "event") { lastId = f.id; render(f.data); }
  if (f.type === "close") ws.close();                                  // then reconnect with after
};
```

| Client sends | Fields | Server answers |
| --- | --- | --- |
| `connect` | `token` (JWT), `session` (a client session id on a `session-key: CLIENT` namespace), `origin` (the connection's origin id: the default of every `sub`, and what a `pub` is stamped with) | `connected` (`connectionId`, `authenticated`, `limits`, `keepaliveMs`) or `error` |
| `sub` | `topics` (or `topic`), `after` (one id, or `{topic: id}`), `qos`, `group` and `member` (a consumer group; `member` defaults to the connection id), `origin` (this subscription's origin id, overriding the connection's) | `subscribed` per topic (`topic`, `position`, `qos`) or `error` with `topic` |
| `unsub` | `topics` | `unsubscribed` per topic |
| `pub` | `topic`, `data`, `attributes`, `key` | `published` (`topic`, `id`) or `error`; needs `client-publish` and the subscribe check. The event is stamped `from` (the principal) and `push.origin` (the `connect` origin); any `push.*` attribute or `from` the frame carries is dropped |
| `refresh` | `token` | `connected` |
| `ack` | `topic`, `id` | `acked` (`topic`, `cursor`) or `error`; moves a persistent session's cursor forward, or releases a consumer-group claim on a topic subscribed with `group` |
| `ping` | | `pong` |

Server-initiated frames: `event` (`topic`, `id`, `data`, `attributes`, `key`, `time`),
`overflow` (`topic`, `reason`: `queue_full` when an `AT_LEAST_ONCE` topic's queue filled and it
was unsubscribed, resubscribe with `after`; `session_overflow` with `dropped` when a persistent
session resumed past `max-queued`, the subscription continues), `reset` (`topic`, `reason`:
`history_gap` or `no_history`; the resume point is older than the retained history, the retained
events follow, see [Delivery guarantees](#delivery-guarantees)), `close` (`code`, `reason`) right
before the socket closes. A `connected` frame arrives
first on every socket.

- **Authentication**: the handshake's `Authorization` header goes through the security chain like
  any request. A browser sends the JWT in a `connect` frame instead; the gateway's JWT decoder
  (and your converter bean, if any) turns it into the same principal. `refresh` replaces it. When
  a JWT expires without a refresh the socket closes with 4401. A browser signed in through the
  [browser sign-in](bff.md) needs no token at all: see [Cookie connections](#cookie-connections).
- **Origin**: a browser's `Origin` must be the gateway's own host, or one of `ws.allowed-origins`
  (`scheme://host[:port]`, or `*`); otherwise the socket closes with 1008 at the handshake. CORS
  does not cover WebSocket; this does. Clients without an `Origin` header are not checked.
- **Cookie connections.** See below.
- **Queues** are per topic: an `AT_MOST_ONCE` topic drops its oldest queued event for a socket that
  is not reading, an `AT_LEAST_ONCE` topic sends `overflow` and is dropped.
- **Lifetime and shutdown** end the socket with a `close` frame then status 1012; WebSocket pings
  go out at `connection.keepalive`.
- `ws.enabled: false` turns the endpoint off.
- **Client library**: [`@appiaworks/shiguchi-cursus-client`](../shiguchi-cursus-client-js/README.md) (experimental,
  TypeScript, browser and Node) does the reconnect, the resubscribe with `after`, the token
  refresh and the gap reporting that every consumer of this protocol otherwise rewrites.

### Cookie connections

With [browser sign-in](bff.md) on, a browser holding the session cookie opens
`/push/ws` (with its own page's `Origin`) or `/push/subscribe` without a token. The principal is the
same one a bearer caller of that user gets, so `PRINCIPAL_MATCH` topics behave identically. Such a
connection lives as long as the **session**, not the 10-minute access token:

- No `refresh` and no 4401. A `connect` frame without a token is fine (it still carries `session`,
  `device`, `origin`); a `connect` or `refresh` **with** a token closes the socket with 1008 `token
  not accepted on a cookie connection`.
- When the session ends (logout, a refused token refresh, the idle or absolute timeout) the
  WebSocket closes with **4440** `session ended` (a `close` frame first), and an SSE stream ends with
  `event: close` and `data: {"reason":"session ended"}`. 4440 means "sign in again, do not
  refresh"; `@appiaworks/shiguchi-cursus-client` stops and reports `session-ended`, and an `EventSource`
  should be closed on a `close` whose reason is `session ended` rather than left to reconnect.
- A session that ends on this instance closes its sockets at once; one that ends on another instance
  is found by a poll of the session store every `shiguchi.cursus.session-check-interval` (default
  `30s`, at least `5s`), which is the longest a socket outlives a logout. A store error during a poll
  keeps the connection (logged, and counted in `shiguchi.cursus.session.checks{outcome=error}`);
  closes are counted in `shiguchi.cursus.connections.closed{reason=session-ended}`.
- The gateway has already matched the `Origin` exactly against the session's application, so
  `ws.allowed-origins` is not needed for cookie connections.
- The gRPC-Web `PushService/Subscribe` stream and GraphQL subscriptions of a cookie caller are bound
  to the session too, by the gateway (not by push), polled at `shiguchi.security.session-check-interval`:
  a gRPC-Web stream finishes with `grpc-status: 16` and `grpc-message: session ended`, a GraphQL
  subscription ends with an `UNAUTHORIZED` "session ended" error. See [bff.md](bff.md#rest-grpc-web-and-graphql-streams).

## gRPC, gRPC-Web and typed JSON

The push surface is also a gRPC service, `shiguchi.cursus.v1.PushService`
([push.proto](../shiguchi-proto/src/main/proto/shiguchi/push.proto), shipped in
`shiguchi-proto` with the other gateway protos):

```proto
service PushService {
  rpc Publish(PublishRequest) returns (PublishResponse);       // POST <path>/v1/publish
  rpc Subscribe(SubscribeRequest) returns (stream PushEvent);  // GET  <path>/v1/subscribe
  rpc Ack(AckRequest) returns (AckResponse);                   // POST <path>/v1/ack
}
```

The gateway hosts it itself and declares it as one more service of its own, STATIC mode over an
in-process channel, so nothing is special about it: it answers on every surface the gateway has,
with the gateway's routing, metrics, access log and security chain in front. On the native gRPC
port it is also added as a service of the proxy server itself, so a gRPC client's stream is one
hop, not a bridge into the in-process channel.

| Surface | Call |
| --- | --- |
| Native gRPC (`shiguchi.grpc.enabled`, port 9090) | `grpcurl -d '{"topics":["orders:123"],"json":{"status":"SHIPPED"}}' gw:9090 shiguchi.cursus.v1.PushService/Publish`; `Subscribe` is a server stream; the service is in the merged reflection, and is served on the port in place (a `GatewayHostedService`), not through the proxy's bridge |
| gRPC-Web (`shiguchi.grpc-web.enabled`) | `POST /shiguchi.cursus.v1.PushService/Publish` and `/Subscribe` with `application/grpc-web+proto` or `-text`, from grpc-web and Connect clients |
| JSON | `POST /push/v1/publish` with the proto JSON of `PublishRequest`; `GET /push/v1/subscribe?topics=orders:123,orders:124&after=<id>&session=<id>` streams `PushEvent` documents as `application/x-ndjson`, or as SSE with `Accept: text/event-stream`; `POST /push/v1/ack` with `{"topic", "id", "session"?}` or `{"topic", "id", "group", "member"}`; `&group=&member=` on the subscribe joins a consumer group |

```bash
curl -X POST https://gw/push/v1/publish -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
  -d '{"topics":["orders:123"],"json":{"orderId":"123","status":"SHIPPED"},"attributes":{"tenant":"acme"}}'
# {"events":[{"topic":"orders:123","id":"1759953600000000042"}]}
curl -N https://gw/push/v1/subscribe?topics=orders:123 -H "Accept: application/x-ndjson"
# {"topic":"orders:123","id":"1759953600000000042","json":{"orderId":"123","status":"SHIPPED"},"attributes":{"tenant":"acme"},"time":"2026-10-08T19:00:00.000042Z"}
```

**Payloads.** `json` is a `google.protobuf.Value`: any JSON value, delivered as published.
`message` is a `google.protobuf.Any` for typed publishers. The gateway renders it to its proto
JSON, `@type` included, for the JSON subscribers (SSE, WebSocket, the transcoded stream), and
keeps the message itself for the gRPC subscribers, who receive `message` exactly as it was
published, with no JSON on the way. The type must be one the gateway knows, a message of a
configured service (or of `push.proto` itself); an unknown type is `INVALID_ARGUMENT`. A typed
event is stored and carried in both forms, so it costs about twice its size in history and on the
Redis channel. The wire bytes of an event are built once and shared by every gRPC stream on the
instance, whatever the subscriber count.

**Who is calling.** The service reads a bearer token from the `authorization` metadata, the one the
gateway forwards from the HTTP surfaces, and decodes it with the gateway's JWT decoder, as the
WebSocket `connect` frame does. The `x-principal` the gateway adds for backends is not trusted
here: the native gRPC port forwards a caller's own metadata verbatim. So a publisher calling the
gRPC port needs a JWT the gateway can verify, and a deployment without a JWT decoder can only
publish into namespaces with `publish-auth: NONE` over gRPC. The namespace's `publish-auth` and
`auth` apply as everywhere else: `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND` for an
undeclared namespace, `INVALID_ARGUMENT` for a bad topic, payload or resume id,
`RESOURCE_EXHAUSTED` beyond `max-message-bytes`.

**Origin.** `SubscribeRequest.origin` declares the subscriber's own origin (see "Echo suppression");
`PublishRequest.origin` and `SendRequest.origin` stamp the event. A `push.`-prefixed key in
`attributes` is `INVALID_ARGUMENT`, a malformed origin too.

**How a stream ends.** The gateway closes a `Subscribe` stream with `UNAVAILABLE` and the trailer
`push-close-reason` (`rebalance` at the connection lifetime, `shutdown`, `admin`); an
`AT_LEAST_ONCE` subscriber that stopped reading gets `RESOURCE_EXHAUSTED` (`queue_full`). Either
way: reconnect, and resume with `after` set to the last id seen. Events are written as the client
reads them: gRPC flow control is the backpressure.

**Through gRPC-Web.** `Subscribe` is a server stream like any other, so a browser reaches it with
Connect-ES's `createGrpcWebTransport`; the example, resume and token refresh are in
[grpc-web.md](grpc-web.md#server-streaming-from-connect-es). `shiguchi.rest.max-response-body`
limits each event on a stream, not the stream's running total, so a subscription is not cut after
some number of bytes (an event over the cap ends the stream with `RESOURCE_EXHAUSTED`). The gRPC
and gRPC-Web `Subscribe` stream sends no heartbeat while idle, unlike SSE and WebSocket, so a
proxy that closes idle connections ends a quiet stream; raise its idle timeout and reconnect with
`after`.

The service appears in `gateway-routes`, the OpenAPI document and the console under the name
`push` (`shiguchi.cursus.grpc.service-name`); `shiguchi.cursus.grpc.enabled: false` leaves it
out. Declaring a service named `push` yourself under `shiguchi.services` replaces the
built-in declaration.

## GraphQL subscriptions

With the GraphQL surface on (`shiguchi-graphql-*-starter` next to the push starter) every namespace
is also a field of `type Subscription`, served over graphql-transport-ws where
`spring.graphql.websocket.path` is set:

```graphql
subscription {
  ordersEvents(topic: "42", after: "0000000000000000017", origin: "client-7") {   # origin: optional, see "Echo suppression"
    id            # what to resume from: pass it back as `after`
    topic
    time
    key
    attributes { key value }
    payload { orderId status }   # the namespace's payload-type, with field selection
  }
}
```

```yaml
shiguchi:
  cursus:
    namespaces:
      orders:
        qos: AT_LEAST_ONCE
        history-size: 100
        payload-type: acme.OrderEvent    # a message of a service the gateway serves
        graphql-field: orderEvents       # default: <namespace>Events
      ticker:
        graphql: false                   # no field for this one
```

- **Field and envelope.** `<namespace>Events(topic: String!, after: ID): <Namespace>EventsEnvelope`;
  `topic` is the name within the namespace, `after` the id to resume from (the same ids as
  `Last-Event-ID`). The envelope carries the event's id, topic, time, key and attributes next to
  the payload, so a client has what it needs to resume without transport extensions.
- **Typed or JSON payload.** With `payload-type` the payload is the generated GraphQL object
  type of that message (field selection applies, nested types come along), from a service the
  gateway serves; startup fails with a plain message when the message is unknown. Events
  published as JSON are parsed into the message, events published typed (an `Any` of that type)
  are used as sent. Without `payload-type` the payload is the `JSON` scalar.
- **Same engine, same rules.** The fetcher goes through the same subscription path as SSE,
  WebSocket and gRPC: the namespace's `auth` applies to the caller the WebSocket handshake
  authenticated (a bearer token on the upgrade request), the client queue, the connection
  lifetime, the shutdown close, the `gateway-push` views (transport `graphql`) and the metrics.
  A refused subscription is a graphql-transport-ws `error` frame; an `AT_LEAST_ONCE` subscriber
  that stopped reading gets an error with `overflow` in its message and resumes with `after`.
- **Out of scope for now.** Federation of these fields, and the declarative `(push.topic)` RPC
  option that would derive fields from upstream streams.

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

## Upstream-backed topics

A server-streaming RPC of a served service can be the source of a topic, declared on the method:

```proto
import "shiguchi/api/topic.proto";

service OrderService {
  rpc WatchOrder(WatchOrderRequest) returns (stream OrderEvent) {
    option (shiguchi.api.topic) = { pattern: "orders:{order_id}" };
  }
}
```

`pattern` is `namespace:template`; every `{var}` names a top-level field of the request message
(a string, number, bool or enum). A client that subscribes to `orders:42` on any transport (SSE,
WebSocket, gRPC, GraphQL) makes the gateway call `WatchOrder{order_id: "42"}` and publish every
message it answers into `orders:42` as a typed event (`payload-type` is the response message, so
the gRPC subscribers get the message as sent and the GraphQL field a typed `payload`). The
backend sees one stream per topic, not one per client:

- **One stream per topic, across instances.** The first subscriber of a topic on an instance
  makes the instance take the topic's lease in the store (`upstream.lease-ttl`, renewed at a
  third of it); the instance that holds it opens the stream and publishes, the others wait and
  retry at the lease interval, their subscribers fed through the engine. A lost lease closes the
  stream. With the `MEMORY` store the lease is local, as everything else is.
- **On demand.** The stream opens with the first subscriber and closes `upstream.idle-linger`
  after the last one left; a stream the backend ends or fails is reopened with a doubling
  backoff (`upstream.retry-backoff` up to `max-retry-backoff`) while subscribers remain.
- **The namespace.** Declared under `shiguchi.cursus.namespaces` with default settings when
  it is not already (configure it to change `qos`, `history-size`, `auth`); its `payload-type`
  becomes the response message when unset. A topic of the namespace the pattern does not match
  (`orders:eu/west` for `orders:{order_id}`) is a plain topic.
- **Startup checks.** The method must be server-streaming, every `{var}` a singular scalar field
  of the request, a pattern declared once; the message names what is wrong.
- **Event ids.** `event_id_field: "seq"` on the option names a response field (dots reach nested
  messages: `"meta.sequence"`; an integer type, or a string holding a decimal integer) whose value
  is the backend's own sequence. On the `MEMORY` and `REDIS` stores it **is** the event id, so the
  id a client resumes from (`Last-Event-ID`) is the backend's, stable across gateway restarts and
  across an owner handoff: a gateway that takes over the topic reads the newest kept event's
  `upstream.id` and skips what the upstream replays at or below it. Ids must increase within a
  topic: a value the topic already holds (a replay the dedupe could not see, or another publisher
  that put a newer id on the topic) is published as a new event under an engine id with the
  attributes `upstream.id` and `upstream.id-rejected=true`, never dropped; do not mix publishers
  on such a topic, since an engine id is far above a small sequence and every later upstream id
  would then land below it. A value of zero or less, unset, above `Long.MAX_VALUE` or not a
  decimal counts as missing and the event gets an engine id. The `POSTGRES` store numbers every
  topic from one table-wide sequence, so it keeps its ids and records the value only as the
  `upstream.id` attribute (the handoff dedupe works the same). The dedupe needs history on the
  namespace (`history-size` above zero). An invalid field is refused at startup. Outcomes are
  counted in `shiguchi.cursus.upstream.ids{outcome=kept|rejected|attribute|missing|duplicate}`.
  Plain server-streaming SSE routes of such a method do not get an `id:` line yet.
- **Operating it.** `shiguchi.cursus.upstreams` (gauge) counts the streams this instance owns;
  the `gateway-push` overview lists them (`engine.upstreams`) and the topics view marks a topic
  `owner` or `waiting`.

The descriptor must carry the option: with `PB_FILE` include `shiguchi/api/topic.proto`
in the descriptor set (`protoc --include_imports`); with reflection it travels with the file.
The option's `mode` has one value, `SHARED_UPSTREAM`.

## Sources

Records that already flow elsewhere can become events without a publisher of your own:

```yaml
shiguchi:
  cursus:
    sources:
      - name: order-events
        type: KAFKA
        topic-template: "orders:{key}"          # {key} {topic} {partition} {header.<name>}
        payload-type: acme.OrderEvent           # values are this message, so events are typed
        kafka:
          bootstrap-servers: kafka:9092
          topics: [order-events]
          start: LATEST                         # or EARLIEST
          properties: { security.protocol: SASL_SSL }
      - name: order-firehose
        type: GRPC_STREAM
        topic-template: "orders:{order_id}"     # the response message's scalar fields
        key-field: order_id
        grpc: { service: acme.OrderService, method: Events, request: '{"since":"now"}' }
```

- **KAFKA** reads every partition of the Kafka topics with a consumer of the gateway's own (no
  consumer group; needs `org.apache.kafka:kafka-clients` on the classpath). The value is the
  event's JSON payload, or, with `payload-type`, a message of that type (protobuf bytes, or JSON
  with `payload-format: JSON`) so the event is typed for gRPC and GraphQL subscribers. Each
  event carries `kafka_topic`, `kafka_partition`, `kafka_offset`, and the record key as its key.
- **GRPC_STREAM** opens a served service's server-streaming method through the gateway and
  publishes every response message as a typed event; `topic-template` draws on the message's
  singular scalar fields, `key-field` names the one that becomes the event key. Where
  `(shiguchi.api.topic)` opens a stream per topic on demand, a source is one firehose
  for all its topics, open while the gateway runs.
- **One consumer, across instances.** A source is `shared` by default: one gateway instance
  consumes it (a lease in the store, `source:<name>`, paced by the `upstream.*` knobs), the
  others wait and take over when it goes. `shared: false` makes every instance consume, for a
  source that is already partitioned per instance.
- **Mapping failures and refused events** go to the dead-letter queue (below): a record whose
  template variable is missing, a value that is not the declared type, an event the gateway
  refuses (namespace undeclared, too large). The source keeps going.
- **The namespace** of the template is declared with default settings when it is not configured,
  with the source's `payload-type` (or the stream's message) as its payload type.
- **Your own source.** A bean implementing `dev.appiaworks.shiguchi.cursus.PushSource`
  (`@ApiStatus(EXPERIMENTAL)`): a name and a `Flux<SourcedEvent>`; the gateway runs it like the
  built-in ones, lease included. Every event carries `from=source` and `source=<name>`.

Operating them: `shiguchi.cursus.sources` (gauge: sources this instance consumes),
`shiguchi.cursus.sources.events{source, outcome=published|dead_lettered}`; the overview lists
each source's state (`owner`, `waiting`, `running`), counts and last error, as does
`GET .../sources`.

## Hooks

A backend can be told what the push surface is doing: implement
`dev.appiaworks.shiguchi.cursus.PushHook` as a bean (any of its methods), or serve the
`shiguchi.cursus.v1.PushHooks` contract (`shiguchi/push_hooks.proto`) on one of
the gateway's backends and name it:

```yaml
shiguchi:
  cursus:
    hooks:
      grpc-service: acme.PushHooks     # any subset of the methods below
      idle-linger: 10s
      timeout: 5s
      fail-closed: false               # a decision hook that times out or fails: false allows, true rejects
```

| Hook | When |
| --- | --- |
| `OnTopicActive(TopicActivity)` | the first subscriber of a topic arrived on this instance: start producing |
| `OnTopicIdle(TopicActivity)` | the last one left and `idle-linger` passed without a new one: stop producing |
| `OnConnect(ConnectionInfo)` | **decision**: a connection opened (id, transport, principal; topics not known yet); answer `ConnectDecision{reject, reason, subscribe[]}` |
| `OnDisconnect(ConnectionInfo)` | it closed, with its topics and the reason (`closed`, `admin`, `lifetime`, `shutdown`, `overflow`, `rejected`) |
| `OnSubscribe(SubscribeHook)` | **decision**: a client asked for topics; answer `SubscribeDecision{reject, reason, topics[]}` |
| `OnClientMessage(ClientMessage)` | **decision**: a client published over its WebSocket (`pub`); answer `ClientMessageResult{action, reason, payload}` |

`OnTopicActive`, `OnTopicIdle` and `OnDisconnect` are notifications made after the fact: nothing
waits for them, a failing or slow hook (`timeout`) is logged and counted
(`shiguchi.cursus.hooks{hook, outcome}`), the client never sees it.

### Decisions

`OnConnect`, `OnSubscribe` and `OnClientMessage` are asked before the client is served, and the
gateway waits for the answer (up to `timeout`):

| Decision | Effect |
| --- | --- |
| `ConnectDecision.reject` | the connection is closed before any subscription or event: SSE and GraphQL answer 403 with the reason, gRPC `PERMISSION_DENIED`, WebSocket sends an `error` frame (`forbidden`) and closes with 1008. The disconnect hook reports the reason `rejected`. |
| `ConnectDecision.subscribe` | topics (`namespace:name`) the gateway subscribes the connection to on the server side. Not authorized again: the backend's word is final. Consumer-group topics cannot be subscribed this way. |
| `SubscribeDecision.reject` | the subscription is refused with the same shape as an authorization failure |
| `SubscribeDecision.topics` | when not empty, replaces the requested topics (not authorized again) |
| `ClientMessageResult.action` | `FORWARD` publishes (the default, also an empty reply), `DROP` acknowledges the client with a `published` frame and publishes nothing, `REJECT` answers an `error` frame (`forbidden`, with the reason) |
| `ClientMessageResult.payload` | when not empty, replaces the payload that is published (`FORWARD` only) |

With several hooks the first reject wins and later hooks are not asked; topic and payload
rewrites compose in bean order (`hooks.grpc-service` first). A hook that times out or fails
allows the client through, counted as `shiguchi.cursus.hooks{outcome=timeout|error}`, unless
`fail-closed: true`, which rejects instead. In process, implement `decideConnect`,
`decideSubscribe` and `onClientMessage` on `PushHook`; an empty `Mono` means no opinion.

On a WebSocket the connect decision is taken once, at the first `connect`, `sub` or `pub` frame
(or at open when the handshake already carries a principal), so a token sent in `connect` is
known to the hook. An SSE, gRPC or GraphQL subscription asks connect then subscribe before the
first event.

Upgrading: `OnConnect` used to answer `google.protobuf.Empty`. An `Empty` reply is read as the
default `ConnectDecision` (allow), so a backend written for the notification keeps working; a
method the backend answers `UNIMPLEMENTED` is remembered and not called again.

Topic activity is per gateway instance: with a shared engine two instances each
report their own first and last subscriber, and `instance` says which. The gRPC hook is sent
through the gateway's dispatcher, so the backend's channel, load balancing and metadata apply;
it is re-encoded as the backend's own input message, which must keep `push_hooks.proto`'s field
numbers.

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


## Authorization

The push endpoints sit inside the gateway's security chain like every other surface: under
`shiguchi.security.mode: AUTHENTICATED` every call needs a JWT; under the default
`PERMIT_ALL` the chain lets anonymous callers through and authenticates a JWT when one is
presented. With `open-rest-bypasses-chain: true` the push paths stay inside the chain, since the
namespace checks below need it to have read the JWT. On top of the chain, each namespace decides
who may subscribe and who may publish:

| `auth` (subscribe) | Allows |
| --- | --- |
| `NONE` (default) | anyone |
| `AUTHENTICATED` | any authenticated principal |
| `ROLE` | a principal holding one of `roles` (`ops` matches `ops`, `ROLE_ops` or `SCOPE_ops`) |
| `PRINCIPAL_MATCH` | the principal whose name is the topic name, or its prefix before `/`: `alice` may read `user:alice` and `user:alice/inbox` |
| `CLAIM` | a JWT whose `claim` (default `push.subscribe`, a dotted path) lists a pattern matching the topic: `orders:*` (one segment), `orders:**`, `tenant:acme/*`, `{var}` like `*` |
| `AUTHORIZER` | your `GatewayAuthorizer` bean, called with `route == null` and `arguments` `push.operation`, `push.topic`, `push.namespace` |

The principal's name is the token's `sub` unless Spring Boot's
`spring.security.oauth2.resourceserver.jwt.principal-claim-name` says otherwise
([security.md](security.md#principal-name)); that renames the caller everywhere. To match one
namespace's topics on another claim instead, set its `principal-claim` (a dotted path):

```yaml
shiguchi:
  cursus:
    namespaces:
      user: { auth: PRINCIPAL_MATCH, principal-claim: preferred_username }
```

The topic name is then compared with that claim's string value; a token without the claim, or
with a non-string value, is refused with `403`. Only the topic check moves: persistent session
keys and the `from` attribute keep the principal's name. A name a topic cannot carry (topic names
are `[A-Za-z0-9_./:@-]`, so not `auth0|abc`) never matches; pick a claim that fits.
`principal-claim` on a namespace whose `auth` is not `PRINCIPAL_MATCH` fails startup.

`publish-auth` has the same values but `PRINCIPAL_MATCH`, reads `publish-roles` and
`publish-claim` (default `push.publish`), and defaults to **`AUTHENTICATED`**: nobody publishes
anonymously unless you say `publish-auth: NONE`, which is logged as a warning at startup.

Refusals are `401` (a principal is needed and there is none) or `403`, with
`{"error": "...", "message": "..."}`.

Design topics so that the topic is the authorization boundary (`user:{sub}`,
`tenant:{id}/orders`): a check runs once per subscription, never per event.

## Command line

`cursus` ([push-cli.md](push-cli.md)) subscribes, publishes, lists and searches from a terminal, with
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
for the writes. The live tail runs on [`@appiaworks/shiguchi-cursus-client`](../shiguchi-cursus-client-js/README.md): it
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
  millisecond with Redis; the numbers are in [the benchmarks](../shiguchi-benchmarks/README.md#push-jdk-25-windows-11-loopback-2026-10-08).

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
  [the benchmarks](../shiguchi-benchmarks/README.md#push-jdk-25-windows-11-loopback-2026-10-08).

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

What Redis costs, measured in [the benchmarks](../shiguchi-benchmarks/README.md#push-jdk-25-windows-11-loopback-2026-10-08).

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `shiguchi.cursus.enabled` | `false` | Mount the endpoints |
| `shiguchi.cursus.path` | `/push` | Base path: `<path>/subscribe`, `<path>/publish`, `<path>/topics/{topic}` |
| `shiguchi.cursus.broker` / `store` | `MEMORY` | `MEMORY` (this instance), `REDIS` (shared; needs `spring-boot-starter-data-redis-reactive`) or `POSTGRES` (shared; needs `spring-boot-starter-data-r2dbc` + `r2dbc-postgresql`) or `JDBC` (shared, polling; needs `spring-jdbc` and a driver, and `broker: JDBC` needs `store: JDBC`; see [the JDBC engine](#the-jdbc-engine)) |
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
