#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the Dijkstra protocol-parameter update cases and measure them with the oracle.

Each case is a `PParamsUpdate DijkstraEra` as the reference encodes it from version 12: a map of
update keys to values. The oracle (`Main.hs`, image `scarab-dijkstra-pparams-oracle:local`) decodes
it at version 12 and prints `ppuWellFormed` at versions 12.0 and 10.0 and `modifiedPPGroups`, or the
decoder's refusal.

    python3 scripts/oracle/dijkstra-pparams/run.py            # offline: the cases match the fixture
    python3 scripts/oracle/dijkstra-pparams/run.py --replay   # run the image and compare
    python3 scripts/oracle/dijkstra-pparams/run.py --record   # rewrite the fixture and its provenance
"""

import argparse
import datetime
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
FIXTURE = ROOT / "fixtures/reference/dijkstra-pparams"
CASES_FILE = FIXTURE / "cases.jsonl"
PROVENANCE = FIXTURE / "provenance.json"
PROGRAM = "scripts/oracle/dijkstra-pparams/Main.hs"
IMAGE = "scarab-dijkstra-pparams-oracle:local"


def head(major, n):
    m = major << 5
    if n < 24:
        return bytes([m | n])
    for code, width in ((24, 1), (25, 2), (26, 4), (27, 8)):
        if n < 1 << (8 * width):
            return bytes([m | code]) + n.to_bytes(width, "big")
    raise ValueError(n)


def uint(n):
    return head(0, n)


def arr(*items):
    return head(4, len(items)) + b"".join(items)


def cmap(pairs):
    return head(5, len(pairs)) + b"".join(uint(k) + v for k, v in pairs)


NULL = bytes([0xF6])


def ratio(n, d):
    """A bounded ratio, tag 30 of numerator and denominator."""
    return head(6, 30) + arr(uint(n), uint(d))


ZERO_RATIO = ratio(0, 1)
HALF = ratio(1, 2)

# Per update key, the encoding of a zero value and of a non-zero one, in the fields' order
# (`Dijkstra/PParams.hs:161-300`); a value the decoder refuses is the refused case's.
WORD = (uint(0), uint(1))
INTERVAL = (ZERO_RATIO, HALF)
EX_UNITS = (arr(uint(0), uint(0)), arr(uint(1), uint(1)))
FIELDS = {
    0: ("txFeePerByte", WORD), 1: ("txFeeFixed", WORD), 2: ("maxBBSize", WORD),
    3: ("maxTxSize", WORD), 4: ("maxBHSize", WORD), 5: ("keyDeposit", WORD),
    6: ("poolDeposit", WORD), 7: ("eMax", WORD), 8: ("nOpt", WORD), 9: ("a0", INTERVAL),
    10: ("rho", INTERVAL), 11: ("tau", INTERVAL), 16: ("minPoolCost", WORD),
    17: ("coinsPerUTxOByte", WORD), 18: ("costModels", (cmap([]), cmap([(0, arr())]))),
    19: ("prices", (arr(ZERO_RATIO, ZERO_RATIO), arr(HALF, HALF))),
    20: ("maxTxExUnits", EX_UNITS), 21: ("maxBlockExUnits", EX_UNITS), 22: ("maxValSize", WORD),
    23: ("collateralPercentage", WORD), 24: ("maxCollateralInputs", WORD),
    25: ("poolVotingThresholds", (arr(*[ZERO_RATIO] * 5), arr(*[HALF] * 5))),
    26: ("drepVotingThresholds", (arr(*[ZERO_RATIO] * 10), arr(*[HALF] * 10))),
    27: ("committeeMinSize", WORD), 28: ("committeeMaxTermLength", WORD),
    29: ("govActionLifetime", WORD), 30: ("govActionDeposit", WORD), 31: ("drepDeposit", WORD),
    32: ("drepActivity", WORD), 33: ("minFeeRefScriptCostPerByte", INTERVAL),
    34: ("maxRefScriptSizePerBlock", WORD), 35: ("maxRefScriptSizePerTx", WORD),
    36: ("refScriptCostStride", WORD), 37: ("refScriptCostMultiplier", INTERVAL),
    38: ("maxPledgeLeverage", (NULL, HALF)), 39: ("minPoolMargin", INTERVAL),
    40: ("leiosAnnouncementPeriodLength", WORD), 41: ("leiosVotePeriodLength", WORD),
    42: ("leiosDiffusionPeriodLength", WORD), 43: ("leiosCommitteeSize", WORD),
    44: ("leiosQuorumStakeThreshold", INTERVAL), 45: ("maxEndorserBlockReferencesSize", WORD),
    46: ("maxEndorserBlockTxsSize", WORD), 47: ("maxEndorserBlockExUnits", EX_UNITS),
    48: ("maxRefScriptSizePerEndorserBlock", WORD), 49: ("perasMinCandidateBlockAge", WORD),
    50: ("perasHealingFactor", INTERVAL), 51: ("perasCertBoost", WORD),
    52: ("perasTargetCommitteeSize", WORD), 53: ("perasBootstrapRound", (NULL, uint(1))),
    54: ("perasQuorumThresholdSafetyMargin", INTERVAL),
    55: ("refInputsCostPerMultiAssetPolicy", WORD), 56: ("refInputsCostPerDatumByte", WORD),
}


def case(name, pairs):
    return {"id": name, "update": cmap(sorted(pairs)).hex()}


def generate():
    cases = [case("empty", [])]
    for key, (name, (zero, one)) in FIELDS.items():
        cases.append(case(f"{name} zero", [(key, zero)]))
        cases.append(case(f"{name} non-zero", [(key, one)]))
    every_one = [(k, one) for k, (_, (_, one)) in FIELDS.items()]
    cases.append(case("every field non-zero", every_one))
    cases.append(case("every field non-zero but nOpt", [(k, v) if k != 8 else (8, uint(0)) for k, v in every_one]))
    cases.append(case("every field non-zero but eMax", [(k, v) if k != 7 else (7, uint(0)) for k, v in every_one]))
    cases.append(case("a size and a Leios duration", [(3, uint(16384)), (41, uint(4000))]))
    cases.append(case("a zero deposit among others", [(5, uint(2)), (6, uint(0)), (43, uint(600))]))
    cases.append(case("an unknown key", [(57, uint(1))]))
    return cases


def run_image(cases):
    done = subprocess.run(
        ["docker", "run", "--rm", "-i", "--network", "none", "--read-only", IMAGE],
        input="".join(json.dumps(c) + "\n" for c in cases), check=True, capture_output=True, text=True)
    results = [json.loads(line) for line in done.stdout.splitlines() if line]
    out = []
    for c, r in zip(cases, results, strict=True):
        if r["id"] != c["id"]:
            raise ValueError(f"out of order: {r['id']} for {c['id']}")
        out.append(dict(c, reference={k: v for k, v in r.items() if k != "id"}))
    return out


def sha256(path):
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


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
            "scope": "Dijkstra protocol-parameter updates of the Leios testnet build: the update decoded at "
                     "version 12, ppuWellFormed at versions 12.0 and 10.0 and modifiedPPGroups",
            "image": IMAGE,
            "imageId": image,
            "ledger": {"repository": "IntersectMBO/cardano-ledger",
                       "revision": "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c",
                       "packages": ["cardano-ledger-dijkstra 0.5.0.0"]},
            "program": PROGRAM,
            "programSha256": sha256(PROGRAM),
            "cases": len(results),
            "casesSha256": sha256(CASES_FILE.relative_to(ROOT)),
            "measuredAt": datetime.date.today().isoformat(),
        }, indent=2, sort_keys=True) + "\n")
        print(f"recorded {len(results)} cases")
        return 0
    if [strip(c) for c in recorded()] != cases:
        print("the generated cases differ from the fixture: run --record", file=sys.stderr)
        return 1
    if args.replay:
        fresh = run_image(cases)
        differing = [f["id"] for f, r in zip(fresh, recorded(), strict=True) if f["reference"] != r["reference"]]
        if differing:
            print(f"{len(differing)} results differ: {differing[:5]}", file=sys.stderr)
            return 1
    print(f"{len(cases)} cases agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
