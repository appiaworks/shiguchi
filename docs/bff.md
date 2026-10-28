# Browser sign-in with a session cookie (BFF pattern)

> Experimental, `shiguchi-bff`, off unless `shiguchi.bff.enabled=true`. The design record,
> with every decision and its reasons, is [design/bff.md](design/bff.md); every property is in
> [configuration.md](configuration.md). A runnable single-page app is in `samples/sample-bff-spa`.

## What it is for

This module implements the *Backend For Frontend* pattern of the IETF draft [OAuth 2.0 for
Browser-Based Applications](https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/)
(section 6.1). It is **not** API aggregation: nothing here shapes or combines your backends'
responses, it only moves the OIDC client and the tokens from the browser to the gateway.

A single-page app that signs users in at an OIDC provider normally keeps the provider's tokens in
JavaScript, where any script on the page (an XSS, a compromised dependency) can read and ship them
away. With this module the gateway is the OIDC client instead. It runs the sign-in, keeps the tokens in
a server-side session, and gives the browser one `HttpOnly` cookie. For every call the gateway looks
the session up and sends the provider's access token to your backend as `authorization: Bearer ...`,
so backends validate JWTs exactly as for any other caller. The browser never sees a token.

The module is a plain OIDC client of your issuer. It does not issue tokens and does not do social
login by itself: put the identity providers behind your issuer.

## Threat model

| Threat | What stops it |
| --- | --- |
| Token theft by script on the page | Tokens never leave the server. The cookie is `HttpOnly` |
| CSRF (another site makes the browser send the cookie) | Every cookie request that is not `GET`/`HEAD`/`OPTIONS`, and every WebSocket handshake, needs an `Origin` that is exactly one of the session's application's origins, else `403`. `SameSite=Lax` is the second layer, not the only one; the optional `csrf-header` is a third (below) |
| Application B's page reading application A's session (cross-application reads with `credentials: 'include'`) | A cookie `GET`/`HEAD` that carries an `Origin` needs the session's own application's origin, else `403`; the browser holds one session per gateway host, so signing in to B replaces A's |
| A form or `text/plain` post that skips CORS preflight | `415` on REST bodies of those content types |
| A backend (or a custom filter) setting or overwriting the session cookie | `Set-Cookie` for the session or login cookie is dropped unless it comes from the sign-in endpoints; startup fails if you configure `set-cookie` as a forwarded response header |
| The session cookie reaching a backend | The gateway never forwards `Cookie` (startup fails if `client.forward-headers` lists it) |
| A signed-in user reaching the gateway's admin surface | Cookie sessions get `403` on every actuator endpoint |
| Session fixation | Sign-in always issues a new session id |
| A refresh token still working after logout | Revoked at the issuer (RFC 7009) on the user's logout and on an admin revoke, best effort: a failure is logged and counted, never changes the response. Not on back-channel logout (the issuer ended the session itself), not on idle or absolute expiry (no request to hang it on; the issuer's refresh-token lifetime applies). A session created between the admin's subject lookup and the delete is ended but not revoked |
| Flooding sign-in starts (each `GET /bff/login/{provider}` stores a login record for `login-timeout`), or many logins filling the browser's cookie jar until it evicts the session cookie | `login-timeout` is capped at 15 minutes. `login-rate-limit` (off by default, per instance, per client address) answers `429` with `Retry-After` past the burst and stores nothing. There is one fixed-name login cookie, so a client that starts many sign-ins holds one cookie, not one per attempt. A cluster-wide limit belongs at the edge |
| A leaked session store | Store keys are SHA-256 of the cookie value, so the store holds nothing a browser can present. The access, refresh and ID tokens in the Redis and JDBC stores are AES-256-GCM ciphertext bound to their session and field, so a store read without the key ring yields no tokens (see [Encrypting tokens at rest](#encrypting-tokens-at-rest)). The memory store is not encrypted (the tokens sit in the process anyway), and `session.encryption.disabled: true` turns encryption off for development |
| The gateway's own secrets leaking | The gateway is a **confidential client**: its client secret lets anyone act as the application at the issuer, and the encryption keys decrypt every stored token. The client secret and the encryption keys are as sensitive as the session store itself: keep them in a secret store (see [Deployment](#deployment)), never in the repository. The module never logs a token, secret, key or cookie value |

What the module does not cover: a request with an `Authorization` header is a bearer request and is
handled as without the module (a bad header is `401`, never rescued by a cookie). A script on your
own page can still call your API through the user's browser while the page is open; the module keeps
tokens out of its hands, it does not make XSS harmless.

## Layout: same origin, or same-site siblings

**Recommended: one origin.** Serve the app and the gateway from the same host, so the app calls
`/v1/...`, `/bff/...` and `/push/ws` as relative URLs. No CORS, no third-party-cookie questions.

**Same-site siblings** (`app.example.com` and `api.example.com`) also work, with `shiguchi.cors`
allowing the app's origin with `allow-credentials: true` and the app sending `credentials:
'include'`. Set `shiguchi.bff.external-base-url` to the gateway's public origin. Startup warns
when `external-base-url` and an application are cross-site (last two host labels) while
`cookie.same-site` is not `NONE`, and when an application is on another origin than the gateway's
and `cors` does not allow it with credentials. It also warns when `external-base-url` is unset while
`cookie.secure` is `true`: the `redirect_uri` is then built from the request, and behind a TLS terminator
that sends no forwarded headers it becomes `http://` and every sign-in fails with `sign_in_failed`.
`cookie.same-site: NONE` with `cookie.secure: false` fails startup (browsers drop that cookie).

**Cross-site does not work.** A `SameSite=Lax` cookie is never attached to a cross-site `fetch`.

## Configuring an issuer

The module speaks generic OIDC: authorization code with PKCE, `state`, `nonce`. The gateway is a
**confidential client**, so register it with a client secret, the redirect URI
`https://<gateway>/bff/callback/<provider>`, and refresh tokens enabled.

```yaml
shiguchi:
  security:
    mode: AUTHENTICATED            # backends and routes need a caller
  bff:
    enabled: true
    external-base-url: https://app.example.com   # the gateway's public origin
    providers:
      sso:
        issuer-uri: https://sso.example.com      # discovery is fetched on first use
        client-id: gateway
        client-secret: ${SSO_CLIENT_SECRET}
        display-name: Corporate SSO
        scopes: [openid, profile, email, offline_access]
        audiences: [orders-api]                  # optional: the access token's aud must contain one
    applications:
      web:
        origins: [https://app.example.com]
        providers: [sso]
        return-to: [https://app.example.com/]
    session:
      store: REDIS
    admin:
      role: ops
```

- `providers.<id>`: `<id>` is `[a-z0-9-]{1,32}`. `issuer-uri`, `client-id` and `client-secret` are
  required. `authorization-uri`, `token-uri`, `jwk-set-uri` and `end-session-uri` each replace the
  value from the discovery document. `client-authentication-method` is `BASIC` (default) or `POST`.
  `hint-parameter` (default `provider`) names the authorize-request parameter that carries the
  login `hint`.
- `providers.<id>.authorize-parameters` adds fixed parameters to every authorize request, for
  example an issuer that brokers several identity providers and needs `provider: google`, or one
  that wants `audience: api`. The gateway's own parameters (`response_type`, `client_id`,
  `redirect_uri`, `scope`, `state`, `nonce`, `code_challenge`, `code_challenge_method`) cannot be
  set here: naming one fails startup. A login `hint` replaces the fixed parameter with the
  `hint-parameter` name, so a fixed `provider` acts as the default. Spring drops characters other
  than letters, digits, `-` and `.` from map keys, so quote a name with `_` in brackets:
  `"[resource_id]": api`.

  ```yaml
  shiguchi:
    bff:
      providers:
        sso:
          authorize-parameters:
            provider: google
  ```
- `providers.<id>.pass-through-parameters` (default `[prompt, max_age, acr_values, login_hint,
  ui_locales]`) lists the query parameters of `GET /bff/login/{provider}` that are copied onto the
  authorize request; any other query parameter is ignored. A value is at most 256 characters with no
  control characters; `prompt` must be space-separated `none`, `login`, `consent` or
  `select_account` (`none` alone), `max_age` a non-negative integer, and a parameter given twice is
  refused. A bad value answers `400 {"error":"invalid_parameter","parameter":"<name>"}` and the
  value is never echoed or logged. A passed value replaces a fixed `authorize-parameters` entry of
  the same name. The gateway's own parameters cannot be listed (startup fails). Set `[]` to forward
  nothing.
- `providers.<id>.jws-algorithms` lists the signature algorithms accepted on the provider's access,
  ID and back-channel logout tokens, checked against its JWK set. The default is `[RS256]` only: an
  issuer that signs with ES256, PS256 or EdDSA needs, for example, `jws-algorithms: [ES256]`, or its
  tokens are refused. List only what you trust the issuer to use, not everything it advertises: the
  discovery document's `id_token_signing_alg_values_supported` is never read. Names are exact
  (`RS256`, not `rs256`); `none` and the HMAC algorithms (`HS256`, `HS384`, `HS512`) are refused at
  startup, since HMAC needs a shared secret, not a JWK set.
- `applications.<id>`: a browser application. `origins` are exact `scheme://host[:port]` (no path,
  no `*`, never shared between applications), `providers` lists the providers it may use (others
  get `403` at login), `return-to` lists absolute URLs under its origins, and `post-logout-redirect`
  is where the provider sends the browser after logout (default the first `return-to`). One
  application needs no resolution; with several, a request belongs to the one whose origins hold its
  `Origin` header, or its own host.
- **One session per gateway host.** The gateway has one session cookie name, so a browser holds one
  session per host at a time: signing in to application B replaces application A's session (A's
  page is signed out until it signs in again). This is by design. What the applications cannot do
  is read each other's session: see the CSRF and Origin rules below.
- Startup fails, one line per problem, for a missing provider or application, an unknown provider
  name, a malformed or shared origin, a `return-to` outside the application's origins, a `__Host-`
  cookie that is not `secure`, a provider without `issuer-uri` or `client-id`, an empty, unknown, `none` or HMAC `jws-algorithms` value, and the unsafe
  combinations listed under the CSRF section below.

**Worked example: Spring Authorization Server.** The module was built against an issuer based on
Spring Authorization Server, and a provider block like the one above is all it needs: a confidential
client with PKCE and refresh tokens, `issuer-uri` set, nothing else.

### Issuer prerequisites

What the provider (the OIDC issuer) has to offer for the module to work:

- **JWT access tokens.** The access token is decoded against the issuer's JWK set and forwarded to
  your backends as it is. An opaque access token is not supported yet.
- **A supported signature algorithm.** The access, ID and logout tokens must be signed with an
  algorithm in the provider's `jws-algorithms` (default `[RS256]` only, see above).
- **An `exp` claim** on the access token: the session's refresh schedule follows it.
- **An ID token** (request the `openid` scope), and in it **`sid`** if you want back-channel logout
  by session id (without `sid` only a logout token with `sub` can end sessions, and it ends all of
  that subject's sessions at the provider). `acr`, `amr` and `auth_time` are optional; see
  [Silent re-authentication and step-up](#silent-re-authentication-and-step-up).
- **Refresh token rotation is assumed.** The module refreshes once per session across all
  instances because issuers that rotate refresh tokens treat a second use as theft (see
  [Refresh](#refresh)). Without a refresh token the session ends with the access token.
- **A `revocation_endpoint`** (RFC 7009) is recommended: without it a logout cannot revoke the
  refresh token at the issuer, which then lives until its own lifetime ends. It comes from the
  discovery document or `revocation-uri`.
- **`offline_access`** only when you want sessions to outlive the issuer's default refresh-token
  lifetime; many issuers need it to issue a refresh token at all, so check yours.

## `relay`, and why there is no `mint`

`upstream-token-mode` has one value, `RELAY` (the default): the access token your issuer issued is
what backends receive. They validate it against the same issuer as for any other client, and the
gateway builds the same principal for a cookie caller as for a bearer caller (scopes, `x-principal`,
rate-limit key, push `PRINCIPAL_MATCH`).

`mint`, where the gateway would sign its own tokens, is deliberately not offered. It would make the
gateway an issuer, with signing keys, a JWKS and its own user identity. If you have an issuer, you do
not need it; it stays an optional later slice for deployments without one.

## Sessions and stores

The session cookie (`shiguchi.bff.cookie.*`) defaults to `__Host-gw-session`, `HttpOnly`,
`Secure`, `Path=/`, `SameSite=Lax`, and is a browser-session cookie unless `cookie.persistent`
(then `Max-Age` equals `session.absolute-timeout`). A `__Host-` name requires `secure: true`; for
plain-http development set `cookie.name` and `cookie.secure: false`.

`session.idle-timeout` (30m) runs from the last use and `session.absolute-timeout` (12h) from
sign-in; both are checked on every lookup. `session.store` selects where sessions and in-flight
logins live:

| Store | Use it for | Needs |
| --- | --- | --- |
| `MEMORY` (default) | Development, one instance | nothing |
| `REDIS` | Several instances | `spring-boot-starter-data-redis-reactive` and `spring.data.redis.*`. Redis 5+, Redis Cluster works (one key per command). `session.redis.key-prefix` (`gw:bff:`) |
| `JDBC` | Several instances with a database | `spring-boot-starter-jdbc`, a driver and one `DataSource` (H2 and PostgreSQL tested). `session.jdbc.data-source`, `table-prefix` (`gw_bff_`), `create-schema`, `sweep-interval` (`1m`), `dialect` |

Choosing a store whose dependency or bean is missing fails startup naming it. A `BffSessionStore`
bean of your own replaces all three; it must implement the lease and lifecycle methods for refresh
and revocation to work (see [configuration.md](configuration.md#extension-points-beans)).

### Encrypting tokens at rest

With `REDIS` or `JDBC` the access, refresh and ID token of every session are stored as AES-256-GCM
ciphertext, `v1.<key id>.<value>`, with the session key and the field name as authenticated data, so a
value copied to another row or field does not decrypt. Subject, `sid`, provider, application and the
timestamps stay readable: they are the indexes and what the admin view shows. Startup fails unless
you either set a key ring or accept plaintext:

```yaml
shiguchi:
  bff:
    session:
      store: REDIS
      encryption:
        active: k1
        keys:
          k1: ${BFF_SESSION_KEY_K1}   # Base64 of 32 random bytes: openssl rand -base64 32
```

Keep the keys in your secret store, not in the repository, and give every instance the same ring. The
standard or URL-safe Base64 alphabet is accepted; a key id matches `[A-Za-z0-9_-]{1,32}` (lower case if
you set it from an environment variable). `session.encryption.disabled: true` keeps plaintext tokens
(startup logs a WARN); use it for development only. The `MEMORY` store is never encrypted and ignores
the keys.

**Rotating a key.** Writes use `active`; reads accept every key in `keys`. Add the new key, switch
`active` to it, roll the instances, and remove the old key after one `session.absolute-timeout`, when no
session written under it is left. A session whose tokens cannot be decrypted (a key removed too early,
a plaintext row from before encryption was on) reads as no session: the browser signs in again. The
gateway logs one WARN per reason and minute naming the key id, never a value.

## The endpoints

All under `shiguchi.bff.path` (default `/bff`), open in the security chain, always
`Cache-Control: no-store`.

| Endpoint | What it does |
| --- | --- |
| `GET /bff/login/{provider}?return_to=&hint=` | Start sign-in with a top-level navigation. `return_to` must fall under one application's `return-to` (400 otherwise); a provider the application does not list is 403. Also forwards the allow-listed `prompt`, `max_age`, `acr_values`, `login_hint` and `ui_locales` (see Silent re-authentication and step-up; `400 invalid_parameter` for a bad value). Answers 302 to the issuer |
| `GET /bff/callback/{provider}` | Where the issuer sends the browser back. Needs the login cookie, whose value must equal `state` (else `400 {"error":"login_expired"}`); exchanges the code, validates the ID and access tokens, sets the session cookie and answers 302 to `return_to`. A failure after that point redirects with `bff_error=sign_in_failed` (or the issuer's own short `error`, such as `access_denied`; `login_required`, `interaction_required`, `consent_required` and `account_selection_required` all arrive as `bff_error=login_required`) |
| `GET /bff/session` | `{"authenticated":true,"subject","provider","application","name","email","picture","claims","acr","amr","authTime","expiresAt"}` (`name`, `email` and `picture` only when the application lists them in `session-claims` and the ID token carried them; `claims` only when other claims are listed and present, see Session claims; `acr`, `amr` and `authTime`, an ISO-8601 instant, only when the ID token carried them) or `{"authenticated":false}`. Never 401, so a UI can poll it |
| `GET /bff/sessions` | The caller's own sessions ("my devices"), see My sessions: `{"sessions":[{"id","application","created","lastSeen","current","userAgent"}]}`. Cookie callers only: `401` without a session cookie, with an `Authorization` header, or with an ended session |
| `DELETE /bff/sessions/{id}` | Ends one of the caller's own sessions; `404 {"error":"session_not_found"}` for an id that is not one of them. Needs the application's `Origin` (`403` otherwise). Ending the session the request carries is a logout (cookie cleared, `logoutUrl` returned) |
| `POST /bff/logout` | See Logout |
| `GET /bff/providers?app=` | `[{"id","displayName"}]` for the application (`app`, else `Origin`, else the single application) |
| `POST /bff/backchannel-logout/{provider}` | The issuer's way to end sessions, see below |

### Session claims

`shiguchi.bff.applications.<id>.session-claims` lists the ID-token claims `GET /bff/session` returns
for that application (default `[name, email, picture]`: what it returned before). `name`, `email` and
`picture` come back as fields of their own; every other listed name that the ID token carries comes back
under `claims`, so an SPA gets roles, tenant or locale without a separate userinfo call:

```json
{"authenticated":true,"subject":"alice","name":"Alice","claims":{"roles":["admin","dev"],"tenant":"acme"}, "...":"..."}
```

```yaml
shiguchi:
  bff:
    applications:
      eve:
        session-claims: [name, email, roles, tenant, locale]
```

- Values are copied as JSON: strings, numbers, booleans, lists of those and objects of those, at most two
  levels deep. A claim of any other shape (deeper nesting, anything that is not JSON-like) is dropped.
- The claims are read from the ID token at sign-in and replaced by the listed claims of each refreshed
  ID token that verifies (the access token is not consulted; a refresh answer without an ID token, or
  with one that does not verify, keeps the stored claims). They are as old as the last sign-in or refresh;
  a role revoked at the issuer shows up after the next refresh.
- At most 4 KB of claims per session (serialized): a claim that no longer fits is dropped with a WARN that
  names the application and the claim, never a value. Keep large claims (group lists) out of the list.
- Changing `session-claims` takes effect at once for claims already stored (an unlisted one stops being
  returned); a newly listed claim appears at the next sign-in or refresh.
- There is no `/bff/userinfo` proxy: the ID token already carries what the endpoint would return. If an
  issuer puts a claim only in its userinfo response, that claim is not available here.
- Claims are not secrets from the user, but they are stored in clear text in Redis or the database
  (unlike the tokens, they are not encrypted).

### My sessions

`GET /bff/sessions` lists the signed-in user's own sessions at the same provider, across applications, and
`DELETE /bff/sessions/{id}` ends one, so a page can offer "sign out other devices":

```js
const {sessions} = await (await fetch('/bff/sessions', {credentials: 'include'})).json();
for (const s of sessions.filter(s => !s.current)) {
  await fetch(`/bff/sessions/${s.id}`, {method: 'DELETE', credentials: 'include'});
}
```

- Fields: `id` (an opaque handle, see below), `application`, `created` and `lastSeen` (ISO-8601),
  `current` (the session making the request) and `userAgent` (when recorded). At most 100 sessions, most
  recently used first. Session keys, cookie values and tokens are never returned.
- Only the caller's own sessions can be listed or ended: the lookup starts from the caller's subject, so a
  handle of someone else's session is `404`, the same answer as for a handle that does not exist.
- Cookie callers only. A request with an `Authorization` header is `401`, and so is one without a valid
  session. `DELETE` needs an `Origin` that is one of the session's application's origins (`403`
  otherwise); a `GET` that carries an `Origin` is held to the same rule.
- Ending another session works like an administrator ending it (`BffSessions`): the session is deleted,
  its push connections close, its refresh token is revoked at the issuer, and it is counted as
  `gateway.bff.logout{kind=user}`. The issuer's own session and its other sessions are untouched.
- **Handles.** `id` is `Base64url(HMAC-SHA256(k, store key))`, where `k` is derived (HKDF-SHA256, label
  `bff-session-handle`) from the active `session.encryption` key, never the key itself. Handles are
  therefore stable across restarts and across instances sharing the key, and change when the active key
  is rotated: use an `id` from a listing you just fetched. Without encryption keys (the `MEMORY` store, or
  `session.encryption.disabled`) `k` is random per boot: handles change on restart, and with several
  instances sharing a store a handle only works on the instance that listed it.
- The sign-in's `User-Agent` (cut to 200 characters, control characters replaced) is stored with the
  session for the listing; `session.record-user-agent: false` stops that.

### Silent re-authentication and step-up

The login endpoint forwards a few OIDC parameters, so a SPA can ask the issuer for a different
authentication without a separate flow. The result is always the same redirect back to `return_to`.

- **Silent re-auth.** Navigate (a hidden iframe does not work: the session cookie is
  `SameSite=Lax` and the login must be top-level) to `/bff/login/idp?return_to=...&prompt=none`. If
  the user still has a session at the issuer, the sign-in completes without a page. If not, the
  issuer answers `login_required` (or `interaction_required`, `consent_required`) and the browser
  returns to `return_to?bff_error=login_required`: show your sign-in button. The existing gateway
  session is never touched by a failed silent attempt.
- **Step-up.** `.../login/idp?return_to=...&acr_values=urn:example:mfa&max_age=0` asks the issuer for
  a stronger or fresher authentication. The old session stays valid until the new sign-in succeeds,
  then it is replaced (the cookie rotates). The new session's `acr`, `amr` and `authTime` are on
  `GET /bff/session`, so the SPA can check the level it got.
- **`max_age` is enforced, `acr_values` is not.** When `max_age` was sent, the ID token must carry
  `auth_time` no older than `max_age` plus 60 seconds of skew (OIDC Core 3.1.2.1 and 3.1.3.7);
  otherwise the sign-in fails with `bff_error=sign_in_failed` and no session is created. For
  `acr_values` the gateway only records the `acr` the issuer returned: whether it is enough is the
  SPA's decision, and for anything that matters an authorizer's
  to enforce, since the SPA cannot be trusted to.

```js
// Ask for a fresh MFA sign-in before a sensitive action
const s = await (await fetch('/bff/session', {credentials: 'include'})).json();
if (!s.acr || s.acr !== 'urn:example:mfa' || Date.now() - Date.parse(s.authTime) > 300000) {
  location.href = '/bff/login/idp?return_to=' + encodeURIComponent(location.href)
      + '&acr_values=urn:example:mfa&max_age=300';
}
```

### Sign-in limits and the login cookie

Every sign-in start stores a login record for `login-timeout` (at most 15 minutes; startup fails
above that). `shiguchi.bff.login-rate-limit.requests-per-second` caps how fast one client
address may start sign-ins; past `burst` (default twice the rate) `GET /bff/login/{provider}`
answers `429 {"error":"too_many_requests"}` with `Retry-After` (whole seconds, at least 1) and
stores nothing. It is **off by default**: behind a load balancer that sends no forwarded headers,
or behind a corporate NAT, every user shares one address, and a default-on limit would cap sign-in
for the whole site. The key is the request's remote address; forwarded headers count only when
`server.forward-headers-strategy` is set (Spring's `ForwardedHeaderTransformer`), and startup
warns when the limit is on, `external-base-url` is set and the strategy is not. The limit is per
instance; a cluster-wide limit belongs at the edge. Startup logs an INFO line while it is off.

There is one login cookie, `__Host-gw-login` (`gw-login` when `cookie.secure` is `false`, because
the prefix requires `Secure`), holding the `state` of the latest sign-in. Starting a second sign-in
in the same browser (another tab) replaces it, so the first tab's callback answers `400
{"error":"login_expired"}` and the user starts that sign-in again; the second tab completes. A
callback whose cookie is missing, expired or for another `state` gets the same answer.

## Refresh

A session outlives its access token. A request whose token expires within `shiguchi.bff.refresh-skew`
(60 s) is refreshed first, by the gateway, from the session's refresh token. A session with no
refresh token ends when the access token does.

Issuers rotate the refresh token on every use and revoke the whole family on any reuse, so two
refreshes of one session sign the user out everywhere. The gateway therefore refreshes **once per
session across all instances**: concurrent requests on one instance share one attempt, and a lease
in the session store (`refresh-lease`, 10 s) keeps other instances waiting for the result instead of
calling the issuer.

The holder's critical section is bounded so the lease cannot run out under it. The token call (5 s
timeout), the checks of its answer and the decoding of the new tokens run for at most `refresh-lease`
minus 2 s; past that the holder gives the lease up and answers as if the issuer were unreachable
(`503` with `Retry-After` for an expired token). The store write that follows is one atomic
compare-and-set and is never cancelled. `refresh-lease` must therefore be at least 8 s (startup fails
below that). The issuer's JWK set is fetched, with a 5 s timeout, **before** the lease is taken (by
decoding the session's own old access token, best effort), so a slow or cold key fetch normally costs
the lease nothing; a failed warm-up never blocks the refresh.

A new ID token that comes back with a refresh is verified before it replaces the stored one (the one
`id_token_hint` sends at logout): signature, issuer, audience, expiry, and the same `sub` as the ID token
the session signed in with (OpenID Connect Core 12.2). There is no nonce in a refresh response, so none is
checked. A different `sub` ends the session like a refused refresh; any other failure keeps the previous ID
token, logs one warning (never the token) and carries on with the new access token.

| Outcome | Result |
| --- | --- |
| Refreshed, or someone else refreshed | The call is served with the new token |
| The issuer answers 400 or 401 (revoked, `invalid_grant`), or returns a token for another principal | The session is deleted: `401`, `/bff/session` false |
| Issuer unreachable or 5xx, token expired | `503` with `Retry-After: 1`; the session is kept, so the app can retry |
| Issuer unreachable, token still valid | Served with the token it has |

A token that is still valid never waits for a refresh. Meter: `gateway.bff.refresh{outcome=ok|rejected|transient|waited}`.

**Deployment note: clocks.** The leases compare clocks of the instances (the JDBC store stores an expiry
written by one instance and read by another). Keep every instance NTP-synchronised; clock skew must stay
well below `refresh-lease` minus the token-call time (5 s), or two instances can believe they hold one lease
and the issuer revokes the session's refresh-token family.

## CSRF and Origin rules

Applied by `BffOriginFilter` to cookie-authenticated requests only (bearer and anonymous requests pass):

- A method other than `GET`/`HEAD`/`OPTIONS`, or any `Upgrade: websocket` handshake, needs an
  `Origin` equal to one of **the session's own application's** origins. A missing, `null` or other
  `Origin` (another configured application's included) is `403 {"error":"origin not allowed"}`.
  `Sec-Fetch-Site` is not consulted: browsers always send `Origin` on those requests, and an exact
  match proves what it would.
- A `GET`/`HEAD` that carries the cookie and an `Origin` is held to the same rule (`null` and
  another application's origin included), on REST, gRPC-Web, GraphQL, `/bff/session` and
  `/bff/providers`: application B's page cannot read application A's session with
  `fetch(url, {credentials: 'include'})`. Without an `Origin` (navigations, `<img>`, same-origin
  fetches) the request passes. A CORS preflight (`OPTIONS`) carries no cookie and is never refused
  by this rule.
- `GET /bff/sessions` and `DELETE /bff/sessions/{id}` check the `Origin` themselves as well (required for
  `DELETE`), against the origins of the session's own application.
- On REST, a body typed `text/plain`, `application/x-www-form-urlencoded` or `multipart/form-data`,
  or untyped, is `415`. gRPC-Web, GraphQL and `POST /bff/logout` are exempt (the logout endpoint keeps
  its own `Origin` check).
- Optional second layer: with `shiguchi.bff.csrf-header.name` set (default empty, off), every
  cookie request on an unsafe method must also carry that header (name case-insensitive) with
  exactly `csrf-header.value` (default `1`), else `403 {"error":"csrf header required"}`. It
  applies on REST, gRPC-Web, GraphQL and `POST <path>/logout`; reads, WebSocket handshakes (a
  browser cannot set headers on `new WebSocket`; the `Origin` rule still covers them) and bearer
  callers are exempt. A custom header forces a CORS preflight for a cross-origin request, so the
  `415` content-type rule is skipped while the header is required. With it on, the SPA logs out with
  `fetch`, not a plain form post. Off by default because the `Origin` rule already proves the
  request's origin, and every client (curl, scripts using the cookie, SPAs) would need to send the
  header; turn it on for defence in depth. Per gateway, not per application.
- Startup fails for: `cors.allow-credentials: true` with `*` in `cors.allowed-origins`;
  `set-cookie` or `*` in `rest.forward-response-headers` or a service's `forward-response-headers`;
  `cookie` in `client.forward-headers`.

See [security.md](security.md#cookie-authenticated-requests-csrf-and-websockets).

## Push with the cookie

With `shiguchi.cursus.enabled`, the browser opens `/push/ws` or `/push/subscribe` with the cookie
and no token. The principal is the one a bearer caller of that user gets, so `PRINCIPAL_MATCH`
namespaces work unchanged. The connection lives as long as the **session**, not the access token:

- Session over (logout, a refused refresh, idle or absolute timeout, revocation): the WebSocket
  closes with **4440** `session ended`; an SSE stream ends with `event: close` and
  `{"reason":"session ended"}`. 4440 means "sign in again", not "reconnect".
- On the instance where the session ended, sockets close at once. Sockets on other instances are
  found by a poll every `shiguchi.cursus.session-check-interval` (`30s`, at least `5s`).
- A token in `connect` or `refresh` on a cookie connection closes it with `1008`.
- The `Origin` is matched exactly against the session's application. `ws.allowed-origins` is for
  token connections only.

In `@appiaworks/shiguchi-cursus-client` use `auth: { mode: 'cookie' }`; it sends no `connect` frame, stops on
4440 and reports `session-ended`. Details: [push.md](push.md#cookie-connections).

### REST, gRPC-Web and GraphQL streams

More long-lived streams a browser opens with the cookie live and die with the **session** too, not
the access token (a gRPC-Web or REST stream is sent upstream with the token it started with and is not
renewed; a GraphQL socket's operations are, see below):

- **gRPC-Web server streams**, every one (the push `PushService/Subscribe` included). When the session
  ends the frames stop, the call to the backend is cancelled, and the stream finishes with the trailers
  `grpc-status: 16` (`UNAUTHENTICATED`) and `grpc-message: session ended`, not the usual `OK`. A
  client should treat 16 on a cookie stream as "sign in again".
- **REST server streams** of transcoded routes, NDJSON and Server-Sent Events alike (and a
  `stream google.api.HttpBody` download). When the session ends the items stop, the backend call is
  cancelled, and the stream ends the way a failure after the first item always does: NDJSON gets a
  last line `{"error": {...}}`, SSE a last `event: error`, each with the usual error body
  (status `UNAUTHENTICATED`, message `session ended`), and an `HttpBody` download is
  aborted like any mid-download failure. If the session ended before the first item the response is
  the plain `401`.
- **GraphQL subscriptions** over `graphql-transport-ws`. When the session ends the subscription
  receives an `error` with `message: "session ended"`, `extensions.classification: UNAUTHORIZED` and
  `extensions.code: unauthenticated`. Every *new* operation (subscribe, query, mutation) sent on an open
  socket of a cookie caller first asks whether the session is still active and is refused with the same
  error when it is not, so a socket opened before the logout cannot go on calling. A GraphQL request
  over plain HTTP has just loaded its session and is not asked again.

An operation on a cookie GraphQL socket also reaches the backend with the session's **current** access
token, not the one the socket was opened with. While the token the socket holds is more than
`shiguchi.bff.refresh-skew` from expiry the operation costs nothing extra. Past that, the gateway
reads the session and, if its token is due, refreshes it through the same path as an HTTP request (once
per session across instances), so a socket older than the access token keeps working. If the issuer
refuses the refresh the session ends (as on HTTP) and the operation fails with an `UNAUTHENTICATED` error
(`session ended`); if the issuer cannot be reached and the token has expired, it fails too and is not sent
with the expired token. The `x-principal` the backend sees is the one the socket was opened with, which
the refresh guarantees is the same user; scopes checked by `auth_scopes` are those of the handshake token.

As for push, a session that ends on the instance holding the stream ends it at once; one that ends on
another instance is found by a poll every `shiguchi.security.session-check-interval` (`30s`, at
least `5s`; push keeps its own, `shiguchi.cursus.session-check-interval`). A store error during a
poll keeps the stream. Bearer streams are not bound to a session. A gRPC-Web or REST stream with a
*bearer* token ends when that token expires (`exp`), with `UNAUTHENTICATED` "token expired" (the same
trailers or in-band error as "session ended"; a push WebSocket closes with 4401); the client reconnects
with a fresh token. A token accepted within the JWT clock skew (up to 60 s past `exp`) ends its stream at
once. `shiguchi.security.end-streams-at-token-expiry=false` lets such a stream outlive its token.
A cookie caller's streams never follow the access token they carry, only the session.

A bearer GraphQL subscription ends the same way (over WebSocket or HTTP), with an `error` whose
`message` is `token expired`, `extensions.classification` `UNAUTHORIZED` and `extensions.code`
`unauthenticated`, and the backend call is cancelled. A socket's authentication is the handshake's,
so every *new* operation sent on an open bearer socket after the token's `exp` is refused with that
error; a GraphQL request over plain HTTP was just authenticated and is not re-checked. This needs no
browser sign-in.

## Logout

`POST /bff/logout` from the app's page (needs the application's `Origin`; `403` otherwise). It
deletes the session, expires the cookie and answers `200 {"logoutUrl": "..."}`: the provider's
end-session URL with `id_token_hint`, `post_logout_redirect_uri` and `client_id`, when the provider
has an end-session endpoint, else `{}`. The app should navigate to `logoutUrl` to end the issuer's
session too. Without a session the call is idempotent and answers 200. The session's push sockets
close with 4440.

**Revocation at the issuer.** After the session row is deleted, the gateway also revokes the session's
refresh token at the issuer's revocation endpoint (RFC 7009: `token` and `token_type_hint=refresh_token`,
the client authenticated as for the token endpoint; the access token with `access_token` when the session
has no refresh token). The endpoint comes from the discovery document's `revocation_endpoint`, or
`shiguchi.bff.providers.<id>.revocation-uri`; with neither, nothing is revoked (logged at DEBUG once per
provider). The call runs in the background with a 5 s timeout, after the response is decided: a failure
(non-2xx, timeout, connection error) is logged as a WARN naming the provider and the status or error
class, never a token, and never changes the response or the revoke count. The same happens for sessions
ended through `BffSessions` and the `gateway-bff-sessions` endpoint. It does **not** happen on back-channel
logout (the issuer ended the session), nor when a session expires by idle or absolute timeout. Meter:
`gateway.bff.revocation{outcome=revoked|failed|skipped}`.

## Back-channel logout and revocation

**Back-channel logout** (OpenID Connect Back-Channel Logout 1.0): register
`https://<gateway>/bff/backchannel-logout/<provider>` as the client's `backchannel_logout_uri`. The
endpoint is open to anonymous callers (the signed `logout_token` is the credential), verifies it
against the provider's keys (signature, `iss`, `aud`, `iat`/`exp`, the event, `sid` or `sub`, no
`nonce`, a `jti` not seen before) and answers `400 {"error":"invalid_request"}` on any failure. A
`sid` ends the sessions with that `sid`; a `sub` alone ends all of that subject's sessions at that
provider. A token naming no session is `200`. `503` means the issuer should retry.

**Revoke-all.** The `BffSessions` bean (`revokeSubject(provider, subject)`, `revoke(handle)`) is for an
application that learns of a disabled account through its own channel. The actuator endpoint
`gateway-bff-sessions` (add it to `management.endpoints.web.exposure.include`) has
`GET /actuator/gateway-bff-sessions/{provider}/{subject}` (the count, and each session's `application`,
`created`, `lastSeen`; no tokens) and `DELETE` on the same path. Both need an authenticated caller
holding the role **`shiguchi.bff.admin.role`** names (as `X`, `ROLE_X` or `SCOPE_X`). The role is
required: without it the endpoint answers `403 {"error":"admin role not configured"}` to every caller,
and startup logs a WARN when the endpoint is exposed.

**Browser sessions never reach any actuator endpoint:** a cookie-authenticated request under the
actuator base path gets `403 {"error":"cookie sessions cannot call actuator endpoints"}`, whatever
its role.

An ended session stops new requests at once. An access token already sent upstream stays valid at the
backends until it expires, and a call in flight is not interrupted. Meters:
`gateway.bff.logout{kind=backchannel|admin|user|refresh-rejected}` and
`gateway.bff.revocation{outcome=revoked|failed|skipped}`.

## Deployment

- **TLS and the public origin.** The session cookie is `Secure` (and `__Host-`) by default, so the
  browser must reach the gateway over https. Set `shiguchi.bff.external-base-url` to that public
  origin, since the `redirect_uri` registered at the issuer is built from it. When a TLS terminator or
  load balancer sits in front, also set `server.forward-headers-strategy` (for example `framework`) so the
  gateway sees the original scheme, host and client address; the sign-in rate limit needs it too.
  Startup warns about the combinations that cannot work.
- **More than one instance needs `REDIS` or `JDBC`.** The `MEMORY` store is for development and a
  single instance: two instances would not see each other's sessions, logins or refresh leases. Redis
  Cluster is supported (every command touches one key); Redis Sentinel is untested. Give every instance
  the same `session.encryption` key ring.
- **Clocks.** Keep every instance NTP-synchronised, see the note under [Refresh](#refresh).
- **Sizing.** The store holds one record per live session (three tokens, the listed claims up to 4 KB, a
  few indexes) plus one per sign-in in flight, which lives at most `login-timeout`. A session is kept until
  its idle or absolute timeout, so size for the users signed in within the last `idle-timeout` plus the
  busiest hour of sign-ins. Redis expires records itself; the `JDBC` store deletes expired rows every
  `session.jdbc.sweep-interval` (`1m`), so its cost grows with the number of rows that expired in between.
- **Secrets to inject.** Each provider's `client-secret` and the `session.encryption.keys` come from your
  secret store through environment variables or config properties, not from the image or the repository.
- **The issuer being down.** Provider discovery is lazy: the gateway starts without the issuer and fetches
  its metadata at the first sign-in, and a failure is not cached. While it cannot be reached
  `GET /bff/login/{provider}` answers `502 {"error":"provider_unavailable"}`. Existing sessions keep working
  until their access token expires; then a request is answered `503` with `Retry-After: 1` and the session
  is kept (see [Refresh](#refresh)).

## Migrating a SPA from bearer tokens

For an application that holds access tokens in JavaScript today. The backend side needs no change:
keep the resource-server configuration (`spring.security.oauth2.resourceserver.jwt.*`) so bearer callers
(scripts, other services) work as before. The sample page,
[`samples/sample-bff-spa/web/src/main.ts`](../samples/sample-bff-spa/web/src/main.ts), shows each step in
about 200 lines.

1. **Stop storing tokens.** Remove the OIDC client library and its `localStorage`/`sessionStorage` use. The
   page asks `GET /bff/session` ([`refreshSession`](../samples/sample-bff-spa/web/src/main.ts)) whether it
   is signed in; the endpoint never returns 401, so it can be polled.
2. **Drop the `Authorization` header** from every call. A request with one is handled as a bearer request
   and the cookie is not consulted, so a stale header turns a signed-in user into a `401`.
3. **Send the cookie.** Same origin needs nothing. For a sibling origin use `credentials: 'include'`
   everywhere (`fetch`, the gRPC-Web transport) and configure `shiguchi.cors` as described under
   [Layout](#layout-same-origin-or-same-site-siblings).
4. **Sign in by navigation.** List the providers with `GET /bff/providers` and send the browser to
   `/bff/login/{provider}?return_to=<your page>`
   ([`loadProviders`](../samples/sample-bff-spa/web/src/main.ts)). On a `401` from any call, do the same.
5. **Read the outcome.** After a failed sign-in the browser returns to `return_to` with `?bff_error=<code>`
   (`sign_in_failed`, `access_denied`, `login_required`...). Show your sign-in button and strip the
   parameter.
6. **Handle `503`.** A `503` with `Retry-After` means the issuer could not be reached to refresh the token;
   the session is kept, so retry after the delay instead of signing the user out.
7. **Log out with a `POST`** to `/bff/logout` ([`logout`](../samples/sample-bff-spa/web/src/main.ts)), then
   navigate to the returned `logoutUrl` if you want the issuer's session ended too.
8. **Add the CSRF header if `csrf-header` is on.** Every unsafe-method call (REST writes, gRPC-Web, GraphQL,
   logout) must carry it; the sample does ([`CSRF`](../samples/sample-bff-spa/web/src/main.ts)).
9. **Push.** Use `auth: { mode: 'cookie' }` and no token
   ([`startPush`](../samples/sample-bff-spa/web/src/main.ts)). Close code **4440** means the session ended:
   sign in again, do not reconnect.
10. **Streams.** A gRPC-Web stream that ends with `grpc-status 16` and a REST or GraphQL stream that ends
    with `session ended` mean the same: sign in again (see
    [REST, gRPC-Web and GraphQL streams](#rest-grpc-web-and-graphql-streams)).

### SPA snippets

The snippets assume one origin and `csrf-header.name: X-CSRF`; drop the header lines when it is off and add
`credentials: 'include'` for a sibling origin.

**`fetch`** (REST and the `/bff` endpoints):

```ts
const CSRF = { 'X-CSRF': '1' }

async function api(path: string, init: RequestInit = {}): Promise<Response> {
  const res = await fetch(path, { ...init, headers: { ...CSRF, ...init.headers } })
  if (res.status === 401) {
    location.href = '/bff/login/sso?return_to=' + encodeURIComponent(location.href)
  } else if (res.status === 503) {
    const wait = Number(res.headers.get('Retry-After') ?? '1') * 1000
    await new Promise((resolve) => setTimeout(resolve, wait))
    return api(path, init) // the session is kept: try again
  }
  return res
}
```

**Connect-ES** (gRPC-Web, with stubs generated as in [grpc-web.md](grpc-web.md#server-streaming-from-connect-es)):

```ts
import { Code, ConnectError, createClient, type Interceptor } from "@connectrpc/connect";
import { createGrpcWebTransport } from "@connectrpc/connect-web";
import { PushService } from "./gen/dev/appiaworks/shiguchi/proto/push_pb";

// No Authorization header: the browser sends the session cookie.
const csrf: Interceptor = (next) => async (req) => {
  req.header.set("X-CSRF", "1");
  return next(req);
};
const transport = createGrpcWebTransport({
  baseUrl: location.origin,
  interceptors: [csrf],
  // sibling origin only: fetch: (input, init) => globalThis.fetch(input, { ...init, credentials: "include" }),
});
const client = createClient(PushService, transport);

try {
  for await (const ev of client.subscribe({ topics: ["orders:123"] })) console.log(ev.id);
} catch (e) {
  // 16 on a cookie stream: the session ended (also the answer to a call without a session)
  if (ConnectError.from(e).code === Code.Unauthenticated) {
    location.href = "/bff/login/sso?return_to=" + encodeURIComponent(location.href);
  }
}
```

**graphql-ws** (subscriptions over `graphql-transport-ws`; the path is `spring.graphql.websocket.path`):

```ts
import { createClient } from "graphql-ws";

// The browser sends the session cookie with the handshake; there are no connectionParams.
const client = createClient({
  url: `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/graphql/ws`,
});

client.subscribe(
  { query: "subscription { ... }" },
  {
    next: (data) => console.log(data),
    error: (e) => console.error(e), // a "session ended" error: sign in again
    complete: () => {},
  },
);
```

**Push client** (`@appiaworks/shiguchi-cursus-client`, as in [`startPush`](../samples/sample-bff-spa/web/src/main.ts)):

```ts
import { createPushClient } from "@appiaworks/shiguchi-cursus-client";

const push = createPushClient({
  url: `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/push/ws`,
  auth: { mode: "cookie" }, // no token, no connect frame
});
push.on("error", (e) => {
  if (e.code === "session-ended" || e.code === "signed-out") location.reload(); // 4440: sign in again
});
push.subscribe("user:alice", { onEvent: (e) => console.log(e.topic, e.id, e.data) });
```

## Known limitations

- **A GraphQL socket keeps the scopes of its handshake.** Operations get the session's refreshed
  access token upstream, but the gateway's own `auth_scopes` check still uses the authorities read when
  the socket was opened; a scope the issuer adds or removes later applies to a new socket.
- **Push polling does not touch the session.** An open socket does not keep an idle session alive, so
  the idle timeout governs sockets: a page that only listens is signed out after `idle-timeout`
  unless it also makes calls.
- **Tokens at rest are unencrypted in the memory store**, and in Redis and JDBC when `session.encryption.disabled` is `true`.
- `REST_DOWNSTREAM` routes have no cookie to send and no end-to-end test of their own with cookie sign-in.
