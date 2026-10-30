# sample-bff-spa

A single-page app that signs in **without holding a token**: the gateway's BFF module runs the
OpenID Connect code flow, keeps the tokens in a server-side session, and the browser carries only
an `HttpOnly` session cookie. REST, gRPC-Web and the push WebSocket all authenticate with that
cookie, and the page is served by the gateway itself (same origin, the recommended layout).

Three parts, three terminals:

| Part | What | Port |
| --- | --- | --- |
| `issuer/` | Spring Authorization Server: one user (`alice` / `password`), one confidential client (authorization code + PKCE, refresh tokens that rotate), OIDC on, **2-minute access tokens** so a refresh is visible | 9000 |
| `gateway/` | The gateway with `bff` and `push` on (memory session store), an in-process gRPC backend `sample.bff.WhoAmI/Whoami` that answers with the caller it saw, a push namespace `user` with `PRINCIPAL_MATCH`, gRPC-Web on, and the built page as static resources | 8080 |
| `web/` | Plain TypeScript page (Vite, Node 22+, npm): sign-in buttons from `/bff/providers`, the `/bff/session` display, one REST call, one gRPC-Web call, a push tail with `@appiaworks/shiguchi-cursus-client` in cookie mode, a logout button | built into `gateway/src/main/resources/static` |

**Not run by CI.** The samples job builds a few samples only; this one needs a real browser to
prove anything (see the checklist). The automated proof of the same flow is the browser-shaped
journey test in the `shiguchi-bff` starters.

## Run

Everything uses `localhost`, plain http. The ports are fixed because the issuer's redirect URI and
the gateway's `applications.spa.origins` name `http://localhost:8080`.

```bash
# once, from the repo root, so the samples resolve this checkout's artifacts
./gradlew publishToMavenLocal

# 1. the page: writes into gateway/src/main/resources/static (gitignored)
cd samples/sample-bff-spa/web
npm install
npm run build        # tsc --noEmit, then vite build

# 2. the issuer (terminal A)
./gradlew -p samples sample-bff-issuer:bootRun

# 3. the gateway (terminal B); start it after the issuer
./gradlew -p samples sample-bff-gateway:bootRun
```

Open <http://localhost:8080/>. Gradle never runs npm: rebuild the page with `npm run build` and
restart (or reload; Boot serves the classpath copy, so a restart picks up the new files).

The sample builds against `-PgatewayVersion` (default `0.14.1`), so it needs the `shiguchi-bff` and push
starters in the artifacts you published locally.

## Expected flow

1. The page loads; `/bff/session` says `{"authenticated": false}` and a **Sign in with Sample
   issuer** button is shown (from `/bff/providers`).
2. The button navigates to `/bff/login/local?return_to=http://localhost:8080/`. The gateway stores
   the login state, and redirects to the issuer with a PKCE challenge and a `nonce`.
3. Sign in as `alice` / `password`. The issuer redirects to `/bff/callback/local`; the gateway
   exchanges the code, creates the session, sets the `gw-session` cookie and redirects back to
   the page.
4. `/bff/session` now shows `authenticated: true`, `subject: alice`. The page opens
   `/push/ws` with `auth: { mode: 'cookie' }` and subscribes to `user:alice`.
5. **REST** (`GET /v1/whoami`) and **gRPC-Web** (`POST /sample.bff.WhoAmI/Whoami`) both answer
   with `principal: alice` and the claims of the access token the gateway attached for the
   backend (`upstream-token-mode: RELAY`). The page never sees that token.
6. **Publish an event to myself** posts to `/push/publish` (cookie plus the page's `Origin`, which
   the gateway checks) and the event arrives on the socket.
7. Access tokens last 2 minutes. A call in the last minute of a token's life makes the gateway
   refresh it (once per session); call **Who am I** again and the `issuedAt`/`expires` times move.
8. **Log out** deletes the session and clears the cookie; the gateway closes the socket with
   close code **4440** `session ended`, and `/bff/session` is `false` again.

## Manual real-browser checklist

Use a fresh browser profile (or clear `localhost` cookies), with DevTools open on the Network tab.

- [ ] Open <http://localhost:8080/>. The session panel shows `authenticated: false`.
- [ ] Click **Sign in with Sample issuer**, sign in as `alice` / `password`. You land on the page
      again and the session panel shows `subject: alice`. In Application > Cookies, `gw-session`
      is `HttpOnly` and `SameSite=Lax`; no token is in local or session storage.
- [ ] **REST** shows `principal: alice` and token times. **gRPC-Web** shows the same.
- [ ] The push state is `open`. **Publish an event to myself** logs an `event user:alice ...` line.
- [ ] Wait more than 2 minutes (the first token has expired), click **Who am I** again: still
      `alice`, with a later `issuedAt` than before. That is the refresh. The socket stays open: it
      lives as long as the session, not the token.
- [ ] Click **Log out**. The push log shows the socket closing (DevTools > Network > WS: close code
      **4440**, `session ended`), the push state returns to `idle`, `/bff/session` is
      `{"authenticated": false}` and **Who am I** is refused (401).

## Notes

- **No Connect-ES.** [docs/grpc-web.md](../../docs/grpc-web.md) shows Connect-ES with generated
  stubs. This sample's schema is built in code (no protoc), so there is nothing to generate from;
  the page frames one empty request and decodes one reply by hand with `fetch`. With real stubs
  use `createGrpcWebTransport({ baseUrl: location.origin })` and the browser cookie is sent the
  same way.
- **The push client** comes from the repo source (`shiguchi-cursus-client-js`) through a Vite alias, as
  the gRPC console does, because `@appiaworks/shiguchi-cursus-client` is not on npmjs.com yet. Once it is published, drop the alias in `web/vite.config.ts` and
  depend on the npm package instead.
- **Why `AUTHENTICATED` and a customizer.** `security.mode: AUTHENTICATED` gates REST, gRPC-Web
  and push; the static page is opened by a small `GatewaySecurityCustomizer` in
  `SampleBffGatewayApplication`, and the sign-in endpoints by the `shiguchi-bff` module.
- **Cookie name and `secure: false`.** The default `__Host-gw-session` needs https; this sample
  runs on plain http, so `application.yml` sets a plain cookie name and `secure: false`. That is for
  local development only: behind https keep the defaults (`__Host-` name, `secure: true`).
- **Anti-CSRF header.** `shiguchi.bff.csrf-header.name: X-CSRF` is on, and `main.ts` sends
  `X-CSRF: 1` on logout, the gRPC-Web call and the publish: the recommended setup (the `Origin`
  rule stays the primary check; the header is the second layer). Logout must then be a `fetch`,
  not a plain form post.
- **Logout and the issuer.** `POST /bff/logout` answers with a `logoutUrl` for the issuer's own
  end-session endpoint; this page ignores it (the issuer would ask for confirmation), so the
  issuer's own login session stays: signing in again does not ask for a password.
- **Other ports.** Override `server.port`, `shiguchi.bff.applications.spa.origins`/`return-to`/
  `post-logout-redirect` on the gateway and the client's `redirect-uris` on the issuer together.
- **Restarting the issuer** invalidates every token (its signing key is generated at startup).
