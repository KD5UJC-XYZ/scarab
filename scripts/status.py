#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""On-demand digest of the open issues, one line each, grouped by area. Never committed output.

    python3 scripts/status.py                      all open issues (needs an authenticated `gh`)
    python3 scripts/status.py --area networking    one area (the label without `area:`)
    python3 scripts/status.py --priority P1        one priority (`P1` or `priority:P1`)
    python3 scripts/status.py --json               the digest as JSON instead of text
    python3 scripts/status.py --from issues.json   read the output of
                                                   `gh issue list --json number,title,labels` from a file

A line shows the number, priority, status label, roadmap id, and the title cut to the terminal width. The
roadmap id comes from `docs/roadmap.md`: the bold id that leads a line whose leading links cite the issue, and
failing that the first line that cites the issue at all. Labels follow docs/process.md: `priority:P0..P2`,
`status:*`, `area:*`.

Pure standard library; the only network use is `gh issue list`, and `--from` avoids it.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path

GH_COMMAND = ["gh", "issue", "list", "--state", "open", "--limit", "1000", "--json", "number,title,labels"]
NO_AREA = "none"
ISSUE_LINK = re.compile(r"issues/(\d+)")
LEADING = re.compile(r"^\s*-\s+\*\*([^*]+)\*\*((?:\s*\[#\d+\]\([^)]*issues/\d+\)\s*,?)*)")


@dataclass(frozen=True)
class Digest:
    number: int
    priority: str
    status: str
    area: str
    roadmap: str
    title: str


def roadmap_ids(text: str) -> dict[int, str]:
    """Issue number -> roadmap id, from the lines of docs/roadmap.md."""
    leading: dict[int, str] = {}
    mentioned: dict[int, str] = {}
    for line in text.splitlines():
        head = LEADING.match(line)
        if head is None:
            continue
        for number in ISSUE_LINK.findall(head.group(2)):
            leading.setdefault(int(number), head.group(1).strip())
        for number in ISSUE_LINK.findall(line):
            mentioned.setdefault(int(number), head.group(1).strip())
    return {**mentioned, **leading}


def label_value(labels: list[str], prefix: str, default: str) -> str:
    return next((label[len(prefix):] for label in labels if label.startswith(prefix)), default)


def digest(raw: list[dict], roadmap: dict[int, str]) -> list[Digest]:
    result = []
    for issue in raw:
        labels = [label["name"] if isinstance(label, dict) else label for label in issue.get("labels", [])]
        number = int(issue["number"])
        result.append(Digest(
            number, label_value(labels, "priority:", "-"), label_value(labels, "status:", "-"),
            label_value(labels, "area:", NO_AREA), roadmap.get(number, "-"), issue["title"].strip(),
        ))
    return sorted(result, key=lambda d: (d.area, d.priority, d.number))


def select(items: list[Digest], area: str | None, priority: str | None) -> list[Digest]:
    wanted = priority.removeprefix("priority:").upper() if priority else None
    wanted_area = area.removeprefix("area:") if area else None
    return [d for d in items
            if (wanted is None or d.priority.upper() == wanted) and (wanted_area is None or d.area == wanted_area)]


def render_text(items: list[Digest], width: int) -> str:
    groups: dict[str, list[Digest]] = defaultdict(list)
    for d in items:
        groups[d.area].append(d)
    ids = max((len(d.roadmap) for d in items), default=1)
    statuses = max((len(d.status) for d in items), default=1)
    lines = []
    for area, members in groups.items():
        lines.append(f"{area} ({len(members)})")
        for d in members:
            prefix = f"  #{d.number:<5} {d.priority:<3} {d.status:<{statuses}} {d.roadmap:<{ids}} "
            room = max(width - len(prefix), 10)
            title = d.title if len(d.title) <= room else d.title[:room - 3].rstrip() + "..."
            lines.append(prefix + title)
    return "\n".join(lines)


def load_issues(source: Path | None, run=subprocess.run) -> list[dict]:
    if source is not None:
        return json.loads(source.read_text(encoding="utf-8"))
    done = run(GH_COMMAND, capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise SystemExit(f"gh issue list failed: {done.stderr.strip()}")
    return json.loads(done.stdout)


def main(argv: list[str] | None = None) -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--area", help="only this area (label without the `area:` prefix)")
    parser.add_argument("--priority", help="only this priority, for example P1")
    parser.add_argument("--json", action="store_true", help="print JSON instead of text")
    parser.add_argument("--from", dest="source", type=Path, help="read `gh issue list --json` output from a file")
    parser.add_argument("--roadmap", type=Path, default=root / "docs/roadmap.md")
    parser.add_argument("--width", type=int, help="line width (default: the terminal's)")
    args = parser.parse_args(argv)
    roadmap = roadmap_ids(args.roadmap.read_text(encoding="utf-8")) if args.roadmap.is_file() else {}
    items = select(digest(load_issues(args.source), roadmap), args.area, args.priority)
    if args.json:
        print(json.dumps([asdict(d) for d in items], indent=1))
    else:
        print(render_text(items, args.width or shutil.get_terminal_size((100, 24)).columns))
    return 0


if __name__ == "__main__":
    sys.exit(main())
