# @appiaworks/shiguchi-cursus-client

> **Experimental.** The API may change before 1.0.

A small TypeScript client for the gateway's push WebSocket protocol (`push.v1.json`, see
[docs/push.md](../docs/push.md#websocket)). It does what every consumer of the protocol otherwise
rewrites:

- reconnects with capped exponential backoff and jitter;
- resubscribes every live subscription with `after` set to the last id it delivered;
- sends the JWT in `connect`, refreshes it before its `exp`, and reconnects with a fresh one on close 4401;
- reports `overflow` and `reset` frames as gaps;
- sends `ack` and `pub` and resolves them on the gateway's answer;
- sends an `origin` for echo suppression.

No runtime dependencies. ESM, with type declarations. Node 22 or newer, or any browser.

## Token example

```ts
import { createPushClient } from '@appiaworks/shiguchi-cursus-client'

const client = createPushClient({
  url: 'wss://gw.example.com/push/ws',
  auth: { mode: 'token', token: () => getAccessToken() }, // called on every (re)connect and refresh
  origin: 'web-' + crypto.randomUUID(), // optional: this client's own publishes are not echoed back
})

client.on('state', (state) => console.log('push:', state)) // connecting | open | waiting | closed
client.on('error', (e) => console.warn(e.code, e.message))
client.on('frame', (f) => console.debug('push <-', f)) // optional: every frame the gateway sends, raw

const sub = client.subscribe(['orders:123'], {
  after: lastSeenId, // optional resume point: one id, or { 'orders:123': id }
  onEvent: (e) => render(e.topic, e.id, e.data),
  onGap: (g) => refetch(g.topic), // g.reason: queue_full | session_overflow | history_gap | no_history
  onError: (e) => console.warn(e.code, e.topic),
})

await client.publish('orders:123', { status: 'PACKED' }) // needs client-publish on the namespace
await sub.ack(eventId) // persistent sessions and consumer groups only

sub.close()
client.close()
```

## Cookie example

```ts
const client = createPushClient({
  url: 'wss://gw.example.com/push/ws',
  auth: { mode: 'cookie' }, // sends no connect frame; the browser's cookies are the handshake credential
})
client.subscribe('user:alice', { onEvent: console.log })
```

Cookie mode means "whatever the handshake authenticates": anonymous, or a request the gateway's
security chain accepts, which includes the session cookie of a browser sign-in
([docs/design/bff.md](../docs/design/bff.md)). A socket opened with that cookie lives as long as the
BFF session, not the access token, and the gateway closes it with **4440** `session ended` within
`shiguchi.cursus.session-check-interval` (30 s by default) of a logout, a refused token refresh or
the session's expiry. In cookie mode close 4401 stops the client and reports `signed-out` through
`onError`, `client.on('error')` and `client.on('state')`; the client never calls a token function. Call
`client.connect()` again once the user has signed in.

Close 4440 is terminal in every mode: the client stops with state `closed`, reports `session-ended`
the same way, does not reconnect and does not call a token function. Sign in again, then call
`client.connect()`.

## Node

Node 22 has a global `WebSocket`, and the client uses it by default. To send headers or use the
`ws` package, pass the implementation:

```ts
import WebSocket from 'ws'
import { createPushClient } from '@appiaworks/shiguchi-cursus-client'

const client = createPushClient({
  url: 'wss://gw.example.com/push/ws',
  auth: { mode: 'token', token: async () => fetchToken() },
  webSocket: WebSocket as never,
})
```

## Behaviour

| Situation | What the client does |
| --- | --- |
| Socket drops (any other code) | Waits `min * factor^n` ms (capped at `max`, reduced by `jitter`), reconnects, resubscribes with `after`. The attempt count resets after the connection stays open `stableAfterMs` (10 s). Not a gap. |
| Close 1012 (rebalance, lifetime) | Reconnects at once, no backoff. A second 1012 before the connection proved stable falls back to backoff. |
| Close 4401, token mode | Reconnects at once with a fresh token. |
| Close 4401, cookie or none | Stops; `signed-out`. |
| Close 4440 (session ended) | Stops; `session-ended`. No reconnect, no token call. |
| Close 1008 (origin or policy) | Stops; every subscription gets `onError` with code `policy`. |
| `error` frame for a subscribed topic | That topic leaves the subscription for good (`onError`), it is not resent after a reconnect. |
| `error` frame for a `pub` | The `publish()` promise rejects with the gateway's code. |
| `overflow` with `queue_full` | `onGap`, then the topic is resubscribed from the last id. |
| `overflow` with `session_overflow`, `reset` | `onGap`; the gateway keeps the subscription and replays what it retained. |

A `reset` or `overflow` never moves the resume point: only an event's own id does. The client sends a
`ping` frame every `keepaliveMs` the gateway announced (default 25 s; `pingIntervalMs`, 0 to turn off).

Options: `url`, `auth`, `origin`, `backoff { min, max, factor, jitter }`, `webSocket`, `pingIntervalMs`,
`requestTimeoutMs`, `stableAfterMs`, `refreshSkewMs`. See `src/types.ts`.

Notes and limits:

- Use one subscription per topic per client. A second subscription on a topic the connection
  already has gets live events only; the gateway does not replay for it.
- `ack()` and `publish()` need an open connection; offline publishes are rejected, not queued.
  The gateway's `acked` and `published` frames carry no request id, so answers are matched by
  topic (and id for `acked`) in order, and an unanswered request rejects with `timeout`.
- Events with no id (gateway signals) are delivered like any event but never become a resume point.

## Not provided

SSE fallback transport, gRPC-Web transport, React hooks, an offline publish queue. For SSE use
`EventSource` (`Last-Event-ID` is built in).

## Install

```bash
npm i @appiaworks/shiguchi-cursus-client
```

Published to npmjs.com with each gateway release; the package version is the
gateway's.

## Development

```bash
npm ci
npm run lint
npm test
npm run build
```

`test/fixtures/frames.json` holds example frames of both directions. The tests here use it, and
`PushClientProtocolFixtureTest` in `shiguchi-cursus` drives the real `PushWebSocketHandler` and
checks that the server frames keep the fixture's field names, so the two cannot drift silently.
