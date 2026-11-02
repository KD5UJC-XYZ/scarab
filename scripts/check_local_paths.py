#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when a tracked file records an absolute home-directory path (`/home/<name>/`, `/Users/<name>/`),
or cites a directory on the author's machine (`/tmp/...`, `.workspace/...`) as the place where evidence is kept.

A home path publishes the capturing machine's account name and layout and is never reproducible.
Provenance is recorded relative to the repository root (see `repo_paths.py`). Placeholders such as
`/home/<user>/` do not match: a name must start with a letter, digit or underscore.

A reader cannot open a directory on someone else's machine, and `/tmp` is emptied at boot, so a
document records what a reader needs (results, hashes, commands, pinned versions) and says that the raw
run artifacts are not published (docs/process.md); it does not name where they sit locally. The rule
flags any `.workspace/<path>` and any named run under the data directory (`/tmp/scarab/<run>`,
`/tmp/scarab-<run>`) in Markdown prose (they exist only on the author's machine; the bare default
`/tmp/scarab` and settings such as `/tmp/sbt-global` may be named), Markdown prose where "retained", "remain(s)", "kept", "preserved", "archived" or "outside Git" and a `/tmp/` path share
a sentence, in either order, and JSON fields named `private…` or containing "archive" or "retain" that
hold a local path anywhere in their value. Commands (fenced code blocks, and inline code
that contains a space) and other JSON fields (a container mount, the log a run wrote) record how
something was produced, not where it is kept, and are not flagged.
"""
from __future__ import annotations

from collections.abc import Iterable, Mapping
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
HOME_PATH = re.compile(rb"(?:/home|/Users)/[A-Za-z0-9_][A-Za-z0-9._-]*/")
LOCAL_PATH = r"(?:/tmp/|\.workspace/)[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+*-]+)*"
RETENTION = r"\b(?:retained|remains?|kept|preserved|archived|outside git)\b"
# Within one sentence (a full stop ends it only when followed by whitespace, so `file.json` does not, and
# the sentence may wrap onto following lines): a retention phrase then a local path, or a local path then a
# retention phrase.
SENTENCE_GAP = r"(?:[^.]|\.(?!\s)){0,200}?"
TMP_PATH = r"/tmp/[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+*-]+)*"
WORKSPACE_PATH = re.compile(r"\.workspace/[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+*-]+)*"
                            r"|/tmp/scarab(?:/|-)[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+*-]+)*")
RETAINED_IN_TMP = re.compile(
    "(?i)" + RETENTION + SENTENCE_GAP + "(" + TMP_PATH + ")|(" + TMP_PATH + ")" + SENTENCE_GAP + RETENTION)
FENCE = re.compile(r"^\s*(```|~~~)")
CODE_SPAN = re.compile(r"`[^`\n]*`")

# Tracked path -> why a home-directory path there is intentional. Keep this empty unless a file must
# quote one verbatim; an entry without a reason is a failure.
ALLOWED: Mapping[str, str] = {}
# Tracked path -> why it may name a local directory: instructions about where local data goes, not evidence.
LOCAL_ALLOWED: Mapping[str, str] = {"AGENTS.md": "tells agents where local run data and drafts go"}


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


def command_blanked(span: re.Match[str]) -> str:
    """An inline code span that contains a space is a command: blank it. Spans pair left to right."""
    return " " * len(span.group()) if any(c.isspace() for c in span.group()[1:-1]) else span.group()


def prose(text: str) -> str:
    """The Markdown text with fenced code blocks and inline commands (code spans containing a space) blanked,
    keeping line numbers."""
    lines, fenced = [], False
    for line in text.split("\n"):
        opens = FENCE.match(line) is not None
        lines.append("" if fenced or opens else CODE_SPAN.sub(command_blanked, line))
        fenced = fenced != opens
    return "\n".join(lines)


def local_strings(value: object) -> Iterable[str]:
    """Every local path in any string at any depth of a JSON value."""
    if isinstance(value, str):
        yield from re.findall(LOCAL_PATH, value)
    elif isinstance(value, dict):
        for inner in value.values():
            yield from local_strings(inner)
    elif isinstance(value, list):
        for inner in value:
            yield from local_strings(inner)


def retention_keys(value: object, trail: str = "") -> Iterable[tuple[str, str]]:
    """`(field path, local path)` for each JSON field that names where something private is kept."""
    if isinstance(value, dict):
        for key, inner in value.items():
            named = key.lower().startswith("private") or "archive" in key.lower() or "retain" in key.lower()
            if named:
                yield from ((f"{trail}{key}", where) for where in local_strings(inner))
            else:
                yield from retention_keys(inner, f"{trail}{key}.")
    elif isinstance(value, list):
        for index, inner in enumerate(value):
            yield from retention_keys(inner, f"{trail}{index}.")


def local_retention(files: Iterable[tuple[str, bytes]], allowed: Mapping[str, str] = LOCAL_ALLOWED) -> list[str]:
    """`path:line: local path` (Markdown) or `path: field: local path` (JSON) for each citation of a local
    directory as the place evidence is kept."""
    rows = []
    for path, content in files:
        if allowed.get(path):
            continue
        if path.endswith(".md"):
            text = prose(content.decode("utf-8", errors="replace"))
            found = {(match.start(), match.group().rstrip(".")) for match in WORKSPACE_PATH.finditer(text)}
            for match in RETAINED_IN_TMP.finditer(text):
                group = 1 if match.group(1) else 2
                found.add((match.start(group), match.group(group).rstrip(".")))
            rows.extend(f"{path}:{text.count(chr(10), 0, start) + 1}: {where}" for start, where in sorted(found))
        elif path.endswith(".json"):
            try:
                document = json.loads(content)
            except ValueError:
                continue
            rows.extend(f"{path}: {field}: {where}" for field, where in retention_keys(document))
    return rows


def tracked(root: Path) -> Iterable[tuple[str, bytes]]:
    names = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], check=True, capture_output=True).stdout
    for name in filter(None, names.split(b"\0")):
        path = root / name.decode()
        if path.is_file() and not path.is_symlink():
            yield name.decode(), path.read_bytes()


def main() -> int:
    empty = [path for path, reason in {**ALLOWED, **LOCAL_ALLOWED}.items() if not reason.strip()]
    files = list(tracked(ROOT))
    rows = findings(files)
    kept = local_retention(files)
    if empty or rows:
        print("absolute home paths in tracked files (record paths relative to the repository root):")
        print("\n".join(rows + [f"{path}: allowlist entry without a reason" for path in empty]))
    if kept:
        print("evidence cited as kept in a local directory (record results and hashes; say the raw artifacts are not published):")
        print("\n".join(kept))
    if empty or rows or kept:
        return 1
    print("no absolute home paths and no evidence cited as kept locally in tracked files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
