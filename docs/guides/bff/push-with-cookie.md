# BFF: push with the cookie

Part of the [bff guide](index.md).

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
4440 and reports `session-ended`. Details: [push.md](../cursus/subscribing.md#cookie-connections).

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
