# Cursus: authorization

Part of the [cursus guide](index.md).

## Authorization

The push endpoints sit inside the gateway's security chain like every other surface: under
`shiguchi.security.mode: AUTHENTICATED` every call needs a JWT; under the default
`PERMIT_ALL` the chain lets anonymous callers through and authenticates a JWT when one is
presented. With `open-rest-bypasses-chain: true` the push paths stay inside the chain, since the
namespace checks below need it to have read the JWT. On top of the chain, each namespace decides
who may subscribe and who may publish:

| `auth` (subscribe) | Allows |
| --- | --- |
| `NONE` (default) | anyone |
| `AUTHENTICATED` | any authenticated principal |
| `ROLE` | a principal holding one of `roles` (`ops` matches `ops`, `ROLE_ops` or `SCOPE_ops`) |
| `PRINCIPAL_MATCH` | the principal whose name is the topic name, or its prefix before `/`: `alice` may read `user:alice` and `user:alice/inbox` |
| `CLAIM` | a JWT whose `claim` (default `push.subscribe`, a dotted path) lists a pattern matching the topic: `orders:*` (one segment), `orders:**`, `tenant:acme/*`, `{var}` like `*` |
| `AUTHORIZER` | your `GatewayAuthorizer` bean, called with `route == null` and `arguments` `push.operation`, `push.topic`, `push.namespace` |

The principal's name is the token's `sub` unless Spring Boot's
`spring.security.oauth2.resourceserver.jwt.principal-claim-name` says otherwise
([security.md](../security.md#principal-name)); that renames the caller everywhere. To match one
namespace's topics on another claim instead, set its `principal-claim` (a dotted path):

```yaml
shiguchi:
  cursus:
    namespaces:
      user: { auth: PRINCIPAL_MATCH, principal-claim: preferred_username }
```

The topic name is then compared with that claim's string value; a token without the claim, or
with a non-string value, is refused with `403`. Only the topic check moves: persistent session
keys and the `from` attribute keep the principal's name. A name a topic cannot carry (topic names
are `[A-Za-z0-9_./:@-]`, so not `auth0|abc`) never matches; pick a claim that fits.
`principal-claim` on a namespace whose `auth` is not `PRINCIPAL_MATCH` fails startup.

`publish-auth` has the same values but `PRINCIPAL_MATCH`, reads `publish-roles` and
`publish-claim` (default `push.publish`), and defaults to **`AUTHENTICATED`**: nobody publishes
anonymously unless you say `publish-auth: NONE`, which is logged as a warning at startup.

Refusals are `401` (a principal is needed and there is none) or `403`, with
`{"error": "...", "message": "..."}`.

Design topics so that the topic is the authorization boundary (`user:{sub}`,
`tenant:{id}/orders`): a check runs once per subscription, never per event.
