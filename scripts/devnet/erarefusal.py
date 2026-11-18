# SPDX-License-Identifier: Apache-2.0
"""Refusals of Plutus and witness rules in Alonzo, Babbage and Conway, with the reference's raw reasons.

Each case is a transaction the Haskell node must refuse; its raw `ApplyTxErr` over LocalTxSubmission is kept
with the transaction and the provenance of the run in `refused-raw-plutus.jsonl` of the run directory, and
`refused_fixture.py raw-plutus` assembles `fixtures/devnet/refused-raw-plutus.jsonl` from it. The Scala suite
`PlutusFailureReferenceSuite` checks that `PreConwayFailureCbor` / `PredicateFailureCbor` write those bytes.
Nothing is asserted about the reference's text here: the bytes are the evidence, and a case the reference
accepts or refuses with a list the Scala model cannot rebuild shows up when the suite is run on the recording.

The redeemer-index cases (`redeemer_index`) keep every verdict, accepted or refused, in `redeemer-index.jsonl`
of the run directory, with the outputs each transaction spends and the Plutus V1 cost model in force, so that
`RedeemerIndexReferenceSuite` can put the very transaction to Scarab's rules; `refused_fixture.py
redeemer-index` assembles `fixtures/devnet/redeemer-index.jsonl` from it.
"""
import random
import subprocess

from . import eratx
from .eraload import ENV, MAGIC, SOCKET
from .ntcquery import decode_all
from .eraplutus import COLLATERAL, DATUM, FEE, FUND, OK, UNITS
from .util import REFERENCE_IMAGE, REPO


def provenance():
    def git(*args):
        return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True).stdout.strip()
    return {"commit": git("rev-parse", "HEAD"), "dirty": bool(git("status", "--porcelain", "--", "scripts")),
            "image": REFERENCE_IMAGE}


def rendering(load, era, tx, label):
    """The `cardano-cli` rendering of the reference's refusal of `tx`, from a second submission."""
    cli = load.sh(f"mkdir -p /work/tx && cat > /work/tx/{label}.tx && cardano-cli conway transaction submit "
                  f"--testnet-magic {MAGIC} --socket-path {SOCKET} --tx-file /work/tx/{label}.tx",
                  stdin=eratx.envelope(era, tx))
    return (cli.stderr or cli.stdout).strip().split("CallStack")[0].strip()


def run_context(load, era):
    """The node, the protocol in force and the provenance of the run, kept with each recorded verdict."""
    node = load.sh("cardano-node --version").stdout.strip().splitlines()
    protocol = None
    try:
        from .eraplutus import reference_observation
        protocol = reference_observation(load, era)
    except Exception as e:
        protocol = {"error": repr(e)}
    return {"node": node[0] if node else "", "protocol": protocol,
            "command": f"python3 scripts/devnet.py --name {load.name} e2e", **provenance()}


def record(load, era, tx, label):
    """Submit `tx` over LocalTxSubmission, keep the raw reason, and the CLI's rendering next to it."""
    try:
        with load.lsq_factory() as session:
            reason = session.submit(tx, load.ERA_INDEX[era])
    except Exception as e:
        load.log({"refusalCaptureFailed": label, "error": repr(e)})
        return
    row = {"label": label, "era": era, "refused": reason is not None}
    if reason is None:
        load.refusal_evidence.append(row)
        load.log({"refusalAccepted": label})
        return
    text = rendering(load, era, tx, label)
    entry = {"label": label, "era": era, "tx": tx.hex(), "reason": reason.hex(), "cli": text[:3000],
             **run_context(load, era)}
    load.keep_tx("refused-raw-plutus.jsonl", entry)
    load.refusal_evidence.append({**row, "reasonBytes": len(reason), "cli": text[:300]})
    load.log({"refusal": label, "reasonHex": reason.hex()[:400], "cli": text[:600]})


REDEEMER_INDEX = "redeemer-index.jsonl"

# The redeemer-index cases of each era, in the order `redeemer_index` sends them; a recording is complete only
# with every one of them, once (`refused_fixture.redeemer_index_fixture` refuses anything else).
PRE_CONWAY_CASES = ("missing-redeemers-every-purpose", "repeated-certificate-redeemers-0-1",
                    "repeated-certificate-no-redeemer", "distinct-certificates-redeemer-0",
                    "repeated-certificate-redeemer-0")
CONWAY_CASES = ("missing-redeemers-every-purpose", "distinct-certificates-redeemer-0",
                "distinct-certificates-redeemers-0-1")
REDEEMER_INDEX_ERAS = ("alonzo", "babbage", "conway")


def redeemer_index_cases(era):
    """The labels of `era`'s redeemer-index cases, in the order they are sent."""
    return [f"{era}-{case}" for case in (CONWAY_CASES if era == "conway" else PRE_CONWAY_CASES)]


def redeemer_index_labels():
    """Every label a complete redeemer-index recording holds."""
    return [label for era in REDEEMER_INDEX_ERAS for label in redeemer_index_cases(era)]


def keep_verdict(load, era, tx, txid, label, spent, models):
    """Submit `tx` over LocalTxSubmission and keep the reference's verdict in `redeemer-index.jsonl`: accepted, or
    refused with the raw reason and the CLI's rendering, next to the outputs the transaction spends (`spent`, the
    output CBOR by `txid#ix`) and the Plutus V1 cost model in force. An accepted transaction is waited for, so that
    the next case spends the outputs it left."""
    try:
        with load.lsq_factory() as session:
            reason = session.submit(tx, load.ERA_INDEX[era])
    except Exception as e:
        load.log({"redeemerIndexCaptureFailed": label, "error": repr(e)})
        return None
    entry = {"label": label, "era": era, "tx": tx.hex(), "verdict": "refused" if reason is not None else "accepted",
             "spent": spent, "costModelPlutusV1": models[1]}
    if reason is not None:
        entry.update(reason=reason.hex(), cli=rendering(load, era, tx, label)[:3000])
    load.keep_tx(REDEEMER_INDEX, {**entry, **run_context(load, era)})
    load.refusal_evidence.append({"label": label, "era": era, "refused": reason is not None})
    load.log({"redeemerIndex": label, "verdict": entry["verdict"], "reasonHex": entry.get("reason", "")[:400]})
    if reason is None and not load.confirm(txid):
        load.log({"redeemerIndexUnconfirmed": label})
    return entry["verdict"]


def redeemer_index(load, era, rw, cred, h, pools, script_addr, datum_hash, spend, collateral, avoid, models):
    """The redeemer index of a certificate, each case's verdict kept whatever it is (`keep_verdict`).

    Before Conway a certificate that occurs again takes the redeemer index of its first occurrence
    (`getAlonzoScriptsNeeded`, cardano-ledger f649f975 `Alonzo/UTxO.hs:318-361`): two identical delegations of the
    script's credential, with a redeemer at index 0 alone, at 0 and 1, and with none. Conway gives each certificate
    its own index (`Conway/UTxO.hs:62-80`) and its body cannot repeat one (`decodeOSet`), so its pair delegates to two
    pools. Then one transaction that needs the script for each purpose of these eras, spending, withdrawing,
    certifying and minting, with no redeemer. The cases that change the ledger (accepted ones) come last."""
    pre_conway = era != "conway"
    first, other = [2, cred, pools[0]], [2, cred, pools[1]]

    def redeemer(tag, index):
        return (tag, index, DATUM, UNITS)

    def sdh(redeemers, datums):
        return eratx.script_data_hash(eratx.enc(eratx.redeemers_cbor(era, redeemers)),
                                      eratx.enc([DATUM]) if datums else b"", models)

    def outputs_of(tx, by_input):
        body = decode_all(tx)[0]
        spent = {}
        for txid, ix in list(body[0]) + list(body.get(13, [])):
            key = (txid.hex(), ix)
            address, coin, assets = by_input[key]
            out = eratx.tx_out(era, address, coin, assets or None,
                               datum_hash=datum_hash if address == script_addr else None)
            spent[f"{key[0]}#{ix}"] = eratx.enc(out).hex()
        return spent

    def case(label, certs, redeemers, datums=False, **kw):
        _, _, by_addr = load.snapshot()
        by_input = {txin: (address, coin, assets) for address, outs in by_addr.items() for txin, coin, assets in outs}
        built = load.act(era, label, rw, certs=certs, plutus={1: [OK]}, collateral=collateral, avoid=avoid,
                         redeemers=list(redeemers) or None, script_data=sdh(redeemers, datums), fee=FEE,
                         build_only=era, datums=[DATUM] if datums else None, **kw)
        if not built:
            load.log({"redeemerIndexNotBuilt": label})
            return None
        tx, txid = built
        try:
            spent = outputs_of(tx, by_input)
        except Exception as e:
            load.log({"redeemerIndexSpentUnknown": label, "error": repr(e)})
            return None
        return keep_verdict(load, era, tx, txid, label, spent, models)

    def attempt(label, *args, **kw):
        try:
            return case(label, *args, **kw)
        except Exception as e:  # one case must not stop the next
            load.log({"redeemerIndexCaseFailed": label, "error": repr(e)})
            return None

    token = {h: {b"A": 1}}
    kept = {}
    kept[f"{era}-missing-redeemers-every-purpose"] = attempt(
        f"{era}-missing-redeemers-every-purpose", [first], [], datums=True, spend_extra=[spend],
        withdrawals={eratx.reward_address(h, script=True): 0}, mint=token)
    if pre_conway:
        for label, certs, redeemers in [
                (f"{era}-repeated-certificate-redeemers-0-1", [first, first], [redeemer(2, 0), redeemer(2, 1)]),
                (f"{era}-repeated-certificate-no-redeemer", [first, first], []),
                (f"{era}-distinct-certificates-redeemer-0", [first, other], [redeemer(2, 0)]),
                (f"{era}-repeated-certificate-redeemer-0", [first, first], [redeemer(2, 0)])]:
            kept[label] = attempt(label, certs, redeemers)
    else:
        for label, certs, redeemers in [
                (f"{era}-distinct-certificates-redeemer-0", [first, other], [redeemer(2, 0)]),
                (f"{era}-distinct-certificates-redeemers-0-1", [first, other], [redeemer(2, 0), redeemer(2, 1)])]:
            kept[label] = attempt(label, certs, redeemers)
    missing = [label for label in redeemer_index_cases(era) if kept.get(label) is None]
    if missing:
        load.log({"redeemerIndexIncomplete": era, "missing": missing})
    return missing


def exercise(load, era):
    """The refusals of `era`, from a dedicated wallet so that the load's other scenarios leave its outputs alone."""
    from .eraload import Wallet
    pp = load.protocol_params()
    deposit = pp["stakeAddressDeposit"]
    v1 = {1: pp["costModels"]["PlutusV1"]}
    pre_conway = era != "conway"
    funder, rw = load.wallets[0], Wallet(random.Random(0x123 + load.ERA_INDEX[era]))
    h = eratx.plutus_script_hash(OK, 1)
    cred = [1, h]
    pool = load.read_key(f"{ENV}/pools-keys/pool1/cold.skey").hash
    script_addr = eratx.enterprise_address(h, script=True)
    datum_hash = eratx.blake2b(eratx.enc(DATUM))
    outputs = [(script_addr, FUND, None, {"datum_hash": datum_hash}), (rw.address, COLLATERAL, None, {}),
               (rw.address, 3_000_000, None, {}), (rw.address, 60_000_000, None, {})]
    if era != "alonzo":
        outputs += [(rw.address, 4_000_000, None, {"inline_datum": [42, b"inline"]}),
                    (rw.address, 8_000_000, None, {"script_ref": eratx.all_script(eratx.sig_script(funder.pay.hash))})]
    # the redeemer-index cases' own script-locked output and funds: a case above the reference accepts spends the
    # others, and its change is not in the ledger's UTxO until a block holds it
    own = len(outputs)
    outputs += [(script_addr, FUND, None, {"datum_hash": datum_hash}), (rw.address, 60_000_000, None, {})]
    # registering the script credential needs no witness: it is the refused certificates that name its script;
    # it stays registered into the later eras
    register = "refusal-script-registered" not in load.done
    setup = None
    for _ in range(3):
        setup = load.act(era, f"refusal-setup-{era}", funder, outputs, certs=[[0, cred]] if register else None,
                         deposit=deposit if register else 0)
        if setup:
            break
    if not setup:
        raise RuntimeError(f"{era}: refusal setup not included")
    load.done.add("refusal-script-registered")
    spend = ((setup, 0), FUND, {})
    collateral = [(setup, 1)]
    avoid = [(setup, ix) for ix in range(1, own) if ix != 3]

    def sdh(redeemers, datums, models, empty=None):
        encoded = eratx.enc(eratx.redeemers_cbor(era, redeemers)) if empty is None else empty
        return eratx.script_data_hash(encoded, eratx.enc([DATUM]) if datums else b"", models)

    def attempt(label, outputs=(), **kw):
        try:
            load.act(era, label, rw, outputs, refusal=label, avoid=avoid, **kw)
        except Exception as e:  # one case must not stop the next
            load.log({"refusalCaseFailed": label, "error": repr(e)})

    other_empty = b"\xa0" if pre_conway else b"\x80"  # the empty redeemers the other way
    hashes = {"hash": lambda red, dat: sdh([], False, v1), "alt": lambda red, dat: sdh([], False, v1, other_empty),
              "none": None}
    # (1) a certificate that names the script, no redeemer
    for suffix, script_data in hashes.items():
        extra = {} if script_data is None else {"script_data": script_data}
        attempt(f"{era}-missing-redeemer-delegation-{suffix}", certs=[[2, cred, pool]], plutus={1: [OK]}, fee=FEE,
                **extra)
        attempt(f"{era}-missing-redeemer-deregistration-{suffix}", certs=[[1, cred]], refund=deposit,
                plutus={1: [OK]}, fee=FEE, **extra)
        if not pre_conway:
            attempt(f"{era}-missing-redeemer-deregistration-refund-{suffix}",
                    certs=[eratx.unreg_cert(h, deposit, script=True)], refund=deposit, plutus={1: [OK]}, fee=FEE,
                    **extra)
    # (2) a spent script without a redeemer, and without its script
    datums_hash = {"hash": lambda red, dat: sdh([], True, v1), "alt": lambda red, dat: sdh([], True, v1, other_empty)}
    for suffix, script_data in datums_hash.items():
        attempt(f"{era}-no-redeemer-spend-{suffix}", spend_extra=[spend], plutus={1: [OK]}, datums=[DATUM],
                collateral=collateral, script_data=script_data, fee=FEE)

    def spend_redeemers(sorted_inputs):
        return [(0, sorted_inputs.index(spend[0]), DATUM, UNITS)]

    def with_redeemers(models):
        return lambda red, dat: sdh(red, True, models)

    attempt(f"{era}-no-witness-spend-language", spend_extra=[spend], datums=[DATUM], collateral=collateral,
            redeemers=spend_redeemers, script_data=with_redeemers(v1), fee=FEE)
    attempt(f"{era}-no-witness-spend-plain", spend_extra=[spend], datums=[DATUM], collateral=collateral,
            redeemers=spend_redeemers, script_data=with_redeemers({}), fee=FEE)
    # a script spent next to an input the UTxO does not have: the translation cannot resolve it
    missing = (("ab" * 32, 0), 0, {})
    attempt(f"{era}-bad-translation-missing-input", spend_extra=[spend, missing], plutus={1: [OK]}, datums=[DATUM],
            collateral=collateral, script_data=with_redeemers(v1), redeemers=spend_redeemers, fee=FEE)
    # (3) a Plutus V1 script cannot see what Babbage added
    if era != "alonzo":
        translate = dict(plutus={1: [OK]}, datums=[DATUM], collateral=collateral, script_data=with_redeemers(v1),
                         redeemers=spend_redeemers, fee=FEE)
        attempt(f"{era}-bad-translation-inline-datum", spend_extra=[spend, ((setup, 4), 4_000_000, {})], **translate)
        attempt(f"{era}-bad-translation-reference-script", spend_extra=[spend, ((setup, 5), 8_000_000, {})],
                **translate)
        if era == "babbage":  # Conway shows a V1 script no reference input and accepts this one, spending the inputs
            attempt(f"{era}-bad-translation-reference-input", spend_extra=[spend], reference_inputs=[(setup, 2)],
                    **translate)
    # (4) plain refusals: a key witness that is missing (a set), an output below the minimum
    w1 = [u for u in load.funds(load.wallets[1].address) if not u[2]]
    if w1:
        attempt(f"{era}-missing-vkey-witness", spend_extra=[w1[0]])
    attempt(f"{era}-output-too-small", outputs=[(load.wallets[2].address, 100_000, None, {})])
    # (5) the redeemer index of a certificate: last, since its accepted cases change the ledger
    pools = [load.read_key(f"{ENV}/pools-keys/pool{n}/cold.skey").hash for n in (1, 2)]
    redeemer_index(load, era, rw, cred, h, pools, script_addr, datum_hash, ((setup, own), FUND, {}), collateral,
                   [(setup, ix) for ix in range(1, own + 1)], v1)
