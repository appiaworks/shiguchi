# BFF: endpoints, refresh and logout

Part of the [bff guide](index.md).

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

See [security.md](../security.md#cookie-authenticated-requests-csrf-and-websockets).

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
