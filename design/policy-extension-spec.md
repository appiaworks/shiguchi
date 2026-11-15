# Shiguchi enhancement spec: pluggable, runtime-editable method policy

> **Design history, superseded by what shipped.** This was the first draft (2026-11-15). The
> implementation differs in several places (no `PolicyResolver`/`PolicyStore`, no tag catalogue,
> exposure is an allowlist, tags live in `MethodRule`). For the real behaviour read
> [docs/guides/security.md](../docs/guides/security.md) (Policies, External vs internal
> deployment), [docs/reference/proto-options.md](../docs/reference/proto-options.md),
> [docs/guides/rate-limiting.md](../docs/guides/rate-limiting.md) and the revised plan in
> `.claude/plans/policy-tags/README.md`. Companion drafts `policy.proto` and `policy-tags.yaml`
> are reduced to stubs.

Status: draft proposal, based on a read-only trace of the Java source on 2026-11-15; line numbers are approximate.

## 1. Problem

Gateway operators need to change access policy while running: who may call which method, rate limits per principal, hiding methods from public surfaces. At the time of the draft:

- `GatewayAuthorizer` (`shiguchi-rest/.../authz/GatewayAuthorizer.java`) is one bean, replaces the default entirely, and receives only `(RouteDefinition, Authentication, arguments, body)`: no headers.
- The default `ScopeGatewayAuthorizer` checks `auth_scopes` (ALL-of) and ignores `(shiguchi.method).authz`; those rules take effect only with a custom authorizer.
- Rate limiting is a single global bucket bound at startup; the native gRPC proxy has a per-method limiter shared by all callers.
- `include-methods`/`exclude-methods` hide methods only at route registration, and not on the native gRPC passthrough.
- Native gRPC passthrough does no JWT validation, scopes or authorizer call.

## 2. Goals and non-goals

Goals: (a) one policy hook on every surface including passthrough; (b) hide methods from public surfaces by policy; (c) policy keyed by stable method facts so it survives renames.

Non-goals: a general rules language (policy data is code or deployment-supplied, not a core store); replacing OpenFGA for relationship checks; deciding policy content.

## 3. Proto: tags only

Policy values are not in the proto. The proto carries stable facts:

- Tags are opaque labels: `repeated string tags = 4` in `MethodRule` (no new extension number), plus `operation-tags` for `REST_DOWNSTREAM`.
- Read vs write comes from the standard `option idempotency_level` (NO_SIDE_EFFECTS = read, IDEMPOTENT = retry-safe write, unset = write, fail safe). Policies match on `RouteDefinition.idempotencyLevel`; there are no READ/WRITE defaults keys and no custom enum.
- No tag catalogue. The draft proposed one with a warning on unknown tags; warn-only fails open, so it was dropped. Exposure is an allowlist instead (section 4, change 6): an unknown or mistyped tag hides a method, it never exposes one. Descriptions and lint are the deployment's.

## 4. Shiguchi changes (as drafted; shipped form in brackets)

| # | Change | Notes |
|---|---|---|
| 1 | Policy SPI beside `GatewayAuthorizer` | [Shipped as ordered, additive `GatewayPolicy`; deny = `AccessDeniedException`, throttle = `ThrottledException(Duration)`; an error denies.] |
| 2 | Call the chain from the single enforcement point (`GatewayAuthorizationEnforcer`) covering REST, gRPC-Web, GraphQL; guard cache hit paths | [Shipped; GraphQL cache keyed per caller for `authz`/`auth_scopes` fields.] |
| 3 | Attributes on `RouteDefinition` | [Shipped as typed `tags` only; no attribute map, no pluggable extension registry.] |
| 4 | `PolicyStore` with hot-swappable snapshot | [Not in core. Only in `samples/sample-policy`.] |
| 5 | Per-key rate limiting | [Shipped as `shiguchi.rate-limit.rules[]`, a built-in policy reusing `RateLimiter`. Quotas out of scope.] |
| 6 | Method exposure | [Shipped as a static allowlist, `shiguchi.exposure.expose-tags` (global, per-service override); no SPI. When set, only methods carrying a listed tag exist. Hidden = 404 on REST, `UNIMPLEMENTED` on gRPC-Web and the proxy, absent from GraphQL, OpenAPI, reflection and the routes report. Hiding denies, it does not only omit from docs.] |
| 7 | Passthrough interceptor | [Shipped as opt-in `shiguchi.grpc.security.mode: ENFORCE`; default stays pass-through. Honors exposure in `lookupMethod` and `GrpcReflectionAggregator`.] |
| 8 | Principal enrichment (tenant) | [Rejected.] |

## 5. Caveats found in the trace

- A custom `GatewayAuthorizer` replaces the scope check entirely; policies are additive so the scope check keeps running.
- Authorizer results are bypassed by GraphQL response-cache hits and DataLoader batches.
- Properties are bound at startup only, so runtime-editable policy has to live in the policy bean (see the sample), not in Spring properties.

## 6. Compatibility

All changes are additive and off by default: no policy beans and no `expose-tags` means current behaviour.

## 7. Open questions

Answered in the plan's status section; see `.claude/plans/policy-tags/README.md`.
