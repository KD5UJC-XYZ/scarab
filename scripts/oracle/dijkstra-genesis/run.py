#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference verdicts of the Dijkstra genesis parser of the Leios testnet build (#54).

Each case is a Dijkstra genesis file of the repository, whole or with one change: a field removed,
a field of the wrong JSON type, or a value the reference's type refuses. The fixture keeps the
change, not the changed file, so the suite rebuilds the input from the base file.

  run.py            offline: the generated cases equal the recorded ones; the base files are unchanged
  run.py --replay   run the image and compare every verdict with the fixture
  run.py --record   run the image and rewrite the fixture and its provenance
"""
import argparse
import copy
import datetime
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
FIXTURE = ROOT / "fixtures/reference/dijkstra-genesis"
CASES_FILE = FIXTURE / "cases.jsonl"
PROVENANCE = FIXTURE / "provenance.json"
IMAGE = "scarab-dijkstra-genesis-oracle:local"
PROGRAM = "scripts/oracle/dijkstra-genesis/Main.hs"

BASES = {
    "leios-testnet": "fixtures/leios/testnet/config/dijkstra-genesis.json",
    "dijkstra-devnet": "fixtures/dijkstra-devnet/genesis/dijkstra-genesis.json",
}
# The file whose fields are changed one at a time.
MUTATED = "leios-testnet"
WORD16_MAX = 2**16 - 1
WORD32_MAX = 2**32 - 1
WORD64_MAX = 2**64 - 1
INT64_MAX = 2**63 - 1


def load(base):
    return json.loads((ROOT / BASES[base]).read_text())


def wrong(value):
    """A value of another JSON type than `value`."""
    return 0 if isinstance(value, str) else "x"


def case(base, op, path, value=None, label=None):
    name = f"{base}/{label or op}:{'.'.join(path) if path else '-'}"
    c = {"name": name, "base": BASES[base], "op": op, "path": list(path)}
    if op == "replace":
        c["value"] = value
    return c


def mutations():
    """Each field removed and given the wrong type, and one level into the execution units."""
    document = load(MUTATED)
    out = []
    for prefix in ["", "maxEndorserBlockExecutionUnits"]:
        path = prefix.split(".") if prefix else []
        node = document
        for key in path:
            node = node[key]
        for key, value in node.items():
            out.append(case(MUTATED, "remove", path + [key]))
            out.append(case(MUTATED, "replace", path + [key], wrong(value), "wrongType"))
    return out


def specials():
    """Values the reference's types decide: the bounds of each word, the intervals, the non-zero
    stride, the nullable fields, the execution units' names and the cost model's length."""
    half = {"numerator": 1, "denominator": 2}
    m = MUTATED
    out = [
        case(m, "replace", [], [], "notAnObject"),
        case(m, "replace", ["unknownField"], 1, "extraKey"),
        # `.:` of a `StrictMaybe Word32`: the key is required, null is SNothing
        case(m, "replace", ["perasBootstrapRound"], 5, "number"),
        case(m, "replace", ["perasBootstrapRound"], WORD32_MAX, "word32Max"),
        case(m, "replace", ["perasBootstrapRound"], WORD32_MAX + 1, "pastWord32"),
        case(m, "replace", ["perasBootstrapRound"], -1, "negative"),
        # `.:? "maxPledgeLeverage" .!= MaxPledgeLeverage SNothing`, a nullable NonNegativeInterval
        case(m, "replace", ["maxPledgeLeverage"], 2.5, "decimal"),
        case(m, "replace", ["maxPledgeLeverage"], half, "ratioObject"),
        case(m, "replace", ["maxPledgeLeverage"], -1, "negative"),
        case(m, "replace", ["maxPledgeLeverage"], WORD64_MAX + 1, "pastWord64"),
        # NonZero Word32
        case(m, "replace", ["refScriptCostStride"], 0, "zero"),
        case(m, "replace", ["refScriptCostStride"], 1, "one"),
        case(m, "replace", ["refScriptCostStride"], WORD32_MAX + 1, "pastWord32"),
        case(m, "replace", ["refScriptCostStride"], 25600.0, "wholeDecimal"),
        # PositiveInterval
        case(m, "replace", ["refScriptCostMultiplier"], 0, "zero"),
        case(m, "replace", ["refScriptCostMultiplier"], 1e-19, "epsilon"),
        case(m, "replace", ["refScriptCostMultiplier"], 1e-20, "tooPrecise"),
        case(m, "replace", ["refScriptCostMultiplier"], half, "ratioObject"),
        case(m, "replace", ["refScriptCostMultiplier"], {"numerator": 0, "denominator": 1}, "zeroObject"),
        case(m, "replace", ["refScriptCostMultiplier"], 12345.5, "aboveOne"),
        case(m, "replace", ["perasHealingFactor"], 0, "zero"),
        # UnitInterval
        case(m, "replace", ["minPoolMargin"], 1.5, "aboveOne"),
        case(m, "replace", ["minPoolMargin"], 1, "one"),
        case(m, "replace", ["leiosQuorumStakeThreshold"], half, "ratioObject"),
        case(m, "replace", ["leiosQuorumStakeThreshold"], -0.5, "negative"),
        case(m, "replace", ["perasQuorumThresholdSafetyMargin"], 1.01, "aboveOne"),
        # Word16, Word32 and the Word32 newtypes (Milliseconds32, SlotInterval)
        case(m, "replace", ["leiosCommitteeSize"], WORD16_MAX, "word16Max"),
        case(m, "replace", ["leiosCommitteeSize"], WORD16_MAX + 1, "pastWord16"),
        case(m, "replace", ["perasCertBoost"], WORD16_MAX + 1, "pastWord16"),
        case(m, "replace", ["perasTargetCommitteeSize"], -1, "negative"),
        case(m, "replace", ["maxRefScriptSizePerBlock"], WORD32_MAX, "word32Max"),
        case(m, "replace", ["maxRefScriptSizePerTx"], WORD32_MAX + 1, "pastWord32"),
        case(m, "replace", ["maxEndorserBlockTxsSize"], 1.5, "fraction"),
        case(m, "replace", ["leiosAnnouncementPeriodLength"], WORD32_MAX + 1, "pastWord32"),
        case(m, "replace", ["leiosVotePeriodLength"], -1, "negative"),
        case(m, "replace", ["leiosDiffusionPeriodLength"], 7e3, "exponent"),
        case(m, "replace", ["perasMinCandidateBlockAge"], WORD32_MAX + 1, "pastWord32"),
        # Coin (an Integer) and CoinPerByte (a Word64)
        case(m, "replace", ["refInputsCostPerMultiAssetPolicy"], -1, "negative"),
        case(m, "replace", ["refInputsCostPerMultiAssetPolicy"], WORD64_MAX + 1, "pastWord64"),
        case(m, "replace", ["refInputsCostPerMultiAssetPolicy"], 1.5, "fraction"),
        case(m, "replace", ["refInputsCostPerDatumByte"], -1, "negative"),
        case(m, "replace", ["refInputsCostPerDatumByte"], WORD64_MAX, "word64Max"),
        case(m, "replace", ["refInputsCostPerDatumByte"], WORD64_MAX + 1, "pastWord64"),
        # ExUnits: `o .: "memory" <|> o .: "exUnitsMem"`, then the Word64 bound
        case(m, "replace", ["maxEndorserBlockExecutionUnits"], {"exUnitsMem": 10, "exUnitsSteps": 20}, "longNames"),
        case(m, "replace", ["maxEndorserBlockExecutionUnits"], {"memory": "x", "exUnitsMem": 10, "steps": 20},
             "firstMalformed"),
        case(m, "replace", ["maxEndorserBlockExecutionUnits", "memory"], WORD64_MAX, "word64Max"),
        case(m, "replace", ["maxEndorserBlockExecutionUnits", "steps"], WORD64_MAX + 1, "pastWord64"),
    ]
    # `parseCostModelAsArray False PlutusV4`: an array of Int64 of exactly the initial count
    model = load(m)["plutusV4CostModel"]
    out += [
        case(m, "replace", ["plutusV4CostModel"], model[:-1], "short"),
        case(m, "replace", ["plutusV4CostModel"], model + [0], "long"),
        case(m, "replace", ["plutusV4CostModel"], [INT64_MAX] + model[1:], "int64Max"),
        case(m, "replace", ["plutusV4CostModel"], [INT64_MAX + 1] + model[1:], "pastInt64"),
        case(m, "replace", ["plutusV4CostModel"], [1.5] + model[1:], "fraction"),
        case(m, "replace", ["plutusV4CostModel"], {"addInteger-cpu-arguments-intercept": 1}, "object"),
    ]
    return out


def generate():
    cases = [case(label, "none", []) for label in BASES]
    cases.extend(mutations())
    cases.extend(specials())
    names = [c["name"] for c in cases]
    assert len(names) == len(set(names)), "case names must be unique"
    return cases


def apply(document, c):
    document = copy.deepcopy(document)
    if c["op"] == "none":
        return document
    if not c["path"]:
        return c["value"]
    *parents, last = c["path"]
    node = document
    for key in parents:
        node = node[key]
    if c["op"] == "remove":
        del node[last]
    else:
        node[last] = c["value"]
    return document


def oracle_input(c):
    return {"name": c["name"], "input": apply(json.loads((ROOT / c["base"]).read_text()), c)}


def run_image(cases):
    with tempfile.TemporaryDirectory() as scratch:
        (Path(scratch) / "in.json").write_text(json.dumps([oracle_input(c) for c in cases]))
        done = subprocess.run(
            ["docker", "run", "--rm", "--network", "none", "--read-only", "-v", f"{scratch}:/in:ro", IMAGE, "/in/in.json"],
            check=True, capture_output=True)
    results = json.loads(done.stdout)
    return [dict(c, reference=r["reference"]) for c, r in zip(cases, results, strict=True)]


def sha256(path):
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def bases_hashes():
    return {path: sha256(path) for path in BASES.values()}


def recorded():
    return [json.loads(line) for line in CASES_FILE.read_text().splitlines() if line]


def strip(c):
    return {k: v for k, v in c.items() if k != "reference"}


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--record", action="store_true")
    mode.add_argument("--replay", action="store_true")
    args = parser.parse_args()
    cases = generate()
    if args.record:
        results = run_image(cases)
        FIXTURE.mkdir(parents=True, exist_ok=True)
        CASES_FILE.write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in results))
        image = subprocess.check_output(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"], text=True).strip()
        PROVENANCE.write_text(json.dumps({
            "schemaVersion": 1,
            "scope": "The unchanged FromJSON DijkstraGenesis of the Leios testnet build: the repository's "
                     "Dijkstra genesis files whole, a single field removed or replaced, and values the types "
                     "decide; parser verdicts and the values read, no translation or rule execution",
            "image": IMAGE,
            "imageId": image,
            "ledger": {"repository": "IntersectMBO/cardano-ledger",
                       "revision": "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c",
                       "packages": ["cardano-ledger-dijkstra 0.5.0.0"]},
            "program": PROGRAM,
            "programSha256": sha256(PROGRAM),
            "bases": bases_hashes(),
            "cases": len(results),
            "casesSha256": sha256(CASES_FILE.relative_to(ROOT)),
            "measuredAt": datetime.date.today().isoformat(),
        }, indent=2, sort_keys=True) + "\n")
        print(f"recorded {len(results)} cases")
        return 0
    if [strip(c) for c in recorded()] != cases:
        print("the generated cases differ from the fixture: run --record", file=sys.stderr)
        return 1
    if json.loads(PROVENANCE.read_text())["bases"] != bases_hashes():
        print("a base genesis file changed: run --record", file=sys.stderr)
        return 1
    if args.replay:
        fresh = run_image(cases)
        differing = [f["name"] for f, r in zip(fresh, recorded(), strict=True) if f["reference"] != r["reference"]]
        if differing:
            print(f"{len(differing)} verdicts differ: {differing[:5]}", file=sys.stderr)
            return 1
    print(f"{len(cases)} cases agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
