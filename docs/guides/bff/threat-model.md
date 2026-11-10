# BFF: threat model and limitations

Part of the [bff guide](index.md).

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
| A leaked session store | Store keys are SHA-256 of the cookie value, so the store holds nothing a browser can present. The access, refresh and ID tokens in the Redis and JDBC stores are AES-256-GCM ciphertext bound to their session and field, so a store read without the key ring yields no tokens (see [Encrypting tokens at rest](index.md#encrypting-tokens-at-rest)). The memory store is not encrypted (the tokens sit in the process anyway), and `session.encryption.disabled: true` turns encryption off for development |
| The gateway's own secrets leaking | The gateway is a **confidential client**: its client secret lets anyone act as the application at the issuer, and the encryption keys decrypt every stored token. The client secret and the encryption keys are as sensitive as the session store itself: keep them in a secret store (see [Deployment](migrating.md#deployment)), never in the repository. The module never logs a token, secret, key or cookie value |

What the module does not cover: a request with an `Authorization` header is a bearer request and is
handled as without the module (a bad header is `401`, never rescued by a cookie). A script on your
own page can still call your API through the user's browser while the page is open; the module keeps
tokens out of its hands, it does not make XSS harmless.

## Known limitations

- **A GraphQL socket keeps the scopes of its handshake.** Operations get the session's refreshed
  access token upstream, but the gateway's own `auth_scopes` check still uses the authorities read when
  the socket was opened; a scope the issuer adds or removes later applies to a new socket.
- **Push polling does not touch the session.** An open socket does not keep an idle session alive, so
  the idle timeout governs sockets: a page that only listens is signed out after `idle-timeout`
  unless it also makes calls.
- **Tokens at rest are unencrypted in the memory store**, and in Redis and JDBC when `session.encryption.disabled` is `true`.
- `REST_DOWNSTREAM` routes have no cookie to send and no end-to-end test of their own with cookie sign-in.
