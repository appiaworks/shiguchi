# BFF: deployment and migrating a SPA

Part of the [bff guide](index.md).

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
- **Clocks.** Keep every instance NTP-synchronised, see the note under [Refresh](endpoints.md#refresh).
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
  is kept (see [Refresh](endpoints.md#refresh)).

## Migrating a SPA from bearer tokens

For an application that holds access tokens in JavaScript today. The backend side needs no change:
keep the resource-server configuration (`spring.security.oauth2.resourceserver.jwt.*`) so bearer callers
(scripts, other services) work as before. The sample page,
[`samples/sample-bff-spa/web/src/main.ts`](../../../samples/sample-bff-spa/web/src/main.ts), shows each step in
about 200 lines.

1. **Stop storing tokens.** Remove the OIDC client library and its `localStorage`/`sessionStorage` use. The
   page asks `GET /bff/session` ([`refreshSession`](../../../samples/sample-bff-spa/web/src/main.ts)) whether it
   is signed in; the endpoint never returns 401, so it can be polled.
2. **Drop the `Authorization` header** from every call. A request with one is handled as a bearer request
   and the cookie is not consulted, so a stale header turns a signed-in user into a `401`.
3. **Send the cookie.** Same origin needs nothing. For a sibling origin use `credentials: 'include'`
   everywhere (`fetch`, the gRPC-Web transport) and configure `shiguchi.cors` as described under
   [Layout](index.md#layout-same-origin-or-same-site-siblings).
4. **Sign in by navigation.** List the providers with `GET /bff/providers` and send the browser to
   `/bff/login/{provider}?return_to=<your page>`
   ([`loadProviders`](../../../samples/sample-bff-spa/web/src/main.ts)). On a `401` from any call, do the same.
5. **Read the outcome.** After a failed sign-in the browser returns to `return_to` with `?bff_error=<code>`
   (`sign_in_failed`, `access_denied`, `login_required`...). Show your sign-in button and strip the
   parameter.
6. **Handle `503`.** A `503` with `Retry-After` means the issuer could not be reached to refresh the token;
   the session is kept, so retry after the delay instead of signing the user out.
7. **Log out with a `POST`** to `/bff/logout` ([`logout`](../../../samples/sample-bff-spa/web/src/main.ts)), then
   navigate to the returned `logoutUrl` if you want the issuer's session ended too.
8. **Add the CSRF header if `csrf-header` is on.** Every unsafe-method call (REST writes, gRPC-Web, GraphQL,
   logout) must carry it; the sample does ([`CSRF`](../../../samples/sample-bff-spa/web/src/main.ts)).
9. **Push.** Use `auth: { mode: 'cookie' }` and no token
   ([`startPush`](../../../samples/sample-bff-spa/web/src/main.ts)). Close code **4440** means the session ended:
   sign in again, do not reconnect.
10. **Streams.** A gRPC-Web stream that ends with `grpc-status 16` and a REST or GraphQL stream that ends
    with `session ended` mean the same: sign in again (see
    [REST, gRPC-Web and GraphQL streams](push-with-cookie.md#rest-grpc-web-and-graphql-streams)).

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

**Connect-ES** (gRPC-Web, with stubs generated as in [grpc-web.md](../grpc-web.md#server-streaming-from-connect-es)):

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

**Push client** (`@appiaworks/shiguchi-cursus-client`, as in [`startPush`](../../../samples/sample-bff-spa/web/src/main.ts)):

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
