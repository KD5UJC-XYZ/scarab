# SPDX-License-Identifier: Apache-2.0
"""Capture signed context-failure transactions and raw refusals on an existing private PV10 devnet."""
import argparse
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from devnet.scenarios.v1context import ENV_DIR, open_text, run as run_cases
from devnet.util import query
from oracle.plutus.context_transactions import ReferenceDriver, broadcast, confirmed_case
from oracle.plutus.boundary_transactions import confirmed_setup, present


def confirmed_collateral(driver, txid, resolved, security, timeout=90):
    """Invalid-tag admission spends every collateral input and leaves every ordinary input present."""
    deadline, anchor = time.monotonic() + timeout, None
    while time.monotonic() < deadline:
        before = driver.whole_utxo()
        tips = [query(driver.name, "tip", node=node) for node in (1, 2, 3)]
        after = driver.whole_utxo()
        disposition = (bool(resolved["spent"]) and bool(resolved["collateral"])
                       and all(present(before, entry) and present(after, entry) for entry in resolved["spent"])
                       and all(not present(before, entry) and not present(after, entry)
                               for entry in resolved["collateral"]))
        shared = len({(tip["block"], tip["hash"]) for tip in tips}) == 1
        if disposition and shared:
            height = tips[0]["block"]
            anchor = height if anchor is None else anchor
            if height >= anchor + security + 1:
                return {"txId": txid, "firstSharedBlock": anchor, "confirmedBlock": height,
                        "sharedTip": tips[0]["hash"], "securityParam": security, "collateralSpent": True}
        else:
            anchor = None
        time.sleep(1)
    raise RuntimeError("invalid-tag admitted control lacks shared k+1 funding/collateral disposition")


class CapturingDriver(ReferenceDriver):
    def __init__(self, *args, output, **kwargs):
        super().__init__(*args, **kwargs)
        self.output = Path(output)

    def setup(self, specials, count):
        security = json.loads(open_text(self.name, f"{ENV_DIR}/shelley-genesis.json"))["securityParam"]
        made, self.proof = confirmed_setup(self, count, security, specials=specials, utxo=self.whole_utxo)
        return made

    def submit(self, tx, label):
        accepted, text = super().submit(tx, label)
        self.propagated = broadcast(self, label) if accepted else []
        return accepted, text

    def record(self, name, description, tx, tid, resolved, accepted, text, valid, languages, expect):
        agree = super().record(name, description, tx, tid, resolved, accepted, text, valid, languages, expect)
        row = self.cases[-1]
        row["contextCase"] = {"transaction": tx.hex(), "broadcast": self.propagated,
                              "setupConfirmation": self.proof}
        # Preserve partial/failed attempts before waiting for independent chain confirmation.
        self.output.write_text(json.dumps(self.cases, indent=1) + "\n")
        if accepted:
            security = self.proof["securityParam"]
            proof = (confirmed_case(self, tid, security) if valid
                     else confirmed_collateral(self, tid, resolved, security))
            row["contextCase"]["inclusion"] = proof
        self.output.write_text(json.dumps(self.cases, indent=1) + "\n")
        return agree


def run(name, output):
    major = query(name, "protocol-parameters")["protocolVersion"]["major"]
    if major != 10:
        raise ValueError(f"expected private Conway protocol10, observed {major}")
    address = open_text(name, f"{ENV_DIR}/utxo-keys/utxo1/utxo.addr")
    driver = CapturingDriver(name, "conway", major, f"{ENV_DIR}/utxo-keys/utxo1/utxo.skey", address,
                             output=output, log=lambda row: print(json.dumps(row), flush=True))
    failed = run_cases(driver)
    if failed:
        raise RuntimeError(f"context cases failed: {failed}")
    print(json.dumps({"cases": len(driver.cases), "failed": failed}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    run(args.name, args.out)
