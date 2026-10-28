#!/usr/bin/env bash
# Attaches the cursus fat jar to the GitHub Release of a tag, creating the release (with generated
# notes) when no workflow has yet. Plain curl, so it needs nothing on the runner but bash and curl.
# Usage: attach-cursus.sh vX.Y.Z   (GH_TOKEN and GITHUB_REPOSITORY in the environment;
# run after `./gradlew :shiguchi-cursus-cli:shadowJar`.)
set -euo pipefail

TAG="${1:?usage: attach-cursus.sh vX.Y.Z}"
API="https://api.github.com/repos/${GITHUB_REPOSITORY}"
AUTH=(-H "Authorization: Bearer ${GH_TOKEN}" -H "Accept: application/vnd.github+json")

JAR=$(ls shiguchi-cursus/shiguchi-cursus-cli/build/libs/cursus-*-all.jar | head -1)
NAME=$(basename "$JAR")

release_json() { curl -s "${AUTH[@]}" "$API/releases/tags/$TAG"; }

JSON=$(release_json)
if ! grep -q '"upload_url"' <<<"$JSON"; then
  # Another workflow may be creating it at this very moment: a 422 then means "already there".
  curl -s "${AUTH[@]}" -X POST "$API/releases" \
    -d "{\"tag_name\":\"$TAG\",\"name\":\"$TAG\",\"generate_release_notes\":true}" >/dev/null || true
  JSON=$(release_json)
fi
grep -q '"upload_url"' <<<"$JSON" || { echo "::error::no GitHub Release for $TAG"; exit 1; }

if grep -q "\"name\": *\"$NAME\"" <<<"$JSON"; then
  echo "$NAME is already attached to $TAG"
  exit 0
fi
RELEASE_ID=$(grep -m1 -oP '"id": *\K[0-9]+' <<<"$JSON")
curl -sf "${AUTH[@]}" -X POST -H "Content-Type: application/java-archive" \
  --data-binary @"$JAR" \
  "https://uploads.github.com/repos/${GITHUB_REPOSITORY}/releases/${RELEASE_ID}/assets?name=${NAME}" >/dev/null
echo "Attached $NAME to $TAG"
