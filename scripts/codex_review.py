#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the independent Codex review of a pull request's diff, with the model and effort its level requires.

This file is the one home of the reviewer's model (`MODEL`) and of the lowest reasoning effort each change allows
(`LEVEL_FLOOR`, and `DECISIVE_FLOOR` for a change to a design document or an ADR, which decides what later code
does); CONTRIBUTING.md "Independent review" states the rule and `scripts/pr_policy.py` reads both from here.
`--effort` may raise the effort above the floor, never lower it.

The review runs with the network on and the file system read-only: a permission profile that extends Codex's
`:read-only` profile with network access, and no approvals, so every command the reviewer runs stays in that
sandbox. Live web search is requested; whether Codex grants it is not checked. Every setting is passed on the
command line, so the user's own Codex configuration cannot change the model, the effort or the sandbox.

The floor comes from the pull request's whole diff (`--pr-base`); `--base` sets the review's scope, which a second
review narrows to the fix commits. The run is accepted only when the worktree is clean before it, Codex exits
successfully with a completed turn and a final message and reports no reroute to another model, the session file
Codex wrote records the model and effort in every turn, the read-only sandbox and the network, and the worktree's HEAD and status are unchanged after it. The output
file, outside the worktree, starts with a stamp line that `pr_policy.py` reads from the posted comment
(`<!-- codex-review model=... effort=... level=... head=... -->`), then a line for the reader and the review. Links
to files in the worktree become `path:line`, other absolute links plain text, and a review that still names a home
directory is refused, since the comment is public. The stamp records what the session file shows; it is evidence
against a mistaken run, not a signature.

Run the copy on the base branch, not the one under review, when the change touches this file:
`git show origin/main:scripts/codex_review.py > /tmp/codex_review.py`, then `python3 /tmp/codex_review.py --cd .`.

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
DECISIVE = ("docs/adr/", "docs/design/")
DECISIVE_FLOOR = "high"
PROFILE = "scarab_review"
STAMP = re.compile(r"<!-- codex-review ([a-z]+=[^\s>]+(?: [a-z]+=[^\s>]+){0,15}) -->")
REQUIRED = ("model", "effort", "level", "head")
LINK = re.compile(r"\[([^\]\n]{1,300})\]\((/[^)\s]{1,500})\)")
LINE = re.compile(r"(?:#L|:)(\d+)(?:-L?\d+)?$")
ATTRIBUTION = "Independent review by Codex "
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
    if any(f.startswith(DECISIVE) for f in files) and rank(DECISIVE_FLOOR) > rank(base):
        return DECISIVE_FLOOR
    return base


def stamp(fields):
    return "<!-- codex-review " + " ".join(f"{k}={v}" for k, v in fields.items()) + " -->"


def stamp_of(text):
    """The fields of the stamp on the first line of `text`, or None when the first line is not a stamp. A stamp
    with a repeated or missing field, or with no review after it and its attribution line, is refused."""
    first, _, rest = (text or "").partition("\n")
    match = STAMP.fullmatch(first.rstrip("\r "))
    if not match:
        return None
    pairs = [pair.split("=", 1) for pair in match.group(1).split(" ")]
    fields = dict(pairs)
    if len(fields) != len(pairs):
        raise ReviewError("the stamp repeats a field")
    missing = [k for k in REQUIRED if k not in fields]
    if missing:
        raise ReviewError(f"the stamp lacks {', '.join(missing)}")
    body = [line for line in rest.split("\n") if line.strip() and not line.startswith(ATTRIBUTION)]
    if not body:
        raise ReviewError("the stamp has no review after it")
    return fields


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


def records(text, strict=True):
    """The JSON objects of a JSON-lines text, split on newlines only: a JSON string may hold other line breaks. A
    line that is not JSON is refused, or skipped when not `strict` (Codex's standard output)."""
    out = []
    for number, line in enumerate(text.split("\n"), 1):
        if line.strip():
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError as error:
                if strict:
                    raise ReviewError(f"line {number} is not JSON: {error}") from error
    return out


def thread_of(events):
    """The thread id of a run from its JSON events. The run must end with a completed turn and no failed one, and
    must not have been rerouted to another model (Codex reports a reroute only here, as an error item); an error
    event alone is not a failure, since Codex reports retried errors too."""
    thread, completed = None, False
    for event in records(events, strict=False):
        kind = event.get("type")
        item = event.get("item") or {}
        if kind == "thread.started":
            thread = event.get("thread_id")
        elif kind == "item.completed" and item.get("type") == "error" \
                and str(item.get("message", "")).startswith("model rerouted"):
            raise ReviewError(f"Codex rerouted the run to another model: {item.get('message')}")
        elif kind == "turn.failed":
            raise ReviewError(f"the turn failed: {json.dumps(event)[:400]}")
        elif kind == "turn.completed":
            completed = True
    if not thread:
        raise ReviewError("the run reported no thread")
    if not completed:
        raise ReviewError("the run reported no completed turn")
    return thread


def session_file(thread, home):
    found = sorted(Path(home, "sessions").rglob(f"rollout-*-{thread}.jsonl"))
    if len(found) != 1:
        raise ReviewError(f"expected one session file for thread {thread}, found {len(found)}")
    return found[0]


def session_settings(path):
    """Each turn's model, effort, sandbox and network, and the CLI version, from a session file; a recorded reroute
    to another model is refused too."""
    meta, turns = None, []
    try:
        entries = records(Path(path).read_text(encoding="utf-8"))
    except ReviewError as error:
        raise ReviewError(f"the session file {Path(path).name}: {error}") from error
    for entry in entries:
        payload = entry.get("payload") or {}
        if entry.get("type") == "session_meta" and meta is None:
            meta = payload
        elif entry.get("type") == "turn_context":
            sandbox = payload.get("sandbox_policy") or {}
            turns.append({"model": payload.get("model"), "effort": payload.get("effort"),
                          "sandbox": sandbox.get("type"),
                          "network": "on" if sandbox.get("network_access") is True else "off"})
        elif payload.get("type") == "model_reroute":
            raise ReviewError(f"Codex rerouted the run to another model: {json.dumps(payload)[:300]}")
    if meta is None or not turns:
        raise ReviewError(f"the session file {Path(path).name} has no metadata or turn context")
    return turns, meta.get("cli_version")


def check_session(turns, effort):
    expected = {"model": MODEL, "effort": effort, "sandbox": "read-only", "network": "on"}
    for turn in turns:
        if turn != expected:
            wrong = {k: turn.get(k) for k, v in expected.items() if turn.get(k) != v}
            raise ReviewError(f"a turn ran with {wrong}, not {expected}")


def scrub(text, workdir):
    """The review without absolute paths: a link into the worktree becomes `path:line`, another absolute link its
    label; refused if a home path is left."""
    roots = sorted({str(Path(workdir).resolve()), str(workdir).rstrip("/")}, key=len, reverse=True)

    def relative(match):
        label, target = match.groups()
        for root in roots:
            if target.startswith(root + "/"):
                path = target[len(root) + 1:]
                line = LINE.search(path)
                where = LINE.sub("", path) + (f":{line.group(1)}" if line else "")
                return label if label in (where, path) else f"{label} (`{where}`)"
        return label

    text = LINK.sub(relative, text)
    for root in roots:
        text = text.replace(root + "/", "")
    left = HOME_PATH.search(text)
    if left:
        raise ReviewError(f"the review still names a home directory ({left.group(0)}...); edit it before posting")
    return text


def git(workdir, *args):
    return subprocess.run(["git", "-C", str(workdir), *args], check=True, capture_output=True, text=True).stdout


def changed_files(workdir, base):
    """The paths the diff touches, both sides of a rename, as Git stores them (no quoting)."""
    out = git(workdir, "diff", "-z", "--no-renames", "--name-only", f"{base}...HEAD")
    return [path for path in out.split("\0") if path]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--level", type=int, required=True, help="the level the pull request body states (0-4)")
    parser.add_argument("--brief", type=Path, required=True,
                        help="the review brief (docs/playbooks/review-a-change.md)")
    parser.add_argument("--out", type=Path, required=True,
                        help="where to write the review, outside the worktree, to post as a comment")
    parser.add_argument("--effort", help="an effort at or above the change's floor (default: the floor)")
    parser.add_argument("--base", default="origin/main",
                        help="the base the review's scope starts from (a scoped second review: the commit before the fixes)")
    parser.add_argument("--pr-base", default="origin/main",
                        help="the pull request's base, whose diff sets the effort floor (default: origin/main)")
    parser.add_argument("--cd", type=Path, default=Path.cwd(), help="the worktree to review (default: here)")
    parser.add_argument("--codex", default=os.environ.get("CODEX", "codex"), help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    try:
        workdir = Path(git(args.cd.resolve(), "rev-parse", "--show-toplevel").strip())
        if args.out.resolve().is_relative_to(workdir):
            raise ReviewError("write the review outside the worktree under review")
        need = floor(args.level, changed_files(workdir, args.pr_base))
        effort = args.effort or need
        if rank(effort) < rank(need):
            raise ReviewError(f"effort {effort} is below {need}, the floor for this change")
        if git(workdir, "status", "--porcelain"):
            raise ReviewError("the worktree has uncommitted changes; the review covers committed work only")
        head = git(workdir, "rev-parse", "HEAD").strip()
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
        if git(workdir, "status", "--porcelain") or git(workdir, "rev-parse", "HEAD").strip() != head:
            raise ReviewError("the worktree changed during the review; a review makes no edits")
        turns, cli = session_settings(session_file(thread, os.environ.get("CODEX_HOME") or Path.home() / ".codex"))
        check_session(turns, effort)
        fields = {"model": MODEL, "effort": effort, "level": args.level, "head": head, "base": args.base,
                  "prbase": args.pr_base,
                  "cli": cli, "sandbox": "read-only", "network": "on"}
        header = (f"{ATTRIBUTION}`{MODEL}` at effort `{effort}` (floor `{need}` for level "
                  f"{args.level}), read-only with network, of `{head[:12]}` against `{args.base}`.")
        args.out.write_text(f"{stamp(fields)}\n{header}\n\n{scrub(message, workdir)}\n", encoding="utf-8")
    except (ReviewError, OSError, subprocess.CalledProcessError) as error:
        print(f"codex_review: no review: {error}", file=sys.stderr)
        return 1
    print(f"codex_review: {args.out} ({MODEL}, effort {effort}, head {head[:12]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
