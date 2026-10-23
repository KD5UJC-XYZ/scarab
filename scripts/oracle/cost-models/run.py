# SPDX-License-Identifier: Apache-2.0
"""Build, check or replay the pre-Conway cost-model decoding corpus (issue #332).

Every case is a whole transaction `[body, {}, true, null]` whose body carries one genesis-key
update proposal that sets only protocol-parameter field 18 (cost models). The unchanged released
Alonzo and Babbage ledger decoders read it at protocol version 5 and 7 respectively
(`decodeCostModelsFailing`, `decodeCostModelLegacy`, cardano-ledger `f649f975`
`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/CostModels.hs:302-337`).

    python3 scripts/oracle/cost-models/run.py            # offline: fixture inputs and hashes
    python3 scripts/oracle/cost-models/run.py --replay   # run the oracle image, compare verdicts
    python3 scripts/oracle/cost-models/run.py --record   # rewrite the fixture from the image
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = ROOT / "scripts/oracle/cost-models"
DIRECTORY = ROOT / "fixtures/reference/cost-models"
FIXTURE = DIRECTORY / "legacy-update.json"
PROVENANCE = DIRECTORY / "provenance.json"
IMAGE = "scarab-cost-models332-oracle:local"
ERAS = ("alonzo", "babbage")
CATEGORIES = ("accepted", "unsupportedLanguage", "length", "unknownTag", "outOfRange", "malformed")


def head(major, n):
    if n < 24:
        return bytes([(major << 5) | n])
    if n < 256:
        return bytes([(major << 5) | 24, n])
    if n < 65536:
        return bytes([(major << 5) | 25]) + n.to_bytes(2, "big")
    return bytes([(major << 5) | 27]) + n.to_bytes(8, "big")


def integer(v):
    if v > 2**64 - 1 or v < -(2**64):
        magnitude = v if v >= 0 else -1 - v
        raw = magnitude.to_bytes((magnitude.bit_length() + 7) // 8, "big")
        return head(6, 2 if v >= 0 else 3) + head(2, len(raw)) + raw
    return head(0, v) if v >= 0 else head(1, -1 - v)


def array(items):
    return head(4, len(items)) + b"".join(items)


def values(n, first=None, start=1):
    return [integer(first if first is not None and i == 0 else start + i) for i in range(n)]


def model(n, first=None, start=1):
    return array(values(n, first, start))


def models(*entries):
    return head(5, len(entries)) + b"".join(k + v for k, v in entries)


def indefinite_models(*entries):
    return b"\xbf" + b"".join(k + v for k, v in entries) + b"\xff"


def lang(n):
    return integer(n)


GENESIS_KEY = b"\x58\x1c" + b"\x11" * 28
INT64_MIN, INT64_MAX = -(2**63), 2**63 - 1


def transaction(cost_models):
    # {0: [], 1: [], 2: 0, 6: [{genesis key: {18: cost models}}, epoch 1]}, no witnesses, valid, no auxiliary data.
    body = (b"\xa4\x00\x80\x01\x80\x02\x00\x06\x82\xa1" + GENESIS_KEY + b"\xa1\x12" + cost_models + b"\x01")
    return b"\x84" + body + b"\xa0\xf5\xf6"


def corpus():
    """Name, expected category of the typed Scarab refusal, its detail and the cost-model map of field 18."""
    v1, v2 = 166, 175
    ok = "accepted"

    out = [
        ("v1-166", ok, {}, models((lang(0), model(v1)))),
        ("v2-175", ok, {}, models((lang(1), model(v2, start=1000)))),
        ("v1-and-v2", ok, {}, models((lang(0), model(v1)), (lang(1), model(v2, start=1000)))),
        ("v2-and-v1", ok, {}, models((lang(1), model(v2, start=1000)), (lang(0), model(v1)))),
        ("empty-map", ok, {}, models()),
        ("v1-int64-max-first", ok, {}, models((lang(0), model(v1, INT64_MAX)))),
        ("v1-int64-min-first", ok, {}, models((lang(0), model(v1, INT64_MIN)))),
        ("v2-int64-max-first", ok, {}, models((lang(1), model(v2, INT64_MAX)))),
        ("v2-int64-min-first", ok, {}, models((lang(1), model(v2, INT64_MIN)))),
        ("v1-indefinite-array", ok, {}, models((lang(0), b"\x9f" + b"".join(values(v1)) + b"\xff"))),
        ("map-indefinite", ok, {}, indefinite_models((lang(0), model(v1)))),
        ("v1-duplicate-distinct-values", ok, {}, models((lang(0), model(v1, start=1)), (lang(0), model(v1, start=2)))),
        ("v2-duplicate-distinct-values", ok, {}, models((lang(1), model(v2, start=1)), (lang(1), model(v2, start=2)))),
        ("v1-v2-v1-interleaved", ok, {},
         models((lang(0), model(v1, start=1)), (lang(1), model(v2, start=1000)), (lang(0), model(v1, start=2)))),
    ]
    short = models((lang(0), model(v1)), (lang(0), model(165)))
    out += [
        ("v1-duplicate-second-short", "length", {"language": 0, "expected": 166, "actual": 165}, short),
        ("v1-duplicate-first-short", "length", {"language": 0, "expected": 166, "actual": 165},
         models((lang(0), model(165)), (lang(0), model(v1)))),
    ]
    out += [(f"v1-length-{n}", "length", {"language": 0, "expected": 166, "actual": n}, models((lang(0), model(n))))
            for n in (0, 1, 165, 167, 175, 251)]
    out += [(f"v2-length-{n}", "length", {"language": 1, "expected": 175, "actual": n}, models((lang(1), model(n))))
            for n in (0, 1, 166, 174, 176, 251)]
    unsupported = "unsupportedLanguage"
    out += [
        ("language-2-251", unsupported, {"language": 2}, models((lang(2), model(251)))),
        ("language-3-251", unsupported, {"language": 3}, models((lang(3), model(251)))),
        ("language-2-empty", unsupported, {"language": 2}, models((lang(2), model(0)))),
        ("language-2-int64-overflow", unsupported, {"language": 2}, models((lang(2), model(3, 2**63)))),
        ("language-2-after-v1", unsupported, {"language": 2}, models((lang(0), model(v1)), (lang(2), model(251)))),
        ("language-4", "unknownTag", {"tag": "4"}, models((lang(4), model(10)))),
        ("language-255", "unknownTag", {"tag": "255"}, models((lang(255), model(10)))),
        ("language-256", "unknownTag", {"tag": "256"}, models((lang(256), model(10)))),
        ("language-negative-1", "unknownTag", {"tag": "-1"}, models((lang(-1), model(10)))),
        ("language-4-int64-overflow", "unknownTag", {"tag": "4"}, models((lang(4), model(3, 2**63)))),
        ("language-text", "malformed", {}, models((head(3, 1) + b"a", model(10)))),
        ("v1-overflow-2^63-first", "outOfRange", {"value": str(2**63)}, models((lang(0), model(v1, 2**63)))),
        ("v1-underflow-first", "outOfRange", {"value": str(-(2**63) - 1)}, models((lang(0), model(v1, -(2**63) - 1)))),
        ("v2-overflow-2^63-first", "outOfRange", {"value": str(2**63)}, models((lang(1), model(v2, 2**63)))),
        ("v2-underflow-first", "outOfRange", {"value": str(-(2**63) - 1)}, models((lang(1), model(v2, -(2**63) - 1)))),
        ("v1-bignum-2^64", "malformed", {}, models((lang(0), model(v1, 2**64)))),
        ("v1-bignum-small", "malformed", {}, models((lang(0), array([b"\xc2\x41\x01"] + values(v1 - 1))))),
        ("v1-overflow-then-short", "outOfRange", {"value": str(2**63)}, models((lang(0), model(165, 2**63)))),
        ("v1-text-entry", "malformed", {}, models((lang(0), array([head(3, 1) + b"a"])))),
        ("v1-entry-not-array", "malformed", {}, models((lang(0), integer(5)))),
        ("v1-null-entry", "malformed", {}, models((lang(0), b"\xf6"))),
    ]
    return out


def cases():
    return [
        {"name": f"{era}/{name}", "era": era, "expect": category, "detail": detail, "tx": transaction(cm).hex()}
        for era in ERAS for name, category, detail, cm in corpus()
    ]


# Compared by hand on 2026-10-22: each released source file read from the package archive in the
# base image (`~/.cache/cabal/packages/cardano-haskell-packages`) against the same path at the pin.
SOURCES = {
    "schemaVersion": 1,
    "scope": "Unchanged released Alonzo and Babbage transaction decoders at protocol versions 5 and 7, "
             "update proposals that set only cost models; not UTXO/LEDGER admission, genesis or Plutus evaluation",
    "ledgerSourceCommit": "f649f9751074d2ab3de033fc3912f29c9862c1f5",
    "compiler": "GHC 9.10.3",
    "referenceNode": "11.1.3 (matching embedded ledger package versions, not a bit-identical node release build)",
    "packages": {
        "cardano-ledger-alonzo": "1.16.0.0",
        "cardano-ledger-babbage": "1.14.0.0",
        "cardano-ledger-shelley": "1.19.0.1",
        "cardano-ledger-core": "1.21.0.0",
        "cardano-ledger-binary": "1.9.1.0",
    },
    "sourceFiles": {
        "costModels": {
            "file": "libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/CostModels.hs",
            "sourceSha256": "5fd2ab01dabc4a7998b46f1bce8a8db2ddd89912fc6d680416d5f9d5a1becf47",
            "matchesPin": True,
            "archiveSha256": "9789ca4b4959048ccb881aeabf623ce4514394e31e5a270b6b301dbcdaf1810a",
        },
        "alonzoPParams": {
            "file": "eras/alonzo/impl/src/Cardano/Ledger/Alonzo/PParams.hs",
            "sourceSha256": "5c494a8339bacbbc3ca0537b7f31527074afdad64f6743a19386158caaa307f1",
            "matchesPin": True,
            "archiveSha256": "195f1b9dac59bcdd8d4f499275f450bc37ee57169efee0fadf0d1676bc37cae5",
        },
        "babbagePParams": {
            "file": "eras/babbage/impl/src/Cardano/Ledger/Babbage/PParams.hs",
            "sourceSha256": "dd631dc8a2771d0c259e2c1d41edeacb45fae270530a983924ba1b17abe4a862",
            "matchesPin": True,
            "archiveSha256": "8a0466673e9de9f0a2375cd137fb1b6368c901929865b0def41abd83a200730d",
        },
        "binaryDecoder": {
            "file": "libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs",
            "sourceSha256": "cf8c16d44c36d7e3fb288028a9742d406c6042bec0f6fc75be566be2d319019a",
            "matchesPin": False,
            "difference": "two lines, the IPv4 octet order (`getWord32be` against `getWord32le`, lines 266 and 1363); "
                          "`decodeMapByKey` (lines 806-830) is identical",
            "archiveSha256": "05d59229edffc94b8f61273117456601532e23d8656f3076775bbf38e600bb8a",
        },
    },
    "originalBodyBytesRetained": True,
    "network": "none",
    "keysRetained": False,
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def inputs(rows):
    return [{k: row[k] for k in ("name", "era", "expect", "detail", "tx")} for row in rows]


def run_oracle(rows):
    """The oracle reads the fixture shape itself; no network, read-only root, input mounted read-only."""
    scratch = Path(subprocess.check_output(["mktemp", "-d"], text=True).strip())
    try:
        (scratch / "in.json").write_text(json.dumps(inputs(rows)))
        done = subprocess.run(
            ["docker", "run", "--rm", "--network", "none", "--read-only", "-v", f"{scratch}:/in:ro",
             IMAGE, "/in/in.json"],
            check=True, capture_output=True)
        return done.stdout
    finally:
        for p in scratch.iterdir():
            p.unlink()
        scratch.rmdir()


def check(rows):
    names = [r["name"] for r in rows]
    require(len(names) == len(set(names)), "duplicate case names")
    for row in rows:
        require(row["expect"] in CATEGORIES, f"unknown category: {row['name']}")
        verdict = row["reference"]
        require(verdict["accepted"] == (row["expect"] == "accepted"), f"category contradicts the reference: {row['name']}")
        require(("failure" in verdict) == (not verdict["accepted"]), f"verdict shape: {row['name']}")
    for era in ERAS:
        require({r["name"].split("/", 1)[1] for r in rows if r["era"] == era}
                == {name for name, _, _, _ in corpus()}, f"{era} lacks a case")


def verify():
    rows = json.loads(FIXTURE.read_text())
    require(inputs(rows) == cases(), "fixture inputs differ from the generator")
    check(rows)
    provenance = json.loads(PROVENANCE.read_text())
    require(provenance["fixtureSha256"] == sha256(FIXTURE), "fixture hash differs from provenance")
    require(provenance["harnessSha256"] == sha256(HERE / "Main.hs"), "harness changed since the measurement")
    return rows


def replay(rows):
    stdout = run_oracle(rows)
    again = json.loads(stdout)
    require(len(again) == len(rows), "case count differs")
    for old, new in zip(rows, again):
        require(old["name"] == new["name"] and old["tx"] == new["tx"], "case order or bytes changed")
        require(old["reference"] == new["reference"], f"verdict changed: {old['name']}")
    return stdout


def record():
    rows = cases()
    stdout = run_oracle(rows)
    measured = json.loads(stdout)
    by_name = {m["name"]: m for m in measured}
    out = [{**row, "reference": by_name[row["name"]]["reference"]} for row in rows]
    check(out)
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_text(json.dumps(out, indent=1) + "\n")
    image = subprocess.check_output(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"], text=True).strip()
    base = subprocess.check_output(
        ["docker", "image", "inspect", "scarab-plutus176-context-reference:local", "--format", "{{.Id}}"], text=True).strip()
    PROVENANCE.write_text(json.dumps({
        **SOURCES,
        "fixtureSha256": sha256(FIXTURE),
        "harnessSha256": sha256(HERE / "Main.hs"),
        "originalStdoutSha256": hashlib.sha256(stdout).hexdigest(),
        "imageId": image,
        "baseImageId": base,
        "cases": len(out),
    }, indent=1) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--replay", action="store_true", help="run the oracle image and compare every verdict")
    parser.add_argument("--record", action="store_true", help="rewrite the fixture and its hashes from the image")
    args = parser.parse_args()
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
