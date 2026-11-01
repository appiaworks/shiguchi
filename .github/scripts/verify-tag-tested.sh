#!/usr/bin/env bash
# Decides whether a tag's build can skip the tests because the release PR already ran them on the
# same code. Writes tested=true or tested=false to $GITHUB_OUTPUT and prints the reason.
#
# Usage: verify-tag-tested.sh <commit-sha>
#
# tested=true needs all of these:
#   - the commit is the merge commit of a PR merged into main or release/*
#   - its git tree equals the tree of the PR's head, so the PR run built what the tag holds. A PR
#     that was behind its base when it merged has a different tree and gets the full build.
#   - the "Build and test" step succeeded in the job "Build & Test" of a pull_request CI run on that
#     head. The step, because on a docs-only PR the job is green with the step skipped.
#
# Anything else, an API error included, means tested=false: doubt costs a full build. This script
# never fails the job, so the reason it prints is the only sign that a token lost a permission.
set -uo pipefail

SHA="${1:-}"
REPO="${GITHUB_REPOSITORY:-appiaworks/shiguchi}"
BUILD_JOB="Build & Test"
BUILD_STEP="Build and test"

decide() {
  echo "tested=$1: $2"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "tested=$1" >> "$GITHUB_OUTPUT"
  fi
  exit 0
}

[ -n "$SHA" ] || decide false "no commit given"

PR=$(gh api "repos/$REPO/commits/$SHA/pulls" --jq \
  "[.[] | select(.merge_commit_sha == \"$SHA\" and .merged_at != null)
        | select(.base.ref == \"main\" or (.base.ref | startswith(\"release/\")))][0]
   | select(. != null) | \"\(.number) \(.head.sha)\"") \
  || decide false "could not look up the PR of $SHA"
[ -n "$PR" ] || decide false "$SHA is not the merge commit of a PR into main or release/*"
NUMBER="${PR%% *}"
HEAD="${PR##* }"

TAG_TREE=$(gh api "repos/$REPO/git/commits/$SHA" --jq .tree.sha) \
  || decide false "could not read the tree of $SHA"
HEAD_TREE=$(gh api "repos/$REPO/git/commits/$HEAD" --jq .tree.sha) \
  || decide false "could not read the tree of #$NUMBER's head $HEAD"
[ -n "$TAG_TREE" ] && [ "$TAG_TREE" = "$HEAD_TREE" ] \
  || decide false "#$NUMBER was behind its base when it merged: tree $TAG_TREE, head tree $HEAD_TREE"

RUNS=$(gh api "repos/$REPO/actions/runs?head_sha=$HEAD&event=pull_request&per_page=100" --jq \
  '.workflow_runs[] | select(.path == ".github/workflows/ci.yml") | .id') \
  || decide false "could not list the CI runs of #$NUMBER"
[ -n "$RUNS" ] || decide false "#$NUMBER has no CI run on its head $HEAD"

for RUN in $RUNS; do
  # filter=latest: a failed first attempt doesn't count against a re-run that passed.
  JOB_OK=$(gh api "repos/$REPO/actions/runs/$RUN/jobs?filter=latest&per_page=100" --jq \
    "[.jobs[] | select(.name == \"$BUILD_JOB\" and .conclusion == \"success\")
              | .steps[] | select(.name == \"$BUILD_STEP\" and .conclusion == \"success\")] | length") \
    || decide false "could not read the jobs of run $RUN"
  if [ "${JOB_OK:-0}" -gt 0 ]; then
    decide true "#$NUMBER's run $RUN built and tested tree $TAG_TREE"
  fi
done

decide false "no CI run of #$NUMBER has a successful '$BUILD_STEP' step on its head"
