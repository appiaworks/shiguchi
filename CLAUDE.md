# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The project is **Shiguchi**: Maven group `io.github.appiaworks`, `shiguchi-*`
artifacts, packages `dev.appiaworks.shiguchi.*`, property root `shiguchi.*`.

## Build & Test

- `./gradlew build` — compile + test everything. Single module: `./gradlew :shiguchi-core:test` (Boot modules live in nested folders but are flat Gradle projects named after the artifact: `spring-boot4/shiguchi-autoconfigure` is `:shiguchi-spring-boot4-autoconfigure:test`; `./gradlew projects` lists them all).
- After changing a module, run that module's tests before declaring done.
- Built and tested on JDK 25 (CI and locally); published as Java 21 bytecode (`options.release = 21`), so consumers still need only Java 21. The one exception is `protobuf-java-extras-classfile` (`release = 25`, Class-File API): found through `ServiceLoader`, loaded only on Java 25+, inert below, and the generated serde tiers switch off without it. The `fallback-jdk21` CI job runs the serde tests on a Java 21 launcher to keep the fallback paths honest. Gradle 9.8.0 (wrapper), versions in `gradle/libs.versions.toml`.
- Build cache is on (`gradle.properties`), local only; on CI `gradle/actions/setup-gradle` restores the Gradle User Home from the GitHub Actions cache (written by the nightly run on main). `org.gradle.parallel` is on: a full CI build measured 12m27s vs 16m09s serial on 4 CPUs (#239). Keep WebTestClient response timeouts generous (30 s) in end-to-end tests. With the 5 s default they time out when modules build concurrently.
- Test retry: when `CI` is set, a failed test gets one retry, unless its test task has 5 or more failures. A test that passes on retry leaves the job green and appears in the job summary as "Tests that passed on retry" (`.github/scripts/list-retried-tests.py`). Set `CI=true` to reproduce this locally.
- Samples need published artifacts: run `./gradlew publishToMavenLocal` before building/running `samples/*` on a fresh machine. They build against `gatewayVersion` in `samples/gradle.properties`; override it with `-PgatewayVersion=<version>`. The Maven sample has its own pin (`shiguchi.version` in its `pom.xml`).
- API compatibility: `./gradlew build` runs the japicmp gate over every published module against
  the last release (`apiBaseline`). It fails on any `@ApiStatus` type (EXPERIMENTAL included) and
  `dev.appiaworks.shiguchi.config`; unmarked types are not checked. A deliberate, CHANGELOG-announced
  break lands by listing the classes in `acceptedBreaks` (root build) under the current baseline,
  which lapses when `apiBaseline` moves. The default is `apiBaseline` in the root `build.gradle.kts`. Pass
  `-PapiBaseline=<version>` to compare against another. Extend an SPI with a `default` method — an added abstract
  method fails the gate by design. See `docs/operations/api-compatibility.md`.
- Published POMs: `./gradlew checkPublishedPoms` (part of `check`) fails on what Maven Central would reject: missing metadata, or a version-less dependency with no imported BOM or managed version. It doesn't open the BOM, so a BOM that doesn't manage the dependency still passes. The rules live in `buildSrc`; test them with `./gradlew -p buildSrc test`, which `./gradlew build` does not run.
- Formatting: Spotless (palantir-java-format), ratcheted from `origin/main` — only files changed vs main are checked. `./gradlew spotlessApply` to fix; `spotlessCheck` runs as part of `build`.

## Spring Boot line

Main ships Spring Boot 4 only (`spring-boot4/*`). 0.18.x was the last release line with the Boot 3 starters. Fixes for it go to `release/0.18.x` (best effort, security only, until May 2027) and are cherry-picked by hand, since main has no Boot 3 modules. All samples are Boot 4 consumers; `sample-full` is the source of the OCI image.

## Branching & Releases

- A ruleset protects `main` and `release/**`: changes go through a squash-merged PR, and `Build & Test` and `Serde fallback on Java 21` must pass (no approval needed, and the branch needn't be up to date). Nobody can push to these branches directly. Admins can force a merge in an emergency with `gh pr merge --admin`. Creating or deleting a `release/x.y.x` branch is still allowed. If you rename either job in `ci.yml`, update the ruleset too, or every PR waits for a check that never reports.
- Fixes land on `main` first, then cherry-pick to the active `release/*` branch (the highest one) for patch releases.
- **`apiBaseline`'s default moves in the first commit AFTER a minor publishes**, never in the
  release commit itself: the release workflow runs `./gradlew build`, that build runs the gate,
  and an unresolvable baseline fails — the new version is not on Maven Central until the publish it gates
  has succeeded. The pipeline's `post-release` job opens that PR.
- Release: dispatch the `Prepare release` workflow from `main` with the version and its base (`main`, or `release/x.y.x` for a patch; that branch needs `samples/gradle.properties`). It opens the `chore(release): x.y.z` PR with the versions, doc pins and changelog date from `.github/scripts/prepare-release.py`. Merge the PR, then push tag `vx.y.z` on the merge commit. This workflow and the `post-release` job open their PRs with the release bot GitHub App's token (variable `RELEASE_BOT_CLIENT_ID`, secret `RELEASE_BOT_PRIVATE_KEY`), so those PRs run CI normally. Without the secret they fall back to `GITHUB_TOKEN` with a warning, which needs "Allow GitHub Actions to create and approve pull requests" on, and someone has to approve the PR's CI runs by hand. If it can't run, run the script locally from the release branch and commit the result; npm cannot publish `-SNAPSHOT`, and the release step fails if the package version differs from the tag. The tag's `ci.yml` run (GitHub-hosted) builds, then calls `release-pipeline.yml` at `@main`, which publishes to Maven Central (signed, Central Portal) and creates the GitHub Release (a draft, the cursus jar attached, then published: releases are immutable, so nothing can be added later). The sample-full OCI image and the Helm chart go to GHCR after that, so a Central rejection pushes nothing. The image builds natively per platform (`ubuntu-latest`, `ubuntu-24.04-arm`), pushed by digest and joined under the tag, from a boot jar built against the libraries the Central job hands over; if they don't arrive, that job publishes them itself. `ci.yml` then publishes the npm client, even if helm or the image failed.
- After Central: the pipeline's `post-release` job (`.github/scripts/post-release.py`) waits up to 60 minutes for the POM on repo1, then opens the follow-up. A minor gets `release/x.y.x` and the `apiBaseline` PR; a patch gets the PR that syncs its changelog and pins to main. Merge that PR. If the job failed or timed out, re-run it: it creates no second branch or PR. Deleting old release branches stays manual. Its permissions must be on the caller too (`release`, `pipeline-check`, the rehearsal's `rehearse`, each live release branch's `release`), or every PR on that branch fails at startup.
- Tag runs skip the tests the release PR already ran: `.github/scripts/verify-tag-tested.sh` looks for a merged PR whose head has the tagged commit's git tree and a successful `Build and test` step, then the build is `assemble apiCompatibility checkPublishedPoms publishToMavenLocal`. Anything else (the PR was behind its base, a docs-only PR, an API error) runs the full build, and the script prints its reason in the job log. `ci.yml` comes from the tag's tree, so a release branch cut before this keeps the full build.
- Release pipeline: tag runs take the logic from main and the tree from the tag, so the pipeline may only use a Gradle task or file that every live release branch has. GitHub resolves `release-pipeline.yml@main` at the start of every `ci.yml` run, PRs included, even though the job only runs on tags. If the file on main is missing or invalid, every PR fails at startup with no jobs and no `Build & Test`, including the PR that fixes it, which then needs an admin-bypass merge (`gh pr merge --admin`, not exercised yet). The `pipeline-check` job blocks the way in: it makes a PR's own copy of the file get validated at startup. It catches structural errors only; `Release rehearsal` runs the pipeline as a dry run on PRs that touch it, and on dispatch with the real signing key. The `release` job can't pass an input main's file doesn't define yet, so a new input takes two PRs: add it as optional (and to `pipeline-check`), then pass it. Release branches call the pipeline too and main's CI never checks them, so don't require, rename or remove an input while a live release branch uses it.
- `@appiaworks/shiguchi-cursus-client` publishes to npmjs.com by trusted publishing (OIDC; `ci.yml` and `publish-npm.yml` are its trusted publishers on npmjs.com, no token) with provenance (`.github/scripts/publish-cursus-client.sh`). If the tag run skipped it, dispatch `publish-npm.yml` with the tag (same script, checks out the tag). Secrets the release needs: `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`/`SIGNING_KEY_PASSWORD`, `RELEASE_BOT_PRIVATE_KEY` (`.github/PUBLIC-RELEASE-TODO.md`).
- Never publish from a local machine (`publish`, `publishAndReleaseToMavenCentral`, `npm publish`): Central refuses a second deployment of a version, and only the tag run should create one.

## Conventions

- Conventional Commits (`feat:`, `fix:`, `chore:`...).
- Tests: BDD naming `GIVEN_..._WHEN_..._SHOULD_...` with `@DisplayName` (see CONTRIBUTING.md).
- Feature/config changes must update README.md and any affected docs in the same PR.

## Do not touch

- Generated proto/gRPC sources (build outputs of the protobuf plugin).
- Bundled UI assets in `shiguchi-ui` static resources — they are built from `shiguchi-ui-frontend` (React/Vite, Node 22+); change the frontend source and rebuild instead.
