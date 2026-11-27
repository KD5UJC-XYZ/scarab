#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the secret-path timing test (`SecretPathTiming`, tools) several times and decide whether it found a leak.

    python3 -I -B scripts/secret_timing.py --out DIR [--repetitions 3] [--measurements 100000] [options]
    python3 -I -B scripts/secret_timing.py --out DIR --reports RUN.json [RUN.json ...] [--allow-mixed]
    python3 -I -B scripts/secret_timing.py --out DIR --outcome STATUS

The first form

  1. builds the JDK image when asked (`--build-image`) or when it is absent (never java or sbt on the host), probes
     in it the CPU a tool container sees (the probe of `conformance/run.py`), then builds `tools` and exports its
     runtime classpath, as `conformance/run.py` does (or takes one already exported, `--classpath-file`). The build
     writes the checkout's `target/` directories and the dependency cache, and the image is Docker's;
  2. runs the tool `--repetitions` times, one after another, each in a fresh container of the image (no network,
     the checkout and the dependency cache read-only, only DIR writable) and a JVM of its own (`JVM`), and keeps
     each run's output (`run-N.log`) and its record (`run-N.json`): the tool's report, with the run's identity, the
     commit, the JVM, the CPU and the tool's exit status around it;
  3. decides (`decide`) and writes `summary.json`, `summary.md` (every target's statistic in every run, the CPU
     and the verdict) and `provenance.json` (the commit, the image, the JDK, the CPU and the limits, the time each
     run took; no host name, user or host path).

The second form decides from records already written, by this program, in this run or others (downloaded workflow
artifacts), and runs nothing. A record given twice, or two of different commits or sizes, is refused, unless
`--allow-mixed` lets commits and sizes differ; a record whose tool did not exit 0 is refused.

The third form is the workflow's last step: it checks that DIR holds the evidence the exit status STATUS needs (a
summary that gives that status, and for 0 or 2 every run's record), prints the job's annotation, and exits 0 for a
job that may be green and 1 for one that must be red.

The rule, recomputed here from each record's statistics with the roster `ROSTER` (the tool's flags must agree with
it, or the record is refused):

  - a run is blind when its variable-time control is not above the threshold: it could not see a leak that is
    there, and it votes neither way;
  - otherwise its floor is the largest of its noise controls' statistics (random/random, same seed) and its
    field-arithmetic control's; it is clean when the floor is not above the threshold, noisy when it is;
  - in a clean run a secret target counts when its statistic is above the threshold, dudect's 10; in a noisy run
    when it is above the threshold and above `FACTOR` times the floor, the rule `TimingProbeMain` gives a target
    against its noise control ("t > 10 && t > 5 * noise");
  - a leak is confirmed when one secret target counts in at least `CONFIRMATIONS` runs;
  - otherwise the result is inconclusive when any secret target's statistic is above the threshold in any run (the
    summary names it and its readings), or when fewer than `CONFIRMATIONS` runs are clean;
  - otherwise no leak was found.

The confirmation adapts the two independent experiments of Goodwill, Jun, Jaffe and Rohatgi ("A testing methodology
for side-channel resistance validation", NIST NIAT 2011): they reject a device when Welch's t exceeds the threshold at
the same point in time and in the same direction in two independent data sets, with 4.5 in their AES procedure.
Here the threshold is dudect's 10, each run's statistic is its largest over the crops, so two runs may confirm at
different crops, and the direction is not compared, since a target whose fixed secret is drawn once draws it anew
in each run. The CPU's model is recorded with every run, beside whether it is the reference CPU of the timing
scenarios (`TURBO_REFERENCE_CPU`); the verdict does not depend on it (docs/running/secret-timing.md).

Exit status: 0 no leak found, 1 a leak confirmed, 2 inconclusive (the devnet scenarios' convention), 3 the check
could not run (a build or a run failed, a record is not one or is refused, a file could not be written, the
arguments are wrong, or anything else went wrong in this program). Nothing is published.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import shlex
import subprocess
import sys
import time
import traceback
from dataclasses import dataclass
from pathlib import Path

# No bytecode in the checkout: `-I` ignores PYTHONDONTWRITEBYTECODE.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

from conformance import run as runner  # noqa: E402
from conformance.scenarios import TURBO_REFERENCE_CPU  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
MAIN = "xyz.kd5ujc.scarab.crypto.vrf.SecretPathTiming"
SOURCE = "tools/src/main/scala/xyz/kd5ujc/scarab/crypto/vrf/SecretPathTiming.scala"
# The tool's documented JVM (its Scaladoc): the serial collector and compilation in the foreground, so that no
# collector or compiler thread runs beside a timed call, and a fixed heap, so that its sizing does not move.
JVM = ("-XX:+UseSerialGC", "-Xbatch", "-Xms2g", "-Xmx2g")
# The tool's report version and its exact roster: every target, by id, with its role.
REPORT_VERSION = 2
ROSTER = {
    "vrf-prove": "secret",
    "vrf-output": "secret",
    "kes-sign": "secret",
    "kes-evolve-0-1": "secret",
    "kes-evolve-1-2": "secret",
    "kh-zero": "secret",
    "kb-zero": "secret",
    "kh-once": "secret",
    "kb-once": "secret",
    "control-random": "noise-control",
    "control-keys": "noise-control",
    "control-field": "value-control",
    "control-vartime": "leak-control",
}
# dudect's `t_threshold_moderate`, the tool's own threshold, which every report must carry.
THRESHOLD = 10.0
# TimingProbeMain's factor over the noise floor ("t > 10 && t > 5 * noise").
FACTOR = 5.0
# Runs in which one secret target must count for a leak to be confirmed (the rule above).
CONFIRMATIONS = 2
# Measurements each class of every target must hold, and the tool's crops' own minimum.
MIN_CLASS_SAMPLES = 1000
MAX_REPETITIONS = 10
# With a quarter as many, and at least 1,000, for a proof, output, signature or evolution, this many a
# multiplication gives each class of every target about 1,250.
MIN_MEASUREMENTS = 10000
# How long a stopped run's container may take to go.
REMOVE_TIMEOUT = 60
# The fields that must be the same in every record decided together, unless `--allow-mixed`.
SIZES = ("measurements", "proofs", "calibration_share", "pool", "crops", "min_crop_class")
NO_LEAK, LEAK, INCONCLUSIVE, ERROR = 0, 1, 2, 3
VERDICTS = {NO_LEAK: "no leak found", LEAK: "leak confirmed", INCONCLUSIVE: "inconclusive"}


class CheckError(RuntimeError):
    """The check could not run, or a record is not one; the message says which and why."""


@dataclass(frozen=True)
class Target:
    id: str
    name: str
    role: str
    statistic: float


@dataclass(frozen=True)
class Run:
    """One run of the tool: its record's identity and circumstances, and the statistics of its report."""
    path: str
    id: str
    commit: str
    dirty: bool
    jvm: tuple[str, ...]
    cpu: str | None
    sizes: tuple[tuple[str, int], ...]
    targets: tuple[Target, ...]

    def statistic(self, ident: str) -> float:
        return next(target.statistic for target in self.targets if target.id == ident)

    @property
    def blind(self) -> bool:
        return not self.statistic("control-vartime") > THRESHOLD

    @property
    def floor(self) -> float:
        return max(target.statistic for target in self.targets if target.role in ("noise-control", "value-control"))

    @property
    def clean(self) -> bool:
        return not self.blind and not self.floor > THRESHOLD

    @property
    def kind(self) -> str:
        return "blind" if self.blind else "clean" if self.clean else "noisy"

    def counts(self, target: Target) -> bool:
        """Whether a secret target's reading in this run counts towards a leak (the rule above)."""
        if self.blind or not target.statistic > THRESHOLD:
            return False
        return self.clean or target.statistic > FACTOR * self.floor

    def flaws(self) -> list[str]:
        """The controls of this run that read what they must not, as the tool names them."""
        found = []
        for target in self.targets:
            value = target.statistic
            if target.role in ("noise-control", "value-control") and value > THRESHOLD:
                found.append(f"{target.id} {_reading(value)}")
            if target.role == "leak-control" and not value > THRESHOLD:
                found.append(f"{target.id} {_reading(value)}, not above {THRESHOLD:g}")
        return found


@dataclass(frozen=True)
class Decision:
    status: int
    clean: int
    counted: dict[str, list[int]]
    above: dict[str, list[tuple[int, float]]]

    @property
    def verdict(self) -> str:
        return VERDICTS[self.status]

    @property
    def confirmed(self) -> list[str]:
        return sorted(ident for ident, runs in self.counted.items() if len(runs) >= CONFIRMATIONS)


def _reading(value: float) -> str:
    return "infinite" if math.isinf(value) else f"{value:,.1f}"


def _statistic(value: object) -> float | None:
    """A statistic as the tool writes it: a finite number, or `Infinity` as a string; None otherwise."""
    if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value):
        return float(value)
    if value in ("Infinity", "-Infinity"):
        return math.inf
    return None


def _count(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _target(entry: object, where: str) -> Target:
    if not isinstance(entry, dict):
        raise CheckError(f"{where}: a target is an object")
    ident, name, role = entry.get("id"), entry.get("name"), entry.get("role")
    if ident not in ROSTER or not isinstance(name, str):
        raise CheckError(f"{where}: {ident!r} is not a target of report version {REPORT_VERSION}")
    if role != ROSTER[ident]:
        raise CheckError(f"{where}: {ident} has the role {role!r}, not {ROSTER[ident]}")
    statistic = _statistic(entry.get("statistic"))
    if statistic is None:
        raise CheckError(f"{where}: {ident} has no statistic")
    for side in ("fixed", "random"):
        counts = entry.get(side)
        if not isinstance(counts, dict) or not _count(counts.get("n")):
            raise CheckError(f"{where}: {ident} has no count of its {side} class")
        if counts["n"] < MIN_CLASS_SAMPLES:
            raise CheckError(f"{where}: {ident}'s {side} class holds {counts['n']} measurements, fewer than "
                             f"{MIN_CLASS_SAMPLES}")
    if entry.get("over") is not (statistic > THRESHOLD):
        raise CheckError(f"{where}: {ident} reads {_reading(statistic)} but its report says `over` is "
                         f"{entry.get('over')!r}")
    return Target(ident, name, role, statistic)


def parse_record(text: str, path: str) -> Run:
    """A run's record as this program writes it, checked and recomputed; `CheckError` when it is not one, when the
    tool did not exit 0, or when the report's own flags disagree with its statistics."""
    try:
        record = json.loads(text)
    except ValueError as error:
        raise CheckError(f"{path}: not JSON: {error}") from None
    run = record.get("run") if isinstance(record, dict) else None
    report = record.get("report") if isinstance(record, dict) else None
    if not isinstance(run, dict) or not isinstance(report, dict):
        raise CheckError(f"{path}: not a record of secret_timing.py (a run and its report)")
    if run.get("exit_status") != 0:
        raise CheckError(f"{path}: the tool exited {run.get('exit_status')!r}, not 0")
    if not isinstance(run.get("commit"), str) or not isinstance(run.get("dirty"), bool) \
            or not isinstance(run.get("jvm"), list) or not all(isinstance(flag, str) for flag in run["jvm"]) \
            or not (run.get("cpu") is None or isinstance(run.get("cpu"), str)):
        raise CheckError(f"{path}: the run names no commit, JVM or CPU")
    if report.get("tool") != "SecretPathTiming" or report.get("version") != REPORT_VERSION:
        raise CheckError(f"{path}: not a version {REPORT_VERSION} report of SecretPathTiming")
    ident = report.get("run_id")
    if not isinstance(ident, str) or not ident or run.get("id") != ident:
        raise CheckError(f"{path}: the run's identity is missing or is not its report's")
    if report.get("probably") != THRESHOLD:
        raise CheckError(f"{path}: the report's threshold is {report.get('probably')!r}, not {THRESHOLD:g}")
    if not all(_count(report.get(key)) and report[key] > 0 for key in SIZES):
        raise CheckError(f"{path}: the report does not give its sizes ({', '.join(SIZES)})")
    if report["min_crop_class"] < MIN_CLASS_SAMPLES:
        raise CheckError(f"{path}: its crops count from {report['min_crop_class']} a class, fewer than "
                         f"{MIN_CLASS_SAMPLES}")
    entries = report.get("targets")
    if not isinstance(entries, list):
        raise CheckError(f"{path}: no targets")
    targets = tuple(_target(entry, path) for entry in entries)
    ids = [target.id for target in targets]
    if sorted(ids) != sorted(ROSTER):
        raise CheckError(f"{path}: its targets are not exactly the roster of version {REPORT_VERSION}")
    parsed = Run(path, ident, run["commit"], run["dirty"], tuple(run["jvm"]), run.get("cpu"),
                 tuple((key, report[key]) for key in SIZES), targets)
    flaws = report.get("flaws")
    if not isinstance(flaws, list) or report.get("conclusive") is not parsed.clean \
            or len(flaws) != len(parsed.flaws()):
        raise CheckError(f"{path}: its `conclusive` and `flaws` disagree with its controls' statistics")
    return parsed


def check_together(runs: list[Run], allow_mixed: bool) -> None:
    """Refuse a run given twice, and, unless `allow_mixed`, runs of different commits, JVMs or sizes."""
    seen: set[str] = set()
    for run in runs:
        if run.id in seen:
            raise CheckError(f"{run.path}: the run {run.id} is given twice; a copy is not another experiment")
        seen.add(run.id)
    if allow_mixed:
        return
    first = runs[0]
    for run in runs[1:]:
        for name, mine, theirs in (("commit", run.commit, first.commit), ("dirty", run.dirty, first.dirty),
                                   ("JVM", run.jvm, first.jvm), ("sizes", run.sizes, first.sizes)):
            if mine != theirs:
                raise CheckError(f"{run.path}: its {name} differs from {first.path}'s ({mine!r} against "
                                 f"{theirs!r}); --allow-mixed decides such runs together")


def decide(runs: list[Run]) -> Decision:
    """The rule of this program's docstring, over the runs given."""
    if not runs:
        raise CheckError("no run to decide from")
    counted: dict[str, list[int]] = {}
    above: dict[str, list[tuple[int, float]]] = {}
    for index, run in enumerate(runs, start=1):
        for target in run.targets:
            if target.role != "secret":
                continue
            if target.statistic > THRESHOLD:
                above.setdefault(target.id, []).append((index, target.statistic))
            if run.counts(target):
                counted.setdefault(target.id, []).append(index)
    clean = sum(1 for run in runs if run.clean)
    if any(len(indices) >= CONFIRMATIONS for indices in counted.values()):
        status = LEAK
    elif above or clean < CONFIRMATIONS:
        status = INCONCLUSIVE
    else:
        status = NO_LEAK
    return Decision(status, clean, counted, above)


def _cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def _cpu_text(cpu: str | None) -> str:
    if cpu is None:
        return "unknown"
    return cpu + (" (the reference CPU)" if cpu == TURBO_REFERENCE_CPU else f" (not the reference CPU, {TURBO_REFERENCE_CPU})")


def _why(runs: list[Run], decision: Decision) -> str:
    limit = f"{THRESHOLD:g}"
    if decision.status == LEAK:
        return "counted in at least {} runs: {}".format(
            CONFIRMATIONS, "; ".join(f"`{ident}` in runs {', '.join(map(str, decision.counted[ident]))}"
                                     for ident in decision.confirmed))
    if decision.above:
        readings = "; ".join(f"`{ident}` " + ", ".join(f"{_reading(value)} in run {index}" for index, value in values)
                             for ident, values in sorted(decision.above.items()))
        return (f"a secret target was above {limit} without being confirmed, so no leak cannot be said: {readings}; "
                "neither a pass nor a failure")
    if decision.clean < CONFIRMATIONS:
        return (f"{decision.clean} of {len(runs)} runs clean, fewer than the {CONFIRMATIONS} that no leak needs: "
                "neither a pass nor a failure")
    return f"no secret target above {limit} in any run ({decision.clean} of {len(runs)} runs clean)"


def summary(runs: list[Run], decision: Decision) -> dict:
    """What `summary.json` holds: the verdict and its reasons, the kind and CPU of each run and every statistic."""
    names: dict[str, dict] = {}
    for run in runs:
        for target in run.targets:
            names.setdefault(target.id, {"name": target.name, "role": target.role})
    return {
        "verdict": decision.verdict,
        "status": decision.status,
        "why": _why(runs, decision),
        "threshold": THRESHOLD,
        "factor": FACTOR,
        "confirmations": CONFIRMATIONS,
        "report_version": REPORT_VERSION,
        "runs": len(runs),
        "clean_runs": decision.clean,
        "confirmed": decision.confirmed,
        "counted": decision.counted,
        "above": {ident: [{"run": index, "statistic": None if math.isinf(value) else round(value, 2)}
                          for index, value in values] for ident, values in decision.above.items()},
        "cpus": sorted({run.cpu or "unknown" for run in runs}),
        "reference_cpu": TURBO_REFERENCE_CPU,
        "on_reference_cpu": [run.cpu == TURBO_REFERENCE_CPU for run in runs],
        "targets": names,
        "results": [{"record": Path(run.path).name, "run_id": run.id, "commit": run.commit, "dirty": run.dirty,
                     "cpu": run.cpu, "kind": run.kind, "floor": round(run.floor, 2), "flaws": run.flaws(),
                     "sizes": dict(run.sizes),
                     "statistics": {target.id: None if math.isinf(target.statistic) else round(target.statistic, 2)
                                    for target in run.targets}}
                    for run in runs],
    }


def markdown(runs: list[Run], decision: Decision) -> str:
    """The job summary: the verdict, the CPU, the rule, and a table of every statistic by run."""
    limit = f"{THRESHOLD:g}"
    cpus = sorted({_cpu_text(run.cpu) for run in runs})
    sizes = sorted({dict(run.sizes)["measurements"] for run in runs})
    commits = sorted({run.commit[:12] + (" (dirty)" if run.dirty else "") for run in runs})
    lines = [
        f"## Secret-path timing: {decision.verdict}",
        "",
        "| | |",
        "|---|---|",
        f"| Verdict | **{decision.verdict}**: {_cell(_why(runs, decision))} |",
        f"| CPU | {_cell('; '.join(cpus))} |",
        f"| Runs | {len(runs)}: {decision.clean} clean, "
        f"{sum(1 for run in runs if run.kind == 'noisy')} noisy, {sum(1 for run in runs if run.blind)} blind; "
        + ", ".join(f"{size:,}" for size in sizes) + " measurements a multiplication; commit "
        + ", ".join(commits) + " |",
        f"| Rule | a run is blind when its variable-time control is not above {limit}, noisy when its random/random, "
        f"same-seed or field-arithmetic control is above {limit}, clean otherwise; a secret target counts above {limit} in a "
        f"clean run, above {limit} and {FACTOR:g} times the floor in a noisy one; a leak is one target counted in "
        f"{CONFIRMATIONS} runs; any reading above {limit} that is not a leak is inconclusive; "
        "docs/running/secret-timing.md |",
        "",
        "| Target | Role | " + " | ".join(f"Run {i}" for i in range(1, len(runs) + 1)) + " | Counted in runs |",
        "|---|---|" + "---:|" * len(runs) + "---|",
    ]
    for ident, role in ROSTER.items():
        cells = []
        for run in runs:
            target = next(mine for mine in run.targets if mine.id == ident)
            text = _reading(target.statistic)
            if role == "secret" and run.counts(target):
                cells.append(f"**{text}**")
            elif target.statistic > THRESHOLD:
                cells.append(f"*{text}*")
            else:
                cells.append(text)
        name = next(target.name for target in runs[0].targets if target.id == ident)
        counted = ", ".join(map(str, decision.counted.get(ident, []))) if role == "secret" else ""
        lines.append(f"| {_cell(name)} | {role} | " + " | ".join(cells) + f" | {counted} |")
    lines.append("| *run* | | " + " | ".join(
        f"{run.kind}" + (f", floor {_reading(run.floor)}" if run.kind == "noisy" else "") for run in runs) + " | |")
    if len({run.cpu for run in runs}) > 1:
        lines.append("| *CPU* | | " + " | ".join(_cell(run.cpu or "unknown") for run in runs) + " | |")
    for index, run in enumerate(runs, start=1):
        for flaw in run.flaws():
            lines.append(f"\nRun {index} {run.kind}: {_cell(flaw)}.")
    lines.append("")
    lines.append(f"A statistic is the larger of |t| on all measurements and the largest |t| over dudect's crops; "
                 f"in bold when it counts, in italics when it is above {limit} and does not. Container timings on a "
                 "shared runner are what this check can see, no more: the JVM gives no constant-time guarantee, and "
                 "this measurement is the only evidence either way.")
    return "\n".join(lines) + "\n"


def read_records(paths: list[Path]) -> list[Run]:
    runs = []
    for path in paths:
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as error:
            raise CheckError(f"{path}: {error.strerror or error}") from None
        runs.append(parse_record(text, str(path)))
    return runs


def tool_command(checkout: runner.Checkout, classpath: str, args: argparse.Namespace, index: int) -> list[str]:
    """Run `index` of the tool in a container of its own: no network, everything read-only but the output."""
    return ["docker", "run", "--rm", "--name", f"{args.owner}-run-{index}", "--network", "none",
            "--user", f"{os.getuid()}:{os.getgid()}", "--cpus", args.cpus, "--memory", args.memory,
            *runner.labels("timing", args.owner),
            "-v", f"{checkout.root}:/work:ro", "-v", f"{checkout.cache}:/work/.cache:ro", "-v", f"{args.out}:/out",
            "-e", "HOME=/tmp", "-w", "/work", args.image,
            "java", *JVM, "-cp", classpath, MAIN, str(args.measurements), f"/out/tool-{index}.json"]


def classpath_of(args: argparse.Namespace, checkout: runner.Checkout) -> list[str]:
    """The tools runtime classpath: the one `--classpath-file` holds, or one the build exports."""
    if args.classpath_file is not None:
        return runner.parse_classpath(args.classpath_file.read_text(encoding="utf-8"))
    runner.prepare_cache(checkout.cache)
    print(f"building tools at {checkout.commit} in {args.image}", file=sys.stderr)
    done = subprocess.run(runner.build_command(checkout, args.image, args.owner), text=True,
                          capture_output=True)
    (args.out / "build.log").write_text(done.stdout + done.stderr, encoding="utf-8")
    if done.returncode != 0:
        raise CheckError(f"the tools build failed (exit {done.returncode}); see build.log")
    return runner.parse_classpath(done.stdout)


def _text(output: str | bytes | None) -> str:
    if output is None:
        return ""
    return output if isinstance(output, str) else output.decode("utf-8", "replace")


def stop(name: str) -> str | None:
    """Remove the container `name`, bounded in time; why it may be left behind, or None once it is gone."""
    try:
        done = subprocess.run(["docker", "rm", "-f", name], text=True, capture_output=True, timeout=REMOVE_TIMEOUT)
    except subprocess.TimeoutExpired:
        return f"`docker rm -f {name}` did not return within {REMOVE_TIMEOUT} s"
    except OSError as error:
        return f"`docker rm -f {name}` could not be started: {error.strerror or error}"
    if done.returncode != 0 and "No such container" not in done.stderr:
        return f"`docker rm -f {name}` exited {done.returncode}: {_text(done.stderr).strip()[:200]}"
    return None


def run_tool(checkout: runner.Checkout, classpath: str, args: argparse.Namespace, index: int, cpu: str | None,
             commit: tuple[str, bool]) -> dict:
    """One run: its output kept in `run-N.log` and its record in `run-N.json`; what it took, or `CheckError` when it
    failed or ran too long."""
    command = tool_command(checkout, classpath, args, index)
    name = f"{args.owner}-run-{index}"
    clock = time.monotonic()
    print(f"run {index} of {args.repetitions}: {args.measurements} measurements", file=sys.stderr)
    log = args.out / f"run-{index}.log"
    raw = args.out / f"tool-{index}.json"
    try:
        done = subprocess.run(command, text=True, capture_output=True, timeout=args.run_timeout)
    except subprocess.TimeoutExpired as expired:
        log.write_text(_text(expired.stdout) + _text(expired.stderr), encoding="utf-8")
        left = stop(name)
        raise CheckError(f"run {index} took more than {args.run_timeout} s and was stopped; see {log.name}"
                         + (f"; its container may be left behind: {left}" if left else "")) from None
    log.write_text(done.stdout + done.stderr, encoding="utf-8")
    seconds = round(time.monotonic() - clock, 1)
    if not raw.is_file():
        raise CheckError(f"run {index}: the tool exited {done.returncode} without its report; see {log.name}")
    try:
        report = json.loads(raw.read_text(encoding="utf-8"))
    except ValueError as error:
        raise CheckError(f"run {index}: the tool's report is not JSON: {error}") from None
    ident = report.get("run_id") if isinstance(report, dict) else None
    record = {"run": {"index": index, "id": ident, "commit": commit[0], "dirty": commit[1], "jvm": list(JVM),
                      "cpu": cpu, "exit_status": done.returncode, "seconds": seconds},
              "report": report}
    (args.out / f"run-{index}.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    raw.unlink()
    if done.returncode != 0:
        raise CheckError(f"run {index}: the tool exited {done.returncode}; see {log.name}")
    return {"record": f"run-{index}.json", "log": log.name, "run_id": ident, "exit_status": done.returncode,
            "seconds": seconds}


def measure(args: argparse.Namespace) -> list[Run]:
    """Build, probe and run the tool, keeping its records and the provenance; the runs."""
    earlier = sorted(path.name for pattern in ("run-*.json", "tool-*.json") for path in args.out.glob(pattern))
    if earlier:
        raise CheckError(f"{args.out} already holds reports ({', '.join(earlier[:3])}): give each run a fresh --out")
    checkout = runner.checkout_facts(ROOT)
    started = runner.now()
    if args.build_image or subprocess.run(["docker", "image", "inspect", args.image],
                                          capture_output=True).returncode != 0:
        done = subprocess.run(["docker", "build", "-f", "docker/jdk.Dockerfile", "-t", args.image,
                               str(checkout.root)], cwd=checkout.root)
        if done.returncode != 0:
            raise CheckError(f"the image could not be built (exit {done.returncode})")
    env = runner.environment(args.image, args.owner, args.cpus, args.memory)
    clock = time.monotonic()
    classpath = ":".join(classpath_of(args, checkout))
    build_seconds = round(time.monotonic() - clock, 1)
    if not runner.has_class(classpath.split(":"), MAIN, checkout):
        raise CheckError(f"{MAIN} is not on the tools classpath")
    model = env["cpu"]["model"]
    runs: list[dict] = []
    # the provenance is written however the runs end, with the runs that finished
    try:
        for index in range(1, args.repetitions + 1):
            runs.append(run_tool(checkout, classpath, args, index, model, (checkout.commit, checkout.dirty)))
    finally:
        record = {
            "scarab": {"repository": os.environ.get("GITHUB_REPOSITORY", runner.REPOSITORY),
                       "commit": checkout.commit, "dirty": checkout.dirty},
            "tool": {"main": MAIN, "source": SOURCE, "jvm": list(JVM), "measurements": args.measurements,
                     "repetitions": args.repetitions},
            "environment": env,
            "timings_seconds": {"build": build_seconds},
            "runs": runs,
            "started_at": runner.stamp(started),
            "finished_at": runner.stamp(runner.now()),
        }
        (args.out / "provenance.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return read_records([args.out / run["record"] for run in runs])


def outcome(out: Path, status: str) -> int:
    """The workflow's last step: 0 when the job may be green, 1 when it must be red, with its annotation."""
    def red(title: str, message: str) -> int:
        print(f"::error title=Secret-path timing: {title}::{message}")
        return 1

    if status not in ("0", "1", "2", "3"):
        return red("the check could not run", f"the step that ran the test gave the status {status!r}")
    try:
        data = json.loads((out / "summary.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        return red("the check could not run", f"exit status {status}, and no readable summary: {error}")
    if not isinstance(data, dict) or data.get("status") != int(status):
        return red("the check could not run",
                   f"exit status {status}, but the summary gives {data.get('status') if isinstance(data, dict) else None!r}")
    results = data.get("results")
    expected = [out / "summary.md", out / "provenance.json"]
    expected += [out / item["record"] for item in results if isinstance(item, dict) and isinstance(item.get("record"), str)] \
        if isinstance(results, list) else []
    missing = [path.name for path in expected if not path.is_file()]
    if not isinstance(results, list) or not results or missing:
        return red("the check could not run", f"exit status {status}, but evidence is missing: "
                   + (", ".join(missing) if missing else "no run in the summary"))
    why = str(data.get("why", ""))
    if status == "1":
        return red("leak confirmed", why + "; see the job summary and docs/running/secret-timing.md")
    if status == "2":
        print(f"::warning title=Secret-path timing: inconclusive::{why} (docs/running/secret-timing.md)")
        return 0
    if status == "3":
        return red("the check could not run", "see the log of the step that ran the test")
    print(f"no leak found: {why}")
    return 0


def plan(args: argparse.Namespace) -> None:
    checkout = runner.checkout_facts(ROOT)
    print("image:", args.image, "(built)" if args.build_image else "(built when absent)")
    print("cpu probe:", shlex.join(runner.probe_command(args.image, args.owner, args.cpus, args.memory)))
    if args.classpath_file is None:
        print("build:", shlex.join(runner.build_command(checkout, args.image, args.owner)))
    for index in range(1, args.repetitions + 1):
        print(f"run {index}:", shlex.join(tool_command(checkout, "<tools classpath>", args, index)))


class Parser(argparse.ArgumentParser):
    """Arguments; a usage error exits 3, since 2 is this program's inconclusive."""

    def error(self, message: str) -> None:  # type: ignore[override]
        self.print_usage(sys.stderr)
        self.exit(ERROR, f"{self.prog}: error: {message}\n")


def arguments(argv: list[str]) -> argparse.Namespace:
    parser = Parser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, required=True, help="where the records, logs and summaries go")
    parser.add_argument("--reports", type=Path, nargs="+", help="decide from these records and run nothing")
    parser.add_argument("--allow-mixed", action="store_true",
                        help="with --reports, decide records of different commits, JVMs or sizes together")
    parser.add_argument("--outcome", metavar="STATUS",
                        help="check the evidence in --out for the exit status STATUS and print the job's annotation")
    parser.add_argument("--repetitions", type=int, default=3,
                        help=f"runs of the tool, {CONFIRMATIONS} to {MAX_REPETITIONS} (default: %(default)s)")
    parser.add_argument("--measurements", type=int, default=100000,
                        help=f"measurements a multiplication, at least {MIN_MEASUREMENTS}; a quarter as many, and at "
                        "least 1,000, a proof, output, signature or evolution (default: %(default)s)")
    parser.add_argument("--image", default="scarab-jdk:local", help="the JDK image (default: %(default)s)")
    parser.add_argument("--build-image", action="store_true", help="build the image even if it exists")
    parser.add_argument("--classpath-file", type=Path,
                        help="a tools runtime classpath already exported (`export tools/Runtime/fullClasspath`), "
                        "used instead of building")
    parser.add_argument("--owner", default="secret-timing", help="owner label of the containers")
    parser.add_argument("--cpus", default="3", help="CPUs of each tool container (default: %(default)s)")
    parser.add_argument("--memory", default="4g", help="memory of each tool container (default: %(default)s)")
    parser.add_argument("--run-timeout", type=int, default=1800,
                        help="seconds one run may take before it is stopped (default: %(default)s)")
    parser.add_argument("--dry-run", action="store_true", help="print the commands, run nothing")
    args = parser.parse_args(argv)
    if not runner.OWNER.match(args.owner):
        parser.error(f"--owner must match {runner.OWNER.pattern}")
    if not CONFIRMATIONS <= args.repetitions <= MAX_REPETITIONS:
        parser.error(f"--repetitions must be {CONFIRMATIONS} to {MAX_REPETITIONS}")
    if args.measurements < MIN_MEASUREMENTS:
        parser.error(f"--measurements must be at least {MIN_MEASUREMENTS}")
    if args.run_timeout < 1:
        parser.error("--run-timeout must be a positive number of seconds")
    if args.allow_mixed and args.reports is None:
        parser.error("--allow-mixed goes with --reports")
    if args.outcome is not None and args.reports is not None:
        parser.error("--outcome and --reports are two forms of their own")
    args.out = args.out.resolve()
    return args


def main(argv: list[str] | None = None) -> int:
    args = arguments(sys.argv[1:] if argv is None else argv)
    if args.outcome is not None:
        return outcome(args.out, args.outcome)
    if args.dry_run and args.reports is None:
        plan(args)
        return 0
    try:
        args.out.mkdir(parents=True, exist_ok=True)
        runs = read_records(args.reports) if args.reports is not None else measure(args)
        check_together(runs, args.allow_mixed)
        decision = decide(runs)
        text = markdown(runs, decision)
        (args.out / "summary.md").write_text(text, encoding="utf-8")
        (args.out / "summary.json").write_text(json.dumps(summary(runs, decision), indent=2) + "\n",
                                               encoding="utf-8")
    except (CheckError, runner.RunError, OSError) as error:
        print(f"the check could not run: {error}", file=sys.stderr)
        return ERROR
    print(text)
    return decision.status


def entry() -> int:
    """`main`, with any failure of this program's own an exit status of 3, never one of the verdicts."""
    try:
        return main()
    except SystemExit:
        raise
    except BaseException:  # noqa: BLE001 - every failure here is "could not run", whatever it is
        traceback.print_exc()
        return ERROR


if __name__ == "__main__":
    sys.exit(entry())
