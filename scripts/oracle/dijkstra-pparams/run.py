#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the Dijkstra protocol-parameter update cases and measure them with the oracle.

Each case is a `PParamsUpdate DijkstraEra` of version 12: a map of update keys to values, as the
reference encodes it, or as it does not (`noncanonical`, `malformed`). The oracle (`Main.hs`, image
`scarab-dijkstra-pparams-oracle:local`) decodes it at version 12 and prints `ppuWellFormed` at
versions 12.0 and 10.0, `modifiedPPGroups` and the update as the reference writes it, or the
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
sys.path.insert(0, str(ROOT / "scripts"))
from cleanup import container_labels  # noqa: E402

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
    return cases + lengths() + noncanonical() + malformed()


def raw(name, encoded):
    """A case of the bytes as written, not sorted or wrapped by `case`."""
    return {"id": name, "update": encoded.hex()}


def indef_arr(*items):
    return b"\x9f" + b"".join(items) + b"\xff"


def indef_map(pairs):
    return b"\xbf" + b"".join(uint(k) + v for k, v in pairs) + b"\xff"


def nint(n):
    """The integer -1 - n."""
    return head(1, n)


INT64_MIN, INT64_MAX = nint((1 << 63) - 1), uint((1 << 63) - 1)


def costs(count):
    return [uint(i) if i % 2 == 0 else nint(i) for i in range(count)]


def lengths():
    """Cost models on both sides of `lengthThreshold` (`Encoder.hs:442-468`): the reference writes a
    map of more than 23 languages, and a list of more than 23 parameters, of indefinite length."""
    def models(pairs):
        return [(18, cmap(pairs))]
    return [
        case("cost models of 23 languages", models([(i, arr()) for i in range(23)])),
        case("cost models of 24 languages", models([(i, arr()) for i in range(24)])),
        case("cost models of 30 languages", models([(i, arr(uint(i))) for i in range(30)])),
        case("a cost model of 23 parameters", models([(0, arr(*costs(23)))])),
        case("a cost model of 24 parameters", models([(0, arr(*costs(24)))])),
        case("an unknown language's cost model of 24 parameters", models([(9, arr(*costs(24)))])),
        case("a cost model of 200 parameters, Int64's bounds among them",
             models([(2, arr(INT64_MIN, INT64_MAX, *costs(198)))])),
        case("cost models of 24 languages of 24 parameters each",
             models([(i, arr(*costs(24))) for i in range(24)])),
    ]


def noncanonical():
    """Updates the reference accepts but does not write: indefinite maps and lists where it writes
    definite ones, wide heads, unsorted keys and an unreduced ratio."""
    return [
        raw("an indefinite update", indef_map([(0, uint(1))])),
        raw("an indefinite update of two keys", indef_map([(3, uint(16384)), (41, uint(4000))])),
        raw("update keys out of order", cmap([(1, uint(1)), (0, uint(1))])),
        raw("a wide key and a wide value", bytes.fromhex("a1 1800 1801")),
        raw("an eight-byte value", bytes.fromhex("a1 00 1b0000000000000001")),
        raw("a wide coin", bytes.fromhex("a1 181e 1a00000001")),
        raw("an unreduced ratio", cmap([(9, ratio(2, 4))])),
        raw("a ratio of an indefinite list", cmap([(9, head(6, 30) + indef_arr(uint(1), uint(2)))])),
        raw("a ratio of a wide tag and wide items", bytes.fromhex("a1 09 d9001e 82 1801 1a00000002")),
        raw("indefinite cost models", cmap([(18, indef_map([(0, arr())]))])),
        raw("an indefinite cost model", cmap([(18, cmap([(0, indef_arr(uint(1), uint(2)))]))])),
        raw("indefinite cost models of 24 languages", cmap([(18, indef_map([(i, arr()) for i in range(24)]))])),
        raw("an indefinite cost model of 24 parameters", cmap([(18, cmap([(0, indef_arr(*costs(24)))]))])),
        raw("cost-model languages out of order", cmap([(18, cmap([(1, arr()), (0, arr())]))])),
        raw("indefinite prices", cmap([(19, indef_arr(HALF, HALF))])),
        raw("indefinite execution units", cmap([(20, indef_arr(uint(1), uint(1)))])),
        raw("indefinite pool thresholds", cmap([(25, indef_arr(*[HALF] * 5))])),
        raw("indefinite DRep thresholds", cmap([(26, indef_arr(*[HALF] * 10))])),
        raw("indefinite endorser-block execution units", cmap([(47, indef_arr(uint(1), uint(1)))])),
    ]


def malformed():
    """Updates the reference refuses, most with two faults, so that which one it finds first is its
    verdict: a ratio's items before its length (`decodeIntegralRational`, `DecCBOR.hs:271-280`), a
    cost-model map's entries before a repeated language (`decodeMapByKey`, `Decoder.hs:801-832`), a
    record's fields before its length (`decodeRecordNamed`, `Decoder.hs:645-700`), and an update's
    entries in order (`decodeSparseKeyed`, `Decoder.hs:1228-1287`)."""
    tag30 = head(6, 30)
    return [
        raw("a ratio of three items, the third null", cmap([(9, tag30 + arr(uint(1), uint(2), NULL))])),
        raw("a ratio of three items, a zero denominator", cmap([(9, tag30 + arr(uint(1), uint(0), uint(5)))])),
        raw("a ratio of one null item", cmap([(9, tag30 + arr(NULL))])),
        raw("a ratio of a zero denominator", cmap([(9, ratio(1, 0))])),
        raw("an indefinite ratio of three items, the third null",
            cmap([(9, tag30 + indef_arr(uint(1), uint(2), NULL))])),
        raw("a ratio of a negative item", cmap([(9, tag30 + arr(nint(0), uint(2)))])),
        raw("cost models of a repeated language, its second list bad",
            cmap([(18, bytes.fromhex("a2 00 80 00 81 f6"))])),
        raw("cost models of a repeated language", cmap([(18, bytes.fromhex("a2 00 80 00 80"))])),
        raw("a cost-model language past Word8, its list bad", cmap([(18, bytes.fromhex("a1 190100 81 f6"))])),
        raw("a negative cost-model language", cmap([(18, bytes.fromhex("a1 20 80"))])),
        raw("a cost-model parameter as a bignum", cmap([(18, bytes.fromhex("a1 00 81 c24101"))])),
        raw("a cost-model parameter past Int64", cmap([(18, bytes.fromhex("a1 00 81 1b8000000000000000"))])),
        raw("indefinite execution units of one item", cmap([(20, indef_arr(uint(1)))])),
        raw("indefinite execution units of three items", cmap([(20, indef_arr(uint(1), uint(1), uint(1)))])),
        raw("execution units of one item, a key after it", bytes.fromhex("a2 14 81 01 15 82 01 01")),
        raw("execution units of one item, last", cmap([(20, arr(uint(1)))])),
        raw("execution units of three items, the first null", cmap([(20, arr(NULL, uint(0), uint(0)))])),
        raw("execution units of null", cmap([(20, NULL)])),
        raw("indefinite prices of one item", cmap([(19, indef_arr(HALF))])),
        raw("prices of three items, the first null", cmap([(19, arr(NULL, HALF, HALF))])),
        raw("indefinite pool thresholds of four", cmap([(25, indef_arr(*[HALF] * 4))])),
        raw("indefinite DRep thresholds of ten, the tenth null", cmap([(26, indef_arr(*[HALF] * 9, NULL))])),
        raw("a repeated key, its second value bad", bytes.fromhex("a2 00 01 00 f6")),
        raw("a bad value before an unknown key", bytes.fromhex("a2 1822 f6 1839 01")),
        raw("an unknown key after a good one", bytes.fromhex("a2 1822 01 1839 f6")),
        raw("a negative word", cmap([(0, nint(0))])),
        raw("a Word32 past its width", cmap([(3, bytes.fromhex("1b0000000100000000"))])),
    ]


def run_image(cases, name):
    done = subprocess.run(
        ["docker", "run", "--rm", "-i", "--name", name, *container_labels("oracle", name), "--network", "none",
         "--read-only", IMAGE],
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
    parser.add_argument("--name", default="dijkstra-pparams",
                        help="the container name and owner label of the oracle's run")
    args = parser.parse_args()
    cases = generate()
    if args.record:
        results = run_image(cases, args.name)
        FIXTURE.mkdir(parents=True, exist_ok=True)
        CASES_FILE.write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in results))
        image = subprocess.check_output(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"], text=True).strip()
        PROVENANCE.write_text(json.dumps({
            "schemaVersion": 1,
            "scope": "Dijkstra protocol-parameter updates of the Leios testnet build: the update decoded at "
                     "version 12, ppuWellFormed at versions 12.0 and 10.0, modifiedPPGroups and the decoded "
                     "update serialized at version 12",
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
        fresh = run_image(cases, args.name)
        differing = [f["id"] for f, r in zip(fresh, recorded(), strict=True) if f["reference"] != r["reference"]]
        if differing:
            print(f"{len(differing)} results differ: {differing[:5]}", file=sys.stderr)
            return 1
    print(f"{len(cases)} cases agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
