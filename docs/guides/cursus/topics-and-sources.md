# Cursus: topics, sources and hooks

Part of the [cursus guide](index.md).

## Upstream-backed topics

A server-streaming RPC of a served service can be the source of a topic, declared on the method:

```proto
import "shiguchi/topic.proto";

service OrderService {
  rpc WatchOrder(WatchOrderRequest) returns (stream OrderEvent) {
    option (shiguchi.topic) = { pattern: "orders:{order_id}" };
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

The descriptor must carry the option: with `PB_FILE` include `shiguchi/topic.proto`
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
  `(shiguchi.topic)` opens a stream per topic on demand, a source is one firehose
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
