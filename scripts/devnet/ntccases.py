# SPDX-License-Identifier: Apache-2.0
"""Transactions the ledger refuses, built with `cardano-cli` inside the reference container, for the
`ntc` scenario: each is submitted to both nodes over their node-to-client sockets and the rejection
(the raw `ApplyTxErr` bytes and the text `cardano-cli transaction submit` prints) must be equal.

Every case but one violates exactly one rule. The ledger reports all the failures it finds, in the order
CERTS, GOV, UTXOW; a case with one violation has one failure, which is what the Scala rules report. The
exception is `withdrawal-network-flag-true`: its withdrawal names an account of the other network, which LEDGER
and UTXO both refuse, and the Scala rules stop at the first failure. Its `Divergence` records that comparison,
the failure counts it expects and the issue whose resolution removes it.
"""
import json
from dataclasses import dataclass
from typing import Optional

from . import eratx
from .eraplutus import DATUM
from .util import ENV, MAGIC, in_ref

CLI = "cardano-cli conway"
ANCHOR = ("--anchor-url http://example.com/a --anchor-data-hash "
          "0000000000000000000000000000000000000000000000000000000000000000")


@dataclass(frozen=True)
class Divergence:
    """A refusal compared by its first failure only, because the Scala rules report one failure where the
    reference accumulates `reference_failures` of them (#412, `Rules` accumulation). The comparison requires
    the first failures to be byte-equal and both counts to be exactly these; once the whole refusals are equal
    the case fails, so that the exemption is removed together with the divergence."""
    issue: int
    reference_failures: int
    scala_failures: int


@dataclass
class Case:
    """`ledger_rule` is the constructor the reference reports, matched as a whole identifier in its text.
    `divergence` is None for a case compared exactly (raw bytes and text)."""
    label: str
    tx: bytes
    ledger_rule: str
    divergence: Optional[Divergence] = None


ACCUMULATED_WITHDRAWAL_FAILURES = Divergence(issue=412, reference_failures=2, scala_failures=1)


class Builder:
    def __init__(self, name, socket):
        self.name, self.socket = name, socket
        self.n = 0

    def sh(self, script):
        return in_ref(self.name, script).stdout.strip()

    def wallet(self, number):
        d = f"{ENV}/utxo-keys/utxo{number}"
        addr = self.sh(f"{CLI} address build --payment-verification-key-file {d}/utxo.vkey --testnet-magic {MAGIC}")
        utxo = json.loads(self.sh(f"{CLI} query utxo --address {addr} --testnet-magic {MAGIC} "
                                  f"--socket-path {self.socket} --output-json"))
        txin = max(utxo, key=lambda k: utxo[k]["value"]["lovelace"])
        return {"addr": addr, "skey": f"{d}/utxo.skey", "txin": txin, "lovelace": utxo[txin]["value"]["lovelace"],
                "vkey": f"{d}/utxo.vkey"}

    def cert(self, args):
        self.n += 1
        self.sh(f"{CLI} {args} --out-file /work/c{self.n}.cert")
        return f"--certificate-file /work/c{self.n}.cert"

    def tx(self, w, deposit=0, fee=200000, extra="", sign=None, outs=None, ins=None):
        """A transaction paying everything but `fee` and `deposit` back to the wallet."""
        self.n += 1
        ins = ins if ins is not None else [w["txin"]]
        outs = outs if outs is not None else [(w["addr"], w["lovelace"] - fee - deposit)]
        in_args = " ".join(f"--tx-in {i}" for i in ins)
        out_args = " ".join(f"--tx-out {a}+{v}" for a, v in outs)
        keys = " ".join(f"--signing-key-file {k}" for k in (sign or [w["skey"]]))
        base = f"/work/case{self.n}"
        self.sh(f"{CLI} transaction build-raw {in_args} {out_args} --fee {fee} {extra} --out-file {base}.body")
        self.sh(f"{CLI} transaction sign --tx-body-file {base}.body {keys} --out-file {base}.signed")
        return bytes.fromhex(json.loads(self.sh(f"cat {base}.signed"))["cborHex"])


# Plutus V3 policies as script files: `\ctx -> ()` and `\ctx -> error` (flat, wrapped twice)
SUCCEEDS = {"type": "PlutusScriptV3", "description": "", "cborHex": eratx.enc(eratx.plutus_always_succeeds()).hex()}
FAILS = {"type": "PlutusScriptV3", "description": "",
         "cborHex": eratx.enc(eratx.enc(bytes.fromhex("0101002601"))).hex()}


def script_tx(b, w, collateral, script, account="testnet", invalid=False, fee=1500000, skew_cost_model=False,
               withdraw=True):
    """A transaction that mints one token under the Plutus policy `script`, with a withdrawal of nothing
    from the reward account of the delegator `delegator1` on the network `account` ("testnet" or
    "mainnet"); `collateral` (a wallet other than `w`) pays the collateral. `invalid` sets the validity
    flag of the transaction. `skew_cost_model` builds the transaction against parameters whose Plutus V3 cost
    model differs by one in its first value, so that its script data hash is not the ledger's. Returns the
    signed bytes and the collateral input. `withdraw` False leaves out the withdrawal, so that the transaction
    breaks no rule but the one `skew_cost_model` provokes."""
    b.n += 1
    n = b.n
    d1 = f"{ENV}/stake-delegators/delegator1"
    network = f"--testnet-magic {MAGIC}" if account == "testnet" else "--mainnet"
    stake = b.sh(f"{CLI} stake-address build --stake-verification-key-file {d1}/staking.vkey {network}")
    b.sh(f"echo '{json.dumps(script)}' > /work/script{n}.plutus")
    policy = b.sh(f"{CLI} transaction policyid --script-file /work/script{n}.plutus")
    token = f"1 {policy}.746f6b656e"
    b.sh(f"{CLI} query protocol-parameters --testnet-magic {MAGIC} --socket-path {b.socket} "
         f"--out-file /work/pp{n}.json")
    if skew_cost_model:
        params = json.loads(b.sh(f"cat /work/pp{n}.json"))
        params["costModels"]["PlutusV3"][0] += 1
        b.sh(f"echo '{json.dumps(params)}' > /work/pp{n}.json")
    held, kept = 3000000, collateral["lovelace"] - 3000000
    base = f"/work/case{n}"
    # cardano-cli expects mint execution units in (steps, memory) order.
    b.sh(f"{CLI} transaction build-raw --tx-in {w['txin']} "
         f"--tx-out '{w['addr']}+{held}+{token}' --tx-out {w['addr']}+{w['lovelace'] - fee - held} "
         f"--fee {fee} --mint '{token}' --mint-script-file /work/script{n}.plutus --mint-redeemer-value 0 "
         f"--mint-execution-units '(1000000000,2000000)' --tx-in-collateral {collateral['txin']} "
         f"--tx-total-collateral {held} --tx-out-return-collateral {collateral['addr']}+{kept} "
         f"{f'--withdrawal {stake}+0 ' if withdraw else ''}--protocol-params-file /work/pp{n}.json "
         f"{'--script-invalid' if invalid else ''} --out-file {base}.body")
    signers = (w["skey"], collateral["skey"], f"{d1}/staking.skey") if withdraw else (w["skey"], collateral["skey"])
    keys = " ".join(f"--signing-key-file {k}" for k in signers)
    b.sh(f"{CLI} transaction sign --tx-body-file {base}.body {keys} --out-file {base}.signed")
    return bytes.fromhex(json.loads(b.sh(f"cat {base}.signed"))["cborHex"]), collateral["txin"]


def corpus(name, socket):
    """The refusal cases, in a fixed order."""
    b = Builder(name, socket)
    w = b.wallet(1)
    other = b.wallet(2)
    d1 = f"{ENV}/stake-delegators/delegator1"
    pool1 = f"{ENV}/pools-keys/pool1"
    drep1 = f"{ENV}/drep-keys/drep1"
    b.sh(f"{CLI} stake-address key-gen --verification-key-file /work/newstake.vkey --signing-key-file /work/newstake.skey")
    b.sh(f"{CLI} governance drep key-gen --verification-key-file /work/newdrep.vkey --signing-key-file /work/newdrep.skey")
    b.sh(f"{CLI} node key-gen --cold-verification-key-file /work/coldx.vkey --cold-signing-key-file /work/coldx.skey "
         f"--operational-certificate-issue-counter-file /work/coldx.counter")
    main_addr = b.sh(f"{CLI} address build --payment-verification-key-file {w['vkey']} --mainnet")
    cases = []

    def add(label, rule, tx, divergence=None):
        cases.append(Case(label, tx, rule, divergence))

    add("fee-too-small", "FeeTooSmallUTxO", b.tx(w, fee=1))
    add("all-inputs-spent", "ConwayMempoolFailure",
        b.tx(w, ins=["0000000000000000000000000000000000000000000000000000000000000000#0"]))
    add("value-not-conserved", "ValueNotConservedUTxO", b.tx(w, outs=[(w["addr"], w["lovelace"] - 300000)]))
    add("expired", "OutsideValidityIntervalUTxO", b.tx(w, extra="--invalid-hereafter 1"))
    add("not-yet-valid", "OutsideValidityIntervalUTxO", b.tx(w, extra="--invalid-before 9999999999"))
    add("missing-witness", "MissingVKeyWitnessesUTXOW", b.tx(w, sign=[other["skey"]]))
    add("output-too-small", "BabbageOutputTooSmallUTxO",
        b.tx(w, outs=[(w["addr"], 1), (w["addr"], w["lovelace"] - 200001)]))
    add("wrong-network-output", "WrongNetwork",
        b.tx(w, outs=[(main_addr, 5000000), (w["addr"], w["lovelace"] - 200000 - 5000000)]))
    add("treasury-mismatch", "ConwayTreasuryValueMismatch", b.tx(w, extra="--current-treasury-value 1"))
    add("stake-key-registered", "StakeKeyRegisteredDELEG",
        b.tx(w, deposit=400000, sign=[w["skey"], f"{d1}/staking.skey"],
             extra=b.cert(f"stake-address registration-certificate --stake-verification-key-file {d1}/staking.vkey "
                          f"--key-reg-deposit-amt 400000")))
    add("stake-key-not-registered", "StakeKeyNotRegisteredDELEG",
        b.tx(w, sign=[w["skey"], "/work/newstake.skey"],
             extra=b.cert(f"stake-address stake-delegation-certificate --stake-verification-key-file "
                          f"/work/newstake.vkey --cold-verification-key-file {pool1}/cold.vkey")))
    add("delegate-to-unknown-pool", "DelegateeStakePoolNotRegisteredDELEG",
        b.tx(w, sign=[w["skey"], f"{d1}/staking.skey"],
             extra=b.cert(f"stake-address stake-delegation-certificate --stake-verification-key-file "
                          f"{d1}/staking.vkey --cold-verification-key-file /work/coldx.vkey")))
    add("drep-not-registered", "ConwayDRepNotRegistered",
        b.tx(w, deposit=-1000000, sign=[w["skey"], "/work/newdrep.skey"],
             extra=b.cert("governance drep retirement-certificate --drep-verification-key-file "
                          "/work/newdrep.vkey --deposit-amt 1000000")))
    add("drep-update-not-registered", "ConwayDRepNotRegistered",
        b.tx(w, sign=[w["skey"], "/work/newdrep.skey"],
             extra=b.cert("governance drep update-certificate --drep-verification-key-file /work/newdrep.vkey")))
    add("pool-retirement-wrong-epoch", "StakePoolRetirementWrongEpochPOOL",
        b.tx(w, sign=[w["skey"], f"{pool1}/cold.skey"],
             extra=b.cert(f"stake-pool deregistration-certificate --cold-verification-key-file {pool1}/cold.vkey "
                          f"--epoch 1")))
    b.sh(f"{CLI} governance action create-info --testnet --governance-action-deposit 1000000 "
         f"--deposit-return-stake-verification-key-file /work/newstake.vkey {ANCHOR} --out-file /work/p2.action")
    add("proposal-return-account-unknown", "ProposalReturnAccountDoesNotExist",
        b.tx(w, deposit=1000000, extra="--proposal-file /work/p2.action"))
    fake = "1111111111111111111111111111111111111111111111111111111111111111"
    b.sh(f"{CLI} governance vote create --yes --governance-action-tx-id {fake} --governance-action-index 0 "
         f"--drep-verification-key-file {drep1}/drep.vkey --out-file /work/v1.vote")
    add("vote-on-unknown-action", "GovActionsDoNotExist",
        b.tx(w, extra="--vote-file /work/v1.vote", sign=[w["skey"], f"{drep1}/drep.skey"]))
    # a transaction larger than maxTxSize (16384): padded with metadata
    words = ", ".join(f'"{"x" * 60}"' for _ in range(300))
    b.sh(f"echo '{{\"1\": [{words}]}}' > /work/big.json")
    add("tx-too-large", "MaxTxSizeUTxO", b.tx(w, extra="--metadata-json-file /work/big.json"))
    # the measure comes before the ledger rules (`Mempool/Update.hs`): the oversize transaction that also
    # spends an input that does not exist is refused for its size alone, not with "All inputs are spent"
    add("tx-too-large-spent-inputs", "MaxTxSizeUTxO",
        b.tx(w, ins=["0000000000000000000000000000000000000000000000000000000000000000#0"],
             extra="--metadata-json-file /work/big.json"))
    # the network of a withdrawal, under each value of the validity flag
    # Alonzo/Rules/Utxow.hs `checkScriptIntegrityHash`: PPViewHashesDontMatch below protocol version 11,
    # ScriptIntegrityHashMismatch (UTXOW tag 18, with the expected preimage) from 11
    major = json.loads(b.sh(f"{CLI} query protocol-parameters --testnet-magic {MAGIC} "
                            f"--socket-path {socket}"))["protocolVersion"]["major"]
    add("script-integrity-hash-mismatch",
        "ScriptIntegrityHashMismatch" if major >= 11 else "PPViewHashesDontMatch",
        script_tx(b, w, other, SUCCEEDS, skew_cost_model=True, withdraw=False)[0])
    add("withdrawal-network-flag-false", "WrongNetworkWithdrawal",
        script_tx(b, w, other, FAILS, account="mainnet", invalid=True)[0])
    # from protocol version 11 LEDGER refuses a withdrawal from an account it does not hold
    # (`ConwayWithdrawalsMissingAccounts`, Conway/Rules/Ledger.hs); below 11 CERTS does
    # (`WithdrawalsNotInRewardsCERTS`). UTXO adds `WrongNetworkWithdrawal`, which the Scala rules do not
    # reach because they stop at the first failure (#412)
    add("withdrawal-network-flag-true",
        "ConwayWithdrawalsMissingAccounts" if major >= 11 else "WithdrawalsNotInRewardsCERTS",
        script_tx(b, w, other, SUCCEEDS, account="mainnet")[0], divergence=ACCUMULATED_WITHDRAWAL_FAILURES)
    # a datum in the witnesses that no spent Plutus input requires and no output carries (#330): a key-only
    # spend with the right script data hash, so that `NotAllowedSupplementalDatums` is its only failure
    key = eratx.Key(b.sh(f"cat {w['skey']}"))
    fee = 300000
    datum_tx, _ = eratx.build_tx(
        "conway", [(w["txin"].split("#")[0], int(w["txin"].split("#")[1]))],
        [eratx.tx_out("conway", eratx.enterprise_address(key.hash), w["lovelace"] - fee)], fee, [key],
        datums=[DATUM],
        script_data=eratx.script_data_hash(eratx.enc(eratx.redeemers_cbor("conway", [])), eratx.enc([DATUM]), {}))
    add("supplemental-datum", "NotAllowedSupplementalDatums", datum_tx)
    return cases
