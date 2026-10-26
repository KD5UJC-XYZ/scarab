# SPDX-License-Identifier: Apache-2.0
"""The transactions of the reference-refusal corpus (#401): each is one the reference node must refuse.

The single-rule cases of `ntccases` are reused as they are. The cases added here break several rules at
once, so that the reference's refusal holds more than one predicate failure: the order in which its rules
run, and what each rule accumulates before it stops, is then part of the expected bytes. Nothing here says
what the refusal is; `refusalcorpus` records what the reference answers. `expect` only names constructors
the reference's rendering must contain, so that a case built wrongly (refused for another reason than the
one it was built for) is noticed when the corpus is refreshed.
"""
import json
from dataclasses import dataclass

from . import eratx, ntccases
from .eraplutus import DATUM
from .ntccases import CLI, FAILS, SUCCEEDS, Builder
from .util import ENV, MAGIC

FEE = 400_000
PLUTUS_FEE = 1_500_000
UNITS = (1_000_000, 500_000_000)
FUND = 10_000_000


@dataclass
class Case:
    label: str
    tx: bytes
    expect: tuple


def seeded_key(name):
    """A throwaway key whose seed is derived from `name`, so that a refresh builds the same credentials."""
    seed = eratx.blake2b(f"scarab refusal corpus {name}".encode())
    return eratx.Key(json.dumps({"cborHex": "5820" + seed.hex()}))


def read_key(b, path):
    return eratx.Key(b.sh(f"cat {path}"))


def txin(w):
    txid, ix = w["txin"].split("#")
    return txid, int(ix)


def setup_outputs(b, funder, key, outputs):
    """Submit an ordinary transaction creating `outputs` from `funder`, wait until the reference holds its
    outputs, and return its id. The corpus cases that spend script outputs need them on chain."""
    fee = FEE
    total = sum(coin for _, coin in outputs)
    outs = [out for out, _ in outputs] + [
        eratx.tx_out("conway", eratx.enterprise_address(key.hash), funder["lovelace"] - total - fee)]
    tx, body_hash = eratx.build_tx("conway", [txin(funder)], outs, fee, [key])
    b.sh(f"echo '{eratx.envelope('conway', tx)}' > /work/setup.tx")
    b.sh(f"{CLI} transaction submit --testnet-magic {MAGIC} --socket-path {b.socket} --tx-file /work/setup.tx")
    for _ in range(120):
        utxo = json.loads(b.sh(f"{CLI} query utxo --tx-in {body_hash}#0 --testnet-magic {MAGIC} "
                               f"--socket-path {b.socket} --output-json"))
        if utxo:
            return body_hash
        b.sh("sleep 1")
    raise RuntimeError("the setup transaction of the script cases did not reach a block")


def corpus(name, socket):
    """Every case, in a fixed order: the `ntccases` corpus, then the combinations."""
    cases = [Case(c.label, c.tx, (c.ledger_rule,)) for c in ntccases.corpus(name, socket)]
    b = Builder(name, socket)
    w, other, funder = b.wallet(1), b.wallet(2), b.wallet(3)
    k1, k2, k3 = read_key(b, w["skey"]), read_key(b, other["skey"]), read_key(b, funder["skey"])
    kd1 = read_key(b, f"{ENV}/stake-delegators/delegator1/staking.skey")
    kdrep = read_key(b, f"{ENV}/drep-keys/drep1/drep.skey")
    fresh_stake, fresh_drep = seeded_key("stake"), seeded_key("drep")
    fresh_cold, fresh_hot = seeded_key("cold"), seeded_key("hot")
    params = json.loads(b.sh(f"{CLI} query protocol-parameters --testnet-magic {MAGIC} --socket-path {socket}"))
    v3 = params["costModels"]["PlutusV3"]
    gov_deposit, key_deposit = params["govActionDeposit"], params["stakeAddressDeposit"]
    epoch = json.loads(b.sh(f"{CLI} query tip --testnet-magic {MAGIC} --socket-path {socket}"))["epoch"]
    here = eratx.enterprise_address(k1.hash)
    unknown_pool = eratx.blake2b(b"scarab refusal corpus unknown pool", 28)
    unknown_input = ("00" * 31 + "01", 0)
    fake_action = eratx.gov_id("11" * 32, 0)
    d1_account = eratx.reward_address(kd1.hash)

    def add(label, expect, **kw):
        tx, _ = eratx.build_tx("conway", **kw)
        cases.append(Case(label, tx, tuple(expect)))

    def spend(fee=FEE, delta=0, extra_inputs=(), outputs=None, signers=None, **kw):
        """`w`'s input back to `w`, less the fee, plus `delta` (refunds, withdrawals less deposits)."""
        outs = outputs if outputs is not None else [eratx.tx_out("conway", here, w["lovelace"] - fee + delta)]
        return dict(inputs=[txin(w), *extra_inputs], outputs=outs, fee=fee, signers=signers or [k1], **kw)

    # ---- UTXO: several failures of one rule
    add("utxo-fee-interval-balance", ["FeeTooSmallUTxO", "OutsideValidityIntervalUTxO", "ValueNotConservedUTxO"],
        **spend(fee=1, delta=-5, ttl=1))
    add("utxo-small-and-wrong-network-outputs", ["BabbageOutputTooSmallUTxO", "WrongNetwork"],
        **spend(outputs=[eratx.tx_out("conway", here, 1),
                         eratx.tx_out("conway", eratx.enterprise_address(k1.hash, network=1), 5_000_000),
                         eratx.tx_out("conway", here, w["lovelace"] - FEE - 5_000_001)]))
    # ---- UTXO and UTXOW: an input the UTxO does not hold and a key that did not sign
    add("utxo-bad-input-missing-witness", ["BadInputsUTxO", "MissingVKeyWitnessesUTXOW"],
        **spend(extra_inputs=[unknown_input], signers=[k2]))
    # ---- LEDGER before or after UTXOW: the treasury value and a fee too small
    add("ledger-treasury-and-fee", ["ConwayTreasuryValueMismatch", "FeeTooSmallUTxO"],
        **spend(fee=1, treasury=1))
    # ---- CERTS, GOV and UTXOW in one transaction: the outer order
    add("certs-gov-utxow", ["DelegateeStakePoolNotRegisteredDELEG", "GovActionsDoNotExist",
                            "MissingVKeyWitnessesUTXOW"],
        **spend(certs=[eratx.delegation_cert(kd1.hash, unknown_pool)],
                votes=eratx.voting_procedures([(eratx.voter("drep", kdrep.hash), [(fake_action, "yes")])]),
                signers=[k1, kdrep]))
    # ---- two failing certificates: does CERTS go on after the first
    add("certs-two-failing", ["StakeKeyRegisteredDELEG", "ConwayDRepNotRegistered"],
        **spend(delta=-key_deposit + 1_000_000,
                certs=[eratx.reg_cert(kd1.hash, key_deposit), eratx.drep_unreg_cert(fresh_drep.hash, 1_000_000)],
                signers=[k1, kd1, fresh_drep]))
    add("certs-three-failing", ["StakeKeyRegisteredDELEG", "ConwayDRepNotRegistered",
                                "DelegateeStakePoolNotRegisteredDELEG"],
        **spend(delta=-key_deposit + 1_000_000,
                certs=[eratx.reg_cert(kd1.hash, key_deposit),
                       eratx.drep_unreg_cert(fresh_drep.hash, 1_000_000),
                       eratx.delegation_cert(kd1.hash, unknown_pool)],
                signers=[k1, kd1, fresh_drep]))
    # ---- DELEG: an unregistered credential delegating to an unknown pool
    add("deleg-unregistered-to-unknown-pool", ["StakeKeyNotRegisteredDELEG", "DelegateeStakePoolNotRegisteredDELEG"],
        **spend(certs=[eratx.delegation_cert(fresh_stake.hash, unknown_pool)], signers=[k1, fresh_stake]))
    # ---- GOVCERT
    add("govcert-drep-registered-wrong-deposit", ["ConwayDRepAlreadyRegistered", "ConwayDRepIncorrectDeposit"],
        **spend(delta=-5, certs=[eratx.drep_reg_cert(kdrep.hash, 5)], signers=[k1, kdrep]))
    add("govcert-committee-unknown", ["ConwayCommitteeIsUnknown"],
        **spend(certs=[eratx.auth_committee_cert(fresh_cold.hash, fresh_hot.hash)], signers=[k1, fresh_cold]))
    # ---- POOL
    add("pool-retire-unregistered", ["StakePoolNotRegisteredOnKeyPOOL"],
        **spend(certs=[eratx.pool_retirement_cert(fresh_cold.hash, epoch + 1)], signers=[k1, fresh_cold]))
    # ---- withdrawals (LEDGER or CERTS, by protocol version)
    add("withdrawal-more-than-balance", ["Withdrawals"],
        **spend(delta=1, withdrawals={d1_account: 1}, signers=[k1, kd1]))
    add("withdrawal-unregistered-account", ["Withdrawals"],
        **spend(withdrawals={eratx.reward_address(fresh_stake.hash): 0}, signers=[k1, fresh_stake]))
    # ---- GOV
    add("gov-proposal-wrong-deposit", ["ProposalDepositIncorrect"],
        **spend(delta=-5, proposals=[eratx.proposal(5, d1_account, eratx.info_action())]))
    add("gov-unknown-voter-unknown-action", ["VotersDoNotExist"],
        **spend(votes=eratx.voting_procedures([(eratx.voter("drep", fresh_drep.hash), [(fake_action, "no")])]),
                signers=[k1, fresh_drep]))
    add("gov-proposal-return-account-network", ["ProposalProcedureNetworkIdMismatch"],
        **spend(delta=-gov_deposit,
                proposals=[eratx.proposal(gov_deposit, eratx.reward_address(kd1.hash, network=1),
                                          eratx.info_action())]))
    add("gov-treasury-withdrawal-zero", ["ZeroTreasuryWithdrawals"],
        **spend(delta=-gov_deposit,
                proposals=[eratx.proposal(gov_deposit, d1_account,
                                          eratx.treasury_withdrawal_action({d1_account: 0}))]))

    # ---- UTXOS: the validity flag against what the script does
    cases.append(Case("utxos-script-fails-flag-true", ntccases.script_tx(b, w, other, FAILS, withdraw=False)[0],
                      ("ValidationTagMismatch",)))
    cases.append(Case("utxos-script-passes-flag-false",
                      ntccases.script_tx(b, w, other, SUCCEEDS, invalid=True, withdraw=False)[0],
                      ("ValidationTagMismatch",)))

    # ---- Plutus scripts: minting without collateral, and with more execution units than allowed
    ok = eratx.plutus_always_succeeds()
    policy = eratx.plutus_script_hash(ok, 3)
    token = {policy: {b"token": 1}}

    def minting(units, collateral, fee=PLUTUS_FEE):
        redeemers = [(1, 0, 0, units)]
        integrity = eratx.script_data_hash(eratx.enc(eratx.redeemers_cbor("conway", redeemers)), b"", {3: v3})
        outs = [eratx.tx_out("conway", here, 3_000_000, token),
                eratx.tx_out("conway", here, w["lovelace"] - fee - 3_000_000)]
        return spend(fee=fee, outputs=outs, mint=token, plutus={3: [ok]}, redeemers=redeemers,
                     script_data=integrity, signers=[k1, k2] if collateral else [k1],
                     **({"collateral": [txin(other)]} if collateral else {}))

    add("utxo-mint-no-collateral", ["NoCollateralInputs"], **minting(UNITS, collateral=False))
    add("utxo-mint-units-too-big", ["ExUnitsTooBigUTxO"],
        **minting((params["maxTxExecutionUnits"]["memory"] + 1, params["maxTxExecutionUnits"]["steps"] + 1),
                  collateral=True))

    # ---- UTXOW: two malformed Plutus scripts, one a witness and one in an output
    malformed = eratx.enc(bytes.fromhex("00"))
    add("utxow-malformed-witness-and-reference-script", ["MalformedScriptWitnesses", "MalformedReferenceScripts"],
        **spend(outputs=[eratx.tx_out("conway", here, 20_000_000, script_ref_plutus=(3, malformed)),
                         eratx.tx_out("conway", here, w["lovelace"] - FEE - 20_000_000)],
                plutus={3: [malformed]}))

    # ---- UTXOW: a script output spent without its datum, its redeemer or its script (#334)
    script_address = eratx.enterprise_address(policy, script=True)
    datum_hash = eratx.blake2b(eratx.enc(DATUM))
    setup = setup_outputs(b, funder, k3, [
        (eratx.tx_out("conway", script_address, FUND, datum_hash=datum_hash), FUND)])
    locked = (setup, 0)

    def spend_locked(**kw):
        outs = [eratx.tx_out("conway", here, w["lovelace"] + FUND - PLUTUS_FEE)]
        return spend(fee=PLUTUS_FEE, extra_inputs=[locked], outputs=outs, collateral=[txin(other)],
                     signers=[k1, k2], **kw)

    sorted_inputs = sorted([txin(w), locked])
    spend_redeemer = [(0, sorted_inputs.index(locked), DATUM, UNITS)]
    add("utxow-spend-without-datum-or-redeemer", ["MissingRequiredDatums", "MissingRedeemers"],
        **spend_locked(plutus={3: [ok]}))
    add("utxow-spend-without-script-with-extra-datum", ["MissingScriptWitnesses"],
        **spend_locked(datums=[DATUM, 43], redeemers=spend_redeemer,
                       script_data=eratx.script_data_hash(
                           eratx.enc(eratx.redeemers_cbor("conway", spend_redeemer)), eratx.enc([DATUM, 43]),
                           {3: v3})))
    add("utxow-spend-redeemer-without-datum-wrong-hash", ["MissingRequiredDatums", "PPViewHashesDontMatch"],
        **spend_locked(plutus={3: [ok]}, redeemers=spend_redeemer, script_data=bytes(32)))
    return cases


# ---------------------------------------------------------------- Babbage

# Plutus V2 scripts in the language version Babbage reads (1.0.0): `error`, and the script of `eraplutus`
# (two lambdas) that succeeds for any arguments
FAILS_V2 = eratx.enc(bytes.fromhex("0100002601"))


class EraWallets:
    """The delegators' base addresses of the era-walking cluster, which hold its funds."""

    def __init__(self, name):
        self.name = name

    def sh(self, script):
        from .util import in_ref
        return in_ref(self.name, script).stdout.strip()

    def wallet(self, number):
        from .util import SOCKET
        d = f"{ENV}/stake-delegators/delegator{number}"
        pay, stake = read_key(self, f"{d}/payment.skey"), read_key(self, f"{d}/staking.skey")
        address = eratx.base_address(pay.hash, stake.hash)
        utxo = json.loads(self.sh(f"cardano-cli query utxo --address {self.sh_address(address)} {SOCKET} "
                                  f"--output-json"))
        plain = {k: v for k, v in utxo.items() if set(v["value"]) == {"lovelace"} and not v.get("datumhash")
                 and not v.get("inlineDatum") and not v.get("referenceScript")}
        txin = max(plain, key=lambda k: plain[k]["value"]["lovelace"])
        return {"txin": txin, "lovelace": plain[txin]["value"]["lovelace"], "key": pay, "stake": stake,
                "address": address}

    @staticmethod
    def sh_address(address):
        return bech32("addr_test", address)


def bech32(hrp, data):
    """BIP-173 bech32 of `data` (the address form `cardano-cli` reads)."""
    charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    def polymod(values):
        gen = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
        chk = 1
        for v in values:
            top = chk >> 25
            chk = (chk & 0x1ffffff) << 5 ^ v
            for i in range(5):
                chk ^= gen[i] if ((top >> i) & 1) else 0
        return chk

    acc, bits, words = 0, 0, []
    for byte in data:
        acc = (acc << 8) | byte
        bits += 8
        while bits >= 5:
            bits -= 5
            words.append((acc >> bits) & 31)
    if bits:
        words.append((acc << (5 - bits)) & 31)
    expanded = [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp]
    check = polymod(expanded + words + [0] * 6) ^ 1
    words += [(check >> 5 * (5 - i)) & 31 for i in range(6)]
    return hrp + "1" + "".join(charset[w] for w in words)


def babbage_corpus(name):
    """The Babbage cases, on the era-walking cluster stopped in Babbage (`up-eras --final-era babbage`)."""
    from .eraplutus import OK
    from .util import SOCKET
    b = EraWallets(name)
    funder, wd, other = b.wallet(1), b.wallet(2), b.wallet(3)
    k, k_other, kd = wd["key"], other["key"], wd["stake"]
    fresh_stake, fresh_cold = seeded_key("babbage stake"), seeded_key("babbage cold")
    params = json.loads(b.sh(f"cardano-cli query protocol-parameters {SOCKET}"))
    v2 = params["costModels"]["PlutusV2"]
    key_deposit = params["stakeAddressDeposit"]
    epoch = json.loads(b.sh(f"cardano-cli query tip {SOCKET}"))["epoch"]
    pool1 = read_key(b, f"{ENV}/pools-keys/pool1/cold.skey")
    here = wd["address"]
    unknown_pool = eratx.blake2b(b"scarab refusal corpus unknown pool", 28)
    unknown_input = ("00" * 31 + "01", 0)
    cases = []

    def add(label, expect, **kw):
        tx, _ = eratx.build_tx("babbage", **kw)
        cases.append(Case(label, tx, tuple(expect)))

    def spend(fee=FEE, delta=0, extra_inputs=(), outputs=None, signers=None, **kw):
        outs = outputs if outputs is not None else [eratx.tx_out("babbage", here, wd["lovelace"] - fee + delta)]
        return dict(inputs=[txin(wd), *extra_inputs], outputs=outs, fee=fee, signers=signers or [k], **kw)

    # ---- UTXO and UTXOW, alone and together
    add("fee-too-small", ["FeeTooSmallUTxO"], **spend(fee=1))
    add("value-not-conserved", ["ValueNotConservedUTxO"], **spend(delta=-7))
    add("expired", ["OutsideValidityIntervalUTxO"], **spend(ttl=1))
    add("output-too-small", ["BabbageOutputTooSmallUTxO"],
        **spend(outputs=[eratx.tx_out("babbage", here, 1), eratx.tx_out("babbage", here, wd["lovelace"] - FEE - 1)]))
    add("missing-witness", ["MissingVKeyWitnessesUTXOW"], **spend(signers=[k_other]))
    add("utxo-fee-interval-balance", ["FeeTooSmallUTxO", "OutsideValidityIntervalUTxO", "ValueNotConservedUTxO"],
        **spend(fee=1, delta=-5, ttl=1))
    add("utxo-small-and-wrong-network-outputs", ["BabbageOutputTooSmallUTxO", "WrongNetwork"],
        **spend(outputs=[eratx.tx_out("babbage", here, 1),
                         eratx.tx_out("babbage", eratx.enterprise_address(k.hash, network=1), 5_000_000),
                         eratx.tx_out("babbage", here, wd["lovelace"] - FEE - 5_000_001)]))
    add("utxo-bad-input-missing-witness", ["BadInputsUTxO", "MissingVKeyWitnessesUTXOW"],
        **spend(extra_inputs=[unknown_input], signers=[k_other]))
    # ---- DELEGS, DELEG and POOL
    add("deleg-stake-key-registered", ["StakeKeyAlreadyRegisteredDELEG"],
        **spend(delta=-key_deposit, certs=[eratx.stake_key_cert(0, kd.hash)]))
    add("deleg-unregistered-delegation", ["StakeDelegationImpossibleDELEG"],
        **spend(certs=[eratx.delegation_cert(fresh_stake.hash, eratx.blake2b(pool1.vkey, 28))],
                signers=[k, fresh_stake]))
    add("delegs-unknown-pool", ["DelegateeNotRegisteredDELEG"],
        **spend(certs=[eratx.delegation_cert(kd.hash, unknown_pool)], signers=[k, kd]))
    add("certs-two-failing", ["StakeKeyAlreadyRegisteredDELEG", "DelegateeNotRegisteredDELEG"],
        **spend(delta=-key_deposit,
                certs=[eratx.stake_key_cert(0, kd.hash), eratx.delegation_cert(kd.hash, unknown_pool)],
                signers=[k, kd]))
    add("certs-three-failing", ["StakeKeyAlreadyRegisteredDELEG", "DelegateeNotRegisteredDELEG",
                                "StakePoolNotRegisteredOnKeyPOOL"],
        **spend(delta=-key_deposit,
                certs=[eratx.stake_key_cert(0, kd.hash), eratx.delegation_cert(kd.hash, unknown_pool),
                       eratx.pool_retirement_cert(fresh_cold.hash, epoch + 1)],
                signers=[k, kd, fresh_cold]))
    # Deleg.hs:351-361 retains the negative intermediate Coin even after a predicate failure.
    # The middle certificate offsets it; the final negative update must fail again.
    for pot, label in ((0, "reserves"), (1, "treasury")):
        add(f"mir-signed-intermediate-{label}", ["MIRProducesNegativeUpdate"],
            **spend(certs=[[6, [pot, {(0, kd.hash): amount}]] for amount in (-1, 1, -1)]))
    add("withdrawal-more-than-balance", ["Withdrawals"],
        **spend(delta=1, withdrawals={eratx.reward_address(kd.hash): 1}, signers=[k, kd]))
    add("withdrawal-unregistered-account", ["Withdrawals"],
        **spend(withdrawals={eratx.reward_address(fresh_stake.hash): 0}, signers=[k, fresh_stake]))
    add("pool-retire-unregistered", ["StakePoolNotRegisteredOnKeyPOOL"],
        **spend(certs=[eratx.pool_retirement_cert(fresh_cold.hash, epoch + 1)], signers=[k, fresh_cold]))
    add("pool-retirement-wrong-epoch", ["StakePoolRetirementWrongEpochPOOL"],
        **spend(certs=[eratx.pool_retirement_cert(eratx.blake2b(pool1.vkey, 28), epoch)], signers=[k, pool1]))

    # ---- Plutus V2 minting: the validity flag, collateral
    policy_ok = eratx.plutus_script_hash(OK, 2)
    policy_fails = eratx.plutus_script_hash(FAILS_V2, 2)

    def minting(script, policy, collateral=True, is_valid=True, total_collateral=None, units=UNITS):
        token = {policy: {b"token": 1}}
        redeemers = [(1, 0, 0, units)]
        integrity = eratx.script_data_hash(eratx.enc(eratx.redeemers_cbor("babbage", redeemers)), b"", {2: v2})
        outs = [eratx.tx_out("babbage", here, 3_000_000, token),
                eratx.tx_out("babbage", here, wd["lovelace"] - PLUTUS_FEE - 3_000_000)]
        extra = {}
        if collateral:
            extra["collateral"] = [txin(other)]
        if total_collateral is not None:
            extra["total_collateral"] = total_collateral
            # the return leaves the collateral input's whole value less 3 ADA, not `total_collateral`
            extra["collateral_return"] = eratx.tx_out("babbage", other["address"], other["lovelace"] - 3_000_000)
        return spend(fee=PLUTUS_FEE, outputs=outs, mint=token, plutus={2: [script]}, redeemers=redeemers,
                     script_data=integrity, signers=[k, k_other] if collateral else [k], is_valid=is_valid,
                     **extra)

    add("utxos-script-fails-flag-true", ["ValidationTagMismatch"], **minting(FAILS_V2, policy_fails))
    add("utxos-script-passes-flag-false", ["ValidationTagMismatch"], **minting(OK, policy_ok, is_valid=False))
    add("utxo-mint-no-collateral", ["NoCollateralInputs"], **minting(OK, policy_ok, collateral=False))
    add("utxo-total-collateral-wrong", ["IncorrectTotalCollateralField"],
        **minting(OK, policy_ok, total_collateral=2_250_000))

    # ---- UTXOW: malformed scripts, and a script output spent without its datum, redeemer or script
    malformed = eratx.enc(bytes.fromhex("00"))
    add("utxow-malformed-witness-and-reference-script", ["MalformedScriptWitnesses", "MalformedReferenceScripts"],
        **spend(outputs=[eratx.tx_out("babbage", here, 20_000_000, script_ref_plutus=(2, malformed)),
                         eratx.tx_out("babbage", here, wd["lovelace"] - FEE - 20_000_000)],
                plutus={2: [malformed]}))
    script_address = eratx.enterprise_address(policy_ok, script=True)
    datum_hash = eratx.blake2b(eratx.enc(DATUM))
    setup = submit_setup(b, funder, "babbage", [
        (eratx.tx_out("babbage", script_address, FUND, datum_hash=datum_hash), FUND)])
    locked = (setup, 0)

    def spend_locked(**kw):
        outs = [eratx.tx_out("babbage", here, wd["lovelace"] + FUND - PLUTUS_FEE)]
        return spend(fee=PLUTUS_FEE, extra_inputs=[locked], outputs=outs, collateral=[txin(other)],
                     signers=[k, k_other], **kw)

    sorted_inputs = sorted([txin(wd), locked])
    spend_redeemer = [(0, sorted_inputs.index(locked), DATUM, UNITS)]
    add("utxow-spend-without-datum-or-redeemer", ["MissingRequiredDatums", "MissingRedeemers"],
        **spend_locked(plutus={2: [OK]}))
    add("utxow-spend-without-script-with-extra-datum", ["MissingScriptWitnesses"],
        **spend_locked(datums=[DATUM, 43], redeemers=spend_redeemer,
                       script_data=eratx.script_data_hash(
                           eratx.enc(eratx.redeemers_cbor("babbage", spend_redeemer)), eratx.enc([DATUM, 43]),
                           {2: v2})))
    add("utxow-spend-redeemer-without-datum-wrong-hash", ["MissingRequiredDatums", "PPViewHashesDontMatch"],
        **spend_locked(plutus={2: [OK]}, redeemers=spend_redeemer, script_data=bytes(32)))
    return cases


def conway_certificate_order(name):
    """Capture the three-certificate production judgment independently of earlier corpora."""
    return [case for case in corpus(name, f"{ENV}/socket/node1/sock")
            if case.label == "certs-three-failing"]


def babbage_certificate_order(name):
    """Capture DELEGS nesting and signed MIR state across three production certificates."""
    return [case for case in babbage_corpus(name)
            if case.label == "certs-three-failing" or case.label.startswith("mir-signed-intermediate-")]


def submit_setup(b, funder, era, outputs):
    """An ordinary transaction of `era` creating `outputs` from `funder` (a wallet of `EraWallets`), waited
    for until the reference holds them; its id."""
    from .util import SOCKET
    total = sum(coin for _, coin in outputs)
    outs = [out for out, _ in outputs] + [eratx.tx_out(era, funder["address"], funder["lovelace"] - total - FEE)]
    tx, body_hash = eratx.build_tx(era, [txin(funder)], outs, FEE, [funder["key"]])
    b.sh(f"echo '{eratx.envelope(era, tx)}' > /work/setup.tx")
    b.sh(f"cardano-cli conway transaction submit {SOCKET} --tx-file /work/setup.tx")
    for _ in range(120):
        if json.loads(b.sh(f"cardano-cli query utxo --tx-in {body_hash}#0 {SOCKET} --output-json")):
            return body_hash
        b.sh("sleep 1")
    raise RuntimeError("the setup transaction of the script cases did not reach a block")


def babbage_ppup(name):
    """Add the retired PPUP rule to an existing Babbage corpus without recapturing other cases."""
    return [case for case in preconway_families(name, "babbage") if case.label.startswith("ppup-")]


def preconway_families(name, era):
    """Recorded family and accumulation cases for a cluster stopped in a pre-Conway era."""
    from .util import SOCKET
    b = EraWallets(name)
    wd, other = b.wallet(2), b.wallet(3)
    key, stake = wd["key"], wd["stake"]
    fresh_stake, fresh_cold = seeded_key(f"{era} refusal stake"), seeded_key(f"{era} refusal pool")
    tip = json.loads(b.sh(f"cardano-cli query tip {SOCKET}"))
    params = json.loads(b.sh(f"cardano-cli query protocol-parameters {SOCKET}"))
    unknown_pool = eratx.blake2b(b"refusal corpus unknown pool", 28)
    cases = []

    def spend(fee=FEE, delta=0, signers=None, **kw):
        return dict(inputs=[txin(wd)], outputs=[eratx.tx_out(era, wd["address"], wd["lovelace"] - fee + delta)],
                    fee=fee, signers=signers or [key], ttl=tip["slot"] + 1_000_000, **kw)

    def add(label, expect, **kw):
        tx, _ = eratx.build_tx(era, **kw)
        cases.append(Case(label, tx, tuple(expect)))

    add("fee-too-small", ["FeeTooSmallUTxO"], **spend(fee=1))
    add("missing-witness", ["MissingVKeyWitnessesUTXOW"], **spend(signers=[other["key"]]))
    add("withdrawal-unregistered-account", ["Withdrawals"],
        **spend(withdrawals={eratx.reward_address(fresh_stake.hash): 0}, signers=[key, fresh_stake]))
    add("deleg-stake-key-registered", ["StakeKeyAlreadyRegisteredDELEG"],
        **spend(certs=[eratx.stake_key_cert(0, stake.hash)]))
    add("pool-retire-unregistered", ["StakePoolNotRegisteredOnKeyPOOL"],
        **spend(certs=[eratx.pool_retirement_cert(fresh_cold.hash, tip["epoch"] + 1)],
                signers=[key, fresh_cold]))
    add("certs-three-failing", ["StakeKeyAlreadyRegisteredDELEG", "DelegateeNotRegisteredDELEG",
                                "StakePoolNotRegisteredOnKeyPOOL"],
        **spend(delta=-params["stakeAddressDeposit"],
                certs=[eratx.stake_key_cert(0, stake.hash), eratx.delegation_cert(stake.hash, unknown_pool),
                       eratx.pool_retirement_cert(fresh_cold.hash, tip["epoch"] + 1)],
                signers=[key, stake, fresh_cold]))
    add("ppup-non-genesis-proposer", ["NonGenesisUpdatePPUP"],
        **spend(update=[{fresh_stake.hash: {0: 45}}, tip["epoch"]]))
    if era in ("alonzo", "babbage"):
        add("utxos-empty-script-set-invalid", ["ValidationTagMismatch"], **spend(is_valid=False))
    return cases
