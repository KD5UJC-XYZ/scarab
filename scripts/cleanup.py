#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""List what finished work left on this machine and, with `--apply`, remove what is provably finished or yours.

    python3 scripts/cleanup.py                          dry run: each resource, `remove` or `keep`, and why
    python3 scripts/cleanup.py --apply                  remove unused worktrees whose pull request was merged
    python3 scripts/cleanup.py --owner soak-1 --apply   also the run directory and stopped containers of soak-1
    python3 scripts/cleanup.py --stale-days 14 --apply  also anyone's run directories and stopped labelled
                                                        containers untouched for 14 days (a machine-wide sweep)
    python3 scripts/cleanup.py --offline                do not ask GitHub; only heads in origin/main count as merged

Worktrees: every `git worktree list` entry except the main checkout and the one this runs in. One is removed,
with its local branch, when its checkout is clean, it is not locked, no process works or holds a file in it, no
container mounts it, nothing is mounted inside it, it holds no ignored files other than build output, and its
head commit is exactly the head of a pull request from its branch merged into `main` of this repository (a
squash merge leaves the branch's own commits outside `main`). A head that is merely in `origin/main` proves nothing (a worktree just
created from main has one), so such a worktree is removed only for `--owner <its directory or branch>` or, with
`--stale-days`, after that long without git activity.

Run directories (entries of `SCARAB_DATA_DIR`, default `/tmp/scarab`) and stopped containers labelled
`xyz.kd5ujc.scarab.kind` (see `container_labels`) carry no record of being finished, so age alone never removes
them: they are removed only for an owner named with `--owner` (a devnet's name is its owner), or, with
`--stale-days`, when untouched for that long. Either way a run directory is kept while any tracked file cites it,
a devnet container `<name>-ref` exists, a process works or holds a file in it, a container mounts it, something
is mounted inside it or it holds another user's files. A symlink is removed as a link; its target is kept.
Unlabelled and running containers, images and volumes are never touched. Evidence that must outlive a run
belongs in a dated `.workspace/data/` directory, not here.

Every removal is checked again against fresh process, mount and container state immediately before it runs.
Process use is read from `/proc` for the processes this user can inspect, so this runs on Linux only, and a
directory holding another user's files is never removed.
"""
from __future__ import annotations

import argparse
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
LABEL = "xyz.kd5ujc.scarab"
# Ignored entries that may be discarded with a worktree; anything else ignored is kept. Build output, and the
# editor directories, which hold no settings this project keeps (owner decision, 2026-11-02, #694).
BUILD_OUTPUT = {"target", ".bsp", ".metals", ".bloop", ".scala-build", "__pycache__", ".idea", ".vscode"}
DAY = 86400


def container_labels(kind: str, owner: str) -> list[str]:
    """`docker run` options naming a container's kind (`devnet`, `oracle`, `sbt`) and owner (the devnet, task or
    issue it belongs to), so `cleanup.py --owner` can find it and say whose it is."""
    return ["--label", f"{LABEL}.kind={kind}", "--label", f"{LABEL}.owner={owner}"]


@dataclass(frozen=True)
class Worktree:
    path: str
    head: str
    branch: str | None
    locked: bool
    dirty: bool
    ignored: tuple[str, ...]
    activity: float  # last checkout, commit or index write, seconds since the epoch


@dataclass(frozen=True)
class Container:
    id: str
    name: str
    running: bool
    labels: Mapping[str, str]
    mounts: tuple[str, ...]
    finished: float  # when it stopped, seconds since the epoch; 0 when unknown


@dataclass(frozen=True)
class RunDir:
    path: str  # canonical
    symlink: bool
    newest_write: float
    foreign: bool  # holds a file owned by another user


@dataclass(frozen=True)
class Scope:
    owners: frozenset[str]
    stale_days: int | None


@dataclass(frozen=True)
class Facts:
    """What the machine looks like now: paths in use by processes, container mounts, host mount points and
    container names."""
    used: frozenset[str]
    mounted: tuple[str, ...]
    mount_points: tuple[str, ...]
    containers: frozenset[str]


def under(path: str, roots: Iterable[str]) -> bool:
    """Whether `path` is one of `roots` or inside one of them."""
    return any(path == root or path.startswith(root.rstrip("/") + "/") for root in roots)


def holds(path: str, used: Iterable[str]) -> bool:
    """Whether any of the `used` paths is `path` or inside it."""
    prefix = path.rstrip("/") + "/"
    return any(entry == path or entry.startswith(prefix) for entry in used)


def overlaps(path: str, others: Iterable[str]) -> bool:
    return any(under(other, [path]) or under(path, [other]) for other in others)


def build_output(entry: str) -> bool:
    return any(part in BUILD_OUTPUT for part in Path(entry).parts) or entry.rstrip("/") == "project/project"


def in_use(path: str, facts: Facts) -> str | None:
    """Why `path` is in use, or None."""
    if holds(path, facts.used):
        return "a process works or holds a file in it"
    if overlaps(path, facts.mounted):
        return "mounted by a container"
    if any(under(point, [path]) for point in facts.mount_points):
        return "something is mounted inside it"
    return None


def worktree_verdict(tree: Worktree, facts: Facts, merged: Callable[[str, str | None], str | None], scope: Scope,
                     now: float) -> tuple[bool, str]:
    """`(remove, reason)` for one worktree. `merged(head, branch)` is asked last, as it may query GitHub:
    `PULL_REQUEST` when a pull request from this branch with exactly this head was merged into main (the work is
    delivered), `IN_MAIN` when the
    head is only an ancestor of origin/main (as a worktree just created from main is, so that alone proves
    nothing), `UNMERGED`, or None when it cannot be established."""
    if tree.locked:
        return False, "locked"
    if tree.dirty:
        return False, "uncommitted or untracked files"
    busy = in_use(tree.path, facts)
    if busy:
        return False, busy
    kept = [entry for entry in tree.ignored if not build_output(entry)]
    if kept:
        return False, "ignored files that are not build output: " + ", ".join(kept[:3])
    state = merged(tree.head, tree.branch)
    if state is None:
        return False, "merge state unknown"
    if state == PULL_REQUEST:
        return True, "its head was merged into main by a pull request"
    if state == UNMERGED:
        return False, "head is neither in origin/main nor the head of a pull request merged into main"
    name = Path(tree.path).name
    if name in scope.owners or (tree.branch or "") in scope.owners:
        return True, f"head is in origin/main, owner {name}"
    age = (now - tree.activity) / DAY
    if scope.stale_days is not None and age >= scope.stale_days:
        return True, f"head is in origin/main, no git activity for {age:.0f} days"
    return False, f"head is in origin/main without a merged pull request; not removed without --owner {name} or --stale-days"


def container_verdict(box: Container, scope: Scope, now: float) -> tuple[bool, str]:
    kind = box.labels.get(f"{LABEL}.kind")
    owner = box.labels.get(f"{LABEL}.owner")
    if kind is None:
        return False, "not labelled as ours"
    if box.running:
        return False, f"running ({kind}, {owner})"
    if owner and owner in scope.owners:
        return True, f"stopped, owner {owner}"
    age = (now - box.finished) / DAY if box.finished else 0
    if scope.stale_days is not None and box.finished and age >= scope.stale_days:
        return True, f"stopped {age:.0f} days ago ({kind}, {owner})"
    return False, f"stopped ({kind}, {owner}); not removed without --owner {owner} or --stale-days"


def run_dir_verdict(entry: RunDir, facts: Facts, cited: Mapping[str, str], scope: Scope,
                    now: float) -> tuple[bool, str]:
    """`(remove, reason)` for one entry of the data directory. `cited` maps an entry name to a tracked file that
    names it: a document or manifest may rely on it, so it is kept until it moves somewhere durable."""
    name = Path(entry.path).name
    if name in cited:
        return False, f"cited by {cited[name]}"
    if f"{name}-ref" in facts.containers:
        return False, f"devnet container {name}-ref exists"
    busy = None if entry.symlink else in_use(entry.path, facts)
    if busy:
        return False, busy
    if entry.foreign:
        return False, "holds another user's files"
    link = " (a symlink; its target is kept)" if entry.symlink else ""
    if name in scope.owners:
        return True, f"owner {name}{link}"
    age = (now - entry.newest_write) / DAY
    if scope.stale_days is not None and age >= scope.stale_days:
        return True, f"untouched for {age:.0f} days{link}"
    return False, f"written {age:.1f} days ago; not removed without --owner {name} or --stale-days"


def git(*args: str, cwd: Path | None = None, check: bool = True) -> str:
    return subprocess.run(["git", "-C", str(cwd or ROOT), *args], check=check, capture_output=True, text=True).stdout


def worktree_entries() -> list[dict[str, str]]:
    entries, current = [], {}
    for line in git("worktree", "list", "--porcelain").splitlines() + [""]:
        if not line:
            if current:
                entries.append(current)
            current = {}
            continue
        key, _, value = line.partition(" ")
        current[key] = value or "true"
    return entries


def github_repo() -> str | None:
    url = git("remote", "get-url", "origin", check=False).strip()
    match = re.search(r"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$", url)
    return match.group(1) if match else None


def merged_pull_request(repo: str, head: str, branch: str) -> bool | None:
    """Whether a pull request from `branch` with exactly this head commit was merged into `main` of `repo`; None
    when GitHub cannot say. Tying it to the branch keeps a new worktree made from a merged head (on a branch of
    its own) from counting as that pull request's."""
    result = subprocess.run(["gh", "api", f"repos/{repo}/commits/{head}/pulls"], capture_output=True, text=True)
    if result.returncode != 0:
        return False if "No commit found" in result.stdout + result.stderr else None
    try:
        pulls = json.loads(result.stdout)
    except ValueError:
        return None
    return any(pull.get("merged_at") and pull.get("head", {}).get("sha") == head
               and pull.get("head", {}).get("ref") == branch
               and pull.get("base", {}).get("ref") == "main"
               and (pull.get("base", {}).get("repo") or {}).get("full_name", "").lower() == repo.lower()
               for pull in pulls)


PULL_REQUEST, IN_MAIN, UNMERGED = "pull request", "in main", "unmerged"


def merge_state(offline: bool) -> Callable[[str, str | None], str | None]:
    """How a head reached main (see `worktree_verdict`). Answers are remembered, so a recheck before removal does
    not ask GitHub again."""
    repo = None if offline else github_repo()
    known: dict[tuple[str, str | None], str | None] = {}

    def merged(head: str, branch: str | None) -> str | None:
        key = (head, branch)
        if key not in known:
            in_main = subprocess.run(["git", "-C", str(ROOT), "merge-base", "--is-ancestor", head, "origin/main"],
                                     capture_output=True).returncode == 0
            if branch is None:
                by_request = False  # a detached checkout belongs to no pull request
            else:
                by_request = None if offline or not repo else merged_pull_request(repo, head, branch)
            if by_request:
                known[key] = PULL_REQUEST
            elif in_main:
                known[key] = IN_MAIN
            elif offline or by_request is False:
                known[key] = UNMERGED
            else:
                known[key] = None
        return known[key]
    return merged


def worktrees(only: Iterable[str] | None = None) -> list[Worktree]:
    entries = worktree_entries()
    wanted = set(only) if only is not None else None
    trees = []
    for entry in entries[1:]:
        path = os.path.realpath(entry["worktree"])
        if under(str(ROOT), [path]) or not Path(path).is_dir() or (wanted is not None and path not in wanted):
            continue
        # Untracked and ignored files are listed whatever the user's status configuration says; a failed status
        # counts as uncommitted work, so the worktree is kept.
        listed = subprocess.run(["git", "-C", path, "status", "--porcelain", "--untracked-files=normal",
                                 "--ignored=traditional"], capture_output=True, text=True)
        status = listed.stdout.splitlines() if listed.returncode == 0 else ["?? (git status failed)"]
        admin = Path(git("rev-parse", "--absolute-git-dir", cwd=Path(path), check=False).strip() or path)
        stamps = [stamp.stat().st_mtime for stamp in (admin / "HEAD", admin / "index", admin / "logs" / "HEAD")
                  if stamp.exists()]
        trees.append(Worktree(
            path=path, head=entry.get("HEAD", ""), branch=entry.get("branch", "").removeprefix("refs/heads/") or None,
            locked="locked" in entry, dirty=any(not line.startswith("!!") for line in status),
            ignored=tuple(line[3:] for line in status if line.startswith("!!")),
            activity=max(stamps, default=0.0)))
    return trees


def used_paths() -> frozenset[str]:
    """Canonical working directories and open files of every process this user can inspect."""
    used = set()
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit():
            continue
        links = [proc / "cwd"]
        try:
            links += list((proc / "fd").iterdir())
        except OSError:
            pass
        for link in links:
            try:
                target = os.readlink(link).removesuffix(" (deleted)")
            except OSError:
                continue
            if target.startswith("/"):
                used.add(os.path.realpath(target))
    return frozenset(used)


def mount_points() -> tuple[str, ...]:
    """Host mount points, from /proc/self/mountinfo (field 5, with octal escapes decoded)."""
    points = []
    for line in Path("/proc/self/mountinfo").read_text().splitlines():
        fields = line.split()
        if len(fields) > 4:
            points.append(re.sub(r"\\([0-7]{3})", lambda m: chr(int(m.group(1), 8)), fields[4]))
    return tuple(points)


def containers() -> list[Container]:
    ids = subprocess.run(["docker", "ps", "-aq"], check=True, capture_output=True, text=True).stdout.split()
    if not ids:
        return []
    inspected = json.loads(subprocess.run(["docker", "inspect", *ids], check=True, capture_output=True,
                                          text=True).stdout)
    return [Container(id=item["Id"][:12], name=item["Name"].lstrip("/"), running=item["State"]["Running"],
                      labels=item["Config"].get("Labels") or {},
                      mounts=tuple(os.path.realpath(mount["Source"]) for mount in item.get("Mounts", [])
                                   if mount.get("Source")),
                      finished=finished_at(item["State"].get("FinishedAt", "")))
            for item in inspected]


def finished_at(stamp: str) -> float:
    """Docker's `FinishedAt` (RFC 3339 with nanoseconds, or the zero time) in seconds since the epoch; 0 if unset."""
    match = re.match(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.\d+)?(Z|[+-]\d\d:\d\d)$", stamp)
    if not match or stamp.startswith("0001-"):
        return 0.0
    zone = "+00:00" if match.group(2) == "Z" else match.group(2)
    return datetime.fromisoformat(match.group(1) + zone).timestamp()


def facts() -> tuple[Facts, list[Container]]:
    boxes = containers()
    return Facts(used=used_paths(), mounted=tuple(mount for box in boxes for mount in box.mounts),
                 mount_points=mount_points(), containers=frozenset(box.name for box in boxes)), boxes


def same_device(path: str, device: int) -> bool:
    try:
        return os.lstat(path).st_dev == device
    except OSError:
        return False


def scan(path: Path) -> tuple[float, bool]:
    """The newest modification time under `path` and whether it holds anything owned by another user. The walk
    does not follow symlinks or cross into other file systems."""
    me = os.getuid()
    top = path.lstat()
    newest, foreign = top.st_mtime, top.st_uid != me
    for base, dirs, files in os.walk(path):
        dirs[:] = [name for name in dirs if same_device(os.path.join(base, name), top.st_dev)]
        for name in dirs + files:
            try:
                info = os.lstat(os.path.join(base, name))
            except OSError:
                continue
            newest = max(newest, info.st_mtime)
            foreign = foreign or info.st_uid != me
    return newest, foreign


def run_dirs(data: Path, only: Iterable[str] | None = None) -> list[RunDir]:
    if not data.is_dir():
        return []
    wanted = set(only) if only is not None else None
    rows = []
    for entry in sorted(data.iterdir()):
        canonical = str(entry) if entry.is_symlink() else os.path.realpath(entry)
        if wanted is not None and canonical not in wanted:
            continue
        if entry.is_symlink():
            info = entry.lstat()
            rows.append(RunDir(canonical, True, info.st_mtime, info.st_uid != os.getuid()))
        else:
            newest, foreign = scan(entry)
            rows.append(RunDir(canonical, False, newest, foreign))
    return rows


def citations(data: Path) -> dict[str, str]:
    """Entry name of the data directory -> a tracked file that cites `<data dir>/<name>` (under any spelling of
    the data directory: as given, canonical or the `/tmp/scarab` default)."""
    spellings = {str(data).rstrip("/"), os.path.realpath(data), "/tmp/scarab"}
    pattern = "(" + "|".join(re.escape(spelling) for spelling in spellings) + r")/[A-Za-z0-9._+-]+"
    found = subprocess.run(["git", "-C", str(ROOT), "grep", "-I", "-o", "-E", pattern],
                           capture_output=True, text=True).stdout
    cited: dict[str, str] = {}
    for line in found.splitlines():
        source, _, path = line.partition(":")
        cited.setdefault(path.rsplit("/", 1)[1], source)
    return cited


def remove_worktree(tree: Worktree) -> str:
    git("worktree", "remove", tree.path)
    if tree.branch and git("rev-parse", "--verify", "--quiet", tree.branch, check=False).strip() == tree.head:
        git("branch", "-D", tree.branch)
        return f"removed, branch {tree.branch} deleted"
    return "removed"


def remove_container(box: Container) -> str:
    subprocess.run(["docker", "rm", box.id], check=True, capture_output=True)
    return "removed"


def remove_run_dir(entry: RunDir) -> str:
    path = Path(entry.path)
    if entry.symlink:
        path.unlink()
    else:
        shutil.rmtree(path)
    return "removed"


def assess(args: argparse.Namespace, merged: Callable[[str, str | None], str | None], cited: Mapping[str, str],
           only: Mapping[str, Iterable[str]] | None = None) -> list[tuple[bool, str, object, str]]:
    """`(remove, kind, resource, reason)` for every resource, from fresh facts. `only` limits each kind to the
    named resources, for the recheck just before removal."""
    now = time.time()
    state, boxes = facts()
    scope = Scope(frozenset(args.owner), args.stale_days)
    pick = (lambda kind: only.get(kind, ())) if only is not None else (lambda kind: None)
    rows: list[tuple[bool, str, object, str]] = []
    rows += [(*worktree_verdict(tree, state, merged, scope, now), "worktree", tree)
             for tree in worktrees(pick("worktree"))]
    chosen = pick("container")
    rows += [(*container_verdict(box, scope, now), "container", box) for box in boxes
             if chosen is None or box.id in chosen]
    rows += [(*run_dir_verdict(entry, state, cited, scope, now), "run dir", entry)
             for entry in run_dirs(args.data_dir, pick("run dir"))]
    return [(remove, kind, resource, reason) for remove, reason, kind, resource in rows]


def label(kind: str, resource: object) -> str:
    return resource.name if kind == "container" else resource.path


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--apply", action="store_true", help="remove what the dry run marks `remove`")
    parser.add_argument("--owner", action="append", default=[],
                        help="also remove this owner's run directory and stopped containers (repeatable)")
    parser.add_argument("--stale-days", type=int,
                        help="also remove anyone's run directories and stopped labelled containers untouched this long")
    parser.add_argument("--offline", action="store_true", help="do not ask GitHub whether a head was merged")
    parser.add_argument("--data-dir", type=Path,
                        default=Path(os.environ.get("SCARAB_DATA_DIR") or os.environ.get("CSL_DATA_DIR") or "/tmp/scarab"))
    args = parser.parse_args(argv)
    if not Path("/proc/self/mountinfo").exists():
        parser.error("needs /proc (Linux)")
    args.data_dir = Path(os.path.abspath(args.data_dir))

    merged = merge_state(args.offline)
    cited = citations(args.data_dir)
    rows = assess(args, merged, cited)
    for remove, kind, resource, reason in rows:
        if kind == "container" and reason == "not labelled as ours":
            continue
        print(f"{'remove' if remove else 'keep  '}  {kind:<9} {label(kind, resource)}  ({reason})")
    if not args.apply:
        print("dry run; pass --apply to remove what is marked `remove`")
        return 0

    actions = {"worktree": remove_worktree, "container": remove_container, "run dir": remove_run_dir}
    failures = 0
    for remove, kind, resource, _ in rows:
        if not remove:
            continue
        # Fresh facts for this one resource, immediately before its removal: anything that changed since the
        # report, including through an earlier removal, keeps it.
        key = resource.id if kind == "container" else resource.path
        fresh = assess(args, merged, citations(args.data_dir), {kind: [key]})
        if not fresh or not fresh[0][0]:
            print(f"{label(kind, resource)}: kept on recheck ({fresh[0][3] if fresh else 'gone'})")
            continue
        try:
            print(f"{label(kind, resource)}: {actions[kind](fresh[0][2])}")
        except (OSError, subprocess.CalledProcessError) as error:
            failures += 1
            print(f"{label(kind, resource)}: FAILED {error}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
