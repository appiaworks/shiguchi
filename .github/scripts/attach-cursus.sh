#!/usr/bin/env bash
# Creates the GitHub Release of a tag with the cursus fat jar attached. Releases are immutable on
# this repo: once published, no asset can be added. So the release is created as a draft (with
# generated notes, categories in .github/release.yml), the jar is uploaded, and only then is it
# published. A re-run picks up a draft left by an earlier attempt. A release that is already
# published without the jar only gets a warning: it can never take the asset, and failing here
# would skip the npm publish.
# Usage: attach-cursus.sh vX.Y.Z   (GH_TOKEN and GITHUB_REPOSITORY in the environment;
# run after `./gradlew :shiguchi-cursus-cli:shadowJar`. Needs curl and jq.)
set -euo pipefail

TAG="${1:?usage: attach-cursus.sh vX.Y.Z}"
API="https://api.github.com/repos/${GITHUB_REPOSITORY}"
AUTH=(-H "Authorization: Bearer ${GH_TOKEN}" -H "Accept: application/vnd.github+json")

JAR=$(ls shiguchi-cursus/shiguchi-cursus-cli/build/libs/cursus-*-all.jar | head -1)
NAME=$(basename "$JAR")

# /releases/tags/{tag} does not return drafts, so look the tag up in the release list.
JSON=$(curl -sf "${AUTH[@]}" "$API/releases?per_page=100" |
  jq -c --arg tag "$TAG" 'map(select(.tag_name == $tag)) | first // empty')

if [ -n "$JSON" ] && [ "$(jq -r .draft <<<"$JSON")" = "false" ]; then
  if jq -e --arg name "$NAME" '.assets | any(.name == $name)' <<<"$JSON" >/dev/null; then
    echo "$TAG is published with $NAME attached"
  else
    echo "::warning::$TAG is already published without $NAME; an immutable release cannot take it"
  fi
  exit 0
fi

if [ -z "$JSON" ]; then
  JSON=$(curl -sf "${AUTH[@]}" -X POST "$API/releases" \
    -d "$(jq -n --arg tag "$TAG" '{tag_name: $tag, name: $tag, draft: true, generate_release_notes: true}')")
  echo "Created draft release $TAG"
fi
RELEASE_ID=$(jq -r .id <<<"$JSON")

if ! jq -e --arg name "$NAME" '.assets | any(.name == $name)' <<<"$JSON" >/dev/null; then
  curl -sf "${AUTH[@]}" -X POST -H "Content-Type: application/java-archive" \
    --data-binary @"$JAR" \
    "https://uploads.github.com/repos/${GITHUB_REPOSITORY}/releases/${RELEASE_ID}/assets?name=${NAME}" >/dev/null
  echo "Attached $NAME to $TAG"
fi

curl -sf "${AUTH[@]}" -X PATCH "$API/releases/${RELEASE_ID}" -d '{"draft": false}' >/dev/null
echo "Published $TAG"
