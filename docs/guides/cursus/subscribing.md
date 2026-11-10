# Cursus: subscribing

Part of the [cursus guide](index.md).

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
events follow, see [Delivery guarantees](delivery.md#delivery-guarantees)), `close` (`code`, `reason`) right
before the socket closes. A `connected` frame arrives
first on every socket.

- **Authentication**: the handshake's `Authorization` header goes through the security chain like
  any request. A browser sends the JWT in a `connect` frame instead; the gateway's JWT decoder
  (and your converter bean, if any) turns it into the same principal. `refresh` replaces it. When
  a JWT expires without a refresh the socket closes with 4401. A browser signed in through the
  [browser sign-in](../bff/index.md) needs no token at all: see [Cookie connections](#cookie-connections).
- **Origin**: a browser's `Origin` must be the gateway's own host, or one of `ws.allowed-origins`
  (`scheme://host[:port]`, or `*`); otherwise the socket closes with 1008 at the handshake. CORS
  does not cover WebSocket; this does. Clients without an `Origin` header are not checked.
- **Cookie connections.** See below.
- **Queues** are per topic: an `AT_MOST_ONCE` topic drops its oldest queued event for a socket that
  is not reading, an `AT_LEAST_ONCE` topic sends `overflow` and is dropped.
- **Lifetime and shutdown** end the socket with a `close` frame then status 1012; WebSocket pings
  go out at `connection.keepalive`.
- `ws.enabled: false` turns the endpoint off.
- **Client library**: [`@appiaworks/shiguchi-cursus-client`](../../../shiguchi-cursus-client-js/README.md) (experimental,
  TypeScript, browser and Node) does the reconnect, the resubscribe with `after`, the token
  refresh and the gap reporting that every consumer of this protocol otherwise rewrites.

### Cookie connections

With [browser sign-in](../bff/index.md) on, a browser holding the session cookie opens
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
  subscription ends with an `UNAUTHORIZED` "session ended" error. See [bff.md](../bff/push-with-cookie.md#rest-grpc-web-and-graphql-streams).

## gRPC, gRPC-Web and typed JSON

The push surface is also a gRPC service, `shiguchi.cursus.v1.PushService`
([push.proto](../../../shiguchi-proto/src/main/proto/shiguchi/push.proto), shipped in
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

The JSON of an event is printed once, when it is published, and stored as text. Its doubles and floats
follow `json.print.number-format` of the service the message belongs to (the global setting for a `json`
payload), so `1.0` is stored as `1` under `ecmascript`. Changing the option does not rewrite events already
stored: history keeps the text it was printed with. See [json-types.md](../json-types.md#doubles-floats-and-googleprotobufvalue-numbers).

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
[grpc-web.md](../grpc-web.md#server-streaming-from-connect-es). `shiguchi.rest.max-response-body`
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
- **Live queries share the broker.** With `shiguchi.graphql.live.enabled`, a GraphQL `query ... @live`
  is invalidated across instances through this module's broker: one topic, `shiguchi.graphql:live.invalidate`,
  published without a namespace declaration, no history and no authorization (nothing client-visible rides on it),
  so it needs an engine shared by the instances (`REDIS`, `POSTGRES` or `JDBC`; `MEMORY` is per instance). A method's
  `(shiguchi.graphql).invalidated_by` can also name exact topics of this module, whose events re-run its live
  queries. See [graphql-annotations.md](../graphql-annotations.md#live).
- **Out of scope for now.** Federation of these fields, and the declarative `(push.topic)` RPC
  option that would derive fields from upstream streams.
