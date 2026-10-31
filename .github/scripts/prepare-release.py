#!/usr/bin/env python3
"""Make the release commit's edits in the current directory: versions, doc pins, changelog.

Usage: prepare-release.py <x.y.z> [--date YYYY-MM-DD]

Never touches apiBaseline: that moves in the first commit after the release publishes. Reads and
writes bytes as they are, so a CRLF checkout stays CRLF. Nothing is written unless every edit can
be made. Running it again for a version that is already prepared changes nothing.

Exits 1 when the version is refused or an edit can't be made, and also when a run leaves the old
version behind somewhere. The last one runs after the edits are written, because it searches the
checkout. It means a new kind of pin: add a pattern for it to DOC_PINS.
"""

import argparse
import datetime
import re
import subprocess
import sys
from pathlib import Path

ROOT_BUILD = "build.gradle.kts"
SAMPLES_PROPERTIES = "samples/gradle.properties"
MAVEN_SAMPLE = "samples/sample-protoc-java-extras-maven/pom.xml"
NPM_PACKAGE = "shiguchi-cursus-client-js/package.json"
NPM_LOCK = "shiguchi-cursus-client-js/package-lock.json"
CHANGELOG = "CHANGELOG.md"

# The one-version-per-file pins, first match only. Group 1 is kept, group 2 is the version.
VERSION_FIELDS = [
    (ROOT_BUILD, r'^(\s*version = ")(\d+\.\d+\.\d+)(?=")'),
    (SAMPLES_PROPERTIES, r"^(gatewayVersion=)(\d+\.\d+\.\d+)"),
    (MAVEN_SAMPLE, r"(<shiguchi\.version>)(\d+\.\d+\.\d+)(?=</shiguchi\.version>)"),
    (NPM_PACKAGE, r'^(  "version": ")(\d+\.\d+\.\d+)(?=")'),
    # Top-level "version" and packages[""]: the only two at these indents before any dependency.
    (NPM_LOCK, r'^(  "version": ")(\d+\.\d+\.\d+)(?=")'),
    (NPM_LOCK, r'(^  "packages": \{\r?\n    "": \{\r?\n(?:      "name".*\r?\n)?      "version": ")'
     r"(\d+\.\d+\.\d+)(?=\")"),
]

# How the current release appears in the README and docs. Prose about an older release ("first
# ships in 0.14.0") has to stay, so this names each pin's shape rather than replacing the number.
DOC_PINS = [
    r"(io\.github\.appiaworks:[\w.-]+:){old}(?![\w.-])",
    r"(<version>){old}(?=</version>)",
    r"(shiguchi-[a-z-]+[-/]){old}(?=[-/])",
    r"(/sample-full:v){old}(?![\w.-])",
    r"(cursus-){old}(?=-all\.jar)",
]

# Files that keep the old version on purpose.
DOCS_WITHOUT_PINS = {"docs/migration.md", "docs/api-compatibility.md"}

# Paths and lines where an old version is history rather than a pin. build.gradle.kts holds
# apiBaseline; its version line is checked on its own by VERSION_FIELDS.
LEFTOVER_EXCLUDED = [
    ":!CHANGELOG.md",
    ":!CLAUDE.md",
    ":!build.gradle.kts",
    ":!docs/migration.md",
    ":!docs/api-compatibility.md",
    ":!.github/",
    ":!buildSrc/",
]
LEFTOVER_EXCLUDED_LINE = re.compile(r"since\s*=|first ships|ships from|not on v")


class ReleaseError(Exception):
    pass


def parse_version(text):
    match = re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", text)
    if not match:
        raise ReleaseError(f"'{text}' is not a release version: expected x.y.z, with no suffix")
    return tuple(int(part) for part in match.groups())


def git_lines(*args):
    done = subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8")
    # git grep exits 1 when nothing matches, which is what a clean run looks like.
    if done.returncode not in (0, 1):
        raise ReleaseError(f"git {args[0]} failed: {done.stderr.strip()}")
    return done.stdout.splitlines()


class Tree:
    """Edits held in memory, so a failure part way through leaves the checkout alone."""

    def __init__(self):
        self.files = {}

    def read(self, path):
        if path not in self.files:
            file = Path(path)
            if not file.is_file():
                raise ReleaseError(f"{path} is missing")
            self.files[path] = file.read_bytes().decode("utf-8")
        return self.files[path]

    def write(self, path, text):
        self.read(path)
        self.files[path] = text

    def save(self):
        changed = []
        for path, text in self.files.items():
            data = text.encode("utf-8")
            if Path(path).read_bytes() != data:
                Path(path).write_bytes(data)
                changed.append(path)
        return changed


def current(tree, path, pattern):
    match = re.search(pattern, tree.read(path), re.MULTILINE)
    if not match:
        raise ReleaseError(f"{path}: no version found matching {pattern}")
    return match.group(2)


def set_version_fields(tree, new):
    for path, pattern in VERSION_FIELDS:
        current(tree, path, pattern)
        text = re.sub(pattern, lambda m: m.group(1) + new, tree.read(path), count=1, flags=re.MULTILINE)
        tree.write(path, text)


def set_doc_pins(tree, old, new):
    docs = [p for p in git_lines("ls-files", "README.md", "docs/*.md") if p not in DOCS_WITHOUT_PINS]
    for path in docs:
        text = tree.read(path)
        for shape in DOC_PINS:
            text = re.sub(shape.format(old=re.escape(old)), lambda m: m.group(1) + new, text)
        tree.write(path, text)


def changelog_has(tree, new):
    return re.search(rf"^## \[{re.escape(new)}\]", tree.read(CHANGELOG), re.MULTILINE) is not None


def date_changelog(tree, new, date):
    text = tree.read(CHANGELOG)
    if changelog_has(tree, new):
        return
    match = re.search(r"^## \[Unreleased\](\r?\n)\s*", text, re.MULTILINE)
    if not match:
        raise ReleaseError(f"{CHANGELOG}: no [Unreleased] section")
    rest = text[match.end():]
    if rest.startswith("## [") or not rest.strip():
        raise ReleaseError(f"{CHANGELOG}: [Unreleased] is empty, so this release has no notes")
    eol = match.group(1)
    heading = f"## [Unreleased]{eol}{eol}## [{new}] - {date}{eol}{eol}"
    tree.write(CHANGELOG, text[: match.start()] + heading + rest)


def leftovers(old):
    # Not -w: "v" is a word character, so -w would miss a v0.15.0 image tag.
    number = rf"(^|[^0-9.]){re.escape(old)}($|[^0-9.])"
    lines = git_lines("grep", "-n", "-E", number, "--", ".", *LEFTOVER_EXCLUDED)
    return [line for line in lines if not LEFTOVER_EXCLUDED_LINE.search(line)]


def prepare(new, date):
    wanted = parse_version(new)
    tree = Tree()

    root = current(tree, ROOT_BUILD, VERSION_FIELDS[0][1])
    pin = current(tree, SAMPLES_PROPERTIES, VERSION_FIELDS[1][1])
    # After a patch's sync PR, main's pins are ahead of its root version, so compare with both.
    latest = max(root, pin, key=parse_version)
    if wanted == parse_version(latest) and root == pin and changelog_has(tree, new):
        print(f"{new} is already prepared: nothing to do")
        return
    if wanted <= parse_version(latest):
        raise ReleaseError(f"{new} is not newer than {latest} (root version {root}, sample pin {pin})")

    set_version_fields(tree, new)
    set_doc_pins(tree, pin, new)
    date_changelog(tree, new, date)
    for path in tree.save():
        print(f"updated {path}")

    left = leftovers(pin)
    if left:
        raise ReleaseError(f"{pin} is still in these lines; add a pattern or an exclusion:\n" + "\n".join(left))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("version", help="the version to release, x.y.z")
    parser.add_argument("--date", help="changelog date, YYYY-MM-DD (default: today, UTC)")
    args = parser.parse_args()
    date = args.date or datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    try:
        prepare(args.version, date)
    except ReleaseError as error:
        print(f"prepare-release: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
