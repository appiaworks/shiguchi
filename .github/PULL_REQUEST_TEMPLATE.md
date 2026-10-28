## What & why

<!-- One or two sentences. Link the issue if there is one. -->

## Checklist

- [ ] Conventional Commit title (`feat:`, `fix:`, `perf:`, `docs:` ...)
- [ ] Tests added/updated (`GIVEN_..._WHEN_..._SHOULD_...` + `@DisplayName`); serde changes keep `SerdeTierParityTest` green
- [ ] Boot 3 change mirrored in the Boot 4 module line (or divergence noted below)
- [ ] README / docs updated for feature or config changes
- [ ] `./gradlew build` passes locally
