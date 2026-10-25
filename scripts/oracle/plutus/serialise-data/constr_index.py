#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the ConstrIndexCorpus requests through the pinned costing oracle and write the verdicts.

Each row keeps the reference stage and, on success, the CPU and memory the reference spent. Script
bytes and cost model are bound by SHA-256, as in `plutus-boundaries`.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

IMAGE = "scarab-plutus-costing-oracle:local"
BUDGET = re.compile(r"ExBudget \{exBudgetCPU = ExCPU ([0-9]+), exBudgetMemory = ExMemory ([0-9]+)\}")


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--requests", type=Path, required=True)
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("fixtures/reference/plutus-serialise-data/constr-index-verdicts.jsonl"))
    args = parser.parse_args()
    requests = [json.loads(line) for line in args.requests.read_text().splitlines()]
    with args.requests.open() as stdin, args.raw.open("w") as stdout:
        subprocess.run(["docker", "run", "--rm", "-i", IMAGE], stdin=stdin, stdout=stdout, check=True)
    results = [json.loads(line) for line in args.raw.read_text().splitlines()]
    assert len(results) == len(requests)
    rows = []
    for request, result in zip(requests, results):
        if "toolError" in result:
            raise ValueError(result)
        assert (request["id"], request["language"], request["protocol"]) == (result["id"], result["language"], result["protocol"])
        reference = result["reference"]
        row = {
            "id": request["id"],
            "language": request["language"],
            "protocol": request["protocol"],
            "scriptSha256": hashlib.sha256(bytes.fromhex(request["script"])).hexdigest(),
            "costModelSha256": sha(json.dumps(request["costModel"], separators=(",", ":"))),
            "admitted": reference["admitted"],
            "stage": reference["stage"],
        }
        if reference["stage"] == "success":
            match = BUDGET.fullmatch(reference["detail"])
            if match is None:
                raise ValueError(reference["detail"])
            row["cpu"], row["memory"] = map(int, match.groups())
        rows.append(row)
    args.out.write_text("".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows))


if __name__ == "__main__":
    main()
