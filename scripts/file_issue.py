#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""File an issue through `gh issue create` only when it carries the labels and milestone docs/process.md asks for.

    python3 scripts/file_issue.py --title "Network: ..." --body-file body.md \
        --area networking --priority P2 --status backlog --milestone reference-parity
    python3 scripts/file_issue.py --title "..." --body "..." --area ops --priority P2 --status idea
    python3 scripts/file_issue.py ... --label audit --dry-run      print the command without running it

`--area`, `--priority` and `--status` take the value with or without its prefix (`P2` or `priority:P2`) and
must be in the vocabulary of `status.py`, which docs/process.md lists. `--milestone` is required unless the
status is `idea` or `parked`, and refused with them. The command is printed before it runs; the exit status is
`gh`'s.
"""
from __future__ import annotations

import argparse
import shlex
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from status import AREAS, MILESTONES, PRIORITIES, STATUSES, WITHOUT_MILESTONE


def value(raw: str, prefix: str, vocabulary: tuple[str, ...], parser: argparse.ArgumentParser) -> str:
    bare = raw.removeprefix(prefix + ":")
    if bare not in vocabulary:
        parser.error(f"--{prefix} {raw!r} is not one of: {', '.join(vocabulary)}")
    return bare


def command(argv: list[str] | None = None) -> tuple[list[str], bool]:
    """The `gh issue create` argument list for these options, and whether this is a dry run."""
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--title", required=True)
    body = parser.add_mutually_exclusive_group(required=True)
    body.add_argument("--body")
    body.add_argument("--body-file", type=Path)
    parser.add_argument("--area", required=True, help="one of: " + ", ".join(AREAS))
    parser.add_argument("--priority", required=True, help="one of: " + ", ".join(PRIORITIES))
    parser.add_argument("--status", required=True, help="one of: " + ", ".join(STATUSES))
    parser.add_argument("--milestone", help="one of: " + ", ".join(MILESTONES) + "; none for idea or parked")
    parser.add_argument("--label", action="append", default=[], help="a further label, such as audit (repeatable)")
    parser.add_argument("--dry-run", action="store_true", help="print the command, do not run it")
    args = parser.parse_args(argv)
    area = value(args.area, "area", AREAS, parser)
    priority = value(args.priority, "priority", PRIORITIES, parser)
    status = value(args.status, "status", STATUSES, parser)
    if status in WITHOUT_MILESTONE and args.milestone is not None:
        parser.error(f"a status:{status} issue carries no milestone; drop --milestone")
    if status not in WITHOUT_MILESTONE and args.milestone is None:
        parser.error(f"a status:{status} issue needs --milestone (one of: {', '.join(MILESTONES)})")
    if args.milestone is not None and args.milestone not in MILESTONES:
        parser.error(f"--milestone {args.milestone!r} is not one of: {', '.join(MILESTONES)}")
    for label in args.label:
        if label.split(":", 1)[0] in ("area", "priority", "status"):
            parser.error(f"--label {label!r}: give it with --area, --priority or --status")
    gh = ["gh", "issue", "create", "--title", args.title]
    gh += ["--body", args.body] if args.body is not None else ["--body-file", str(args.body_file)]
    for label in [f"area:{area}", f"priority:{priority}", f"status:{status}", *args.label]:
        gh += ["--label", label]
    if args.milestone is not None:
        gh += ["--milestone", args.milestone]
    return gh, args.dry_run


def main(argv: list[str] | None = None, run=subprocess.run) -> int:
    gh, dry_run = command(argv)
    print("+ " + shlex.join(gh), file=sys.stderr)
    if dry_run:
        return 0
    return run(gh, check=False).returncode


if __name__ == "__main__":
    sys.exit(main())
