# `google.api.http` annotations

The REST surface is driven by the standard
[`google.api.http`](https://github.com/googleapis/googleapis/blob/master/google/api/http.proto)
option on each RPC — the same annotation other transcoders and Google's own APIs read. One
annotation gives a method its HTTP verb, its path template, and how the request message is
assembled from the path, the query string and the body. Nothing else is needed: the gateway reads
the option straight from the descriptors it discovers (reflection, `.pb` file, or compiled stubs).

```protobuf
import "google/api/annotations.proto";

service Library {
  rpc GetBook(GetBookRequest) returns (Book) {
    option (google.api.http) = { get: "/v1/books/{id}" };
  }
  rpc ListBooks(ListBooksRequest) returns (ListBooksResponse) {
    option (google.api.http) = { get: "/v1/books" };
  }
  rpc CreateBook(CreateBookRequest) returns (Book) {
    option (google.api.http) = {
      post: "/v1/{parent}/books"
      body: "book"
      additional_bindings { post: "/v1/books" body: "*" }
    };
  }
  rpc UpdateBook(UpdateBookRequest) returns (Book) {
    option (google.api.http) = { patch: "/v1/books/{book.id}" body: "book" };
  }
  rpc DeleteBook(DeleteBookRequest) returns (google.protobuf.Empty) {
    option (google.api.http) = { delete: "/v1/books/{id}" };
  }
  rpc WatchBooks(WatchBooksRequest) returns (stream Book) {
    option (google.api.http) = { get: "/v1/books:watch" };
  }
}
```

A method without the annotation gets **no REST route**. It is still exposed on GraphQL (by
convention or through `(shiguchi.api.graphql)`), on the gRPC proxy, and in the console — only the REST
and OpenAPI surfaces need the HTTP binding. Hide a method from every surface with
`services[].exclude-methods`.

## How a request becomes the gRPC message

Every REST request is transcoded into the RPC's request message, field by field, in this order
(later sources win):

1. **The HTTP body**, parsed as JSON — or Avro, when the caller sends it and the Avro starter is
   on the classpath — onto the field `body` names. `body: "*"` and an absent `body` both map it
   onto the whole request message. A named field must be a top-level field of the request message
   (`body: "book"` fills `CreateBookRequest.book` with the entire body); the spec forbids nested
   paths there and so does the gateway.
2. **Query parameters**, each matched to a request field by name — `?title=Dune` or
   `?title=Dune` on a `string title` field, camelCase (`?pageSize=`) and snake_case
   (`?page_size=`) both accepted. A dotted name reaches into a message field:
   `?book.title=Dune` sets `book.title`. A repeated scalar or enum field takes one element per
   occurrence of its name: `?tag=a&tag=b` sends `["a", "b"]`, nested ones too
   (`?filter.authors=x&filter.authors=y`). A comma is part of the value — `?tag=a,b` sends
   `["a,b"]`, since a comma may be part of a value. A singular field given twice keeps the first
   value.
3. **Path variables**, highest precedence. `{id}` sets `id`; `{book.id}` sets `book.id`. A
   variable spans one segment unless it names a pattern: `{name=shelves/*/books/*}` binds the
   whole resource name (`shelves/s1/books/b2`), and `{path=**}` (or Spring's `{*path}`) the rest
   of the path. Values arrive percent-decoded — see [Path variable values](#path-variable-values).

### Path variable values

Templates follow the grammar in `google/api/http.proto`: literal segments, `*` (one segment),
`**` (the rest of the path, so only last), variables with an optional `=pattern`, and a trailing
custom verb (`:publish`). A request is matched on its raw path, so an encoded `%2F` never splits a
segment, and the bound values are then decoded the way the spec asks:

| Template | Request path | Backend receives |
| --- | --- | --- |
| `/v1/items/{id}` | `/v1/items/a%20b` | `id: "a b"` |
| `/v1/items/{id}` | `/v1/items/a%2Fb` | `id: "a/b"` — a one-segment variable is fully decoded |
| `/v1/{name=shelves/*}` | `/v1/shelves/a%2Fb` | `name: "shelves/a%2Fb"` — `%2F` is kept, since `/` is a separator here |
| `/v1/files/{path=**}` | `/v1/files/docs/my%20file` | `path: "docs/my file"` |
| `/v1/items/{id}` | `/v1/items/a+b` | `id: "a+b"` — `+` is only a space in query strings |

An encoded slash (`%2F`) never reaches the gateway with Spring Security's default firewall, which
answers 400 before routing. If path values legitimately contain `/`, allow it:

```java
@Bean
ServerWebExchangeFirewall firewall() {
    var firewall = new StrictServerWebExchangeFirewall();
    firewall.setAllowUrlEncodedSlash(true);
    return firewall;
}
```

### Unknown query parameters

By default a query parameter that matches no request field is ignored, so `?pageSzie=10` quietly
returns the default page. Set `shiguchi.rest.request-validation.reject-unknown-query-parameters:
true` (or `reject-unknown-query-parameters: true` on one service) to answer it with HTTP 400
instead, before the body is read or any backend is called:

```json
{
  "code": "INVALID_ARGUMENT",
  "message": "Unknown query parameter: pageSzie",
  "details": [ {
    "@type": "type.googleapis.com/google.rpc.BadRequest",
    "fieldViolations": [ {
      "field": "pageSzie",
      "description": "No field named 'pageSzie' on acme.ListBooksRequest",
      "reason": "UNKNOWN_QUERY_PARAMETER"
    } ]
  } ]
}
```

A parameter is known when it names a request field in either spelling, dotted for a nested one
(`?book.title=`). Below a map field, or a `Struct`, `Value` or `Any`, any key is accepted,
since those keys are data. The gateway's own parameters (`pageSize`, `pageToken`, `first`,
`after`, `sort`, `fields`) are always accepted, and so is anything in
`rest.request-validation.ignored-query-parameters` — list `access_token`, cache busters and
the like there. REST_DOWNSTREAM pass-through routes are not checked: they have no request
message to check against, and the backend sees the query string unchanged.

A `+` in a query value is a space and `%2B` a literal plus, as in HTML form encoding:
`?q=a+b` sends `"a b"`, `?q=a%2Bb` sends `"a+b"`.

### Conflicting values

Path variables win over query parameters, and both over the body, silently. With
`shiguchi.rest.request-validation.reject-conflicting-values: true`, a request whose sources
*disagree* about a field is answered 400 instead — a `BadRequest` violation per field, reason
`BINDING_CONFLICT`:

```bash
# PATCH /v1/{book.name=books/*} with body: "book"
curl -X PATCH localhost:8080/v1/books/7 -d '{"name":"books/9","title":"Dune"}'   # 400
curl -X PATCH localhost:8080/v1/books/7 -d '{"name":"books/7","title":"Dune"}'   # fine
```

Only a different value is a conflict. Repeating the same value is how AIP-134 updates carry the
resource name in both the path and the body, so it is always accepted. Values are compared as
values (`7` in JSON equals `?id=7`), and both spellings of a field name (`displayName`,
`display_name`) are the same field.

### Enum case

Proto3 JSON enum names are case-sensitive, so `?color=red` fails for a value declared `RED`.
`shiguchi.rest.request-validation.case-insensitive-enums: true` accepts any case in query
parameters, path variables and JSON bodies — including repeated enums and enums inside nested
messages — and hands the backend the declared name.

## Shaping the response: `response_body`

By default the HTTP body is the whole response message. `response_body` names one top-level field
to send instead:

```protobuf
rpc ListBooks(ListBooksRequest) returns (ListBooksResponse) {
  option (google.api.http) = {
    get: "/v1/books"
    response_body: "books"
  };
}
```

```jsonc
// without response_body
{ "books": [ { "id": "1" } ], "nextPageToken": "abc" }

// with response_body: "books"
[ { "id": "1" } ]
```

This exists because the two surfaces want different things. A gRPC method should return a wrapper
message — a bare `repeated Book` cannot gain a `next_page_token` sibling later without breaking
every client — while a REST caller asking for `/v1/books` usually wants the array. `response_body`
lets one method serve both without a second RPC.

The field is rendered by exactly the same proto3 JSON rules it would follow inside the full
message: 64-bit integers as strings, enums by name, well-known types in their JSON forms. An
unset field still produces a value of the right shape (`[]` for a repeated field, `""` for a
string) rather than `null` or an empty object, so a client never has to special-case an empty
page.

`response_body: "*"` means the whole message, which is the default. A `response_body` naming a
field the response message does not have fails the call rather than quietly sending everything —
it is a proto bug, and a response that merely looks wrong is harder to find than an error.

> Pagination headers (`X-Next-Page-Token`, `X-Total-Count`) are still set from the response's
> `page_info`, which is what makes `response_body: "items"` practical: the cursor moves to a
> header rather than being lost with the wrapper.

The paging, sorting and projection conventions (`?pageSize=`, `?pageToken=`, `?sort=`,
`?fields=`) are recognised by field name on the request message and folded onto their
convention fields — see [projections-paging-sorting.md](projections-paging-sorting.md).

## Backend response headers and trailers

By default nothing the backend sends as gRPC metadata reaches the HTTP caller. List the names
to pass through — or `*` for all of them — and the gateway copies them onto the HTTP response:

```yaml
shiguchi:
  rest:
    forward-response-headers: [x-ratelimit-remaining, etag, x-cost]
  services:
    - name: billing
      forward-response-headers: [x-invoice-id]   # replaces the global list for this service
```

| Route | Forwarded |
| --- | --- |
| Unary, client streaming, `HttpBody` upload | Response headers **and** trailers, success or error. The gateway writes the response only once the call has ended, so trailers are in by then. |
| Server streaming (NDJSON, SSE, `HttpBody` download) | Response headers only. The HTTP headers go out with the first message, before the backend has sent its trailers. |
| Merge | Nothing — several backends answer one request. |

Names match case-insensitively and keep every value (a name in both headers and trailers sends
both). Some never cross, whatever the list says: `grpc-*` and pseudo-headers, binary `-bin`
metadata, framing and hop-by-hop headers (`content-type`, `content-length`, `content-encoding`,
`transfer-encoding`, `connection`, `te`, ...), and any header the gateway already set on the
response, such as the pagination headers or `X-Request-Id`. With retries or hedging on, the
headers are those of the attempt whose answer was used.

Unlike grpc-gateway, names are not prefixed (`Grpc-Metadata-`): a listed header arrives as the
backend named it. Only list what callers should see —
`*` also exposes whatever internal metadata a backend happens to send.

## What each part of the rule does here

| Part | Supported | Notes |
| --- | --- | --- |
| `get` / `post` / `put` / `delete` / `patch` | yes | Verb + path template. The method is the primary route. |
| `custom { kind, path }` | yes | Any other verb (`HEAD`, `OPTIONS`, ...); `kind` is used as the HTTP method. |
| `body` | yes | Field name, `*`, or unset (= whole message). Honoured on the JSON and Avro request paths and reflected in the OpenAPI document: the request body schema is that field's type and the remaining fields become query parameters. |
| `additional_bindings` | yes | One extra REST route per entry, on the same RPC. Extra routes carry the primary's `auth_scopes`, cache TTL and loader hints, but no GraphQL field of their own and never a merge. One level only, as the option itself requires. |
| `response_body` | yes | Unary routes send the named top-level field instead of the whole message, to JSON and Avro callers alike — see [Shaping the response](#shaping-the-response-response_body). Server-streaming and merge routes ignore it and send whole messages. The OpenAPI document describes the same thing: the named field's schema as the `200` JSON response where it applies, the whole message elsewhere. Use `(shiguchi.api.graphql).pluck` for the GraphQL-side equivalent. |
| Path variables `{name}` | yes | Segment-wide. Dotted names nest (`{book.id}`). The OpenAPI document lists them as path parameters with the dotted name. Values are percent-decoded. |
| `{name=segments/*}` value patterns | yes | The variable binds every segment its pattern covers (`shelves/s1/books/b2`). The OpenAPI document writes it as `{name}`. |
| Wildcards `*`, `**`, `{*name}` | yes | `*` is one segment; `**` and `{*name}` match the rest of the path and must come last. |
| Custom verbs `:verb` | yes | `/v1/{name=shelves/*}:publish` routes separately from `/v1/{name=shelves/*}`. |
| `path-prefix` (gateway config) | yes | `services[].path-prefix` is prepended to every template of the service, primary and additional. |

## Methods without the annotation

A method with no `google.api.http` rule gets no REST route by default. Set
`shiguchi.rest.auto-mapping: true` (or `auto-mapping: true` on one service) and each such
method is bound as if it carried

```proto
option (google.api.http) = { post: "/{package.Service}/{Method}" body: "*" };
```

— its gRPC path, so it cannot collide with an annotated route:

```bash
curl -X POST localhost:8080/acme.library.v1.LibraryService/GetBook -d '{"id":"42"}'
```

`path-prefix` applies (`/lib/acme.library.v1.LibraryService/GetBook`), include/exclude-methods
still hide what they hide, and the route shows up in the OpenAPI document like any other.
Server- and client-streaming methods stream as described below; bidirectional ones are skipped.
A method that has a rule never gets the automatic route in addition to it. This is off by default
because it makes methods reachable over REST that were not before — check that auth scopes or
authorization rules cover them before turning it on.

## Binary bodies: `google.api.HttpBody`

For files, images, CSV or any other non-JSON payload, use
[`google.api.HttpBody`](https://github.com/googleapis/googleapis/blob/master/google/api/httpbody.proto)
on either side of the RPC. The gateway then passes bytes through instead of transcoding JSON.

```proto
import "google/api/httpbody.proto";

// Download: the response is the file.
rpc GetCover(GetCoverRequest) returns (google.api.HttpBody) {
  option (google.api.http) = { get: "/v1/books/{id}/cover" };
}

// Upload: the request body is the file; {id} still binds from the path.
message UploadCoverRequest {
  string id = 1;
  google.api.HttpBody file = 2;
}
rpc UploadCover(UploadCoverRequest) returns (Book) {
  option (google.api.http) = { put: "/v1/books/{id}/cover" body: "file" };
}
```

```bash
curl -o cover.png localhost:8080/v1/books/42/cover                    # Content-Type: image/png
curl -X PUT --data-binary @cover.png -H 'Content-Type: image/png' localhost:8080/v1/books/42/cover
```

- **Request:** when the input *is* `HttpBody` (`body: "*"` or no `body`), or `body` names a
  field of that type, the HTTP body is not parsed. Its bytes go into `data` and its
  `Content-Type` into `content_type`. Path variables and query parameters bind the rest of the
  message as usual. `shiguchi.rest.max-request-body` applies.
- **Response:** when the output *is* `HttpBody` (no `response_body`), or `response_body` names a
  field of that type, the gateway sends `data` as the body with `content_type` as the
  `Content-Type` (`application/octet-stream` when the backend leaves it empty). `cache_ttl`
  and ETags work as for JSON. `shiguchi.rest.max-response-body` applies to `data`: over it,
  502 `RESPONSE_TOO_LARGE`.
- `extensions` is neither filled nor sent, since HTTP has nothing to map it from or to.
- The authorizer sees the path and query arguments, never the raw bytes.
- The OpenAPI document describes these bodies as `*/*`, `type: string, format: binary`.

### Streaming `HttpBody`

```proto
rpc Export(ExportRequest) returns (stream google.api.HttpBody) {
  option (google.api.http) = { get: "/v1/exports/{id}" };
}
rpc Import(stream google.api.HttpBody) returns (ImportSummary) {
  option (google.api.http) = { post: "/v1/imports", body: "*" };
}
```

- **Download** (`returns (stream google.api.HttpBody)`): the first message's `content_type`
  becomes the response's `Content-Type`, and every message's `data` is written and flushed as
  it arrives, at the pace the caller reads — a file larger than memory streams straight
  through. The request is built as for a unary route, raw when its input is an `HttpBody`.
  A failure before the first message is an ordinary HTTP error; after it, raw bytes have no
  in-band error form, so the gateway aborts the connection and the caller sees a truncated
  transfer rather than a file that looks complete. `response_body` does not apply to streams.
  `shiguchi.rest.max-response-body` counts the whole download: a first chunk over it is a
  502 `RESPONSE_TOO_LARGE`, and crossing it later aborts the transfer the same way.
- **Upload** (`stream google.api.HttpBody`, or a streamed request whose `body` names an
  `HttpBody` field): the HTTP body is never aggregated. Each chunk that arrives becomes one
  message with the request's `Content-Type`, path and query arguments bound on every message,
  sent as fast as the backend accepts them. The answer is JSON, or raw when it is an
  `HttpBody`. `max-request-body` does not bound a streamed upload, which is the point of
  streaming it; chunk boundaries follow the network, not the file, so the backend should
  concatenate `data` rather than rely on chunk sizes.

## Streaming and merge routes

A server-streaming RPC bound with `get` (or any verb) answers `application/x-ndjson`, one JSON
object per line, flow-controlled end to end.

### Server-Sent Events

Send `Accept: text/event-stream` and the same stream comes back as
[Server-Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html) instead,
one event per message — which a browser reads with no library at all:

```js
const events = new EventSource("/v1/books:watch?shelf=s1");
events.onmessage = (e) => render(JSON.parse(e.data));
events.addEventListener("error", (e) => {
  if (e.data) { console.error(JSON.parse(e.data)); events.close(); }   // the gateway's error event
});
```

```
data: {"id":"1","title":"Dune"}

data: {"id":"2","title":"Emma"}

```

The caller's highest-ranked `Accept` entry decides: `text/event-stream` for SSE,
`application/x-ndjson` or `application/json` for NDJSON. With no `Accept` header, or only a
wildcard, `shiguchi.rest.streaming.default-format` (`NDJSON` unless set to `SSE`) applies.
The payload, backpressure, cancellation on disconnect, auth and rate limiting are identical in
both framings; only the envelope differs. SSE responses carry `Cache-Control: no-cache`.

- A failure after the first message becomes an event named `error` carrying the same body a
  unary failure would get (see [error-handling.md](error-handling.md#errors-in-the-middle-of-a-stream)),
  and the stream ends.
- `EventSource` reconnects on its own whenever a stream ends, including after that error event
  and after a normal end. Close it in your handler when the stream is finite, as above.
- `Last-Event-ID` is not supported: events carry no `id`, since a gRPC stream has no resume
  point the gateway could honour.
- Proxies that buffer responses (nginx's `proxy_buffering`) hold events back; turn buffering off
  for these routes.
- A quiet stream is kept alive: after `shiguchi.rest.streaming.sse-keepalive` (15 s) without
  an event the gateway sends an SSE comment line, `: keepalive`, which `EventSource` ignores and
  which stops proxies and load balancers (often 60 s idle) from closing the connection. The timer
  restarts on every event; `0` turns it off. Keepalives also cover the wait for the first event,
  so once one has been sent a later failure arrives as the `error` event rather than as an HTTP
  error status.

A **client-streaming** RPC is the mirror image: bind it with `post` and `body: "*"`, and the
caller sends `application/x-ndjson` — one JSON message per line — and gets the backend's single
response as ordinary JSON.

```proto
rpc RecordRoute(stream Point) returns (Summary) {
  option (google.api.http) = { post: "/v1/routes:record", body: "*" };
}
```

```bash
printf '{"x":1,"y":1}
{"x":2,"y":2}
'   | curl -X POST --data-binary @- -H 'Content-Type: application/x-ndjson'     localhost:8080/v1/routes:record
```

The upload is never buffered whole: lines are cut out of the request as they arrive and sent as
messages, paced by the backend's flow control, so an upload larger than memory streams through.
That is also why `shiguchi.rest.max-request-body` bounds **one line** here rather than the
whole body — a single unterminated line cannot grow without limit, but the upload as a whole is
deliberately unbounded. Path and query parameters are applied to every message. A line the
gateway cannot parse fails the call with 400 and cancels it, so the rest of the upload is not
sent.

Bidirectional streaming has no REST binding: one HTTP request and response cannot carry an
interleaved conversation. A `google.api.http` rule on a bidi method is ignored with a startup
warning naming the method, rather than producing a route that treats the stream as a unary call. Use
the gRPC proxy port for those.

Merge routes (`shiguchi.merge.routes[]`) are declared in configuration against a path, not
through the annotation.

## Where the annotation is read

`DynamicRouteLoader` (reflection and `.pb` files) and `StaticRouteLoader` (compiled stubs) both
turn the option into `RouteDefinition`s through `HttpRuleBindings`; `GrpcRequestBuilder` applies
the body/query/path assembly above; `GatewayOpenApiBuilder` documents the result. Reflection
needs the annotation's descriptor to survive the trip: backends must include
`google/api/annotations.proto` in what they serve (every gRPC server that links
`proto-google-common-protos` does), else the option arrives as unknown bytes and the method has
no REST route — the gateway parses reflection responses with an extension registry so it can read
the option whenever the backend sends it.
