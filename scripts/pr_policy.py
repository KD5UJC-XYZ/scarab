#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate pull-request metadata without loading pull-request code.

Besides the title and the promotion source, the body states the level of the change (CONTRIBUTING.md, "Levels"), and a
pull request of level 2 or higher carries the two sections CONTRIBUTING.md asks for ("Commits and pull requests"):
"Deviations from the reference" (`None`, or a table of behaviour, the pinned reference, what Scarab does and the
approval link) and "Gaps" (`None`, or bullets that each start with an issue link). A bot's pull request (a release
proposal, a dependency update) and a promotion of `dev` to `main` are not checked for these. The promotion controller
(`promote_main.py`) calls `evaluate` only, never the body checks.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re


MODES = ("transition", "dev-only-main")
TITLE = re.compile(r"(?:build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test)(?:\([^\s():!]+\))?!?: \S[^\r\n]*")
COMMIT = re.compile(r"[0-9a-f]{40}")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
LEVEL = re.compile(r"^\s*(?:[-*]\s+)?\**Level\**:?\**\s*([0-4])\b", re.IGNORECASE | re.MULTILINE)
UNFILLED_LEVEL = re.compile(r"0\s+docs\s*/\s*1\s+small", re.IGNORECASE)
COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
HEADING = re.compile(r"^##\s+(.+?)\s*$", re.MULTILINE)
DEVIATIONS = "deviations from the reference"
GAPS = "gaps"
ISSUE_LINK = re.compile(r"(?:\[#\d+\]\([^)]*\)|#\d+\b|https://github\.com/[^/\s]+/[^/\s]+/issues/\d+)")
APPROVAL = re.compile(r"(?:https?://|#\d+|\]\()")
SECTION_LEVEL = 2


class PolicyError(ValueError):
    """Metadata does not satisfy the repository's reviewed promotion policy."""


def require(condition, message):
    if not condition:
        raise PolicyError(message)


def level_of(body):
    """The level the body states, or None when it states none (the template's unfilled line counts as none)."""
    for match in LEVEL.finditer(body):
        end = body.find("\n", match.start())
        line = body[match.start():end if end >= 0 else len(body)]
        if not UNFILLED_LEVEL.search(line):
            return int(match.group(1))
    return None


def sections(body):
    """The text under each level-2 heading, keyed by the heading in lower case, without HTML comments."""
    text = COMMENT.sub("", body)
    heads = list(HEADING.finditer(text))
    return {h.group(1).strip().lower(): text[h.end():(heads[i + 1].start() if i + 1 < len(heads) else len(text))].strip()
            for i, h in enumerate(heads)}


def is_none(text):
    return text.rstrip(".").strip().lower() == "none"


def check_deviations(text):
    require(text is not None, 'a level 2 or higher pull request needs a "## Deviations from the reference" section')
    if is_none(text):
        return
    rows = [line.strip() for line in text.splitlines() if line.strip()]
    require(len(rows) >= 3 and all(r.startswith("|") for r in rows) and set(rows[1]) <= set("|-: "),
            "Deviations from the reference: None, or a table with a header, a separator and one row per deviation")
    for row in rows[2:]:
        cells = [c.strip() for c in row.strip("|").split("|")]
        require(len(cells) == 4 and all(cells), "Deviations from the reference: each row has four nonempty cells "
                "(behaviour, reference at its pin, Scarab, approval)")
        require(APPROVAL.search(cells[3]) is not None, "Deviations from the reference: each approval cell links its record")


def check_gaps(text):
    require(text is not None, 'a level 2 or higher pull request needs a "## Gaps" section')
    if is_none(text):
        return
    bullets = [line for line in text.splitlines() if line.strip() and not line[:1].isspace()]
    require(bool(bullets), "Gaps: None, or one bullet per gap")
    for bullet in bullets:
        item = bullet.lstrip("-* ").strip()
        require(bullet.lstrip().startswith(("-", "*")) and ISSUE_LINK.match(item) is not None,
                "Gaps: each bullet starts with the issue that tracks the gap")


def check_body(pr):
    """The level the body states; None for a bot's pull request and for a promotion of `dev`, whose changes each
    passed this check as their own pull request."""
    if (pr.get("user") or {}).get("type") == "Bot":
        return None
    if pr.get("head", {}).get("ref") == "dev" and pr.get("base", {}).get("ref") == "main":
        return None
    body = pr.get("body") or ""
    level = level_of(body)
    require(level is not None, "state the level of the change in the body (pull request template)")
    if level >= SECTION_LEVEL:
        found = sections(body)
        check_deviations(found.get(DEVIATIONS))
        check_gaps(found.get(GAPS))
    return level


def evaluate(pr, repository, repository_id, mode):
    require(mode in MODES, "unknown promotion policy mode")
    require(REPOSITORY.fullmatch(repository), "invalid repository identity")
    require(type(repository_id) is int and repository_id > 0, "repository ID must be a positive integer")
    require(type(pr.get("number")) is int and pr["number"] > 0, "missing pull-request number")
    require(type(pr.get("title")) is str and TITLE.fullmatch(pr["title"]),
            "require a conventional PR title: type(scope): description")
    base, head = pr.get("base", {}), pr.get("head", {})
    require(base.get("repo", {}).get("id") == repository_id
            and base.get("repo", {}).get("full_name") == repository,
            "pull request does not target the expected repository")
    require(type(base.get("ref")) is str and bool(base["ref"]), "missing target branch")
    require(type(head.get("ref")) is str and bool(head["ref"]), "missing source branch")
    require(type(head.get("sha")) is str and COMMIT.fullmatch(head["sha"]), "missing full source commit")
    if mode == "dev-only-main" and base["ref"] == "main":
        require(head["ref"] == "dev" and (head.get("repo") or {}).get("id") == repository_id
                and (head.get("repo") or {}).get("full_name") == repository,
                "main accepts only dev from the same repository")
    return {"schemaVersion": 1, "repository": repository, "repositoryId": repository_id,
            "pullRequest": pr["number"], "mode": mode, "base": base["ref"],
            "head": head["ref"], "headSha": head["sha"],
            "titleSha256": hashlib.sha256(pr["title"].encode()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", type=Path, default=os.environ.get("GITHUB_EVENT_PATH"))
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"), required=False)
    parser.add_argument("--repository-id", type=int, default=os.environ.get("GITHUB_REPOSITORY_ID"))
    parser.add_argument("--mode", default=os.environ.get("SCARAB_PROMOTION_POLICY") or "transition")
    args = parser.parse_args()
    require(args.event is not None and args.repository is not None, "require event and repository identity")
    event = json.loads(args.event.read_text())
    require(event.get("repository", {}).get("id") == args.repository_id,
            "event repository identity differs from the trusted runner")
    pr = event.get("pull_request", {})
    result = evaluate(pr, args.repository, args.repository_id, args.mode)
    print(json.dumps(dict(result, level=check_body(pr)), sort_keys=True))


if __name__ == "__main__":
    main()
