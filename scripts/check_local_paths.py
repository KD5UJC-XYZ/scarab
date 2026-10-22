#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when a tracked file records an absolute home-directory path (`/home/<name>/`, `/Users/<name>/`).

Such a path publishes the capturing machine's account name and layout and is never reproducible.
Provenance is recorded relative to the repository root (see `repo_paths.py`). Placeholders such as
`/home/<user>/` do not match: a name must start with a letter, digit or underscore.
"""
from __future__ import annotations

from collections.abc import Iterable, Mapping
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
HOME_PATH = re.compile(rb"(?:/home|/Users)/[A-Za-z0-9_][A-Za-z0-9._-]*/")

# Tracked path -> why a home-directory path there is intentional. Keep this empty unless a file must
# quote one verbatim; an entry without a reason is a failure.
ALLOWED: Mapping[str, str] = {}


def findings(files: Iterable[tuple[str, bytes]], allowed: Mapping[str, str] = ALLOWED) -> list[str]:
    """`path:line: match` for each home path outside the allowlist, in input order."""
    rows = []
    for path, content in files:
        if allowed.get(path):
            continue
        for number, line in enumerate(content.split(b"\n"), start=1):
            match = HOME_PATH.search(line)
            if match:
                rows.append(f"{path}:{number}: {match.group().decode()}")
    return rows


def tracked(root: Path) -> Iterable[tuple[str, bytes]]:
    names = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], check=True, capture_output=True).stdout
    for name in filter(None, names.split(b"\0")):
        path = root / name.decode()
        if path.is_file() and not path.is_symlink():
            yield name.decode(), path.read_bytes()


def main() -> int:
    empty = [path for path, reason in ALLOWED.items() if not reason.strip()]
    rows = findings(tracked(ROOT))
    if empty or rows:
        print("absolute home paths in tracked files (record paths relative to the repository root):")
        print("\n".join(rows + [f"{path}: allowlist entry without a reason" for path in empty]))
        return 1
    print("no absolute home paths in tracked files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
