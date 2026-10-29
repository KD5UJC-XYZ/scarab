#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference verdicts of the Shelley, Alonzo and Conway genesis parsers for #497.

Each case is a genesis file of the repository, whole or with one change: a field removed, a field
of the wrong JSON type, or a value the reference's type refuses. The fixture keeps the change, not
the changed file, so the suite rebuilds the input from the base file.

  run.py            offline: the generated cases equal the recorded ones; the base files are unchanged
  run.py --replay   run the image and compare every verdict with the fixture
  run.py --record   run the image and rewrite the fixture and its provenance
"""
import argparse
import copy
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
FIXTURE = ROOT / "fixtures/reference/genesis-fields"
CASES_FILE = FIXTURE / "cases.jsonl"
PROVENANCE = FIXTURE / "provenance.json"
IMAGE = "scarab-genesis-fields497-oracle:local"

ERAS = {"shelley": "shelleyGenesis", "alonzo": "alonzoGenesis", "conway": "conwayGenesis"}
WORD64_MAX = 2**64 - 1
# Directories of whole genesis sets, and the refusal corpus's single files.
SETS = {
    "mainnet": "fixtures/mainnet/genesis",
    "preprod": "fixtures/preprod/genesis",
    "preview": "fixtures/preview/genesis",
    "eras": "fixtures/eras/genesis",
    "devnet-lsq": "fixtures/devnet/lsq/genesis",
    "chain-dep": "fixtures/chain-dep/genesis",
    "leios-testnet": "fixtures/leios/testnet/config",
    "ntc-peer-relays": "fixtures/reference/ntc-wallet-peers-11.1.3/peer-relays/genesis",
    "ntc-reward-history": "fixtures/reference/ntc-wallet-peers-11.1.3/reward-history/genesis",
    "ntc-reward-later": "fixtures/reference/ntc-wallet-peers-11.1.3/reward-later/genesis",
    "horizon-alonzo": "fixtures/reference/plutus-forecast-horizon/alonzo-horizon/genesis",
    "horizon-babbage": "fixtures/reference/plutus-forecast-horizon/babbage-horizon/genesis",
    "horizon-babbage-known": "fixtures/reference/plutus-forecast-horizon/babbage-known-horizon/genesis",
    "horizon-conway": "fixtures/reference/plutus-forecast-horizon/conway-past-horizon/genesis",
}
CORPUS = "fixtures/reference/refusals/genesis"


def bases():
    """Every Shelley, Alonzo and Conway genesis file of the repository: (label, era, path)."""
    out = [(label, era, f"{d}/{era}-genesis.json") for label, d in SETS.items() for era in ERAS]
    for path in sorted((ROOT / CORPUS).glob("*-genesis.json")):
        era = path.name.rsplit("-", 2)[1]
        if era in ERAS:
            out.append((f"refusals-{path.name[:8]}", era, str(path.relative_to(ROOT))))
    return out


def base_path(label, era):
    return next(p for b, e, p in bases() if b == label and e == era)


def load(label, era):
    return json.loads((ROOT / base_path(label, era)).read_text())


def wrong(value):
    """A value of another JSON type than `value`."""
    return 0 if isinstance(value, str) else "x"


# The set whose files are changed one field at a time.
MUTATED = "preview"


def case(era, base, op, path, value=None, label=None):
    name = f"{era}/{base}/{label or op}:{'.'.join(path) if path else '-'}"
    c = {"name": name, "kind": ERAS[era], "base": base_path(base, era), "op": op, "path": list(path)}
    if op == "replace":
        c["value"] = value
    return c


def mutations(era, document, base):
    """Each field removed and given the wrong type, one level into the objects the parsers read."""
    nested = {
        "shelley": ["protocolParams", "protocolParams.protocolVersion", "protocolParams.extraEntropy"],
        "alonzo": ["executionPrices", "maxTxExUnits", "maxBlockExUnits"],
        "conway": ["constitution", "constitution.anchor", "committee", "poolVotingThresholds", "dRepVotingThresholds"],
    }[era]
    out = []
    for prefix in [""] + nested:
        path = prefix.split(".") if prefix else []
        node = document
        for key in path:
            node = node[key]
        if not isinstance(node, dict):
            continue
        for key, value in node.items():
            out.append(case(era, base, "remove", path + [key]))
            out.append(case(era, base, "replace", path + [key], wrong(value), "wrongType"))
    return out


def specials():
    """Values the reference's types decide: the nonce tag, units within Word64 and the names they
    are read under, the ratios' object form, the quorum's width, the optional fields and the map
    entries."""
    half = {"numerator": 1, "denominator": 2}
    out = [
        case("shelley", MUTATED, "replace", ["protocolParams", "extraEntropy", "tag"], "Other", "otherTag"),
        case("shelley", MUTATED, "replace", ["protocolParams", "extraEntropy"],
             {"tag": "Nonce", "contents": "00" * 32}, "nonce"),
        case("shelley", MUTATED, "replace", ["protocolParams", "extraEntropy"], {"tag": "Nonce"}, "nonceNoContents"),
        case("shelley", MUTATED, "replace", ["protocolParams", "minPoolCost"], 340000000, "minPoolCost"),
    ]
    for units in ("maxTxExUnits", "maxBlockExUnits"):
        for key in ("exUnitsMem", "exUnitsSteps"):
            out.append(case("alonzo", MUTATED, "replace", [units, key], WORD64_MAX, "word64Max"))
            out.append(case("alonzo", MUTATED, "replace", [units, key], WORD64_MAX + 1, "pastWord64"))
        out.append(case("alonzo", MUTATED, "replace", [units], {"memory": 10, "steps": 20}, "shortNames"))
    out.append(case("alonzo", MUTATED, "replace", ["executionPrices"],
                    {"priceSteps": {"numerator": 1, "denominator": 2}, "priceMemory": {"numerator": 3, "denominator": 4}},
                    "longNames"))
    # `o .: "memory" <|> o .: "exUnitsMem"`, then the Word64 bound
    out.append(case("alonzo", MUTATED, "replace", ["maxTxExUnits"],
                    {"memory": "x", "exUnitsMem": 10, "steps": 20}, "firstMalformed"))
    out.append(case("alonzo", MUTATED, "replace", ["maxTxExUnits"],
                    {"memory": WORD64_MAX + 1, "exUnitsMem": 10, "steps": 20}, "firstPastWord64"))
    out.append(case("alonzo", MUTATED, "replace", ["executionPrices"],
                    {"priceSteps": "x", "prSteps": half, "prMem": half}, "firstMalformed"))
    out.append(case("alonzo", MUTATED, "replace", ["executionPrices"],
                    {"priceSteps": 2, "prSteps": half, "prMem": half}, "decimalPrice"))
    # the ratios as objects and as decimals
    for key in ("a0", "rho", "tau", "decentralisationParam"):
        out.append(case("shelley", MUTATED, "replace", ["protocolParams", key], half, "ratioObject"))
        out.append(case("shelley", MUTATED, "replace", ["protocolParams", key], 1.5, "ratioAboveOne"))
    out.append(case("conway", MUTATED, "replace", ["minFeeRefScriptCostPerByte"], half, "ratioObject"))
    out.append(case("conway", MUTATED, "replace", ["poolVotingThresholds", "committeeNormal"], 0.5, "ratioDecimal"))
    out.append(case("conway", MUTATED, "replace", ["committee", "threshold"], 0.5, "ratioDecimal"))
    # the quorum is a Word64
    for value, label in ((2**31, "past31Bits"), (WORD64_MAX, "word64Max"), (WORD64_MAX + 1, "pastWord64")):
        out.append(case("shelley", MUTATED, "replace", ["updateQuorum"], value, label))
    # the optional fields and the map entries, on files that have them
    staked = "devnet-lsq"
    out += [
        case("shelley", staked, "remove", ["staking"]),
        case("shelley", staked, "replace", ["staking"], "x", "wrongType"),
        case("shelley", staked, "replace", ["staking", "pools"], "x", "wrongType"),
        case("shelley", staked, "remove", ["extraConfig"]),
        case("shelley", staked, "replace", ["extraConfig"], "x", "wrongType"),
        case("alonzo", staked, "remove", ["extraConfig"]),
        case("alonzo", staked, "replace", ["extraConfig"], "x", "wrongType"),
        case("conway", staked, "remove", ["extraConfig"]),
        case("conway", staked, "replace", ["extraConfig"], "x", "wrongType"),
        case("conway", MUTATED, "replace", ["delegs"], {}, "empty"),
        case("conway", MUTATED, "replace", ["delegs"], "x", "wrongType"),
        case("conway", MUTATED, "replace", ["initialDReps"], {}, "empty"),
        case("conway", MUTATED, "replace", ["initialDReps"], "x", "wrongType"),
    ]
    delegate = next(iter(load(MUTATED, "shelley")["genDelegs"]))
    out += [
        case("shelley", MUTATED, "remove", ["genDelegs", delegate, "delegate"]),
        case("shelley", MUTATED, "replace", ["genDelegs", delegate, "vrf"], "00", "shortHash"),
        case("shelley", MUTATED, "replace", ["genDelegs"], {"00": load(MUTATED, "shelley")["genDelegs"][delegate]}, "shortKey"),
        case("shelley", MUTATED, "replace", ["initialFunds"], {"00": 1}, "badAddress"),
        case("shelley", MUTATED, "replace", ["initialFunds"],
             {"60" + "11" * 28: "x"}, "coinWrongType"),
        case("shelley", MUTATED, "replace", ["initialFunds"], {"60" + "11" * 28: 5}, "oneFund"),
    ]
    member = next(iter(load("leios-testnet", "conway")["committee"]["members"]))
    out += [
        case("conway", "leios-testnet", "replace", ["committee", "members", member], "x", "wrongType"),
        case("conway", "leios-testnet", "replace", ["committee", "members"], {"keyHash-00": 5}, "badCredential"),
        case("conway", MUTATED, "replace", ["constitution", "anchor", "url"], "x" * 129, "longUrl"),
        case("conway", MUTATED, "replace", ["constitution", "anchor", "dataHash"], "00", "shortHash"),
    ]
    return out


def generate():
    cases = [case(era, label, "none", []) for label, era, _ in bases()]
    for era in ERAS:
        cases.extend(mutations(era, load(MUTATED, era), MUTATED))
    cases.extend(specials())
    names = [c["name"] for c in cases]
    assert len(names) == len(set(names)), "case names must be unique"
    return cases


def apply(document, c):
    document = copy.deepcopy(document)
    if c["op"] == "none":
        return document
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
    return {"name": c["name"], "kind": c["kind"], "input": apply(json.loads((ROOT / c["base"]).read_text()), c)}


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
    return {path: sha256(path) for _, _, path in bases()}


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
            "image": IMAGE,
            "imageId": image,
            "ledger": {"repository": "IntersectMBO/cardano-ledger", "revision": "f649f975",
                       "packages": ["cardano-ledger-shelley", "cardano-ledger-alonzo 1.16.0.0",
                                    "cardano-ledger-conway 1.23.0.0"]},
            "program": "scripts/oracle/genesis-fields/Main.hs",
            "programSha256": sha256("scripts/oracle/genesis-fields/Main.hs"),
            "bases": bases_hashes(),
            "cases": len(results),
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
