# SPDX-License-Identifier: Apache-2.0
"""Normal era-load V1/V2 admission controls and observed ledger effects.

Legacy validators take datum, redeemer and context; policies take redeemer and context.
The unused third lambda is allowed as a legacy policy result (unlike V3's unit requirement).
Use datum hashes, no reference inputs and no inline datums, so V1 contexts remain representable.
"""
import time

from . import eratx

LANGUAGES = {"alonzo": (1,), "babbage": (1, 2), "conway": (1, 2)}
UNITS = [200_000, 100_000_000]
FEE = 1_500_000
FUND = 10_000_000
COLLATERAL = 3_000_000
DATUM = 42
OK = eratx.enc(bytes.fromhex("010000222499"))
FAIL = eratx.enc(bytes.fromhex("01000061"))


def index_utxo(snapshot):
    return {u[0]: (u[1], u[2]) for outs in snapshot[2].values() for u in outs}


def effect_errors(before, after, normal, collateral, txid, valid, expected_outputs):
    """Check both input roles and the complete set of this transaction's resulting outputs."""
    errors = []
    for txin in normal:
        if valid and txin in after:
            errors.append(f"normal input remains: {txin}")
        if not valid and after.get(txin) != before.get(txin):
            errors.append(f"normal input changed: {txin}")
    if valid and after.get(collateral) != before.get(collateral):
        errors.append("successful script consumed or changed collateral")
    if not valid and collateral in after:
        errors.append("failed script did not consume collateral")
    actual = {i: value for (tid, i), value in after.items() if tid == txid}
    if actual != (expected_outputs if valid else {}):
        errors.append("transaction outputs differ from advertised validity effects")
    return errors


def coverage_failures(rows):
    expected = {(era, language, case) for era, languages in LANGUAGES.items() for language in languages
                for case in ("success", "collateral", "reject-failure-valid", "reject-success-invalid")}
    observed = {(r["era"], r["language"], r["case"]) for r in rows if r.get("passed")}
    return [f"missing legacy Plutus admission/effect evidence: {sorted(expected - observed)}"] if expected - observed else []


def exercise(load, era):
    """Fund independent cases, reject opposite validity flags, then include both valid dispositions."""
    wallet = load.wallets[0]
    models = load.protocol_params()["costModels"]
    specs = []
    for language in LANGUAGES[era]:
        for script in (OK, FAIL):
            address = eratx.enterprise_address(eratx.plutus_script_hash(script, language), script=True)
            specs.extend([(wallet.address, FUND, None, {}), (wallet.address, COLLATERAL, None, {}),
                          (address, FUND, None, {"datum_hash": eratx.blake2b(eratx.enc(DATUM))})])
    setup = load.act(era, f"legacy-plutus-setup-{era}", wallet, specs)
    if not setup:
        raise RuntimeError(f"{era}: legacy Plutus setup not included")
    for ordinal, language in enumerate(LANGUAGES[era]):
        for branch, script in enumerate((OK, FAIL)):
            offset = ordinal * 6 + branch * 3
            normal = [(setup, offset), (setup, offset + 2)]
            collateral = (setup, offset + 1)
            policy = eratx.plutus_script_hash(script, language)
            assets = {policy: {b"era-load": 7}}
            outputs = [eratx.tx_out(era, wallet.address, 2 * FUND - FEE, assets)]
            # Ledger redeemer indices refer to the canonical ordering of normal inputs.
            redeemers = [(0, sorted(normal).index(normal[1]), DATUM, UNITS), (1, 0, DATUM, UNITS)]
            integrity = eratx.script_data_hash(eratx.enc(eratx.redeemers_cbor(era, redeemers)),
                                             eratx.enc([DATUM]), {language: models[f"PlutusV{language}"]})
            before = index_utxo(load.snapshot())
            for accepted in (False, True):
                valid = (script == OK) == accepted
                case = ("success" if valid else "collateral") if accepted else (
                    "reject-failure-valid" if valid else "reject-success-invalid")
                label = f"legacy-plutus-{era}-v{language}-{case}"
                tx, txid = eratx.build_tx(era, normal, outputs, FEE, [wallet.pay], mint=assets,
                    collateral=[collateral], plutus={language: [script]}, redeemers=redeemers,
                    datums=[DATUM], script_data=integrity, is_valid=valid)
                if not accepted:
                    passed = load.expect_rejected(era, tx, label, ["ValidationTagMismatch"])
                    after = index_utxo(load.snapshot())
                    passed = passed and all(after.get(i) == before.get(i) for i in normal + [collateral])
                    errors = [] if passed else ["opposite validity flag was not refused without consuming inputs"]
                else:
                    submitted = load.submit(era, tx, label)
                    deadline = time.monotonic() + 45
                    errors = ["transaction submission refused"]
                    while submitted and not load.stop.is_set() and time.monotonic() < deadline:
                        after = index_utxo(load.snapshot())
                        errors = effect_errors(before, after, normal, collateral, txid, valid,
                                               {0: (2 * FUND - FEE, assets)})
                        if not errors:
                            break
                        time.sleep(0.4)
                    passed = submitted and not errors
                observed = {"normalInputsPresent": [i in after for i in normal],
                            "collateralPresent": collateral in after,
                            "outputs": {str(i): eratx.enc(value).hex() for (tid, i), value in after.items()
                                        if tid == txid}}
                row = {"era": era, "language": language, "case": case, "passed": passed,
                       "operations": ["spend", "mint"], "observedEffects": observed,
                       "isValid": valid, "txid": txid, "errors": errors}
                load.plutus_evidence.append(row)
                load.keep_tx("legacy-plutus.jsonl", {**row, "tx": tx.hex(),
                    "normalInputs": normal, "collateral": collateral})
                load.log({"legacyPlutus": row})
                if not passed:
                    raise RuntimeError(f"{label}: {errors}")
