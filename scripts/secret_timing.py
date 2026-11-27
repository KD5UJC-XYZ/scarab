#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the secret-path timing test (`SecretPathTiming`, tools) several times and decide whether it found a leak.

    python3 -I -B scripts/secret_timing.py --out DIR [--repetitions 3] [--measurements 100000] [options]
    python3 -I -B scripts/secret_timing.py --out DIR --reports RUN.json [RUN.json ...]

The first form

  1. builds the JDK image when asked (`--build-image`) or when it is absent (never java or sbt on the host), probes
     in it the CPU a tool container sees (the probe of `conformance/run.py`), then builds `tools` and exports its
     runtime classpath, as `conformance/run.py` does (or takes one already exported, `--classpath-file`);
  2. runs the tool `--repetitions` times, one after another, each in a fresh container of the image (no network,
     the checkout and the dependency cache read-only, only DIR writable) and a JVM of its own (`JVM`), and keeps
     each run's output (`run-N.log`) and its report (`run-N.json`, the tool's JSON);
  3. decides (`decide`) and writes `summary.json`, `summary.md` (every target's statistic in every run, the CPU
     and the verdict) and `provenance.json` (the commit, the image, the JDK, the CPU and the limits, the time each
     run took; no host name, user or host path).

The second form decides from reports already written, by this program or by earlier runs of it (a downloaded
workflow artifact, several of them together), and runs nothing; a report's CPU is the one named by the
`provenance.json` beside it, if there is one.

The rule. A run is conclusive when the tool says so: its random/random control, its field-arithmetic control and
its variable-time control each read as they must (the tool's Scaladoc has what each one rules out). A secret target
is above the threshold in a run when its statistic is above the tool's own threshold, dudect's 10, which the report
carries. A leak is confirmed when one secret target is above it in at least `CONFIRMATIONS` conclusive runs: the
rule of Goodwill, Jun, Jaffe and Rohatgi ("A testing methodology for side-channel resistance validation", NIST NIAT
2011) that a device is rejected only when Welch's t exceeds the threshold in two independent experiments, with
dudect's 10 as the threshold. Theirs also asks for the same sign in both; this rule does not, since a target whose
fixed secret is drawn once is drawn anew in each run, and a leak may then be slower in one run and faster in the
other. Otherwise, with fewer than `CONFIRMATIONS` conclusive runs the check could not
have confirmed a leak, and is inconclusive; with as many or more, no leak was found, and a target above the
threshold in one conclusive run only is listed as such. The CPU's model is recorded with every result, beside
whether it is the reference CPU of the timing scenarios (`TURBO_REFERENCE_CPU`); the verdict does not depend on
it, since every run compares its two classes, and its controls, on one machine (docs/running/secret-timing.md).

Exit status: 0 no leak found, 1 a leak confirmed, 2 inconclusive (the devnet scenarios' convention), 3 the check
could not run (a build or a run failed, a report is not one, or the arguments are wrong). Nothing is published.
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
from dataclasses import dataclass
from pathlib import Path

# No bytecode in the checkout: `-I` ignores PYTHONDONTWRITEBYTECODE.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

from conformance import provenance, run as runner  # noqa: E402
from conformance.scenarios import TURBO_REFERENCE_CPU  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
MAIN = "xyz.kd5ujc.scarab.crypto.vrf.SecretPathTiming"
SOURCE = "tools/src/main/scala/xyz/kd5ujc/scarab/crypto/vrf/SecretPathTiming.scala"
# The tool's documented JVM (its Scaladoc): the serial collector and compilation in the foreground, so that no
# collector or compiler thread runs beside a timed call, and a fixed heap, so that its sizing does not move.
JVM = ("-XX:+UseSerialGC", "-Xbatch", "-Xms2g", "-Xmx2g")
# Conclusive runs in which one secret target must be above the threshold for a leak to be confirmed (the rule above).
CONFIRMATIONS = 2
MAX_REPETITIONS = 10
MIN_MEASUREMENTS = 1000
NO_LEAK, LEAK, INCONCLUSIVE, ERROR = 0, 1, 2, 3
VERDICTS = {NO_LEAK: "no leak found", LEAK: "leak confirmed", INCONCLUSIVE: "inconclusive"}
ROLES = ("secret", "noise-control", "value-control", "leak-control")


class CheckError(RuntimeError):
    """The check could not run, or a report is not one; the message says which and why."""


@dataclass(frozen=True)
class Target:
    id: str
    name: str
    role: str
    statistic: float
    over: bool


@dataclass(frozen=True)
class Report:
    """One run of the tool, as its JSON report gives it, with the CPU it ran on when that is known."""
    path: str
    measurements: int
    proofs: int
    probably: float
    targets: tuple[Target, ...]
    conclusive: bool
    flaws: tuple[str, ...]
    cpu: str | None


@dataclass(frozen=True)
class Decision:
    status: int
    conclusive: int
    confirmed: tuple[str, ...]
    once: tuple[str, ...]

    @property
    def verdict(self) -> str:
        return VERDICTS[self.status]


def _number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _target(entry: object, where: str) -> Target:
    if not isinstance(entry, dict):
        raise CheckError(f"{where}: a target is an object")
    name, ident, role = entry.get("name"), entry.get("id"), entry.get("role")
    if not isinstance(ident, str) or not ident or not isinstance(name, str) or not name:
        raise CheckError(f"{where}: a target has an id and a name")
    if role not in ROLES:
        raise CheckError(f"{where}: target {ident} has the role {role!r}, not one of {', '.join(ROLES)}")
    if not _number(entry.get("statistic")) or not isinstance(entry.get("over"), bool):
        raise CheckError(f"{where}: target {ident} has no finite statistic or no `over`")
    return Target(ident, name, role, float(entry["statistic"]), entry["over"])


def parse_report(text: str, path: str, cpu: str | None) -> Report:
    """The report the tool wrote, checked; `CheckError` when it is not one."""
    try:
        record = json.loads(text)
    except ValueError as error:
        raise CheckError(f"{path}: not JSON: {error}") from None
    if not isinstance(record, dict) or record.get("tool") != "SecretPathTiming" or record.get("version") != 1:
        raise CheckError(f"{path}: not a version 1 report of SecretPathTiming")
    for key in ("measurements", "proofs"):
        if not isinstance(record.get(key), int) or isinstance(record.get(key), bool) or record[key] < 1:
            raise CheckError(f"{path}: `{key}` is not a positive count")
    if not _number(record.get("probably")) or record["probably"] <= 0:
        raise CheckError(f"{path}: `probably` is not a positive threshold")
    entries = record.get("targets")
    if not isinstance(entries, list) or not entries:
        raise CheckError(f"{path}: no targets")
    targets = tuple(_target(entry, path) for entry in entries)
    ids = [target.id for target in targets]
    if len(set(ids)) != len(ids):
        raise CheckError(f"{path}: a target id appears twice")
    missing = [role for role in ROLES if role not in {target.role for target in targets}]
    if missing:
        raise CheckError(f"{path}: no target of the role {', '.join(missing)}")
    flaws = record.get("flaws")
    if not isinstance(record.get("conclusive"), bool) or not isinstance(flaws, list) \
            or not all(isinstance(flaw, str) for flaw in flaws) or record["conclusive"] != (not flaws):
        raise CheckError(f"{path}: `conclusive` and `flaws` disagree or are missing")
    return Report(path, record["measurements"], record["proofs"], float(record["probably"]), targets,
                  record["conclusive"], tuple(flaws), cpu)


def decide(reports: list[Report]) -> Decision:
    """The rule of this program's docstring, over the runs given."""
    if not reports:
        raise CheckError("no run to decide from")
    if len({report.probably for report in reports}) != 1:
        raise CheckError("the reports do not share one threshold")
    conclusive = [report for report in reports if report.conclusive]
    above: dict[str, int] = {}
    for report in conclusive:
        for target in report.targets:
            if target.role == "secret" and target.over:
                above[target.id] = above.get(target.id, 0) + 1
    confirmed = tuple(sorted(ident for ident, count in above.items() if count >= CONFIRMATIONS))
    once = tuple(sorted(ident for ident, count in above.items() if count < CONFIRMATIONS))
    status = LEAK if confirmed else INCONCLUSIVE if len(conclusive) < CONFIRMATIONS else NO_LEAK
    return Decision(status, len(conclusive), confirmed, once)


def _cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def _cpu_text(cpu: str | None) -> str:
    if cpu is None:
        return "unknown"
    return cpu + (" (the reference CPU)" if cpu == TURBO_REFERENCE_CPU else f" (not the reference CPU, {TURBO_REFERENCE_CPU})")


def summary(reports: list[Report], decision: Decision) -> dict:
    """What `summary.json` holds: the verdict and its reasons, the CPU of each run and every statistic."""
    names: dict[str, dict] = {}
    for report in reports:
        for target in report.targets:
            names.setdefault(target.id, {"name": target.name, "role": target.role})
    cpus = sorted({report.cpu or "unknown" for report in reports})
    return {
        "verdict": decision.verdict,
        "status": decision.status,
        "threshold": reports[0].probably,
        "confirmations": CONFIRMATIONS,
        "runs": len(reports),
        "conclusive_runs": decision.conclusive,
        "confirmed": list(decision.confirmed),
        "above_once": list(decision.once),
        "cpus": cpus,
        "reference_cpu": TURBO_REFERENCE_CPU,
        "on_reference_cpu": [report.cpu == TURBO_REFERENCE_CPU for report in reports],
        "targets": names,
        "results": [{"report": Path(report.path).name, "cpu": report.cpu, "measurements": report.measurements,
                     "proofs": report.proofs, "conclusive": report.conclusive, "flaws": list(report.flaws),
                     "statistics": {target.id: round(target.statistic, 2) for target in report.targets}}
                    for report in reports],
    }


def markdown(reports: list[Report], decision: Decision) -> str:
    """The job summary: the verdict, the CPU, the rule, and a table of every statistic by run."""
    threshold = reports[0].probably
    limit = f"{threshold:g}"
    if decision.status == LEAK:
        why = (f"above {limit} in at least {CONFIRMATIONS} conclusive runs: "
               + ", ".join(f"`{ident}`" for ident in decision.confirmed))
    elif decision.status == INCONCLUSIVE:
        why = (f"{decision.conclusive} of {len(reports)} runs conclusive, fewer than the {CONFIRMATIONS} a leak "
               "needs to be confirmed: neither a pass nor a failure")
    else:
        why = (f"no secret target above {limit} in {CONFIRMATIONS} conclusive runs ({decision.conclusive} of "
               f"{len(reports)} runs conclusive)")
    cpus = sorted({_cpu_text(report.cpu) for report in reports})
    sizes = sorted({(report.measurements, report.proofs) for report in reports})
    lines = [
        f"## Secret-path timing: {decision.verdict}",
        "",
        "| | |",
        "|---|---|",
        f"| Verdict | **{decision.verdict}**: {_cell(why)} |",
        f"| CPU | {_cell('; '.join(cpus))} |",
        f"| Runs | {len(reports)}, of which {decision.conclusive} conclusive; "
        + "; ".join(f"{m:,} measurements a multiplication, {p:,} a proof, output, signature or evolution"
                    for m, p in sizes) + " |",
        f"| Rule | a run is conclusive when its three controls read as they must; a leak is one secret target above "
        f"{limit} (dudect's \"probably not constant time\") in {CONFIRMATIONS} conclusive runs (TVLA's two "
        "independent tests); docs/running/secret-timing.md |",
    ]
    if decision.once:
        lines.append(f"| Above {limit} once, not repeated | "
                     + ", ".join(f"`{ident}`" for ident in decision.once) + " |")
    lines += ["", "| Target | Role | " + " | ".join(f"Run {i}" for i in range(1, len(reports) + 1))
              + f" | Conclusive runs above {limit} |",
              "|---|---|" + "---:|" * len(reports) + "---:|"]
    order: list[Target] = []
    for report in reports:
        order += [target for target in report.targets if target.id not in {seen.id for seen in order}]
    for target in order:
        cells = []
        for report in reports:
            found = next((mine for mine in report.targets if mine.id == target.id), None)
            if found is None:
                cells.append("–")
            else:
                text = f"{found.statistic:,.1f}"
                cells.append(f"**{text}**" if found.statistic > threshold else text)
        above = sum(1 for report in reports if report.conclusive for mine in report.targets
                    if mine.id == target.id and mine.over)
        count = f"{above} of {decision.conclusive}" if target.role == "secret" else ""
        lines.append(f"| {_cell(target.name)} | {target.role} | " + " | ".join(cells) + f" | {count} |")
    lines.append("| *conclusive* | | " + " | ".join("yes" if report.conclusive else "**no**" for report in reports)
                 + " | |")
    if len({report.cpu for report in reports}) > 1:
        lines.append("| *CPU* | | " + " | ".join(_cell(report.cpu or "unknown") for report in reports) + " | |")
    for index, report in enumerate(reports, start=1):
        for flaw in report.flaws:
            lines.append(f"\nRun {index} inconclusive: {_cell(flaw)}.")
    lines.append("")
    lines.append("A statistic is the larger of |t| on all measurements and the largest |t| over dudect's 100 crops, "
                 "in bold when above the threshold. Container timings on a shared runner are what this check can "
                 "see, no more: the JVM gives no constant-time guarantee, and this measurement is the only evidence "
                 "either way.")
    return "\n".join(lines) + "\n"


def read_reports(paths: list[Path]) -> list[Report]:
    """Reports given by path, each with the CPU of the `provenance.json` beside it, if there is one."""
    reports = []
    for path in paths:
        cpu = None
        beside = path.parent / "provenance.json"
        if beside.is_file():
            try:
                cpu = provenance.cpu_model(json.loads(beside.read_text(encoding="utf-8")))
            except ValueError:
                cpu = None
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as error:
            raise CheckError(f"{path}: {error.strerror or error}") from None
        reports.append(parse_report(text, str(path), cpu))
    return reports


def tool_command(checkout: runner.Checkout, classpath: str, args: argparse.Namespace, index: int) -> list[str]:
    """Run `index` of the tool in a container of its own: no network, everything read-only but the output."""
    return ["docker", "run", "--rm", "--name", f"{args.owner}-run-{index}", "--network", "none",
            "--user", f"{os.getuid()}:{os.getgid()}", "--cpus", args.cpus, "--memory", args.memory,
            *runner.labels("timing", args.owner),
            "-v", f"{checkout.root}:/work:ro", "-v", f"{checkout.cache}:/work/.cache:ro", "-v", f"{args.out}:/out",
            "-e", "HOME=/tmp", "-w", "/work", args.image,
            "java", *JVM, "-cp", classpath, MAIN, str(args.measurements), f"/out/run-{index}.json"]


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


def run_tool(checkout: runner.Checkout, classpath: str, args: argparse.Namespace, index: int) -> dict:
    """One run, its output kept in `run-N.log`; what it took, or `CheckError` when it failed or ran too long."""
    command = tool_command(checkout, classpath, args, index)
    clock = time.monotonic()
    print(f"run {index} of {args.repetitions}: {args.measurements} measurements", file=sys.stderr)
    log = args.out / f"run-{index}.log"
    try:
        done = subprocess.run(command, text=True, capture_output=True, timeout=args.run_timeout)
    except subprocess.TimeoutExpired as expired:
        subprocess.run(["docker", "rm", "-f", f"{args.owner}-run-{index}"], capture_output=True)
        log.write_text(_text(expired.stdout) + _text(expired.stderr), encoding="utf-8")
        raise CheckError(f"run {index} took more than {args.run_timeout} s and was stopped; see {log.name}") from None
    log.write_text(done.stdout + done.stderr, encoding="utf-8")
    seconds = round(time.monotonic() - clock, 1)
    if done.returncode != 0 or not (args.out / f"run-{index}.json").is_file():
        raise CheckError(f"run {index}: the tool exited {done.returncode} without its report; see {log.name}")
    return {"report": f"run-{index}.json", "log": log.name, "exit_status": done.returncode, "seconds": seconds}


def _text(output: str | bytes | None) -> str:
    if output is None:
        return ""
    return output if isinstance(output, str) else output.decode("utf-8", "replace")


def measure(args: argparse.Namespace) -> list[Report]:
    """Build, probe and run the tool, keeping its reports and the provenance; the runs' reports."""
    earlier = sorted(path.name for path in args.out.glob("run-*.json"))
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
    runs: list[dict] = []
    # the provenance is written however the runs end, with the runs that finished
    try:
        for index in range(1, args.repetitions + 1):
            runs.append(run_tool(checkout, classpath, args, index))
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
    model = env["cpu"]["model"]
    return [parse_report((args.out / run["report"]).read_text(encoding="utf-8"), str(args.out / run["report"]),
                         model) for run in runs]


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
    parser.add_argument("--out", type=Path, required=True, help="where the reports, logs and summaries go")
    parser.add_argument("--reports", type=Path, nargs="+", help="decide from these reports and run nothing")
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
    args.out = args.out.resolve()
    return args


def main(argv: list[str] | None = None) -> int:
    args = arguments(sys.argv[1:] if argv is None else argv)
    if args.dry_run and args.reports is None:
        plan(args)
        return 0
    try:
        args.out.mkdir(parents=True, exist_ok=True)
        reports = read_reports(args.reports) if args.reports is not None else measure(args)
        decision = decide(reports)
    except (CheckError, runner.RunError, OSError) as error:
        print(f"the check could not run: {error}", file=sys.stderr)
        return ERROR
    (args.out / "summary.json").write_text(json.dumps(summary(reports, decision), indent=2) + "\n", encoding="utf-8")
    text = markdown(reports, decision)
    (args.out / "summary.md").write_text(text, encoding="utf-8")
    print(text)
    return decision.status


if __name__ == "__main__":
    sys.exit(main())
