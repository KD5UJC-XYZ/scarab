# SPDX-License-Identifier: Apache-2.0
"""Record complete V3 Flat boundary transactions on an already running private reference cluster."""
import argparse
from enum import Enum
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from devnet.scenarios.v1context import Driver, ENV_DIR, FEE, FUND, open_text, script_hash
from devnet.util import query


class Stage(Enum):
    DECODE = "decode"
    SCOPE = "scope"
    EVALUATION = "evaluation"
    SUCCESS = "success"


def expected_failure(stage, valid):
    if stage is Stage.DECODE:
        return "MalformedScriptWitnesses"
    return None if valid == (stage is Stage.SUCCESS) else "ValidationTagMismatch"


def expected_spent(accepted, valid):
    return {"funding": accepted and valid, "collateral": accepted and not valid}


def present(utxo, output):
    return (output[0], str(output[1])) in utxo


def confirmed_setup(driver, count, security, *, timeout=180, resubmit_after=5,
                    now=time.monotonic, pause=time.sleep, tips=None):
    """Before any case, confirm all funding on a shared chain k+1 blocks beyond observation.

    An early private-cluster fork can remove setup after one UTxO observation. Only this
    setup transaction may be resubmitted, with identical bytes and its source still unspent.
    Script transactions retain their original, strict reference-verdict checks.
    """
    prepared = driver.prepare_setup({}, count)
    accepted, detail = driver.submit(prepared.transaction, "setup")
    if not accepted:
        raise RuntimeError(f"setup refused: {detail}")
    deadline, submitted = now() + timeout, now()
    anchor, resubmissions = None, 0
    read_tips = tips or (lambda: [query(driver.name, "tip", node=n) for n in (1, 2, 3)])
    while now() < deadline:
        before = driver.utxo()
        observed = read_tips()
        utxo = driver.utxo()
        outputs = [present(utxo, out) for out in prepared.outputs.values()]
        shared = len(observed) == 3 and len({(tip["block"], tip["hash"]) for tip in observed}) == 1
        if all(outputs):
            if not shared or not all(present(before, out) for out in prepared.outputs.values()):
                anchor = None
            else:
                height = observed[0]["block"]
                anchor = height if anchor is None else anchor
                if height >= anchor + security + 1:
                    proof = {"txId": prepared.txid, "securityParam": security,
                             "firstSharedBlock": anchor, "confirmedBlock": height,
                             "sharedTip": observed[0]["hash"], "resubmissions": resubmissions}
                    driver.log({"setupConfirmed": proof})
                    return prepared.outputs, proof
        elif any(outputs):
            raise RuntimeError("setup outputs partially absent before any boundary case")
        else:
            anchor = None
            if (prepared.source[0], str(prepared.source[1])) not in utxo:
                raise RuntimeError("setup and its original input are both absent before boundary cases")
            if now() - submitted >= resubmit_after and resubmissions < 3:
                accepted, detail = driver.submit(prepared.transaction, "setup")
                resubmissions += 1
                submitted = now()
                driver.log({"setupResubmission": resubmissions, "accepted": accepted, "detail": detail})
        pause(1)
    raise RuntimeError(f"setup never reached shared k+1 confirmation in {timeout}s; resubmissions={resubmissions}")


def run(name, scripts, output, protocol):
    pp = query(name, "protocol-parameters")
    major = pp["protocolVersion"]["major"]
    if major != protocol:
        raise ValueError(f"actual reference protocol {major}, required {protocol}")
    address = open_text(name, f"{ENV_DIR}/utxo-keys/utxo1/utxo.addr")
    driver = Driver(name, "conway", major, f"{ENV_DIR}/utxo-keys/utxo1/utxo.skey", address,
                    log=lambda row: print(json.dumps(row), flush=True))
    probes = [json.loads(line) for line in Path(scripts).read_text().splitlines()]
    ids = [probe["id"] for probe in probes]
    if not probes or len(set(ids)) != len(ids):
        raise ValueError("expected nonempty uniquely named boundary probes")
    if any(not ident or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-" for c in ident) for ident in ids):
        raise ValueError("probe identifiers must be lowercase ASCII letters, digits and hyphens")
    stages = {probe["id"]: Stage(probe["stages"][str(major)]) for probe in probes}
    security = json.loads(open_text(name, f"{ENV_DIR}/shelley-genesis.json"))["securityParam"]
    made, setup_proof = confirmed_setup(driver, 2 * len(probes), security)
    for index, (probe, valid) in enumerate((probe, valid) for probe in probes for valid in (True, False)):
        stage = stages[probe["id"]]
        fund, collateral = made[f"fund{index}"], made[f"collateral{index}"]
        before = driver.utxo()
        if not present(before, fund) or not present(before, collateral):
            raise RuntimeError("independently funded case inputs are absent before submission")
        content = bytes.fromhex(probe["script"])
        policy = script_hash(3, content)
        minted = {policy: {b"t": 1}}
        expected = expected_failure(stage, valid)
        label = f"{probe['id']}-valid-{str(valid).lower()}"
        tx, tid = driver.build([(fund[0], fund[1])], [driver.out(FUND - FEE, assets=minted)],
                               mint=minted, scripts={3: content},
                               redeemers=[(1, 0, 0, (1_000_000, 1_000_000_000))],
                               collateral=[(collateral[0], collateral[1])], valid=valid)
        accepted, detail = driver.submit(tx, label)
        included = not accepted or driver.wait_gone(*(fund[:2] if valid else collateral[:2]))
        after = driver.utxo()
        spent = {"funding": not present(after, fund), "collateral": not present(after, collateral)}
        agree = driver.record(label, "V3 original Flat admission, scope, evaluation and validity tag", tx, tid,
                              {"spent": [fund], "references": [], "collateral": [collateral]},
                              accepted, detail, valid, [3], expected)
        driver.cases[-1]["boundary"] = {
            "id": probe["id"], "stage": stage.value, "script": probe["script"],
            "sourceScript": probe["sourceScript"], "sourceIndices": probe["sourceIndices"],
            "observedProtocolMajor": major, "before": {"funding": True, "collateral": True},
            "spentAfter": spent, "included": accepted and included,
            "setupConfirmation": setup_proof,
        }
        Path(output).write_text(json.dumps(driver.cases, indent=1) + "\n")
        if not agree or (expected is not None and expected not in detail):
            raise RuntimeError(f"reference admission differed for {label}: {detail[:300]}")
        if not included or spent != expected_spent(accepted, valid):
            raise RuntimeError(f"reference input/collateral disposition differed for {label}: {spent}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--scripts", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--protocol", required=True, type=int, choices=(10, 11))
    args = parser.parse_args()
    run(args.name, args.scripts, args.out, args.protocol)
