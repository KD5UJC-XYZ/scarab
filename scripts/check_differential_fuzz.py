#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The differential fuzz targets ran against the libsodium oracle, on as many cases as the run asked for, and both
sides agreed in each way often enough (#1418).

    check_differential_fuzz.py LOG --cases N --seeds S

`SodiumTargets.run` (fuzz tests) prints one `differential {...}` line per target: the number of its random cases,
and the tallies of its seeds and of its random run, by kind: cases both sides accepted (`accepted`), both refused
(`refused`), and accepted cases built with a point of order 2, 4 or 8 added to the key (`key2`, `key4`, `key8`) or
to Gamma (`gamma2`, ...). FLOORS is the one place the fuzz workflows' floors are kept: for each target, the most it
may divide the run's N cases per seed by (its `share` in `SodiumTargets.all` may be lower, never higher), and the
least fraction of the cases that leaves that each tally of its random run must reach. The check requires each
target's line, at least N // share * S random cases, and each listed tally at or above its fraction of that number,
rounded down, so that a short run is held only to the fractions that make a whole case; the suite itself requires
each kind once, its seeds included.

A target that was skipped prints no line, so the check fails then too: a skipped comparison is not a pass.

Exit 0 when every target meets its floors, 1 with each shortfall listed. Pure standard library.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import sys


@dataclass(frozen=True)
class Floor:
    share: int
    fractions: dict[str, float]


# The fractions are about a quarter of what the gate's seeds 1 to 4 gave (100000 cases per seed; the torsion kinds
# 0.52% to 0.69% of Ed25519's cases and 0.78% to 1.29% of the VRF's): a target whose generator stops reaching a kind
# falls below, a run of other seeds does not.
FLOORS = {
    "sodium-ed25519": Floor(2, {"accepted": 0.02, "refused": 0.5, "key2": 0.001, "key4": 0.001, "key8": 0.001}),
    "sodium-vrf-verify": Floor(8, {"accepted": 0.015, "refused": 0.5, "key2": 0.002, "key4": 0.002, "key8": 0.002,
                                   "gamma2": 0.002, "gamma4": 0.002, "gamma8": 0.002}),
    "sodium-vrf-prove": Floor(20, {"accepted": 0.99}),
}

LINE = re.compile(r"(?:^|\s)differential (\{.*\})\s*$")


def read_lines(text: str) -> dict[str, dict]:
    """The last `differential` line of each target in a log."""
    found = {}
    for line in text.splitlines():
        match = LINE.search(line)
        if match:
            record = json.loads(match.group(1))
            found[record["target"]] = record
    return found


def shortfalls(records: dict[str, dict], cases: int, seeds: int, floors: dict[str, Floor] = FLOORS) -> list[str]:
    """Each way the records fall short of the floors for a run of `cases` per seed and `seeds` seeds."""
    problems = []
    for target, floor in floors.items():
        record = records.get(target)
        if record is None:
            problems.append(f"{target}: no differential line, so it did not run against the libsodium oracle")
            continue
        least = cases // floor.share * seeds
        if record["cases"] < least:
            problems.append(f"{target}: {record['cases']} cases, fewer than {least} ({cases} // {floor.share} * {seeds})")
        run = record.get("run", {})
        for kind, fraction in floor.fractions.items():
            needed = int(fraction * least)
            if run.get(kind, 0) < needed:
                problems.append(f"{target}: {run.get(kind, 0)} {kind} in the random run, fewer than {needed} "
                                f"({fraction} of {least})")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("log", type=Path, help="the fuzz run's log")
    parser.add_argument("--cases", type=int, required=True, help="the run's cases per target and seed")
    parser.add_argument("--seeds", type=int, required=True, help="the run's number of seeds")
    args = parser.parse_args(argv)
    problems = shortfalls(read_lines(args.log.read_text(errors="replace")), args.cases, args.seeds)
    for problem in problems:
        print(f"::error::{problem}")
    if not problems:
        print(f"differential fuzz: {', '.join(FLOORS)} ran and met their floors")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
