# Browser sign-in with a session cookie (BFF pattern)

> Experimental, `shiguchi-bff`, off unless `shiguchi.bff.enabled=true`. The design record,
> with every decision and its reasons, is [design/bff.md](../../design/bff.md); every property is in
> [configuration.md](../../reference/configuration.md). A runnable single-page app is in `samples/sample-bff-spa`.

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
  [Silent re-authentication and step-up](endpoints.md#silent-re-authentication-and-step-up).
- **Refresh token rotation is assumed.** The module refreshes once per session across all
  instances because issuers that rotate refresh tokens treat a second use as theft (see
  [Refresh](endpoints.md#refresh)). Without a refresh token the session ends with the access token.
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
and revocation to work (see [configuration.md](../../reference/configuration.md#extension-points-beans)).

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
