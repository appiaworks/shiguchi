# Cursus: server-to-client push messaging

Experimental. The shape may change in a minor release; changes are called out in
the changelog.

Push lets a backend send events to connected clients without a request from them: a browser
watching an order, a dashboard, a worker waiting for jobs. Clients subscribe to **topics** over
Server-Sent Events; backends publish over HTTP. The gateway fans each event out to every
subscriber of its topic and, where the namespace asks for it, keeps a history so a client that
drops resumes without a gap.

What ships now: SSE and WebSocket subscriptions, HTTP publishing, the `PushService` gRPC service
(native gRPC, gRPC-Web, and typed JSON through the gateway's own transcoding), the in-memory
engine (one instance) and the Redis and Postgres engines (every instance sees every event,
history kept across restarts), `AT_MOST_ONCE` and `AT_LEAST_ONCE` delivery with resume, per-namespace authorization, a
close frame on shutdown, the `gateway-push` endpoint and the console's Push tab. The design in
[design/push.md](../../design/push.md) covers what follows: GraphQL subscriptions, the Postgres engine
and message search, persistent sessions and groups, the command line.

It is in-app messaging, not mobile push (APNs/FCM), and not a message broker.

## Turning it on

Add the push starter next to the gateway starter:

```kotlin
implementation("io.github.appiaworks:shiguchi-cursus-spring-boot4-starter")
```

```yaml
shiguchi:
  cursus:
    enabled: true
    namespaces:
      ticker: { auth: NONE, publish-auth: NONE }
      orders: { qos: AT_LEAST_ONCE, history-size: 500, history-ttl: 1h, auth: AUTHENTICATED }
      user:   { auth: PRINCIPAL_MATCH }
```

A topic is `namespace:name`: `orders:123`, `user:alice`, `tenant:acme/alerts`. The namespace must
be declared; it carries the policy. The name is free within it (letters, digits, `_ . / : @ -`).
