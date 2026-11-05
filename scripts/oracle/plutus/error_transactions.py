# SPDX-License-Identifier: Apache-2.0
"""Submit native-compiled error programs as signed transactions to the official node."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from devnet.scenarios.v1context import Driver, ENV_DIR, FEE, FUND, UNITS, open_text, script_hash
from devnet.util import query
from malformed_transactions import legal_languages


def requests(programs, definitions, languages):
    indexed = {row["id"]: row for row in definitions}
    selected = [(program, indexed[program["id"]], UNITS) for program in programs
                if indexed[program["id"]]["language"] in languages]
    tiny = [(program, definition, (1, 1)) for program, definition, _ in selected
            if program["id"].startswith("explicit-error-")]
    return [(program, definition, units, valid)
            for program, definition, units in selected + tiny for valid in (True, False)]


def run(name, era, protocol, output, registry, programs_file, definitions_file, *, wallet=None):
    if query(name, "protocol-parameters")["protocolVersion"]["major"] != protocol:
        raise ValueError("the native protocol does not match the requested profile")
    program_bytes, definition_bytes = programs_file.read_bytes(), definitions_file.read_bytes()
    programs = json.loads(program_bytes)
    definitions = json.loads(definition_bytes)
    pending = requests(programs, definitions, legal_languages(era, protocol, registry))
    skey, address = wallet or (f"{ENV_DIR}/utxo-keys/utxo1/utxo.skey",
                              open_text(name, f"{ENV_DIR}/utxo-keys/utxo1/utxo.addr"))
    driver = Driver(name, era, protocol, skey, address,
                    log=lambda row: print(json.dumps(row), flush=True))
    made = driver.setup({}, len(pending))
    for index, (program, definition, units, valid) in enumerate(pending):
        language = definition["language"]
        script = bytes.fromhex(program["script"])
        policy = script_hash(language, script)
        assets = {policy: {b"probe": 1}}
        fund, collateral = made[f"fund{index}"], made[f"collateral{index}"]
        transaction, txid = driver.build(
            [fund[:2]], [driver.out(FUND - FEE, assets=assets)], mint=assets,
            scripts={language: script}, redeemers=[(1, 0, 0, units)],
            collateral=[collateral[:2]], valid=valid)
        budget_label = "tiny-budget-" if units == (1, 1) else ""
        label = f"{era}-pv{protocol}-{budget_label}{program['id']}-valid-{str(valid).lower()}"
        accepted, text = driver.submit(transaction, label)
        if accepted and not driver.wait_gone(*(fund[:2] if valid else collateral[:2])):
            raise RuntimeError(f"accepted control was not included: {label}")
        driver.record(label, "native-compiled evaluation-error exposure control", transaction, txid,
                      {"spent": [fund], "references": [], "collateral": [collateral]},
                      accepted, text, valid, [language], None if accepted else "native refusal")
        driver.cases[-1]["nativeProgram"] = {
            "id": program["id"], "serializedScript": program["script"],
            "language": language, "version": definition["version"],
            "arity": len(definition["arguments"]), "budget": list(units),
            "compileInputsSha256": hashlib.sha256(definition_bytes).hexdigest(),
            "compileOutputsSha256": hashlib.sha256(program_bytes).hexdigest(),
            "compileInput": definition, "compileOutput": program,
        }
        output.write_text(json.dumps(driver.cases, indent=1) + "\n")
    print(json.dumps({"profile": [era, protocol], "cases": len(driver.cases),
                      "refused": sum(not row["reference"]["accepted"] for row in driver.cases)}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--era", choices=("alonzo", "babbage", "conway"), required=True)
    parser.add_argument("--protocol", type=int, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--programs", type=Path, required=True)
    parser.add_argument("--definitions", type=Path, required=True)
    parser.add_argument("--registry", type=Path,
                        default=Path(__file__).resolve().parents[3] / "fixtures/reference/plutus-costing/models.jsonl")
    args = parser.parse_args()
    run(args.name, args.era, args.protocol, args.out, args.registry, args.programs, args.definitions)
