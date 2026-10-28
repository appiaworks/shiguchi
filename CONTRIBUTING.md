# Contributing to Shiguchi

Thanks for your interest in contributing. This guide covers building, testing, and
submitting changes.

## Prerequisites

- **JDK 25** (Temurin recommended; CI builds on 25). The build compiles with `--release 21`, so the artifacts run on Java 21 and any use of a newer JDK API fails the build.
- **Node 22+** — only needed if you change the gRPC UI frontend (jsdom 30 needs it).
- No global Gradle install required — use the bundled wrapper (`./gradlew`).

## Project layout

It is a single multi-module Gradle build. Highlights:

- `shiguchi-core` — dynamic serde, descriptor cache, routing, reflection client.
- `protobuf-java-extras` — the proto JSON serde and type converters, plain Java (no Spring, no gRPC); core builds on it.
- `protoc-gen-java-extras` / `protoc-gen-java-extras-gradle-plugin` — protoc plugin adding Java-typed accessors to generated classes, and the Gradle plugin that wires it. Nothing in the gateway depends on them.
- `shiguchi-rest` / `-graphql` / `-openapi` / `-grpc-proxy` / `-validation` — surfaces.
- `shiguchi-ui` (+ `shiguchi-ui-frontend`) — embedded web console.
- `spring-boot3/*`, `spring-boot4/*` — auto-config + starters per Boot line.
- `samples/*` — runnable example apps.
- `shiguchi-benchmarks` — JMH performance suite.

Module names map to grouped directories in `settings.gradle.kts`.

## Build & test

```bash
./gradlew build              # compile + run all tests
./gradlew :module:test       # test one module, e.g. :shiguchi-core:test
./gradlew :shiguchi-benchmarks:jmh   # run benchmarks
```

Changing an extension point? `./gradlew build` also runs the API compatibility gate, which
compares every published module against the last release and fails on a change that would break
an application implementing one of the `@ApiStatus(STABLE)` types. Grow an SPI with a `default`
method rather than an abstract one; the reasoning and the escape hatches are in
[docs/api-compatibility.md](docs/api-compatibility.md).

A change on the request path (serde, dispatch, merge, routing) should be benchmarked:
run the JMH suite locally, or dispatch the *Benchmarks* workflow on the branch from the
Actions tab so it is compared against the stored baseline, and quote the numbers in the PR
(see `shiguchi-benchmarks/README.md`, section CI). The suite is not wired to any
automatic trigger — a run is 20-30 hosted-runner minutes.

Rebuild the UI frontend (bundled into `shiguchi-ui` static resources):

```bash
cd shiguchi-ui-frontend && npm ci && npm run build
```

Run a sample locally:

```bash
./gradlew :samples:sample-full:bootRun
```

## Coding conventions

- **Tests are required** for behavioral changes. Use JUnit 5 with **BDD-style** method
  naming (`GIVEN_…_WHEN_…_SHOULD_…`) and `@DisplayName` describing the scenario in plain
  English. Test behavior, not implementation details. Put non-trivial fixtures under
  `src/test/resources` rather than inlining large literals.
- **A new extension point** — an interface applications implement — should carry
  `@ApiStatus`. Start it `EXPERIMENTAL` unless the shape is already settled; promoting it later
  is free, while breaking a `STABLE` type is not.
- Match the style of the surrounding code (naming, comment density, formatting).
- Keep changes focused — one logical change per PR.

## Submitting a pull request

1. Fork and branch from `main` (e.g. `feat/…`, `fix/…`, `chore/…`).
2. Make your change with tests; run `./gradlew build` locally — it must pass.
3. Use [Conventional Commits](https://www.conventionalcommits.org/) for commit messages
   (`feat:`, `fix:`, `docs:`, `chore:`, …).
4. Open a PR against `main`. CI runs the full build (with test annotations on the Files tab and a
   JaCoCo coverage report as an artifact — coverage is measured, never enforced), Helm lint and a
   template smoke test, a sample smoke test that boots a sample against the libraries your branch
   builds and calls it over HTTP, and — only when you touched `shiguchi-ui-frontend/` — a
   `npm ci && npm run build` type-check of the console.
5. Note any security-relevant impact in the PR description (see [SECURITY.md](SECURITY.md)).

## Reporting bugs & security issues

- Functional bugs: open a GitHub issue with reproduction steps and versions.
- Security vulnerabilities: **do not** open a public issue — follow [SECURITY.md](SECURITY.md).

## License

By contributing you agree your contributions are licensed under the
[Apache License 2.0](LICENSE).
