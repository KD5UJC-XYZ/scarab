#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The matrix of the E2E devnet workflow (`.github/workflows/e2e.yml`): every scenario it runs, or the ones a
dispatch names.

    python3 scripts/ci_e2e_matrix.py                          # every scenario: the nightly run, a tag
    python3 scripts/ci_e2e_matrix.py "ntc, relay"             # only these, by their names in `SCENARIOS`
    python3 scripts/ci_e2e_matrix.py --repeat 3 "ntc"         # three jobs of ntc, each on a devnet of its own

The selection is names separated by spaces or commas; a name given twice runs once, and none runs every scenario.
`--repeat` (1 to `MAX_REPEAT`; empty is 1) runs each selected scenario that many times. The matrix keeps the order
of `SCENARIOS`, a scenario's copies together. Each job has a `job` name of its own, for the job, its devnet and its
artifact: the scenario's name, or with copies `<name>-1`, `<name>-2`, ... It is printed as
`{"include": [{"job": ..., "scenario": ..., "args": ...}, ...]}` and, when `GITHUB_OUTPUT` is set, written there as
`matrix=<json>`. A name that is not a scenario, or a repeat out of range, writes nothing and exits 2.

Pure standard library.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections.abc import Sequence
from typing import NamedTuple


class Scenario(NamedTuple):
    """A scenario of the workflow: its name, and the `devnet.py` arguments a job of it runs."""
    name: str
    args: str


# Every scenario the workflow runs, one job each, in this order.
SCENARIOS = (
    Scenario("soak", "soak --seconds 480"),
    Scenario("e2e", "e2e"),
    Scenario("relay", "relay --seconds 300"),
    Scenario("pool", "pool"),
    Scenario("pool-plutus", "pool --plutus-load"),
    Scenario("kes-rotate", "kes-rotate"),
    Scenario("dynamic-forging", "dynamic-forging"),
    Scenario("ntc", "ntc"),
    Scenario("restart", "restart"),
)
MAX_REPEAT = 5  # runs of each scenario one workflow run may ask for


class BadRepeat(ValueError):
    """A repeat that is not a whole number from 1 to `MAX_REPEAT`."""

    def __init__(self, text: str):
        super().__init__(f"repeat {text!r} is not a whole number from 1 to {MAX_REPEAT}")
        self.text = text


def repeat_of(text: str) -> int:
    """The copies a repeat asks for; empty (a run not started by hand) is 1."""
    text = text.strip()
    if not text:
        return 1
    if not re.fullmatch(r"[0-9]+", text) or not 1 <= int(text) <= MAX_REPEAT:
        raise BadRepeat(text)
    return int(text)


class UnknownScenarios(ValueError):
    """Names of a selection that are not scenarios of `SCENARIOS`."""

    def __init__(self, names: Sequence[str]):
        super().__init__(f"not E2E scenarios: {', '.join(names)}; "
                         f"the scenarios are {', '.join(s.name for s in SCENARIOS)}")
        self.names = tuple(names)


def names_of(selection: str) -> list[str]:
    """The names of a selection, separated by spaces or commas."""
    return [name for name in re.split(r"[\s,]+", selection) if name]


def select(selection: str) -> list[Scenario]:
    """The scenarios a selection names, in the order of `SCENARIOS`; all of them when it names none."""
    names = names_of(selection)
    if not names:
        return list(SCENARIOS)
    known = {s.name for s in SCENARIOS}
    unknown = [name for name in dict.fromkeys(names) if name not in known]
    if unknown:
        raise UnknownScenarios(unknown)
    return [s for s in SCENARIOS if s.name in names]


def matrix(scenarios: Sequence[Scenario], repeat: int = 1) -> dict:
    """The jobs: each scenario `repeat` times, named by `job` alone when once."""
    def job(s: Scenario, copy: int) -> str:
        return s.name if repeat == 1 else f"{s.name}-{copy}"

    return {"include": [{"job": job(s, copy), "scenario": s.name, "args": s.args}
                        for s in scenarios for copy in range(1, repeat + 1)]}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--repeat", default="", help=f"runs of each scenario, 1 to {MAX_REPEAT} (default 1)")
    parser.add_argument("selection", nargs="*", help="scenario names, separated by spaces or commas (default: all)")
    args = parser.parse_args(argv)
    try:
        chosen, repeat = select(" ".join(args.selection)), repeat_of(args.repeat)
    except (UnknownScenarios, BadRepeat) as error:
        print(f"e2e: {error}", file=sys.stderr)
        return 2
    text = json.dumps(matrix(chosen, repeat), separators=(",", ":"))
    print(text)
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"matrix={text}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
