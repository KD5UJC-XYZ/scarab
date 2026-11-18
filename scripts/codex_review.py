#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the independent Codex review of a pull request's diff, with the model and effort its level requires.

This file is the one home of the reviewer's model (`MODEL`) and of the lowest reasoning effort each level of change
allows (`LEVEL_FLOOR`, CONTRIBUTING.md "Independent review"); `scripts/pr_policy.py` reads both from here. A change
to a design document or an ADR raises the floor to `DECISIVE_FLOOR`, since it decides what later code does.
`--effort` may raise the effort above the floor, never lower it.

The review runs read-only with the network on: a permission profile that extends Codex's `:read-only` profile with
network access, live web search, and no approvals, so nothing runs outside the sandbox. Every setting is passed on
the command line, so the user's own Codex configuration cannot change what runs. The run is accepted only when it
completed with a final message, the session file Codex wrote for it records the model, the effort, the read-only
sandbox and the network, and `git status` is the same before and after.

The output file starts with a stamp, an HTML comment that `pr_policy.py` reads from the posted comment
(`<!-- codex-review model=... effort=... level=... head=... -->`), and a line for the reader, then the review. Links
to absolute paths are rewritten as plain text and the worktree's path is removed; a review that still names a home
directory is refused, since the comment is public. The stamp records what the session file shows; it is evidence
against a mistaken run, not a signature.

Usage: python3 scripts/codex_review.py --level N --brief BRIEF.md --out REVIEW.md [--effort E] [--base origin/main]
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

MODEL = "gpt-6.2-astra"
EFFORTS = ("low", "medium", "high", "xhigh", "max", "ultra")
LEVEL_FLOOR = {0: "medium", 1: "high", 2: "high", 3: "xhigh", 4: "xhigh"}
DECISIVE = re.compile(r"docs/(?:adr|design)/.*")
DECISIVE_FLOOR = "high"
PROFILE = "scarab_review"
STAMP = re.compile(r"<!--\s*codex-review\s+([^>]*?)\s*-->")
FIELD = re.compile(r"([a-z]+)=(\S+)")
PATH_LINK = re.compile(r"\[([^\]]+)\]\(/[^)\s]*\)")
HOME_PATH = re.compile(r"(?:/home|/Users)/[A-Za-z0-9_][A-Za-z0-9._-]*/")


class ReviewError(Exception):
    """The review did not run as required, so it is no review."""


def rank(effort):
    if effort not in EFFORTS:
        raise ReviewError(f"unknown effort {effort!r}; one of {', '.join(EFFORTS)}")
    return EFFORTS.index(effort)


def floor(level, files=()):
    """The lowest effort a review of a change of `level` touching `files` may run at."""
    if level not in LEVEL_FLOOR:
        raise ReviewError(f"unknown level {level!r}")
    base = LEVEL_FLOOR[level]
    if any(DECISIVE.fullmatch(f) for f in files) and rank(DECISIVE_FLOOR) > rank(base):
        return DECISIVE_FLOOR
    return base


def stamp(fields):
    return "<!-- codex-review " + " ".join(f"{k}={v}" for k, v in fields.items()) + " -->"


def stamps(text):
    """The stamps in `text`, each as a dict of its fields."""
    return [dict(FIELD.findall(m.group(1))) for m in STAMP.finditer(text or "")]


def stamp_problem(fields, level, files=()):
    """Why a stamp does not meet the requirement for a change of `level`, or None when it does."""
    if fields.get("model") != MODEL:
        return f"the review ran on {fields.get('model')!r}, not {MODEL}"
    effort = fields.get("effort")
    if effort not in EFFORTS:
        return f"the review's effort {effort!r} is unknown"
    need = floor(level, files)
    if rank(effort) < rank(need):
        return f"the review ran at effort {effort}, below {need} for a level {level} change"
    return None


def command(effort, workdir, last_message, codex="codex"):
    """The Codex command line; the brief goes on standard input."""
    return [codex, "exec", "-m", MODEL, "-c", f'model_reasoning_effort="{effort}"',
            "-c", f'default_permissions="{PROFILE}"', "-c", f'permissions.{PROFILE}.extends=":read-only"',
            "-c", f"permissions.{PROFILE}.network.enabled=true", "-c", 'approval_policy="never"',
            "-c", 'web_search="live"', "--json", "-o", str(last_message), "-C", str(workdir), "-"]


def thread_of(events):
    """The thread id of a run from its JSON event lines; a failed turn or an error event refuses the run."""
    thread = None
    for line in events.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        kind = event.get("type")
        if kind == "thread.started":
            thread = event.get("thread_id")
        elif kind in ("turn.failed", "error"):
            raise ReviewError(f"the run failed: {json.dumps(event)[:400]}")
    if not thread:
        raise ReviewError("the run reported no thread")
    return thread


def session_file(thread, home):
    found = sorted(Path(home, "sessions").rglob(f"rollout-*-{thread}.jsonl"))
    if len(found) != 1:
        raise ReviewError(f"expected one session file for thread {thread}, found {len(found)}")
    return found[0]


def session_settings(path):
    """Model, effort, sandbox and CLI version of a session, from its metadata and its first turn context."""
    meta, context = None, None
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        entry = json.loads(line)
        if entry.get("type") == "session_meta" and meta is None:
            meta = entry.get("payload") or {}
        elif entry.get("type") == "turn_context" and context is None:
            context = entry.get("payload") or {}
    if meta is None or context is None:
        raise ReviewError(f"the session file {path.name} has no metadata or turn context")
    sandbox = context.get("sandbox_policy") or {}
    return {"model": context.get("model"), "effort": context.get("effort"), "sandbox": sandbox.get("type"),
            "network": "on" if sandbox.get("network_access") is True else "off", "cli": meta.get("cli_version")}


def check_session(settings, effort):
    expected = {"model": MODEL, "effort": effort, "sandbox": "read-only", "network": "on"}
    wrong = {k: settings.get(k) for k, v in expected.items() if settings.get(k) != v}
    if wrong:
        raise ReviewError(f"the session ran with {wrong}, not {expected}")


def scrub(text, workdir):
    """The review without links to absolute paths and without the worktree's path; refused if a home path is left."""
    text = PATH_LINK.sub(r"\1", text)
    text = text.replace(str(Path(workdir).resolve()) + "/", "").replace(str(workdir).rstrip("/") + "/", "")
    left = HOME_PATH.search(text)
    if left:
        raise ReviewError(f"the review still names a home directory ({left.group(0)}...); edit it before posting")
    return text


def git(workdir, *args):
    return subprocess.run(["git", "-C", str(workdir), *args], check=True, capture_output=True, text=True).stdout


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--level", type=int, required=True, help="the level the pull request body states (0-4)")
    parser.add_argument("--brief", type=Path, required=True,
                        help="the review brief (docs/playbooks/review-a-change.md)")
    parser.add_argument("--out", type=Path, required=True, help="where to write the review, to post as a comment")
    parser.add_argument("--effort", help="an effort at or above the level's floor (default: the floor)")
    parser.add_argument("--base", default="origin/main", help="the base the diff is taken against")
    parser.add_argument("--cd", type=Path, default=Path.cwd(), help="the worktree to review (default: here)")
    parser.add_argument("--codex", default=os.environ.get("CODEX", "codex"), help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    try:
        workdir = args.cd.resolve()
        files = git(workdir, "diff", "--name-only", f"{args.base}...HEAD").split()
        need = floor(args.level, files)
        effort = args.effort or need
        if rank(effort) < rank(need):
            raise ReviewError(f"effort {effort} is below {need}, the floor for this change")
        head = git(workdir, "rev-parse", "HEAD").strip()
        before = git(workdir, "status", "--porcelain")
        brief = args.brief.read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory() as scratch:
            last = Path(scratch, "last-message.md")
            run = subprocess.run(command(effort, workdir, last, args.codex), input=brief, capture_output=True,
                                 text=True)
            if run.returncode != 0:
                raise ReviewError(f"codex exited with {run.returncode}: {run.stderr.strip()[-400:]}")
            thread = thread_of(run.stdout)
            message = last.read_text(encoding="utf-8").strip() if last.exists() else ""
        if not message:
            raise ReviewError("the run ended without a final message")
        if git(workdir, "status", "--porcelain") != before:
            raise ReviewError("the worktree changed during the review; a review makes no edits")
        home = os.environ.get("CODEX_HOME") or Path.home() / ".codex"
        settings = session_settings(session_file(thread, home))
        check_session(settings, effort)
        fields = {"model": MODEL, "effort": effort, "level": args.level, "head": head, "base": args.base,
                  "cli": settings["cli"], "sandbox": "read-only", "network": "on"}
        header = (f"Independent review by Codex `{MODEL}` at effort `{effort}` (floor `{need}` for level "
                  f"{args.level}), read-only with network, of `{head[:12]}` against `{args.base}`.")
        args.out.write_text(f"{stamp(fields)}\n{header}\n\n{scrub(message, workdir)}\n", encoding="utf-8")
    except (ReviewError, OSError, subprocess.CalledProcessError) as error:
        print(f"codex_review: no review: {error}", file=sys.stderr)
        return 1
    print(f"codex_review: {args.out} ({MODEL}, effort {effort}, head {head[:12]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
