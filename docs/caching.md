# Caching

The gateway can save work at four points, each opt-in and independent:

| Layer | Saves | Configured by |
| --- | --- | --- |
| [HTTP caching headers](#http-caching-headers) | the whole request, for clients and CDNs that honour them | `services[].cache` rules, or the proto's `cache_ttl_seconds` |
| [Response cache](#the-response-cache) and [request coalescing](#request-coalescing) | the backend call | `rest.response-cache`, `rest.coalesce-requests` |
| [Resource etags](#resource-etags-aip-154) | the response body, and lost updates | `rest.resource-etags` |
| [Compression](#compression) | bandwidth | `rest.compression` |
| [GraphQL document cache and persisted queries](#graphql-document-cache-and-persisted-queries) | parsing and validation, request size | `graphql.document-cache`, `graphql.persisted-queries` |

Everything here applies to unary REST routes. Streams, downloads, merged pages and gRPC-Web are
never cached or compressed by the gateway.

## HTTP caching headers

A method becomes cacheable through a rule on its service, or through the `cache_ttl_seconds`
field of its `(shiguchi.api.method)` proto option. A rule wins over the proto option:

```yaml
shiguchi:
  services:
    - name: catalog
      cache:
        - methods: [GetBook, "List*"]       # glob patterns, as include-methods
          ttl: 60s                          # Cache-Control: max-age; 0 = uncacheable
        - methods: [GetCountryList]
          ttl: 1h
          visibility: PUBLIC                # AUTO (default) | PRIVATE | PUBLIC
          shared-max-age: 6h                # s-maxage, for shared caches only
          stale-while-revalidate: 5m
```

The first matching rule applies. A method matching none keeps its proto TTL with the defaults
(`AUTO`, no `s-maxage`, no `stale-while-revalidate`).

### Visibility

`visibility` decides who may store the response.

- **`AUTO`** (the default) sends `private` whenever the request names its caller: an authenticated
  principal, or an `Authorization` or `Cookie` header. Otherwise it sends no directive. A shared
  cache only refuses on its own to store a response to a request that carries `Authorization`. It
  would happily hand one cookie- or API-key-authenticated user's answer to the next caller, so
  without `private` a per-user response is not safe behind a CDN.
- **`PRIVATE`** always sends `private`: browsers may keep the response, shared caches may not.
- **`PUBLIC`** always sends `public`. Use it only for responses that are the same for everybody:
  any cache may then serve one to anyone, credentials or not. `s-maxage` is only sent for
  responses a shared cache may store.

### What a cacheable response carries

- `Cache-Control` as above.
- `Vary: X-Fields`, since the field projection header selects a different body for the same URL.
  `Accept` is added when a non-JSON codec (Avro, ...) is installed, and `Accept-Encoding` when the
  body is large enough for [compression](#compression).
- A strong `ETag`: the first 128 bits of a SHA-256 over the exact bytes sent. A request whose
  `If-None-Match` names it (or `*`) is answered `304 Not Modified` with no body. A tag weakened by
  an intermediary (`W/"…"`) still matches, since `If-None-Match` uses weak comparison. The call to
  the backend still happens: the tag saves the body on the wire, not the work behind it. To save
  the work too, turn on the response cache.

## The response cache

```yaml
shiguchi:
  rest:
    response-cache:
      enabled: true
      max-size: 64MB        # total bodies kept; least recently used go first
      max-entry-size: 1MB   # larger bodies are served, not kept
```

With it on, a `GET` (or `HEAD`) of a unary route with a cache policy is answered from memory while
the entry is fresh. A rule's `server-cache: false` keeps its methods out while still sending the
headers. `server-cache: true` puts them in even with `enabled: false`.

**The key** is everything that can change the answer: the method, the raw path, every query
parameter with all its values, `X-Fields`, the request body if a GET has one, and the response
format. Unless the policy is `PUBLIC`, the key also includes the caller's forwarded identity: the
principal, `Authorization`, and every `client.forward-headers` value. Tracing headers, which differ
on every request, are left out. Two callers therefore never share an entry unless the rule says the
answer is the same for everybody. This holds for callers authenticated by a session cookie too: the
principal and the session's token are in the key, so two signed-in users never see each other's body.

**Authorization runs first.** The lookup happens after the authorizer, so a caller who may not call
the method never reads its cached answer.

**What is kept:** only 200 responses, never one that sets a cookie, never one larger than
`max-entry-size`. Forwarded backend headers (`forward-response-headers`) are stored with the entry.

**Freshness:** an entry lives for the rule's `ttl`. With `stale-while-revalidate`, it is still
served for that long past the TTL while one background call fetches a fresh copy. Callers never
wait on the refresh.

**Invalidation:** entries also go when any of these happen:

- a successful `POST`, `PUT`, `PATCH` or `DELETE` on the same path;
- the schema reloads;
- `DELETE /actuator/gateway-cache` (everything) or `DELETE /actuator/gateway-cache/{service}` (one
  service's entries).

There is no cross-instance invalidation. Each gateway replica has its own cache, so keep TTLs to
what a replica may serve stale.

**Observability:** cached and coalesced answers carry `Cache-Status` ([RFC 9211]):

| `Cache-Status` | Meaning |
| --- | --- |
| `shiguchi; hit` | served from the cache; `Age` says how old it is |
| `shiguchi; hit; detail=stale` | served stale while a refresh runs |
| `shiguchi; fwd=miss; stored` | this request's own backend call, now cached |
| `shiguchi; fwd=miss; collapsed` | another request's in-flight call, joined |

Other ways to see the cache:

- `GET /actuator/gateway-cache` reports entries, weight, hits, misses, stale serves and coalesced
  requests (expose it with `management.endpoints.web.exposure.include=gateway-cache`).
- The counter `shiguchi.rest.response.cache` has a `result` tag with the same four outcomes.

[RFC 9211]: https://www.rfc-editor.org/rfc/rfc9211

## Request coalescing

```yaml
shiguchi:
  rest:
    coalesce-requests: true
```

Identical concurrent `GET`s of a unary route share one backend call. The second and later callers
wait for the first caller's answer instead of sending their own. "Identical" means the same
response-cache key, caller identity included, so no caller ever receives another caller's answer.
This is independent of the response cache and needs no cache policy. The response cache always
coalesces its own misses, which is what keeps a popular entry's expiry from sending a burst of
calls to the backend.

A caller that disconnects does not cancel the shared call: the others are still waiting for it.

## Resource etags (AIP-154)

```yaml
shiguchi:
  rest:
    resource-etags: true
```

For resources that carry a string field `etag`, as [AIP-154] describes:

- A response whose message has a top-level `etag` sends it as the `ETag` header, quoted. `W/abc`
  becomes `W/"abc"`. This replaces the hash tag. The backend's own version is a better validator,
  and it applies whether or not the route declares a cache policy. `If-None-Match` on a `GET`
  gets a 304 when it matches.
- An `If-Match` header fills the request's `etag` field, without quotes. That is the top-level
  `etag` (AIP-135 deletes) or the `etag` of the message the route's `body` names (AIP-134 updates,
  `body: "book"` → `book.etag`). A body that already carries a different etag is a 400. So is an
  `If-Match` naming more than one tag, since the field holds one. `If-Match: *` sets nothing.
- When the caller sent `If-Match`, a backend answer of `ABORTED` (AIP-154's stale-etag code) or
  `FAILED_PRECONDITION` becomes **412 Precondition Failed**.

The backend does the comparison, since only it knows the current version. The gateway just carries
the tag between HTTP and the message. Store etag values unquoted: the gateway adds quotes for the
header and strips them from `If-Match`.

[AIP-154]: https://google.aip.dev/154

## Compression

```yaml
shiguchi:
  rest:
    compression:
      enabled: true
      min-size: 1KB
      mime-types: [application/json, "text/*"]   # +json types count as application/json
```

The gateway gzips a unary body when the caller's `Accept-Encoding` allows gzip and the body is at
least `min-size`. It adds `Vary: Accept-Encoding` to every response eligible for compression,
whether or not this caller got gzip. The compressed form gets its own strong tag: `"abc"` becomes
`"abc-gzip"`, because they are different bytes. A cached entry keeps its gzip form after the first
time it is sent.

Prefer this to Spring Boot's `server.compression`. That one compresses after the gateway has tagged
the body, so the gateway sends its hash tags weak (`W/"…"`) when it is on, since it can no longer
promise byte identity. It also recompresses every cache hit.

## GraphQL document cache and persisted queries

```yaml
shiguchi:
  graphql:
    document-cache:
      enabled: true
      max-entries: 2000
      expire-after-access: 1h
    persisted-queries:
      enabled: true
      max-entries: 1000
      expire-after-access: 1h
```

**The document cache** keeps each query text's parsed and validated document, so a repeated query
skips both. Validation errors are kept too, so a broken query is not re-validated on every attempt.

**Automatic persisted queries** (the Apollo protocol) let a client send the SHA-256 of its query in
`extensions.persistedQuery` instead of the text:

1. The first time, the gateway answers `PersistedQueryNotFound`.
2. The client resends the request with the text, which registers it.
3. From then on, the hash alone runs the query.

This cuts request size for large queries. A hash that does not match
its text is refused. A query that fails to parse or validate is not registered.

Both caches are emptied whenever the schema reloads. A validation result is only true of the schema
it ran against: kept across a reload, a query using a field a backend just dropped would pass
validation and fail in a data fetcher. That is the difference from DGS's own
`dgs.graphql.preparsedDocumentProvider` and `dgs.graphql.apq`, which do not know when the gateway's
schema changes. Leave those off when using these.
