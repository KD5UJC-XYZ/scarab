#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Publish the reports `run.py` wrote to the `conformance-reports` pre-release, where the node-diversity-conformance
site finds them.

    python3 -I scripts/conformance/publish.py DIR_OR_FILES... [--dry-run | --apply] [--replace] [--repo OWNER/NAME]

`--dry-run`, the default, prints the `gh` commands it would run and runs none, not even a read. With `--apply` it

  1. reads the release (`gh release view`); if it is absent, creates it as a published pre-release that is never
     marked latest, with a body that says it holds generated conformance reports and is not a Scarab release. A
     release of that tag that is a draft or a full release is refused;
  2. checks that each report's Scarab commit is on GitHub, so that `implementation_commit` and the provenance
     resolve;
  3. uploads each report with its provenance (`gh release upload`). A file of the same name already in the release
     is refused unless `--replace` is given, which passes `--clobber`: a second report of the same day replaces
     the first, since the site picks one file per date.

Every file is checked first: its name is `scarab-YYYYMMDD-<scenario>[-<era>].json.gz`, its provenance beside it
names it with the same sha256, and records a passed validation of a committed checkout. The site picks, per
scenario, the newest dated file whose name matches the glob of Scarab's node entry, among a repository's first
100 releases.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shlex
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from conformance.scenarios import NAME, provenance_name  # noqa: E402

TAG = "conformance-reports"
REPOSITORY = "KD5UJC-XYZ/scarab"
TITLE = TAG
NOTES = """Generated conformance reports of Scarab for pragma-org's node-diversity-conformance site \
(https://pragma-org.github.io/node-diversity-conformance/). This is not a Scarab release: it holds no runnable \
artifact, and its tag marks no Scarab version.

Each report is named scarab-YYYYMMDD-<scenario>[-<era>].json.gz, with a .provenance.json beside it that names the \
Scarab commit, the pinned inputs and their checksums, the tool and the checks it passed. The Plutus scenarios report \
Scarab's own evaluator (--plutus-vm scarab). They are written by scripts/conformance/run.py and published on demand \
by scripts/conformance/publish.py (docs/running/conformance.md)."""


class PublishError(ValueError):
    """A file or the release is not fit to publish; nothing is uploaded."""


@dataclass(frozen=True)
class Report:
    path: Path
    provenance: Path
    commit: str


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def collect(paths: Sequence[Path]) -> list[Report]:
    """The reports among `paths` (directories are searched one level deep), each checked with its provenance."""
    files = sorted({candidate for path in paths
                    for candidate in (path.glob("scarab-*.json.gz") if path.is_dir() else [path])})
    reports = []
    for path in files:
        if not path.is_file() or NAME.match(path.name) is None:
            raise PublishError(f"{path.name}: not a report name (scarab-YYYYMMDD-<scenario>[-<era>].json.gz)")
        provenance = path.with_name(provenance_name(path.name))
        if not provenance.is_file():
            raise PublishError(f"{path.name}: no {provenance.name} beside it")
        record = json.loads(provenance.read_text(encoding="utf-8"))
        problems = []
        if record.get("report") != path.name:
            problems.append(f"its provenance is of {record.get('report')!r}")
        if record.get("sha256") != sha256(path):
            problems.append("its sha256 is not the one its provenance records")
        if record.get("validation", {}).get("valid") is not True:
            problems.append("its provenance records no passed validation")
        scarab = record.get("scarab", {})
        if scarab.get("dirty") is not False:
            problems.append("it was written from a checkout with uncommitted changes")
        commit = str(scarab.get("commit", ""))
        if len(commit) != 40 or any(c not in "0123456789abcdef" for c in commit):
            problems.append("its provenance names no Scarab commit")
        if problems:
            raise PublishError(f"{path.name}: " + "; ".join(problems))
        reports.append(Report(path, provenance, commit))
    names = [report.path.name for report in reports]
    if len({NAME.match(name).group("label") for name in names}) != len(names):
        raise PublishError("two reports of the same scenario and era: publish one of them")
    return reports


def view_command(repo: str) -> list[str]:
    return ["gh", "release", "view", TAG, "--repo", repo, "--json", "tagName,isDraft,isPrerelease,assets"]


def create_command(repo: str) -> list[str]:
    return ["gh", "release", "create", TAG, "--repo", repo, "--prerelease", "--latest=false",
            "--title", TITLE, "--notes", NOTES]


def commit_command(repo: str, commit: str) -> list[str]:
    return ["gh", "api", f"repos/{repo}/commits/{commit}", "--jq", ".sha"]


def upload_command(repo: str, reports: list[Report], replace: bool) -> list[str]:
    files = [str(path) for report in reports for path in (report.path, report.provenance)]
    return ["gh", "release", "upload", TAG, "--repo", repo, *(["--clobber"] if replace else []), *files]


Run = Callable[[Sequence[str]], subprocess.CompletedProcess]


def gh(argv: Sequence[str]) -> subprocess.CompletedProcess:
    return subprocess.run(list(argv), text=True, capture_output=True)


def dry_run(reports: list[Report], repo: str, replace: bool) -> None:
    print(f"# dry run: no gh command is run. With --apply, for {len(reports)} report(s):")
    print(shlex.join(view_command(repo)))
    print("# only if the release is absent:")
    print(shlex.join(create_command(repo)))
    for commit in sorted({report.commit for report in reports}):
        print(shlex.join(commit_command(repo, commit)))
    print(shlex.join(upload_command(repo, reports, replace)))


def apply(reports: list[Report], repo: str, replace: bool, run: Run) -> None:
    view = run(view_command(repo))
    if view.returncode != 0:
        if "release not found" not in (view.stderr or "").lower():
            raise PublishError(f"cannot read the release: {view.stderr.strip()}")
        existing: set[str] = set()
        created = run(create_command(repo))
        if created.returncode != 0:
            raise PublishError(f"cannot create the release: {created.stderr.strip()}")
        print(f"created the pre-release {TAG}")
    else:
        release = json.loads(view.stdout)
        if release.get("isDraft") or not release.get("isPrerelease"):
            raise PublishError(f"{TAG} exists but is not a published pre-release; it is left alone")
        existing = {asset.get("name") for asset in release.get("assets", [])}
    clashes = sorted(existing & {path.name for report in reports for path in (report.path, report.provenance)})
    if clashes and not replace:
        raise PublishError(f"already in the release: {', '.join(clashes)} (--replace to replace them)")
    for commit in sorted({report.commit for report in reports}):
        found = run(commit_command(repo, commit))
        if found.returncode != 0 or found.stdout.strip() != commit:
            raise PublishError(f"Scarab commit {commit} is not on GitHub: push it before publishing its reports")
    uploaded = run(upload_command(repo, reports, replace))
    if uploaded.returncode != 0:
        raise PublishError(f"the upload failed: {uploaded.stderr.strip()}")
    for report in reports:
        print(f"published {report.path.name}")


def main(argv: list[str] | None = None, run: Run = gh) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="+", type=Path, help="report files, or directories that hold them")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="print the commands and run none (the default)")
    mode.add_argument("--apply", action="store_true", help="run the commands: create the release if absent, upload")
    parser.add_argument("--replace", action="store_true", help="replace files of the same name in the release")
    parser.add_argument("--repo", default=REPOSITORY, help="the repository (default: %(default)s)")
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)
    try:
        reports = collect(args.paths)
        if not reports:
            print("no report to publish")
            return 0
        if args.apply:
            apply(reports, args.repo, args.replace, run)
        else:
            dry_run(reports, args.repo, args.replace)
    except (PublishError, OSError, json.JSONDecodeError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
