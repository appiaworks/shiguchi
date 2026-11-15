# sample-policy

A gateway whose access rules live in a file you can edit while it runs. It shows the
`GatewayPolicy` SPI: the gateway describes each route with facts, and your code decides.

## Tags are facts, policy is code

A route carries **tags** (and an idempotency fact). Those say what the method *is*: `external`,
`internal`, `expensive`. They say nothing about who may call it. The decision is a `GatewayPolicy`
bean, ordinary code you own, which gets the route, the caller and the request headers and answers
with one of:

| Result | Response |
|---|---|
| empty `Mono` | allowed |
| `AccessDeniedException` | 403 |
| `ThrottledException(Duration)` | 429 with `Retry-After` |
| any other error | denied |

This sample is a REST_DOWNSTREAM consumer, so tags come from `operation-tags` in `application.yml`,
keyed by `operationId`. With proto services you set `tags` in the `(shiguchi.method)` option instead.

## The rules file

`policy-rules.yml`, path set by `sample.policy.rules-file`:

```yaml
rules:
  - { id: internal-needs-admin, type: require-authority, tag: internal, authority: SCOPE_admin }
  - { id: readonly-no-writes,   type: deny-unsafe,       authority: SCOPE_readonly }
  - { id: expensive-throttle,   type: throttle,          tag: expensive, max: 2, per-seconds: 60 }
```

- `require-authority`: routes with `tag` need `authority`.
- `deny-unsafe`: callers with `authority` may only call retryable (read) methods.
- `throttle`: at most `max` calls per `per-seconds` on routes with `tag`, then 429.

## Hot reload

`FileBackedPolicy` keeps the rules in an `AtomicReference`. Every `sample.policy.reload-interval`
(default 2s) it compares the file content and swaps in the new rules as one immutable list.

- First load fails: the application does not start.
- A later edit that does not parse (bad YAML, unknown rule type): logged, the previous rules stay.
- Throttle counters survive a reload.

Try it: run the app, call the delete endpoint as a non-admin (403), delete the `internal-needs-admin`
rule from the file, wait a couple of seconds, call again (204).

## Two instances from one build

```
./gradlew -p samples :sample-policy:bootRun                                         # internal
./gradlew -p samples :sample-policy:bootRun --args="--spring.profiles.active=external --server.port=8090 --sample.backend-port=8082"
```

The `external` profile sets `shiguchi.exposure.expose-tags: [external]`. Methods without that tag
do not exist on that instance: they answer 404 even for an admin. The policy still applies on top.

## Calling it

The app logs three demo tokens at startup (no scope, `readonly`, `admin`), signed with a public
HS256 secret. Do not copy that into a real gateway; set
`spring.security.oauth2.resourceserver.jwt.issuer-uri`.

```
curl -H "Authorization: Bearer $TOKEN" localhost:8080/inv/items/42
curl -X DELETE -H "Authorization: Bearer $TOKEN" localhost:8080/inv/items/42
```

Run the tests with `./gradlew publishToMavenLocal` at the repo root, then
`./gradlew -p samples :sample-policy:test`.
