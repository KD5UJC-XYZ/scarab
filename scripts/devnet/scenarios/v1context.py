# SPDX-License-Identifier: Apache-2.0
"""The `v1-context` scenario: the Plutus V1 script context of the reference node, case by case.

Every case is a transaction with one minting script whose context the ledger has to build. What the
context can show differs by era (Babbage refuses a reference input, a reference script and an inline
datum; Conway shows none of the first two but still checks that each reference input can be shown), so
each case is built for the era the cluster is in, submitted to the reference node and recorded with
its verdict: accepted into a block, or refused with the node's own predicate failure. The record
(`v1-context-cases.json` in the run directory) is the shape of `fixtures/preview/plutus`, plus the
reference's verdict, and is what `fixtures/reference/v1-context` and `V1ContextSuite` are made from.

The transaction input and output sets are created first, in one setup transaction, so a case never
depends on what an earlier case did to the chain (an accepted case spends its own funding output).
"""
import importlib
import json
import subprocess
import time
from typing import NamedTuple

from .. import eratx
from ..cbor import enc
from ..cluster import up, up_eras
from ..util import MAGIC, SOCKET, data_root, query, ref

ENV_DIR = "/work/env"
UNITS = (200_000, 100_000_000)  # memory, steps: far below the limits, far above what the scripts spend
FEE = 1_500_000
COLLATERAL = 3_000_000  # at least 150 percent of the fee
FUND = 10_000_000
REFERENCE_VALUE = 3_000_000

# `\d r c -> ()` (version 1.0.0, three lambdas, unit): spends take three arguments, a minting policy two
# (the result of a legacy script is not looked at), and `error` is the script that fails.
FLAT_OK = bytes.fromhex("010000222499")
FLAT_FAIL = bytes.fromhex("01000061")
FLAT_OK_V3 = bytes.fromhex("0101002499")  # version 1.1.0, `\ctx -> ()`


def wrapped(flat):
    """The script as the witness set holds it: the flat encoding as a CBOR byte string."""
    return enc(flat)


def script_hash(language, content):
    return eratx.plutus_script_hash(content, language)


def base58_decode(text):
    alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    n = 0
    for c in text:
        n = n * 58 + alphabet.index(c)
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return b"\x00" * (len(text) - len(text.lstrip("1"))) + raw


class PreparedSetup(NamedTuple):
    transaction: bytes
    txid: str
    source: tuple
    outputs: dict


class Driver:
    """The cases of one era at one protocol version, funded from one key of the cluster."""

    def __init__(self, name, era, protocol_major, skey_path, address_text, log=print):
        self.name, self.era, self.protocol_major, self.log = name, era, protocol_major, log
        self.key = eratx.Key(self.sh(f"cat {skey_path}").stdout)
        self.address_text = address_text
        info = json.loads(self.sh(f"cardano-cli conway address info --address {address_text}").stdout)
        self.address = bytes.fromhex(info["base16"])
        pp = json.loads(self.sh(f"cardano-cli query protocol-parameters {SOCKET}").stdout)
        models = pp["costModels"]
        self.models = {1: models["PlutusV1"], 2: models.get("PlutusV2"), 3: models.get("PlutusV3")}
        self.byron = self.byron_address()
        self.cases = []

    # ------------------------------------------------------------ reference plumbing
    def sh(self, script, stdin=None, timeout=90):
        return subprocess.run(["docker", "exec", "-i", ref(self.name), "/bin/sh", "-c", script],
                              input=stdin, text=True, capture_output=True, timeout=timeout)

    def byron_address(self):
        text = self.sh(f"cardano-cli byron key signing-key-address --byron-formats --testnet-magic {MAGIC} "
                       f"--secret {ENV_DIR}/pools-keys/pool1/byron-delegate.key").stdout.split()[0]
        return base58_decode(text)

    def utxo(self):
        out = json.loads(self.sh(f"cardano-cli query utxo --address {self.address_text} {SOCKET} "
                                 f"--output-json").stdout)
        return {tuple(k.split("#")): v for k, v in out.items()}

    def submit(self, tx, label):
        env = eratx.envelope(self.era, tx)
        r = self.sh(f"mkdir -p /work/tx && cat > /work/tx/{label}.tx && cardano-cli conway transaction submit "
                    f"{SOCKET} --tx-file /work/tx/{label}.tx", stdin=env)
        text = (r.stderr or r.stdout).strip().split("CallStack")[0].strip()
        return r.returncode == 0, text

    def wait_gone(self, txid, ix, timeout=90):
        """The input is spent (or, for an invalid transaction, the collateral is)."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if (txid, str(ix)) not in self.utxo():
                return True
            time.sleep(1)
        return False

    def wait_present(self, txid, ix, timeout=90):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if (txid, str(ix)) in self.utxo():
                return True
            time.sleep(1)
        return False

    # ------------------------------------------------------------ transactions
    def out(self, coin, address=None, assets=None, inline_datum=None, script=None):
        return eratx.tx_out(self.era, address or self.address, coin, assets, inline_datum=inline_datum,
                            script_ref_plutus=script)

    def script_data(self, redeemers, languages):
        red = eratx.enc(eratx.redeemers_cbor(self.era, redeemers))
        return eratx.script_data_hash(red, b"", {l: self.models[l] for l in languages})

    def build(self, inputs, outputs, *, fee=FEE, mint=None, scripts=None, redeemers=None, collateral=None,
              reference_inputs=None, valid=True):
        """A signed transaction. `scripts` maps a language to its script contents."""
        languages = sorted(scripts or {})
        data_hash = self.script_data(redeemers, languages) if redeemers else None
        return eratx.build_tx(
            self.era, inputs, outputs, fee, [self.key], mint=mint,
            plutus={l: [s] for l, s in (scripts or {}).items()}, redeemers=redeemers,
            collateral=collateral, reference_inputs=reference_inputs, script_data=data_hash,
            is_valid=valid)

    # ------------------------------------------------------------ the setup transaction
    def prepare_setup(self, specials, count):
        """Prepare once, so a caller confirming inclusion can resubmit identical signed bytes."""
        funds = self.utxo()
        (txid, ix), v = max(funds.items(), key=lambda kv: kv[1]["value"]["lovelace"])
        outputs, names = [], []
        for n in range(count):
            outputs.append(self.out(FUND))
            names.append(f"fund{n}")
            outputs.append(self.out(COLLATERAL))
            names.append(f"collateral{n}")
        for name, o in specials.items():
            outputs.append(o)
            names.append(name)
        spent = sum(self.coin_of(o) for o in outputs)
        change = v["value"]["lovelace"] - spent - FEE
        outputs.append(self.out(change))
        names.append("change")
        tx, tid = self.build([(txid, int(ix))], outputs)
        made = {n: (tid, i, o) for i, (n, o) in enumerate(zip(names, outputs))}
        return PreparedSetup(tx, tid, (txid, int(ix)), made)

    def setup(self, specials, count):
        """Create funding, collateral and special outputs; preserve the context driver's wait policy."""
        prepared = self.prepare_setup(specials, count)
        ok, text = self.submit(prepared.transaction, "setup")
        if not ok:
            raise SystemExit(f"setup refused: {text}")
        if not self.wait_present(prepared.txid, prepared.outputs["change"][1]):
            raise SystemExit("setup transaction not in a block")
        return prepared.outputs

    @staticmethod
    def coin_of(o):
        v = o[1] if isinstance(o, list) else o[1]
        return v if isinstance(v, int) else v[0]

    # ------------------------------------------------------------ one case
    def record(self, name, description, tx, tid, resolved, accepted, text, valid, languages, expect):
        """`resolved`: {role: [(txid, ix, output)]} for spent, references, collateral."""
        enc_out = lambda o: enc(o).hex()
        parts = _split_tx(tx)
        case = {
            "name": name, "kind": description, "slot": "0", "protocolMajor": self.protocol_major,
            "era": self.era, "languages": languages,
            "body": parts[0].hex(), "witnesses": parts[1].hex(), "auxiliary": None, "isValid": valid,
            "spent": [{"in": f"{t}#{i}", "out": enc_out(o)} for t, i, o in resolved["spent"]],
            "references": [{"in": f"{t}#{i}", "out": enc_out(o)} for t, i, o in resolved["references"]],
            "collateral": [{"in": f"{t}#{i}", "out": enc_out(o)} for t, i, o in resolved["collateral"]],
            "costModels": {str(l - 1): [str(x) for x in self.models[l]] for l in languages},  # by language id, V1 is 0
            "clock": {},
            "txId": tid,
            "reference": {"accepted": accepted, "expected": expect, "error": "" if accepted else text[:1500]},
        }
        self.cases.append(case)
        verdict = "accepted" if accepted else "refused"
        agree = accepted == (expect is None)
        self.log({"case": name, "verdict": verdict, "agreesWithExpectation": agree,
                  "error": "" if accepted else text[:400]})
        return agree


def _split_tx(tx):
    """(body bytes, witness set bytes) of a signed transaction `84 body wits is_valid aux`."""
    from ..cborspan import children
    spans = children(tx, 0)
    return tx[spans[0][0]:spans[0][1]], tx[spans[1][0]:spans[1][1]]


def run(driver):
    """All cases of the cluster's era at its protocol version. Returns the names of the cases the
    reference decided differently from what the case expects."""
    d = driver
    era = d.era
    ok2 = wrapped(FLAT_OK)
    pre_babbage = era == "alonzo"
    conway = era == "conway"
    prefix = f"{era}-pv{d.protocol_major}-"
    inline_err = "InlineDatumsNotSupported"
    byron_err = "ByronTxOutInContext"
    refscript_err = "ReferenceScriptsNotSupported"
    refinput_err = "ReferenceInputsNotSupported"
    mismatch = "ValidationTagMismatch"

    # outputs read as references or as inputs (Babbage on)
    specials = {} if pre_babbage else {
        "refPlain": d.out(REFERENCE_VALUE),
        "refInline": d.out(REFERENCE_VALUE, inline_datum=42),
        "refByron": d.out(REFERENCE_VALUE, address=d.byron),
        "refScript": d.out(REFERENCE_VALUE, script=(2, ok2)),
        "inInline": d.out(FUND, inline_datum=7),
        "inScript": d.out(FUND, script=(2, ok2)),
        "inScriptInvalid": d.out(FUND, script=(2, ok2)),
    }
    count = 6 if pre_babbage else 26  # a funding and a collateral output for each case that follows
    made = d.setup(specials, count)
    used = {"n": 0}

    def fresh():
        n = used["n"]
        used["n"] += 1
        return made[f"fund{n}"], made[f"collateral{n}"]

    failures = []

    def case(name, description, *, language=1, refs=(), spend_extra=(), extra_outputs=(), valid=True,
             script_ok=True, expect=None, second=None):
        """A transaction that mints one token under a script of `language` (and under a second policy
        when `second` gives a further (language, flat) script), with the given reference inputs, further
        spent inputs and further outputs. `expect`: None when the reference must accept the transaction,
        else a text its refusal must contain."""
        name = prefix + name
        fund, coll = fresh()
        flats = {1: FLAT_OK if script_ok else FLAT_FAIL, 2: FLAT_OK if script_ok else FLAT_FAIL, 3: FLAT_OK_V3}
        content = wrapped(flats[language])
        policy = script_hash(language, content)
        scripts = {language: content}
        minted = {policy: {b"t": 1}}
        policies = [policy]
        if second is not None:
            language2, flat2 = second
            content2 = wrapped(flat2)
            policy2 = script_hash(language2, content2)
            scripts[language2] = content2
            minted[policy2] = {b"t": 1}
            policies.append(policy2)
        order = sorted(minted)
        redeemers = [(1, order.index(p), 0, UNITS) for p in policies]
        inputs = [(fund[0], fund[1])] + [(m[0], m[1]) for m in spend_extra]
        spent_value = FUND + sum(d.coin_of(m[2]) for m in spend_extra)
        change = spent_value - FEE - sum(d.coin_of(o) for o in extra_outputs)
        outputs = [d.out(change, assets=minted)] + list(extra_outputs)
        tx, tid = d.build(inputs, outputs, mint=minted, scripts=scripts, redeemers=redeemers,
                          collateral=[(coll[0], coll[1])],
                          reference_inputs=[(m[0], m[1]) for m in refs] or None, valid=valid)
        accepted, text = d.submit(tx, name)
        if accepted and not (d.wait_gone(coll[0], coll[1]) if not valid else d.wait_gone(fund[0], fund[1])):
            accepted, text = False, "accepted by the node but not in a block in time"
        resolved = {"spent": [fund] + list(spend_extra), "references": list(refs), "collateral": [coll]}
        agree = d.record(name, description, tx, tid, resolved, accepted, text, valid, sorted(scripts), expect)
        if agree and expect is not None and expect not in text:
            agree = False
            d.log({"case": name, "wrongReason": expect, "error": text[:600]})
        if not agree:
            failures.append(name)

    S = made
    # ---- the validity tag against the outcome of the script (every era)
    case("v1-plain", "V1 mint, nothing special", expect=None)
    case("v1-plain-invalid-tag", "V1 mint that fails, is_valid = false", script_ok=False, valid=False)
    case("v1-plain-fail-valid-tag", "V1 mint that fails, is_valid = true", script_ok=False, expect=mismatch)
    case("v1-plain-pass-invalid-tag", "V1 mint that passes, is_valid = false", valid=False, expect=mismatch)
    if pre_babbage:
        # Alonzo filtered a Byron output out of the V1 context instead of refusing it (kept for
        # compatibility: `Alonzo/Plutus/TxInfo.hs:146-149`)
        case("v1-output-byron", "V1 with an output at a Byron address (left out of the context)",
             extra_outputs=[d.out(REFERENCE_VALUE, address=d.byron)])
        return failures
    # ---- reference inputs
    if not conway:
        case("v1-refinput-plain", "V1 with a reference input", refs=[S["refPlain"]], expect=refinput_err)
        case("v1-refinput-plain-invalid-tag", "same, is_valid = false and a failing script",
             refs=[S["refPlain"]], valid=False, script_ok=False, expect=refinput_err)
    else:
        case("v1-refinput-plain", "V1 with a plain reference input", refs=[S["refPlain"]])
        case("v1-refinput-inline-datum", "V1 with a reference input holding an inline datum",
             refs=[S["refInline"]], expect=inline_err)
        case("v1-refinput-inline-datum-invalid-tag", "same, is_valid = false and a failing script",
             refs=[S["refInline"]], valid=False, script_ok=False, expect=inline_err)
        case("v1-refinput-byron", "V1 with a reference input at a Byron address",
             refs=[S["refByron"]], expect=byron_err)
        case("v1-refinput-byron-invalid-tag", "same, is_valid = false and a failing script",
             refs=[S["refByron"]], valid=False, script_ok=False, expect=byron_err)
        case("v1-refinput-refscript", "V1 with a reference input carrying a reference script",
             refs=[S["refScript"]])
        case("v1-refinput-refscript-invalid-tag", "same, is_valid = false and a failing script",
             refs=[S["refScript"]], valid=False, script_ok=False)
    # ---- reference scripts in outputs and in spent inputs
    ref_out = d.out(REFERENCE_VALUE, script=(2, ok2))
    if not conway:
        case("v1-output-refscript", "V1 with an output carrying a reference script",
             extra_outputs=[ref_out], expect=refscript_err)
        case("v1-input-refscript", "V1 spending an input carrying a reference script",
             spend_extra=[S["inScript"]], expect=refscript_err)
    else:
        case("v1-output-refscript", "V1 with an output carrying a reference script", extra_outputs=[ref_out])
        case("v1-output-refscript-invalid-tag", "same, is_valid = false and a failing script",
             extra_outputs=[ref_out], valid=False, script_ok=False)
        case("v1-input-refscript", "V1 spending an input carrying a reference script",
             spend_extra=[S["inScript"]])
        case("v1-input-refscript-invalid-tag", "same, is_valid = false and a failing script",
             spend_extra=[S["inScriptInvalid"]], valid=False, script_ok=False)
    # ---- restrictions that stay: inline datums and Byron addresses
    case("v1-output-inline-datum", "V1 with an output holding an inline datum",
         extra_outputs=[d.out(REFERENCE_VALUE, inline_datum=9)], expect=inline_err)
    case("v1-input-inline-datum", "V1 spending an input holding an inline datum",
         spend_extra=[S["inInline"]], expect=inline_err)
    case("v1-output-byron", "V1 with an output at a Byron address",
         extra_outputs=[d.out(REFERENCE_VALUE, address=d.byron)], expect=byron_err)
    # ---- the context of a later script refuses although an earlier one fails (is_valid = false)
    if conway:
        case("v2-fail-then-v1-refinput-inline-invalid-tag",
             "is_valid = false; a failing V2 script and a V1 script whose context cannot be built",
             language=2, script_ok=False, second=(1, FLAT_OK), refs=[S["refInline"]], valid=False,
             expect=inline_err)
    # ---- other languages see none of it (the cost models of V2 and V3 come with the Conway genesis: a
    # cluster that walks the eras has none in Babbage, and no script of the language can run there)
    if d.models[2] is None:
        return failures
    case("v2-refinput-inline-datum", "V2 with a reference input holding an inline datum",
         language=2, refs=[S["refInline"]])
    case("v2-refinput-byron", "V2 with a reference input at a Byron address",
         language=2, refs=[S["refByron"]], expect=byron_err)
    case("v2-output-refscript", "V2 with an output carrying a reference script",
         language=2, extra_outputs=[ref_out])
    if conway:
        case("v3-refinput-inline-datum", "V3 with a reference input holding an inline datum",
             language=3, refs=[S["refInline"]])
        case("v3-refinput-byron", "V3 with a reference input at a Byron address",
             language=3, refs=[S["refByron"]], expect=byron_err)
    return failures


def open_text(name, path):
    return subprocess.run(["docker", "exec", ref(name), "cat", path], capture_output=True, text=True,
                          check=True).stdout.strip()


def v1_context(args):
    """`up` (a Conway cluster at the protocol version of the devnet genesis) or `--eras`, the cluster that
    walks Byron to Conway with the cases run in every Alonzo, Babbage and Conway protocol version."""
    root = data_root(args.name)
    log = lambda m: print(json.dumps(m), flush=True)
    cases, failures = [], []

    def one_pass(era, major, skey, address):
        # the module is read again for each pass, so a repair made while the cluster walks takes effect
        module = importlib.reload(importlib.import_module(__name__))
        driver = module.Driver(args.name, era, major, skey, address, log=log)
        try:
            failed = module.run(driver)
        finally:
            cases.extend(driver.cases)
            (root / "v1-context-cases.json").write_text(json.dumps(cases, indent=1))
        failures.extend(failed)
        (root / "v1-context-cases.json").write_text(json.dumps(cases, indent=1))
        log({"pass": f"{era} {major}", "cases": len(driver.cases), "disagreements": failed})

    if getattr(args, "eras", False):
        state = {"driver": None, "ran": set()}

        def wallet(drv):
            # the second delegator's payment key and base address: the era driver's proposals use the first
            address = in_ref_text(args.name, f"cardano-cli conway address build --testnet-magic {MAGIC} "
                                             f"--payment-verification-key-file {ENV_DIR}/stake-delegators/delegator2/payment.vkey "
                                             f"--stake-verification-key-file {ENV_DIR}/stake-delegators/delegator2/staking.vkey")
            return f"{ENV_DIR}/stake-delegators/delegator2/payment.skey", address

        def on_pools_ready(drv):
            state["driver"] = drv

        def on_tick(tip):
            drv = state["driver"]
            era, epoch = tip["era"].lower(), tip["epoch"]
            if drv is None or era not in ("alonzo", "babbage") or epoch in state["ran"]:
                return
            if ("epoch", epoch) not in drv.done:
                return
            state["ran"].add(epoch)
            try:
                one_pass(era, drv.protocol[epoch], *wallet(drv))
            except BaseException as e:  # a failed pass must not stop the walk; the next epoch tries again
                log({"passFailed": f"{era} epoch {epoch}", "error": repr(e)})
                failures.append(f"{era} epoch {epoch}")

        up_eras(args, on_pools_ready=on_pools_ready, on_tick=on_tick)
        drv = state["driver"]
        one_pass("conway", max(drv.protocol.values()), *wallet(drv))
    else:
        if not getattr(args, "no_up", False):
            up(args)
        for _ in range(60):
            if query(args.name, "tip").get("block", 0) >= 3:
                break
            time.sleep(1)
        pp = query(args.name, "protocol-parameters")
        address = open_text(args.name, f"{ENV_DIR}/utxo-keys/utxo1/utxo.addr")
        one_pass("conway", pp["protocolVersion"]["major"], f"{ENV_DIR}/utxo-keys/utxo1/utxo.skey", address)
    print(json.dumps({"cases": len(cases), "record": str(root / "v1-context-cases.json"),
                      "disagreements": failures}))
    raise SystemExit(1 if failures else 0)


def in_ref_text(name, script):
    return subprocess.run(["docker", "exec", ref(name), "/bin/sh", "-c", script], capture_output=True, text=True,
                          check=True).stdout.strip()
