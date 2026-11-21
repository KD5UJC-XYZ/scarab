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
shard, and that each shard's commands run each of its suites exactly once (`commands` checks the plan again).
`verify` reads the JUnit reports the shards wrote and refuses a suite without exactly one report from the shard it
was planned in, a report for a suite the plan does not hold, and a report with a failure or an error. sbt writes a
suite's report to one file per module, so a second run of a suite in the same shard would replace the first; the
check of the commands is what rules that out.
An incomplete run is not a pass (CNF-2, docs/design-rules.md): `verify` also refuses a report that ran no test, and
a skipped test that `scripts/ci_test_skips.json` does not list. sbt's report shows munit's `assume` and `.ignore`
alike, as a `<skipped/>` test case. Each entry of that file names the module, suite and test, the environment
variables or data the test waits on, and the issue that owns running it; an entry whose test did not skip is
refused too, so the list holds exactly the skips CI has. The allowed skips are printed with what they wait on.
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
SKIPS = "scripts/ci_test_skips.json"
# A suite name `testOnly` takes as itself: a dotted JVM class name, no glob (`*`) or space.
SUITE = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*")
MODULE = re.compile(r"[A-Za-z][\w-]*")
VARIABLE = re.compile(r"[A-Z][A-Z0-9_]*")
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


@dataclass(frozen=True)
class Skip:
    """A test CI may report as skipped: what it waits on, and the issue that owns running it."""
    module: str
    suite: str
    test: str
    environment: tuple[str, ...]
    data: str | None
    issue: int

    @property
    def key(self) -> tuple[str, str, str]:
        return (self.module, self.suite, self.test)

    def waits_on(self) -> str:
        return ", ".join([*self.environment, *([self.data] if self.data else [])])


@dataclass(frozen=True)
class Verified:
    """What `verify` accepted: each shard's (suites, tests, skipped), and the skips it allowed."""
    totals: dict[int, tuple[int, int, int]]
    skipped: tuple[Skip, ...]


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


def read_skips(data: object) -> dict[tuple[str, str, str], Skip]:
    """The allowlist of skipped tests, by (module, suite, test). Each entry names its `module`, `suite` and `test`,
    the `environment` variables the test waits on (a list, empty only when `data` says what it waits on), and the
    `issue` that owns running it; `data` is optional."""
    if not isinstance(data, dict) or not isinstance(data.get("skips"), list):
        raise ShardError([f"{SKIPS} must be a JSON object with a `skips` list"])
    fields = {"module", "suite", "test", "environment", "data", "issue"}
    problems = []
    result: dict[tuple[str, str, str], Skip] = {}
    for number, entry in enumerate(data["skips"], 1):
        where = f"{SKIPS} entry {number}"
        if not isinstance(entry, dict):
            problems.append(f"{where} is not an object")
            continue
        wrong = [f"unknown field {k!r}" for k in sorted(set(entry) - fields)]
        wrong += [f"no {k!r}" for k in sorted(fields - {"data"} - set(entry))]
        module, suite, test = entry.get("module"), entry.get("suite"), entry.get("test")
        environment, extra, issue = entry.get("environment", []), entry.get("data"), entry.get("issue")
        if not isinstance(module, str) or not MODULE.fullmatch(module):
            wrong.append("`module` is not a module name")
        if not isinstance(suite, str) or not SUITE.fullmatch(suite):
            wrong.append("`suite` is not a class name")
        if not isinstance(test, str) or not test.strip() or test != test.strip():
            wrong.append("`test` is not a test name")
        if (not isinstance(environment, list) or len(set(map(str, environment))) != len(environment)
                or not all(isinstance(v, str) and VARIABLE.fullmatch(v) for v in environment)):
            wrong.append("`environment` is not a list of distinct environment variable names")
        if extra is not None and (not isinstance(extra, str) or not extra.strip()):
            wrong.append("`data` is not a description of the data the test waits on")
        if isinstance(environment, list) and not environment and extra is None:
            wrong.append("it names neither the environment variables nor the data the test waits on")
        if not isinstance(issue, int) or isinstance(issue, bool) or issue < 1:
            wrong.append("`issue` is not the number of the issue that owns running the test")
        if wrong:
            problems.append(f"{where}: " + "; ".join(wrong))
            continue
        skip = Skip(module, suite, test, tuple(environment), extra, issue)
        if skip.key in result:
            problems.append(f"{where}: test {test!r} of {suite} of {module} is listed twice")
        result[skip.key] = skip
    if problems:
        raise ShardError(problems)
    return result


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


def command_suites(inventory: list[Module], command: str) -> list[tuple[str, str]]:
    """The (module, suite) pairs an sbt command of the plan runs: every suite of the module for
    `<module>/Test/test`, the named ones for `<module>/Test/testOnly <suites>`."""
    modules = {m.name: m for m in inventory}
    target, _, rest = command.partition(" ")
    name, _, task = target.partition("/")
    names = rest.split(" ") if rest else []
    if name not in modules:
        raise ShardError([f"command {command!r} names no module of the inventory"])
    if task == "Test/test" and not names:
        return [(name, s) for s in modules[name].suites]
    if task == "Test/testOnly" and names and all(s in modules[name].suites for s in names):
        return [(name, s) for s in names]
    raise ShardError([f"command {command!r} is not a whole module's tests or some of its suites by name"])


def check_plan(plan: dict) -> None:
    """Refuses a plan whose shards do not partition the inventory, or whose commands do not run each suite of
    their shard exactly once (a suite run twice in one shard would leave one report, the second run's)."""
    inventory = plan_inventory(plan)
    check_partition(inventory, [[tuple(pair) for pair in s["suites"]] for s in plan["shards"]])
    problems = []
    for shard in plan["shards"]:
        ran = Counter(pair for command in shard["commands"] for pair in command_suites(inventory, command))
        declared = Counter(tuple(pair) for pair in shard["suites"])
        if ran != declared:
            problems.append(f"shard {shard['shard']}: its commands run "
                            + ", ".join(f"{s} of {m} {ran[(m, s)]} times" for m, s in sorted(ran.keys() | declared.keys())
                                        if ran[(m, s)] != 1 or (m, s) not in declared)
                            + "; each of its suites must run exactly once")
    if problems:
        raise ShardError(problems)


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


def verify(plan: dict, reports: Path, skips: dict[tuple[str, str, str], Skip]) -> Verified:
    """Refuses unless every suite of the plan has exactly one report, from its own shard, that ran at least one test,
    with no failure or error and no skipped test that `skips` does not list, and every report is for a suite of the
    plan. Refuses an entry of `skips` whose test did not skip."""
    check_plan(plan)
    planned = {(m, s): shard["shard"] for shard in plan["shards"] for m, s in shard["suites"]}
    modules = {m.name for m in plan_inventory(plan)}
    seen: Counter[tuple[str, str]] = Counter()
    read: set[tuple[str, str]] = set()
    skipped: set[tuple[str, str, str]] = set()
    unlisted = 0
    totals = {shard["shard"]: (0, 0, 0) for shard in plan["shards"]}
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
        counts = {k: root.get(k, "") for k in ("tests", "failures", "errors", "skipped")}
        if root.tag != "testsuite" or root.get("name") != suite or not all(v.isdigit() for v in counts.values()):
            problems.append(f"report {where} is not the JUnit report of {suite}")
            continue
        cases = root.findall("testcase")
        skips_here = [case.get("name", "") for case in cases if case.find("skipped") is not None]
        if int(counts["tests"]) != len(cases) or int(counts["skipped"]) != len(skips_here):
            problems.append(f"report {where} counts {counts['tests']} tests, {counts['skipped']} skipped, but holds "
                            f"{len(cases)} test cases, {len(skips_here)} skipped")
            continue
        read.add((module, suite))
        if not cases:
            problems.append(f"report {where}: suite {suite} of {module} ran no test")
        if int(counts["failures"]) or int(counts["errors"]):
            problems.append(f"report {where}: {counts['failures']} failures, {counts['errors']} errors")
        for test in skips_here:
            if (module, suite, test) in skips:
                skipped.add((module, suite, test))
            else:
                unlisted += 1
                problems.append(f"report {where}: test {test!r} was skipped, and {SKIPS} does not list it")
        suites, tests, skips_so_far = totals[number]
        totals[number] = (suites + 1, tests + len(cases), skips_so_far + len(skips_here))
    if unlisted:
        problems.append(f"an incomplete run is not a pass (CNF-2): run each test above that skipped, or list it in "
                        f"{SKIPS} with what it waits on and the issue that owns running it")
    problems += [f"suite {s} of {m} has no report (planned in shard {n})" for (m, s), n in sorted(planned.items())
                 if seen[(m, s)] == 0]
    problems += [f"suite {s} of {m} has {n} reports" for (m, s), n in sorted(seen.items()) if n > 1]
    for key, skip in sorted(skips.items()):
        if (skip.module, skip.suite) not in planned:
            problems.append(f"{SKIPS} lists test {skip.test!r} of {skip.suite} of {skip.module}, a suite the plan "
                            "does not hold: remove its entry")
        elif (skip.module, skip.suite) in read and key not in skipped:
            problems.append(f"{SKIPS} lists test {skip.test!r} of {skip.suite} of {skip.module}, which did not "
                            "skip: remove its entry")
    if problems:
        raise ShardError(problems)
    return Verified(totals, tuple(skips[key] for key in sorted(skipped)))


def compiled(log: str) -> list[str]:
    """The lines of an sbt log in which zinc starts a compilation."""
    return [line for line in (ANSI.sub("", raw).rstrip() for raw in log.splitlines()) if COMPILING.match(line)]


def load_weights(root: Path) -> Weights:
    return read_weights(json.loads((root / WEIGHTS).read_text(encoding="utf-8")))


def load_skips(root: Path) -> dict[tuple[str, str, str], Skip]:
    return read_skips(json.loads((root / SKIPS).read_text(encoding="utf-8")))


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
            plan = json.loads(args.plan.read_text(encoding="utf-8"))
            check_plan(plan)
            print("\n".join(shard_of(plan, args.shard)["commands"]))
        elif args.action == "collect":
            count = collect(json.loads(args.plan.read_text(encoding="utf-8")), args.shard, Path.cwd(), args.out)
            print(f"shard {args.shard}: {count} reports")
        elif args.action == "verify":
            verified = verify(json.loads(args.plan.read_text(encoding="utf-8")), args.reports, load_skips(ROOT))
            for number, (suites, tests, skipped) in sorted(verified.totals.items()):
                print(f"shard {number}: {suites} suites, {tests} tests, {skipped} skipped")
            for skip in verified.skipped:
                print(f"skipped, as {SKIPS} allows: {skip.suite} of {skip.module}: {skip.test!r} "
                      f"(waits on {skip.waits_on()}; #{skip.issue})")
            suites, tests = (sum(t[i] for t in verified.totals.values()) for i in (0, 1))
            print(f"every suite ran once, in its shard, and passed but for the {len(verified.skipped)} skips listed: "
                  f"{suites} suites, {tests} tests")
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
