#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate pull-request metadata without loading pull-request code.

Besides the title and the promotion source, the body states the level of the change (CONTRIBUTING.md, "Levels"), and a
pull request of level 1 or higher carries the two sections CONTRIBUTING.md asks for ("Commits and pull requests"):
"Deviations from the reference" (`None`, or a table of behaviour, the pinned reference, what Scarab does and the
approval link) and "Gaps" (`None`, or bullets that each start with an issue link). A section that opens with
`None` may go on in prose. A pull request that is not a draft (the event's `draft` flag) also carries an
"Independent review" section with a link, to the review of its final diff posted on it (CONTRIBUTING.md,
"Independent review"); a draft passes without it. Given the pull request's comments (`--comments`, one JSON object
per line with `id`, `body` and `user`), the merge base of its base and head (`--merge-base`; a full review must start
there, so a retarget needs a new one), and reviews (`--reviews`, with `id`, `state` and `user`), the section must link
a review on this pull request: a comment whose first line is the stamp of `codex_review.py`, naming the pinned model
and an effort at or above the floor for the stated level and the changed files, or a GitHub review submitted by a
person other than the author (compared by account id). A review of the whole pull request, extended by reviews of
fix commits that each start where a linked review ended, must reach the head, unless a line
`Since the review: <head> <reason>` says why the later commits need no review (CONTRIBUTING.md, "Independent review").
The stated level has floors from the changed files (`--files`, the pull request's file list): a file that is not documentation makes it at least 1, and the persisted-format register at least 3, as the
levels table of CONTRIBUTING.md defines them. A bot's pull request (a release
proposal, a dependency update) and a promotion of `dev` to `main` are not checked for these. The promotion controller
(`promote_main.py`) calls `evaluate` only, never the body checks.

A storage format change (FMT-1, docs/design-rules.md) is a pull request that changes the format manifest
`docs/data/storage-format.json` or its guard `scripts/check_storage_format.py`. It is a pull request of its own: its
title is conventional with the `storage` scope and the breaking-change marker, and its description names the
storage format (`fix(storage)!: storage format V10 drops the bootstrap sidecar fields`); it touches only the paths of
`check_storage_format.FORMAT_CHANGE_PATHS`; and its body has a "Storage format change" section with the question
as it was asked and the owner's decision in neutral words, and the UTC times they were asked and given (`Question:`,
`Asked at:`, `Decision:`, `Decided at:`). Those lines record an approval the owner gave in the chat session doing the
work; this check reads their form only. It applies to a bot's pull request too, and not to a promotion of `dev`, whose changes each passed
it as their own pull request. A storage format change is at least level 3.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_storage_format import approval_problems, outside_format_change, touches_freeze  # noqa: E402
import codex_review  # noqa: E402


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
REVIEW = "independent review"
LINK = re.compile(r"https?://\S+")
REFERENCE_DEFINITION = re.compile(r"^ {0,3}\[([^\]]+)\]:[ \t]*(?:\n[ \t]*)?(\S+)[^\n]*$", re.MULTILINE)
REFERENCE = re.compile(r"\[([^\]]+)\](?:\[([^\]]*)\])?(?![(:])")
ISSUE_LINK = re.compile(r"(?:\[#\d+\]\([^)]*\)|#\d+\b|https://github\.com/[^/\s]+/[^/\s]+/issues/\d+)")
APPROVAL = re.compile(r"(?:https?://|#\d+|\]\()")
SECTION_LEVEL = 1
DOCUMENTATION = re.compile(r"(?:.*\.md|docs/.*|changes/.*|LICENSE|NOTICE)")
PERSISTED_FORMAT = "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PersistedFormat.scala"
STORAGE_SECTION = "storage format change"
STORAGE_TITLE = re.compile(r"[a-z]+\(storage\)!: (?P<description>.*)")
STORAGE_WORDS = re.compile(r"\bstorage format\b", re.IGNORECASE)
STORAGE_TITLE_FORM = ('"<type>(storage)!: <description that names the storage format change>", for example '
                      '"fix(storage)!: storage format V10 drops the bootstrap sidecar fields"')
RECORD_FIELD = re.compile(r"^\s*(?:[-*]\s+)?\**(question|asked at|decision|decided at)\**\s*:\**\s*(.*)$", re.IGNORECASE)


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


NONE = re.compile(r"none\b(?![-\w])", re.IGNORECASE)


def is_none(text):
    """The section opens with the word None; prose may follow it ("None. This removes one deviation ...")."""
    return NONE.match(text.strip()) is not None


def check_deviations(text):
    require(text is not None and text.strip(), 'a level 1 or higher pull request needs a "## Deviations from the reference" section')
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
    require(text is not None and text.strip(), 'a level 1 or higher pull request needs a "## Gaps" section')
    if is_none(text):
        return
    bullets = [line for line in text.splitlines() if line.strip() and not line[:1].isspace()]
    require(bool(bullets), "Gaps: None, or one bullet per gap")
    for bullet in bullets:
        item = bullet.lstrip("-* ").strip()
        require(bullet.lstrip().startswith(("-", "*")) and ISSUE_LINK.match(item) is not None,
                "Gaps: each bullet starts with the issue that tracks the gap")


def without_code_spans(text):
    """`text` without its code spans: a run of backticks that no backslash escapes opens one, and the next run of
    the same length closes it; an opening run with no closing run is plain text (CommonMark, "Code spans")."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text[i] == "\\" and i + 1 < n:
            out.append(text[i:i + 2])
            i += 2
        elif text[i] == "`":
            j = i
            while j < n and text[j] == "`":
                j += 1
            run, k = j - i, j
            close = None
            while k < n:
                if text[k] == "`":
                    m = k
                    while m < n and text[m] == "`":
                        m += 1
                    if m - k == run:
                        close = m
                        break
                    k = m
                else:
                    k += 1
            if close is None:
                out.append(text[i:j])
                i = j
            else:
                out.append(" ")
                i = close
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def has_link(text):
    """Whether `text` renders a link: an inline link, an autolink or a bare URL outside link reference definitions,
    or a reference link whose label one of them defines with a URL."""
    defined = {label.strip().lower(): target for label, target in REFERENCE_DEFINITION.findall(text)}
    rest = REFERENCE_DEFINITION.sub("", text)
    if LINK.search(rest):
        return True
    return any(LINK.match(defined.get((ref or label).strip().lower(), "")) for label, ref in REFERENCE.findall(rest))


def check_review(pr, level=None, files=(), evidence=None):
    """A pull request that is not a draft links the independent review of its final diff: a link GitHub renders
    (`has_link`), in the section as `rendered_section` reads it, outside code spans. With its `evidence` (comments
    and reviews), the link must reach a review on this pull request (`linked_reviews`)."""
    if pr.get("draft") is True:
        return
    text = rendered_section(pr.get("body"), REVIEW)
    require(text is not None and has_link(without_code_spans(text)),
            'a pull request that is not a draft needs an "## Independent review" section that links the review of its '
            "final diff posted on it (CONTRIBUTING.md, \"Independent review\"); keep it a draft until then")
    if evidence is not None and level is not None:
        linked_reviews(pr, text, level, files, *evidence)


HUMAN_VERDICTS = {"APPROVED", "CHANGES_REQUESTED", "COMMENTED"}
SINCE_REVIEW = re.compile(r"^[ \t]*(?:[-*][ \t]+)?\**since the review\**[ \t]*:\**[ \t]*`?([0-9a-f]{7,40})(?![0-9a-f])`?"
                          r"[ \t]*[:,;-]?[ \t]+(?=[^\n]*[A-Za-z])\S[^\n]*$", re.IGNORECASE | re.MULTILINE)
INLINE_LINK = re.compile(r"\[(?:[^\[\]\n]|\[[^\]\n]*\])*\]\([ \t]*(?:<([^<>\n]+)>|([^\s()<>]+))"
                         r"(?:[ \t]+(?:\"[^\"\n]*\"|'[^'\n]*'))?[ \t]*\)")
AUTOLINK = re.compile(r"<(https?://[^<>\s]+)>")
BARE_URL = re.compile(r"(?<![\w<\[/])https?://[^\s<>()\[\]]+")
URL_TRAILING = ".,;:!?*_~'\""


def destinations(text):
    """The URLs the text links as GitHub renders it: inline link destinations, autolinks, bare URLs outside link
    labels (without trailing punctuation, which GitHub leaves out of the link), and the targets of reference links
    it uses; an unused reference definition links nothing."""
    defined = {}
    for label, target in REFERENCE_DEFINITION.findall(text):
        defined.setdefault(label.strip().lower(), target)
    rest = REFERENCE_DEFINITION.sub("", text)
    found = [angled or plain for angled, plain in INLINE_LINK.findall(rest)]
    used = [key for key in ((ref or label).strip().lower() for label, ref in REFERENCE.findall(rest)) if key in defined]
    rest = INLINE_LINK.sub(" ", rest)
    found += AUTOLINK.findall(rest)
    rest = REFERENCE.sub(" ", AUTOLINK.sub(" ", rest))
    found += [url.rstrip(URL_TRAILING) for url in BARE_URL.findall(rest)]
    return found + [defined[key] for key in used]


def linked_reviews(pr, text, level, files, comments, reviews, merge_base=None):
    """The reviews the section links on this pull request: a comment whose first line is the stamp of
    `codex_review.py` with the pinned model at or above the floor, or a GitHub review submitted by a person other than
    the author. At least one is required; a Codex review of the whole pull request (`scope=full`) or a person's
    review starts the coverage, and a review of fixes (`scope=fixes`) extends it only from a head already covered
    (its `from`); the coverage must reach the head unless the section says, in a line
    `Since the review: <head> <reason>` naming the current head, why the commits after the review need none (a rebase
    that leaves the diff as reviewed, a wording fix). Every linked stamp must meet the floor; an unstamped comment (a
    disposition, a reply) may be linked beside them."""
    number = pr.get("number")
    repository = ((pr.get("base") or {}).get("repo") or {}).get("full_name", "")
    link = re.compile(r"https://github\.com/" + re.escape(repository) + r"/pull/" + str(number)
                      + r"(?:/files|/commits)?#(issuecomment|pullrequestreview)-(\d+)", re.IGNORECASE)
    found = {"issuecomment": set(), "pullrequestreview": set()}
    for url in destinations(without_code_spans(text)):
        match = link.fullmatch(url)
        if match:
            found[match.group(1).lower()].add(match.group(2))
    comment_by_id = {str(c.get("id")): c for c in comments}
    review_by_id = {str(r.get("id")): r for r in reviews}
    linked_comments = [comment_by_id[i] for i in sorted(found["issuecomment"]) if i in comment_by_id]
    linked_reviews_ = [review_by_id[i] for i in sorted(found["pullrequestreview"]) if i in review_by_id]
    need = codex_review.floor(level, files)
    require(linked_comments or linked_reviews_,
            f'the "Independent review" section links no comment or review on this pull request (#{number}); link the '
            "posted review (CONTRIBUTING.md, \"Independent review\")")
    author = (pr.get("user") or {}).get("id")
    accepted, reviewed, fixes = [], set(), []
    for comment in linked_comments:
        try:
            fields = codex_review.stamp_of(comment.get("body"))
        except codex_review.ReviewError as error:
            raise PolicyError(f"the linked comment {comment.get('id')}: {error}") from error
        if fields is not None:
            problem = codex_review.stamp_problem(fields, level, files)
            require(problem is None, f"the linked comment {comment.get('id')}: {problem}; run the review again "
                    "with scripts/codex_review.py (CONTRIBUTING.md, \"Independent review\")")
            accepted.append(comment)
            if fields["scope"] == "full" and merge_base in (None, fields["from"]):
                reviewed.add(fields["head"])
            else:
                fixes.append((fields["from"], fields["head"]))
    for review in linked_reviews_:
        user = review.get("user") or {}
        if (type(user.get("id")) is int and type(author) is int and user["id"] != author
                and user.get("type") == "User" and review.get("state") in HUMAN_VERDICTS):
            accepted.append(review)
            reviewed.add(review.get("commit_id"))
    require(accepted, "no linked item is a review: link a comment that starts with the stamp of "
            f"scripts/codex_review.py at effort {need} or above, or a GitHub review submitted by a person other than "
            "the author (CONTRIBUTING.md, \"Independent review\")")
    require(reviewed, "no linked review covers the whole pull request from its merge base "
            f"{str(merge_base or '')[:12]}: link the review of the whole diff, or review it again after a retarget "
            "(CONTRIBUTING.md, \"Independent review\")")
    while True:
        extended = {head for start, head in fixes if start in reviewed} - reviewed
        if not extended:
            break
        reviewed |= extended
    head = str((pr.get("head") or {}).get("sha"))
    excused = any(head.startswith(sha.lower()) for sha in SINCE_REVIEW.findall(text))
    require(head in reviewed or excused,
            f"no linked review covers the head {head[:12]}: review the commits since the last review (a second "
            f"review scoped to them), or say in a line \"Since the review: {head[:12]} <reason>\" why they need none "
            "(CONTRIBUTING.md, \"Independent review\")")
    return accepted


def level_floor(files):
    """The lowest level the changed files allow, from the levels table of CONTRIBUTING.md."""
    if PERSISTED_FORMAT in files or touches_freeze(files):
        return 3
    return 1 if any(not DOCUMENTATION.fullmatch(f) for f in files) else 0


FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
ANY_HEADING = re.compile(r"^ {0,3}(#{1,6})(?:\s+(.*?))?\s*#*\s*$")
SETEXT = re.compile(r"^ {0,3}(=+|-+)\s*$")
LIST_ITEM = re.compile(r"^ {0,3}(?:[-*+]|\d+[.)])\s")


def rendered_lines(body):
    """The lines of `body` that GitHub renders as text: without HTML comments, fenced code blocks (a fence closes
    only on a line of the same character, at least as long, and nothing else) and indented code blocks (lines
    indented four spaces or more after an empty line or a heading). A Setext underline is kept: `rendered_section`
    ends there."""
    lines, fence, code, previous = [], None, False, ""
    for line in COMMENT.sub("", body or "").splitlines():
        opened = FENCE.match(line)
        blank, indented = not line.strip(), line.startswith(("    ", "\t"))
        if fence is not None:
            if opened and opened.group(1)[0] == fence[0] and len(opened.group(1)) >= len(fence) \
                    and not opened.group(2).strip():
                fence = None
        elif opened and not (opened.group(1)[0] == "`" and "`" in opened.group(2)):
            fence = opened.group(1)
        elif indented and not blank and (code or not previous.strip() or ANY_HEADING.match(previous)
                                         or SETEXT.match(previous)):
            code = True
        elif not blank:
            code = False
            lines.append(line)
        else:
            lines.append(line)
        previous = line
    return lines


def rendered_section(body, title):
    """The text under the first level-2 heading `title` (in lower case) as GitHub renders the body
    (`rendered_lines`), up to the next heading of level 1 or 2, ATX or Setext; None when there is no such heading.
    The level-2 sections of `sections` are read as before; this reading is for the sections whose content is
    checked."""
    found, lines = None, rendered_lines(body)
    for at, line in enumerate(lines):
        heading = ANY_HEADING.match(line)
        if found is None:
            if heading and len(heading.group(1)) == 2 and (heading.group(2) or "").strip().lower() == title:
                found = []
        elif heading and len(heading.group(1)) <= 2:
            break
        elif SETEXT.match(line) and found and found[-1].strip() and not LIST_ITEM.match(found[-1]) \
                and not found[-1].startswith((" ", "\t")):
            found.pop()
            break
        else:
            found.append(line)
    return None if found is None else "\n".join(found).strip()


def approval_record(text):
    """The `Question:`, `Asked at:`, `Decision:` and `Decided at:` of a "Storage format change" section, keyed as the
    manifest keys them. A time is the rest of its line. A question or a decision goes on over the lines after it that
    are indented or quoted (`>`); any other line ends it."""
    record, current = {}, None
    for line in (text or "").splitlines():
        found = RECORD_FIELD.match(line)
        if found:
            current = found.group(1).lower().replace(" ", "_")
            record[current] = found.group(2).strip()
            current = None if current.endswith("_at") else current
        elif current is not None and line.strip() and (line[:1].isspace() or line.lstrip().startswith(">")):
            record[current] = (record[current] + "\n" + line.strip().lstrip(">").strip()).strip()
        else:
            current = None
    return {k: v.strip("`").strip() if k.endswith("_at") else v.lstrip(">").strip() for k, v in record.items()}


def promotion(pr):
    """A promotion of `dev` to `main` within the repository: the branch names and the repository of both sides."""
    base, head = pr.get("base") or {}, pr.get("head") or {}
    base_repo, head_repo = base.get("repo") or {}, head.get("repo") or {}
    return (head.get("ref") == "dev" and base.get("ref") == "main" and base_repo.get("id") is not None
            and head_repo.get("id") == base_repo.get("id") and head_repo.get("full_name") == base_repo.get("full_name"))


def check_storage_format(pr, files, now):
    """Whether the pull request is a storage format change (FMT-1); one that is must be its own pull request of
    level 3 or more, with a title that says so and the record of the owner's decision. A bot's pull request is
    checked too. A promotion of `dev` from the same repository is not: its changes each passed this check as their
    own pull request."""
    if promotion(pr) or not touches_freeze(files):
        return False
    title = pr.get("title") if isinstance(pr.get("title"), str) else ""
    named = STORAGE_TITLE.fullmatch(title)
    require(TITLE.fullmatch(title) is not None and named is not None
            and STORAGE_WORDS.search(named.group("description")) is not None,
            "a storage format change (FMT-1) needs a title of the form " + STORAGE_TITLE_FORM)
    outside = outside_format_change(files)
    require(not outside, "a storage format change must be its own pull request (FMT-1): it may touch only the paths of "
            "FORMAT_CHANGE_PATHS in scripts/check_storage_format.py, and these are outside them: " + ", ".join(outside))
    level = level_of(pr.get("body") or "")
    require(level is not None and level >= 3, f"a storage format change is level 3 or more, not {level} (FMT-1)")
    section = rendered_section(pr.get("body"), STORAGE_SECTION)
    require(bool(section), 'a storage format change needs a "## Storage format change" section with the owner\'s '
            "question and decision, in neutral words, and when each was asked and given in UTC (FMT-1)")
    problems = approval_problems(approval_record(section), now)
    require(not problems, "Storage format change: " + "; ".join(problems) +
            " (Question:, Asked at:, Decision:, Decided at:, times as 2026-11-14T09:30:00Z)")
    return True


def check_body(pr, files=(), evidence=None):
    """The level the body states; None for a bot's pull request and for a promotion of `dev`, whose changes each
    passed this check as their own pull request."""
    if (pr.get("user") or {}).get("type") == "Bot":
        return None
    if promotion(pr):
        return None
    body = pr.get("body") or ""
    level = level_of(body)
    require(level is not None, "state the level of the change in the body (pull request template)")
    floor = level_floor(files)
    require(level >= floor, f"level {level} is below {floor}: " + (
        "the persisted-format register, the storage format manifest or its guard changed (CONTRIBUTING.md, Levels)"
        if floor == 3
        else "a file other than documentation changed (CONTRIBUTING.md, Levels)"))
    if level >= SECTION_LEVEL:
        found = sections(body)
        check_deviations(found.get(DEVIATIONS))
        check_gaps(found.get(GAPS))
    check_review(pr, level, files, evidence)
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
    parser.add_argument("--files", type=Path, required=True,
                        help="the pull request's changed paths, one per line")
    parser.add_argument("--comments", type=Path, required=True,
                        help="the pull request's comments, one JSON object per line with id, body and user (id, type)")
    parser.add_argument("--reviews", type=Path, required=True,
                        help="the pull request's reviews, one JSON object per line with id, state, commit_id and "
                             "user (id, type)")
    parser.add_argument("--merge-base", type=Path, required=True,
                        help="a file holding the merge base of the pull request's base and head commits")
    args = parser.parse_args()
    require(args.event is not None and args.repository is not None, "require event and repository identity")
    event = json.loads(args.event.read_text())
    require(event.get("repository", {}).get("id") == args.repository_id,
            "event repository identity differs from the trusted runner")
    pr = event.get("pull_request", {})
    result = evaluate(pr, args.repository, args.repository_id, args.mode)
    files = [f for f in args.files.read_text(encoding="utf-8").split("\n") if f]
    evidence = []
    for path in (args.comments, args.reviews):
        try:
            evidence.append(codex_review.records(path.read_text(encoding="utf-8")))
        except codex_review.ReviewError as error:
            raise PolicyError(f"the review evidence in {path.name}: {error}") from error
    merge_base = args.merge_base.read_text(encoding="utf-8").strip()
    require(COMMIT.fullmatch(merge_base), "the merge base is not a full commit id")
    evidence = (*evidence, merge_base)
    storage = check_storage_format(pr, files, datetime.now(timezone.utc))
    print(json.dumps(dict(result, level=check_body(pr, files, evidence), storageFormatChange=storage),
                     sort_keys=True))


if __name__ == "__main__":
    main()
