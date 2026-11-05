# SPDX-License-Identifier: Apache-2.0
"""Record signed malformed-script admission controls on a named official-node cluster."""
import argparse
from enum import Enum
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from devnet.scenarios.v1context import Driver, ENV_DIR, FEE, FUND, UNITS, open_text, script_hash
from devnet.util import query


class Mode(Enum):
    WITNESS = "required-minting-witness"
    REFERENCE = "reference-output-publication"
    BOTH = "required-witness-and-reference-output"

    @property
    def witness(self):
        return self is not Mode.REFERENCE

    @property
    def reference(self):
        return self is not Mode.WITNESS

    @property
    def constructors(self):
        return (["MalformedScriptWitnesses"] if self.witness else []) + (
            ["MalformedReferenceScripts"] if self.reference else [])


def legal_languages(era, protocol, registry):
    profiles = [json.loads(line) for line in Path(registry).read_text().splitlines() if line.strip()]
    expected = "alonzo" if protocol < 7 else "babbage" if protocol < 9 else "conway"
    if expected != era:
        raise ValueError(f"era {era} disagrees with registered protocol {protocol}")
    return sorted({row["language"] for row in profiles if row["protocol"] == protocol})


def build(driver, language, mode, valid, fund, collateral):
    serialized = bytes.fromhex("4100")
    policy = script_hash(language, serialized)
    assets = {policy: {b"probe": 1}} if mode.witness else None
    output = driver.out(FUND - FEE, assets=assets,
                        script=(language, serialized) if mode.reference else None)
    return driver.build(
        [fund[:2]], [output], valid=valid,
        mint=assets, scripts={language: serialized} if mode.witness else None,
        redeemers=[(1, 0, 0, UNITS)] if mode.witness else None,
        collateral=[collateral[:2]] if mode.witness else None)


def run(name, era, protocol, output, registry):
    observed = query(name, "protocol-parameters")["protocolVersion"]["major"]
    if observed != protocol:
        raise ValueError(f"native protocol is {observed}, requested {protocol}")
    languages = legal_languages(era, protocol, registry)
    if not languages:
        raise ValueError("no legal language/protocol profiles in the maintained registry")
    modes = list(Mode) if era != "alonzo" else [Mode.WITNESS]
    requests = [(language, mode, valid) for language in languages for mode in modes
                for valid in (True, False)]
    address = open_text(name, f"{ENV_DIR}/utxo-keys/utxo1/utxo.addr")
    driver = Driver(name, era, protocol, f"{ENV_DIR}/utxo-keys/utxo1/utxo.skey", address,
                    log=lambda row: print(json.dumps(row), flush=True))
    made = driver.setup({}, len(requests))
    for index, (language, mode, valid) in enumerate(requests):
        fund, collateral = made[f"fund{index}"], made[f"collateral{index}"]
        transaction, txid = build(driver, language, mode, valid, fund, collateral)
        label = f"{era}-pv{protocol}-v{language}-{mode.value}-valid-{str(valid).lower()}"
        accepted, text = driver.submit(transaction, label)
        if accepted:
            raise RuntimeError(f"reference unexpectedly accepted malformed4100: {label}")
        if any(constructor not in text for constructor in mode.constructors):
            raise RuntimeError(f"reference did not name the intended malformed-script guard: {label}")
        driver.record(label, "original4100 native admission/exposure control", transaction, txid,
                      {"spent": [fund], "references": [], "collateral": [collateral]
                       if mode.witness else []}, accepted, text, valid, [language],
                      mode.constructors[0])
        driver.cases[-1]["malformedProbe"] = {
            "language": language, "serializedScript": "4100", "mode": mode.value,
            "expectedConstructors": mode.constructors,
            "referenceInputExecutionClaim": False,
            "boundary": "required minting witness" if not mode.reference
            else "creation of an output carrying a reference script",
        }
        Path(output).write_text(json.dumps(driver.cases, indent=1) + "\n")
    print(json.dumps({"profile": [era, protocol], "languages": languages,
                      "cases": len(driver.cases), "allRefused": True}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--era", required=True, choices=("alonzo", "babbage", "conway"))
    parser.add_argument("--protocol", required=True, type=int, choices=range(5, 12))
    parser.add_argument("--out", required=True)
    parser.add_argument("--registry", default="fixtures/reference/plutus-costing/models.jsonl")
    options = parser.parse_args()
    run(options.name, options.era, options.protocol, options.out, options.registry)
