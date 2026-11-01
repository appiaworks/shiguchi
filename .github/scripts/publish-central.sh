#!/usr/bin/env bash
# The Maven step of the release pipeline. Run from the root of the tree being released.
#
# Usage: publish-central.sh vX.Y.Z [--dry-run]
#
# Real run: signs and releases to Maven Central. Central refuses a second deployment of a version,
# so a version already there is skipped and a re-run cannot collide.
#
# Dry run: never contacts Central. The pipeline does not give this mode the Central credentials, so
# the build has no Central target to reach. It runs the POM gate and publishToMavenLocal. If a
# signing key is in the environment it also proves the key and passphrase work, by checking that a
# signature was written.
set -euo pipefail

TAG="${1:?usage: publish-central.sh vX.Y.Z [--dry-run]}"
DRY_RUN=false
[ "${2:-}" = "--dry-run" ] && DRY_RUN=true

# The tag must name the version the build produces: a mismatched tag would publish artifacts under
# a version nobody asked for.
case "$TAG" in
  v*) ;;
  *) echo "::error::tag must look like vX.Y.Z, got '$TAG'"; exit 1 ;;
esac
VERSION="${TAG#v}"
BUILD_VERSION=$(grep -oP 'version = "\K[^"]+' build.gradle.kts | head -1)
if [ "$BUILD_VERSION" != "$VERSION" ]; then
  echo "::error::build.gradle.kts says $BUILD_VERSION but the tag is $TAG"
  exit 1
fi

if [ "$DRY_RUN" = "true" ]; then
  echo "Dry run of $TAG: nothing is published"
  ./gradlew checkPublishedPoms publishToMavenLocal --no-daemon --no-configuration-cache

  ARTIFACT_DIR="$HOME/.m2/repository/io/github/appiaworks/shiguchi-core/$VERSION"
  if [ -n "${ORG_GRADLE_PROJECT_signingInMemoryKey:-}" ]; then
    if ! ls "$ARTIFACT_DIR"/*.asc >/dev/null 2>&1; then
      echo "::error::a signing key was supplied but $ARTIFACT_DIR holds no signatures"
      exit 1
    fi
    echo "Signed: $(ls "$ARTIFACT_DIR"/*.asc | wc -l) signatures in shiguchi-core"
  else
    echo "No signing key in this run: artifacts are unsigned"
  fi
  exit 0
fi

if [ -z "${ORG_GRADLE_PROJECT_mavenCentralUsername:-}" ]; then
  echo "::error::MAVEN_CENTRAL_* secrets are not configured; cannot publish to Maven Central."
  exit 1
fi

POM="https://repo1.maven.org/maven2/io/github/appiaworks/shiguchi-core/${VERSION}/shiguchi-core-${VERSION}.pom"
if [ "$(curl -s -o /dev/null -w '%{http_code}' "$POM")" = "200" ]; then
  echo "${VERSION} is already on Maven Central; nothing to publish"
  exit 0
fi
./gradlew publishAndReleaseToMavenCentral --no-daemon --no-configuration-cache
