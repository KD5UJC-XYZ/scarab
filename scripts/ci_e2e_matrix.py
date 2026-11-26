#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The matrix of the E2E devnet workflow (`.github/workflows/e2e.yml`): every scenario it runs, or the ones a
dispatch names.

    python3 scripts/ci_e2e_matrix.py                  # every scenario: the nightly run, a tag, an empty selection
    python3 scripts/ci_e2e_matrix.py "ntc, relay"     # only these, by their names in `SCENARIOS`

The selection is names separated by spaces or commas; a name given twice runs once. The matrix keeps the order of
`SCENARIOS`. It is printed as `{"include": [{"scenario": ..., "args": ...}, ...]}` and, when `GITHUB_OUTPUT` is set,
written there as `matrix=<json>`. A name that is not a scenario writes nothing and exits 2, naming the known ones.

Pure standard library.
"""
from __future__ import annotations

import json
import os
import re
import sys
from collections.abc import Sequence
from typing import NamedTuple


class Scenario(NamedTuple):
    """One job of the matrix: its name (the job's, the devnet's and the artifact's) and the `devnet.py` arguments."""
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


def matrix(scenarios: Sequence[Scenario]) -> dict:
    return {"include": [{"scenario": s.name, "args": s.args} for s in scenarios]}


def main(argv: Sequence[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else list(argv)
    try:
        chosen = select(" ".join(args))
    except UnknownScenarios as error:
        print(f"e2e: {error}", file=sys.stderr)
        return 2
    text = json.dumps(matrix(chosen), separators=(",", ":"))
    print(text)
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"matrix={text}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
