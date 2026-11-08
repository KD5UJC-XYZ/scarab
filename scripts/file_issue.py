#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""File an issue through `gh issue create` only when it carries the labels and milestone docs/process.md asks for.

    python3 scripts/file_issue.py --title "Network: ..." --body-file body.md \
        --area network --area node --priority P1 --status backlog --milestone reference-parity
    python3 scripts/file_issue.py --title "..." --body "..." --priority P2 --parent 124
    python3 scripts/file_issue.py --title "..." --body "..." --area scripts --priority P2 --status idea \
        --own-issue "the owner asked for it on its own"
    python3 scripts/file_issue.py ... --label audit --dry-run      print the commands without running them

A P2 item is a checklist line of the issue it follows up, not an issue of its own (docs/process.md, "Keeping
the open list short"). With `--parent N` the body is posted as a comment on open issue N and a line
`- [ ] <title> (<date>, <comment link>)` is added to N's `## Checklist` or `## Follow-ups` section (a
`## Follow-ups` section is added at the end when there is neither); the parent's labels and milestone stand, so
`--area`, `--status`, `--milestone` and `--label` are refused there. The body is read again after the edit, and
the run fails if the line is missing (another edit of the parent raced it). A P2 item that needs its own issue
gives the reason with `--own-issue`, which is recorded at the end of the issue's body. P0 and P1 items are
filed as issues without either.

`--area` (one or more), `--priority` and `--status` take the value with or without its prefix (`P2` or
`priority:P2`) and must be in the vocabulary of `status.py`: an area is an sbt module of docs/codemap.md or
`build`, `scripts` or `docs`, and the rest is what docs/process.md lists. `--milestone` is required unless the
status is `idea` or `parked`, and refused with them. Each command is printed before it runs; the exit status is
`gh`'s.
"""
from __future__ import annotations

import argparse
import datetime
import json
import re
import shlex
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from status import AREAS, MILESTONES, PRIORITIES, STATUSES, WITHOUT_MILESTONE

OWN_ISSUE = "Filed as an issue of its own, not as a checklist line of a parent:"
CHECKLIST_HEADING = re.compile(r"^##\s+(Checklist|Follow-ups)\b.*$", re.MULTILINE)


def value(raw: str, prefix: str, vocabulary: tuple[str, ...], parser: argparse.ArgumentParser) -> str:
    bare = raw.removeprefix(prefix + ":")
    if bare not in vocabulary:
        parser.error(f"--{prefix} {raw!r} is not one of: {', '.join(vocabulary)}")
    return bare


def options(argv: list[str] | None = None) -> argparse.Namespace:
    """The checked options; refuses (exit 2) what docs/process.md does not allow."""
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--title", required=True)
    body = parser.add_mutually_exclusive_group(required=True)
    body.add_argument("--body")
    body.add_argument("--body-file", type=Path)
    parser.add_argument("--area", action="append", default=[],
                        help="one of: " + ", ".join(AREAS) + " (repeat it for work that spans modules)")
    parser.add_argument("--priority", required=True, help="one of: " + ", ".join(PRIORITIES))
    parser.add_argument("--status", help="one of: " + ", ".join(STATUSES))
    parser.add_argument("--milestone", help="one of: " + ", ".join(MILESTONES) + "; none for idea or parked")
    parser.add_argument("--label", action="append", default=[], help="a further label, such as audit (repeatable)")
    where = parser.add_mutually_exclusive_group()
    where.add_argument("--parent", type=int, metavar="N", help="add the item to open issue N's checklist")
    where.add_argument("--own-issue", metavar="REASON", help="why a P2 item needs an issue of its own")
    parser.add_argument("--dry-run", action="store_true", help="print the commands, do not run them")
    args = parser.parse_args(argv)
    args.priority = value(args.priority, "priority", PRIORITIES, parser)
    if args.parent is not None:
        given = [flag for flag, v in (("--area", args.area), ("--status", args.status),
                                      ("--milestone", args.milestone), ("--label", args.label)) if v]
        if given:
            parser.error(f"{', '.join(given)}: a checklist line keeps the labels and milestone of #{args.parent}")
        return args
    if args.priority == "P2" and not args.own_issue:
        parser.error("a P2 item is a checklist line of the issue it follows up: give --parent N, or --own-issue "
                     "\"<why it needs an issue of its own>\" (docs/process.md, Keeping the open list short)")
    if not args.area:
        parser.error("the following arguments are required: --area")
    if args.status is None:
        parser.error("the following arguments are required: --status")
    args.area = list(dict.fromkeys(value(a, "area", AREAS, parser) for a in args.area))
    args.status = value(args.status, "status", STATUSES, parser)
    if args.status in WITHOUT_MILESTONE and args.milestone is not None:
        parser.error(f"a status:{args.status} issue carries no milestone; drop --milestone")
    if args.status not in WITHOUT_MILESTONE and args.milestone is None:
        parser.error(f"a status:{args.status} issue needs --milestone (one of: {', '.join(MILESTONES)})")
    if args.milestone is not None and args.milestone not in MILESTONES:
        parser.error(f"--milestone {args.milestone!r} is not one of: {', '.join(MILESTONES)}")
    for label in args.label:
        if label.split(":", 1)[0] in ("area", "priority", "status"):
            parser.error(f"--label {label!r}: give it with --area, --priority or --status")
    return args


def body_text(args: argparse.Namespace) -> str:
    return args.body if args.body is not None else args.body_file.read_text()


def create_command(args: argparse.Namespace) -> list[str]:
    """The `gh issue create` argument list for an issue of its own."""
    gh = ["gh", "issue", "create", "--title", args.title]
    if args.own_issue:
        gh += ["--body", f"{body_text(args).rstrip()}\n\n{OWN_ISSUE} {args.own_issue}\n"]
    else:
        gh += ["--body", args.body] if args.body is not None else ["--body-file", str(args.body_file)]
    for label in [*(f"area:{a}" for a in args.area), f"priority:{args.priority}", f"status:{args.status}",
                  *args.label]:
        gh += ["--label", label]
    if args.milestone is not None:
        gh += ["--milestone", args.milestone]
    return gh


def with_line(body: str, line: str) -> str:
    """The parent body with `line` after the last item of its first checklist section, or in a new section."""
    heading = CHECKLIST_HEADING.search(body)
    if heading is None:
        return f"{body.rstrip()}\n\n## Follow-ups\n\n{line}\n"
    rest = body[heading.end():]
    following = re.search(r"^#{1,2}\s", rest, re.MULTILINE)
    section = rest[:following.start()] if following else rest
    items = list(re.finditer(r"^\s*[-*] \[[ xX]\].*$", section, re.MULTILINE))
    if items:
        at = heading.end() + items[-1].end()
        return f"{body[:at]}\n{line}{body[at:]}"
    after = rest.lstrip("\n")
    return f"{body[:heading.end()]}\n\n{line}\n" + (f"\n{after}" if after else "")


def show(command: list[str]) -> None:
    print("+ " + shlex.join(command), file=sys.stderr)


def add_to_parent(args: argparse.Namespace, run) -> int:
    """Post the item as a comment on the parent and add its checklist line; checked by reading the body again."""
    n = str(args.parent)
    view = ["gh", "issue", "view", n, "--json", "state,body"]
    comment = ["gh", "issue", "comment", n, "--body-file", "-"]
    edit = ["gh", "issue", "edit", n, "--body-file", "-"]
    details = f"Follow-up (priority:{args.priority}): **{args.title}**\n\n{body_text(args).rstrip()}\n"
    if args.dry_run:
        for command in (view, comment, edit):
            show(command)
        print(f"  checklist line: - [ ] {args.title} ({datetime.date.today().isoformat()}, <comment link>)",
              file=sys.stderr)
        return 0
    show(view)
    seen = run(view, check=False, capture_output=True, text=True)
    if seen.returncode != 0:
        print(seen.stderr, end="", file=sys.stderr)
        return seen.returncode
    parent = json.loads(seen.stdout)
    if parent.get("state") != "OPEN":
        print(f"#{n} is {str(parent.get('state')).lower()}: give an open parent, or --own-issue", file=sys.stderr)
        return 1
    show(comment)
    posted = run(comment, input=details, check=False, capture_output=True, text=True)
    if posted.returncode != 0:
        print(posted.stderr, end="", file=sys.stderr)
        return posted.returncode
    link = posted.stdout.strip()
    line = f"- [ ] {args.title} ({datetime.date.today().isoformat()}, {link})"
    show(edit)
    edited = run(edit, input=with_line(parent.get("body") or "", line), check=False, capture_output=True, text=True)
    if edited.returncode != 0:
        print(edited.stderr, end="", file=sys.stderr)
        return edited.returncode
    again = run(view, check=False, capture_output=True, text=True)
    if again.returncode != 0 or line not in (json.loads(again.stdout).get("body") or ""):
        print(f"the checklist line is not in #{n} after the edit (another edit raced it?); the details are in "
              f"{link}; add this line by hand:\n{line}", file=sys.stderr)
        return 1
    print(f"added to #{n}: {line}")
    return 0


def main(argv: list[str] | None = None, run=subprocess.run) -> int:
    args = options(argv)
    if args.parent is not None:
        return add_to_parent(args, run)
    gh = create_command(args)
    show(gh)
    if args.dry_run:
        return 0
    return run(gh, check=False).returncode


if __name__ == "__main__":
    sys.exit(main())
