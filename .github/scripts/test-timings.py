#!/usr/bin/env python3
"""Write the slowest test classes and modules of a build to the job summary.

Reads the JUnit XML under the current directory (the same reports list-retried-tests.py reads).
A test task restored from the build cache brings back the times of the run that produced it, so
a module that was not re-run shows its earlier duration, not zero.
"""
import os
import pathlib
import xml.etree.ElementTree as ET

TOP_CLASSES = 20


def timings(root):
    classes = {}
    modules = {}
    for report in pathlib.Path(root).glob("**/build/test-results/*/TEST-*.xml"):
        suite = ET.parse(report).getroot()
        seconds = float(suite.get("time") or 0)
        module = report.parts[report.parts.index("build") - 1]
        classes[(module, suite.get("name"))] = seconds
        modules[module] = modules.get(module, 0.0) + seconds
    return classes, modules


def main():
    classes, modules = timings(".")
    slowest = sorted(classes.items(), key=lambda kv: -kv[1])[:TOP_CLASSES]
    by_module = sorted(modules.items(), key=lambda kv: -kv[1])
    total = sum(modules.values())
    lines = [f"### Test time by class (sum of test classes: {total:.0f} s, {len(classes)} classes)\n\n"]
    lines.append(f"Slowest {len(slowest)} classes:\n\n| s | module | class |\n| ---: | --- | --- |\n")
    lines += [f"| {s:.1f} | `{m}` | `{c}` |\n" for (m, c), s in slowest]
    lines.append("\nBy module:\n\n| s | module |\n| ---: | --- |\n")
    lines += [f"| {s:.1f} | `{m}` |\n" for m, s in by_module]
    text = "".join(lines)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(text)
    else:
        print(text)


if __name__ == "__main__":
    main()
