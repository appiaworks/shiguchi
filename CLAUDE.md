# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The project is **Shiguchi**: Maven group `io.github.appiaworks`, `shiguchi-*`
artifacts, packages `dev.appiaworks.shiguchi.*`, property root `shiguchi.*`.

## Build & Test

- `./gradlew build` — compile + test everything. Single module: `./gradlew :shiguchi-core:test` (Boot modules live in nested folders but are flat Gradle projects named after the artifact: `spring-boot3/shiguchi-autoconfigure` is `:shiguchi-spring-boot3-autoconfigure:test`; `./gradlew projects` lists them all).
- After changing a module, run that module's tests before declaring done.
- Built and tested on JDK 25 (CI and locally); published as Java 21 bytecode (`options.release = 21`), so consumers still need only Java 21. The one exception is `protobuf-java-extras-classfile` (`release = 25`, Class-File API): found through `ServiceLoader`, loaded only on Java 25+, inert below, and the generated serde tiers switch off without it. The `fallback-jdk21` CI job runs the serde tests on a Java 21 launcher to keep the fallback paths honest. Gradle 9.8.0 (wrapper), versions in `gradle/libs.versions.toml`.
- Build cache is on (`gradle.properties`), local only; on CI `gradle/actions/setup-gradle` restores the Gradle User Home from the GitHub Actions cache (written by the nightly run on main). `org.gradle.parallel` is on: a full CI build measured 12m27s vs 16m09s serial on 4 CPUs (#239). Keep WebTestClient response timeouts generous (30 s) in end-to-end tests. With the 5 s default they time out when modules build concurrently.
- Samples need published artifacts: run `./gradlew publishToMavenLocal` before building/running `samples/*` on a fresh machine.
- API compatibility: `./gradlew build` runs the japicmp gate over every published module against
  the last release (`apiBaseline`). It only fails on `@ApiStatus(STABLE)` types and
  `dev.appiaworks.shiguchi.config`. The default is the last release; it is `"none"` (gate off) until the first
  `io.github.appiaworks` release, which becomes the baseline. Pass
  `-PapiBaseline=<version>` to compare against another. Extend an SPI with a `default` method — an added abstract
  method fails the gate by design. See `docs/api-compatibility.md`.
- Formatting: Spotless (palantir-java-format), ratcheted from `origin/main` — only files changed vs main are checked. `./gradlew spotlessApply` to fix; `spotlessCheck` runs as part of `build`.

## Boot 3 / Boot 4 duality

`spring-boot3/*` and `spring-boot4/*` are parallel module lines. Any change to one must be mirrored in the other's counterpart module unless a Boot API difference makes it impossible — then note the divergence in the PR.

## Branching & Releases

- Fixes land on `main` first, then cherry-pick to the active `release/*` branch (none yet: cut `release/0.14.x` from the first Shiguchi tag) for patch releases.
- **Bump `apiBaseline`'s default in the first commit AFTER a release publishes**, never in the
  release commit itself: the release workflow runs `./gradlew build`, that build runs the gate,
  and an unresolvable baseline fails — the new version is not on Maven Central until the publish it gates
  has succeeded. (Currently `"none"`: nothing is released under the Shiguchi coordinates yet; set it to the
  first `io.github.appiaworks` release once that publishes.)
- Release is manual: bump version in `build.gradle.kts` and `shiguchi-cursus-client-js/package.json` (+ `package-lock.json`, top-level and `packages[""]` `version`; npm cannot publish `-SNAPSHOT`, and the release step fails if the package version differs from the tag), commit `chore(release): x.y.z`, push tag `vx.y.z`. The tag's `ci.yml` run (GitHub-hosted) then publishes to Maven Central (signed, Central Portal), creates the GitHub Release (a draft, the cursus jar attached, then published: releases are immutable, so nothing can be added later), publishes the npm client, builds the sample-full OCI image, and pushes the Helm chart to GHCR.
- `@appiaworks/shiguchi-cursus-client` publishes to npmjs.com with `NPM_TOKEN` and `--provenance` (`.github/scripts/publish-cursus-client.sh`). If the tag run skipped it, dispatch `publish-npm.yml` with the tag (same script, checks out the tag). Secrets the release needs: `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`/`SIGNING_KEY_PASSWORD`, `NPM_TOKEN` (`.github/PUBLIC-RELEASE-TODO.md`).
- Never publish from a local machine (`publish`, `publishAndReleaseToMavenCentral`, `npm publish`): Central refuses a second deployment of a version, and only the tag run should create one.

## Conventions

- Conventional Commits (`feat:`, `fix:`, `chore:`...).
- Tests: BDD naming `GIVEN_..._WHEN_..._SHOULD_...` with `@DisplayName` (see CONTRIBUTING.md).
- Feature/config changes must update README.md and any affected docs in the same PR.

## Do not touch

- Generated proto/gRPC sources (build outputs of the protobuf plugin).
- Bundled UI assets in `shiguchi-ui` static resources — they are built from `shiguchi-ui-frontend` (React/Vite, Node 22+); change the frontend source and rebuild instead.
