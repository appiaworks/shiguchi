#!/usr/bin/env bash
# Publishes @appiaworks/shiguchi-cursus-client (shiguchi-cursus-client-js/) to npmjs.com for a
# release tag. Usage: publish-cursus-client.sh vX.Y.Z
# Authenticates by npm trusted publishing (OIDC), not a token: the package on npmjs.com names
# ci.yml and publish-npm.yml as its trusted publishers, and the job needs
# `permissions: id-token: write`. Needs npm 11.5.1+ (upgraded here when the runner's is older).
# It also:
#  - fails if the package version is not the tag's version;
#  - skips when that version is already on the registry, so re-running a release is harmless.
set -euo pipefail

TAG="${1:?usage: publish-cursus-client.sh vX.Y.Z}"
VERSION="${TAG#v}"
REGISTRY="https://registry.npmjs.org/"
cd "$(dirname "$0")/../../shiguchi-cursus-client-js"

PKG_VERSION="$(node -p "require('./package.json').version")"
if [ "$PKG_VERSION" != "$VERSION" ]; then
  echo "::error::shiguchi-cursus-client-js/package.json says $PKG_VERSION but the tag is $TAG"
  exit 1
fi

if [ -z "${ACTIONS_ID_TOKEN_REQUEST_URL:-}" ]; then
  echo "::error::no OIDC token: the job needs 'permissions: id-token: write'; @appiaworks/shiguchi-cursus-client@${VERSION} was not published."
  exit 1
fi

NPM_MIN=11.5.1
NPM_HAVE="$(npm --version)"
if [ "$(printf '%s\n' "$NPM_MIN" "$NPM_HAVE" | sort -V | head -1)" != "$NPM_MIN" ]; then
  echo "npm $NPM_HAVE predates trusted publishing; installing npm 11"
  npm install -g npm@11
fi

if npm view "@appiaworks/shiguchi-cursus-client@${VERSION}" version --registry "$REGISTRY" >/dev/null 2>&1; then
  echo "@appiaworks/shiguchi-cursus-client@${VERSION} is already on ${REGISTRY}; nothing to publish"
  exit 0
fi

npm ci
npm run build
npm publish --registry "$REGISTRY" --access public --provenance
echo "Published @appiaworks/shiguchi-cursus-client@${VERSION} to ${REGISTRY}"
