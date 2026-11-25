#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Write turbo-memory.md and turbo-memory.json beside a runner's uplc-turbo report: its memory dimension alone.

    python3 -I -B scripts/conformance/turbo_memory.py --out <the runner's --out> [--inputs <its --inputs>]

For the uplc-turbo report in `--out` it counts, by the site's rule (`score.py`), the corpus's programs whose
allocated bytes are within the memory limit, and gives the median and 95th percentile (nearest rank) of bytes per
memory unit. The bytes a program allocates depend mostly on the JVM's settings and CPU count, which the runner
fixes, and little on the machine's speed or load: the tool averages the thirty fastest of fifty evaluations, so
which ones enter the average, and the JIT compiler's state, move a program's figure by a fraction of a per cent
from run to run. The count is the figure to compare between runs; `cpu_us` on a shared runner is not a
measurement, and this summary leaves it out. The exit status is 1 when `--out` holds no readable uplc-turbo report.
"""
from __future__ import annotations

import argparse
import gzip
import json
import statistics
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from conformance import inputs as fetching, score  # noqa: E402
from conformance.scenarios import SCENARIOS  # noqa: E402


class SummaryError(ValueError):
    """A report or provenance that is not a uplc-turbo report's."""


def percentile_index(count: int, percent: int) -> int:
    """The index, in `count` sorted values, of the nearest-rank percentile: the smallest value with at least
    `percent` per cent of the values at or below it."""
    return -(-count * percent // 100) - 1


def checked(report: object, record: object) -> tuple[dict, str]:
    """The report and its Scarab commit, once the report is a uplc-turbo report with benchmarks and the
    provenance is a record."""
    if not isinstance(report, dict) or report.get("scenario") != "uplc-turbo":
        raise SummaryError("it is not a uplc-turbo report")
    if not isinstance(report.get("benchmarks"), list) or not report["benchmarks"]:
        raise SummaryError("it reports no benchmarks")
    scarab = record.get("scarab") if isinstance(record, dict) else None
    if not isinstance(scarab, dict):
        raise SummaryError("its provenance names no Scarab checkout")
    return report, str(scarab.get("commit", ""))


def summarize(report: dict, benchmarks: tuple[str, ...]) -> dict:
    """The memory dimension of `report` over the corpus `benchmarks`: a program not reported, or reported without
    both numbers, is not within the limit."""
    corpus = set(benchmarks)
    items = {item["bench"]: item for item in report.get("benchmarks", [])
             if isinstance(item, dict) and isinstance(item.get("bench"), str) and item["bench"] in corpus}
    verdicts = [(item, score.memory_within(item)) for item in items.values()]
    measured = [item for item, verdict in verdicts if verdict is not None]
    within = sum(1 for _, verdict in verdicts if verdict)
    ratios = sorted(item["mem_bytes"] / item["budget_mem"] for item in measured)
    return {
        "programs": len(benchmarks),
        "measured": len(measured),
        "within_memory_limit": within,
        "bytes_per_memory_unit_median": round(statistics.median(ratios), 3) if ratios else None,
        "bytes_per_memory_unit_p95": round(ratios[percentile_index(len(ratios), 95)], 3) if len(ratios) >= 20 else None,
        "allocated_bytes": round(sum(item["mem_bytes"] for item in measured)),
    }


def markdown(summary: dict, commit: str) -> str:
    return "\n".join([
        "### uplc-turbo: memory dimension",
        "",
        f"Scarab `{commit[:9]}`. Timings on this runner are not measurements and are left out.",
        "",
        "| Programs | Measured | Within the memory limit | Bytes per memory unit, median | 95th percentile "
        "| Bytes allocated |",
        "|---|---|---|---|---|---|",
        f"| {summary['programs']} | {summary['measured']} | {summary['within_memory_limit']} "
        f"| {summary['bytes_per_memory_unit_median']} | {summary['bytes_per_memory_unit_p95']} "
        f"| {summary['allocated_bytes']} |",
        "",
    ])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, required=True, help="the runner's --out")
    parser.add_argument("--inputs", type=Path, help="the runner's --inputs (default: OUT/inputs)")
    args = parser.parse_args(argv)
    reports = sorted(args.out.glob("scarab-*-uplc-turbo.json.gz"))
    if len(reports) != 1:
        print(f"{args.out} holds {len(reports)} uplc-turbo reports, not one", file=sys.stderr)
        return 1
    site = (args.inputs or args.out / "inputs") / fetching.load_pins()["site"].directory
    try:
        benchmarks = score.turbo_benchmarks(site / SCENARIOS["uplc-turbo"].schema)
        report, commit = checked(
            json.loads(gzip.decompress(reports[0].read_bytes())),
            json.loads(reports[0].with_name(
                reports[0].name.removesuffix(".json.gz") + ".provenance.json").read_text(encoding="utf-8")))
    except (OSError, EOFError, zlib.error, ValueError, score.ScoreError) as error:
        print(f"the uplc-turbo report cannot be summarised: {error}", file=sys.stderr)
        return 1
    summary = summarize(report, benchmarks)
    (args.out / "turbo-memory.json").write_text(
        json.dumps({"scarab_commit": commit, **summary}, indent=2) + "\n", encoding="utf-8")
    (args.out / "turbo-memory.md").write_text(markdown(summary, commit), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
