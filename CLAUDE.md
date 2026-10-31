# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The project is **Shiguchi**: Maven group `io.github.appiaworks`, `shiguchi-*`
artifacts, packages `dev.appiaworks.shiguchi.*`, property root `shiguchi.*`.

## Build & Test

- `./gradlew build` — compile + test everything. Single module: `./gradlew :shiguchi-core:test` (Boot modules live in nested folders but are flat Gradle projects named after the artifact: `spring-boot3/shiguchi-autoconfigure` is `:shiguchi-spring-boot3-autoconfigure:test`; `./gradlew projects` lists them all).
- After changing a module, run that module's tests before declaring done.
- Built and tested on JDK 25 (CI and locally); published as Java 21 bytecode (`options.release = 21`), so consumers still need only Java 21. The one exception is `protobuf-java-extras-classfile` (`release = 25`, Class-File API): found through `ServiceLoader`, loaded only on Java 25+, inert below, and the generated serde tiers switch off without it. The `fallback-jdk21` CI job runs the serde tests on a Java 21 launcher to keep the fallback paths honest. Gradle 9.8.0 (wrapper), versions in `gradle/libs.versions.toml`.
- Build cache is on (`gradle.properties`), local only; on CI `gradle/actions/setup-gradle` restores the Gradle User Home from the GitHub Actions cache (written by the nightly run on main). `org.gradle.parallel` is on: a full CI build measured 12m27s vs 16m09s serial on 4 CPUs (#239). Keep WebTestClient response timeouts generous (30 s) in end-to-end tests. With the 5 s default they time out when modules build concurrently.
- Test retry: when `CI` is set, a failed test gets one retry, unless its test task has 5 or more failures. A test that passes on retry leaves the job green and appears in the job summary as "Tests that passed on retry" (`.github/scripts/list-retried-tests.py`). Set `CI=true` to reproduce this locally.
- Samples need published artifacts: run `./gradlew publishToMavenLocal` before building/running `samples/*` on a fresh machine. They build against `gatewayVersion` in `samples/gradle.properties`; override it with `-PgatewayVersion=<version>`. The Maven sample has its own pin (`shiguchi.version` in its `pom.xml`).
- API compatibility: `./gradlew build` runs the japicmp gate over every published module against
  the last release (`apiBaseline`). It only fails on `@ApiStatus(STABLE)` types and
  `dev.appiaworks.shiguchi.config`. The default is the last release (`0.15.0`). The first
  `io.github.appiaworks` release is `0.14.0`. Pass
  `-PapiBaseline=<version>` to compare against another. Extend an SPI with a `default` method — an added abstract
  method fails the gate by design. See `docs/api-compatibility.md`.
- Published POMs: `./gradlew checkPublishedPoms` (part of `check`) fails on what Maven Central would reject: missing metadata, or a version-less dependency with no imported BOM or managed version. It doesn't open the BOM, so a BOM that doesn't manage the dependency still passes. The rules live in `buildSrc`; test them with `./gradlew -p buildSrc test`, which `./gradlew build` does not run.
- Formatting: Spotless (palantir-java-format), ratcheted from `origin/main` — only files changed vs main are checked. `./gradlew spotlessApply` to fix; `spotlessCheck` runs as part of `build`.

## Boot 3 / Boot 4 duality

`spring-boot3/*` and `spring-boot4/*` are parallel module lines. Any change to one must be mirrored in the other's counterpart module unless a Boot API difference makes it impossible — then note the divergence in the PR.

## Branching & Releases

- Fixes land on `main` first, then cherry-pick to the active `release/*` branch (`release/0.15.x`) for patch releases.
- **Bump `apiBaseline`'s default in the first commit AFTER a release publishes**, never in the
  release commit itself: the release workflow runs `./gradlew build`, that build runs the gate,
  and an unresolvable baseline fails — the new version is not on Maven Central until the publish it gates
  has succeeded. (Currently `0.15.0`.)
- Release: dispatch the `Prepare release` workflow from `main` with the version and its base (`main`, or `release/x.y.x` for a patch; that branch needs `samples/gradle.properties`, which `release/0.15.x` doesn't have yet). It opens the `chore(release): x.y.z` PR with the versions, doc pins and changelog date from `.github/scripts/prepare-release.py`, and starts CI on it. Merge the PR, then push tag `vx.y.z` on the merge commit. The workflow needs "Allow GitHub Actions to create and approve pull requests" on, in the org and then the repo. If it can't run, run the script locally from the release branch and commit the result; npm cannot publish `-SNAPSHOT`, and the release step fails if the package version differs from the tag. The tag's `ci.yml` run (GitHub-hosted) builds, then calls `release-pipeline.yml` at `@main`, which publishes to Maven Central (signed, Central Portal) and creates the GitHub Release (a draft, the cursus jar attached, then published: releases are immutable, so nothing can be added later). The sample-full OCI image and the Helm chart go to GHCR after that, so a Central rejection pushes nothing. `ci.yml` then publishes the npm client, even if helm or the image failed.
- Release pipeline: tag runs take the logic from main and the tree from the tag, so the pipeline may only use a Gradle task or file that every live release branch has. GitHub resolves `release-pipeline.yml@main` at the start of every `ci.yml` run, PRs included, even though the job only runs on tags. If the file on main is missing or invalid, every PR fails at startup with no jobs and no `Build & Test`, including the PR that fixes it, which then needs an admin-bypass merge (`gh pr merge --admin`, not exercised yet). The `pipeline-check` job blocks the way in: it makes a PR's own copy of the file get validated at startup. It catches structural errors only; `Release rehearsal` runs the pipeline as a dry run on PRs that touch it, and on dispatch with the real signing key. The `release` job can't pass an input main's file doesn't define yet, so a new input takes two PRs: add it as optional (and to `pipeline-check`), then pass it. Release branches call the pipeline too and main's CI never checks them, so don't require, rename or remove an input while a live release branch uses it.
- `@appiaworks/shiguchi-cursus-client` publishes to npmjs.com by trusted publishing (OIDC; `ci.yml` and `publish-npm.yml` are its trusted publishers on npmjs.com, no token) with provenance (`.github/scripts/publish-cursus-client.sh`). If the tag run skipped it, dispatch `publish-npm.yml` with the tag (same script, checks out the tag). Secrets the release needs: `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`/`SIGNING_KEY_PASSWORD` (`.github/PUBLIC-RELEASE-TODO.md`).
- Never publish from a local machine (`publish`, `publishAndReleaseToMavenCentral`, `npm publish`): Central refuses a second deployment of a version, and only the tag run should create one.

## Conventions

- Conventional Commits (`feat:`, `fix:`, `chore:`...).
- Tests: BDD naming `GIVEN_..._WHEN_..._SHOULD_...` with `@DisplayName` (see CONTRIBUTING.md).
- Feature/config changes must update README.md and any affected docs in the same PR.

## Do not touch

- Generated proto/gRPC sources (build outputs of the protobuf plugin).
- Bundled UI assets in `shiguchi-ui` static resources — they are built from `shiguchi-ui-frontend` (React/Vite, Node 22+); change the frontend source and rebuild instead.
