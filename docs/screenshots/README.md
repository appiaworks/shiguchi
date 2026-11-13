# Regenerating the UI screenshots

`capture.mjs` takes `docs/images/graphiql.png` and `docs/images/swagger-ui.png` from
`samples/sample-jpa-backend`, so they only ever show the sample's catalogue (no real data, no tokens).
GraphiQL loads from esm.sh, so the capture needs internet.

1. `./gradlew publishToMavenLocal`, then in `samples/` run `sample-jpa-backend:bootRun` (port 8181; on a
   machine with a Gradle mirror add `-I` an init script that puts `mavenLocal()` first).
2. In a scratch directory: `npm i playwright && npx playwright install chromium`.
3. Copy `capture.mjs` next to that `node_modules` and run `OUT_DIR=<repo>/docs/images/ node capture.mjs` (`BASE_URL` for another port).

Every shot uses a 1280x800 light-theme viewport. Give each image alt text where it is referenced.
