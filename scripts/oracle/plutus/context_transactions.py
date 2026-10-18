# SPDX-License-Identifier: Apache-2.0
"""Original signed spending-context transactions and raw reference refusals on a private devnet."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from enum import Enum
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from devnet import eratx, ntcquery
from devnet.cbor import enc
from devnet.scenarios.v1context import Driver, ENV_DIR, FEE, FUND, FLAT_OK, FLAT_OK_V3, open_text, wrapped
from devnet.util import MAGIC, SOCKET, query, ref
from oracle.plutus.boundary_transactions import confirmed_setup, present


class DatumMode(Enum):
    MISSING = "missing-witness"
    PRESENT = "supplied-witness"
    NONE = "no-datum"


def expected_failure(language, mode, valid):
    if mode is DatumMode.MISSING:
        return "MissingRequiredDatums"
    if mode is DatumMode.NONE and language != 3:
        return "UnspendableUTxONoDatumHash"
    return None if valid else "ValidationTagMismatch"


def broadcast(driver, label):
    """Propagate the exact admitted transaction to each private producer, retaining every result."""
    def submit(node):
        socket = SOCKET.replace("node1", f"node{node}")
        result = driver.sh(f"cardano-cli conway transaction submit {socket} --tx-file /work/tx/{label}.tx")
        return {"node": node, "exit": result.returncode, "text": (result.stderr or result.stdout).strip()[:1500]}
    with ThreadPoolExecutor(max_workers=3) as executor:
        return list(executor.map(submit, (1, 2, 3)))


def confirmed_case(driver, txid, security, timeout=90):
    """The exact new output must survive k+1 shared-chain blocks; absence alone proves nothing."""
    deadline, anchor = time.monotonic() + timeout, None
    while time.monotonic() < deadline:
        before = driver.whole_utxo()
        tips = [query(driver.name, "tip", node=node) for node in (1, 2, 3)]
        after = driver.whole_utxo()
        shared = len({(tip["block"], tip["hash"]) for tip in tips}) == 1
        if shared and (txid, "0") in before and (txid, "0") in after:
            height = tips[0]["block"]
            anchor = height if anchor is None else anchor
            if height >= anchor + security + 1:
                return {"txId": txid, "firstSharedBlock": anchor, "confirmedBlock": height,
                        "sharedTip": tips[0]["hash"], "securityParam": security}
        else:
            anchor = None
        time.sleep(1)
    raise RuntimeError("reference admitted control but exact output lacked shared k+1 inclusion")


class ReferenceDriver(Driver):
    """Keep the original CLI verdict and independently capture the same refusal's raw ApplyTxErr."""
    reason = None

    def submit(self, tx, label):
        accepted, text = super().submit(tx, label)
        self.reason = None
        if not accepted:
            with ntcquery.LocalStateQuery(ref(self.name), f"{ENV_DIR}/socket/node1/sock", MAGIC) as client:
                reason = client.submit(tx)
                if reason is None:
                    raise RuntimeError("raw reference submission disagrees with the CLI refusal")
                self.reason = reason.hex()
        return accepted, text

    def record(self, *args):
        agree = super().record(*args)
        self.cases[-1]["reference"]["reasonCbor"] = self.reason
        return agree

    def whole_utxo(self):
        result = self.sh(f"cardano-cli query utxo --whole-utxo {SOCKET} --output-json")
        if result.returncode:
            raise RuntimeError(result.stderr)
        return {tuple(k.split("#")): value for k, value in json.loads(result.stdout).items()}


def run(name, output):
    pp = query(name, "protocol-parameters")
    major = pp["protocolVersion"]["major"]
    if major != 10:
        raise ValueError(f"expected private Conway protocol10, observed {major}")
    address = open_text(name, f"{ENV_DIR}/utxo-keys/utxo1/utxo.addr")
    driver = ReferenceDriver(name, "conway", major, f"{ENV_DIR}/utxo-keys/utxo1/utxo.skey", address,
                             log=lambda row: print(json.dumps(row), flush=True))
    datum = 42
    datum_hash = eratx.blake2b(enc(datum))
    cases = [(language, mode, valid) for language in (1, 2, 3) for mode in DatumMode for valid in (True, False)]
    scripts = {language: wrapped(FLAT_OK_V3 if language == 3 else FLAT_OK) for language in (1, 2, 3)}
    specials = {
        f"script{index}": eratx.tx_out("conway", b"\x70" + eratx.plutus_script_hash(scripts[language], language),
                                     FUND, datum_hash=datum_hash if mode is not DatumMode.NONE else None)
        for index, (language, mode, _) in enumerate(cases)
    }
    security = json.loads(open_text(name, f"{ENV_DIR}/shelley-genesis.json"))["securityParam"]
    made, setup = confirmed_setup(driver, len(cases), security, specials=specials, utxo=driver.whole_utxo)
    for index, (language, mode, valid) in enumerate(cases):
        fund, collateral, script_input = made[f"fund{index}"], made[f"collateral{index}"], made[f"script{index}"]
        before = driver.whole_utxo()
        if not all(present(before, out) for out in (fund, collateral, script_input)):
            raise RuntimeError("independently funded inputs missing before submission")
        inputs = sorted([fund[:2], script_input[:2]])
        redeemers = [(0, inputs.index(script_input[:2]), 0, (200_000, 100_000_000))]
        datums = [datum] if mode is DatumMode.PRESENT else []
        integrity = eratx.script_data_hash(enc(eratx.redeemers_cbor("conway", redeemers)),
                                          enc(datums) if datums else b"", {language: driver.models[language]})
        tx, tid = eratx.build_tx("conway", inputs, [driver.out(2 * FUND - FEE)], FEE, [driver.key],
                                 plutus={language: [scripts[language]]}, redeemers=redeemers, datums=datums,
                                 collateral=[collateral[:2]], script_data=integrity, is_valid=valid)
        label = f"v{language}-{mode.value}-valid-{str(valid).lower()}"
        expected = expected_failure(language, mode, valid)
        accepted, detail = driver.submit(tx, label)
        propagated = broadcast(driver, label) if accepted else []
        inclusion = confirmed_case(driver, tid, security) if accepted else None
        after = driver.whole_utxo()
        spent = {"funding": not present(after, fund), "script": not present(after, script_input),
                 "collateral": not present(after, collateral)}
        agree = driver.record(label, "spending datum lookup, phase1 completeness and argument arity", tx, tid,
                              {"spent": [fund, script_input], "references": [], "collateral": [collateral]},
                              accepted, detail, valid, [language], expected)
        driver.cases[-1]["datumCase"] = {"language": language, "mode": mode.value, "script": scripts[language].hex(),
                                          "transaction": tx.hex(), "spentAfter": spent, "setupConfirmation": setup,
                                          "broadcast": propagated, "inclusion": inclusion}
        Path(output).write_text(json.dumps(driver.cases, indent=1) + "\n")
        if not agree or (expected is not None and expected not in detail):
            raise RuntimeError(f"reference verdict differed for {label}: {detail[:400]}")
        if spent != {"funding": accepted, "script": accepted, "collateral": False}:
            raise RuntimeError(f"reference funding/script/collateral disposition differs: {label}: {spent}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    run(args.name, args.out)
