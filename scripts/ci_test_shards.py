#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Split the Scala test suites of `check` into CI's test shards, and check that every suite ran exactly once.

CI builds once and fans the tests out (`.github/workflows/ci.yml`): the build job runs `checkBuild`, which is
`check` without its `<module>/Test/test` commands (both aliases are built from the same lists in `build.sbt`), and
writes with `testSuiteInventory` every module's suites as sbt detects them (`Test / definedTestNames`). The shard
jobs run the tests on that build's output. This script decides who runs what, from the inventory and the measured
test seconds in `scripts/ci_test_shards.json`:

- a module runs whole in one shard, with the `<module>/Test/test` that `check` runs, unless the weights file lists
  it under `split`. A split module's suites are spread over the shards and run with `<module>/Test/testOnly`, which
  keeps the module's `testGrouping` (its forked groups); a suite the file does not name shares what is left of the
  module's seconds with the other unnamed ones, so a new suite needs no edit here;
- the units (whole modules and the suites of split modules) go, heaviest first, to the shard with the fewest
  seconds so far, ties to the lowest shard: the same inventory and weights always give the same plan.

`plan` refuses a module without a weight, a weight for a module or a suite that sbt does not report, and a suite
name that `testOnly` would read as a pattern; it then checks that every suite of the inventory is in exactly one
shard. `verify` reads the JUnit reports the shards wrote and refuses a suite without exactly one report from the
shard it was planned in, a report for a suite the plan does not hold, and a report with a failure or an error.
`compiled` refuses a shard's sbt log in which sbt compiled anything: a shard runs the build job's classes as they
are.

    python3 scripts/ci_test_shards.py plan --inventory target/ci/test-suites.tsv --out plan.json
    python3 scripts/ci_test_shards.py commands --plan plan.json --shard 3      the shard's sbt commands, one a line
    python3 scripts/ci_test_shards.py collect --plan plan.json --shard 3 --out reports
    python3 scripts/ci_test_shards.py verify --plan plan.json --reports reports
    python3 scripts/ci_test_shards.py compiled shard.log

Pure standard library.
"""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from pathlib import Path
import argparse
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[1]
WEIGHTS = "scripts/ci_test_shards.json"
# A suite name `testOnly` takes as itself: a dotted JVM class name, no glob (`*`) or space.
SUITE = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*")
MODULE = re.compile(r"[A-Za-z][\w-]*")
DIRECTORY = re.compile(r"[\w.-]+(?:/[\w.-]+)*")
# Zinc's line for a compilation it starts ("compiling 14 Scala sources to ..."), the meta-build's included.
COMPILING = re.compile(r"^\[info\] compiling \d+ (?:Scala|Java) source")
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


class ShardError(ValueError):
    """An inventory, weights file, plan or report set that the shards cannot run or account for."""

    def __init__(self, problems: list[str]):
        super().__init__("\n".join(problems))
        self.problems = problems


@dataclass(frozen=True)
class Module:
    name: str
    directory: str
    suites: tuple[str, ...]


@dataclass(frozen=True)
class Weights:
    shards: int
    modules: dict[str, float]
    split: dict[str, dict[str, float]]


@dataclass(frozen=True)
class Unit:
    module: str
    suite: str | None
    seconds: float


def read_inventory(text: str) -> list[Module]:
    """The modules of `testSuiteInventory`'s file, in its order: one `<module>\\t<directory>[\\t<suite>]` a line, a
    module without suites on one line of its own."""
    order: list[str] = []
    directories: dict[str, str] = {}
    suites: dict[str, list[str]] = {}
    problems = []
    for number, line in enumerate(text.splitlines(), 1):
        fields = line.split("\t")
        if len(fields) not in (2, 3) or not MODULE.fullmatch(fields[0]) or not DIRECTORY.fullmatch(fields[1]):
            problems.append(f"inventory line {number}: not `<module>\\t<directory>[\\t<suite>]`: {line!r}")
            continue
        name, directory = fields[0], fields[1]
        if name not in directories:
            order.append(name)
            directories[name] = directory
            suites[name] = []
        elif directories[name] != directory:
            problems.append(f"inventory line {number}: module {name} in two directories")
        if len(fields) == 3:
            if not SUITE.fullmatch(fields[2]):
                problems.append(f"inventory line {number}: {fields[2]!r} is not a class name testOnly takes as itself")
            suites[name].append(fields[2])
    for name in order:
        for suite, count in Counter(suites[name]).items():
            if count > 1:
                problems.append(f"suite {suite} of {name} is in the inventory {count} times")
    if not order:
        problems.append("the inventory lists no module")
    if problems:
        raise ShardError(problems)
    return [Module(name, directories[name], tuple(suites[name])) for name in order]


def read_weights(data: object) -> Weights:
    """The weights file: `shards`, the test seconds of every module, and the named suites of the split ones."""
    def seconds(value: object) -> bool:
        return isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0

    problems = []
    if not isinstance(data, dict):
        raise ShardError([f"{WEIGHTS} is not a JSON object"])
    shards = data.get("shards")
    if not isinstance(shards, int) or isinstance(shards, bool) or shards < 1:
        problems.append("`shards` must be a whole number of at least 1")
    modules = data.get("modules")
    if not isinstance(modules, dict) or not all(seconds(v) for v in modules.values()):
        problems.append("`modules` must map each module to its test seconds")
        modules = {}
    split = data.get("split", {})
    if not isinstance(split, dict) or not all(
            isinstance(named, dict) and all(seconds(v) for v in named.values()) for named in split.values()):
        problems.append("`split` must map each split module to the test seconds of its named suites")
        split = {}
    for name in split:
        if name not in modules:
            problems.append(f"split module {name} has no weight under `modules`")
    if problems:
        raise ShardError(problems)
    return Weights(shards, {k: float(v) for k, v in modules.items()},
                   {m: {k: float(v) for k, v in named.items()} for m, named in split.items()})


def units(inventory: list[Module], weights: Weights) -> list[Unit]:
    """What the shards share out: each whole module, and each suite of a split module; refuses weights that do
    not match the inventory."""
    known = {m.name: m for m in inventory}
    problems = [f"module {m.name} has no weight in {WEIGHTS}: add its measured test seconds"
                for m in inventory if m.name not in weights.modules]
    problems += [f"{WEIGHTS} weighs module {name}, which sbt does not report" for name in weights.modules
                 if name not in known]
    for name, named in weights.split.items():
        if name in known:
            problems += [f"{WEIGHTS} weighs suite {suite} of {name}, which sbt does not report"
                         for suite in named if suite not in known[name].suites]
    if problems:
        raise ShardError(problems)
    result = []
    for module in inventory:
        if module.name not in weights.split:
            result.append(Unit(module.name, None, weights.modules[module.name]))
            continue
        named = weights.split[module.name]
        unnamed = [s for s in module.suites if s not in named]
        rest = max(weights.modules[module.name] - sum(named.values()), 0.0)
        result += [Unit(module.name, s, named[s] if s in named else rest / len(unnamed)) for s in module.suites]
    return result


def assign(inventory: list[Module], weights: Weights) -> list[list[Unit]]:
    """The units of each shard: heaviest first to the shard with the fewest seconds, ties to the lowest shard."""
    order = {m.name: i for i, m in enumerate(inventory)}
    shards: list[list[Unit]] = [[] for _ in range(weights.shards)]
    loads = [0.0] * weights.shards
    for unit in sorted(units(inventory, weights), key=lambda u: (-u.seconds, order[u.module], u.suite or "")):
        lightest = min(range(weights.shards), key=lambda i: (loads[i], i))
        shards[lightest].append(unit)
        loads[lightest] += unit.seconds
    return shards


def commands(inventory: list[Module], shard: list[Unit]) -> list[str]:
    """The shard's sbt commands, in the inventory's module order."""
    result = []
    for module in inventory:
        mine = [u for u in shard if u.module == module.name]
        if any(u.suite is None for u in mine):
            result.append(f"{module.name}/Test/test")
        elif mine:
            result.append(f"{module.name}/Test/testOnly " + " ".join(sorted(u.suite for u in mine if u.suite)))
    return result


def suites_of(inventory: list[Module], shard: list[Unit]) -> list[tuple[str, str]]:
    """Every (module, suite) the shard runs: all of a whole module's, and a split module's named ones."""
    whole = {u.module for u in shard if u.suite is None}
    picked = {(u.module, u.suite) for u in shard if u.suite is not None}
    return [(m.name, s) for m in inventory for s in m.suites if m.name in whole or (m.name, s) in picked]


def check_partition(inventory: list[Module], shards: list[list[tuple[str, str]]]) -> None:
    """Refuses a suite of the inventory that no shard runs or that more than one runs, and one it does not hold."""
    counts = Counter(pair for shard in shards for pair in shard)
    expected = {(m.name, s) for m in inventory for s in m.suites}
    problems = [f"suite {s} of {m} is in no shard" for m, s in sorted(expected - counts.keys())]
    problems += [f"suite {s} of {m} is in {n} shards" for (m, s), n in sorted(counts.items()) if n > 1]
    problems += [f"suite {s} of {m} is in a shard but not in the inventory" for m, s in sorted(counts.keys() - expected)]
    if problems:
        raise ShardError(problems)


def make_plan(inventory: list[Module], weights: Weights) -> dict:
    shards = assign(inventory, weights)
    plan = {
        "modules": [{"module": m.name, "directory": m.directory, "suites": list(m.suites)} for m in inventory],
        "shards": [{"shard": i + 1, "seconds": round(sum(u.seconds for u in units_), 1),
                    "commands": commands(inventory, units_),
                    "suites": [list(pair) for pair in suites_of(inventory, units_)]}
                   for i, units_ in enumerate(shards)],
    }
    check_plan(plan)
    return plan


def plan_inventory(plan: dict) -> list[Module]:
    return [Module(m["module"], m["directory"], tuple(m["suites"])) for m in plan["modules"]]


def check_plan(plan: dict) -> None:
    check_partition(plan_inventory(plan), [[tuple(pair) for pair in s["suites"]] for s in plan["shards"]])


def shard_of(plan: dict, number: int) -> dict:
    found = [s for s in plan["shards"] if s["shard"] == number]
    if len(found) != 1:
        raise ShardError([f"the plan has no shard {number}; it has {len(plan['shards'])}"])
    return found[0]


def collect(plan: dict, number: int, root: Path, out: Path) -> int:
    """Copies the shard's JUnit reports to `<out>/<shard>/<module>/`; returns how many."""
    shard_of(plan, number)
    copied = 0
    for module in plan_inventory(plan):
        reports = sorted((root / module.directory / "target" / "test-reports").glob("TEST-*.xml"))
        if reports:
            target = out / str(number) / module.name
            target.mkdir(parents=True, exist_ok=True)
            for report in reports:
                shutil.copy2(report, target / report.name)
                copied += 1
    return copied


def verify(plan: dict, reports: Path) -> dict[int, tuple[int, int]]:
    """Refuses unless every suite of the plan has exactly one report, from its own shard, with no failure or
    error, and every report is for a suite of the plan. Returns each shard's (suites, tests)."""
    check_plan(plan)
    planned = {(m, s): shard["shard"] for shard in plan["shards"] for m, s in shard["suites"]}
    modules = {m.name for m in plan_inventory(plan)}
    seen: Counter[tuple[str, str]] = Counter()
    totals = {shard["shard"]: (0, 0) for shard in plan["shards"]}
    problems = []
    for path in sorted(reports.glob("*/*/TEST-*.xml")):
        shard_dir, module = path.parent.parent.name, path.parent.name
        suite = path.name[len("TEST-"):-len(".xml")]
        where = f"{shard_dir}/{module}/{path.name}"
        if not shard_dir.isdigit() or int(shard_dir) not in totals or module not in modules:
            problems.append(f"report {where} is not in a shard and module of the plan")
            continue
        number = int(shard_dir)
        seen[(module, suite)] += 1
        if (module, suite) not in planned:
            problems.append(f"report {where}: suite {suite} of {module} is not in the plan")
            continue
        if planned[(module, suite)] != number:
            problems.append(f"report {where}: suite {suite} of {module} was planned in shard "
                            f"{planned[(module, suite)]}")
        try:
            root = ElementTree.parse(path).getroot()
        except ElementTree.ParseError as error:
            problems.append(f"report {where} is not XML: {error}")
            continue
        counts = {k: root.get(k, "") for k in ("tests", "failures", "errors")}
        if root.tag != "testsuite" or root.get("name") != suite or not all(v.isdigit() for v in counts.values()):
            problems.append(f"report {where} is not the JUnit report of {suite}")
            continue
        if int(counts["failures"]) or int(counts["errors"]):
            problems.append(f"report {where}: {counts['failures']} failures, {counts['errors']} errors")
        suites, tests = totals[number]
        totals[number] = (suites + 1, tests + int(counts["tests"]))
    problems += [f"suite {s} of {m} has no report (planned in shard {n})" for (m, s), n in sorted(planned.items())
                 if seen[(m, s)] == 0]
    problems += [f"suite {s} of {m} has {n} reports" for (m, s), n in sorted(seen.items()) if n > 1]
    if problems:
        raise ShardError(problems)
    return totals


def compiled(log: str) -> list[str]:
    """The lines of an sbt log in which zinc starts a compilation."""
    return [line for line in (ANSI.sub("", raw).rstrip() for raw in log.splitlines()) if COMPILING.match(line)]


def load_weights(root: Path) -> Weights:
    return read_weights(json.loads((root / WEIGHTS).read_text(encoding="utf-8")))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("plan", help="assign every suite to a shard and write the plan")
    p.add_argument("--inventory", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p = sub.add_parser("commands", help="print the sbt commands of a shard, one a line")
    p.add_argument("--plan", type=Path, required=True)
    p.add_argument("--shard", type=int, required=True)
    p = sub.add_parser("collect", help="copy a shard's JUnit reports to <out>/<shard>/<module>/")
    p.add_argument("--plan", type=Path, required=True)
    p.add_argument("--shard", type=int, required=True)
    p.add_argument("--out", type=Path, required=True)
    p = sub.add_parser("verify", help="check the shards' reports against the plan")
    p.add_argument("--plan", type=Path, required=True)
    p.add_argument("--reports", type=Path, required=True)
    p = sub.add_parser("compiled", help="fail if an sbt log shows a compilation")
    p.add_argument("log", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.action == "plan":
            plan = make_plan(read_inventory(args.inventory.read_text(encoding="utf-8")), load_weights(ROOT))
            args.out.write_text(json.dumps(plan, indent=1) + "\n", encoding="utf-8")
            for shard in plan["shards"]:
                print(f"shard {shard['shard']}: {shard['seconds']:7.1f} s planned, {len(shard['suites']):4d} suites: "
                      + ", ".join(c.split(" ")[0] for c in shard["commands"]))
            print(f"every one of {sum(len(m['suites']) for m in plan['modules'])} suites is in exactly one shard")
        elif args.action == "commands":
            print("\n".join(shard_of(json.loads(args.plan.read_text(encoding="utf-8")), args.shard)["commands"]))
        elif args.action == "collect":
            count = collect(json.loads(args.plan.read_text(encoding="utf-8")), args.shard, Path.cwd(), args.out)
            print(f"shard {args.shard}: {count} reports")
        elif args.action == "verify":
            totals = verify(json.loads(args.plan.read_text(encoding="utf-8")), args.reports)
            for number, (suites, tests) in sorted(totals.items()):
                print(f"shard {number}: {suites} suites, {tests} tests")
            print(f"every suite ran once, in its shard, and passed: {sum(s for s, _ in totals.values())} suites, "
                  f"{sum(t for _, t in totals.values())} tests")
        else:
            lines = compiled(args.log.read_text(encoding="utf-8", errors="replace"))
            if lines:
                raise ShardError(["sbt compiled in a test shard, which must run the build job's classes as they are:",
                                  *lines])
            print("sbt compiled nothing")
    except ShardError as error:
        for problem in error.problems:
            print(f"error: {problem}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
