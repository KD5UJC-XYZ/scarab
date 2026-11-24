#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Write turbo-memory.md and turbo-memory.json beside a runner's uplc-turbo report: its memory dimension alone.

    python3 -I -B scripts/conformance/turbo_memory.py --out <the runner's --out> [--inputs <its --inputs>]

For the uplc-turbo report in `--out` it counts, by the site's rule (`score.py`), the corpus's programs whose
allocated bytes are within the memory limit, and gives the median and 95th percentile of bytes per memory unit.
The bytes a program allocates depend on the JVM's settings and CPU count, which the runner fixes, not on the
machine's speed or load, so a shared runner reproduces these figures; it does not reproduce `cpu_us`, which this
summary leaves out. The exit status is 1 when `--out` holds no readable uplc-turbo report.
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


def summarize(report: dict, benchmarks: tuple[str, ...]) -> dict:
    """The memory dimension of `report` over the corpus `benchmarks`: a program not reported, or reported without
    both numbers, is not within the limit."""
    corpus = set(benchmarks)
    items = {item["bench"]: item for item in report.get("benchmarks", [])
             if isinstance(item, dict) and item.get("bench") in corpus}
    measured = [item for item in items.values()
                if score._number(item.get("mem_bytes")) and score._number(item.get("budget_mem"))
                and item["budget_mem"] > 0]
    within = sum(1 for item in measured
                 if score._within(item["mem_bytes"], item["budget_mem"], score.MEMORY_TOLERANCE_BYTES))
    ratios = sorted(item["mem_bytes"] / item["budget_mem"] for item in measured)
    return {
        "programs": len(benchmarks),
        "measured": len(measured),
        "within_memory_limit": within,
        "bytes_per_memory_unit_median": round(statistics.median(ratios), 3) if ratios else None,
        "bytes_per_memory_unit_p95": round(ratios[(len(ratios) * 95) // 100 - 1], 3) if len(ratios) >= 20 else None,
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
        report = json.loads(gzip.decompress(reports[0].read_bytes()))
        benchmarks = score.Site._turbo(site / SCENARIOS["uplc-turbo"].schema)
        record = json.loads(reports[0].with_name(
            reports[0].name.removesuffix(".json.gz") + ".provenance.json").read_text(encoding="utf-8"))
    except (OSError, EOFError, zlib.error, ValueError, score.ScoreError) as error:
        print(f"the uplc-turbo report cannot be summarised: {error}", file=sys.stderr)
        return 1
    summary = summarize(report, benchmarks)
    commit = str(record.get("scarab", {}).get("commit", ""))
    (args.out / "turbo-memory.json").write_text(
        json.dumps({"scarab_commit": commit, **summary}, indent=2) + "\n", encoding="utf-8")
    (args.out / "turbo-memory.md").write_text(markdown(summary, commit), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
