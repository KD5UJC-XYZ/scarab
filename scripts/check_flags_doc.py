#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Checks the flags reference of `follow` against the usage text of `Main`.

The usage text is read statically (no JVM): the string literals of `Main.Usage` in `Main.scala` and of
`LogSettings.Usage`, which `Main` appends. The documented flags are those named in the first cell of a table row of
`docs/running/flags.md` and `docs/running/logging.md`. The check fails when

  * a flag of the usage text has no row in either table, or
  * a table row names a flag that the usage text lacks and that is not one of the removed flags `Main` refuses
    (`RemovedFlag`), which the reference lists on purpose.

Usage: python3 scripts/check_flags_doc.py [--root DIR]
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import argparse
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = "node/src/main/scala/xyz/kd5ujc/scarab/node/Main.scala"
LOG_SETTINGS = "node/src/main/scala/xyz/kd5ujc/scarab/node/log/LogSettings.scala"
DOC_PAGES = ("docs/running/flags.md", "docs/running/logging.md")
FLAG = re.compile(r"--[a-z0-9][a-z0-9-]*")
USAGE_START = re.compile(r"^\s*(?:private\s+)?val Usage(?::\s*String)?\s*=\s*$")
REMOVED = re.compile(r"extends RemovedFlag\(\s*\"([a-z0-9-]+)\"")


@dataclass(frozen=True)
class Result:
    undocumented: tuple[str, ...]
    unknown: tuple[str, ...]


def usage_literal(source: str) -> str:
    """The string literals of the `val Usage =` expression: from its line until the first blank line."""
    lines = source.split("\n")
    for number, line in enumerate(lines):
        if USAGE_START.match(line):
            body: list[str] = []
            for follow in lines[number + 1:]:
                if not follow.strip():
                    break
                body.append(follow)
            return "\n".join(body)
    raise ValueError("no `val Usage =` found")


def usage_flags(main: str, log_settings: str) -> frozenset[str]:
    return frozenset(FLAG.findall(usage_literal(main)) + FLAG.findall(usage_literal(log_settings)))


def removed_flags(main: str) -> frozenset[str]:
    return frozenset(f"--{name}" for name in REMOVED.findall(main))


def documented_flags(pages: list[str]) -> frozenset[str]:
    found: set[str] = set()
    for text in pages:
        for line in text.split("\n"):
            if line.startswith("|"):
                first = line.split("|")[1] if line.count("|") > 1 else ""
                found.update(FLAG.findall(first))
    return frozenset(found)


def compare(usage: frozenset[str], removed: frozenset[str], documented: frozenset[str]) -> Result:
    return Result(tuple(sorted(usage - documented)), tuple(sorted(documented - usage - removed)))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    read = lambda rel: (args.root / rel).read_text(encoding="utf-8")  # noqa: E731
    usage = usage_flags(read(MAIN), read(LOG_SETTINGS))
    result = compare(usage, removed_flags(read(MAIN)), documented_flags([read(p) for p in DOC_PAGES]))
    for flag in result.undocumented:
        print(f"in the usage text, not in the flags reference: {flag}")
    for flag in result.unknown:
        print(f"in the flags reference, not in the usage text: {flag}")
    if not result.undocumented and not result.unknown:
        print(f"flags reference matches the usage text ({len(usage)} flags)")
    return 1 if result.undocumented or result.unknown else 0


if __name__ == "__main__":
    sys.exit(main())
