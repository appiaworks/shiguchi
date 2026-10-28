# API compatibility

Most of this gateway is configuration, and configuration is not an API you compile against. But a
handful of types exist to be implemented or called by an application — the
[extension points](configuration.md#extension-points-beans) — and those carry a promise.

## What the promise is

`dev.appiaworks.protobuf.extras.api.ApiStatus` marks it, and **anything unmarked is internal**. A type is public
only because Java has no finer visibility across packages; that is not an invitation to build on
it.

| Status | What you can rely on |
| --- | --- |
| `STABLE` | Within a major version: nothing removed, renamed, or narrowed. New methods arrive on interfaces only with a `default` implementation, so your implementations keep compiling. |
| `EXPERIMENTAL` | Usable, but still settling. The shape may change in a minor release when experience shows it is wrong, and the changelog will say so. Fine for an application; think twice before re-exposing it from a library of your own. |
| `DEPRECATED` | Still works, still tested, but a replacement exists (named in the `@deprecated` Javadoc) and it will be gone in a later major version. |
| `INTERNAL` | Nothing. It may change in a patch release. |

## Today's stable surface

| Type | Module | What you implement it for |
| --- | --- | --- |
| `GatewayAuthorizer` | `shiguchi-rest` | Authorization decision before dispatch |
| `GatewayErrorRenderer` | `shiguchi-rest` | The shape of an error response body |
| `GrpcStatusMapper` | `shiguchi-rest` | gRPC status to HTTP status mapping |
| `GrpcMetadataPopulator` | `shiguchi-rest` | What inbound context becomes outbound gRPC metadata |
| `RestBodyCodec` | `shiguchi-rest` | A non-JSON encoding callers may use on the REST surface |
| `UpstreamBodyCodec` | `shiguchi-rest` | A non-JSON encoding for talking to `REST_DOWNSTREAM` backends |
| `GrpcChannelBuilderCustomizer` | `shiguchi-core` | Anything about an outbound channel the YAML does not cover |
| `GrpcServiceAddressResolver` | `shiguchi-core` | Custom resolution of a `discovery-name` |
| `RequestValidationHook` | `shiguchi-core` | Request validation before dispatch |

Experimental today: `RateLimiter`, `RateLimitKeyResolver`, `JwtPrincipalConverter`,
`GatewaySecurityCustomizer`, `UpstreamCredentialSource` and `GatewayRequestAttributes`
(`shiguchi-rest`),
`SchemaReloadListener` (`shiguchi-core`), `ProtoJson`, `ProtoScalarCodec`, `ProtoTypes`,
`MoneyValue` and `ProtoJsonException` (`protobuf-java-extras`).

The configuration model (`dev.appiaworks.shiguchi.config`) is covered too, though you do not implement it.
Renaming a getter there silently breaks every consumer's YAML binding, which is worse than a
compile error because it surfaces at startup.

## How it is enforced

`./gradlew apiCompatibility` compares every published module against the last release. It also
runs as part of `./gradlew check`, so a PR that breaks the promise fails in CI.

Three checks, because no single tool covers all of it:

- **`checkStableApi`** — japicmp over the types marked `@ApiStatus`. Catches removals, renames,
  signature and visibility changes.
- **`checkConfigModel`** — japicmp over `dev.appiaworks.shiguchi.config`. Same rules, applied to the
  properties classes.
- **`checkStableAbstractMethods`** — the one japicmp gets backwards. It judges compatibility from
  a *caller's* point of view, where adding a method to an interface is harmless, and reports it as
  "added in source compatible way". For an SPI it is the opposite: a new abstract method breaks
  every application that implements it. japicmp has no setting to promote that to a failure, so
  the two jars are compared directly. Adding a `default` method stays allowed.

japicmp's two filters cannot be combined in one task — it ANDs `annotationIncludes` with
`packageIncludes`, so a task given both matches nothing and passes everything. Hence two tasks.

### Running it

```bash
./gradlew apiCompatibility                  # against the default baseline
./gradlew -PapiBaseline=<version> apiCompatibility
./gradlew -PapiBaseline=none apiCompatibility   # skip, deliberately
```

The baseline defaults to the last release and is fetched from Maven Central. A baseline that cannot be resolved fails the task rather than passing quietly.

A report lands in `<module>/build/reports/japicmp/` for each check.

### After a release

Bump the default baseline in the root `build.gradle.kts` (`apiBaseline`) to the version just
released, in the first commit **after** that release has published — never in the release commit
itself: the release build runs this gate, and a baseline that is not on Maven Central yet fails it.
(Currently `none`: nothing has been released yet. The first release becomes the baseline.)

> **Timing matters when you bump it.** The bump belongs in the first commit *after* the tag
> publishes, never in the release commit: `./gradlew build` runs this gate, the release workflow
> runs that build to produce the artefacts, and a baseline that cannot be resolved fails rather
> than passing quietly — so naming a version before it exists on Maven Central fails the build meant to
> create it.
>
> The break itself was the exception the rule below describes, not a licence to repeat it: it was
> agreed and documented before it landed, and the gate was verified to catch it first.

## Breaking something on purpose

It happens. When it does:

1. Say so in `CHANGELOG.md`, under the release, with what to change.
2. Prefer deprecating first: mark it `@ApiStatus(DEPRECATED)` with a `@deprecated` Javadoc naming
   the replacement, keep it working for a release, then remove it in the next major.
3. Only then remove it, and let the gate fail loudly in the PR that does — `-PapiBaseline=none` is
   for the release after a deliberate break, not for getting a red build green.

Suppressing the gate to land a change is the one thing that makes it worthless.
