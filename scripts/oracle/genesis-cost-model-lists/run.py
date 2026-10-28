#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build, check or replay the genesis cost-model corpus (issue #369).

The unchanged released libraries parse each genesis file and cost model (`FromJSON AlonzoGenesis`,
`FromJSON ConwayGenesis`, `parseCostModels`, `mkCostModel`; cardano-ledger `f649f975`
`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/CostModels.hs`), and the unchanged Plutus 1.70.0.0
evaluator runs scripts under cost-model lists of every length (the costing oracle of #287).

    python3 scripts/oracle/genesis-cost-model-lists/run.py            # offline: inputs, categories and hashes
    python3 scripts/oracle/genesis-cost-model-lists/run.py --replay   # run both images, compare every verdict
    python3 scripts/oracle/genesis-cost-model-lists/run.py --record   # rewrite the fixture and its provenance
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
DIRECTORY = ROOT / "fixtures/reference/genesis-cost-models"
GENESIS = DIRECTORY / "genesis.json"
SWEEP = DIRECTORY / "sweep.json"
EVALUATION = DIRECTORY / "evaluation.jsonl"
MODELS = DIRECTORY / "evaluation-models.json"
PROVENANCE = DIRECTORY / "provenance.json"
IMAGE = "scarab-genesis-cost-models369-oracle:local"
BASE_IMAGE = "scarab-plutus176-context-reference:local"
COSTING_IMAGE = "scarab-plutus-costing-oracle:local"
CATEGORIES = (
    "accepted", "notObject", "notObjectOrArray", "notArray", "notInt64", "missingField", "missingLanguage",
    "missingParameters", "parameterCount", "unknownKey",
)

_spec = importlib.util.spec_from_file_location("genesis_cost_model_cases", HERE / "cases.py")
CASES = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(CASES)


# Compared on 2026-10-25: each released source file read from the package archive in the base image
# (`~/.cache/cabal/packages/cardano-haskell-packages`) against the same path at the pin; all equal.
SOURCE_FILES = {
    "costModels": {
        "file": "libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/CostModels.hs",
        "sha256": "5fd2ab01dabc4a7998b46f1bce8a8db2ddd89912fc6d680416d5f9d5a1becf47",
        "matchesPin": True,
    },
    "alonzoGenesis": {
        "file": "eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Genesis.hs",
        "sha256": "dd471cdae7e509f0e60fc56a5d8bd4a1c33026f8fe2b7d9a589abaa8926ef752",
        "matchesPin": True,
    },
    "conwayPParams": {
        "file": "eras/conway/impl/src/Cardano/Ledger/Conway/PParams.hs",
        "sha256": "ba8712e61f23f067988b7d585194321a954ccedea2191df65e7d72f736db26ab",
        "matchesPin": True,
    },
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def docker_json(image, arguments, payload):
    """Run an image on one input file: no network, read-only root, input mounted read-only."""
    with tempfile.TemporaryDirectory() as scratch:
        (Path(scratch) / "in.json").write_text(json.dumps(payload))
        done = subprocess.run(
            ["docker", "run", "--rm", "--network", "none", "--read-only", "-v", f"{scratch}:/in:ro", image, *arguments],
            check=True, capture_output=True)
    return done.stdout


def run_genesis(cases):
    return json.loads(docker_json(IMAGE, ["/in/in.json"], [CASES.oracle_input(c) for c in cases]))


def sweeps():
    return {name: [c for c in CASES.sweep(number)] for number, name in ((1, "PlutusV1"), (2, "PlutusV2"), (3, "PlutusV3"))}


def run_sweeps():
    summary = {}
    for name, cases in sweeps().items():
        measured = run_genesis(cases)
        failures = [m["name"] for m in measured if not (m["reference"]["accepted"] and m["reference"]["result"]["valid"])]
        summary[name] = {"observations": len(cases), "refused": failures}
    return summary


def run_evaluation(requests):
    with tempfile.TemporaryFile(mode="w+") as inputs, tempfile.TemporaryFile(mode="w+") as outputs:
        for request in requests:
            inputs.write(json.dumps(request, separators=(",", ":")) + "\n")
        inputs.seek(0)
        subprocess.run(["docker", "run", "--rm", "-i", "--network", "none", "--read-only", COSTING_IMAGE],
                       stdin=inputs, stdout=outputs, check=True)
        outputs.seek(0)
        lines = outputs.read().splitlines()
    rows = []
    for request, line in zip(requests, lines):
        result = json.loads(line)
        require("toolError" not in result, f"tool error: {result}")
        require(result["id"] == request["id"], "order changed")
        reference = result["reference"]
        row = {"id": request["id"], "admitted": reference["admitted"], "stage": reference["stage"]}
        if reference["stage"] == "success":
            match = re.fullmatch(r"ExBudget \{exBudgetCPU = ExCPU \(?(-?[0-9]+)\)?, exBudgetMemory = ExMemory \(?(-?[0-9]+)\)?\}", reference["detail"])
            require(match is not None, reference["detail"])
            row["cpu"], row["memory"] = map(int, match.groups())
        rows.append(row)
    require(len(rows) == len(requests), "result count differs")
    return rows


def check(rows):
    names = [r["name"] for r in rows]
    require(len(names) == len(set(names)), "duplicate case names")
    for row in rows:
        require(row["category"] in CATEGORIES, f"unknown category: {row['name']}")
        verdict = row["reference"]
        require(verdict["accepted"] == (row["category"] == "accepted"), f"category contradicts the reference: {row['name']}")
        if row["kind"] == "mkCostModel":
            require(verdict["result"]["valid"], f"mkCostModel refused: {row['name']}")
    require({r["category"] for r in rows} == set(CATEGORIES), "a category has no case")


def inputs(rows):
    return [{k: row[k] for k in ("name", "kind", "category", "input")} for row in rows]


def verify():
    rows = json.loads(GENESIS.read_text())
    require(inputs(rows) == CASES.cases(), "fixture inputs differ from the generator")
    check(rows)
    ids = [json.loads(line)["id"] for line in EVALUATION.read_text().splitlines()]
    require(ids == [r["id"] for r in CASES.evaluation_requests()], "evaluation inputs differ from the generator")
    require(json.loads(MODELS.read_text()) == CASES.evaluation_models(), "evaluation models differ from the generator")
    provenance = json.loads(PROVENANCE.read_text())
    for name, path in (("genesisSha256", GENESIS), ("sweepSha256", SWEEP), ("evaluationSha256", EVALUATION), ("modelsSha256", MODELS),
                       ("harnessSha256", HERE / "Main.hs"), ("casesSha256", HERE / "cases.py")):
        require(provenance[name] == sha256(path), f"{name} differs from provenance")
    sweep = json.loads(SWEEP.read_text())
    require(all(not s["refused"] for s in sweep.values()), "a sweep observation was refused")
    return rows


def replay(rows):
    again = run_genesis(CASES.cases())
    for old, new in zip(rows, again):
        require(old["name"] == new["name"] and old["reference"] == new["reference"], f"verdict changed: {old['name']}")
    require(run_sweeps() == json.loads(SWEEP.read_text()), "sweep changed")
    measured = run_evaluation(CASES.evaluation_requests())
    require(measured == [json.loads(line) for line in EVALUATION.read_text().splitlines()], "evaluation changed")


def image_id(name):
    return subprocess.check_output(["docker", "image", "inspect", name, "--format", "{{.Id}}"], text=True).strip()


def record():
    cases = CASES.cases()
    measured = run_genesis(cases)
    rows = [{**case, "reference": m["reference"]} for case, m in zip(cases, measured)]
    check(rows)
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    GENESIS.write_text(json.dumps(rows, indent=1) + "\n")
    SWEEP.write_text(json.dumps(run_sweeps(), indent=1) + "\n")
    evaluation = run_evaluation(CASES.evaluation_requests())
    MODELS.write_text(json.dumps(CASES.evaluation_models(), indent=1) + "\n")
    EVALUATION.write_text("".join(json.dumps(row, separators=(",", ":")) + "\n" for row in evaluation))
    PROVENANCE.write_text(json.dumps({
        "schemaVersion": 1,
        "scope": "Genesis cost-model parsing (Alonzo and Conway genesis files, parseCostModels, mkCostModel) and Plutus "
                 "evaluation under cost-model lists of every length; not the application of a protocol-parameter update",
        "ledgerSourceCommit": "f649f9751074d2ab3de033fc3912f29c9862c1f5",
        "plutusSourceCommit": "507cab8cf6192e5143f12be5b2d3005a2928dc6d",
        "compiler": "GHC 9.10.3",
        "packages": {
            "cardano-ledger-alonzo": "1.16.0.0",
            "cardano-ledger-conway": "1.23.0.0",
            "cardano-ledger-core": "1.21.0.0",
            "plutus-core": "1.70.0.0",
            "plutus-ledger-api": "1.70.0.0",
        },
        "sourceFiles": SOURCE_FILES,
        "network": "none",
        "genesisSha256": sha256(GENESIS),
        "sweepSha256": sha256(SWEEP),
        "evaluationSha256": sha256(EVALUATION),
        "modelsSha256": sha256(MODELS),
        "harnessSha256": sha256(HERE / "Main.hs"),
        "casesSha256": sha256(HERE / "cases.py"),
        "imageId": image_id(IMAGE),
        "baseImageId": image_id(BASE_IMAGE),
        "costingImageId": image_id(COSTING_IMAGE),
        "cases": len(rows),
        "sweepObservations": sum(s["observations"] for s in json.loads(SWEEP.read_text()).values()),
        "evaluationObservations": len(evaluation),
    }, indent=1) + "\n")


def main():
    global IMAGE
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--replay", action="store_true", help="run the images and compare every verdict")
    parser.add_argument("--record", action="store_true", help="rewrite the fixture and its hashes from the images")
    parser.add_argument("--image", default=IMAGE, help=f"the genesis oracle image (default {IMAGE})")
    args = parser.parse_args()
    IMAGE = args.image
    if args.record:
        record()
        return 0
    rows = verify()
    if args.replay:
        replay(rows)
    print(f"{len(rows)} cases ok" + (" (replayed)" if args.replay else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
