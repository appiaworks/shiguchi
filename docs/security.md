# Authentication and authorization

How to make the gateway decide who may call what, with Spring Security doing the
authenticating. [SECURITY.md](../SECURITY.md) states the default posture and the surface model;
this page is the configuration guide behind it.

Three questions, three places to answer them:

| Question | Answered by | Where |
| --- | --- | --- |
| Is this caller who they say they are? | Spring Security, as an OAuth2 JWT resource server | `shiguchi.security.*` + `spring.security.oauth2.resourceserver.jwt.*` |
| May this caller call this method, or act on this object? | the gateway's `GatewayAuthorizer` | `auth_scopes` / `authz` in `(shiguchi.api.method)`, or your own bean |
| What does the backend learn about the caller? | `GrpcMetadataPopulator` + the per-service `tls` block | `services[].tls`, `client.forward-headers` |

## Modes and surfaces

The gateway boots open — `security.mode: PERMIT_ALL` — so a first run needs no identity provider,
and says so with a WARN at startup. Production is one of these:

```yaml
shiguchi:
  security:
    mode: AUTHENTICATED             # a valid JWT on every request, every surface
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/main
```

```yaml
shiguchi:
  security:
    mode: PERMIT_ALL                # machine routes stay open behind an authenticating ingress…
    surfaces:
      ui:      { require-auth: true }   # …but the console, GraphiQL and Swagger need a token
      openapi: { require-auth: true }
```

The chain groups paths into four surfaces, each with its own `require-auth` that overrides the
mode's default in either direction: `ui` (`<ui.path>/**`, `/graphiql`), `openapi`
(`/v3/api-docs/**`, Swagger UI), `graphql` (`/graphql`) and `rest` (everything else, which is the
transcoded routes). `shiguchi.ui.require-auth` is an older alias of `surfaces.ui.require-auth`
and still works.

Whatever the mode, the `gateway-refresh` actuator endpoint always requires authentication — it
re-reads every backend's schema — and so do the writes of `gateway-traffic`, which move calls
between backend versions. That means they only work once a JWT decoder is configured; under
`PERMIT_ALL` with no decoder they refuse every call rather than accepting anonymous ones.

**Fail-fast.** Whenever any surface requires authentication and no way to validate a JWT is
configured, the gateway refuses to start:

```
Authentication is enabled on at least one gateway surface, which requires an OAuth2 JWT decoder.
Set spring.security.oauth2.resourceserver.jwt.issuer-uri (or jwk-set-uri), or define a
ReactiveJwtDecoder bean. Refusing to start in a configuration that would reject those requests with 401.
```

A gateway that silently answers 401 to everything looks, from the outside, exactly like a
broken one; this makes the misconfiguration a startup error instead.

## Authenticating callers

Spring Security's reactive resource server does the work; the gateway wires it into its chain
whenever a decoder is available. `spring-boot-starter-security` and
`spring-boot-starter-oauth2-resource-server` come with `shiguchi-rest`, so the gateway
starter is the only dependency.

Three ways to give it a decoder, in the order to prefer them:

1. **`issuer-uri`** — the provider's OpenID discovery document supplies the JWKS and the
   issuer claim to check. Keycloak, Entra ID, Auth0, Cognito, Okta all work this way.
2. **`jwk-set-uri`** — when there is no discovery document, or the issuer claim would not
   match. Add `issuer-uri` too if you want the claim validated.
3. **A `ReactiveJwtDecoder` bean** — anything else: a fixed HMAC key for local development, a
   custom validator chain, a multi-tenant decoder.

```java
@Bean
ReactiveJwtDecoder jwtDecoder() {
    var key = new SecretKeySpec(System.getenv("DEV_JWT_SECRET").getBytes(), "HmacSHA256");
    return NimbusReactiveJwtDecoder.withSecretKey(key).build();
}
```

That last shape is what [`sample-secured-gateway`](../samples/sample-secured-gateway) uses so
the sample needs no provider; it also mints a token at startup, which is a handy pattern for
tests.

The caller sends the token as `Authorization: Bearer <jwt>`. Basic auth is disabled and no
login page is configured; a token is the only way in.

### Scopes and authorities

Spring's default converter turns the token's `scope` (or `scp`) claim into authorities prefixed
`SCOPE_`, and the gateway strips that prefix before comparing with the scopes a route declares.
A provider that puts roles elsewhere — Keycloak's `realm_access.roles`, a custom `permissions`
claim — needs a `ReactiveJwtAuthenticationConverter` bean, which the gateway picks up by type (see
[Principal name](#principal-name) for the order it looks converters up in):

```java
@Bean
ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("permissions");
    authorities.setAuthorityPrefix("SCOPE_");     // keep the prefix the gateway strips
    var converter = new ReactiveJwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new ReactiveJwtGrantedAuthoritiesConverterAdapter(authorities));
    return converter;
}
```

### Principal name

The caller's name is the token's `sub` claim by default. It becomes `x-principal` for the
backend, the rate-limit key, the access log's principal, and the push principal (topic matching,
session keys, the `from` attribute). To name callers by another claim, set Spring Boot's own
property; every one of those follows:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          principal-claim-name: preferred_username
```

The HTTP chain and push (WebSocket `connect` frames, gRPC calls) use the same converter, looked
up in this order: a `ReactiveJwtAuthenticationConverter` bean (yours, or the one Boot registers
for `principal-claim-name` and the `authorities-*` properties; yours wins, since Boot's backs off),
then a single bean of any other `Converter<Jwt, Mono<…>>` type (a
`ReactiveJwtAuthenticationConverterAdapter`, for example), then Spring's default. Setting
`principal-claim-name` registers Boot's `ReactiveJwtAuthenticationConverter`, so it takes
precedence over a converter bean of another type (a warning names the conflict); to keep your own
converter, make it a `ReactiveJwtAuthenticationConverter` and set the claim on it. A token that
lacks the principal claim (`sub` by default) is refused with `401`.

A push namespace can match `PRINCIPAL_MATCH` topics on another claim without renaming the caller
anywhere else: see `principal-claim` in [push.md](push.md#authorization).

## Authorizing calls

Authentication says who the caller is. Whether they may call *this* method is a separate
decision, made by the `GatewayAuthorizer` bean before every REST and GraphQL dispatch — after the
route is matched, before any backend is called.

### Per-method scopes

Declare them on the method, and they apply to every surface the method is exposed on:

```protobuf
rpc ListOrders(ListOrdersRequest) returns (ListOrdersResponse) {
  option (google.api.http) = { get: "/v1/orders" };
  option (shiguchi.api.graphql) = { name: "orders", type: QUERY };
  option (shiguchi.api.method) = { auth_scopes: ["orders:read"] };
}
```

That includes a service with GraphQL turned off, and a method with no `(shiguchi.api.graphql)`
option at all: the scopes still gate its REST routes and its gRPC-Web calls.

The default authorizer requires the caller to hold every listed scope. A route with no
`auth_scopes` is open to any caller the surface admits. A route with them is never callable
anonymously — including under `PERMIT_ALL`, where an anonymous caller holds no scopes and is
refused with 403. Denial is the gateway's usual error body with `code: PERMISSION_DENIED` and the
missing scopes in `message`; on GraphQL it is an error with `errorType: PERMISSION_DENIED`.

`REST_DOWNSTREAM` services have no proto to annotate. Their operations take scopes from
`services[].operation-scopes` in YAML, or from an `x-gateway-auth-scopes` extension on the
operation in the OpenAPI document — see [configuration.md](configuration.md#rest_downstream-mode).

### Per-object authorization

Scopes are a property of the caller alone; they cannot say "may this caller read **order 42**".
For that the annotation carries `authz` rules naming the request field that holds the object id,
and your own `GatewayAuthorizer` bean asks a policy engine. `AuthorizationRequest` hands it the
matched route, the `Authentication`, the request arguments and, on REST, the raw body. The
contract, the non-blocking rule, and a composed example are in
[configuration.md](configuration.md#per-object-authorization); the annotation is in
[graphql-annotations.md](graphql-annotations.md#authz--object-level-authorization);
[`sample-openfga-authz`](../samples/sample-openfga-authz) and
[`sample-opa-authz`](../samples/sample-opa-authz) are the two shapes a policy engine usually
takes.

Whatever the authorizer decides, the backend still sees the caller (next section) and should
make its own decision. The gateway sees the request, not the data about to be returned.

## What the backend receives

On every outbound call the gateway puts on the gRPC metadata:

| Key | Value |
| --- | --- |
| `authorization` | the caller's `Authorization` header, verbatim; for a caller authenticated without one, the `UpstreamCredentialSource`'s value (see [Extending the chain](#extending-the-chain)) |
| `x-principal` | the authenticated principal's name, or `anonymous` |
| tracing headers | `traceparent`, `tracestate`, `b3`/`x-b3-*`, `x-request-id`, `x-amzn-trace-id`, `x-cloud-trace-context` when present (`client.forward-tracing-headers`, on by default) |
| `client.forward-headers` | any further inbound header you list, lowercased; `authorization` and `x-principal` are skipped even when listed, so a caller cannot set its own principal |

So a backend can validate the same token, or trust `x-principal` when the hop is one it
trusts. To forward less, or something else — a service token instead of the caller's, a tenant
claim as its own key — register a subclass of `GrpcMetadataPopulator` marked `@Primary`.

**The token travels in the clear unless the hop is encrypted.** A service definition is
`insecure: true` by default, which is right for a sidecar on localhost and wrong for anything
else. When any surface authenticates, the gateway names the plaintext backends at startup:

```
gRPC gateway security: bearer tokens are forwarded to 1 backend(s) over PLAINTEXT (catalog). ...
```

Encrypt the hop per service — `insecure: false` uses the JVM's trust store; a `tls` block pins
the CA and, with a client certificate, makes it mutual:

```yaml
shiguchi:
  services:
    - name: catalog
      host: catalog.internal
      port: 9443
      tls:
        trust-cert-collection: /etc/gateway/ca.pem
        cert-chain:            /etc/gateway/client.pem      # both present → mTLS
        private-key:           /etc/gateway/client-key.pem  # PKCS#8
```

The secured sample runs exactly this: a JWT in, mutual TLS out, and a walkthrough of what each
half looks like when it fails.

## Browser surfaces

- **CORS** (`shiguchi.cors.*`, off by default) runs inside the security chain, so a
  preflight succeeds on a gated surface. It applies to the browser surfaces only; a transcoded
  REST route never gains permissive CORS by accident.
- **Security headers** (`shiguchi.headers.*`, on by default) send a Content-Security-Policy
  fitted to the bundled console, GraphiQL and Swagger, plus `Referrer-Policy` and
  `Permissions-Policy`, on those pages only.
- **The console** invokes through the same dispatch path as REST, so request validation
  applies to it and the bearer token pasted into a call reaches the backend. The per-method
  scope check does not run there: the console is gated as a whole by its surface. It is disabled
  by default and can call any wired method; set `surfaces.ui.require-auth` before exposing it.
- **Swagger's Authorize dialog** is driven by `shiguchi.openapi.security-schemes` and
  `openapi.global-security`, so a bearer or OAuth2 scheme declared there lets a reader try
  gated routes from the page.

### Skipping the chain for open REST routes

Under `PERMIT_ALL` a transcoded request still runs through Spring Security's filters — header
writer, CORS, context propagation, exception translation, authorization — and the observation
opened around each of them: ≈55 µs of a ≈420 µs request on loopback
([benchmarks](../shiguchi-benchmarks/README.md#the-gateway-over-http-µsrequest-jdk-25-windows-11-loopback-2026-10-06)).
When the REST surface is open and an ingress in front authenticates, that work buys nothing:

```yaml
shiguchi:
  security:
    open-rest-bypasses-chain: true
```

The chain then matches only what it still gates or serves — `/actuator/gateway-refresh`, the
`gateway-traffic` writes, the browser surfaces (console, GraphiQL, `/graphql`, Swagger), the
`shiguchi.cors.paths`, with gRPC-Web on its path prefix, and with [push](push.md) on its
`shiguchi.cursus.path` (the namespaces' own checks need the chain to have read the JWT) — and
every other route skips it. What those routes lose: Spring Security's default response headers (`X-Content-Type-Options`,
`X-Frame-Options`, `Cache-Control: no-store`, `Referrer-Policy`) and its request firewall. The
gateway's own [security headers](#browser-surfaces) never applied to them anyway. Ignored when
`surfaces.rest.require-auth` is true, since authenticating is the chain's job.

## Extending the chain

Two experimental hooks add an authentication method without replacing the chain.
They are what the coming browser sign-in (`shiguchi-bff`) is built on; most deployments never
implement them.

**`GatewaySecurityCustomizer`.** Every bean of this type is applied, in `@Order`, to the chain the
gateway builds; with none, the chain is unchanged. Every method defaults to a no-op:

- `authorize(AuthorizeExchangeSpec)` adds rules. They run after the always-gated actuator writes
  (`gateway-refresh`, the `gateway-traffic` and `gateway-push` writes, `gateway-bff-sessions`), which a customizer cannot
  reopen, and before the surface rules, so a sign-in path can be opened under
  `mode: AUTHENTICATED`. A customizer is trusted not to permit what a surface should gate.
- `customize(ServerHttpSecurity)` runs last, before the chain is built: filters, the
  security-context repository.
- `authenticates()` returns `true` when the customizer authenticates callers by itself. The
  gateway then starts with a surface requiring auth and no JWT decoder; a bearer request gets a
  plain `401` (no `WWW-Authenticate: Basic`, so no browser login dialog).
- `chainPaths()` names paths the chain must keep when `open-rest-bypasses-chain` lets open
  routes skip it. Open transcoded routes still skip it, so they see no session either.

```java
@Bean
GatewaySecurityCustomizer signIn() {
    return new GatewaySecurityCustomizer() {
        @Override public void authorize(ServerHttpSecurity.AuthorizeExchangeSpec exchanges) {
            exchanges.pathMatchers("/signin/**").permitAll();
        }
        @Override public void customize(ServerHttpSecurity http) {
            http.securityContextRepository(new SessionCookieRepository());   // yours
        }
        @Override public boolean authenticates() { return true; }
    };
}
```

**`UpstreamCredentialSource`.** A caller authenticated without an `Authorization` header (by a
cookie) would reach the backend with `x-principal` only. One bean of this type supplies the
`authorization` value for such a caller (`"Bearer …"`, or empty for none). It is asked only when
the request has no `Authorization` header and the caller is authenticated and not anonymous, so
bearer callers never reach it. An error fails the call; two beans fail startup. A
`GrpcMetadataPopulator` subclass built on the one-argument constructor keeps working, and does not
consult the source.

Requests authenticated by a session cookie carry the exchange attribute
`GatewayRequestAttributes.AUTHENTICATED_BY` with the value `cookie`; it is absent for bearer and
anonymous callers.

The `shiguchi-bff` module (`shiguchi.bff.enabled=true`) is the first user of both hooks.
Its customizer installs a security-context repository that leaves any request with an
`Authorization` header (whatever the scheme, even malformed) to the bearer path, and asks a
`BffSessionAuthenticator` bean about the rest; its credential source sends the session's token as
`Bearer …`. A bearer header always wins over a cookie and a bad one is never rescued by it: an
invalid bearer token is a 401, and under `AUTHENTICATED` so is any other header the resource server
does not accept. Cookie requests are protected against cross-site request forgery; see
[Cookie-authenticated requests](#cookie-authenticated-requests-csrf-and-websockets).
The module never defines a `ReactiveJwtDecoder`, so Spring Boot's issuer decoder keeps validating
bearer callers. The module also serves the sign-in endpoints (login, callback, session, logout, providers) and, unless you define a `BffSessionAuthenticator` of your own, authenticates the session cookie it issues; see [design/bff.md](design/bff.md).

### Cookie-authenticated requests: CSRF and WebSockets

The chain keeps Spring's CSRF support off because a bearer caller sends no ambient credential: a
forged cross-site request cannot attach a token. A session cookie is the opposite, so with
`shiguchi.bff.enabled=true` the gateway adds its own check, right after authentication, to every
request authenticated by the session cookie. Bearer and anonymous requests are never touched.

- **The origin rule.** A request whose method is not `GET`, `HEAD` or `OPTIONS`, and any WebSocket
  handshake whatever its method, needs an `Origin` header that is exactly (scheme, host, port) one of the
  origins of **the session's own application**. A missing, `null` or different `Origin`, application B's
  included, is `403 {"error":"origin not allowed"}`. Browsers send `Origin` on every non-`GET` `fetch`,
  XHR and form post and on WebSocket handshakes, and a page cannot forge it, so the match alone proves
  the request comes from one of the application's own pages. This covers REST, gRPC-Web, GraphQL and the
  push WebSocket and SSE endpoints.
- **Why not `Sec-Fetch-Site`.** It adds nothing a required, exact `Origin` match does not already prove,
  and older browsers and some proxies drop it. It is not consulted.
- **Content types.** On the REST/JSON surface a request body with `text/plain`,
  `application/x-www-form-urlencoded` or `multipart/form-data`, or a body with no content type, is
  `415`, because a browser sends those cross-origin without a CORS preflight. gRPC-Web (its content type
  forces a preflight) and GraphQL are exempt, and so is `POST <bff path>/logout`: a plain form post is a
  valid logout, but it still needs the origin.
- **Layout.** Serve the application from the gateway's own origin (recommended: no CORS at all), or from
  a same-site sibling host with `shiguchi.cors` allowing it with credentials. A page on another
  *site* cannot work with the default `SameSite=Lax` cookie, which the browser never attaches to its
  cross-site `fetch`; startup logs a warning when `external-base-url` and an application are cross-site.
- **Cookies stay the gateway's.** A backend can never set a cookie on the browser through the gateway:
  startup fails if `rest.forward-response-headers` (or a service's) lists `set-cookie` or `*`, and a
  response filter drops any `Set-Cookie` for the session or login cookie that did not come from the
  sign-in endpoints. The session cookie never reaches a backend (startup fails if
  `client.forward-headers` lists `cookie`), and the response cache keeps cookie callers apart.
- **CORS.** Startup also fails for `cors.allow-credentials: true` with `*` among
  `cors.allowed-origins`.

### Browser sessions never reach actuator endpoints

The actuator writes the gateway gates (`gateway-traffic`, `gateway-push`, `gateway-refresh`,
`gateway-bff-sessions`) need only an authenticated caller, which any signed-in browser user is, and the
`Origin` check is no defence (a user can send their own cookie and a matching `Origin` from a script). So
with `shiguchi.bff.enabled=true` a filter right after authentication answers
`403 {"error":"cookie sessions cannot call actuator endpoints"}` to every cookie-authenticated request under
the actuator base path (`management.endpoints.web.base-path`, default `/actuator`), all methods, reads
included. Bearer callers are untouched. Management on a separate port is not in the chain at all.

### Ending sessions: back-channel logout and revoke-all

The issuer can end a user's gateway sessions with an OpenID Connect Back-Channel Logout token posted to
`<bff path>/backchannel-logout/{provider}` (verified against the provider's keys; by `sid`, or by `sub`
for all of the user's sessions at that provider), and the application or an operator can revoke a
subject (`BffSessions`, or `DELETE /actuator/gateway-bff-sessions/{provider}/{subject}`). The endpoint
needs `shiguchi.bff.admin.role`: without it every caller gets `403` (startup logs a WARN when it is
exposed). Ending a session removes it from the store and closes its
push connections.

**Stream expiry:** a gRPC-Web or REST server stream or a GraphQL subscription of a bearer caller ends
at the token's `exp` with `UNAUTHENTICATED` "token expired" (`shiguchi.security.end-streams-at-token-expiry`,
default `true`), and an operation sent on an open bearer GraphQL WebSocket past `exp` is refused;
a cookie caller's stream follows its session instead. See [bff.md](bff.md#rest-grpc-web-and-graphql-streams).

**Revocation delay:** the cookie stops working at once; a push socket on another instance closes within
`shiguchi.cursus.session-check-interval`, and a REST, gRPC-Web or GraphQL stream within
`shiguchi.security.session-check-interval`; an access token already sent upstream stays valid at the
backends until it expires, and a call in flight is not interrupted. The back-channel endpoint is open to
anonymous callers by design (the signed token is the credential) and outside the `Origin` check because
it is not cookie-authenticated. It has been run by hand against a real issuer's sender, outside CI. See
[design/bff.md](design/bff.md#10-session-lifecycle-built-in-g5).

## Bringing your own chain

A `SecurityWebFilterChain` bean of your own replaces the gateway's entirely — the modes, the
surfaces, CORS wiring, the fail-fast check and the startup warnings all live in the bean that is
no longer created. Use it for what the switches cannot express: API keys, mTLS from callers, an
open health endpoint under `AUTHENTICATED`, or two chains for two audiences.

```java
@Bean
SecurityWebFilterChain gatewayChain(ServerHttpSecurity http) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(Customizer.withDefaults())                       // keeps shiguchi.cors.* working
            .authorizeExchange(authz -> authz
                    .pathMatchers("/actuator/health/**").permitAll()
                    .pathMatchers(HttpMethod.POST, "/actuator/gateway-refresh").hasAuthority("SCOPE_gateway:admin")
                    .anyExchange().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .build();
}
```

Keep `csrf` disabled: bearer callers send no ambient credential, so there is nothing to forge, and
Spring Security's CSRF protection would otherwise reject every POST without a token it never issued.
(Cookie callers of the `shiguchi-bff` module are covered by its own origin check, which is part of the default
chain; a chain of your own gets it only through a `GatewaySecurityCustomizer`.) The `GatewayAuthorizer` still
runs after your chain, so scopes and `authz` rules keep working.

**Health probes under `AUTHENTICATED`.** The actuator's health endpoints are not a surface of
their own; they fall under `rest`, so `mode: AUTHENTICATED` requires a token on
`/actuator/health/**` as well. A Kubernetes probe sends none. Either keep the transcoded routes
open behind an authenticating ingress (`surfaces.rest.require-auth: false`), or bring the chain
above, which is the smallest one that permits the probes and gates everything else.

## The pass-through gRPC proxy

The native gRPC port (`shiguchi.grpc.enabled`) is a different listener and is **not** behind
the HTTP security chain. It forwards every call's metadata to the backend, including
`authorization`, and enforces only its per-method rate limit. The one exception is
`x-principal`: the proxy authenticates nobody, so it drops any `x-principal` the caller sent and
sets `anonymous`, and a backend that trusts that key never sees a caller-chosen name. A backend reached through the proxy
authenticates the token itself — the proxy's job is to let it. Expose the port only where that is
acceptable, and prefer the HTTP surfaces for untrusted callers.

## Spring Boot 3 and 4

Everything above is identical on both lines: the same properties, the same beans, the same
starters with `boot3` or `boot4` in the name. The only difference is internal — Boot 4 moved its
reactive security auto-configurations to new packages, and the gateway registers itself ahead of
both spellings so its chain wins the `@ConditionalOnMissingBean` race either way.

## Before you ship

- `security.mode: AUTHENTICATED`, or every surface you expose has `require-auth: true` and the
  rest is behind an ingress that authenticates. The startup log says which it is.
- No `PLAINTEXT` warning at startup, or the plaintext hops are inside a mesh you trust.
- The console is off, or gated. `gateway-refresh` is exposed only if someone needs it.
- Routes that must not be anonymous declare `auth_scopes`; routes that act on a specific
  object declare `authz` and a policy answers it.
- Health probes work — try one without a token.
- Rate limiting is on ([rate-limiting.md](rate-limiting.md)), GraphQL depth and complexity are
  bounded, and `management.endpoints.web.exposure.include` is short.

→ [`sample-secured-gateway`](../samples/sample-secured-gateway) — JWT in, mTLS out, each half
shown failing · [SECURITY.md](../SECURITY.md) — the posture, the surface model, reporting a
vulnerability · [configuration.md](configuration.md) — every property named here
