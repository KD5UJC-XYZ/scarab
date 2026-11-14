#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Write Scarab's reports for pragma-org's node-diversity-conformance site, ready for `publish.py`.

    python3 -I scripts/conformance/run.py SCENARIO [SCENARIO ...] --out DIR [options]

For the scenarios given (ledger-rules, uplc-conformance, uplc-turbo, cbor-decoding) it

  1. builds the `tools` module in the JDK image (`docker/jdk.Dockerfile`; never java or sbt on the host) and exports
     its runtime classpath. A scenario whose tool is not in this checkout is skipped, before anything is fetched;
  2. fetches the scenario's pinned inputs (`pins.json`) into a directory of their own (`--inputs`) and verifies them;
  3. runs the tool in a fresh container of the image: no network, the checkout, the dependency cache and the inputs
     read-only, only its own work directory writable. The Plutus scenarios use Scarab's own evaluator;
  4. checks the report against the scenario's `schema.json` at the pinned site commit, and that it covers the
     corpus (every fixture or vector has a result); with `implementation_commit` in the schema, it must be this
     checkout's commit;
  5. writes `scarab-YYYYMMDD-<scenario>[-<era>].json.gz`, dated by the report's `generated_at` in UTC, and beside it
     `...provenance.json`: the Scarab commit, the inputs and their pins, the tool and its arguments, the image, the
     checks and the time each phase took. No host name, user or host path is recorded.

`summary.json` in `--out` lists what each scenario gave: a report, a skip and why, or a failure and why. The exit
status is 1 if a scenario failed, 2 for a usage error. Nothing is published: that is `publish.py`'s, on demand.
`--dry-run` prints the plan (commands, inputs and report names) without building, fetching or running anything.

uplc-turbo is a timing benchmark: it runs on a quiet machine with a fast core, never on CI (refused when
`GITHUB_ACTIONS` is set). docs/running/conformance.md has the details.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shlex
import subprocess
import sys
import time
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from conformance import inputs as fetching  # noqa: E402
from conformance.scenarios import (SCENARIOS, Context, Scenario, compress, label, provenance_name,  # noqa: E402
                                   report_date, report_name)
from conformance.schema import UnsupportedSchema, validate  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "KD5UJC-XYZ/scarab"
LABEL = "xyz.kd5ujc.scarab"
OWNER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$")
CLASSPATH_LINE = re.compile(r"^/work/\S+$")


class RunError(RuntimeError):
    """A step of the run failed; the message says which and why."""


@dataclass(frozen=True)
class Checkout:
    root: Path
    commit: str
    dirty: bool
    common: Path
    cache: Path


@dataclass
class Outcome:
    scenario: str
    era: str | None
    status: str
    detail: str = ""
    report: str | None = None
    entries: int | None = None
    checks: list[str] = field(default_factory=list)

    def summary(self) -> dict:
        return {key: value for key, value in self.__dict__.items() if value not in (None, "", [])}


def git(root: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(root), *args], check=True, text=True, capture_output=True).stdout.strip()


def checkout_facts(root: Path) -> Checkout:
    sys.path.insert(0, str(root / "scripts"))
    from devnet.util import cache_dir

    commit = git(root, "rev-parse", "HEAD")
    dirty = bool(git(root, "status", "--porcelain"))
    common = Path(git(root, "rev-parse", "--path-format=absolute", "--git-common-dir"))
    return Checkout(root, commit, dirty, common, Path(cache_dir(root)))


def labels(kind: str, owner: str) -> list[str]:
    return ["--label", f"{LABEL}.kind={kind}", "--label", f"{LABEL}.owner={owner}"]


def build_command(checkout: Checkout, image: str, owner: str) -> list[str]:
    """Compile `tools` and print its runtime classpath, in the image, as CONTRIBUTING's Docker build does."""
    common = [] if checkout.common == checkout.root / ".git" else ["-v", f"{checkout.common}:{checkout.common}:ro"]
    return ["docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}", *labels("sbt", owner),
            "-v", f"{checkout.root}:/work", "-v", f"{checkout.cache}:/work/.cache", *common,
            "-e", "HOME=/work/.cache/home", "-e", "SBT_GLOBAL_BASE=/tmp/sbt-global", "-w", "/work", image,
            "bash", "scripts/sbtw", "tools/compile", "export tools/Runtime/fullClasspath"]


def parse_classpath(output: str) -> list[str]:
    """The classpath `export` printed: the last line that is a path list under `/work`."""
    lines = [line.strip() for line in output.splitlines() if CLASSPATH_LINE.match(line.strip())]
    if not lines:
        raise RunError("the build printed no tools classpath")
    entries = lines[-1].split(":")
    if not all(entry.startswith("/work/") for entry in entries):
        raise RunError("the tools classpath names a path outside /work")
    return entries


def host_path(entry: str, checkout: Checkout) -> Path:
    if entry.startswith("/work/.cache/"):
        return checkout.cache / entry.removeprefix("/work/.cache/")
    return checkout.root / entry.removeprefix("/work/")


def has_class(classpath: list[str], main: str, checkout: Checkout) -> bool:
    """Whether the main class is on the exported classpath, in a directory or a jar."""
    member = main.replace(".", "/") + ".class"
    for entry in classpath:
        path = host_path(entry, checkout)
        if path.is_dir() and (path / member).is_file():
            return True
        if path.is_file() and path.suffix == ".jar":
            with zipfile.ZipFile(path) as jar:
                if member in jar.namelist():
                    return True
    return False


def tool_command(scenario: Scenario, era: str | None, checkout: Checkout, context: Context, classpath: str,
                 inputs_root: Path, work: Path, image: str, owner: str, cpus: str, memory: str) -> list[str]:
    """The tool in a container of its own: no network, everything read-only but its work directory."""
    return ["docker", "run", "--rm", "--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
            "--cpus", cpus, "--memory", memory, *labels("conformance", owner),
            "-v", f"{checkout.root}:/work:ro", "-v", f"{checkout.cache}:/work/.cache:ro",
            "-v", f"{inputs_root}:/inputs:ro", "-v", f"{work}:/out", "-e", "HOME=/tmp", "-w", "/work", image,
            "java", *scenario.jvm, "-cp", classpath, scenario.main, *scenario.arguments(context, era, "/out")]


def environment(image: str, owner: str) -> dict:
    def ask(argv: list[str]) -> str | None:
        done = subprocess.run(argv, text=True, capture_output=True)
        text = (done.stdout or done.stderr).strip()
        return text.splitlines()[0] if done.returncode == 0 and text else None

    return {
        "image": image,
        "image_id": ask(["docker", "image", "inspect", "--format", "{{.Id}}", image]),
        "java": ask(["docker", "run", "--rm", "--network", "none", *labels("conformance", owner), image,
                      "java", "-version"]),
        "docker": ask(["docker", "version", "--format", "{{.Server.Version}}"]),
        "python": platform.python_version(),
        "runner": "github-actions" if os.environ.get("GITHUB_ACTIONS") == "true" else "local",
    }


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def now() -> datetime:
    return datetime.now(timezone.utc)


def stamp(moment: datetime) -> str:
    return moment.isoformat(timespec="seconds").replace("+00:00", "Z")


def finish(scenario: Scenario, era: str | None, raw: Path, context: Context, out: Path, record: dict) -> Outcome:
    """Check the tool's report, then name, compress and record it; a failed check names no report."""
    outcome = Outcome(scenario.id, era, "failed")
    report = json.loads(raw.read_text(encoding="utf-8"))
    schema_path = context.host["site"] / scenario.schema
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    errors = validate(report, schema)
    checks = scenario.checks(report, context, era) if isinstance(report, dict) else ["the report is not an object"]
    if isinstance(report, dict) and "implementation_commit" in schema.get("properties", {}):
        if report.get("implementation_commit") != context.commit:
            checks.append(f"implementation_commit is {report.get('implementation_commit')!r}, "
                          f"not this checkout's {context.commit}")
    outcome.entries = scenario.entries(report) if isinstance(report, dict) else None
    outcome.checks = errors[:20] + checks
    record["validation"] = {
        "schema": scenario.schema, "site_commit": context.pins["site"].spec["commit"],
        "schema_errors": len(errors), "checks": checks, "entries": outcome.entries,
        "valid": not errors and not checks,
    }
    if errors or checks:
        outcome.detail = f"{len(errors)} schema errors, {len(checks)} failed checks"
        return outcome
    data = raw.read_bytes()
    name = report_name(report_date(report.get("generated_at"), now()), scenario.id, era)
    target = out / name
    compress(data, target)
    compressed = target.read_bytes()
    record.update({"report": name, "sha256": sha256_bytes(compressed), "bytes": len(compressed),
                   "json_sha256": sha256_bytes(data), "generated_at": report.get("generated_at")})
    (out / provenance_name(name)).write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    outcome.status, outcome.report = "report", name
    return outcome


def plan(args: argparse.Namespace, checkout: Checkout, selected: list[Scenario], pins: dict) -> None:
    print(f"# dry run: nothing is built, fetched or run; Scarab {checkout.commit}"
          + (" (uncommitted changes)" if checkout.dirty else ""))
    if args.build_image:
        print(shlex.join(["docker", "build", "-f", "docker/jdk.Dockerfile", "-t", args.image, str(checkout.root)]))
    print(shlex.join(build_command(checkout, args.image, args.owner)))
    mounted = {name: f"/inputs/{pin.directory}" for name, pin in pins.items()}
    context = Context(checkout.commit, pins, {}, mounted)
    for scenario in selected:
        if not (checkout.root / scenario.source).is_file():
            print(f"# {scenario.id}: skipped, {scenario.main} is not in this checkout")
            continue
        for name in scenario.inputs:
            pin = pins[name]
            where = pin.spec.get("url") or f"https://github.com/{pin.spec['repository']} at {pin.spec['commit']}"
            print(f"# {scenario.id}: input {name} from {where} into {args.inputs / pin.directory}")
        for era in scenario.eras:
            work = args.out / "work" / label(scenario.id, era)
            print(shlex.join(tool_command(scenario, era, checkout, context, "<classpath>", args.inputs, work,
                                          args.image, args.owner, args.cpus, args.memory)))
            print(f"# writes {args.out}/scarab-<date>-{label(scenario.id, era)}.json.gz and its .provenance.json")


def run_one(args: argparse.Namespace, checkout: Checkout, scenario: Scenario, pins: dict, classpath: list[str],
            env: dict, build_seconds: float) -> list[Outcome]:
    """Fetch a scenario's inputs, run its tool once per era and finish each report."""
    started = now()
    clock = time.monotonic()
    host = {name: fetching.fetch(pins[name], args.inputs) for name in scenario.inputs}
    mounted = {name: f"/inputs/{pins[name].directory}" for name in scenario.inputs}
    context = Context(checkout.commit, pins, host, mounted)
    fetch_seconds = time.monotonic() - clock
    outcomes = []
    for era in scenario.eras:
        work = args.out / "work" / label(scenario.id, era)
        work.mkdir(parents=True, exist_ok=True)
        command = tool_command(scenario, era, checkout, context, ":".join(classpath), args.inputs, work,
                               args.image, args.owner, args.cpus, args.memory)
        print(f"running {label(scenario.id, era)}", file=sys.stderr)
        clock = time.monotonic()
        tool = subprocess.run(command, text=True, capture_output=True)
        run_seconds = time.monotonic() - clock
        (work / "tool.log").write_text(tool.stdout + tool.stderr, encoding="utf-8")
        raw = work / (f"report-{era}.json" if era else "report.json")
        if tool.returncode != 0 or not raw.is_file():
            outcomes.append(Outcome(scenario.id, era, "failed",
                                    f"the tool exited {tool.returncode}; see work/{label(scenario.id, era)}/tool.log"))
            continue
        record = {
            "schemaVersion": 1, "scenario": scenario.id, "era": era,
            "scarab": {"repository": os.environ.get("GITHUB_REPOSITORY", REPOSITORY),
                       "commit": checkout.commit, "dirty": checkout.dirty},
            "tool": {"main": scenario.main, "arguments": scenario.arguments(context, era, "/out"),
                     "jvm": list(scenario.jvm), "plutus_vm": scenario.plutus_vm, "exit_status": tool.returncode},
            "inputs": [pins[name].record() for name in scenario.inputs],
            "environment": env,
            "timings_seconds": {"build": round(build_seconds, 1), "fetch": round(fetch_seconds, 1),
                                "run": round(run_seconds, 1)},
            "started_at": stamp(started),
            "finished_at": stamp(now()),
        }
        clock = time.monotonic()
        outcome = finish(scenario, era, raw, context, args.out, record)
        outcome.detail = outcome.detail or f"run {run_seconds:.0f} s, checked in {time.monotonic() - clock:.1f} s"
        outcomes.append(outcome)
    return outcomes


def run_scenarios(args: argparse.Namespace, checkout: Checkout, selected: list[Scenario], pins: dict) -> list[Outcome]:
    outcomes: list[Outcome] = []
    runnable = []
    for scenario in selected:
        if (checkout.root / scenario.source).is_file():
            runnable.append(scenario)
        else:
            outcomes += [Outcome(scenario.id, era, "skipped", f"{scenario.main} is not in this checkout")
                         for era in scenario.eras]
    if not runnable:
        return outcomes
    clock = time.monotonic()
    try:
        if args.build_image or subprocess.run(["docker", "image", "inspect", args.image],
                                              capture_output=True).returncode != 0:
            subprocess.run(["docker", "build", "-f", "docker/jdk.Dockerfile", "-t", args.image, str(checkout.root)],
                           cwd=checkout.root, check=True)
        print(f"building tools at {checkout.commit} in {args.image}", file=sys.stderr)
        done = subprocess.run(build_command(checkout, args.image, args.owner), text=True, capture_output=True)
        (args.out / "work").mkdir(parents=True, exist_ok=True)
        (args.out / "work" / "build.log").write_text(done.stdout + done.stderr, encoding="utf-8")
        if done.returncode != 0:
            raise RunError(f"the tools build failed (exit {done.returncode}); see work/build.log")
        classpath = parse_classpath(done.stdout)
    except (RunError, subprocess.CalledProcessError, OSError) as error:
        return outcomes + [Outcome(scenario.id, era, "failed", str(error))
                           for scenario in runnable for era in scenario.eras]
    build_seconds = time.monotonic() - clock
    env = environment(args.image, args.owner)
    for scenario in runnable:
        if not has_class(classpath, scenario.main, checkout):
            outcomes += [Outcome(scenario.id, era, "skipped", f"{scenario.main} is not on the tools classpath")
                         for era in scenario.eras]
            continue
        try:
            outcomes += run_one(args, checkout, scenario, pins, classpath, env, build_seconds)
        except (RunError, fetching.InputError, UnsupportedSchema, ValueError, subprocess.CalledProcessError,
                OSError) as error:
            outcomes += [Outcome(scenario.id, era, "failed", f"{type(error).__name__}: {error}")
                         for era in scenario.eras]
    return outcomes


def arguments(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scenarios", nargs="+", choices=sorted(SCENARIOS), metavar="SCENARIO",
                        help=f"one or more of {', '.join(sorted(SCENARIOS))}")
    parser.add_argument("--out", type=Path, required=True, help="where the reports, provenance and summary go")
    parser.add_argument("--inputs", type=Path, help="where the fetched inputs go (default: OUT/inputs)")
    parser.add_argument("--image", default="scarab-jdk:local", help="the JDK image (default: %(default)s)")
    parser.add_argument("--build-image", action="store_true", help="build the image even if it exists")
    parser.add_argument("--owner", default="scarab-conformance", help="owner label of the containers")
    parser.add_argument("--cpus", default="3", help="CPUs of each tool container (default: %(default)s)")
    parser.add_argument("--memory", default="8g", help="memory of each tool container (default: %(default)s)")
    parser.add_argument("--allow-dirty", action="store_true",
                        help="run with uncommitted changes (recorded; publish.py refuses such reports)")
    parser.add_argument("--dry-run", action="store_true", help="print the plan, run nothing")
    args = parser.parse_args(argv)
    if not OWNER.match(args.owner):
        parser.error(f"--owner must match {OWNER.pattern}")
    args.out = args.out.resolve()
    args.inputs = (args.inputs or args.out / "inputs").resolve()
    return args


def main(argv: list[str] | None = None) -> int:
    args = arguments(sys.argv[1:] if argv is None else argv)
    selected = [SCENARIOS[name] for name in dict.fromkeys(args.scenarios)]
    if os.environ.get("GITHUB_ACTIONS") == "true":
        refused = [scenario.id for scenario in selected if not scenario.in_ci]
        if refused:
            print(f"{', '.join(refused)}: timing scenarios run on a quiet local machine, never on CI",
                  file=sys.stderr)
            return 2
    pins = fetching.load_pins()
    checkout = checkout_facts(ROOT)
    if args.dry_run:
        plan(args, checkout, selected, pins)
        return 0
    if checkout.dirty and not args.allow_dirty:
        print("the checkout has uncommitted changes: a report names its commit, so commit first "
              "(or --allow-dirty for a report that is not published)", file=sys.stderr)
        return 2
    args.out.mkdir(parents=True, exist_ok=True)
    args.inputs.mkdir(parents=True, exist_ok=True)
    outcomes = run_scenarios(args, checkout, selected, pins)
    summary = {"scarab_commit": checkout.commit, "dirty": checkout.dirty,
               "outcomes": [outcome.summary() for outcome in outcomes]}
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    for outcome in outcomes:
        label = outcome.scenario + (f" {outcome.era}" if outcome.era else "")
        print(f"{outcome.status:8} {label}: {outcome.report or outcome.detail}"
              + (f" ({outcome.entries} entries)" if outcome.entries is not None else ""))
        for check in outcome.checks:
            print(f"         {check}")
    return 1 if any(outcome.status == "failed" for outcome in outcomes) else 0


if __name__ == "__main__":
    sys.exit(main())
