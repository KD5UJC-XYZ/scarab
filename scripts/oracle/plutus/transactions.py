# SPDX-License-Identifier: Apache-2.0
"""Record the reference's validity-tag verdicts for the retained binary-DST assertion.

Runs only against an already-started, uniquely named private devnet. Reuses the reference
transaction driver; it never starts a follower or touches a public network.
"""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from devnet.scenarios.v1context import Driver, ENV_DIR, FEE, FUND, open_text, script_hash
from devnet.util import query


def run(name, scripts, output):
    pp = query(name, "protocol-parameters")
    major = pp["protocolVersion"]["major"]
    address = open_text(name, f"{ENV_DIR}/utxo-keys/utxo1/utxo.addr")
    driver = Driver(name, "conway", major, f"{ENV_DIR}/utxo-keys/utxo1/utxo.skey", address,
                    log=lambda row: print(json.dumps(row), flush=True))
    probes = [json.loads(line) for line in Path(scripts).read_text().splitlines()]
    if len(probes) != 2 or {probe["succeeds"] for probe in probes} != {True, False}:
        raise ValueError("expected passing binary-DST assertion and evaluated failing control")
    made = driver.setup({}, 4)
    for index, (probe, valid) in enumerate((probe, valid) for probe in probes for valid in (True, False)):
        fund, collateral = made[f"fund{index}"], made[f"collateral{index}"]
        content = bytes.fromhex(probe["script"])
        policy = script_hash(3, content)
        minted = {policy: {b"t": 1}}
        expected = None if valid == probe["succeeds"] else "ValidationTagMismatch"
        label = f"raw-dst-{'pass' if probe['succeeds'] else 'fail'}-valid-{str(valid).lower()}"
        tx, tid = driver.build([(fund[0], fund[1])], [driver.out(FUND - FEE, assets=minted)],
                               mint=minted, scripts={3: content}, redeemers=[(1, 0, 0, (1_000_000, 1_000_000_000))],
                               collateral=[(collateral[0], collateral[1])], valid=valid)
        accepted, detail = driver.submit(tx, label)
        if accepted and not driver.wait_gone(*(collateral[:2] if not valid else fund[:2])):
            raise RuntimeError("reference admitted a transaction but did not include it in a block")
        agree = driver.record(label, "V3 original-byte binary-DST assertion and validity tag", tx, tid,
                              {"spent": [fund], "references": [], "collateral": [collateral]},
                              accepted, detail, valid, [3], expected)
        if not agree or (expected is not None and expected not in detail):
            raise RuntimeError(f"reference verdict differed for {label}: {detail[:300]}")
        Path(output).write_text(json.dumps(driver.cases, indent=1) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--scripts", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    run(args.name, args.scripts, args.out)
