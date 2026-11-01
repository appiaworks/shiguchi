#!/usr/bin/env python3
"""Do what a release leaves behind, once its POM is on Maven Central.

Usage: post-release.py <vX.Y.Z> --source <ref> [--dry-run] [--timeout-minutes N]

Run it from a clean checkout of main. --source is where the release was built from: a new minor's
release branch starts there, and a patch's changelog section is read from there.

- A minor (x.y.0) creates release/x.y.x if it's absent, and opens the PR that bumps apiBaseline.
- A patch opens the PR that syncs its changelog section and version pins to main. apiBaseline stays
  on the last minor. A patch on an older line than main's pins syncs only the changelog.

Every step checks whether it's already done, so a failed run can simply run again.

--dry-run makes the edits to show them, puts the files back, and writes nothing else: no branch, no
push, no PR, no token needed. If the POM isn't there yet it says so and carries on, because a dry
run has nothing to wait for. A real run exits 1 at the timeout, before writing anything.
"""

import argparse
import importlib.util
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.dont_write_bytecode = True

# The file name has a hyphen, so a plain import can't reach it.
_spec = importlib.util.spec_from_file_location("prepare_release", Path(__file__).with_name("prepare-release.py"))
prepare = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(prepare)

ReleaseError = prepare.ReleaseError

POM_URL = "https://repo1.maven.org/maven2/io/github/appiaworks/shiguchi-core/{v}/shiguchi-core-{v}.pom"
POLL_SECONDS = 30

API_DOC = "docs/api-compatibility.md"
BASELINE = r'(providers\.gradleProperty\("apiBaseline"\)\.orElse\(")(\d+\.\d+\.\d+)(?=")'
BASELINE_DOC = r"(\(Currently `)(\d+\.\d+\.\d+)(?=`)"

# The pins a patch moves on main. Not the root version or the npm package: main's own version only
# moves in a release commit from main.
SYNCED_FIELDS = [field for field in prepare.VERSION_FIELDS if field[0] in (prepare.SAMPLES_PROPERTIES, prepare.MAVEN_SAMPLE)]

BOT_NAME = "github-actions[bot]"
BOT_EMAIL = "41898282+github-actions[bot]@users.noreply.github.com"


def run(*args):
    done = subprocess.run(args, capture_output=True, text=True, encoding="utf-8")
    if done.returncode != 0:
        raise ReleaseError(f"{' '.join(args)} failed: {done.stderr.strip()}")
    return done.stdout.strip()


def on_central(version):
    request = urllib.request.Request(POM_URL.format(v=version), method="HEAD")
    try:
        with urllib.request.urlopen(request, timeout=30):
            return True
    except urllib.error.HTTPError as error:
        if error.code != 404:
            print(f"Central answered {error.code}; trying again")
        return False
    except OSError as error:
        print(f"Central couldn't be reached ({error}); trying again")
        return False


def wait_for_central(version, timeout_minutes):
    deadline = time.monotonic() + timeout_minutes * 60
    while not on_central(version):
        if time.monotonic() >= deadline:
            raise ReleaseError(f"{version} isn't on Maven Central after {timeout_minutes} minutes; nothing was written. Run this again once it is.")
        print(f"{version} isn't on Maven Central yet")
        time.sleep(POLL_SECONDS)
    print(f"{version} is on Maven Central")


def replace_version(tree, path, pattern, new):
    prepare.current(tree, path, pattern)
    tree.write(path, re.sub(pattern, lambda m: m.group(1) + new, tree.read(path), count=1, flags=re.MULTILINE))


def plan_baseline(tree, version):
    baseline = prepare.current(tree, prepare.ROOT_BUILD, BASELINE)
    if prepare.parse_version(baseline) >= prepare.parse_version(version):
        print(f"apiBaseline is already {baseline}")
        return
    replace_version(tree, prepare.ROOT_BUILD, BASELINE, version)
    replace_version(tree, API_DOC, BASELINE_DOC, version)


def changelog_section(text, version):
    start = re.search(rf"^## \[{re.escape(version)}\][^\n]*\n", text, re.MULTILINE)
    if not start:
        raise ReleaseError(f"the release's {prepare.CHANGELOG} has no section for {version}")
    following = re.search(r"^## \[", text[start.end() :], re.MULTILINE)
    end = start.end() + following.start() if following else len(text)
    return text[start.start() : end].rstrip() + "\n\n"


def bullets_of(lines):
    """Yields (first, last + 1) for each "- " entry, continuation lines included."""
    index = 0
    while index < len(lines):
        if lines[index].startswith("- "):
            end = index + 1
            while end < len(lines) and lines[end].startswith((" ", "\t")):
                end += 1
            yield index, end
            index = end
        else:
            index += 1


def normalized(lines):
    return " ".join(" ".join(lines).split())


def without_shipped(unreleased, section):
    """A fix that was cherry-picked is in [Unreleased] on main and in the patch's section. It belongs
    in only one, so an entry with the same words leaves [Unreleased]. A reworded one stays, for the
    reviewer. Anything else in [Unreleased] is for the next minor and is left alone."""
    section_lines = section.split("\n")
    shipped = {normalized(section_lines[first:last]) for first, last in bullets_of(section_lines)}
    lines = unreleased.split("\n")
    drop = set()
    for first, last in bullets_of(lines):
        if normalized(lines[first:last]) in shipped:
            drop.update(range(first, last))
    kept = [line for index, line in enumerate(lines) if index not in drop]
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(kept))
    # A ### heading whose entries all left goes with them.
    return re.sub(r"^### [^\n]*\n\s*(?=^### |\Z)", "", text, flags=re.MULTILINE)


def merge_changelog(main_text, release_text, version):
    eol = "\r\n" if "\r\n" in main_text else "\n"
    main_text = main_text.replace("\r\n", "\n")
    if re.search(rf"^## \[{re.escape(version)}\]", main_text, re.MULTILINE):
        print(f"main's {prepare.CHANGELOG} already has {version}")
        return main_text.replace("\n", eol)
    section = changelog_section(release_text.replace("\r\n", "\n"), version)

    unreleased = re.search(r"^## \[Unreleased\]\n", main_text, re.MULTILINE)
    if not unreleased:
        raise ReleaseError(f"main's {prepare.CHANGELOG}: no [Unreleased] section")
    # Versions run newest first. On an older line, that isn't always straight after [Unreleased].
    wanted = prepare.parse_version(version)
    position = len(main_text)
    first_release = None
    for heading in re.finditer(r"^## \[(\d+\.\d+\.\d+)\]", main_text, re.MULTILINE):
        first_release = first_release if first_release is not None else heading.start()
        if prepare.parse_version(heading.group(1)) < wanted:
            position = heading.start()
            break
    if first_release is None:
        first_release = position

    pending = main_text[unreleased.end() : first_release]
    remaining = without_shipped(pending, section).strip()
    pending_text = f"\n{remaining}\n\n" if remaining else "\n"
    merged = (
        main_text[: unreleased.end()]
        + pending_text
        + main_text[first_release : position]
        + section
        + main_text[position:]
    )
    return merged.replace("\n", eol)


def plan_sync(tree, version, release_changelog):
    pin = prepare.current(tree, prepare.SAMPLES_PROPERTIES, prepare.VERSION_FIELDS[1][1])
    old_pin = None
    if prepare.parse_version(version) > prepare.parse_version(pin):
        for path, pattern in SYNCED_FIELDS:
            replace_version(tree, path, pattern, version)
        prepare.set_doc_pins(tree, pin, version)
        old_pin = pin
    else:
        print(f"main's pins are at {pin}, not older than {version}: syncing only the changelog")
    tree.write(prepare.CHANGELOG, merge_changelog(tree.read(prepare.CHANGELOG), release_changelog, version))
    return old_pin


def check_no_leftovers(old_pin):
    if not old_pin:
        return
    # Main's npm package keeps main's own version, which can equal the pin that just moved.
    left = [line for line in prepare.leftovers(old_pin) if not line.startswith((prepare.NPM_PACKAGE, prepare.NPM_LOCK))]
    if left:
        raise ReleaseError(f"{old_pin} is still in these lines; add a pattern or an exclusion:\n" + "\n".join(left))


def show_and_restore(changed):
    if changed:
        print(subprocess.run(["git", "--no-pager", "diff"], capture_output=True, text=True, encoding="utf-8").stdout)
    run("git", "checkout", "--", ".")


def ensure_release_branch(version, source_sha, dry_run):
    major, minor, _ = prepare.parse_version(version)
    branch = f"release/{major}.{minor}.x"
    if run("git", "ls-remote", "--heads", "origin", f"refs/heads/{branch}"):
        print(f"{branch} already exists")
    elif dry_run:
        print(f"would create {branch} at {source_sha}")
    else:
        repo = os.environ["GITHUB_REPOSITORY"]
        run("gh", "api", f"repos/{repo}/git/refs", "-f", f"ref=refs/heads/{branch}", "-f", f"sha={source_sha}")
        print(f"created {branch} at {source_sha}")


def open_follow_up(branch, title, body):
    """Each step on its own, because an earlier run may have stopped between any two of them."""
    repo = os.environ["GITHUB_REPOSITORY"]
    if run("git", "ls-remote", "--heads", "origin", f"refs/heads/{branch}"):
        print(f"{branch} already exists")
    else:
        run("git", "config", "user.name", BOT_NAME)
        run("git", "config", "user.email", BOT_EMAIL)
        run("git", "switch", "--create", branch)
        run("git", "commit", "--all", "--message", title)
        run("git", "push", "origin", branch)

    prs = run("gh", "pr", "list", "--repo", repo, "--head", branch, "--state", "open", "--json", "url", "--jq", ".[].url")
    if prs:
        print(f"the PR is already open: {prs}")
    else:
        try:
            print(run("gh", "pr", "create", "--repo", repo, "--base", "main", "--head", branch, "--title", title, "--body", body))
        except ReleaseError as error:
            raise ReleaseError(
                f"{error}\nIf Actions isn't permitted to create PRs, turn on \"Allow GitHub Actions to create and "
                "approve pull requests\" in the org settings and then the repo's, and re-run this job."
            ) from error

    # A PR opened with GITHUB_TOKEN starts no workflows, so the required checks need a dispatch.
    runs = run("gh", "run", "list", "--repo", repo, "--workflow", "ci.yml", "--branch", branch, "--json", "databaseId", "--jq", "length")
    if runs == "0":
        run("gh", "workflow", "run", "ci.yml", "--repo", repo, "--ref", branch)
        print(f"dispatched CI on {branch}")
    else:
        print(f"{branch} already has a CI run")


def post_release(tag, source, dry_run, timeout_minutes):
    if not tag.startswith("v"):
        raise ReleaseError(f"'{tag}' is not a release tag: expected vx.y.z")
    version = tag[1:]
    wanted = prepare.parse_version(version)
    patch = wanted[2] != 0

    if run("git", "status", "--porcelain"):
        raise ReleaseError("the checkout isn't clean, and this runs from a clean checkout of main")

    if dry_run:
        if on_central(version):
            print(f"{version} is on Maven Central")
        else:
            print(f"{version} isn't on Maven Central: a real run would wait for it")
    else:
        wait_for_central(version, timeout_minutes)

    run("git", "fetch", "--quiet", "--depth=1", "origin", source)
    source_sha = run("git", "rev-parse", "FETCH_HEAD")

    tree = prepare.Tree()
    old_pin = None
    if patch:
        release_changelog = run("git", "show", f"{source_sha}:{prepare.CHANGELOG}")
        old_pin = plan_sync(tree, version, release_changelog)
        branch = f"chore/sync-{version}"
        title = f"chore: sync {version} changelog and version pins to main" if old_pin else f"chore: sync {version} changelog to main"
        body = (
            f"Opened by the release pipeline after {version} reached Maven Central.\n\n"
            f"Moves {version}'s changelog section, and any sample and doc pins it moves forward, to main. An entry that was "
            f"reworded when it was cherry-picked stays in `[Unreleased]`, so check that section for "
            f"anything {version} already shipped."
        )
    else:
        ensure_release_branch(version, source_sha, dry_run)
        plan_baseline(tree, version)
        branch = f"chore/api-baseline-{version}"
        title = f"chore: bump apiBaseline to {version}"
        body = (
            f"Opened by the release pipeline after {version} reached Maven Central, "
            "so the japicmp gate can resolve the new baseline."
        )

    changed = tree.save()
    try:
        check_no_leftovers(old_pin)
    except ReleaseError:
        if dry_run:
            show_and_restore(changed)
        raise

    if not changed:
        print("main already has these edits: no PR needed")
    elif dry_run:
        print(f"would open a PR from {branch}: {title}")
        for path in changed:
            print(f"  {path}")
    if dry_run:
        show_and_restore(changed)
    elif changed:
        open_follow_up(branch, title, body)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("tag", help="the release, vx.y.z")
    parser.add_argument("--source", required=True, help="the ref or commit the release was built from")
    parser.add_argument("--dry-run", action="store_true", help="show the edits; write nothing")
    parser.add_argument("--timeout-minutes", type=int, default=60, help="how long to wait for Central")
    args = parser.parse_args()
    try:
        post_release(args.tag, args.source, args.dry_run, args.timeout_minutes)
    except ReleaseError as error:
        print(f"post-release: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
