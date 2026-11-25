#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Classify complete Git diffs conservatively for CI; unknown inputs run all checks.

PR merge checkouts compare against their first parent. Pushes compare against the
before SHA, including every commit in a multi-commit push. Manual/nightly runs
always run checks. Both sides of a rename are examined by disabling rename detection.

Two outputs: `run` (anything but documentation changed) and `nix` (the Nix flake, the
launcher it builds or this workflow changed; docs/nix.md).
"""
import json
import os
from pathlib import PurePosixPath, Path
import subprocess

ROOT_DOCS = frozenset({"AGENTS.md", "CLAUDE.md", "README.md", "CONTRIBUTING.md", "CHANGELOG.md",
                       "CODE_OF_CONDUCT.md", "SECURITY.md", "NOTICE", "LICENSE"})
NIX_FILES = frozenset({"flake.nix", "flake.lock", ".envrc", "scripts/nix-network", ".github/workflows/ci.yml"})
NIX_DIRECTORIES = frozenset({"flake", "perSystem"})


def docs_only(paths):
    """Only named root documents and Markdown under docs/changes are exempt."""
    def document(name):
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts:
            return False
        return name in ROOT_DOCS or (len(path.parts) > 1 and path.parts[0] in ("docs", "changes")
                                    and path.suffix == ".md")
    return bool(paths) and all(document(name) for name in paths)


def nix_affected(paths):
    """Whether the change touches the flake, the launcher its apps run, or the workflow that checks them."""
    def nix(name):
        parts = PurePosixPath(name).parts
        return name in NIX_FILES or (len(parts) > 1 and parts[0] in NIX_DIRECTORIES)
    return any(nix(name) for name in paths)


def changed(event_name, event, run=subprocess.run):
    """The paths the change touches, or None when they cannot be known, and then every check runs."""
    if event_name == "pull_request":
        base = "HEAD^1"
    elif event_name == "push":
        base = event.get("before", "")
        if len(base) != 40 or any(c not in "0123456789abcdef" for c in base) or set(base) == {"0"}:
            return None
    else:
        return None
    result = run(["git", "diff", "--no-renames", "--name-only", "-z", base, "HEAD", "--"],
                 capture_output=True, check=False)
    if result.returncode != 0:
        return None
    try:
        paths = result.stdout.decode("utf-8").split("\0")
    except UnicodeDecodeError:
        return None
    if paths[-1] != "":
        return None
    return paths[:-1]


def required(event_name, event, run=subprocess.run):
    paths = changed(event_name, event, run)
    return paths is None or not docs_only(paths)


def main():
    try:
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        paths = changed(os.environ.get("GITHUB_EVENT_NAME", ""), event)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        paths = None
    run = paths is None or not docs_only(paths)
    nix = paths is None or nix_affected(paths)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"run={str(run).lower()}\nnix={str(nix).lower()}\n")
    print("Run full checks" if run else "Documentation-only change: expensive checks are not required")
    print("Check the Nix flake" if nix else "The Nix flake is unchanged: its checks are not required")


if __name__ == "__main__":
    main()
