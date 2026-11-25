#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Publish the reports `run.py` wrote to the public reports repository, `KD5UJC-XYZ/scarab-conformance`, where the
node-diversity-conformance site reads them.

    python3 -I -B scripts/conformance/publish.py DIR_OR_FILES... [--dry-run | --apply] [--replace]
        [--repo-url URL] [--identity 'Name <email>'] [--inputs DIR]

The repository holds

  * `reports/<scenario>/scarab-YYYYMMDD-<scenario>[-<era>].json.gz`, every published report, with its
    `.provenance.json` beside it;
  * `latest/<scenario>[-<era>].json.gz` and its `.provenance.json`: the newest report of each scenario and era,
    which the site reads at a fixed URL (`https://raw.githubusercontent.com/KD5UJC-XYZ/scarab-conformance/main/latest/
    <scenario>[-<era>].json.gz`; it decompresses a `.gz` it downloads);
  * `README.md`, written by this script from what `latest/` holds: the scores of its reports as the site computes
    them (`score.py`), and their provenance.

`--dry-run`, the default, prints every action and runs none: no `gh` or `git` command, no file written. With
`--apply` it

  1. checks that each report's Scarab commit is on GitHub (`gh api`), so that the provenance resolves;
  2. fetches the site at its pinned commit (`pins.json`), whose schemas and data the checks and scores need, into
     `--inputs` unless a verified copy is there (as `run.py` does), or into a temporary directory of its own, and
     checks each report against it;
  3. clones the reports repository shallowly into that temporary directory (`--repo-url`; Git's own credentials,
     which the workflow gives through `GIT_SSH_COMMAND` and its deploy key);
  4. writes each report and its provenance under `reports/`, the very bytes it checked, refusing a dated file that
     is already there unless `--replace` is given; points `latest/` at a report unless a newer one is there; writes
     `README.md`;
  5. stages exactly those files, commits, with a message that names the Scarab commits, and pushes to `main`.

The clone is the script's own and is removed on normal exit and on exceptions. A failure before the push publishes
nothing; after a push failure, the remote's `main` is checked to determine whether it accepted the commit. If that
cannot be determined, the outcome is reported as unknown. Running it again starts from the remote's `main` and
refuses already-published reports unless `--replace` is given; identical files are never committed again.

Every file is read once, and those bytes are checked and, with `--apply`, written. A report and its provenance must
be a pair as `run.py` writes them (`evidence.py`: the provenance's shape, the file's name, size and sha256, the
report's scenario, era, time, commit and CPU against it, and, once the site is there, its schema and the site's
reading of it); the checkout must have had no uncommitted changes; and a report of timings (uplc-turbo) must have
been measured on its reference CPU (`scenarios.py`), wherever it was written. A file that fails any check refuses the
whole publication. These checks give consistency, not authenticity: they do not show that a measurement was made as
its provenance says.

A run directory's `summary.json` names the scenarios the run was asked for; each one with no report there, such as
uplc-turbo on a runner of another CPU, is printed as not published, and its `latest/` stays as it is.
"""
from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence

# A dry run writes nothing, not even the bytecode of the modules it imports.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from conformance import evidence, inputs, provenance, score  # noqa: E402
from conformance.scenarios import NAME, SCENARIOS, label, provenance_name  # noqa: E402
from conformance.schema import loads  # noqa: E402

SCARAB = "KD5UJC-XYZ/scarab"
REPOSITORY = "KD5UJC-XYZ/scarab-conformance"
BRANCH = "main"
REPOSITORY_URL = f"https://github.com/{REPOSITORY}.git"
RAW = f"https://raw.githubusercontent.com/{REPOSITORY}/{BRANCH}"
SITE = "https://pragma-org.github.io/node-diversity-conformance/"
IDENTITY = re.compile(r"^(?P<name>[^<>\n]+?) <(?P<email>[^<>\s]+)>$")
LABELS = {label(scenario.id, era): scenario.id for scenario in SCENARIOS.values() for era in scenario.eras}


class PublishError(ValueError):
    """A file, the reports repository or a command prevents confirmed publication."""


@dataclass(frozen=True)
class Report:
    """A report file and its provenance, with the bytes of each as they were read and checked."""
    path: Path
    provenance: Path
    commit: str
    data: bytes
    provenance_data: bytes
    record: dict

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


def policy(name: str, record: dict) -> list[str]:
    """What publication asks beyond a consistent pair (`evidence.problems`): a clean checkout, and a report of
    timings measured on its scenario's reference CPU. A CPU the provenance does not name is never the reference."""
    found = [] if record["scarab"]["dirty"] is False else ["it was written from a checkout with uncommitted changes"]
    reference = SCENARIOS[LABELS[NAME.match(name)["label"]]].reference_cpu
    model = provenance.cpu_model(record)
    if reference is not None and model != reference:
        found.append(f"it is a timing report measured on {model or 'a CPU its provenance does not name'}, not on the "
                     f"reference CPU {reference}")
    return found


def collect(paths: Sequence[Path], pinned: evidence.Pinned | None = None) -> list[Report]:
    """The reports among `paths` (directories are searched one level deep), each read once and checked with its
    provenance (`evidence.problems`, against the site when `pinned` is given) and by `policy`."""
    files = sorted({candidate for path in paths
                    for candidate in (path.glob("scarab-*.json.gz") if path.is_dir() else [path])})
    reports = []
    for path in files:
        if not path.is_file() or NAME.match(path.name) is None:
            raise PublishError(f"{path.name}: not a report name (scarab-YYYYMMDD-<scenario>[-<era>].json.gz)")
        beside = path.with_name(provenance_name(path.name))
        if not beside.is_file():
            raise PublishError(f"{path.name}: no {beside.name} beside it")
        data, provenance_data = path.read_bytes(), beside.read_bytes()
        try:
            record = loads(provenance_data.decode("utf-8"))
        except (UnicodeDecodeError, ValueError) as error:
            raise PublishError(f"{path.name}: its provenance is not standard JSON: {error}") from error
        found = evidence.problems(path.name, data, record, pinned)
        found += policy(path.name, record) if not found else []
        if found:
            raise PublishError(f"{path.name}: " + "; ".join(found))
        reports.append(Report(path, beside, record["scarab"]["commit"], data, provenance_data, record))
    if len({report.label for report in reports}) != len(reports):
        raise PublishError("two reports of the same scenario and era: publish one of them")
    return reports


def against_site(reports: list[Report], pinned: evidence.Pinned) -> None:
    """Refuse the publication unless every report is valid against the site's schema and readable by its scoring."""
    for report in reports:
        found = evidence.problems(report.path.name, report.data, report.record, pinned)
        if found:
            raise PublishError(f"{report.path.name}: " + "; ".join(found))


def unpublished(paths: Sequence[Path]) -> list[str]:
    """For each run directory among `paths`, the scenarios its `summary.json` says were asked for and have no report
    there, each with what the run said of it: their `latest/` is left as it is."""
    lines = []
    for path in paths:
        summary = path / "summary.json"
        try:
            run = loads(summary.read_text(encoding="utf-8")) if path.is_dir() and summary.is_file() else None
        except (OSError, UnicodeDecodeError, ValueError):
            run = None
        if not isinstance(run, dict) or not isinstance(run.get("outcomes"), list):
            continue
        for item in run["outcomes"]:
            if isinstance(item, dict) and item.get("status") != "report":
                what = label(str(item.get("scenario")), item.get("era") if isinstance(item.get("era"), str) else None)
                lines.append(f"not published: {what} ({item.get('status')}: {item.get('detail', 'no detail')}); "
                             f"latest/{what} is left as it is")
    return lines


@dataclass(frozen=True)
class Placement:
    """Where a report goes in the reports repository, and whether it becomes the latest of its scenario."""
    report: Report
    latest: bool


def latest_date(clone: Path, report: Report) -> str | None:
    """The date of the report `latest/` holds for the scenario and era of `report`, from its provenance."""
    record = clone / provenance_name(report.latest)
    if not record.is_file():
        return None
    match = NAME.match(str(json.loads(record.read_text(encoding="utf-8")).get("report", "")))
    return match.group("date") if match else None


def place(reports: list[Report], clone: Path, replace: bool) -> list[Placement]:
    """Each report's placement in the clone. A dated file already there is refused unless `replace`; `latest/`
    moves to a report unless a newer one is there."""
    there = [report.dated for report in reports if (clone / report.dated).exists()]
    if there and not replace:
        raise PublishError(f"already published: {', '.join(there)} (--replace to replace them)")
    return [Placement(report, (latest_date(clone, report) or "") <= report.date) for report in reports]


def readme(clone: Path, site: score.Site) -> str:
    """The reports repository's README, from what `latest/` holds: the scores of its reports by the rules of the
    site, whose data is `site`, and their provenance."""
    rows = []
    for record_path in sorted((clone / "latest").glob("*.provenance.json")):
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
also writes this file: do not edit it by hand. Scarab's Plutus evaluator is its own (`uplc`).

- `latest/<scenario>[-<era>].json.gz`: the newest report of each scenario and era, which the site reads from
  `{RAW}/latest/`, with its `.provenance.json` beside it.
- `reports/<scenario>/scarab-YYYYMMDD-<scenario>[-<era>].json.gz`: every published report, dated (UTC) by its
  `generated_at`, with its `.provenance.json`: the Scarab commit, the pinned inputs and their checksums, the tool, its
  arguments, where it ran (the runner, and the CPU and limits of its container) and the checks the report passed.

{score.markdown(score.results(clone / "latest", site))}
## Reports in `latest/`

{table}
"""


def stage(data: bytes, destination: Path) -> None:
    """Write `data`, the bytes that were checked, to `destination` in the clone."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)


def lay_out(placements: list[Placement], clone: Path, site: score.Site) -> list[str]:
    """Write the reports into the clone and its README; the paths written, relative to the clone."""
    written = []
    for placement in placements:
        report = placement.report
        targets = [(report.data, report.dated), (report.provenance_data, provenance_name(report.dated))]
        if placement.latest:
            targets += [(report.data, report.latest), (report.provenance_data, provenance_name(report.latest))]
        for source, target in targets:
            stage(source, clone / target)
            written.append(target)
    (clone / "README.md").write_text(readme(clone, site), encoding="utf-8")
    return written + ["README.md"]


def site_pin() -> inputs.Pin:
    """The pinned site, which must be the commit whose scoring `score.py` mirrors."""
    pin = inputs.load_pins()["site"]
    if pin.spec["commit"] != score.SITE_COMMIT:
        raise PublishError(f"pins.json pins the site at {pin.spec['commit']}, but score.py scores as it does at "
                           f"{score.SITE_COMMIT}: read the site's scoring at the new commit and update score.py")
    return pin


def scoring_site(root: Path) -> evidence.Pinned:
    """The site's checkout at the pinned commit under `root`, fetched there unless a verified copy is there."""
    pin = site_pin()
    try:
        return evidence.Pinned.read(inputs.fetch(pin, root))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise PublishError(f"the site at {pin.spec['commit'][:9]} could not be fetched or read: {error}; nothing "
                           "was published") from error


def verified_site(root: Path | None) -> evidence.Pinned | None:
    """The pinned site already verified under `root`, read without fetching anything; None when it is not there."""
    target = inputs.verified(site_pin(), root) if root is not None else None
    try:
        return evidence.Pinned.read(target) if target is not None else None
    except (OSError, ValueError) as error:
        raise PublishError(f"the verified site under {root} cannot be read: {error}") from error


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


def git_commands(clone: Path, reports: list[Report], identity: str | None,
                 paths: Sequence[str]) -> dict[str, list[str]]:
    """The Git commands of a publication in `clone`. `paths` are the files it wrote, which alone are staged."""
    git = ["git", "-C", str(clone)]
    return {
        "add": git + ["add", "--", *paths],
        "staged": git + ["diff", "--cached", "--quiet"],
        "commit": git + identity_flags(identity) + ["commit", "--quiet", "-m", message(reports)],
        "push": git + ["push", "--quiet", "origin", f"HEAD:refs/heads/{BRANCH}"],
    }


Run = Callable[[Sequence[str]], subprocess.CompletedProcess]


def execute(argv: Sequence[str]) -> subprocess.CompletedProcess:
    return subprocess.run(list(argv), text=True, capture_output=True)


def dry_run(reports: list[Report], url: str, replace: bool, identity: str | None, root: Path | None,
            checked_against_site: bool) -> None:
    """Print every action of `--apply`, running none; the files themselves were read and checked."""
    print(f"# dry run: nothing is fetched, cloned, written, committed or pushed. The {len(reports)} report(s) were "
          "checked" + (" against the verified site too" if checked_against_site else
                       "; their check against the site's schema and scoring waits for its checkout") + ". With --apply:")
    for commit in sorted({report.commit for report in reports}):
        print(shlex.join(commit_check(commit)))
    pin = site_pin()
    target = (root or Path("<temporary directory>")) / pin.directory
    print(f"# fetch the site at {pin.spec['commit']} into {target}, unless a verified copy is there, to check the "
          "reports against it and score latest/:")
    for command in inputs.git_commands(pin, target):
        print(shlex.join(command))
    clone = Path("<temporary clone>")
    print(shlex.join(clone_command(url, clone)))
    written = []
    for report in reports:
        print(f"# write {report.path}'s checked bytes -> {report.dated}, and its provenance's "
              f"({'replacing one already there' if replace else 'refused if one is already there'})")
        print(f"# write them -> {report.latest}, and its provenance's, unless a newer report is there")
        written += [report.dated, provenance_name(report.dated), report.latest, provenance_name(report.latest)]
    print("# write README.md from latest/: the scores of its reports by the site's rules, and their provenance")
    commands = git_commands(clone, reports, identity, [*written, "README.md"])
    for step in ("add", "commit", "push"):
        print(shlex.join(commands[step]))
    print("# then delete the temporary clone, also when a step fails")


def git(run: Run, command: list[str], step: str) -> str:
    done = run(command)
    if done.returncode != 0:
        raise PublishError(f"git {step} failed: {done.stderr.strip()}")
    return done.stdout.strip()


def apply(reports: list[Report], url: str, replace: bool, identity: str | None, run: Run,
          root: Path | None) -> None:
    for commit in sorted({report.commit for report in reports}):
        found = run(commit_check(commit))
        if found.returncode != 0 or found.stdout.strip() != commit:
            raise PublishError(f"Scarab commit {commit} is not on GitHub: push it before publishing its reports")
    with tempfile.TemporaryDirectory(prefix="scarab-conformance-") as scratch:
        pinned = scoring_site(root or Path(scratch) / "inputs")
        against_site(reports, pinned)
        site = pinned.scoring
        clone = Path(scratch) / "clone"
        git(run, clone_command(url, clone), "clone")
        try:
            head = ["git", "-C", str(clone), "rev-parse", "HEAD"]
            starting = git(run, head, "rev-parse")
            placements = place(reports, clone, replace)
            commands = git_commands(clone, reports, identity, lay_out(placements, clone, site))
            git(run, commands["add"], "add")
            if run(commands["staged"]).returncode == 0:
                print("already published: the remote's main holds these files; nothing committed")
                return
            git(run, commands["commit"], "commit")
            committed = git(run, head, "rev-parse")
        except (PublishError, OSError, ValueError) as error:
            raise PublishError(f"{error}; nothing was published, and the temporary clone is deleted") from error
        try:
            git(run, commands["push"], "push")
        except (PublishError, OSError, ValueError) as error:
            recovery = (f"{error}; outcome unknown: check {url} refs/heads/{BRANCH}, then re-run; "
                        "the already-published check prevents publishing a report twice")
            try:
                remote = git(run, ["git", "-C", str(clone), "ls-remote", url, f"refs/heads/{BRANCH}"],
                             "ls-remote")
            except (PublishError, OSError, ValueError) as check_error:
                raise PublishError(f"{recovery}; {check_error}") from check_error
            if remote.split() == [starting, f"refs/heads/{BRANCH}"]:
                raise PublishError(f"{error}; nothing was published, and the temporary clone is deleted") from error
            if remote.split() != [committed, f"refs/heads/{BRANCH}"]:
                raise PublishError(recovery) from error
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
    parser.add_argument("--repo-url", default=REPOSITORY_URL, help="the reports repository to clone "
                        "(default: %(default)s)")
    parser.add_argument("--identity", help="the commit's author, 'Name <email>' (default: Git's configuration)")
    parser.add_argument("--inputs", type=Path, help="where the pinned site is fetched to score latest/, and reused "
                        "once verified, as run.py's --inputs (default: a temporary directory)")
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)
    try:
        identity_flags(args.identity)
        root = args.inputs.resolve() if args.inputs else None
        pinned = verified_site(root)
        reports = collect(args.paths, pinned)
        for line in unpublished(args.paths):
            print(line)
        if not reports:
            print("no report to publish")
            return 0
        if args.apply:
            apply(reports, args.repo_url, args.replace, args.identity, run, root)
        else:
            dry_run(reports, args.repo_url, args.replace, args.identity, root, pinned is not None)
    except (PublishError, OSError, json.JSONDecodeError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
