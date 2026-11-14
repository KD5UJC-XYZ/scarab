#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Publish the reports `run.py` wrote to the public reports repository, `KD5UJC-XYZ/scarab-conformance`, where the
node-diversity-conformance site reads them.

    python3 -I -B scripts/conformance/publish.py DIR_OR_FILES... [--dry-run | --apply] [--replace]
        [--checkout DIR | --repo-url URL] [--identity 'Name <email>']

The repository holds

  * `reports/<scenario>/scarab-YYYYMMDD-<scenario>[-<era>].json.gz`, every published report, with its
    `.provenance.json` beside it;
  * `latest/<scenario>[-<era>].json.gz` and its `.provenance.json`: the newest report of each scenario and era,
    which the site reads at a fixed URL (`https://raw.githubusercontent.com/KD5UJC-XYZ/scarab-conformance/main/latest/
    <scenario>[-<era>].json.gz`; it decompresses a `.gz` it downloads);
  * `README.md`, written by this script from what `latest/` holds.

`--dry-run`, the default, prints every action and runs none: no `gh` or `git` command, no file written. With
`--apply` it

  1. checks that each report's Scarab commit is on GitHub (`gh api`), so that the provenance resolves;
  2. clones the reports repository shallowly into a temporary directory (`--repo-url`, with the caller's own Git
     credentials), or uses the clone `--checkout` names (the workflow checks it out with its deploy key). The clone
     must be clean, nothing staged or untracked, and at the remote's `main`, so that what is pushed is this
     publication and nothing else;
  3. copies each report and its provenance under `reports/`, refusing a dated file that is already there unless
     `--replace` is given; points `latest/` at a report unless a newer one is there; writes `README.md`;
  4. stages exactly those files, commits, with a message that names the Scarab commits, and pushes to `main`.

If any step after the clone fails, a copy, the commit or the push, the clone is put back at the remote's `main`
(`git reset --hard`, and the files it wrote removed), so that running it again publishes from a clean start. When
the files are already those of the remote's `main`, nothing is committed and it says so.

Every file is checked first: its name is `scarab-YYYYMMDD-<scenario>[-<era>].json.gz`, its provenance beside it
names it with the same sha256, and records a passed validation of a checkout with no uncommitted changes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence

# A dry run writes nothing, not even the bytecode of the modules it imports.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from conformance.scenarios import NAME, SCENARIOS, label, provenance_name  # noqa: E402

SCARAB = "KD5UJC-XYZ/scarab"
REPOSITORY = "KD5UJC-XYZ/scarab-conformance"
BRANCH = "main"
REPOSITORY_URL = f"https://github.com/{REPOSITORY}.git"
RAW = f"https://raw.githubusercontent.com/{REPOSITORY}/{BRANCH}"
SITE = "https://pragma-org.github.io/node-diversity-conformance/"
IDENTITY = re.compile(r"^(?P<name>[^<>\n]+?) <(?P<email>[^<>\s]+)>$")
LABELS = {label(scenario.id, era): scenario.id for scenario in SCENARIOS.values() for era in scenario.eras}


class PublishError(ValueError):
    """A file, the reports repository or a command is not fit to publish with; nothing is pushed."""


@dataclass(frozen=True)
class Report:
    path: Path
    provenance: Path
    commit: str

    @property
    def label(self) -> str:
        """`<scenario>[-<era>]`."""
        return NAME.match(self.path.name).group("label")

    @property
    def date(self) -> str:
        return NAME.match(self.path.name).group("date")

    @property
    def dated(self) -> str:
        """Where the report is archived in the reports repository."""
        return f"reports/{LABELS[self.label]}/{self.path.name}"

    @property
    def latest(self) -> str:
        """The fixed path the site reads the newest report of this scenario and era from."""
        return f"latest/{self.label}.json.gz"


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
    if len({report.label for report in reports}) != len(reports):
        raise PublishError("two reports of the same scenario and era: publish one of them")
    return reports


@dataclass(frozen=True)
class Placement:
    """Where a report goes in the reports repository, and whether it becomes the latest of its scenario."""
    report: Report
    replaces: bool
    latest: bool


def latest_date(checkout: Path, report: Report) -> str | None:
    """The date of the report `latest/` holds for the scenario and era of `report`, from its provenance."""
    record = checkout / provenance_name(report.latest)
    if not record.is_file():
        return None
    match = NAME.match(str(json.loads(record.read_text(encoding="utf-8")).get("report", "")))
    return match.group("date") if match else None


def place(reports: list[Report], checkout: Path | None, replace: bool) -> list[Placement]:
    """Each report's placement against the clone (None when it is not known yet). A dated file already there is
    refused unless `replace`; `latest/` moves to a report unless a newer one is there."""
    if checkout is None:
        return [Placement(report, False, True) for report in reports]
    there = [report.dated for report in reports if (checkout / report.dated).exists()]
    if there and not replace:
        raise PublishError(f"already published: {', '.join(there)} (--replace to replace them)")
    return [Placement(report, report.dated in there,
                      (latest_date(checkout, report) or "") <= report.date) for report in reports]


def readme(checkout: Path) -> str:
    """The reports repository's README, from the provenance of what `latest/` holds."""
    rows = []
    for record_path in sorted((checkout / "latest").glob("*.provenance.json")):
        record = json.loads(record_path.read_text(encoding="utf-8"))
        name = record_path.name.removesuffix(".provenance.json")
        match = NAME.match(str(record.get("report", "")))
        date = f"{match.group('date')[:4]}-{match.group('date')[4:6]}-{match.group('date')[6:]}" if match else "?"
        commit = str(record.get("scarab", {}).get("commit", ""))
        rows.append(f"| [`{name}`](latest/{name}.json.gz) | {date} | "
                    f"[`{commit[:9]}`](https://github.com/{SCARAB}/commit/{commit}) | "
                    f"`{str(record.get('sha256', ''))[:16]}` |")
    table = "\n".join(["| Report | Date (UTC) | Scarab commit | sha256 of the file |", "|---|---|---|---|", *rows])
    return f"""# Scarab conformance reports

Generated reports of [Scarab](https://github.com/{SCARAB}), a Cardano node in Scala, for pragma-org's
[node-diversity-conformance]({SITE}) site. They are written by `scripts/conformance/run.py` and published on
demand by `scripts/conformance/publish.py` of the Scarab repository (`docs/running/conformance.md` there), which
also writes this file: do not edit it by hand. The Plutus scenarios report Scarab's own evaluator
(`--plutus-vm scarab`).

- `latest/<scenario>[-<era>].json.gz`: the newest report of each scenario and era, which the site reads from
  `{RAW}/latest/`, with its `.provenance.json` beside it.
- `reports/<scenario>/scarab-YYYYMMDD-<scenario>[-<era>].json.gz`: every published report, dated (UTC) by its
  `generated_at`, with its `.provenance.json`: the Scarab commit, the pinned inputs and their checksums, the tool, its
  arguments and the checks the report passed.

{table}
"""


def lay_out(placements: list[Placement], checkout: Path) -> list[str]:
    """Copy the reports into the clone and write its README; the paths written, relative to the clone."""
    written = []
    for placement in placements:
        report = placement.report
        targets = [(report.path, report.dated), (report.provenance, provenance_name(report.dated))]
        if placement.latest:
            targets += [(report.path, report.latest), (report.provenance, provenance_name(report.latest))]
        for source, target in targets:
            destination = checkout / target
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            written.append(target)
    (checkout / "README.md").write_text(readme(checkout), encoding="utf-8")
    return written + ["README.md"]


def message(reports: list[Report]) -> str:
    commits = sorted({report.commit for report in reports})
    labels = ", ".join(sorted(report.label for report in reports))
    lines = [f"Scarab reports at {', '.join(commit[:9] for commit in commits)}: {labels}", ""]
    lines += [f"Scarab commit {commit} (https://github.com/{SCARAB}/commit/{commit})" for commit in commits]
    lines += ["", "Reports:", *(f"- {report.dated}" for report in sorted(reports, key=lambda r: r.label)), "",
              "Written by scripts/conformance/run.py and published by scripts/conformance/publish.py."]
    return "\n".join(lines)


def identity_flags(identity: str | None) -> list[str]:
    if identity is None:
        return []
    match = IDENTITY.match(identity)
    if match is None:
        raise PublishError(f"--identity must be 'Name <email>', got {identity!r}")
    return ["-c", f"user.name={match.group('name')}", "-c", f"user.email={match.group('email')}"]


def commit_check(commit: str) -> list[str]:
    return ["gh", "api", f"repos/{SCARAB}/commits/{commit}", "--jq", ".sha"]


def clone_command(url: str, target: Path) -> list[str]:
    return ["git", "clone", "--quiet", "--depth", "1", "--no-tags", "--branch", BRANCH, url, str(target)]


# What publish.py writes in the reports repository, and so all it may reset or remove there.
OWNED = ("README.md", "latest", "reports")


def git_commands(checkout: Path, reports: list[Report], identity: str | None,
                 paths: Sequence[str] = OWNED) -> dict[str, list[str]]:
    """The Git commands of a publication in `checkout`. `paths` are the files it wrote, which alone are staged."""
    git = ["git", "-C", str(checkout)]
    return {
        "remote": git + ["ls-remote", "--exit-code", "origin", f"refs/heads/{BRANCH}"],
        "head": git + ["rev-parse", "HEAD"],
        "status": git + ["status", "--porcelain", "--untracked-files=all"],
        "add": git + ["add", "--", *paths],
        "staged": git + ["diff", "--cached", "--quiet"],
        "commit": git + identity_flags(identity) + ["commit", "--quiet", "-m", message(reports)],
        "push": git + ["push", "--quiet", "origin", f"HEAD:refs/heads/{BRANCH}"],
    }


def restore_commands(checkout: Path, start: str) -> list[list[str]]:
    """Put the clone back at `start`, the remote's `main` it began from: its commit, its index and the files
    publish.py owns. The clone was clean when the publication began, so nothing else is lost."""
    git = ["git", "-C", str(checkout)]
    return [git + ["reset", "--quiet", "--hard", start], git + ["clean", "-fdq", "--", *OWNED]]


Run = Callable[[Sequence[str]], subprocess.CompletedProcess]


def execute(argv: Sequence[str]) -> subprocess.CompletedProcess:
    return subprocess.run(list(argv), text=True, capture_output=True)


def dry_run(reports: list[Report], checkout: Path | None, url: str, replace: bool, identity: str | None) -> None:
    """Print every action of `--apply`, running none. With `--checkout`, its files are read to say what each
    placement does; nothing in it is written."""
    print(f"# dry run: nothing is checked, cloned, written, committed or pushed. With --apply, for {len(reports)} "
          "report(s):")
    for commit in sorted({report.commit for report in reports}):
        print(shlex.join(commit_check(commit)))
    where = checkout if checkout is not None else Path("<temporary clone>")
    if checkout is None:
        print(shlex.join(clone_command(url, where)))
    else:
        print(f"# uses the clone in {checkout}")
    for placement in place(reports, checkout, replace):
        report = placement.report
        state = ("replaces the file there" if placement.replaces else
                 "new" if checkout is not None else "refused if already there, unless --replace")
        print(f"# copy {report.path} -> {report.dated} ({state}), and its provenance")
        if checkout is None:
            print(f"# copy {report.path} -> {report.latest}, and its provenance, unless a newer report is there")
        elif placement.latest:
            print(f"# copy {report.path} -> {report.latest}, and its provenance")
        else:
            print(f"# {report.latest} keeps the newer report it holds")
    print("# write README.md from latest/")
    commands = git_commands(where, reports, identity)
    print("# the clone must be clean and at the remote's main:")
    for step in ("remote", "head", "status"):
        print(shlex.join(commands[step]))
    print("# stage exactly the files written, then:")
    for step in ("add", "commit", "push"):
        print(shlex.join(commands[step]))
    print("# on a failure after the clone: " + "; ".join(shlex.join(c) for c in restore_commands(where, "<main>")))


def git(run: Run, command: list[str], step: str) -> str:
    done = run(command)
    if done.returncode != 0:
        raise PublishError(f"git {step} failed: {done.stderr.strip()}")
    return done.stdout


def clean_start(checkout: Path, reports: list[Report], identity: str | None, run: Run) -> str:
    """The commit of the remote's `main`, once the clone is checked to be at it with nothing changed, staged or
    untracked: a publication pushes its own files and nothing left by anything else."""
    commands = git_commands(checkout, reports, identity)
    listed = git(run, commands["remote"], "ls-remote").split()
    head = git(run, commands["head"], "rev-parse").strip()
    if not listed or listed[0] != head:
        raise PublishError(f"the clone's HEAD ({head[:9]}) is not the remote's main "
                           f"({listed[0][:9] if listed else 'absent'}): use a fresh clone, or bring this one to "
                           f"the remote's main first (`git -C {checkout} fetch origin {BRANCH}` and "
                           f"`git -C {checkout} reset --hard FETCH_HEAD`)")
    changed = git(run, commands["status"], "status").strip()
    if changed:
        raise PublishError(f"the clone has changed, staged or untracked files: {changed.splitlines()[0]} ...; "
                           "use a fresh clone, or clean this one first")
    return head


def apply(reports: list[Report], checkout: Path | None, url: str, replace: bool, identity: str | None,
          run: Run) -> None:
    for commit in sorted({report.commit for report in reports}):
        found = run(commit_check(commit))
        if found.returncode != 0 or found.stdout.strip() != commit:
            raise PublishError(f"Scarab commit {commit} is not on GitHub: push it before publishing its reports")
    with tempfile.TemporaryDirectory(prefix="scarab-conformance-") as scratch:
        if checkout is None:
            checkout = Path(scratch) / "clone"
            cloned = run(clone_command(url, checkout))
            if cloned.returncode != 0:
                raise PublishError(f"cannot clone {url}: {cloned.stderr.strip()}")
        start = clean_start(checkout, reports, identity, run)
        placements = place(reports, checkout, replace)
        try:
            written = lay_out(placements, checkout)
            commands = git_commands(checkout, reports, identity, written)
            git(run, commands["add"], "add")
            if run(commands["staged"]).returncode == 0:
                print(f"already published: the remote's main ({start[:9]}) holds these files; nothing committed")
                return
            git(run, commands["commit"], "commit")
            git(run, commands["push"], "push")
        except (PublishError, OSError) as error:
            for command in restore_commands(checkout, start):
                run(command)
            raise PublishError(f"{error}; nothing was published, and the clone is back at the remote's main "
                               f"({start[:9]}): run it again once the cause is fixed") from error
    for placement in placements:
        print(f"published {placement.report.dated}"
              + (f" and {placement.report.latest}" if placement.latest else ""))


def main(argv: list[str] | None = None, run: Run = execute) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="+", type=Path, help="report files, or directories that hold them")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="print every action and run none (the default)")
    mode.add_argument("--apply", action="store_true", help="publish: copy, commit and push to the reports repository")
    parser.add_argument("--replace", action="store_true", help="replace a dated report already published")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--checkout", type=Path, help="an existing clone of the reports repository to publish from")
    source.add_argument("--repo-url", default=REPOSITORY_URL, help="the reports repository to clone "
                        "(default: %(default)s)")
    parser.add_argument("--identity", help="the commit's author, 'Name <email>' (default: Git's configuration)")
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)
    try:
        identity_flags(args.identity)
        if args.checkout is not None and not (args.checkout / ".git").exists():
            raise PublishError(f"{args.checkout} is not a clone of the reports repository")
        reports = collect(args.paths)
        if not reports:
            print("no report to publish")
            return 0
        if args.apply:
            apply(reports, args.checkout, args.repo_url, args.replace, args.identity, run)
        else:
            dry_run(reports, args.checkout, args.repo_url, args.replace, args.identity)
    except (PublishError, OSError, json.JSONDecodeError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
