# BFF: browser sign-in with a session cookie — design

Status: experimental. **Built (G0):** the two hooks in `shiguchi-rest`
(`GatewaySecurityCustomizer`, `UpstreamCredentialSource`, `GatewayRequestAttributes`; #406) and
the `shiguchi-bff` module with its Boot 3 and Boot 4 starters: `shiguchi.bff.*` binds
and is validated at startup, `BffApplications` resolves a request's application, and the
cookie/bearer path rule is installed (`BffSecurityContextRepository`, the bff customizer and
credential source, the `BffSessionAuthenticator` seam). **Built (G1a):** the sign-in endpoints
(`login`, `callback`, `session`, `logout`, `providers`), the `BffSessionStore` SPI with its memory
implementation, and the default `BffSessionAuthenticator` (section 6). **Built (G1b):** the Redis
and JDBC stores (section 3, "Session stores"). **Built (G3):** request hardening, section 7. **Built (G2):** `relay` token refresh, one refresh per session across every instance, section 8. **Built (G4):** push with the session cookie, see "Push" below. **Built (G5):** session lifecycle, section 10, checked by hand against a real issuer's sender. **Built (G7):** the user guide [bff.md](bff.md) and the browser-shaped end-to-end test
`BffBrowserJourneyEndToEndTest` (both lines); the sample SPA is `samples/sample-bff-spa`. **All planned
slices are built.** Direct social login and `mint` remain optional and unplanned. Everything below that is not
listed as built is a plan. **Status, one statement (also in the `BffProperties` Javadoc):** experimental. Properties, store formats and endpoint shapes may still change without a migration. Sign-in and
back-channel logout have been run by hand against a real OIDC issuer (not in CI). The request hardening itself
is built: cookie requests, WebSocket upgrades included, get the exact `Origin` check, and push sockets
are bound to the session.

Source brief: an internal BFF enhancement brief of 2026-10-13. It is input, not truth;
where it and this document differ, this document wins and says why.

## 1. What and why

Browser applications should sign in without holding a token in JavaScript. The gateway acts as a
backend-for-frontend: it runs the OIDC sign-in, keeps the provider's tokens in a server-side
session behind an HttpOnly cookie, and sends a bearer token upstream, so backend services keep
validating JWTs exactly as for any other caller.

## 2. Decisions (D1–D5) and their outcomes

| # | Question | Outcome |
| --- | --- | --- |
| D1 | What bearer token do backends receive? | **`relay` only** (answered 2026-10-13). The target deployment runs a dedicated issuer; the gateway is a plain OIDC client of it and sends the issuer's access token upstream. `mint` (the gateway signing its own tokens) and direct social login move to an optional later slice, built only for a deployment without its own issuer. `upstream-token-mode` therefore has one value, `RELAY`, and there is no `upstream-token.*` block |
| D2 | The user's stable identity | Deferred with `mint`. In `relay` mode the subject is the issuer's `sub`. Rule kept for the later slice: a subject must match `[A-Za-z0-9_.:@-]{1,128}` (the push topic grammar **minus `/`**), because under `PRINCIPAL_MATCH` subject `a` may subscribe to `a/…`, so a subject `a/b` would be readable by `a` |
| D3 | Packaging | One shared module `:shiguchi-bff` (package `dev.appiaworks.shiguchi.bff`) found by the gateway's `dev.appiaworks.shiguchi` component scan, plus two test-only starters `grpc-bff-spring-boot{3,4}-starter`, exactly like push. The brief's "starters like push" meant this: push has no autoconfigure modules. See section 4 for the spike that confirmed one module is enough |
| D4 | Session store | `session.store: MEMORY | REDIS | JDBC`, bound in G0. **Outcome (G1a): the module owns a small `BffSessionStore` SPI** (memory, Redis and JDBC built), with no `oauth2Login()`, no `spring-security-oauth2-client` and no Spring Session. The reasons, as reviewed in S12: **(1) Principal shape.** `oauth2Login()` produces an `OAuth2AuthenticationToken`, but a cookie caller must be the same `JwtAuthenticationToken` a bearer caller is (one converter, so authorities, `x-principal`, the rate-limit key and push `PRINCIPAL_MATCH` need no special case). It also saves through its own context repository and drives redirects the module must control (`return_to`, the allow-list, the provider hint). **(2) The binary break is real but avoidable, and not worth avoiding.** `OAuth2AuthorizationRequest.Builder` became an `AbstractBuilder` in Security 7.x, so a module compiled against 6.5 throws `NoSuchMethodError` on Boot 4, and one compiled module must run on both. Per-line adapters could bridge it; roughly 450 lines of duplication would buy nothing visible, so the module builds the authorize URL itself, POSTs to the token endpoint with `WebClient`, and validates with `NimbusReactiveJwtDecoder` plus its own `aud`/`azp`/`nonce` checks (the raw nonce; Spring's `OidcIdTokenValidator` never checked it). **(3) No `WebSession`, no Spring Session.** A `WebSessionManager` sits on every request and would add a lookup to the hot path, which has none today; Spring Session has no compare-and-set, no lease (the cross-instance refresh of G2), no index by `sid` or subject (back-channel logout, revoke-all, G5) and no reactive JDBC store. The packaging spike's list in section 4 is therefore superseded for the client types |
| D5 | One application or several | Several, named under `applications`, resolved from the `Origin` header or else the request's own host; one entry needs no resolution (`BffApplications`) |

### Rejected: a stateless cookie mode

A mode with no server-side session (the tokens in an encrypted cookie, as some proxies do) was considered
and rejected. A session that lives only in the browser cannot be ended by the server: back-channel logout,
the admin and self-service revoke, `revokeSubject` and streams bound to a session all work by deleting or
looking up a stored record. Tokens would also travel to the browser, so a stolen cookie would be a stolen
token that nobody can revoke; the cookie would have to hold two or three JWTs (cookie size limits); and a
refresh by one tab would race with the next request of another, with no shared store to arbitrate the
rotation. Server-side sessions are what the rest of the module is built on.

## 3. Built in G0

### 3.1 The path rule

A request **with** an `Authorization` header, of any scheme and even malformed, is a bearer
request and is handled exactly as without the module: a bad header is a 401 and is never rescued
by a cookie. A request **without** one is offered to the `BffSessionAuthenticator`; if it returns
a caller, the request is authenticated by cookie and marked with the exchange attribute
`GatewayRequestAttributes.AUTHENTICATED_BY = "cookie"` (in `-rest`, so push and G3 can read it
without depending on this module). The attribute is set when something first subscribes to the
security context (the authorization rules do, on every path the chain covers); code that checks it
earlier must read the context first (G3, G4).

Under `open-rest-bypasses-chain`, open REST routes skip the chain: a cookie caller there gets no
context and no upstream credential, while a bearer caller's header is still forwarded unchanged.
The bff customizer adds no `chainPaths` in G0; G1 adds its own `/bff/**`.

`BffSecurityContextRepository` implements the rule and is installed by the bff
`GatewaySecurityCustomizer`. Its `save` is a no-op: only sign-in writes sessions. Spring Security
(6.5 and 7.1 alike, checked in `ReactorContextWebFilter`) re-subscribes to `load` for every
reader of the security context, so `load` is computed once per exchange and cached in an
exchange attribute; session lookups and refreshes in G1/G2 hang off this call.

### 3.2 One principal shape

A cookie caller carries a `JwtAuthenticationToken`, built from the session's upstream token and
converted by the same converter as a bearer request (`JwtPrincipalConverter`, #405). Authorities,
the principal name, `x-principal`, scope checks, the rate-limit key, the access log and push
`PRINCIPAL_MATCH` therefore need no special case.

**Same converter, private decoder.** The bearer path's `ReactiveJwtDecoder` validates one issuer.
Each bff provider gets its own way to a `Jwt` (G2: a decoder for that provider's issuer), held as
a plain object. **The module never registers a `ReactiveJwtDecoder` bean:** Boot's issuer decoder
is `@ConditionalOnMissingBean`, so one would silently replace it for every bearer caller.
`BffJwtDecoderEndToEndTest` asserts the decoder beans are the same with the module on and off.

### 3.3 Upstream credential

The bff `UpstreamCredentialSource` sends `Bearer <token value>` for a cookie-authenticated
`JwtAuthenticationToken` and nothing for anyone else. The `-rest` hook hands the source the
request and the caller but not the exchange, so the cookie mark also travels on the caller:
the repository wraps the token's details in `dev.appiaworks.shiguchi.security.CookieAuthenticated`
(the token class does not change) and the source checks `isCookieAuthenticated`. The source is
declared `@ConditionalOnMissingBean`, so an application's own source replaces it. `BffUpstreamCredentialSourceTest`
covers the default source only; no test installs a replacement.

### 3.4 Configuration and validation

`BffProperties` (`shiguchi.bff`), standalone like `PushProperties`, outside the
`dev.appiaworks.shiguchi.config` compatibility gate. Startup fails, one line per problem, on: enabled
with no application or no provider; an application naming an unknown provider; an origin that is
not exactly `scheme://host[:port]` (http/https, no path, no `*`); two applications sharing an
origin (compared lower-case with default ports dropped); a `return-to` that is not absolute or
not under one of its application's origins; a `__Host-` cookie that is not `secure`; a provider
without `issuer-uri` or `client-id`; a provider id outside `[a-z0-9-]{1,32}`. `security.mode:
PERMIT_ALL` with the module on is allowed and logged as a warning.

The customizer's `authenticates()` is true only when a `BffSessionAuthenticator` bean exists, so
an enabled module with nothing to authenticate with keeps the gateway's "auth required but no
JWT resource server" startup check.

## Sign-in (built in G1a)

The module does the OIDC authorization-code flow itself (see D4 for why it uses neither
`oauth2Login()` nor `spring-security-oauth2-client`). Endpoints live under `shiguchi.bff.path`
(default `/bff`), are open in the security chain and always answer `Cache-Control: no-store`.

| Endpoint | Behaviour |
| --- | --- |
| `GET <path>/login/{provider}?return_to=&hint=` | A top-level navigation, so browsers send no `Origin`: the application comes from `return_to`, which must fall under exactly one application's `return-to` list (prefix on a path boundary: `/app` does not match `/app-evil`). No match, or several: 400. A missing `return_to` is accepted only with a single application. A provider the application does not list: 403. Saves a login record, sets the login cookie and answers 302 to the issuer with `response_type=code`, PKCE S256, `state`, `nonce`, the provider's scopes and `redirect_uri` = `<external base><path>/callback/<provider>` (the external base is `external-base-url`, else taken from the request). `hint` (`[A-Za-z0-9._-]{1,64}`) is forwarded under the provider's `hint-parameter` (default `provider`) |
| `GET <path>/callback/{provider}` | Needs the login cookie whose value equals `state` (else 400 `login_expired`) and an unexpired login record (taken, so single use; else 400 `invalid_state`). Re-checks the provider against the application recorded in the login (the callback has no `Origin` either), exchanges the code, validates the ID token (signature, `iss`, `aud`/`azp`, `exp`, raw `nonce`) and the access token (signature, `iss`, optional `audiences`), and converts the access token with the same `JwtPrincipalConverter` bearer callers get; a token it rejects is a failed sign-in. Always issues a **new** session id (a presented session cookie is deleted first: rotation), sets the session cookie, clears the login cookie and answers 302 to `return_to` |
| `GET <path>/session` | 200 `{"authenticated":true,"subject","provider","application","name","email","picture","expiresAt"}` or `{"authenticated":false}`; never 401, because a UI polls it. An unknown or expired session also clears the cookie |
| `POST <path>/logout` | Needs an `Origin` that is one of the session's application's origins (403 otherwise; the endpoint keeps its own check next to the filter of section 7, and it also covers a request with no valid session). Deletes the session, expires the cookie and answers 200 `{"logoutUrl":"<end-session-endpoint>?id_token_hint=<the session's ID token>&post_logout_redirect_uri=<url-encoded>&client_id=<id>"}` when the provider has an end-session endpoint (discovered or `end-session-uri`), else `{}`. `post_logout_redirect_uri` is the application's `post-logout-redirect`, default its first `return-to`. With no session the call is idempotent and answers 200 without a URL |
| `GET <path>/providers?app=` | The application's providers as `[{"id","displayName"}]`. Resolution: the `app` parameter, else `Origin`, else the single application, else 400 (Chrome sends no `Origin` on a same-origin GET) |

**Cookies.** The session cookie (`cookie.*`) is `HttpOnly`, `Path=/`, a browser-session cookie
unless `cookie.persistent` (then `Max-Age` = `session.absolute-timeout`). One fixed-name login
cookie, `__Host-gw-login` (without the `__Host-` prefix when `cookie.secure=false`), `HttpOnly`,
`SameSite=Lax`, `Max-Age` = `login-timeout`, value = the latest `state`, so a client that starts
many sign-ins cannot fill the browser's cookie jar; a second sign-in in another tab replaces it and
the first tab's callback answers `login_expired`.

**Login records and sessions** live in the `BffSessionStore`. A login record holds the PKCE
verifier, the nonce, the provider, the application, `return_to` and the redirect URI, with a TTL of
`login-timeout` (default 5 minutes). The session cookie value is 32 random bytes (Base64url); the
store key is Base64url(SHA-256(cookie value)), so a leaked store yields no usable cookie. A session
holds the subject, provider, application, `sid`, the display claims, the tokens as issued and the
timestamps. **In the Redis and JDBC stores the three tokens are AES-256-GCM ciphertext** (`EncryptingBffSessionStore`
wraps the store; callers see plaintext). The key is a configured key ring (`session.encryption.keys`,
`active`), not a key derived from the cookie: admin revoke runs without a cookie yet needs the refresh
token for the issuer revocation, and back-channel logout deletes by `sid` without reading tokens at all,
while a cookie-derived key would make both impossible. The session key and the field name are the
authenticated data, so a ciphertext cannot be moved to another row or field. Compare-and-set compares the
`version` only, never a token, so it works on ciphertext unchanged. A session that does not decrypt reads
as absent (sign in again) and is left in place, since another instance may hold the key. The memory
store is not wrapped: its tokens sit in the same process memory as the decrypted ones.

**Timeouts.** `session.absolute-timeout` runs from sign-in and `session.idle-timeout` from the last
use; both are checked on every lookup, not left to the store's TTL alone. The idle mark is written
at most once per a tenth of the idle timeout, so a busy page does not write per request.

**The default authenticator** (a `BffSessionAuthenticator` bean of your own replaces it) acts only
on a request without an `Authorization` header that carries the session cookie: key, lookup,
timeouts, access token not expired (one that is due is refreshed first, section 8), decode with the provider's decoder,
convert with `JwtPrincipalConverter`. Any failure leaves the request unauthenticated, never an
error. The result is a `JwtAuthenticationToken` marked `CookieAuthenticated` (session handle and
application, for G3 and G4). A decoded token is cached per session for its lifetime. A missing or
expired session adds a clearing `Set-Cookie` only on responses under `<path>`, so the proxied hot
path never gains one. A bearer request never reaches the store.

**`bff_error` codes.** A failure after the login record was accepted redirects to `return_to` with
`bff_error=<code>`: `sign_in_failed` (code exchange, token validation, nonce or principal
conversion failed, or `max_age` was sent and `auth_time` is missing or too old) or the issuer's own
`error` value when it is a short lowercase code (for example `access_denied`; descriptions are never
echoed). The issuer errors `login_required`, `interaction_required`, `consent_required` and
`account_selection_required` (the answers to `prompt=none`) are all reported as `login_required`.
A failed silent attempt never touches an existing session. Problems before that (bad `return_to`, unknown
provider, state or cookie mismatch, expired login) are plain 4xx answers, never redirects.

**Session stores (G1b).** `session.store` selects `MEMORY`, `REDIS` or `JDBC`; a `BffSessionStore`
bean of your own wins over all three. Redis and Spring JDBC are `compileOnly` in the module and the
stores sit in guarded nested configurations, so the module runs without either on the classpath;
choosing a store whose dependency or bean is missing fails startup with a message naming it.

- *Redis.* A session is a hash at `<key-prefix>session:<key>`, a login a hash at
  `<key-prefix>login:<state>`; every field is a plain string (times are epoch milliseconds, absent
  values are left out), so no JSON library is involved. `save` replaces the hash and sets
  `PEXPIRE` in one Lua script; `touch` is a script that does nothing for a missing key (a deleted
  or expired session is never resurrected); `takeLogin` is a script that reads and deletes in one
  step, so a login is single use across instances (Lua rather than `GETDEL`, which needs Redis
  6.2). Redis 5 is enough. One key per operation, so Cluster needs no hash tags; the `sid` and `sub` index sets of G5
  (section 10) are separate single-key commands too.
- *JDBC.* Tables `<prefix>session` and `<prefix>login` (default prefix `gw_bff_`), created
  idempotently at startup unless `create-schema` is off, with an `expires_at` index each. All
  calls are blocking JDBC on `boundedElastic`. Expiry is the `expires_at` column (epoch
  milliseconds): reads and `touch` filter on it, and a sweep every `sweep-interval` deletes expired
  rows (not on write). `save` is update-then-insert. `takeLogin` reads the row and then deletes it
  by key; only the caller whose `DELETE` removed a row gets the login, which makes it single use
  under concurrency on every database without a row lock or `RETURNING`.
- Instants are stored with millisecond precision. Tokens are stored as issued in `TEXT`/`CLOB`
  columns, unencrypted.

Request hardening of cookie requests is section 7.

## 4. The packaging spike

Before writing the build files, a throwaway class compiled once against the Boot 3 versions
linked the types G1 and G4 need and ran from both starters: `ClientRegistration` and
`InMemoryReactiveClientRegistrationRepository`, `ClientRegistrations.fromIssuerLocation`,
`DefaultServerOAuth2AuthorizationRequestResolver.resolve(exchange, id)`,
`WebClientReactiveAuthorizationCodeTokenResponseClient`, Spring Session's
`ReactiveMapSessionRepository`, `DefaultWebSessionManager` and Nimbus `RSASSASigner`. All
resolved on Spring Security 6.5.11 (Boot 3) and 7.1.1 (Boot 4). **One shared module is enough**;
the fallback of per-line `shiguchi-bff-autoconfigure` modules is not needed. G1 planned to add
`spring-security-oauth2-client` and Spring Session to `:shiguchi-bff` only (never to `-core`
or `-rest`); neither was added in the end (D4), and the gateway autoconfigure classpaths stay unchanged.

## Session claims and my sessions (S11)

- `/bff/session` returns the ID-token claims an application lists in `session-claims`; `name`, `email` and
  `picture` keep their fields, others go under `claims`. Claims are copied from the ID token at sign-in and
  from each verified refreshed ID token, stored as one JSON text (4 KB cap, two levels deep). A
  `/bff/userinfo` proxy was rejected: the ID token already carries the data; revisit if an issuer puts
  claims only in userinfo.
- `GET /bff/sessions` and `DELETE /bff/sessions/{id}` act on the caller's subject only (the lookup is
  `findBySubject`), cookie callers only, `Origin` required on `DELETE`. The id is
  `HMAC-SHA256(HKDF(active encryption key, "bff-session-handle"), store key)`: the store key and cookie are
  never exposed, handles survive restarts when an encryption key exists and are random per boot otherwise.

## 5. Where the brief was wrong about the code

- Push has no autoconfigure modules; "starters like push" means one module plus test-only starters.
- `GrpcMetadataPopulator` is `STABLE` with one constructor; the credential source had to keep the
  `@Primary` subclass route working (a second, `@Autowired` constructor).
- The security chain had no hook, and hooks needed a point before `anyExchange()` to open
  sign-in paths (`GatewaySecurityCustomizer.authorize`) and a way into the chain under
  `open-rest-bypasses-chain` (`chainPaths`).
- The chain refused to start without a resource-server issuer when auth is required;
  `authenticates()` lifts that for a customizer that authenticates by itself.
- Upstream `authorization` was copied from the inbound header only; a cookie caller would have
  reached backends with `x-principal` alone.
- The HTTP chain and push used different JWT converter lookups; #405 unified them.
- The response cache key already hashes the outbound metadata and treats a `Cookie` header as
  per-caller, so two cookie users get different keys. G3 added the test.

## 6. Plan (G1–G7)

| Slice | What | Depends on |
| --- | --- | --- |
| G0 | Hooks, module, properties, path rule | built |
| G1 | `/bff/providers`, `/bff/login/{provider}` (Authorization Code + PKCE, `provider=` hint forwarded to the issuer), callback, `/bff/logout`, `/bff/session`; per-application provider allow-list at login and callback; `return-to` check; session id rotation; the real `BffSessionAuthenticator` | G0 |
| G2 | `relay` refresh before expiry, one refresh shared by concurrent requests, failed refresh ends the session (401) | G1; built |
| G3 | Exact `Origin` check on cookie requests and WebSocket handshakes, CORS credential checks, no backend `Set-Cookie` forwarded, response-cache isolation test | built |
| G4 | Session cookie on the push WebSocket and SSE handshakes, exact `Origin` match, connections bound to the session | G0 |
| G5 | Idle and absolute timeouts, back-channel logout, revoke-all for a subject | G1; built |
| G7 | Guide, sample SPA, browser-level tests | G1–G4; built |
| – | Direct social login and `mint` (IdentityResolver, provider presets, signing keys, JWKS) | optional, not planned |

## 7. Request hardening (built in G3)

`BffOriginFilter` (added by the bff customizer right after authentication) and `BffSetCookieGuard`
(a response filter) harden cookie-authenticated requests. Bearer and anonymous requests pass
untouched; the filter reads the security context first, which is what marks a request as
cookie-authenticated.

- **Origin rule.** A cookie request with a method other than `GET`/`HEAD`/`OPTIONS`, or any cookie
  WebSocket handshake (`Upgrade: websocket`, any method), needs an `Origin` equal, after the G0
  normalisation, to one of **its session's application's** origins; else `403 {"error":"origin
  not allowed"}`. A cookie `GET`/`HEAD` (and `OPTIONS`, which never carries the cookie) is held to
  the same set **when it carries an `Origin`** (`null` included); without one it passes, since a
  browser then shows the response only to the page's own origin. The content-type `415` rule stays
  on unsafe methods. Matching the session's application, not any configured one, stops application
  B's page from driving or reading application A's session: `/bff/session` and `/bff/providers`
  pass through the same chain, so the filter covers them. The other half is not a rule but a
  property: a gateway host has one session cookie name, so a browser holds one session per host
  and signing in to application B replaces A's. A session whose application is unknown (a
  third-party authenticator whose caller the repository wrapped; `CookieAuthenticated.application`
  is null) is held to the origins of any configured application: it cannot be tied to one, but a
  foreign origin is still refused.
- **Why not `Sec-Fetch-Site`.** Browsers send `Origin` on every non-`GET` `fetch`/XHR/form post
  and on WebSocket handshakes, and a required, exact match already proves what `Sec-Fetch-Site:
  same-origin` would. The header adds no case the origin check misses, so it is not read. A
  cross-*site* layout cannot work anyway: a `SameSite=Lax` cookie is never sent on cross-site
  fetches. Supported layouts: same origin (recommended), or same-site sibling hosts with CORS.
- **Content types.** On the REST/JSON surface a body typed `text/plain`,
  `application/x-www-form-urlencoded` or `multipart/form-data`, or untyped, is `415`. gRPC-Web
  (`application/grpc*`, which forces a preflight) and GraphQL (`spring.graphql.path`, default
  `/graphql`) are exempt, and so is `POST <bff path>/logout`, where a form post is a valid logout.
- **Anti-CSRF header (opt-in).** `csrf-header.name`, default empty, adds a required custom header with an
  exact value on cookie requests with an unsafe method (REST, gRPC-Web, GraphQL, logout); `403
  {"error":"csrf header required"}` otherwise. Off by default: a WebSocket upgrade cannot carry it, every
  client of the cookie (the push client, the sample, scripts) would have to send it, and the exact `Origin`
  match is already the primary defence. When it is on, the `415` content-type rule is skipped, since a
  custom header forces a preflight.
- **Logout.** The endpoint keeps its own `Origin` check. It also covers the cases the filter
  cannot see: no session, an unknown session, or a session whose access token has expired (the
  authenticator then returns no caller). Logout is never forgeable either way.
- **Startup checks** (only with the module on): `cors.allow-credentials` with `*` in
  `cors.allowed-origins`; `set-cookie` or `*` in `rest.forward-response-headers` or a service's
  `forward-response-headers` (`*` would forward `Set-Cookie`); `cookie` in `client.forward-headers`.
  Warnings (when `external-base-url` is set): an application cross-site to it while
  `cookie.same-site` is not `NONE`, and an application on another origin than the gateway's
  without CORS allowing it with credentials. Site is approximated as the last two host labels.
- **Set-Cookie guard.** Just before the response commits, any `Set-Cookie` named like the session
  cookie or starting with the login-cookie prefix is dropped, except on requests under
  `shiguchi.bff.path`, which only `BffEndpoints` serves. A backend or a custom filter cannot overwrite the
  session through a path nobody foresaw.
- **Header copy paths** (verified): `ResponseHeaderForwarder` is the only generic backend-to-client
  header copy; gRPC-Web writes only status trailers; REST_DOWNSTREAM copies the content type;
  GraphQL, the pass-through proxy and push copy none. `GrpcMetadataPopulator` builds the outbound
  metadata of every request-driven call (REST, gRPC-Web, and GraphQL fields, relationship loaders
  and federation `_entities`, which run it inside the request's Reactor context): it never sends
  `Cookie` unless `client.forward-headers` lists it. Push builds its own session metadata.
- **Proven** by `BffRequestHardeningEndToEndTest` on both lines: the refusals on REST, gRPC-Web,
  GraphQL and a WebSocket handshake, two cookie users on a cacheable route, no cookie at REST,
  gRPC-Web and GraphQL backends, and the dropped `Set-Cookie`. REST_DOWNSTREAM's request side takes
  only the outbound metadata, so it has no cookie to send; it has no end-to-end test of its own.

## 8. Token refresh (built in G2)

A session outlives its access token (10 minutes at a typical issuer). `DefaultBffSessionAuthenticator`
refreshes it through `BffTokenRefresher` when `accessTokenExpiresAt - now <= refresh-skew`
(`shiguchi.bff.refresh-skew`, 60 s). A session with no refresh token expires at
`accessTokenExpiresAt` as before. Refresh is a `WebClient` form POST to the token endpoint
(`grant_type=refresh_token`, the client authenticated like the code exchange, 5 s timeout), not
`spring-security-oauth2-client`, for the reason in D4.

**Why a lock.** The issuer rotates the refresh token on every use and revokes the whole family on any
reuse, concurrent use included. Two requests for one session that both reach the token endpoint
(on one instance or on two) sign the user out everywhere, and retrying on `invalid_grant` is too
late. So nobody calls the token endpoint without holding a lock, and a waiter re-reads the
session instead of refreshing with the token it read earlier.

**Two layers.**

1. *In the JVM:* a map of session key to cached `Mono`. Concurrent requests for one session
   subscribe to one attempt; the entry is removed when it completes. The attempt keeps running if
   the request that started it goes away, so a rotated token is never lost.
2. *Across instances:* a lease in the `BffSessionStore` (`tryLease`, `releaseLease`,
   `replaceIfVersion`: new `default` methods whose defaults fail with
   `UnsupportedOperationException`, because a naive default would silently allow double refresh).
   Redis: `SET <prefix>lease:<key> <owner> NX PX`, release by a compare-and-delete script, and the
   write is a script that checks the hash's `version` and keeps the remaining time to live. JDBC:
   `lease_owner` and `lease_until` on `gw_bff_session` (added by idempotent `ALTER TABLE ... ADD
   COLUMN`), a conditional `UPDATE` to take and release, `UPDATE ... WHERE version = ?` for the
   write. Memory: `compute`. The lease lasts `refresh-lease` (10 s, at least 8 s; the token call, decodes and checks are cut off at the lease minus 2 s),
   is not re-entrant, and each attempt uses a fresh owner id.

**Flow.** Read the session; if it is no longer due (another instance refreshed), use it. Else take
the lease. The holder re-reads the session once more, refreshes only if it is still due, writes the
result with `replaceIfVersion` and releases. A request that does not get the lease polls `find`
every 100 ms until the version changes or `refresh-lease` has passed, then tries to take the lease
itself (a holder that died). After two such rounds it gives up as transient. A token that is still
valid is used at once and never waits: only an expired token waits. After a transient failure,
requests with a still valid token skip the attempt for one second so a down issuer is not asked on
every request.

| Outcome | What happens | Request | `/bff/session` | Meter `outcome` |
| --- | --- | --- | --- | --- |
| Refreshed | New access token and expiry, the rotated refresh token, the new ID token if one came back (RP logout needs the latest `id_token_hint`); `version` + 1 | served with the new token | true | `ok` |
| Someone else refreshed | The stored session is used | served | true | `waited` |
| Issuer answers 400 or 401 (`invalid_grant`, revoked family, bad client) | Session deleted | 401 | false | `rejected` |
| Refreshed token is for another principal or does not verify | Session deleted | 401 | false | `rejected` |
| Transient (timeout, 5xx, connection refused, store error), token expired | Session kept | 503 with `Retry-After: 1` | true | `transient` |
| Transient, token still valid | Session kept | served with the old token | true | `transient` |
| The store has no lease methods | Refresh is off (logged once) | an expired token is anonymous | true | none |

The refreshed access token goes through the same decoder and `JwtPrincipalConverter` as at
sign-in. `/bff/session` reads the store and does not refresh, so it keeps saying `true` for a
session whose token has expired until a request refreshes or ends it.

**How the 503 gets out.** `BffSessionAuthenticator`'s contract is that empty means anonymous and an
error fails the request. Empty would turn a blip at the issuer into a 401 on protected routes and
an anonymous backend call on open ones. The authenticator fails with
`BffSessionUnavailableException` instead; the bff customizer adds a `WebFilter` first in the
security chain that maps that exception (also when wrapped) to 503 with `Retry-After: 1`.
`BffSecurityContextRepository` memoises the answer per request, errors included, so a request makes
at most one attempt.

**Proof.** `BffRefreshEndToEndTest` (both lines): the call after expiry reaches the backend with the
new token, 50 simultaneous calls make one refresh, a revoked family is 401 and `/bff/session` false,
an issuer that is down is 503 while a token still valid is served, a changed `sub` ends the
session. `BffRefreshOpenRouteEndToEndTest`: 503 on an open route, never an anonymous backend call.
`BffRefreshRedisEndToEndTest` and `BffRefreshJdbcEndToEndTest`: two gateway instances over one
embedded Redis and over one embedded PostgreSQL, 25 simultaneous calls to each with the same expired
session, against a stub issuer that revokes the family on any reuse: exactly one refresh, nothing
revoked. The store contract tests cover the lease and the compare-and-set on every store.

## 9. Push with the session cookie (built in G4)

The push handshakes were already inside the security chain, so a cookie caller is authenticated at
`/push/ws` and `/push/subscribe` and is the same principal as the bearer caller (same
`JwtPrincipalConverter`; `PRINCIPAL_MATCH` unchanged). What G4 adds is the connection's lifetime.

- **Seam in `-rest`** (`dev.appiaworks.shiguchi.security`, experimental, next to `CookieAuthenticated`):
  `CookieSessions.active(handle)` and the event `CookieSessionEnded(handle)`. Push does not depend on
  `shiguchi-bff`; it asks for an optional `CookieSessions` bean and listens for the event. Without
  the bean nothing changes.
- **bff side.** `active` is one store read: no refresh, no activity touch (a socket must not keep an
  idle session alive), and false past the absolute or idle timeout. The event is published by a
  wrapper around the session store the sign-in endpoints, the authenticator and the refresher share,
  so every `delete` is covered: logout, a refused refresh (`invalid_grant`, a refreshed token for
  another principal), an expired session found on a request, and a sign-in replacing the previous
  session.
- **Push side.** A cookie connection has no token expiry (`armExpiry` skips it). It is closed when
  the event for its handle arrives (same instance, at once) or when the poll
  (`shiguchi.cursus.session-check-interval`, 30 s, at least 5 s) finds the session inactive (any
  other instance: the store is the shared truth, so the worst case is one interval). A store error
  during the poll keeps the connection and is counted. WebSocket: close 4440 `session ended`; SSE: a
  final `event: close` with `{"reason":"session ended"}`. A token in `connect` or `refresh` on a cookie
  connection closes it with 1008: one principal per connection.
- **Origin.** The security chain matches a cookie WebSocket's `Origin` exactly against the session's
  application (section 7); the lenient `ws.allowed-origins` check is for token connections only.
- **Other streams.** The gRPC-Web `PushService/Subscribe` stream and GraphQL subscriptions are bound
  by the gateway-wide `CookieSessionWatch` (`shiguchi-rest`; the logic push uses, extracted: the
  local `CookieSessionEnded` event raced with a poll of `CookieSessions.active`, a store error keeps
  the stream), with its own interval `shiguchi.security.session-check-interval` (30 s, at least
  5 s). `GrpcWebFilter` stops the frames of any bound server stream, cancels the backend call and ends
  with `grpc-status: 16`, `grpc-message: session ended` trailers. One `WebGraphQlInterceptor` binds
  every subscription (its results end with an `UNAUTHORIZED` "session ended" error) and, on a
  WebSocket request, asks `active(handle)` before running a new operation. REST server streams
  (NDJSON, SSE, `HttpBody` downloads) through `ShiguchiFilter` are bound the same way: the stream
  stops, the backend call is cancelled and it fails with `UNAUTHENTICATED` "session ended", which
  the existing in-band path renders (a last `{"error": ...}` line, a last `event: error`; before the
  first item, a plain 401; an `HttpBody` download is aborted). Bearer streams are authenticated once
  and not ended at token expiry. An operation on a cookie GraphQL socket is authenticated once, at the upgrade,
  but `BffUpstreamCredentialSource` sends the session's current token: while the token on the
  `Authentication` is outside the refresh skew it is used as it is (no store read, so ordinary HTTP
  requests pay nothing); inside the skew the session is read by its handle and renewed through the one
  `BffTokenRefresher` (a bean shared with `DefaultBffSessionAuthenticator`, so in-flight attempts and the
  cross-instance lease are shared). A session gone, refused (the refresher deletes it, which ends the
  socket's subscriptions through `CookieSessionEnded`), or an expired token with no refresh token or an
  unreachable issuer, fails the call with gRPC `UNAUTHENTICATED` "session ended" (an unreachable issuer
  with the token not yet expired falls back to the stored one). Rejected alternative: re-authenticating
  the operation in the GraphQL interceptor would need a new SPI in `-rest` to load an Authentication by
  handle, and would refresh on a schedule the credential source already covers. The `auth_scopes` check
  keeps the handshake authorities.
  Also found on the way: `PushService/Subscribe` failed with `INTERNAL` ("Cannot alter onReadyHandler
  after initialization") whenever the caller's JWT was decoded asynchronously (a JWKS fetch), for
  bearer and cookie callers alike; the writer now registers its handlers before the call method returns.
- **Tests.** `BffRestStreamBindingEndToEndTest` on both lines: the same four facts for an NDJSON and
  an SSE stream of a server-streaming route (logout, poll, 2-second token, bearer), with the backend
  asserting it saw the call cancelled. `BffStreamBindingEndToEndTest` on both lines: the cookie's user reaches a push GraphQL
  field over the WebSocket, a gRPC-Web stream and a GraphQL subscription each end on logout (local) and
  on a session lost from the store (poll), a 2-second access token does not end them, a bearer stream
  is not bound, a new operation on an ended socket is refused, a closed client releases the backend call.
  `BffPushEndToEndTest` on both lines: WebSocket with the cookie and the right `Origin`
  receives what is published for its user, a wrong `Origin` is refused at the handshake, logout closes
  with 4440, a session lost from the store is found by the poll, a 2-second access token does not close
  the socket, a bearer socket still closes with 4401, SSE ends with a `close` frame, reason `session ended`.

## 10. Session lifecycle (built in G5)

Idle and absolute timeouts were built with the stores (G1: the store needs a time to live anyway); G5
adds the ways a session ends from outside. The push tests follow it (an idle session of 2 seconds
closes its socket with 4440).

**Back-channel logout** (OpenID Connect Back-Channel Logout 1.0). `POST <path>/backchannel-logout/{provider}`,
a form with `logout_token`, open to anonymous callers (it is not cookie-authenticated, so the `Origin`
check of section 7 does not apply; the end-to-end test posts with a foreign `Origin`). Register
`https://<gateway>/bff/backchannel-logout/<provider>` as the client's `backchannel_logout_uri` at the
issuer. The token is verified with the provider's JWK set, and any failure is
`400 {"error":"invalid_request"}` with nothing deleted:

- signed (not `alg: none`); JOSE `typ` must be `logout+jwt` when present, and an absent `typ` is
  accepted, as the specification allows (a plain `JWT` is refused);
- `iss` is the provider's issuer; `aud` contains the provider's `client-id`;
- `iat` present and not more than 60 s in the future; `exp` present and not more than 60 s past;
- `events` has the key `http://schemas.openid.net/event/backchannel-logout`;
- `sid` or `sub` present, **`nonce` absent**, `jti` present (at most 255 characters);
- the `jti` was not seen: the store records it (Redis `SET NX PX`, JDBC table `<prefix>jti`, a map in
  memory) for the token's remaining life plus 60 s, after every other check passed and before anything
  is deleted, so a replay cannot end sessions created since. If the deletion then fails the record is
  forgotten, so the issuer's retry of the same token is not refused.

Then a `sid` ends the sessions with that `sid` at that provider (one browser may hold several gateway
sessions if it signed in several times), and a token with only a `sub` ends every session of that
subject at that provider. A token naming no session is still `200`. Answers carry
`Cache-Control: no-store`. A provider whose keys cannot be fetched, or a store that fails, is `503` (the
issuer retries); a custom `BffSessionStore` without the lifecycle methods is `501`.

**Indexes.** `BffSessionStore` gains default methods `deleteBySid`, `deleteBySubject`, `findBySubject`,
`markJtiSeen` and `forgetJti`. The delete methods return the keys they deleted, once each even when
instances race, and the wrapper every session-ending path goes through publishes one
`CookieSessionEnded` per key (push closes the sockets at once on this instance and by its poll on the
others). Memory scans its map (one instance); JDBC adds indexes on (`provider`, `subject`) and
(`provider`, `sid`) and deletes row by row, reporting the rows its own `DELETE` removed; Redis keeps two
sets per provider, `<prefix>sid:<provider>:<sid>` and `<prefix>sub:<provider>:<subject>`, with **separate
single-key commands, so the store stays Redis Cluster safe**. The index update is not atomic with the
session write and is ordered so that an index never misses a live session: `save`, `replaceIfVersion`
and `touch` add the key to the sets and stretch their expiry to at least the session's (one small Lua
script per set, Redis 5 has no `PEXPIRE GT`) **before** writing the session; `delete` removes the session
first and then the set entries, ignoring a failure of that second step. A set may hold a key whose
session is gone, expired or now belongs to another sid or subject: every lookup checks the session
against the set it came from, prunes what does not match and never counts it. Only the caller whose
`DEL` removed the hash reports a key, so racing revokers see disjoint answers.

**Revoke-all.** The bean `BffSessions` (experimental): `revokeSubject(provider, subject)` ends every
session of a user and answers the count, `revoke(handle)` one session; for an application that learns of
a disabled account through its own channel. The actuator endpoint `gateway-bff-sessions` (expose it with
`management.endpoints.web.exposure.include`) has `GET /actuator/gateway-bff-sessions/{provider}/{subject}`
(count, and `application`, `created`, `lastSeen` of each session; no tokens, no handles) and `DELETE` on
the same path (revoke all). Unlike `gateway-push`, **both methods** need an authenticated caller (the
chain gates them, also when open REST routes bypass it), because the read shows other users' activity.
**Browser sessions
never reach any actuator endpoint:** the bff module adds a filter, right after authentication, that
answers `403 {"error":"cookie sessions cannot call actuator endpoints"}` to every cookie-authenticated
request under the actuator base path (`management.endpoints.web.base-path`, default `/actuator`), all
methods. Without it any signed-in user satisfies "authenticated" and, since a user can send their own
cookie and a matching `Origin` from a script, could revoke other users' sessions or move traffic
(`gateway-traffic`, `gateway-push` and `gateway-refresh` writes included). Set
`shiguchi.bff.admin.role` names the role a bearer caller needs on `gateway-bff-sessions` (`X`,
`ROLE_X` or `SCOPE_X`). It is required: without it the endpoint answers `403` to every caller (and
startup logs a WARN when it is exposed), since "any authenticated bearer caller" is too wide a default
for an endpoint that ends other users' sessions; anyone who wants that sets a role every caller holds.

**Cleanup.** Redis expires on its own; memory sweeps on its own; JDBC deletes expired sessions, logins
and logout-token records every `session.jdbc.sweep-interval` (default 1 minute; the plan called this a
cleanup interval, the property already existed) and stops the task when the context closes. Reads ignore
expired rows whether or not the sweep ran.

**Revocation delay.** Ending a session stops new requests at once (the cookie no longer resolves) and
closes this instance's sockets at once, other instances' within `cursus.session-check-interval`. An access
token already sent upstream stays valid at the backends until it expires, and a call in flight is not
interrupted.

**Meter.** `gateway.bff.logout{kind=backchannel|admin|user|refresh-rejected}`, sessions ended, by how.

**Tested against a real issuer by hand.** In CI the receiver is tested with logout tokens the stub issuer
signs, in a table of refused and accepted shapes (`shiguchi-bff/src/test/resources/bcl/`) and end
to end on both Boot lines (`BffLifecycleEndToEndTest`). It has also been run by hand against a real issuer's
sender (sign-in with `authorize-parameters: {provider: google}`, logout at the issuer, the logout token
delivered on the first attempt, the session ended by `sid`); that run is not part of CI.

- **Tests.** The store contract (all three stores) covers both indexes on save, replace, touch, delete
  and expiry, racing deleters and the jti cache; `BffBackchannelLogoutTest` the validation table, replay,
  retry after a store failure, 503 and 501; `BffLifecycleEndToEndTest` (both lines) a `sub` token ending
  a user's sessions and closing the socket with 4440, a `sid` token ending one, a forged and an unsigned
  token refused, the admin endpoint refusing no token, a token without the role and an end user's cookie
  and then revoking and closing a socket, and a 2-second idle timeout closing a socket.
