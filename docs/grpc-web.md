# gRPC-Web

Browser code cannot speak native gRPC: it has no access to HTTP/2 trailers. The
[gRPC-Web protocol](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md) works around
that, and usually a translating proxy sits in front of the backend. With
`shiguchi.grpc-web.enabled: true` the gateway does that job itself, on its normal HTTP port,
for every service it has loaded:

```yaml
shiguchi:
  grpc-web:
    enabled: true
    path-prefix: ""        # optional, e.g. /grpc-web
  cors:
    enabled: true          # a browser on another origin needs this too
    allowed-origins: [https://app.example.com]
```

A client points at the gateway and calls methods by their gRPC path:

```ts
// grpc-web (protoc-gen-grpc-web)
const client = new LibraryClient("https://gateway.example.com");
client.getBook(new GetBookRequest().setId("42"), {}, (err, book) => { /* ... */ });

// Connect-ES, gRPC-Web transport
const transport = createGrpcWebTransport({ baseUrl: "https://gateway.example.com" });
const client = createClient(Library, transport);
const book = await client.getBook({ id: "42" });
```

With a `path-prefix`, put it in the client's base URL (`https://gateway.example.com/grpc-web`).

## What is supported

| | |
| --- | --- |
| Content types | `application/grpc-web`, `application/grpc-web+proto`, `application/grpc-web-text`, `application/grpc-web-text+proto` |
| Path | `POST <path-prefix>/<package.Service>/<Method>` |
| Unary | yes |
| Server streaming | yes; each message is flushed as the backend produces it, and the browser's demand flows back to the backend |
| Client / bidi streaming | no — gRPC-Web cannot carry a client stream; the call ends with `UNIMPLEMENTED` |
| `-text` (base64) | yes, both directions; each response frame is encoded on its own so a stream decodes as it arrives |
| Compressed request frames | no; answered with `INVALID_ARGUMENT` |

Nothing is transcoded: the request and response message bytes are passed through unchanged,
so no `google.api.http` annotation is needed and the call costs no JSON work. The HTTP status
is always `200`; the outcome is in the trailers frame at the end of the body
(`grpc-status`, `grpc-message`, and `grpc-status-details-bin` when the backend attached rich
error details). A call that fails before any message is sent also carries `grpc-status` and
`grpc-message` as response headers.

## What the call goes through

The call reaches the backend over the same channel as the REST surface, so it gets the same
load balancing, retries and hedging, TLS, forwarded headers and outbound metrics
(`grpc.client.call.*`).

- **Security.** The gRPC-Web paths fall under the REST surface's rule
  (`shiguchi.security.surfaces.rest`). An unauthenticated call to a gated gateway is an HTTP
  `401`, which gRPC-Web clients report as `UNAUTHENTICATED`. Method scopes and
  `GatewayAuthorizer` rules are enforced as for REST, and a refusal is `PERMISSION_DENIED` in
  the trailers. A method has no path variables or body fields here, so an authorizer rule that
  reads them sees none.
- **Exposure.** A method hidden by the service's `include-methods` / `exclude-methods` is
  `UNIMPLEMENTED`, as is one the gateway has not loaded.
- **Deadline.** The client's `grpc-timeout` header is honoured. On a unary call the service's
  `deadline` (else `shiguchi.client.default-deadline`) caps it; a stream has only the
  client's.
- **Request size.** `shiguchi.rest.max-request-body` bounds the request body; a larger one
  is `RESOURCE_EXHAUSTED`.
- **Response size.** `shiguchi.rest.max-response-body` bounds a unary response's message,
  and **each message** of a server stream, not the stream's running total: a stream is not
  buffered and may run for days, so a total would end every long-lived subscription. Over it the
  call ends with `RESOURCE_EXHAUSTED` trailers carrying an `ErrorInfo` with reason
  `RESPONSE_TOO_LARGE` (`size` is the offending message); frames already sent stay sent.
- **CORS.** With `shiguchi.cors.enabled` and no explicit `cors.paths`, a path that names a
  loaded method gets the CORS grant, with `grpc-status`, `grpc-message` and
  `grpc-status-details-bin` exposed so the client can read a trailers-only failure. Other paths,
  REST routes included, stay CORS-free. If you set `cors.paths` yourself, include the gRPC-Web
  paths (for instance `/grpc-web/**` with a prefix) and expose those headers in your own
  `CorsConfigurationSource`.

## Server streaming from Connect-ES

A long-lived server stream works from a browser through the same transport. This is
`PushService/Subscribe` ([push.md](push.md#grpc-grpc-web-and-typed-json)) with Connect-ES v2: the
client is generated from `push.proto` by `protoc-gen-es`, and `createGrpcWebTransport` speaks the
gateway's protocol.

```ts
import { Code, ConnectError, createClient, type Interceptor } from "@connectrpc/connect";
import { createGrpcWebTransport } from "@connectrpc/connect-web";
import { PushService, type PushEvent } from "./gen/dev/appiaworks/shiguchi/proto/push_pb";

// The bearer token goes on every call, the long-lived Subscribe included.
let token = "initial-jwt"; // replaced by your own refresh logic
const auth: Interceptor = (next) => async (req) => {
  req.header.set("Authorization", `Bearer ${token}`);
  return next(req);
};

const transport = createGrpcWebTransport({
  baseUrl: "https://gateway.example.com", // add the path-prefix here when one is set
  interceptors: [auth],
});
const client = createClient(PushService, transport);

declare function refreshToken(): Promise<string>;

/** Subscribe until `signal` aborts, reconnecting from the last event id seen. */
async function follow(topics: string[], onEvent: (ev: PushEvent) => void, signal: AbortSignal) {
  let after = ""; // an AT_LEAST_ONCE topic resumes after this id
  while (!signal.aborted) {
    try {
      for await (const ev of client.subscribe({ topics, after }, { signal })) {
        after = ev.id;
        onEvent(ev);
      }
    } catch (e) {
      if (signal.aborted) return;
      // The gateway ends a stream with UNAVAILABLE (rebalance, shutdown), or RESOURCE_EXHAUSTED
      // when the client read too slowly; a refused token is UNAUTHENTICATED.
      if (ConnectError.from(e).code === Code.Unauthenticated) token = await refreshToken();
    }
    await new Promise((resolve) => setTimeout(resolve, 1000)); // back off, then reopen with `after`
  }
}

const stop = new AbortController();
void follow(["orders:123"], (ev) => console.log(ev.topic, ev.id, ev.payload.case), stop.signal);
// stop.abort() ends the stream.
```

The snippet type-checks against `@connectrpc/connect` and `@connectrpc/connect-web` 2.2.0,
`@bufbuild/protobuf` 2.16.0 and code generated from `push.proto` by `@bufbuild/protoc-gen-es`
2.16.0 (`buf generate`, `target=ts`).

- **Resume.** The stream is a plain server stream: a dropped connection, a `rebalance` at the
  connection lifetime or a gateway restart ends it, and the client reopens it with `after` set to
  the last event id it saw. On an `AT_LEAST_ONCE` topic with history the gateway replays what was
  missed, then goes live; on `AT_MOST_ONCE` there is nothing to replay.
- **Token refresh.** The bearer token is read when the call opens; a stream does not carry a new
  one. To change it, end the stream (abort, or let it end) and reopen with `after`. Cookie
  sessions for browsers are not part of this yet.
- **Size cap.** `rest.max-response-body` limits each event, not the stream, so a subscription is
  not cut after some number of bytes. An event over the cap ends the stream with
  `RESOURCE_EXHAUSTED`.
- **Idle streams.** gRPC-Web has no ping frame, and the gRPC `Subscribe` stream sends nothing while
  no event is published (the SSE and WebSocket endpoints do send keepalives; this one does not). A
  proxy or load balancer in front of the gateway that closes idle connections will end a quiet
  stream: raise its idle or read timeout above the quietest period you expect (for nginx,
  `proxy_read_timeout`; for an AWS ALB, the idle timeout) and rely on the reconnect loop above
  for the rest. Do not set `timeoutMs` on the call: it is a deadline for the whole stream.
- **Binary and text framing.** Connect-ES sends `application/grpc-web+proto` (binary framing,
  the default; `useBinaryFormat: false` selects a JSON variant the gateway does not serve) and
  does not implement `grpc-web-text`. The gateway also accepts `application/grpc-web-text` for
  clients that need it (the `grpc-web` package does, for streaming over `XMLHttpRequest`); each
  response frame is then base64-encoded on its own.

## What it does not go through

- **The inbound rate limiter's default paths**, which are the REST routes and GraphQL. List the
  gRPC-Web paths in `shiguchi.rate-limit.paths` to limit them.

The access log does cover gRPC-Web calls, with `protocol` `grpc-web`
([observability.md](observability.md#access-log)), and gateway-side protovalidate
(`shiguchi.validation.gateway-enabled`) checks their request messages: the message is parsed
for the check only when validation is on, and a violation answers trailers-only
`INVALID_ARGUMENT` with the `google.rpc.BadRequest` details ([validation.md](validation.md)).

How this compares with running a separate gRPC-Web proxy: see
[comparison.md](comparison.md#grpc-web).
