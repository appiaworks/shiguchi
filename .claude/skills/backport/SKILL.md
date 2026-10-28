---
name: backport
description: Cherry-pick a commit from main onto the active release branch and optionally prepare a patch release. Use when the user asks to backport a fix or cut a patch release.
disable-model-invocation: true
---

Backport a fix from `main` to the active release branch (the newest `release/*` branch; none until the first patch release).

Input: `$ARGUMENTS` — one or more commit SHAs (or a PR number to resolve to its merge commit). If empty, ask which commit(s) to backport.

Steps:

1. `git fetch origin` and confirm the commit(s) exist on `main`.
2. Check out the release branch (`git switch release/X.Y.x`) and ensure it is up to date with origin.
3. Cherry-pick each commit in order with `git cherry-pick -x <sha>` (the `-x` keeps a back-reference to the original commit). On conflict, resolve it, explain the resolution to the user, and continue.
4. Run tests for the modules touched by the cherry-picked changes. If they fail, report and stop — do not push.
5. Push the release branch only after tests pass and the user confirms.
6. If the user wants a patch release: bump the patch version in `build.gradle.kts`, commit `chore(release): x.y.z`, and remind them CI handles Maven Central/npmjs/GHCR/Helm publishing once the `vx.y.z` tag is pushed. Ask before pushing the tag — tagging triggers publication and is not reversible.
