#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when `project/Dependencies.scala` and the licence records disagree.

Every library declared there must be named in `NOTICE`; a runtime library must also be in the inventory
`docs/licenses.md`. Where a record names a version next to the artifact, that version must be the declared
one, so a bumped dependency cannot leave the licence text behind. The check reads text only: it does not
resolve the classpath, so transitive dependencies remain under the regeneration steps in `docs/licenses.md`.

The licence texts the assembly carries (`licenses/index.json`, `licence_texts.py`) are checked here too: every
text has its recorded digest, and the index names exactly the artifacts of the runtime table in
`docs/licenses.md`. The resolved closure itself is checked against the index when a JAR is assembled.
"""
from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
import re
import sys

import licence_texts

ROOT = Path(__file__).resolve().parents[1]
DEPENDENCIES = "project/Dependencies.scala"
NOTICE = "NOTICE"
INVENTORY = "docs/licenses.md"

VERSION_ENTRY = re.compile(r'^\s+val (\w+) = "([^"]+)"\s*(?://.*)?$')
LIBRARY_ENTRY = re.compile(r'^\s+val (\w+) = "([^"]+)" (%%|%) "([^"]+)" % (V\.\w+|"[^"]+")(?: % (Test))?\s*(?://.*)?$')
VERSION_TOKEN = re.compile(r"(?<![\w.-])\d+(?:\.\d+)+(?:-[A-Za-z0-9][\w.-]*)?(?![\w.-])")
RUNTIME_ROW = re.compile(r"^\| ([\w.-]+:[\w.-]+) \| ([\w.-]+) \|")


@dataclass(frozen=True)
class Dependency:
    name: str
    artifact: str
    version: str
    test_only: bool


def parse_dependencies(source: str) -> list[Dependency]:
    """Libraries declared in `Dependencies.scala`, with their versions resolved through the `V` object."""
    versions: dict[str, str] = {}
    entries = []
    for line in source.splitlines():
        if match := VERSION_ENTRY.match(line):
            versions[match.group(1)] = match.group(2)
        elif match := LIBRARY_ENTRY.match(line):
            entries.append(match)
    dependencies = []
    for match in entries:
        reference = match.group(5)
        version = versions.get(reference[2:]) if reference.startswith("V.") else reference.strip('"')
        if version is None:
            continue
        dependencies.append(Dependency(match.group(1), match.group(4), version, match.group(6) == "Test"))
    return dependencies


def mentions(artifact: str, text: str) -> list[str]:
    """Lines of a record that name the artifact, with or without its Scala 3 suffix."""
    pattern = re.compile(rf"(?<![\w.-]){re.escape(artifact)}(?:_3)?(?![\w-])")
    return [line for line in text.splitlines() if pattern.search(line)]


def findings(dependencies: Iterable[Dependency], notice: str, inventory: str) -> list[str]:
    """One message per missing record or version mismatch, in declaration order."""
    rows = []
    for dependency in dependencies:
        records = [("NOTICE", notice)]
        if not dependency.test_only:
            records.append(("docs/licenses.md", inventory))
        for label, text in records:
            lines = mentions(dependency.artifact, text)
            if not lines:
                rows.append(f"{dependency.artifact}: not named in {label} (declared {dependency.version})")
                continue
            for line in lines:
                named = VERSION_TOKEN.findall(line)
                if named and dependency.version not in named:
                    rows.append(
                        f"{dependency.artifact}: {label} names {', '.join(named)}, "
                        f"Dependencies.scala declares {dependency.version}"
                    )
                    break
    return rows


def runtime_table(inventory: str) -> set[str]:
    """`group:artifact:version` of every row of the inventory's "Runtime classpath" table."""
    section = inventory.split("## Runtime classpath", 1)[-1].split("\n## ", 1)[0]
    return {f"{m.group(1)}:{m.group(2)}" for line in section.splitlines() if (m := RUNTIME_ROW.match(line))}


def licence_findings(inventory: str, index: dict, texts: list[licence_texts.Finding]) -> list[str]:
    """The licence-text index against the runtime table, plus the repository copies' own findings."""
    table, listed = runtime_table(inventory), set(index["components"])
    rows = [f"{c}: in the runtime table of {INVENTORY} but has no licence texts in {licence_texts.INDEX}"
            for c in sorted(table - listed)]
    rows += [f"{c}: has licence texts in {licence_texts.INDEX} but is not in the runtime table of {INVENTORY}"
             for c in sorted(listed - table)]
    return rows + [row.render() for row in texts]


def main() -> int:
    source = (ROOT / DEPENDENCIES).read_text(encoding="utf-8")
    dependencies = parse_dependencies(source)
    if not dependencies:
        print(f"no library declarations parsed from {DEPENDENCIES}")
        return 1
    inventory = (ROOT / INVENTORY).read_text(encoding="utf-8")
    rows = findings(dependencies, (ROOT / NOTICE).read_text(encoding="utf-8"), inventory)
    index = licence_texts.load(ROOT)
    rows += licence_findings(inventory, index, licence_texts.verify_repository(ROOT, index))
    if rows:
        print("licence records disagree with project/Dependencies.scala or the runtime inventory:")
        print("\n".join(rows))
        return 1
    print(f"NOTICE and {INVENTORY} cover all {len(dependencies)} declared libraries; "
          f"{licence_texts.INDEX} has the texts of all {len(index['components'])} runtime artifacts")
    return 0


if __name__ == "__main__":
    sys.exit(main())
