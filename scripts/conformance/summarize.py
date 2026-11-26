#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Write cbor-outcomes.json and cbor-outcomes.md beside a runner's summary.json, and, when the run asked for
uplc-turbo, turbo-score.json and turbo-score.md.

Usage: python3 -I -B scripts/conformance/summarize.py --out DIR [--inputs DIR] [--previous DIR]
Reports must have passed the existing schema/coverage checks and match their provenance hashes. Mismatches
remain visible informational results, never expected failures. The uplc-turbo score is the site's (`score.py`),
read against the corpus of the site under `--inputs` (the runner's, default DIR/inputs), with the programs it misses
on memory and on CPU alone. Exit 1 means incomplete or invalid evidence; exit 0 means the summary was generated,
not that all vectors matched or every program was met. This adds no merge gate.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import statistics
import sys
import zlib

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from conformance import evidence, inputs as fetching, outcomes, provenance, score  # noqa: E402
from conformance.scenarios import NAME, SCENARIOS, provenance_name  # noqa: E402
from conformance.schema import loads  # noqa: E402

INVALID = (OSError, ValueError, KeyError, TypeError, EOFError, zlib.error)
TURBO = "uplc-turbo"
# The missed programs the page lists, the worst first; turbo-score.json lists them all.
TURBO_ROWS = 50
# What a code span on the page may hold.
CODE = re.compile(r"[A-Za-z0-9._-]+")


def explanation(error: Exception) -> str:
    # FileNotFoundError includes the host's absolute scratch path, which is not report evidence.
    return (error.strerror or type(error).__name__) if isinstance(error, OSError) else str(error)


def run_items(directory: Path) -> tuple[dict, list[dict]]:
    run = loads((directory / "summary.json").read_text())
    if not isinstance(run, dict) or not isinstance(run.get("outcomes"), list) \
            or not all(isinstance(item, dict) for item in run["outcomes"]):
        raise outcomes.OutcomeError("run summary is not an object with outcome objects")
    return run, run["outcomes"]


def checked(directory: Path, item: dict, label: str) -> tuple[dict, dict]:
    """The report a run summary's outcome names, of `label`, and its provenance, once the provenance records a
    passed validation and the report's sha256."""
    name = item.get("report", "")
    match = NAME.fullmatch(name)
    if not match or match["label"] != label:
        raise outcomes.OutcomeError("missing or invalid report name")
    raw = (directory / name).read_bytes()
    provenance = loads((directory / provenance_name(name)).read_text())
    if not isinstance(provenance, dict) or not isinstance(provenance.get("validation"), dict) \
            or not isinstance(provenance.get("scarab"), dict):
        raise outcomes.OutcomeError("provenance is not an object with validation and Scarab identity")
    if provenance.get("validation", {}).get("valid") is not True \
            or provenance.get("sha256") != hashlib.sha256(raw).hexdigest():
        raise outcomes.OutcomeError("report lacks valid coverage/schema evidence or its hash differs")
    report = loads(gzip.decompress(raw).decode("utf-8"))
    if not isinstance(report, dict):
        raise outcomes.OutcomeError("report is not an object")
    return report, provenance


def read_report(directory: Path, item: dict) -> dict:
    report, provenance = checked(directory, item, "cbor-decoding-" + str(item.get("era")))
    outcomes.cbor(report)
    if provenance.get("scarab", {}).get("commit") != report.get("implementation_commit"):
        raise outcomes.OutcomeError("report and provenance name different Scarab commits")
    return report


def collect(directory: Path, previous: Path | None = None) -> list[dict]:
    run, items = run_items(directory)
    old, old_error = None, None
    if previous is not None:
        try:
            _, old = run_items(previous)
        except INVALID as error:
            old_error = f"previous run evidence is unavailable: {explanation(error)}"
    result = []
    seen = set()
    for item in items:
        if item.get("scenario") != "cbor-decoding":
            continue
        era = item.get("era")
        try:
            if era in seen:
                raise outcomes.OutcomeError("duplicate era in run summary")
            if era not in ("conway", "dijkstra"):
                raise outcomes.OutcomeError("unknown CBOR era")
            seen.add(era)
            if item.get("status") != "report":
                detail = item.get("detail") or "scenario did not produce a checked report"
                raise outcomes.OutcomeError(str(detail) + "; " + "; ".join(map(str, item.get("checks", [])[:10])))
            report = read_report(directory, item)
            prior = None
            prior_error = old_error
            if old is not None:
                candidates = [o for o in old if o.get("scenario") == "cbor-decoding"
                              and o.get("era") == era and o.get("status") == "report"]
                if len(candidates) == 1:
                    try:
                        prior = read_report(previous, candidates[0])
                    except INVALID as error:
                        prior_error = f"previous evidence is invalid: {explanation(error)}"
                else:
                    prior_error = "previous run has no unique checked report for this era"
            comparison = outcomes.compare(report, prior)
            if prior_error:
                comparison = {"status": "unavailable", "reason": prior_error}
            result.append({"era": era, **outcomes.cbor(report), "comparison": comparison})
        except INVALID as error:
            result.append({"era": era, "verdict": "incomplete", "reason": explanation(error)})
    if "cbor-decoding" in run.get("requested", []):
        result += [{"era": era, "verdict": "incomplete", "reason": "requested era has no outcome"}
                   for era in ("conway", "dijkstra") if era not in seen]
    return result


def _rounded(value: float | None) -> float | None:
    return None if value is None else round(value, 3)


def _miss(miss: score.Miss) -> dict:
    item = miss.item or {}
    return {"bench": miss.bench, "shortfall": miss.shortfall.value, "cpu_within": miss.cpu,
            "memory_within": miss.memory, "cpu_ratio": _rounded(miss.cpu_ratio),
            "memory_ratio": _rounded(miss.memory_ratio), "failing_ratio": _rounded(miss.failing_ratio),
            "cpu_us": item.get("cpu_us"), "budget_cpu": item.get("budget_cpu"), "mem_bytes": item.get("mem_bytes"),
            "budget_mem": item.get("budget_mem")}


def percentile_index(count: int, percent: int) -> int:
    """The index, in `count` sorted values, of the nearest-rank percentile: the smallest value with at least
    `percent` per cent of the values at or below it."""
    return -(-count * percent // 100) - 1


def turbo_summary(breakdown: score.Turbo, record: dict) -> dict:
    """The score of a uplc-turbo report, the programs it misses by why, and its bytes per memory unit; the CPU and
    runner come from the report's provenance (checked by `evidence.check`), and whether that CPU is the reference."""
    ratios = breakdown.memory_ratios
    environment = record["environment"]
    return {
        "verdict": "scored", "scarab_commit": record["scarab"]["commit"], "report": record["report"],
        "runner": environment["runner"], "cpu": environment["cpu"], "limits": environment["limits"],
        "reference_cpu": SCENARIOS[TURBO].reference_cpu,
        "on_reference_cpu": provenance.cpu_model(record) == SCENARIOS[TURBO].reference_cpu,
        "met": breakdown.score.met, "total": breakdown.score.total,
        "missed": {shortfall.value: len(breakdown.missed(shortfall)) for shortfall in score.Shortfall},
        "missed_on_memory_also_on_cpu": sum(1 for miss in breakdown.missed(score.Shortfall.MEMORY)
                                            if miss.cpu is False),
        "within_memory_limit": breakdown.within_memory_limit,
        "bytes_per_memory_unit_median": round(statistics.median(ratios), 3) if ratios else None,
        "bytes_per_memory_unit_p95": round(ratios[percentile_index(len(ratios), 95)], 3) if len(ratios) >= 20
        else None,
        "misses": [_miss(miss) for miss in breakdown.misses],
    }


def turbo(directory: Path, inputs: Path) -> dict | None:
    """The run's uplc-turbo score by the site's rule, scored against the corpus of the site under `inputs`; None when
    the run neither asked for uplc-turbo nor gave an outcome of it."""
    try:
        run, items = run_items(directory)
    except INVALID:
        return None
    mine = [item for item in items if item.get("scenario") == TURBO]
    if not mine and TURBO not in run.get("requested", []):
        return None
    if len(mine) == 1 and mine[0].get("status") == "not-reference-cpu":
        return {"verdict": "not-reference-cpu", "reference_cpu": SCENARIOS[TURBO].reference_cpu,
                "reason": str(mine[0].get("detail") or "this runner's CPU is not the reference")}
    try:
        if len(mine) != 1:
            raise outcomes.OutcomeError(f"the run summary has {len(mine)} uplc-turbo outcomes, not one")
        if mine[0].get("status") != "report":
            raise outcomes.OutcomeError(str(mine[0].get("detail") or "the scenario did not produce a checked report"))
        name = str(mine[0].get("report", ""))
        site = fetching.verified(fetching.load_pins()["site"], inputs)
        if site is None:
            raise outcomes.OutcomeError("the pinned site under --inputs is missing or not the one its receipt "
                                        "verified, so the report cannot be checked or scored against it")
        pinned = evidence.Pinned.read(site)
        record = loads((directory / provenance_name(name)).read_text(encoding="utf-8"))
        report = evidence.check(name, (directory / name).read_bytes(), record, pinned)
        breakdown = score.turbo_breakdown(report, pinned.scoring.turbo)
    except INVALID as error:
        return {"verdict": "incomplete", "reason": explanation(error)}
    return turbo_summary(breakdown, record)


def _cell(text: object) -> str:
    """Text for a Markdown table cell, outside a code span."""
    return str(text).replace("&", "&amp;").replace("|", "&#124;").replace("<", "&lt;").replace("`", "&#96;") \
        .replace("\n", " ")


def _code(text: object) -> str:
    """Text as a code span when it is made of letters, digits, `.`, `_` and `-` alone, which a code span shows as
    they are (an entity there is shown as written, not decoded); otherwise as escaped text."""
    return f"`{text}`" if CODE.fullmatch(str(text)) else _cell(text)


def _times(ratio: float | None) -> str:
    return "–" if ratio is None else f"{ratio:.2f}"


def turbo_markdown(summary: dict) -> str:
    lines = ["# uplc-turbo score", ""]
    if summary["verdict"] == "incomplete":
        return "\n".join(lines + ["**INCOMPLETE: no score can be given.**", "", _cell(summary["reason"]), ""])
    if summary["verdict"] == "not-reference-cpu":
        return "\n".join(lines + [
            "**NOT MEASURED: this runner's CPU is not the reference.**", "", _cell(summary["reason"]), "",
            "Re-run the uplc-turbo job to be given another runner, or dispatch with `uplc-turbo-any-cpu` to measure "
            "on any CPU; a report of another CPU is never published.", ""])
    cpu = summary["cpu"]
    limits = summary["limits"]
    missed = summary["missed"]
    publishable = [] if summary["on_reference_cpu"] else [
        f"**Not publishable: measured on {_cell(cpu['model'] or 'a CPU the probe could not name')}, not the reference "
        f"CPU {_cell(summary['reference_cpu'])}.**", ""]
    lines += publishable + [
              f"**{summary['met']:,} of {summary['total']:,} programs met** by the site's rule at "
              f"`{score.SITE_COMMIT[:9]}`: CPU time and memory each within twice the budget, or within "
              f"{score.CPU_TOLERANCE_US} µs or {score.MEMORY_TOLERANCE_BYTES // 1024} KiB of it. The site also fails "
              "a program whose budget disagrees with the other nodes' reports, which this count leaves out.", "",
              f"Scarab {_code(summary['scarab_commit'][:9])}, written on {_code(summary['runner'])}: CPU "
              f"{_cell(cpu['model'] or 'model unknown')}, {_cell(cpu['logical_cpus'] or '?')} logical CPUs, "
              f"`nproc` {_cell(cpu['available_cpus'] or '?')} and a quota of {_cell(cpu['quota_cpus'] or 'none')} in "
              f"the container; container limits {_cell(limits['cpus'])} CPUs and {_cell(limits['memory'])}. "
              "Timings vary from run to run on any machine.", "",
              "| Missed | Programs |", "|---|---:|",
              f"| on memory | {missed['memory']} ({summary['missed_on_memory_also_on_cpu']} also on CPU) |",
              f"| on CPU alone | {missed['CPU alone']} |",
              f"| not reported | {missed['not reported']} |",
              f"| nothing comparable | {missed['nothing comparable']} |", "",
              f"Within the memory limit: {summary['within_memory_limit']:,} of {summary['total']:,}; bytes per memory "
              f"unit, median {summary['bytes_per_memory_unit_median']}, 95th percentile "
              f"{summary['bytes_per_memory_unit_p95']}.", ""]
    # The worst first, by the dimensions that fail each program; one that fails none comes last.
    misses = sorted(summary["misses"], key=lambda miss: -(miss["failing_ratio"] or 0))
    if misses:
        lines += ["| Program | Missed on | CPU time over budget | Memory over budget |", "|---|---|---:|---:|"]
        lines += [f"| {_code(miss['bench'])} | {miss['shortfall']} | {_times(miss['cpu_ratio'])} | "
                  f"{_times(miss['memory_ratio'])} |" for miss in misses[:TURBO_ROWS]]
        if len(misses) > TURBO_ROWS:
            lines += ["", f"And {len(misses) - TURBO_ROWS} more, in `turbo-score.json`."]
        lines += [""]
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--inputs", type=Path)
    parser.add_argument("--previous", type=Path)
    args = parser.parse_args(argv)
    try:
        items = collect(args.out, args.previous)
    except INVALID as error:
        items = [{"era": None, "verdict": "incomplete", "reason": explanation(error)}]
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "cbor-outcomes.json").write_text(json.dumps({"schemaVersion": 1, "outcomes": items}, indent=2) + "\n")
    (args.out / "cbor-outcomes.md").write_text(outcomes.markdown(items))
    for item in items:
        if item["verdict"] == "mismatches":
            print(f"::warning::CBOR {item['era']}: {item['mismatched']} of {item['total']} samples disagree")
    scored = turbo(args.out, args.inputs or args.out / "inputs")
    if scored is not None:
        (args.out / "turbo-score.json").write_text(json.dumps({"schemaVersion": 1, **scored}, indent=2) + "\n")
        (args.out / "turbo-score.md").write_text(turbo_markdown(scored))
    return int(any(item["verdict"] == "incomplete" for item in items)
               or (scored is not None and scored["verdict"] == "incomplete"))


if __name__ == "__main__":
    sys.exit(main())
