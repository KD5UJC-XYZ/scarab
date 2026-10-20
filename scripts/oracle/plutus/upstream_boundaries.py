#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Export the narrow upstream-input matrix with unchanged 1.70 cost parameters.

The caller runs requests through Main.hs in the released and bounds-backport images.
No upstream defaults or source pins are updated by this tool.
"""

import argparse
import json
from functools import reduce
from operator import getitem
from pathlib import Path


def parameters(resources: Path, language: int, protocol: int) -> list[int]:
    variant = ("C" if protocol < 11 else "E") if language == 3 else (
        "B" if protocol < 11 else "D"
    )
    builtin = json.loads((resources / f"builtinCostModel{variant}.json").read_text())
    machine = json.loads((resources / f"cekMachineCosts{variant}.json").read_text())

    def parameter(name: str) -> int:
        parts = name.split("'")
        keys = (parts[0][0].lower() + parts[0][1:], *parts[1:])
        return reduce(getitem, keys, machine if keys[0].startswith("cek") else builtin)

    return [parameter(name) for name in (
        resources / f"V{language}-param-names.txt"
    ).read_text().splitlines() if name]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    scripts = root / "fixtures/reference/plutus-upstream-boundaries/scripts.jsonl"
    resources = root / "plutus/src/test/resources/plutus-1.70.0.0"
    rows = [
        {
            "id": probe["id"],
            "script": probe["script"],
            "language": language,
            "protocol": protocol,
            "costModel": parameters(resources, language, protocol),
        }
        for probe in map(json.loads, scripts.read_text().splitlines())
        for language in (1, 2, 3)
        for protocol in (9, 10, 11)
    ]
    args.output.write_text("".join(
        json.dumps(row, separators=(",", ":")) + "\n" for row in rows
    ))


if __name__ == "__main__":
    main()
