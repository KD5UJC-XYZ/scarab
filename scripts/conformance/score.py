# SPDX-License-Identifier: Apache-2.0
"""Score the reports of `latest/` as pragma-org's node-diversity-conformance site does, for the README that
`publish.py` writes in the reports repository.

Every rule is the site's own at `SITE_COMMIT`, the commit `pins.json` pins as `site`, and the comment beside it
cites the site's file and line at that commit. What the site's scoring reads from its own repository (the
ledger-rules fixtures' expected results, the uplc-conformance expectations and the uplc-turbo corpus) is read from
a checkout of the site at that commit (`Site.read`). A report the site could not read counts as no report, as on
the site. One rule is left out: the site also fails a uplc-turbo benchmark whose budget disagrees with the other
nodes' reports (62800627 scenarios/uplc-turbo/build.py:209-288 and 410-423), which the reports repository does
not hold.
"""
from __future__ import annotations

import gzip
import json
import math
import re
import zlib
from collections import defaultdict
from dataclasses import dataclass
from functools import reduce
from pathlib import Path
from typing import Any

from conformance.scenarios import PROTOCOL_ERAS, SCENARIOS, label

SITE_REPOSITORY = "pragma-org/node-diversity-conformance"
# The commit whose scoring this module mirrors. A change of the site's pin in pins.json needs its scoring read
# again at the new commit before this moves with it.
SITE_COMMIT = "628006278309c02a1c3e49dce00e43595d925c2e"
SITE = "https://pragma-org.github.io/node-diversity-conformance/"
# 62800627 scripts/build_common.py:32 and 416-418: the era a node is scored by when it reports several.
DEFAULT_ERA = "conway"
# 62800627 scripts/build_common.py:37-42.
CBOR_V1_CLASSES = {
    "valid": ("generated_decoded_reencoded",),
    "invalid": ("generated_must_be_rejected", "zap_must_be_rejected"),
}
CBOR_REPORT_FIELDS = ("generated_at", "protocol_version", "dataset_commit", "implementation_commit", "results")
# 62800627 scenarios/uplc-turbo/build.py:15-17: a dimension passes within twice its budget, or within an absolute
# tolerance of it.
DEVIATION_LIMIT = 1.0
CPU_TOLERANCE_US = 200
MEMORY_TOLERANCE_BYTES = 320 * 1024
# 62800627 scenarios/uplc-conformance/build.py:15 and 28.
EVALUATION_TOKENS = re.compile(r'"(?:\\.|[^"\\])*"|[()[\],{}]|[^\s()[\],{}]+', re.DOTALL)
BUDGET_TEXT = re.compile(r"cpu:\s*(-?\d+).*?mem:\s*(-?\d+)", re.DOTALL)


class ScoreError(ValueError):
    """The site's data cannot be read, or a report the site reads makes its build fail: nothing is scored."""


class Unreadable(ValueError):
    """A report the site could not read, which it counts as no report (62800627 scripts/build_common.py:409-414)."""


@dataclass(frozen=True)
class Score:
    """Met of total; a version 1 CBOR report's counts may be fractional, as the site sums what it reads."""
    met: int | float
    total: int | float

    @property
    def ratio(self) -> float:
        # 62800627 scripts/build.py:46.
        return self.met / self.total if self.total else 0.0


def _json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ScoreError(f"the site's {path.name} cannot be read: {error}") from error


def expected_result(expected: Any) -> Any:
    """The result a ledger-rules fixture expects (62800627 scenarios/ledger-rules/build.py:46-56)."""
    if expected == "Pass":
        return "pass"
    if isinstance(expected, dict):
        if expected.get("decoding_failure") is True:
            return "DecodingFailure"
        predicate = expected.get("predicate")
        if predicate:
            return predicate
    return "failed"


@dataclass(frozen=True)
class Site:
    """What the site's scoring reads from its own repository."""
    scenarios: tuple[str, ...]
    ledger: dict[str, Any]
    uplc: tuple[dict, ...]
    turbo: tuple[str, ...]

    @staticmethod
    def read(site: Path) -> Site:
        """From a checkout of the site's `scenarios/`."""
        # 62800627 scripts/build_common.py:443-451: the scenarios in the order of their directories' names.
        metadata = {}
        for path in sorted((site / "scenarios").glob("*/scenario.json")):
            scenario = _json(path)
            if not isinstance(scenario, dict) or not isinstance(scenario.get("id"), str):
                raise ScoreError(f"the site's {path.parent.name}/scenario.json names no scenario")
            if scenario["id"] in metadata:
                raise ScoreError("the site's scenario ids are not unique")
            metadata[scenario["id"]] = {"schema": f"scenarios/{path.parent.name}/schema.json", **scenario}
        absent = [scenario for scenario in SCENARIOS if scenario not in metadata]
        if absent:
            raise ScoreError(f"the site has no scenario {', '.join(absent)}")
        if not isinstance(metadata["uplc-conformance"].get("expectations"), str):
            raise ScoreError("the site's uplc-conformance names no expectations")
        if not isinstance(metadata["uplc-turbo"].get("schema"), str):
            raise ScoreError("the site's uplc-turbo names no schema")
        return Site(tuple(metadata), Site._ledger(site),
                    Site._uplc(site / metadata["uplc-conformance"]["expectations"]),
                    Site._turbo(site / metadata["uplc-turbo"]["schema"]))

    @staticmethod
    def _ledger(site: Path) -> dict[str, Any]:
        """Each fixture id and the result it expects (62800627 scenarios/ledger-rules/build.py:24-43 and 97); a
        later file of the same id replaces an earlier one."""
        definitions = {}
        for path in sorted((site / "scenarios" / "ledger-rules" / "fixtures" / "scenarios").glob("*.json")):
            document = _json(path)
            match = re.match(r"^(\d{5})-", path.stem)
            if match is None or not isinstance(document, dict) \
                    or not all(key in document for key in ("title", "description", "category")):
                raise ScoreError(f"the site's ledger-rules fixture {path.name} is not one its build reads")
            definitions[match.group(1)] = expected_result(document.get("expected", "Pass"))
        if not definitions:
            raise ScoreError("the site has no ledger-rules fixture")
        return definitions

    @staticmethod
    def _uplc(path: Path) -> tuple[dict, ...]:
        """The expected vectors (62800627 scenarios/uplc-conformance/build.py:32-34)."""
        document = _json(path)
        expectations = document.get("scenarios") if isinstance(document, dict) else None
        if not isinstance(expectations, list) or not all(isinstance(item, dict) and "id" in item
                                                          for item in expectations):
            raise ScoreError("the site's uplc-conformance expectations are not a list of vectors")
        return tuple(expectations)

    @staticmethod
    def _turbo(path: Path) -> tuple[str, ...]:
        """The corpus's benchmarks, from the schema's enumeration (62800627 scenarios/uplc-turbo/build.py:49-56)."""
        schema = _json(path)
        try:
            benchmarks = schema["$defs"]["benchmark"]["properties"]["bench"].get("enum")
        except (KeyError, TypeError, AttributeError) as error:
            raise ScoreError("the site's uplc-turbo schema enumerates no benchmarks") from error
        if not benchmarks or not isinstance(benchmarks, list) or not all(isinstance(b, str) for b in benchmarks) \
                or len(benchmarks) != len(set(benchmarks)):
            raise ScoreError("the site's uplc-turbo schema must enumerate distinct benchmarks")
        return tuple(benchmarks)


def _era_of_protocol(protocol_version: Any) -> str:
    """62800627 scripts/build_common.py:274-279."""
    try:
        major = int(str(protocol_version).split(".")[0])
    except ValueError as error:
        raise Unreadable(f"protocol version {protocol_version!r} has no major version") from error
    eras = [era for first, era in PROTOCOL_ERAS if major >= first]
    if not eras:
        raise Unreadable(f"protocol version {protocol_version} belongs to no known era")
    return eras[-1]


def _cbor_kind(category: str) -> str:
    """62800627 scripts/build_common.py:256-262."""
    if category in ("valid", "manual-valid"):
        return "valid"
    if category == "manual-invalid" or re.fullmatch(r"invalid-zap-[0-9]+", category):
        return "invalid"
    raise Unreadable(f"unknown sample category {category!r}")


def _cbor_v2(report: dict) -> tuple[str, Score]:
    """A version 2 report's era, and its score: every sample counts, and one whose outcome is `true` is met
    (62800627 scripts/build_common.py:282-322; scenarios/cbor-decoding/build.py:110-114, its `rules` being empty
    exactly when its results are)."""
    missing = [field for field in CBOR_REPORT_FIELDS if field not in report]
    if missing:
        raise Unreadable(f"CBOR report is missing {', '.join(missing)}")
    era = _era_of_protocol(report["protocol_version"])
    results = report["results"]
    if not isinstance(results, dict):
        raise Unreadable("CBOR results are not an object")
    met = 0
    for sample, outcome in results.items():
        parts = sample.split("/")
        if len(parts) < 3:
            raise Unreadable(f"sample path {sample!r} is not <rule>/<category>/<file>")
        _cbor_kind(parts[1])
        if outcome is True:
            met += 1
        elif not isinstance(outcome, str):
            raise Unreadable(f"outcome of {sample!r} must be true or an error message")
    # The site names the corpus by `dataset_commit[:7]` (build_common.py:315), which only text or a list can take.
    if not isinstance(report["dataset_commit"], (str, list)):
        raise Unreadable(f"dataset_commit {report['dataset_commit']!r} cannot be cut")
    return era, Score(met, len(results))


def _v1_counts(outcome: Any) -> dict[str, Any]:
    """A version 1 outcome's classes merged into the kinds of a version 2 report (62800627
    scripts/build_common.py:265-271)."""
    if not isinstance(outcome, dict):
        raise Unreadable(f"version 1 counts {outcome!r} are not an object")
    try:
        return {f"{kind}_{side}": sum(outcome.get(f"{old}_{side}", 0) for old in classes)
                for kind, classes in CBOR_V1_CLASSES.items() for side in ("expected", "actual")}
    except (TypeError, OverflowError) as error:
        raise Unreadable(f"version 1 counts are not numbers: {error}") from error


def _cbor_v1(report: dict) -> tuple[Any, Score]:
    """A version 1 report's era, conway when it names none, and its score (62800627 scripts/build_common.py:330-345;
    scenarios/cbor-decoding/build.py:104-115). Scarab writes only version 2 reports."""
    if report.get("scenario") != "cbor-decoding":
        raise Unreadable(f"report belongs to {report.get('scenario')!r}, expected 'cbor-decoding'")
    if "totals" not in report or "rules" not in report:
        raise Unreadable("version 1 CBOR report has no totals or rules")
    if not isinstance(report["rules"], dict):
        raise Unreadable("version 1 CBOR rules are not an object")
    totals = _v1_counts(report["totals"])
    rules = {rule: _v1_counts(outcome) for rule, outcome in report["rules"].items()}
    era = report.get("era", DEFAULT_ERA)
    if report.get("scenarios"):
        # The converted totals have no `passed` or `total`.
        return era, Score(int(totals.get("passed", 0)), int(totals.get("total", 0)))
    if rules:
        return era, Score(sum(value for key, value in totals.items() if key.endswith("_actual")),
                          sum(value for key, value in totals.items() if key.endswith("_expected")))
    return era, Score(0, 0)


def cbor(report: dict, era: str | None) -> Score:
    """A CBOR report read as the site reads it, version 2, version 1 or the legacy form without a version, and
    refused unless it is of `era` (62800627 scripts/build_common.py:325-346 and 380-381)."""
    if report.get("version") == "2":
        read, score = _cbor_v2(report)
    elif "version" not in report:
        if not all(key in report for key in ("corpus", "totals", "rules")):
            raise Unreadable("legacy CBOR report is missing corpus, totals or rules")
        read, score = _cbor_v1({**report, "version": "1", "scenario": "cbor-decoding"})
    elif report["version"] == "1":
        read, score = _cbor_v1(report)
    else:
        raise Unreadable("incompatible report: must declare version=1 or 2")
    if era is not None and read != era:
        raise Unreadable(f"report is for era {read!r}, expected {era!r}")
    return score


def _items(report: dict, key: str) -> list[dict]:
    """The objects the site's scoring iterates under `key`; it fails on anything but objects, and iterates nothing
    in an empty text or object."""
    items = report.get(key, [])
    if not isinstance(items, (list, str, dict)) or not all(isinstance(item, dict) for item in items):
        raise ScoreError(f"the report's {key} are not a list of objects, on which the site's build fails")
    return list(items)


def ledger(report: dict, definitions: dict[str, Any]) -> Score:
    """A fixture is met when the result reported under the first five characters of its id is the one it
    expects; of several, the last counts (62800627 scenarios/ledger-rules/build.py:59-60 and 93-107)."""
    results = {str(item.get("id", ""))[:5]: item.get("result") for item in _items(report, "scenarios")
               if str(item.get("id", ""))[:5] in definitions}
    return Score(sum(results.get(identifier) == expected for identifier, expected in definitions.items()),
                 len(definitions))


def normalize_evaluation(value: Any) -> Any:
    """A result without the pretty-printer's whitespace (62800627 scenarios/uplc-conformance/build.py:10-20)."""
    if not isinstance(value, str):
        return value
    normalized = " ".join(EVALUATION_TOKENS.findall(value))
    normalized = re.sub(r"([\[\({])\s+", r"\1", normalized)
    normalized = re.sub(r"\s+([)\]}])", r"\1", normalized)
    normalized = re.sub(r"\s*,\s*", ",", normalized)
    return normalized.strip()


def budget_value(value: Any) -> Any:
    """62800627 scenarios/uplc-conformance/build.py:23-29."""
    if isinstance(value, dict):
        return value
    if not isinstance(value, str):
        return None
    match = BUDGET_TEXT.search(value)
    return {"cpu": int(match.group(1)), "mem": int(match.group(2))} if match else None


def uplc(report: dict, expectations: tuple[dict, ...]) -> Score:
    """Each expectation takes an unused reported vector of its id, one of its Plutus and protocol versions if
    there is one, else the first; it is met when the versions agree, the results agree and, where it expects a
    budget, the budgets agree (62800627 scenarios/uplc-conformance/build.py:37-76)."""
    items = _items(report, "scenarios")
    if any(isinstance(item.get("id"), (list, dict)) for item in items):
        raise ScoreError("a reported vector's id is a list or an object, on which the site's build fails")
    reported = defaultdict(list)
    for item in items:
        reported[item.get("id")].append(item)
    used = set()
    met = 0
    for expected in expectations:
        candidates = [(index, item) for index, item in enumerate(reported.get(expected["id"], []))
                      if (expected["id"], index) not in used]
        same = [candidate for candidate in candidates
                if candidate[1].get("plutus_version") == expected.get("plutus_version")
                and candidate[1].get("protocol_version") == expected.get("protocol_version")]
        chosen = same[0] if same else candidates[0] if candidates else None
        if chosen is not None:
            used.add((expected["id"], chosen[0]))
        item = chosen[1] if chosen is not None else None
        versions = (item is not None and item.get("plutus_version") == expected.get("plutus_version")
                    and item.get("protocol_version") == expected.get("protocol_version"))
        evaluation = versions and normalize_evaluation(item.get("evaluation")) == normalize_evaluation(
            expected.get("evaluation"))
        budget = expected.get("budget") is None or (
            versions and budget_value(item.get("budget")) == expected.get("budget"))
        met += int(evaluation and budget)
    return Score(met, len(expectations))


def _number(value: Any) -> bool:
    """62800627 scenarios/uplc-turbo/build.py:22-23."""
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _within(measured: Any, budget: Any, tolerance: float) -> bool | None:
    """Whether a measurement is within twice its budget or within `tolerance` of it; None when either is
    absent (62800627 scenarios/uplc-turbo/build.py:84-121)."""
    if measured is None or budget is None:
        return None
    return (measured - budget) / budget <= DEVIATION_LIMIT or abs(measured - budget) <= tolerance


def benchmark_met(item: dict | None) -> bool:
    """A benchmark is met when every dimension that can be compared passes and one can; a benchmark not reported
    fails (62800627 scenarios/uplc-turbo/build.py:59-61, 80-83 and 139-147). The CPU budget is in picoseconds."""
    if item is None:
        return False
    cpu_us = item.get("cpu_us") if _number(item.get("cpu_us")) else None
    cpu_budget = item["budget_cpu"] / 1_000_000 if _number(item.get("budget_cpu")) and item["budget_cpu"] > 0 \
        else None
    mem_bytes = item.get("mem_bytes") if _number(item.get("mem_bytes")) else None
    mem_budget = item["budget_mem"] if _number(item.get("budget_mem")) and item["budget_mem"] > 0 else None
    checks = [check for check in (_within(cpu_us, cpu_budget, CPU_TOLERANCE_US),
                                  _within(mem_bytes, mem_budget, MEMORY_TOLERANCE_BYTES)) if check is not None]
    return bool(checks) and all(checks)


def turbo(report: dict, benchmarks: tuple[str, ...]) -> Score:
    """Each benchmark of the corpus, from the last reported item of its name (62800627
    scenarios/uplc-turbo/build.py:26-34 and 410-423, without the other nodes' budgets)."""
    corpus = set(benchmarks)
    items = report.get("benchmarks", [])
    if not isinstance(items, (list, str, dict)):
        raise ScoreError("the report's benchmarks cannot be iterated, on which the site's build fails")
    reported = {item["bench"]: item for item in items
                if isinstance(item, dict) and isinstance(item.get("bench"), str) and item["bench"] in corpus}
    return Score(sum(benchmark_met(reported.get(bench)) for bench in benchmarks), len(benchmarks))


def read(path: Path, scenario: str, era: str | None, site: Site) -> Score:
    """The score of the report at `path`, or `Unreadable` (62800627 scripts/build_common.py:179-180 and
    325-346)."""
    try:
        report = json.loads(gzip.decompress(path.read_bytes()))
    except (OSError, EOFError, zlib.error, ValueError) as error:
        raise Unreadable(str(error)) from error
    if not isinstance(report, dict):
        raise Unreadable("not a JSON object")
    if scenario == "cbor-decoding":
        return cbor(report, era)
    if report.get("version") != "1":
        raise Unreadable("incompatible report: must declare version=1")
    if report.get("scenario") != scenario:
        raise Unreadable(f"report belongs to {report.get('scenario')!r}, expected {scenario!r}")
    if scenario == "ledger-rules":
        return ledger(report, site.ledger)
    if scenario == "uplc-conformance":
        return uplc(report, site.uplc)
    if scenario == "uplc-turbo":
        return turbo(report, site.turbo)
    raise ScoreError(f"no scoring of the site's scenario {scenario}")


@dataclass(frozen=True)
class Row:
    """One report of `latest/`: its score, or why it has none (`no report`, `unreadable`), and whether it counts
    toward the overall score."""
    scenario: str
    era: str | None
    score: Score | None
    absence: str | None
    scored: bool


@dataclass(frozen=True)
class Results:
    rows: tuple[Row, ...]
    scenarios: int
    overall: float


def _row(latest: Path, scenario: str, era: str | None, site: Site) -> Row:
    path = latest / f"{label(scenario, era)}.json.gz"
    if not path.is_file():
        return Row(scenario, era, None, "no report", False)
    try:
        return Row(scenario, era, read(path, scenario, era, site), None, False)
    except Unreadable:
        return Row(scenario, era, None, "unreadable", False)


def results(latest: Path, site: Site) -> Results:
    """Every scenario of the site, in its order, with each era Scarab reports; the overall score adds each
    scenario's ratio over their number, in that order (62800627 scripts/build.py:38-47). A scenario is scored by
    its Conway report when that can be read, else by the first other era that can (62800627
    scripts/build_common.py:416-418), in the order of the node's report URLs in its `nodes/<id>/metadata.json`;
    the site has no entry for Scarab at that commit, and this takes Scarab's own order (`SCENARIOS`)."""
    rows = []
    ratios = []
    for scenario in site.scenarios:
        eras = SCENARIOS[scenario].eras if scenario in SCENARIOS else (None,)
        read_rows = [_row(latest, scenario, era, site) for era in eras]
        available = [row.era for row in read_rows if row.score is not None]
        primary = DEFAULT_ERA if DEFAULT_ERA in available else (available or [read_rows[0].era])[0]
        rows += [Row(row.scenario, row.era, row.score, row.absence, row.era == primary) for row in read_rows]
        scored = next(row for row in read_rows if row.era == primary)
        ratios.append(scored.score.ratio if scored.score is not None else 0.0)
    # The site adds one term at a time; Python's `sum` of floats compensates its rounding, and could differ.
    overall = reduce(lambda total, ratio: total + ratio / len(ratios), ratios, 0.0)
    return Results(tuple(rows), len(ratios), overall)


def percent(ratio: float) -> str:
    """A ratio as the site shows it: cut, never rounded, to two decimals, and 100 only when it is whole
    (62800627 shared/js/percent.js:4-8), with what JavaScript's `toFixed` gives for a value that is not finite or
    is at least 10^21 in magnitude."""
    if ratio == 1:
        return "100%"
    scaled = ratio * 10000
    if math.isnan(scaled):
        return "NaN%"
    value = min(math.floor(scaled) / 100 if math.isfinite(scaled) else scaled, 99.99)
    if value == -math.inf:
        return "-Infinity%"
    return (repr(value) if abs(value) >= 1e21 else f"{value:.2f}") + "%"


def _table_row(cells: list[str]) -> str:
    return "|" + "|".join(f" {cell} " if cell else " " for cell in cells) + "|"


def markdown(results: Results) -> str:
    """The Results section of the reports repository's README."""
    rows = [_table_row([f"`{row.scenario}`" + (f", {row.era.capitalize()}" if row.era else ""),
                        *([f"{row.score.met:,}", f"{row.score.total:,}", percent(row.score.ratio)]
                          if row.score is not None else ["", "", str(row.absence)]),
                        "yes" if row.scored else "no"])
            for row in results.rows]
    rows.append(_table_row(["**Overall**", "", "", f"**{percent(results.overall)}**", ""]))
    memory = MEMORY_TOLERANCE_BYTES // 1024
    return f"""## Results

The scores of the reports in `latest/`, as pragma-org's [node-diversity-conformance]({SITE}) site computes them by its
rules at [`{SITE_COMMIT[:9]}`](https://github.com/{SITE_REPOSITORY}/tree/{SITE_COMMIT}), computed again each time
reports are published.
The overall score is the mean of the ratios of the site's {results.scenarios} scenarios; a scenario without a
readable report counts zero.
Ratios are shown as the site shows them, cut to two decimals.

| Scenario | Met | Total | Ratio | Scored |
|---|--:|--:|--:|---|
{chr(10).join(rows)}

- `cbor-decoding`: a sample is met when it decodes as the dataset expects.
  The site scores a node by its Conway report, or by another era's when it has no Conway report it can read.
- `ledger-rules`: a fixture of the site's corpus is met when the reported result is the one the fixture expects
  (`pass`, the failing predicate, or `DecodingFailure`); a fixture without a result fails.
- `uplc-conformance`: an expected vector is met when the vector reported for its id, Plutus version and protocol
  version gives the expected result, whitespace aside, and, where a budget is expected, the expected budget.
- `uplc-turbo`: a benchmark of the corpus is met when its CPU time is at most twice its CPU budget or within
  {CPU_TOLERANCE_US} µs of it, and its memory at most twice its memory budget or within {memory} KiB of it;
  a benchmark that is not reported fails.
  The times depend on the machine that took them.
  The site also fails a benchmark whose budget disagrees with the other nodes' reports, which this count leaves out.
"""
