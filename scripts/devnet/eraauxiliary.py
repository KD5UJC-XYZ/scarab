# SPDX-License-Identifier: Apache-2.0
"""Signed reference evidence: auxiliary Plutus scripts are validated, never executed.

These are six available era/language pairs, not a complete protocol-version cross product.
No auxiliary script is a spending/minting witness and no redeemer is supplied.
"""
import time

from . import eratx
from .eraload import MAGIC, SOCKET
from .eraplutus import index_utxo, reference_observation

LANGUAGES = {"alonzo": (1,), "babbage": (1, 2), "conway": (1, 2, 3)}
CASES = ("valid", "runtime-error", "malformed-valid", "malformed-invalid")
OK = eratx.enc(bytes.fromhex("010000222499"))
RUNTIME_ERROR = eratx.enc(bytes.fromhex("01000061"))
MALFORMED = eratx.enc(b"\x00")
FUND = 5_000_000
COLLATERAL = 3_000_000
FEE = 500_000


def coverage_failures(rows):
    expected = {(era, language, case) for era, languages in LANGUAGES.items()
                for language in languages for case in CASES}
    observed = {(r["era"], r["language"], r["case"]) for r in rows if r.get("passed")}
    return [f"missing auxiliary Plutus admission/effect evidence: {sorted(expected - observed)}"] if expected - observed else []


def effect_errors(before, after, normal, collateral, txid, included):
    errors = []
    if before.get(normal) != (FUND, {}):
        errors.append("normal input was not independently funded")
    if before.get(collateral) != (COLLATERAL, {}):
        errors.append("collateral input was not independently funded")
    if included:
        if normal in after:
            errors.append("included payment did not consume normal input")
    elif after.get(normal) != before.get(normal):
        errors.append("refused transaction changed normal input")
    if after.get(collateral) != before.get(collateral):
        errors.append("auxiliary-only transaction consumed or changed collateral")
    outputs = {index: value for (tid, index), value in after.items() if tid == txid}
    if outputs != ({0: (FUND - FEE, {})} if included else {}):
        errors.append("transaction outputs differ from reference admission")
    return errors


def submit_reference(load, era, tx, label, included):
    """Retain actual CLI receipt and raw typed refusal bytes, without synthetic verdicts."""
    result = load.sh(f"mkdir -p /work/tx && cat > /work/tx/{label}.tx && cardano-cli conway transaction submit "
                     f"--testnet-magic {MAGIC} --socket-path {SOCKET} --tx-file /work/tx/{label}.tx",
                     stdin=eratx.envelope(era, tx))
    receipt = {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    if included:
        return result.returncode == 0, receipt
    passed = result.returncode != 0 and "InvalidMetadata" in (result.stderr + result.stdout)
    # A raw ApplyTxErr is stronger retained evidence than merely matching CLI prose.
    with load.lsq_factory() as session:
        reason = session.submit(tx, load.ERA_INDEX[era])
    receipt["rawRefusal"] = None if reason is None else reason.hex()
    return passed and reason is not None, receipt


def exercise(load, era):
    wallet = load.wallets[0]
    specs = []
    # Each accepted transaction has its own input pair. Both rejected flags share a third pair.
    for _ in LANGUAGES[era]:
        for _ in range(3):
            specs.extend([(wallet.address, FUND, None, {}), (wallet.address, COLLATERAL, None, {})])
    setup = load.act(era, f"auxiliary-plutus-setup-{era}", wallet, specs, record="auxiliary-plutus-setup.jsonl")
    if not setup:
        raise RuntimeError(f"{era}: auxiliary Plutus setup not included")
    for ordinal, language in enumerate(LANGUAGES[era]):
        for case in CASES:
            reference_before = reference_observation(load, era)
            included = case in ("valid", "runtime-error")
            branch = CASES.index(case) if included else 2
            normal = (setup, ordinal * 6 + branch * 2)
            collateral = (setup, ordinal * 6 + branch * 2 + 1)
            script = OK if case == "valid" else RUNTIME_ERROR if case == "runtime-error" else MALFORMED
            valid = case != "malformed-invalid"
            label = f"auxiliary-plutus-{era}-v{language}-{case}"
            tx, txid = eratx.build_tx(era, [normal], [eratx.tx_out(era, wallet.address, FUND - FEE)],
                                    FEE, [wallet.pay], collateral=[collateral], is_valid=valid,
                                    aux_plutus={language: [script]})
            before = index_utxo(load.snapshot())
            submitted, receipt = submit_reference(load, era, tx, label, included)
            deadline = time.monotonic() + 45
            while True:
                after = index_utxo(load.snapshot())
                errors = effect_errors(before, after, normal, collateral, txid, included)
                if not included or not submitted or not errors or load.stop.is_set() or time.monotonic() >= deadline:
                    break
                time.sleep(0.4)
            if not submitted:
                errors.insert(0, "reference receipt does not establish the required admission")
            reference_after = reference_observation(load, era)
            passed = submitted and not errors
            row = {"era": era, "language": language, "case": case, "isValid": valid,
                   "txid": txid, "passed": passed, "errors": errors,
                   "referenceReceipt": receipt,
                   "referenceBefore": reference_before, "referenceAfter": reference_after,
                   "observedEffects": {"normalInputPresent": normal in after,
                       "collateralPresent": collateral in after,
                       "normalBefore": None if normal not in before else eratx.enc(before[normal]).hex(),
                       "normalAfter": None if normal not in after else eratx.enc(after[normal]).hex(),
                       "collateralBefore": None if collateral not in before else eratx.enc(before[collateral]).hex(),
                       "collateralAfter": None if collateral not in after else eratx.enc(after[collateral]).hex(),
                       "outputs": {str(i): eratx.enc(value).hex() for (tid, i), value in after.items() if tid == txid}}}
            load.auxiliary_evidence.append(row)
            load.keep_tx("auxiliary-plutus.jsonl", {**row, "tx": tx.hex(),
                         "normalInput": normal, "collateral": collateral, "script": script.hex()})
            load.log({"auxiliaryPlutus": row})
            if not passed:
                raise RuntimeError(f"{label}: {errors}")
