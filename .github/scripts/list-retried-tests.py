#!/usr/bin/env python3
"""List the tests that failed and then passed on retry, so a green build doesn't hide a flake.

Reads the JUnit XML under the current directory. Gradle marks such a test with <flakyFailure> when
the root build sets mergeReruns. Prints a warning annotation per test and writes them all to the
job summary, because GitHub shows only the first ten annotations of a step.
"""
import os
import pathlib
import xml.etree.ElementTree as ET


def retried_tests(root):
    found = set()
    for report in pathlib.Path(root).glob("**/build/test-results/*/TEST-*.xml"):
        for case in ET.parse(report).getroot().iter("testcase"):
            if case.find("flakyFailure") is not None:
                module = report.parts[report.parts.index("build") - 1]
                found.add((module, case.get("classname"), case.get("name")))
    return sorted(found)


def main():
    tests = retried_tests(".")
    for module, classname, name in tests:
        print(f"::warning title=Test passed on retry::{module}: {classname}.{name}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if tests and summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(f"### Tests that passed on retry ({len(tests)})\n\n")
            out.writelines(f"- `{module}`: `{classname}.{name}`\n" for module, classname, name in tests)
    print(f"{len(tests)} test(s) passed on retry")


if __name__ == "__main__":
    main()
