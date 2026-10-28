# Configuration reference

All gateway configuration lives under the `shiguchi.` prefix (`GatewayProperties`,
plus `ValidationProperties` for validation). Minimal working example:

```yaml
shiguchi:
  services:
    - name: my-grpc-service
      host: localhost
      port: 9090
      mode: DYNAMIC          # DYNAMIC | STATIC | PB_FILE | REST_DOWNSTREAM
  graphql: { enabled: true }
  openapi: { enabled: true }
  ui:      { enabled: false }   # enable only behind auth
```

## Full example

Every feature in one annotated `application.yml` (delete what you don't need — only
`services[]` is required):

```yaml
shiguchi:
  services:
    # Static address, TLS with a private CA, 5s deadline, hide debug methods
    - name: library-service
      host: library.internal
      port: 9443
      mode: DYNAMIC
      tls:
        trust-cert-collection: /etc/gateway/ca.pem     # omit for system trust store
        # cert-chain: /etc/gateway/client.pem          # + private-key => mTLS
        # private-key: /etc/gateway/client-key.pem
      deadline: 5s
      exclude-methods: ["*/Debug*"]
      path-prefix: /library                             # GET /library/v1/books/{id}

    # Resolved through Eureka/Consul/Kubernetes: every registered instance is an address of
    # the channel, calls round-robin across them; grpc-port metadata overrides registry port
    - name: order-service
      discovery-name: order-service
      mode: DYNAMIC
      static-metadata: { x-caller: gateway }            # attached to every outbound call

    # No Spring Cloud: a headless Kubernetes Service (or any multi-A DNS name) balanced by gRPC
    - name: inventory
      target: dns:///inventory-headless.shop.svc.cluster.local:9090
      mode: DYNAMIC
      # load-balancing-policy: round_robin   (the default for discovery-name and target)

  client:
    default-deadline: 30s          # unary calls; per-service deadline overrides
    forward-headers: [x-tenant-id] # extra inbound HTTP headers copied to gRPC metadata
    # forward-tracing-headers: true  (default — W3C/B3/request-id propagate automatically)

  security:
    mode: PERMIT_ALL               # or AUTHENTICATED (requires the JWT config below)
    surfaces:
      ui:      { require-auth: true }   # console + GraphiQL need a JWT…
      openapi: { require-auth: true }   # …and so does Swagger
      # rest / graphql inherit the mode

  cors:
    enabled: true                  # browser surfaces only, unless paths overridden
    allowed-origins: ["https://console.example.com"]
    allow-credentials: true

  access-log:
    enabled: true
    format: JSON                   # one object per line on logger dev.appiaworks.shiguchi.access

  ui:      { enabled: true }       # gated by surfaces.ui above
  openapi: { enabled: true }
  graphql: { enabled: true }

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://idp.example.com   # needed by the gated surfaces
```

## Top level

| Property | Default | Meaning |
| --- | --- | --- |
| `shiguchi.rest.enabled` | `true` | REST route generation and dispatch |
| `shiguchi.rest.auto-mapping` | `false` | Bind each method without a `google.api.http` rule to `POST /{package.Service}/{Method}` (whole body = request message; bidi methods skipped). Per-service override: `auto-mapping`. See [http-annotations.md](http-annotations.md#methods-without-the-annotation) |
| `shiguchi.graphql.enabled` | `true` | GraphQL schema generation + `/graphql` endpoint |
| `shiguchi.graphql.federation.enabled` | `false` | Apollo Federation subgraph protocol (`@key`, `_service`, `_entities`) |
| `shiguchi.graphql.selection-projection` | `true` | Convert only selected composite subtrees of responses |
| `shiguchi.graphql.limits.max-depth` | `0` (unlimited) | Reject queries whose selection is deeper than this (`{ a { b { c } } }` is 3) before any backend is called |
| `shiguchi.graphql.limits.max-complexity` | `0` (unlimited) | Reject queries with more fields than this in their selection (graphql-java's default one-point-per-field count) |
| `shiguchi.graphql.document-cache.enabled` / `max-entries` / `expire-after-access` | `false` / `2000` / `1h` | Keep parsed and validated query documents by query text, so a repeated query skips parsing and validation; emptied on every schema reload. See [caching.md](caching.md#graphql-document-cache-and-persisted-queries) |
| `shiguchi.graphql.persisted-queries.enabled` / `max-entries` / `expire-after-access` | `false` / `1000` / `1h` | Apollo automatic persisted queries: clients send the query's SHA-256 in `extensions.persistedQuery`, the text only once; emptied on schema reload. Leave `dgs.graphql.apq.enabled` off with it |
| `shiguchi.graphql.data-loader.max-batch-size` | `0` (unlimited) | Split a type's DataLoader batch into calls of at most this many ids |
| `shiguchi.graphql.data-loader.cache.<Type>.ttl` | `1h` | Opt-in cross-request cache for the DataLoader of `<Type>` (the `loader_for` name): loaded entities are served for this long without calling the backend. Keyed by id only, whoever asks (the caller's identity is not part of the key), so list only types whose entities are the same for every caller |
| `shiguchi.graphql.data-loader.cache.<Type>.max-size` | `10000` | Most entities of `<Type>` kept in that cache |
| `shiguchi.openapi.enabled` | `true` | OpenAPI 3 spec + Swagger UI |
| `shiguchi.openapi.global-security` | `[]` | Scheme ids applied as global security requirement |
| `shiguchi.openapi.security-schemes.*` | `{}` | Named schemes for the Swagger Authorize dialog (`type`: `http`/`apiKey`/`oauth2`/`openIdConnect` with the usual sub-fields) |
| `shiguchi.headers.enabled` | `true` | Security response headers on the browser surfaces (console, GraphiQL, GraphQL, Swagger) — never on transcoded REST routes |
| `shiguchi.headers.content-security-policy` | a policy fitting the bundled pages | `Content-Security-Policy`. Replace it when serving your own assets or embedding the console; empty disables the header |
| `shiguchi.headers.referrer-policy` | `no-referrer` | `Referrer-Policy`; empty keeps Spring Security's default |
| `shiguchi.headers.permissions-policy` | device APIs off | `Permissions-Policy`; empty disables the header |
| `shiguchi.headers.strict-transport-security` | `""` (off) | `Strict-Transport-Security`, sent only on HTTPS. Off because an ingress usually owns it and a wrong `max-age` outlives the mistake |
| `shiguchi.headers.custom` | `{}` | Extra headers sent verbatim on the browser surfaces |
| `shiguchi.request-id.enabled` | `true` | Give every request an id, in the Reactor context and SLF4J MDC (`requestId`), echoed to the caller |
| `shiguchi.request-id.header` | `X-Request-Id` | Header the id is read from and echoed in |
| `shiguchi.request-id.trust-inbound` | `true` | Reuse the caller's id. Turn off on an internet-facing gateway: the id reaches the logs. A caller-supplied id is always trimmed to 128 printable characters |
| `shiguchi.request-id.include-in-response` | `true` | Send the id back on the response |
| `shiguchi.security.mode` | `PERMIT_ALL` | `PERMIT_ALL` (open, WARN at startup) or `AUTHENTICATED` (JWT required on every request). Guide: [security.md](security.md) |
| `shiguchi.ui.enabled` | `false` | Embedded gRPC web console |
| `shiguchi.ui.path` | `/grpc-ui` | Console base path |
| `shiguchi.ui.require-auth` | `false` | Gate the console behind an authenticated principal |
| `shiguchi.grpc.enabled` | `false` | Embedded native gRPC proxy server |
| `shiguchi.grpc.port` | `9090` | Proxy listen port |
| `shiguchi.grpc.rate-limit.enabled` | `false` | Per-method token-bucket rate limiting on the proxy |
| `shiguchi.grpc.rate-limit.requests-per-second` | `100.0` | Bucket rate |
| `shiguchi.client.keep-alive-time` | `30s` | Interval between HTTP/2 keepalive pings on outbound channels (detects half-open connections through LBs/NATs) |
| `shiguchi.client.keep-alive-timeout` | `5s` | Wait for a ping ack before declaring the connection dead |
| `shiguchi.client.keep-alive-without-calls` | `false` | Ping idle channels too — enable only when backends permit it (`GOAWAY: too_many_pings` risk) |
| `shiguchi.client.idle-timeout` | `0` (gRPC default) | Close idle transports after this period, reconnect lazily |
| `shiguchi.client.discovery-refresh` | `30s` | How often a `discovery-name` channel re-reads the instance list (gRPC also refreshes after a connection failure) |
| `shiguchi.client.max-inbound-message-size` | 4 MiB | Cap on one backend response message; larger → HTTP 502 `RESPONSE_TOO_LARGE`. Per-service override: `max-inbound-message-size` |
| `shiguchi.rest.max-response-body` | unset (no cap) | Largest response body sent, as written (JSON, codec bytes, raw `HttpBody` data, a merged page, a gRPC-Web unary message, or a whole `HttpBody` download). On a server stream (transcoded NDJSON/SSE, gRPC-Web) it limits **each message**, not the running total, so a long-lived stream is not cut; larger → HTTP 502 `RESOURCE_EXHAUSTED` with `ErrorInfo` `RESPONSE_TOO_LARGE`, in-band once a stream has started. See [error-handling.md](error-handling.md#status-mapping-grpc--http) |
| `shiguchi.rest.max-request-body` | `4MB` | Largest request body a transcoded REST route accepts (JSON or codec); larger → HTTP 413 `RESOURCE_EXHAUSTED` before parsing, whether declared by `Content-Length` or found while reading. GraphQL bodies are bounded by `spring.codec.max-in-memory-size` (256 KB by default) |
| `shiguchi.rest.honor-grpc-timeout` | `true` | Take a REST caller's `grpc-timeout` header (`500m`, `2S`) as the backend deadline: the shorter of it and the configured one for unary calls, the caller's for streams. Malformed values are ignored. See [error-handling.md](error-handling.md) |
| `shiguchi.rest.forward-response-headers` | `[]` | Backend response headers and trailers copied onto the HTTP response, by name (case-insensitive) or `*` for all. Unary and client-streaming routes forward headers and trailers; server streams forward headers only; merge routes none. `grpc-*`, `-bin`, framing/hop-by-hop headers and headers the gateway set itself are never forwarded. Per-service override: `forward-response-headers`. See [http-annotations.md](http-annotations.md#backend-response-headers-and-trailers) |
| `shiguchi.rest.request-validation.reject-unknown-query-parameters` | `false` | Answer HTTP 400 `INVALID_ARGUMENT` with a `google.rpc.BadRequest` (reason `UNKNOWN_QUERY_PARAMETER`) when a query parameter matches no request field, instead of ignoring it. Per-service override: `reject-unknown-query-parameters`. See [http-annotations.md](http-annotations.md#unknown-query-parameters) |
| `shiguchi.rest.request-validation.ignored-query-parameters` | `[]` | Query parameters never reported as unknown (`access_token`, a cache buster, ...). The gateway's own `pageSize`, `pageToken`, `first`, `after`, `sort` and `fields` never need listing |
| `shiguchi.rest.request-validation.reject-conflicting-values` | `false` | 400 `BINDING_CONFLICT` when a path variable or query parameter sets a field to a *different* value than the body (or each other); the same value twice is accepted. See [http-annotations.md](http-annotations.md#conflicting-values) |
| `shiguchi.rest.request-validation.case-insensitive-enums` | `false` | Accept enum names in any case (`red` for `RED`) in query, path and JSON body |
| `shiguchi.rest.response-cache.enabled` | `false` | Answer `GET`s of unary routes that have a cache policy (`services[].cache` rule or proto `cache_ttl_seconds`) from memory while fresh; keyed by method, path, query, format and, unless `PUBLIC`, the caller's forwarded identity; looked up after authorization. See [caching.md](caching.md#the-response-cache) |
| `shiguchi.rest.response-cache.max-size` / `max-entry-size` | `64MB` / `1MB` | Total body bytes kept (least recently used evicted first); a larger single body is served but not kept |
| `shiguchi.rest.coalesce-requests` | `false` | Identical concurrent `GET`s of a unary route (same cache key, caller identity included) share one backend call. See [caching.md](caching.md#request-coalescing) |
| `shiguchi.rest.resource-etags` | `false` | AIP-154: a response's `etag` field becomes the `ETag` header (304 on a matching `If-None-Match`), `If-Match` fills the request's `etag` (or `<body>.etag`), and the backend's `ABORTED` / `FAILED_PRECONDITION` to such a request becomes 412. See [caching.md](caching.md#resource-etags-aip-154) |
| `shiguchi.rest.compression.enabled` / `min-size` / `mime-types` | `false` / `1KB` / `[application/json, text/*]` | Gateway-side gzip of unary REST bodies the caller's `Accept-Encoding` allows, with a representation-specific `ETag` (`"x-gzip"`) and the gzip form kept in cached entries. Prefer it to `server.compression`, under which hash tags go out weak. See [caching.md](caching.md#compression) |
| `shiguchi.rate-limit.enabled` | `false` | Per-caller token-bucket limiting of REST and GraphQL requests (429 `RESOURCE_EXHAUSTED` + `Retry-After`); the full key list is in [rate-limiting.md](rate-limiting.md) |
| `shiguchi.rate-limit.backend` | `MEMORY` | `MEMORY` (per instance) or `REDIS` (shared across instances, needs `spring-boot-starter-data-redis-reactive`) |
| `shiguchi.rate-limit.replenish-rate` | `100` | Tokens per second |
| `shiguchi.rate-limit.burst-capacity` | `200` | Bucket size |
| `shiguchi.rate-limit.key` | `PRINCIPAL_OR_IP` | Who gets a bucket: `PRINCIPAL_OR_IP`, `PRINCIPAL`, `IP` (first `client-ip-header` hop, else socket peer) |
| `shiguchi.rate-limit.paths` / `exclude-paths` | `[]` | Ant patterns; empty `paths` = every gateway route and the GraphQL endpoint, actuator never |
| `shiguchi.rate-limit.redis.key-prefix` / `fail-open` | `shiguchi:rate-limit:` / `true` | Redis key prefix; allow or refuse when Redis is unreachable |
| `shiguchi.cursus.enabled` | `false` | Server-to-client messaging over SSE, WebSocket and gRPC (experimental): `<path>/subscribe`, `<path>/ws`, `<path>/publish`, `<path>/topics/{topic}`, `<path>/v1/*`; every key is in [push.md](push.md#configuration) |
| `shiguchi.cursus.broker` / `store` | `MEMORY` | `MEMORY` (per instance), `REDIS` (shared across instances, needs `spring-boot-starter-data-redis-reactive`; `redis.key-prefix` defaults to `shiguchi:cursus:`) or `POSTGRES` (shared, needs `spring-boot-starter-data-r2dbc` + `r2dbc-postgresql` and `spring.r2dbc.*`); `store` also takes `JDBC` (shared, any SQL database through a `DataSource`, needs `spring-jdbc` and a driver; `broker: JDBC` polls the JDBC store's table every `jdbc.poll-interval`, so it needs `store: JDBC`) |
| `shiguchi.cursus.postgres.*` | `push_event` / `shiguchi_cursus` / `true` / `1m` / `16` / `7900` | `table`, `channel`, `create-schema`, `sweep-interval`, `trim-every`, `notify-max-bytes` |
| `shiguchi.cursus.jdbc.*` | `""` / detected / `push_event` / `true` / `1m` / `16` / `500ms` | `data-source` (bean name; empty: the single `DataSource`), `dialect` (`H2`, `POSTGRES`, `MYSQL`, `SQLSERVER`, `ORACLE`, `GENERIC`; empty: detected), `table`, `create-schema`, `sweep-interval`, `trim-every`, `poll-interval` (the JDBC broker's poll interval, at least `50ms`; its latency). See [push.md](push.md#the-jdbc-engine) |
| `shiguchi.cursus.namespaces.<name>.*` | — | Per-namespace policy: `qos`, `history-size`, `history-ttl`, `client-queue-size`, `auth`, `publish-auth`, `client-publish`; `payload-type`, `graphql`, `graphql-field` for the namespace's GraphQL subscription field; `delivery-log` to record every delivery per connection in the store; `session-expiry`, `session-key`, `max-queued` for persistent sessions; `session-key: GROUP` with `ack-deadline`, `max-deliveries`, `max-inflight`, `ordering` (`NONE` default, `KEY`, `STRICT`) for consumer groups |
| `shiguchi.cursus.ws.enabled` / `allowed-origins` | `true` / `[]` | The WebSocket endpoint and the browser origins allowed besides the gateway's own |
| `shiguchi.cursus.admin.role` | `` | Role the writes of the `gateway-push` actuator endpoint need (disconnect, test publish, republish, tombstone) and the NDJSON export at `<cursus.path>/export`; empty: any authenticated caller |
| `shiguchi.cursus.admin.payload-visibility` / `republish.*` | `METADATA` / `10000` / `500` / `[]` | What the archive's message views show (`NONE`, `METADATA`, `FULL`); bulk republish `max-batch`, `rate-per-second`, `namespaces` |
| `shiguchi.cursus.upstream.*` | `10s` / `15s` / `1s` / `30s` / `` | Topics backed by an upstream stream (`(shiguchi.api.topic)`) and the sources' leases: `idle-linger`, `lease-ttl`, `retry-backoff`, `max-retry-backoff`, `instance-id` (deprecated: use `cursus.instance-id`) |
| `shiguchi.cursus.sources[i].*` | — | External records as events: `type` `KAFKA` (`kafka.bootstrap-servers`, `topics`, `start`, `properties`; `payload-type`, `payload-format`) or `GRPC_STREAM` (`grpc.service`, `method`, `request`; `key-field`), `topic-template`, `attributes`, `shared` |
| `shiguchi.cursus.hooks.grpc-service` / `idle-linger` / `timeout` | `` / `10s` / `5s` | A served service implementing `shiguchi.cursus.v1.PushHooks`; the idle debounce; the call timeout |
| `shiguchi.cursus.hooks.fail-closed` | `false` | A decision hook (`OnConnect`, `OnSubscribe`, `OnClientMessage`) that times out or fails: `false` allows the client, `true` rejects it |
| `shiguchi.cursus.dlq.max-entries` | `1000` | Dead-lettered records kept per instance |
| `shiguchi.cursus.archive.retention` / `memory-max-events` / `max-scan` | `0` / `10000` / `10000` | The archive behind message search, republish and tombstone: how long events stay (`0`: off; store `POSTGRES` keeps it in its table, `REDIS` in Streams shared by every instance, `MEMORY` in a per-instance ring); `max-scan` bounds the entries one `REDIS` search reads |
| `shiguchi.cursus.grpc.enabled` / `service-name` | `true` / `push` | Host `shiguchi.cursus.v1.PushService` as a STATIC in-process service: native gRPC on the proxy port, gRPC-Web, and JSON at `<path>/v1/publish` / `<path>/v1/subscribe` through the gateway's transcoding |
| `shiguchi.cursus.instance-id` | host name plus suffix | How this instance appears in leases and the connection registry; replaces `upstream.instance-id`, which still applies when this is empty |
| `shiguchi.cursus.peer.address` / `token` | `` / `` | The peer channel between instances: this instance's own gRPC `host:port` as its peers reach it, and the shared secret (`x-push-peer-token`) every instance presents. Both set, and `shiguchi.grpc.enabled`: a targeted `Send` and an admin disconnect reach connections other instances hold; otherwise they stay on this instance |
| `shiguchi.cursus.send.namespace` / `ack-timeout` | `_direct` / `5s` | Targeted `Send`: the namespace whose `publish-auth` rules decide who may send (it must be declared, or `Send` is refused), and how long `require_ack` waits when the request names no `timeout_ms` |
| `shiguchi.cursus.namespaces.<name>.principal-claim` | unset | For `auth: PRINCIPAL_MATCH`: the JWT claim (dotted path) the topic name must match instead of the principal's name. To rename the principal everywhere, use Spring Boot's `spring.security.oauth2.resourceserver.jwt.principal-claim-name` (see [security.md](security.md#principal-name)) |
| `shiguchi.cursus.namespaces.<name>.presence` | `false` | Index who listens to each topic of the namespace in the store, so `GetSubscribers` lists members across every instance; off: a count of this instance's subscribers only |
| `shiguchi.bff.enabled` | `false` | Browser sign-in with a session cookie (experimental, module `shiguchi-bff`). Sign-in endpoints, memory, Redis and JDBC session stores, a default session authenticator and CSRF protection of cookie requests (exact `Origin` match, see [security.md](security.md#cookie-authenticated-requests-csrf-and-websockets)) are built; token refresh (`relay`, one refresh per session across all instances), push connections bound to the session cookie, back-channel logout and revoke-all are built too. See the guide [bff.md](bff.md) and [design/bff.md](design/bff.md) |
| `shiguchi.bff.path` | `/bff` | Base path of the sign-in endpoints (`login/{provider}`, `callback/{provider}`, `session`, `sessions`, `logout`, `providers`). Starts with `/`, not just `/` |
| `shiguchi.bff.external-base-url` | unset | The gateway's public origin, used for the `redirect_uri` sent to the provider (`<base><path>/callback/<provider>`). Unset: derived from the request (honouring the gateway's forwarded-header settings); startup warns when it is unset while `cookie.secure` is `true`, since behind a TLS terminator without forwarded headers the `redirect_uri` becomes `http://` and every sign-in fails with `sign_in_failed` |
| `shiguchi.bff.login-timeout` | `5m` | How long a started sign-in may take to come back to the callback; also the login cookie's lifetime. At most `15m` (startup fails above that) |
| `shiguchi.bff.login-rate-limit.requests-per-second` / `burst` | `0` (off) / twice the rate | Caps sign-in starts (`GET <path>/login/{provider}`) per client address, in this instance only; past the burst the answer is `429` with `Retry-After` and nothing is stored. Both at least 0. The address is the remote address: set `server.forward-headers-strategy` behind a load balancer (startup warns when the limit is on, `external-base-url` is set and it is not). Off by default because users behind one proxy or NAT share an address. See [bff.md](bff.md#sign-in-limits-and-the-login-cookie) |
| `shiguchi.bff.refresh-skew` / `refresh-lease` | `60s` / `10s` | A session whose access token expires within `refresh-skew` is refreshed (`refresh-skew`: 0 up to, not including, 5 minutes) by the next request that reaches the gateway: one refresh per session across all instances, because the issuer revokes the whole refresh-token family when a token is used twice. `refresh-lease` is how long the instance refreshing holds the session's lease in the store before another may take over; it must be at least 8 seconds (the 5-second token endpoint timeout, a 2-second margin and a second to check and store): the refresh gives up after the lease minus 2 seconds and the instance's clock must agree with the other instances' (use NTP; skew must stay well below the lease minus the token-call time). A request whose token is still valid never waits for a refresh; one whose token has expired waits for it, and gets `503` with `Retry-After: 1` if the issuer cannot be reached (the session is kept). An issuer that answers 400 or 401 ends the session (401). Needs a store with leases: the memory, Redis and JDBC stores have them; a `BffSessionStore` of your own must implement `tryLease`, `releaseLease` and `replaceIfVersion`, or an expired token is anonymous as before. JDBC tables created by an earlier version gain `lease_owner` and `lease_until` at startup. Counted by `gateway.bff.refresh{outcome}` ([observability.md](observability.md)) |
| `shiguchi.bff.session.store` / `idle-timeout` / `absolute-timeout` | `MEMORY` / `30m` / `12h` | Where sessions and in-flight logins are kept, how long an unused session lasts, and how long any session lasts after sign-in. `MEMORY`: this JVM only (development, one instance). `REDIS`: needs `spring-boot-starter-data-redis-reactive` and `spring.data.redis.*` (a `ReactiveStringRedisTemplate` bean). `JDBC`: needs `spring-boot-starter-jdbc`, a driver and one `DataSource` bean (H2 and PostgreSQL are tested). Missing dependency or bean fails startup naming it. A `BffSessionStore` bean of your own replaces all three |
| `shiguchi.bff.session.encryption.keys` / `active` / `disabled` | none / none / `false` | Encrypts the access, refresh and ID token of each session in the `REDIS` and `JDBC` stores (AES-256-GCM). `keys` maps a key id (`[A-Za-z0-9_-]{1,32}`) to Base64 of 32 bytes, `active` is the id that encrypts new writes, any listed key decrypts. Startup fails for `REDIS` or `JDBC` unless keys and `active` are valid or `disabled: true` (plaintext tokens, logged as a WARN). `MEMORY` is never encrypted and ignores them. Rotation and key setup: [bff.md](bff.md#encrypting-tokens-at-rest) |
| `shiguchi.bff.session.record-user-agent` | `true` | Keeps the browser's `User-Agent` (cut to 200 characters) with each session, for the `GET <path>/sessions` listing ("my sessions"). `false`: not stored. See [bff.md](bff.md#my-sessions) |
| `shiguchi.bff.session.redis.key-prefix` | `gw:bff:` | `REDIS` store: sessions are hashes at `<prefix>session:<key>`, logins hashes at `<prefix>login:<state>`, each with a Redis expiry. Works on Redis 5 and later and on Cluster (one key per command). Index sets `<prefix>sid:<provider>:<sid>` and `<prefix>sub:<provider>:<subject>` hold the session keys of a `sid` and of a subject; logout-token ids are keys `<prefix>jti:<provider>:<jti>` |
| `shiguchi.bff.session.jdbc.data-source` / `table-prefix` / `create-schema` / `sweep-interval` / `dialect` | unset / `gw_bff_` / `true` / `1m` / detected | `JDBC` store: bean name of the `DataSource` (default: the only one), prefix of the tables `<prefix>session`, `<prefix>login` and `<prefix>jti` (logout tokens seen), create them (and their `expires_at` and subject/`sid` indexes) with `CREATE TABLE IF NOT EXISTS` at startup, how often expired sessions, logins and logout-token records are deleted (reads ignore them regardless; the task stops with the context), and a forced dialect (`H2`, `POSTGRES`, `MYSQL`, `SQLSERVER`, `ORACLE`, `GENERIC`) |
| `shiguchi.bff.admin.role` | empty | Role the `gateway-bff-sessions` actuator endpoint needs (read a user's sessions, revoke them), with or without the `ROLE_` or `SCOPE_` prefix. Required to use the endpoint: empty, it answers `403` to every caller (startup logs a WARN when the endpoint is exposed). Browser sessions are refused on every actuator endpoint, whatever this says. Back-channel logout is `POST <path>/backchannel-logout/{provider}`, see [design/bff.md](design/bff.md#10-session-lifecycle-built-in-g5) |
| `shiguchi.bff.cookie.name` / `same-site` / `secure` | `__Host-gw-session` / `LAX` / `true` | The session cookie. A `__Host-` name and `same-site: NONE` each need `secure: true`, or startup fails (set `name` and `secure: false` to try it over plain http) |
| `shiguchi.bff.csrf-header.name` / `value` | empty (off) / `1` | Optional anti-CSRF header: when `name` is set, every cookie-authenticated request on an unsafe method (REST, gRPC-Web, GraphQL, `POST <path>/logout`) must carry it with exactly `value`, else `403`. WebSocket handshakes, reads and bearer callers are exempt; the `415` content-type rule is skipped while it is required. See [bff.md](bff.md#csrf-and-origin-rules) |
| `shiguchi.bff.cookie.persistent` | `false` | `false`: a browser-session cookie. `true`: it also carries `Max-Age` = `session.absolute-timeout` |
| `shiguchi.bff.providers.<id>.*` | — | An OIDC provider the gateway signs users in with; `<id>` matches `[a-z0-9-]{1,32}`. `issuer-uri` and `client-id` (required), `client-secret` (required: the gateway is a confidential client), `scopes` (`openid, profile, email`), `upstream-token-mode` (`RELAY`: the provider's access token goes upstream). Also: `display-name` (shown by `/providers`, default the id), `hint-parameter` (`provider`: the authorize-request parameter that carries the login `hint`), `authorize-parameters` (map, empty: fixed parameters added to every authorize request, e.g. `provider: google`; protocol parameters such as `client_id` or `state` fail startup, a login `hint` replaces the `hint-parameter` one; bracket keys with `_`: `"[resource_id]"`), `pass-through-parameters` (`[prompt, max_age, acr_values, login_hint, ui_locales]`: the login request's query parameters copied onto the authorize request; values are validated, a bad one answers `400 invalid_parameter` without echoing it; protocol parameters fail startup; `[]`: none), `authorization-uri` / `token-uri` / `jwk-set-uri` / `end-session-uri` / `revocation-uri` (each replaces the value from the issuer's discovery document, fetched lazily on first use so a down issuer does not fail startup), `revocation-uri` also names the RFC 7009 endpoint the refresh token is revoked at on logout and admin revoke (none known: not revoked), `audiences` (unset: the access token's `aud` is not checked; otherwise one of them must be present), `jws-algorithms` (`[RS256]`: the signature algorithms accepted on the access, ID and logout tokens, exact names such as `ES256` or `PS256`; `none` and `HS256/384/512` fail startup) and `client-authentication-method` (`BASIC`: `client_secret_basic`; `POST`: `client_secret_post`) |
| `shiguchi.bff.applications.<id>.*` | — | A browser application: `origins` (exact `scheme://host[:port]`, no path or wildcard, never shared between applications), `providers` (ids from `providers`), `return-to` (absolute URLs under one of its origins). `post-logout-redirect` (where the provider sends the browser after logout; default the first `return-to`), `session-claims` (`[name, email, picture]`: the ID-token claims `GET <path>/session` returns; `name`, `email` and `picture` as fields, any other name under `claims`; JSON values at most two levels deep, 4 KB per session, at most 32 names of `[A-Za-z0-9_.:/-]{1,64}`, see [bff.md](bff.md#session-claims)). With several applications a request belongs to the one whose origins hold its `Origin` header, or its own host without one |
| *(validation with `shiguchi.bff.enabled`)* | — | Startup fails, one line each, for: `cors.allow-credentials: true` with `*` in `cors.allowed-origins` (while `cors.enabled`); `set-cookie` (any case) or `*` in `rest.forward-response-headers` or `services[].forward-response-headers`; `cookie` in `client.forward-headers`. `bff.cookie.same-site: NONE` with `cookie.secure: false`. Warnings: `bff.external-base-url` unset while `cookie.secure` is `true` (the `redirect_uri` may become `http://` behind a TLS terminator); and, when `bff.external-base-url` is set, an application cross-site to it while `cookie.same-site` is not `NONE`; an application on another origin than the gateway's that `cors` does not allow with `allow-credentials` |
| `shiguchi.cursus.session-check-interval` | `30s` | Session poll of push connections opened with the session cookie, at least `5s`; see [push.md](push.md#cookie-connections) |
| `shiguchi.cursus.connection.*` | `30m` / `3m` / `15s` / `255` / `65536` | `max-lifetime`, `lifetime-jitter`, `keepalive`, `max-subscriptions`, `max-message-bytes` |
| `shiguchi.client.default-deadline` | `0` (disabled) | Deadline for every outbound **unary** call — `DEADLINE_EXCEEDED` → HTTP 504 when exceeded. Streaming calls are exempt. Override per service with `services[].deadline` |
| `shiguchi.client.http-timeout` | `30s` | Timeout for calls to `REST_DOWNSTREAM` (OpenAPI) backends when nothing more specific is set, on both the REST passthrough (SCG `response-timeout`) and the GraphQL / merge dispatch. Must be positive. A positive `services[].deadline` or `client.default-deadline` wins over it; a zero one does not disable it. `services[].operation-timeouts` wins over all. `rest-batch-loaders[].timeout-seconds` is separate and governs only the batch loader |
| `services[].compression` | `identity` (none) | Compress outbound calls to this backend: `gzip`, or any codec registered in gRPC's `CompressorRegistry`. Per service because it is a trade — worth it for large payloads over a constrained link, pure CPU cost for small messages or a loopback hop. Response compression stays the backend's choice; gRPC advertises gzip support on every call regardless |
| `shiguchi.client.forward-headers` | `[]` | Extra inbound HTTP headers copied onto outbound gRPC metadata (e.g. `x-request-id`, `x-tenant-id`). `authorization` and `x-principal` are always forwarded by the gateway; listing either here is ignored with a startup warning, so a caller cannot supply its own `x-principal` |
| `shiguchi.client.forward-tracing-headers` | `true` | Forward `traceparent`/`tracestate`/`b3`/`x-b3-*`/`x-request-id`/`x-amzn-trace-id`/`x-cloud-trace-context` to backends |
| `shiguchi.rest.pagination.next-page-token-header` | `X-Next-Page-Token` | Response header carrying `page_info.next_page_token` |
| `shiguchi.rest.pagination.total-count-header` | `X-Total-Count` | Response header carrying `page_info.total_count` |
| `shiguchi.rest.streaming.default-format` | `NDJSON` | Framing of a server-streaming response when the caller's `Accept` names none (no header or a wildcard): `NDJSON` or `SSE` (Server-Sent Events). `Accept: text/event-stream` / `application/x-ndjson` always wins. See [http-annotations.md](http-annotations.md#server-sent-events) |
| `shiguchi.rest.streaming.sse-keepalive` | `15s` | On an SSE stream, send a `: keepalive` comment after this long without an event, so idle-timeout proxies keep the connection open; restarts on every event. `0` disables. NDJSON streams get none |
| `shiguchi.access-log.enabled` | `false` | Per-request access log on logger `dev.appiaworks.shiguchi.access` (remote IP, method, path, route, HTTP + gRPC status, duration, principal, user agent) |
| `shiguchi.access-log.format` | `COMMON` | `COMMON` text line or `JSON` object per line ([observability.md](observability.md)) |
| `shiguchi.cors.enabled` | `false` | CORS for the browser surfaces, processed inside the security chain |
| `shiguchi.cors.allowed-origins` | `[]` | Origins allowed to call the browser surfaces |
| `shiguchi.cors.allowed-methods` / `allowed-headers` | `GET,POST,OPTIONS` / `*` | Preflight response values |
| `shiguchi.cors.allow-credentials` | `false` | `Access-Control-Allow-Credentials` |
| `shiguchi.cors.max-age` | `1h` | Preflight cache duration |
| `shiguchi.cors.paths` | browser surfaces | Patterns CORS applies to; default = UI path, `/graphiql`, `/graphql`, Swagger, `/v3/api-docs/**` — REST routes excluded on purpose. With gRPC-Web on, the default also covers paths naming a loaded method, exposing `grpc-status`, `grpc-message`, `grpc-status-details-bin` |
| `shiguchi.grpc-web.enabled` | `false` | Serve gRPC-Web (`application/grpc-web*`, binary and `-text`) on the HTTP port: `POST <path-prefix>/<package.Service>/<Method>`, unary and server-streaming, bytes passed through untranscoded. Guide: [grpc-web.md](grpc-web.md) |
| `shiguchi.grpc-web.path-prefix` | `""` | Prefix the gRPC-Web paths sit under, e.g. `/grpc-web`; empty serves them at the plain gRPC path |
| `shiguchi.security.open-rest-bypasses-chain` | `false` | When the transcoded routes are open, keep them out of Spring Security's filter chain (the chain then matches only the gated actuator endpoints, the browser surfaces and the CORS paths): ≈55 µs less per REST request, no Spring Security response headers or request firewall on those routes. See [security.md](security.md#skipping-the-chain-for-open-rest-routes) |
| `shiguchi.security.session-check-interval` | `30s` | How often a gRPC-Web server stream or GraphQL subscription opened with the session cookie checks that its session is still active (at least `5s`); the longest it outlives a logout on another instance. A logout on the same instance ends it at once. Push has its own, `shiguchi.cursus.session-check-interval`. See [bff.md](bff.md#rest-grpc-web-and-graphql-streams) |
| `shiguchi.security.end-streams-at-token-expiry` | `true` | Ends a gRPC-Web or REST server stream (NDJSON, SSE, `HttpBody` download) or a GraphQL subscription of a bearer (JWT) caller when the token expires, with `UNAUTHENTICATED` "token expired" (`UNAUTHORIZED` for GraphQL); a new operation on a bearer GraphQL WebSocket past `exp` is refused with the same error. The client reconnects with a fresh token. A token accepted within the JWT clock skew ends its stream at once. A stream opened with the session cookie never follows the access token, only its session. `false`: a stream outlives its token. See [bff.md](bff.md#rest-grpc-web-and-graphql-streams) |
| `shiguchi.security.surfaces.{ui,graphql,openapi,rest}.require-auth` | inherit mode | Per-surface auth override: gate single surfaces under `PERMIT_ALL`, or carve surfaces open under `AUTHENTICATED` (`ui` = console + GraphiQL; `rest` = the transcoded routes) |
| `shiguchi.refresh.enabled` | `false` | Periodic schema re-discovery |
| `shiguchi.refresh.interval-seconds` | `300` | Poll interval |
| `shiguchi.health.enabled` | `true` | The `shiguchi` actuator health indicator: per-service load state, routes, channel state, last error ([observability.md](observability.md#health-and-readiness)) |
| `shiguchi.health.readiness` | `ALL_LOADED` | When the application reports ready: `ALL_LOADED` (every configured service loaded), `ANY_LOADED`, or `IGNORE` (backends never affect readiness) |
| `shiguchi.startup.retry-interval` | `30s` | How often services that failed to load at startup are retried in the background; `0` disables (the one retry when the application is ready still runs) |
| `shiguchi.startup.fail-fast` | `false` | Abort startup when a configured service is still not loaded once the application is ready, instead of starting degraded |
| `shiguchi.json.scalar-types.date` | `true` | `google.type.Date` as `"2026-09-22"` (partial dates `"2026-09"`, `"2026"`, `"--09-22"`) instead of `{year, month, day}`; the object form is still accepted on input. Guide: [json-types.md](json-types.md) |
| `shiguchi.json.scalar-types.time-of-day` | `true` | `google.type.TimeOfDay` as `"14:03:11.123"` |
| `shiguchi.json.scalar-types.date-time` | `true` | `google.type.DateTime` as `"2026-09-22T14:03:11-04:00"`, `…[America/New_York]` with a zone, local without either |
| `shiguchi.json.scalar-types.decimal` | `true` | `google.type.Decimal` as the string `"12.34"` |
| `shiguchi.json.scalar-types.money` | `false` | `google.type.Money` as `{"currency":"USD","amount":"12.34"}` instead of `currencyCode`/`units`/`nanos` |
| `shiguchi.json.duration.output` | `PROTO` | `google.protobuf.Duration` text: `PROTO` (`"5400s"`) or `ISO_8601` (`"PT1H30M"`) |
| `shiguchi.json.duration.accept-iso8601` | `true` | Also read ISO-8601 durations on input |
| `shiguchi.json.timestamp.accept-epoch-millis` | `false` | Also read a JSON number of epoch milliseconds as a `Timestamp` on input |
| `shiguchi.json.print.preserve-proto-field-names` / `enums-as-ints` / `omit-default-values` / `number-format` | `false` / `false` / `false` / `java` | REST response layout: proto field names instead of lowerCamelCase, enum numbers instead of names, omit default-valued fields, and `ecmascript` for doubles and floats printed as `JSON.stringify` does (`1`, not `1.0`). Per-service override: `services[].json-print.*`. [json-types.md](json-types.md#response-layout-print-options) |
| `shiguchi.json.print.pretty-print` | `false` | Indent whole-body JSON responses (unary, `response_body`, merged pages; not NDJSON/SSE). Keeps the fast serializer tiers. Per-service override: `services[].json-print.pretty-print` |
| `shiguchi.json.strict-input` | `false` | Refuse a request value that does not fit its field (`"abc"` or `1.5` for an `int32`, a number for a `string`) with HTTP 400 naming field, type and value, instead of coercing it to the field's default. Off for compatibility. [json-types.md](json-types.md#strict-input) |
| `shiguchi.json.int64-as` | `STRING` | 64-bit integers as `STRING` (`"42"`, proto3) or `NUMBER` (`42`). A number above 2^53 loses precision in JavaScript; input accepts both. [json-types.md](json-types.md#64-bit-integers) |
| `shiguchi.validation.gateway-enabled` | `false` | protovalidate constraints checked at the gateway (HTTP 400 / `INVALID_ARGUMENT` on violation). Guide: [validation.md](validation.md) |
| `shiguchi.validation.server-enabled` | `false` | Validation interceptor for an embedded gRPC server |
| `shiguchi.validation.log-violations` | `true` | WARN-log violation paths (never field values) |

The `gateway-refresh` actuator endpoint (expose via
`management.endpoints.web.exposure.include`) triggers re-discovery on demand and always
requires authentication. The read-only `gateway-routes` endpoint, exposed the same way, shows
what actually loaded and which routes each service contributes —
see [observability](observability.md#the-gateway-routes-endpoint). `gateway-traffic` shows and
changes the traffic split of version-routed services at runtime, and reports which versions are
drained; its writes always require authentication —
see [service-discovery](service-discovery.md#changing-the-split-at-runtime-and-knowing-when-a-version-is-drained).

### Startup validation

The configuration is checked while Spring Boot binds it. A mistake stops the application with
the property path, the value and the reason, through Boot's usual failure report:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Binding to target dev.appiaworks.shiguchi.config.GatewayProperties failed:

    Property: shiguchi.services[1].port
    Value: "0"
    Reason: port must be between 1 and 65535 when host is set
```

Checked, per service: a name, unique; an address for gRPC modes (one of `host` + `port`,
`discovery-name`, `target`) with the port in range; `pb-descriptor-path` for `PB_FILE`;
`static-service-classes` or `auto-discover.*` for `STATIC`; an absolute http(s) `openapi-url`
(and `base-url`, when set) for `REST_DOWNSTREAM`; `tls.cert-chain` and `tls.private-key`
together or not at all; a non-negative `deadline`; a `path-prefix` starting with `/`;
`avro.schema-registry-url` for `CONFLUENT` framing and `avro.schema-files` for the `FILES`
source. Globally: positive keepalive, refresh and retry intervals, ports in range, a positive
`max-inbound-message-size`, a `ui.path` starting with `/`, a positive rate limit on the proxy
and, for the inbound limiter, a positive `replenish-rate`, a `burst-capacity` of at least 1
and `requested-tokens` between 1 and the burst. The checks
live in `GatewayPropertiesValidator`, registered as Boot's `configurationPropertiesValidator`
bean; define your own bean of that name and the gateway's backs off — call
`GatewayPropertiesValidator.validate(properties)` from it to keep them.

## Per service (`shiguchi.services[]`)

| Property | Default | Meaning |
| --- | --- | --- |
| `name` | — | Service identifier (used in logs, UI, metrics) |
| `host` / `port` | — | Backend address (or use `discovery-name`) |
| `discovery-name` | — | Reach the backend through Spring Cloud `DiscoveryClient` (Eureka/Consul/K8s) using this service id: every registered instance is an address of the channel, balanced by `load-balancing-policy`, re-read every `client.discovery-refresh` and after a connection failure. Instance metadata `grpc-port` overrides the registry port. See [service-discovery.md](service-discovery.md) |
| `target` | — | Raw gRPC target, wins over `host`/`port` and `discovery-name`: `dns:///headless-svc.ns.svc.cluster.local:9090` (multi-A DNS, e.g. a headless Kubernetes Service), `xds:///name`, or any scheme a registered gRPC resolver handles |
| `load-balancing-policy` | `round_robin` for `discovery-name`/`target`, gRPC default (`pick_first`) for `host`/`port` | gRPC load-balancing policy of the channel |
| `versions.metadata-key` / `versions.schema-from` | `version` / `OLDEST` | `discovery-name` services: the instance metadata key carrying each instance's version, and which version the schema is read from (`OLDEST`, `NEWEST` or an exact version). The schema is read from one pinned instance of that version over a direct connection, never through the balanced channel, so a rolling deploy cannot mix or flip schemas. See [service-discovery.md](service-discovery.md#rolling-deploys-and-versions) |
| `versions.traffic[]` (`version`, `weight`) | — (balance by instance) | `discovery-name` services: split calls by version, weights relative (≥ 0, total > 0, each version once). A running version not listed gets no calls; a weighted version that is down falls back to a running one. See [service-discovery.md](service-discovery.md#moving-traffic-between-versions) |
| `versions.rollout.enabled` | `false` | `discovery-name` services: run automated progressive rollouts — while one version takes every call, a newer registered one is stepped through `steps` and rolled back when it breaches the limits. See [service-discovery.md](service-discovery.md#automated-progressive-rollout) |
| `versions.rollout.steps` / `interval` / `min-calls` | `[5, 25, 50, 100]` / `2m` / `100` | The new version's share per step (increasing, ending at 100), how long each step lasts at least, and how many of its calls must finish in a step before it is judged |
| `versions.rollout.max-error-rate` / `max-p99` | `0.01` / — | The limits a step is judged on: share of the new version's calls ending in `error-codes`, and the p99 of its unary calls (unset: not judged) |
| `versions.rollout.error-codes` | `UNKNOWN, DEADLINE_EXCEEDED, UNIMPLEMENTED, INTERNAL, UNAVAILABLE, DATA_LOSS` | gRPC status codes counted as errors |
| `versions.sticky-header` / `versions.preview-header` | — | Request header whose value keeps one caller on one version (`x-principal` for the authenticated caller), and header naming the version a call is sent to regardless of weight. Must be valid gRPC metadata names, not `grpc-*` or `*-bin` |
| `mode` | `DYNAMIC` | `DYNAMIC` = gRPC server reflection · `STATIC` = compiled stubs on the classpath · `PB_FILE` = descriptor-set file · `REST_DOWNSTREAM` = aggregate an existing REST/OpenAPI service |
| `insecure` | `true` | Plaintext channel; set `false` for TLS with the JVM's default trust store |
| `tls.trust-cert-collection` | — | PEM file with CA cert(s) to verify the backend. Setting any `tls.*` value switches the channel to TLS and ignores `insecure` |
| `tls.cert-chain` / `tls.private-key` | — | PEM client certificate chain + PKCS#8 key for mTLS (both required together) |
| `deadline` | inherit `client.default-deadline` | Per-service unary-call deadline; `0` explicitly disables for this service |
| `retry.enabled` | `false` | Retry failed calls to this backend, as a gRPC service config on its channel, so each attempt is re-resolved and re-balanced. Only methods declared idempotent (or bound to a safe HTTP verb) are retried unless `retry.retry-non-idempotent` is set. → [resilience.md](resilience.md) |
| `retry.max-attempts` | `3` | Total attempts including the first, so 3 means two retries (gRPC caps it at 5) |
| `retry.initial-backoff` / `max-backoff` / `backoff-multiplier` | `100ms` / `1s` / `2.0` | Exponential backoff between attempts |
| `retry.retryable-status-codes` | `[UNAVAILABLE, RESOURCE_EXHAUSTED]` | Failures worth repeating. Codes the backend returns after doing work (`INTERNAL`, `UNKNOWN`) mean a retry may repeat that work |
| `retry.retry-non-idempotent` | `false` | Retry methods not declared `idempotency_level` and not bound to GET/HEAD/OPTIONS. Only with a backend that deduplicates |
| `hedging.enabled` / `max-attempts` / `hedging-delay` | `false` / `3` / `100ms` | Hedged requests (gRPC `hedgingPolicy`): resend an unanswered call after the delay and keep the first answer. Mutually exclusive with `retry`; only idempotent methods unless `hedging.hedge-non-idempotent`. `non-fatal-status-codes` (`[UNAVAILABLE]`) and `throttling.*` as for retry. → [resilience.md](resilience.md#hedging) |
| `retry.throttling.enabled` / `max-tokens` / `token-ratio` | `true` / `10` / `0.1` | Per-channel retry budget (gRPC `retryThrottling`): once failures outnumber successes by about `max-tokens / 2`, failing calls get their first attempt only. → [resilience.md](resilience.md#retry-budget) |
| `circuit-breaker.enabled` | `false` | After `failure-threshold` consecutive failures, fail calls to this backend immediately with `UNAVAILABLE` for `open-duration`, then try again. → [resilience.md](resilience.md) |
| `circuit-breaker.failure-threshold` / `open-duration` / `half-open-calls` | `5` / `30s` / `1` | When it opens, how long it stays open, how many trial calls close it |
| `circuit-breaker.failure-status-codes` | `[UNAVAILABLE, DEADLINE_EXCEEDED, INTERNAL, UNKNOWN]` | Statuses counted as the backend failing; caller errors like `NOT_FOUND` deliberately excluded |
| `outlier-detection.enabled` | `false` | Eject an instance whose calls fail far more than its peers' (gRPC `outlier_detection_experimental` around the channel's policy); needs several addresses (`discovery-name` or a multi-address `target`). Knobs: `interval` (`10s`), `base-ejection-time` / `max-ejection-time` (`30s` / `5m`), `max-ejection-percent` (`50`), `failure-percentage.*` (on: `threshold` 50, `minimum-hosts` 2, `request-volume` 20), `success-rate.*` (off: `stdev-factor` 1.9, `minimum-hosts` 5, `request-volume` 100). → [resilience.md](resilience.md#outlier-detection) |
| `max-concurrent-calls` | `0` (no limit) | Calls to this backend in flight from this instance at once; over it a call fails immediately with `UNAVAILABLE` (HTTP 503) without reaching the backend. Counts logical calls (retries/hedges once) and sits above the circuit breaker, which never sees the rejections. → [resilience.md](resilience.md#concurrency-limit) |
| `channels` | `1` | Channels — HTTP/2 connections per address — opened to this backend, used round-robin. Raise it for a busy backend behind one address (a ClusterIP / L4 balancer pins each connection to one pod, and one connection caps in-flight calls at the backend's `MAX_CONCURRENT_STREAMS`). Breaker and concurrency limit are shared by the pool. → [resilience.md](resilience.md#more-connections-per-backend) |
| `cache[].methods` / `ttl` / `visibility` / `shared-max-age` / `stale-while-revalidate` / `server-cache` | — / `0` / `AUTO` / — / — / inherit | HTTP caching rules for this service's REST routes, first match wins over the proto `cache_ttl_seconds`: method globs, `max-age` (`0` = uncacheable), `AUTO`/`PRIVATE`/`PUBLIC`, `s-maxage`, `stale-while-revalidate`, and whether the response cache keeps them (null = `rest.response-cache.enabled`). See [caching.md](caching.md#http-caching-headers) |
| `include-methods` | `[]` (all) | Glob patterns of methods to expose, matched against `pkg.Service/Method` (a pattern without `/` matches the method name alone) |
| `exclude-methods` | `[]` | Glob patterns of methods to hide from all surfaces (REST, GraphQL, OpenAPI, UI). Exclusion wins over inclusion. Does **not** filter the pass-through gRPC proxy |
| `path-prefix` | — | Prefix for all HTTP routes of this service (e.g. `/ref`) |
| `auto-mapping` | inherit | Per-service override of `rest.auto-mapping` |
| `static-metadata` | `{}` | gRPC headers attached to every outbound call |
| `json-print.preserve-proto-field-names` / `enums-as-ints` / `omit-default-values` / `number-format` / `pretty-print` | inherit | Per-service override of `shiguchi.json.print.*` (REST response layout) |
| `max-inbound-message-size` | inherit | Largest response message accepted from this backend, overriding `client.max-inbound-message-size` for this service only |
| `graphql-enabled` / `rest-enabled` / `openapi-enabled` | inherit | Per-service surface overrides |
| `reject-unknown-query-parameters` | inherit | Per-service override of `rest.request-validation.reject-unknown-query-parameters` |
| `forward-response-headers` | inherit | Per-service replacement for `rest.forward-response-headers`; `[]` forwards none |
| `pb-descriptor-path` | — | `PB_FILE` mode: path to the `.pb` FileDescriptorSet |
| `static-service-classes` | `[]` | `STATIC` mode: `*Grpc` stub class names |
| `auto-discover.classpath` | `false` | `STATIC` mode: ClassGraph scan for stubs |
| `auto-discover.spring-beans` | `false` | `STATIC` mode: scan Spring context for `BindableService` beans |
| `auto-discover.scan-packages` | `[]` | Limit the classpath scan |
| `openapi-url` | — | `REST_DOWNSTREAM`: URL of the backend's OpenAPI 3 document (JSON or YAML). Fetched at startup and on refresh; drives route registration and the merged spec |
| `base-url` | scheme+authority of `openapi-url` | `REST_DOWNSTREAM`: where requests are proxied to. Falls back to `http://host:port` when neither is set |
| `inherit-security` | `false` | `REST_DOWNSTREAM`: copy the backend's `securitySchemes` and per-operation `security` into the merged spec. Off by default: the gateway's own `openapi.security-schemes` describe how callers authenticate |
| `operation-scopes` | `{}` | `REST_DOWNSTREAM`: required scopes per `operationId`, e.g. `{ getItem: [inventory.read] }`. Wins over an `x-gateway-auth-scopes` extension on the operation |
| `operation-timeouts` | `{}` | `REST_DOWNSTREAM`: timeout per `operationId`, e.g. `{ slowReport: 2m }`; values must be positive. Resolution, most specific first: this entry, then a positive `deadline`, then a positive `client.default-deadline`, then `client.http-timeout`. A key matching no `operationId` logs a warning at load |
| `openapi-fetch-timeout` | `10s` | `REST_DOWNSTREAM`: timeout for fetching the document |
| `graphql-type-prefix` | — | `REST_DOWNSTREAM`: prefix for GraphQL type names derived from the document's schemas (`Shop` turns `Item` into `ShopItem`). Use it when two services define the same schema name |
| `upstream-content.preferred` | `application/json` | `REST_DOWNSTREAM`: media type to talk to the backend in, per operation, when the document advertises it and a codec can serve the schema, e.g. `avro/binary` (needs `shiguchi-avro-*-starter`). See [avro.md](avro.md) |
| `upstream-content.fallback` | `[application/json]` | `REST_DOWNSTREAM`: media types to fall back to |
| `avro.schema-source` | `OPENAPI` | `REST_DOWNSTREAM` + Avro: `OPENAPI` (`x-avro-schema` on component schemas) · `FILES` (`avro.schema-files`) · `OPENAPI_AND_FILES` |
| `avro.schema-files` | `[]` | `REST_DOWNSTREAM` + Avro: `.avsc` resources (`classpath:` / `file:`) registered by record name |
| `avro.wire-format` | `BINARY` | `REST_DOWNSTREAM` + Avro: how request bodies are framed, `BINARY` (raw datum), `SINGLE_OBJECT` (`C3 01` + fingerprint) or `CONFLUENT` (`00` + 4-byte registry id; needs `avro.schema-registry-url`). Responses are recognised either way |
| `avro.schema-registry-url` | — | `REST_DOWNSTREAM` + Avro: Confluent-compatible schema registry (`http://user:pass@host:8081`). Bound schemas are registered and every version fetched when the service loads; never called per request |
| `avro.subject-strategy` | `RECORD_NAME` | `REST_DOWNSTREAM` + Avro: registry subject per component, the Avro record's full name or the OpenAPI `COMPONENT_NAME` |
| `avro.subjects` | `{}` | `REST_DOWNSTREAM` + Avro: component name → registry subject, overriding the strategy |
| `avro.registry-timeout` | `10s` | `REST_DOWNSTREAM` + Avro: timeout per registry call at load time |
| `field-overrides."Type.field_name".*` | `{}` | YAML-side GraphQL schema hints — same knobs as the `(shiguchi.api.graphql_field)` proto option (`required`, `id`, `name`, `omit`, `description`, `deprecation-reason`, `default-value`); YAML wins over proto. `resolver` is accepted but reserved for future resolver chaining and has no effect |
| `shiguchi.avro.enabled` | `true` | Avro on the REST surface and to backends; only meaningful with `shiguchi-avro-*-starter`. See [avro.md](avro.md) |
| `shiguchi.avro.framing` | `SINGLE_OBJECT` | How Avro responses to callers are written: `BINARY` (raw datum), `SINGLE_OBJECT` (`C3 01` + schema fingerprint + datum) or `CONFLUENT` (`00` + 4-byte registry id + datum; needs `schema-registry-url`) |
| `shiguchi.avro.schema-registry-url` | — | Confluent-compatible registry the derived request/response schemas are registered with (subject = proto message full name) after every route load; never called per request |
| `shiguchi.avro.registry-timeout` | `10s` | Timeout per registry call at load time |
| `shiguchi.avro.schema-endpoint` | `/avro/schemas` | `GET <endpoint>/{proto full name or fingerprint}` serves the derived Avro schema |
| `shiguchi.avro.uint64-as` | `LONG` | How `uint64` / `fixed64` map into derived Avro schemas: `LONG` (wraps above 2^63) or `STRING` |
| `shiguchi.merge.*` | — | Cross-backend merge routes (`max-sources`, `window-multiplier`, `routes[]`) — documented in [merging.md](merging.md) |
| `rest-batch-loaders[]` | `[]` | `REST_DOWNSTREAM`: endpoints that resolve GraphQL types by id (`type`, `endpoint`, `ids-param`, `key-field`, `result-field`, `batch-endpoint`, `timeout-seconds`) |

## Conventions (no annotation needed)

Field names on request/response messages activate behavior automatically:

- `page_request` → maps `?pageSize`, `?pageToken` (and Relay `first`/`after`) into it, on
  both the REST and GraphQL surfaces
- `sort_fields` → maps `?sort=field:asc,other:desc` (REST) or `sort: [SortFieldInput]`
  (GraphQL)
- `read_mask` → filled from `?fields=` / the `X-Fields` header (REST) or the GraphQL
  selection set (`google.protobuf.FieldMask` pushdown) —
  [details](projections-paging-sorting.md)
- `page_info` (+ a repeated items field) → Relay Connection type in GraphQL,
  `X-Next-Page-Token` / `X-Total-Count` headers in REST

## REST_DOWNSTREAM mode

`mode: REST_DOWNSTREAM` aggregates an existing **non-gRPC** REST service into the gateway:

```yaml
shiguchi:
  services:
    - name: inventory
      mode: REST_DOWNSTREAM
      openapi-url: http://inventory:8080/v3/api-docs
      path-prefix: /inv                        # GET /inv/items/{sku} → GET http://inventory:8080/items/{sku}
      exclude-methods: ["deleteItem"]          # matched against operationId
      operation-scopes:
        createItem: [inventory.write]
      operation-timeouts:
        exportReport: 2m                       # everything else uses client.http-timeout (30s)
```

- **Routes.** The gateway fetches the OpenAPI document from `openapi-url` and registers one
  Spring Cloud Gateway route per operation, under `path-prefix`, with the backend
  (`base-url`) as the route URI. Requests are proxied as-is (no transcoding); the prefix is
  stripped so the backend sees the path its own document declares. Operations hidden by
  `include-methods` / `exclude-methods` (globs against `operationId`, or `METHOD /path` when
  the document has no id) get no route and return 404 at the gateway.
- **Authorization.** Before proxying, the `GatewayAuthorizer` runs with the route's scopes:
  `operation-scopes` from YAML first, else the operation's `x-gateway-auth-scopes` vendor
  extension (a list or a space/comma separated string). The security surface mode applies
  regardless.
- **Timeouts.** Each route carries one resolved timeout (see `operation-timeouts` above). The REST
  passthrough applies it as the route's Spring Cloud Gateway `response-timeout` (a slower backend
  answers 504); the GraphQL and merge dispatch applies the same value (`DEADLINE_EXCEEDED`).
- **OpenAPI.** The document is merged into the consolidated `/v3/api-docs`: paths prefixed,
  hidden operations dropped, component names namespaced as `<service>.<Name>` with every
  `$ref` rewritten (so two backends both defining `Error` coexist), `operationId` and tags
  prefixed with the service name, `servers` removed, `securitySchemes` and `security`
  dropped unless `inherit-security: true`. Vendor extensions are preserved.
- **Refresh.** The document is re-fetched on the refresh schedule and via the
  `gateway-refresh` actuator endpoint; routes and spec are swapped only when its bytes
  changed.
- **GraphQL.** When GraphQL is enabled for the service, every operation becomes a root field:
  `GET` operations are queries, the rest are mutations, named after the `operationId`
  (`list_items` → `listItems`). Path and query parameters and the request body's properties
  form the operation's request type, exposed the same way as for gRPC methods: queries with up
  to five scalar parameters take them as flat arguments, everything else takes one `input`
  object. The response schema becomes the return type, with array responses returned as lists
  and 64-bit integers as `String`. Component schemas become GraphQL types under their own names, so set
  `graphql-type-prefix` when two backends define the same schema name. Behind the scenes the
  document is projected onto a synthetic proto descriptor, so field masks, projections and
  the Map converters work unchanged; the call itself goes over HTTP with the same scope check.
  JSON Schema mapping: `$ref` → type, `object` → type, `additionalProperties` → JSON map,
  `array` → list, string `enum` → enum (`<NAME>_UNSPECIFIED` zero value, values prefixed:
  `ItemStatus` + `on-hold` → `ITEM_STATUS_ON_HOLD`; the backend's own strings are restored on
  the way out and mapped back on the way in),
  `integer` int32/int64, `number` float/double, `allOf` merged, `oneOf`/`anyOf`/untyped → the
  `JSON` scalar. The synthetic service is not exposed on the gRPC proxy port or the console.
- **Federation.** With `rest-batch-loaders[]`, each loader maps a GraphQL type to a REST
  endpoint that resolves entities by id (one batched GET with an ids parameter, or N
  parallel GETs with an `{id}` template). The inbound `Authorization` header is forwarded.
- **Avro.** With `shiguchi-avro-*-starter` on the classpath and `upstream-content.preferred:
  avro/binary`, operations that advertise `avro/binary` in the document and whose schema
  carries `x-avro-schema` are called in Avro instead of JSON: `Accept` asks for Avro first,
  request bodies are encoded, and responses are decoded by the `Content-Type` actually
  returned. GraphQL results and JSON responses are unchanged. → [avro.md](avro.md)

## Extension points (beans)

Define any of these beans in your application to replace the gateway default:

These are the types the project promises not to break under you; what that promise covers,
and the build gate enforcing it, are in [api-compatibility.md](api-compatibility.md).

| Bean type | Purpose | Default behavior |
| --- | --- | --- |
| `SecurityWebFilterChain` | Full replacement of the security posture | `security.mode` (PERMIT_ALL / AUTHENTICATED) |
| `GrpcChannelBuilderCustomizer` (0..n) | Customize each outbound channel — add `ClientInterceptor`s (retries, circuit breakers), tune transport. Applied in `@Order` order, per service | — |
| `GatewayAuthorizer` | Authorization decision before dispatch (REST + GraphQL) | `ScopeGatewayAuthorizer` — enforces `auth_scopes` from the proto annotation |
| `GrpcStatusMapper` | gRPC status → HTTP status mapping | Standard transcoding table ([error-handling.md](error-handling.md)) |
| `GatewayErrorRenderer` | Error response body shape (+ content type) | `{"code":"...","message":"..."}` JSON |
| `GrpcMetadataPopulator` | Which inbound context becomes outbound gRPC metadata | principal + authorization + `client.forward-headers` |
| `BffSessionAuthenticator` | With `shiguchi.bff.enabled`: turns the session cookie of a request without an `Authorization` header into a `JwtAuthenticationToken`, refreshing an access token that is about to expire; fails with `BffSessionUnavailableException` (answered 503) when it cannot. Experimental | None yet: nobody is authenticated by cookie |
| `RequestValidationHook` | Request validation before dispatch | protovalidate starter registers one when enabled |
| `UpstreamBodyCodec` (0..n) | A non-JSON body encoding for `REST_DOWNSTREAM` backends: learns each backend's schemas at load time, encodes request bodies, decodes responses by `Content-Type` | `shiguchi-avro-*-starter` contributes the Avro codec |
| `ProtoJson` | Prints and parses proto messages in the gateway's JSON dialect, for application code ([proto-serde-standalone.md](proto-serde-standalone.md#alongside-a-gateway)). Experimental | One built from `shiguchi.json.*`, the global print options and the `ProtoScalarCodec` beans |
| `ProtoScalarCodec` (0..n) | Writes one message type of yours as a single JSON value on every surface ([json-types.md](json-types.md#your-own-scalar-types)). Experimental | The `google.type` messages `shiguchi.json.scalar-types` switches on |
| `RestBodyCodec` (0..n) | A non-JSON body encoding callers may use on the gateway's REST surface for gRPC-backed unary routes, selected by `Content-Type` / `Accept`; advertised in the OpenAPI document | `shiguchi-avro-*-starter` contributes the Avro codec |

### Per-object authorization

The default `GatewayAuthorizer` answers a coarse question: *may this caller call this method?*
It checks the `auth_scopes` declared in the `(shiguchi.api.method)` option against the caller's
JWT scopes. It cannot express *may this caller read **order 42**?* — that needs the request
inputs, which is what the SPI hands you.

`AuthorizationRequest` carries the matched `route`, the `authentication`, the request
`arguments` (REST: path variables merged with query parameters, path variables winning on
collision; GraphQL: the resolved field arguments) and, on REST only, the raw `body`.

The route also carries any `authz` rules declared on the proto annotation (see
[graphql-annotations.md](graphql-annotations.md#authz--object-level-authorization)), which is the
preferred place to state *which* request field holds the object id — the policy then travels with
the service definition instead of the gateway's config. `AuthzRuleResolver.resolve(request)` turns
those rules plus the request arguments into concrete `(relation, object)` pairs, and reports rules
whose id field was absent so you can deny rather than skip them.

```java
@Bean
GatewayAuthorizer objectAuthorizer(ScopeGatewayAuthorizer scopes, MyPolicyClient policy) {
    return request -> scopes.authorize(request)          // keep the coarse scope gate
            .then(Mono.defer(() -> {
                Object id = request.arguments().get("id");
                if (id == null) return Mono.empty();     // nothing object-scoped to check
                return policy.allowed(request.authentication().getName(), "reader", "order:" + id)
                        .flatMap(ok -> ok
                                ? Mono.empty()
                                : Mono.error(new AccessDeniedException("not a reader of order " + id)));
            }));
}
```

Implementations sit on the request hot path and run on Netty event-loop threads, so they must
never block — bridge a `CompletableFuture`-based client with `Mono.fromFuture`, and anything
genuinely blocking with `subscribeOn(Schedulers.boundedElastic())`. A remote policy engine adds
a network round trip per request: apply a timeout, decide deliberately whether a policy-engine
outage fails open or closed, and cache decisions if your policy data changes slowly.

Gateway-level checks are coarse by construction — the gateway sees the request, not the data the
backend is about to return. Treat this as defence in depth, not as a substitute for authorization
inside your services.

Two worked samples show the two shapes a policy engine tends to take:
[`sample-openfga-authz`](../samples/sample-openfga-authz) resolves each `authz` rule into a
`(relation, object)` tuple and asks OpenFGA once per rule, while
[`sample-opa-authz`](../samples/sample-opa-authz) sends one document describing the whole request
and lets a Rego policy decide. The first uses `AuthzRuleResolver`; the second reads
`route.getAuthzRules()` raw, because the resolver's tuple output is deliberately Zanzibar-shaped.

Retries and circuit breaking are configuration now — `services[].retry` and
`services[].circuit-breaker`, see [resilience.md](resilience.md). Reach for a customizer for what
the YAML does not cover, such as gRPC hedging:

```java
@Bean
GrpcChannelBuilderCustomizer hedgingCustomizer() {
    return (service, builder) -> {
        if ("search".equals(service.getName())) {
            builder.defaultServiceConfig(myHedgingConfig).enableRetry();
        }
    };
}
```

GraphQL-specific proto annotations: [graphql-annotations.md](graphql-annotations.md).
Error mapping and custom error bodies: [error-handling.md](error-handling.md).
Metrics, tracing and logging: [observability.md](observability.md).
Security posture and hardening: [../SECURITY.md](../SECURITY.md).
