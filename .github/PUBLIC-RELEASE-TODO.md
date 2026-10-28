# Before the first public release of appiaworks/shiguchi

CI, CodeQL and the Trivy SARIF upload are wired for a public repository on GitHub-hosted
runners. What is left needs an account or a secret only the owner can create.

## npm: `@appiaworks/shiguchi-cursus-client` on npmjs.com

- [x] The `@appiaworks` scope is the npmjs.com user account `appiaworks` (no organization).
- [x] Create a granular access token: read and write on `@appiaworks`, "bypass two-factor
      authentication" on. Note its expiry date (npm caps write tokens).
- [x] Store it: `gh secret set NPM_TOKEN --repo appiaworks/shiguchi`.

## Maven Central

- [x] Create a Sonatype Central Portal account and verify the `io.github.appiaworks` namespace by creating the
      temporary public repository `appiaworks/<verification-key>` (or via central-support@sonatype.com).
- [x] Generate a GPG signing key and publish the public key to a keyserver (keys.openpgp.org,
      keyserver.ubuntu.com).
- [x] Secrets: `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` (Portal user token),
      `SIGNING_KEY` (ASCII-armored private key) / `SIGNING_KEY_PASSWORD`.

The `publish-central` job fails a tag run while these secrets are missing.

## GitHub

- [ ] After the first tag pushes the image and Helm chart to GHCR, make both packages public.
- [ ] Install the Renovate GitHub app on the repository.
