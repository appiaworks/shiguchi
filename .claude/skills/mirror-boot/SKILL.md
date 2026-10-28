---
name: mirror-boot
description: Mirror a change made in a spring-boot3 module into its spring-boot4 counterpart (or vice versa) and verify parity. Use after editing any module under spring-boot3/ or spring-boot4/.
---

The Boot 3 (`spring-boot3/*`) and Boot 4 (`spring-boot4/*`) module lines must stay feature-identical.

Steps:

1. Identify which side was just changed (from the conversation or `git status`/`git diff`). Locate the counterpart module — the directory layout and file names mirror each other (`spring-boot3/shiguchi-autoconfigure` ↔ `spring-boot4/shiguchi-autoconfigure`, etc.).
2. Diff the changed files against their counterparts to see the current delta.
3. Apply the equivalent change to the counterpart. Do not copy files wholesale — the lines intentionally differ in Boot-version-specific imports, BOMs, and APIs; port the change, preserving those differences.
4. If a Boot API difference makes the mirror impossible or meaningfully different, stop and tell the user what diverges instead of forcing it.
5. Run tests for both counterpart modules. The Gradle projects are flat and named after the artifact, e.g.:
   - `./gradlew :shiguchi-spring-boot3-autoconfigure:test :shiguchi-spring-boot4-autoconfigure:test`
6. Report the parity status: what was mirrored, any intentional divergence, test results.
