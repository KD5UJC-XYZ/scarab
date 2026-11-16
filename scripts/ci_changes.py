#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Classify complete Git diffs conservatively for CI; unknown inputs run all checks.

PR merge checkouts compare against their first parent. Pushes compare against the
before SHA, including every commit in a multi-commit push. Manual/nightly runs
always run checks. Both sides of a rename are examined by disabling rename detection.
"""
import json
import os
from pathlib import PurePosixPath, Path
import subprocess

ROOT_DOCS = frozenset({"AGENTS.md", "CLAUDE.md", "README.md", "CONTRIBUTING.md", "CHANGELOG.md",
                       "CODE_OF_CONDUCT.md", "SECURITY.md", "NOTICE", "LICENSE"})


def docs_only(paths):
    """Only named root documents and Markdown under docs/changes are exempt."""
    def document(name):
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts:
            return False
        return name in ROOT_DOCS or (len(path.parts) > 1 and path.parts[0] in ("docs", "changes")
                                    and path.suffix == ".md")
    return bool(paths) and all(document(name) for name in paths)


def required(event_name, event, run=subprocess.run):
    if event_name == "pull_request":
        base = "HEAD^1"
    elif event_name == "push":
        base = event.get("before", "")
        if len(base) != 40 or any(c not in "0123456789abcdef" for c in base) or set(base) == {"0"}:
            return True
    else:
        return True
    result = run(["git", "diff", "--no-renames", "--name-only", "-z", base, "HEAD", "--"],
                 capture_output=True, check=False)
    if result.returncode != 0:
        return True
    try:
        paths = result.stdout.decode("utf-8").split("\0")
    except UnicodeDecodeError:
        return True
    if paths[-1] != "":
        return True
    return not docs_only(paths[:-1])


def main():
    try:
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        run = required(os.environ.get("GITHUB_EVENT_NAME", ""), event)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        run = True
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"run={str(run).lower()}\n")
    print("Run full checks" if run else "Documentation-only change: expensive checks are not required")


if __name__ == "__main__":
    main()
