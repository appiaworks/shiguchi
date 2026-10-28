#!/usr/bin/env bash
# Publishes @appiaworks/shiguchi-cursus-client (shiguchi-cursus-client-js/) to npmjs.com for a
# release tag. Usage: publish-cursus-client.sh vX.Y.Z
# Needs Node on the PATH and NPM_TOKEN (a granular npmjs.com token with write access to
# @appiaworks) in the environment. Publishes with --provenance, so the job needs
# `permissions: id-token: write`.
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

if [ -z "${NPM_TOKEN:-}" ]; then
  echo "::error::NPM_TOKEN is not set; @appiaworks/shiguchi-cursus-client@${VERSION} was not published."
  exit 1
fi

# The token goes in a throwaway user config, never in the repo.
NPMRC="$(mktemp)"
trap 'rm -f "$NPMRC"' EXIT
echo "//registry.npmjs.org/:_authToken=${NPM_TOKEN}" > "$NPMRC"
export NPM_CONFIG_USERCONFIG="$NPMRC"

if npm view "@appiaworks/shiguchi-cursus-client@${VERSION}" version --registry "$REGISTRY" >/dev/null 2>&1; then
  echo "@appiaworks/shiguchi-cursus-client@${VERSION} is already on ${REGISTRY}; nothing to publish"
  exit 0
fi

npm ci
npm run build
npm publish --registry "$REGISTRY" --access public --provenance
echo "Published @appiaworks/shiguchi-cursus-client@${VERSION} to ${REGISTRY}"
