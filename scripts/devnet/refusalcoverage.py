# SPDX-License-Identifier: Apache-2.0
"""Refusal cases for predicate failures that no earlier corpus triggers against the reference: Conway DELEG and
GOVCERT at protocol versions 10 and 11, the collateral rules of Alonzo, Babbage and Conway, and Conway GOV's proposal
and committee-vote checks.

Each case breaks exactly one check, so the reference's refusal holds one predicate failure. `TAGS` names it by its
constructor tags (era index, then the tag at each level, outermost first), and
`scripts/tests/test_refusalcoverage.py` requires every recorded refusal of these corpora to be exactly that one
failure. Citations are to cardano-ledger f649f975 (`eras/<era>/impl/src/Cardano/Ledger/<Era>/...`). The commands
that record the corpora are in `fixtures/reference/refusals/README.md`.
"""
import json

from . import eratx
from .eraplutus import OK
from .ntccases import CLI, Builder
from .refusalcases import FEE, PLUTUS_FEE, UNITS, Case, EraWallets, bech32, read_key, seeded_key, txin
from .util import ENV, MAGIC

# Conway's ledger failure (`Conway/Rules/Ledger.hs`): 1 UTXOW (its 0 UTXO), 2 CERTS (its 1 CERT: 1 DELEG,
# 3 GOVCERT), 3 GOV. Babbage's and Alonzo's (`Shelley/Rules/Ledger.hs`): 0 UTXOW; Babbage's UTXOW has UTXO at 2,
# whose 1 is Alonzo's UTXO (`Babbage/Rules/Utxo.hs:553`); Alonzo's UTXOW has Shelley's at 0, whose 4 is UTXO.
CONWAY, BABBAGE, ALONZO = 6, 5, 4
DELEG, GOVCERT, GOV, CONWAY_UTXO = (2, 1, 1), (2, 1, 3), (3,), (1, 0)
PRE_CONWAY_UTXO = {"babbage": (0, 2, 1), "alonzo": (0, 0, 4)}
# Alonzo's UTXO tags (`Alonzo/Rules/Utxo.hs:673-679`), which Babbage wraps unchanged
ALONZO_UTXO = {"ScriptsNotPaidUTxO": 14, "ExUnitsTooBigUTxO": 15, "CollateralContainsNonADA": 16,
               "WrongNetworkInTxBody": 17}

# the label of each case, the constructor it is built to provoke, and its tags
TAGS = {
    # `Conway/Rules/Deleg.hs:128-142`
    "deleg-reg-deposit-wrong": ("IncorrectDepositDELEG", CONWAY, DELEG + (1,)),
    "deleg-unreg-refund-wrong": ("IncorrectDepositDELEG", CONWAY, DELEG + (1,)),
    "deleg-unreg-reward-balance": ("StakeKeyHasNonZeroAccountBalanceDELEG", CONWAY, DELEG + (4,)),
    "deleg-vote-unregistered-drep": ("DelegateeDRepNotRegisteredDELEG", CONWAY, DELEG + (5,)),
    # `Conway/Rules/GovCert.hs:135`
    "govcert-drep-unreg-refund-wrong": ("ConwayDRepIncorrectRefund", CONWAY, GOVCERT + (4,)),
    # `Conway/Rules/Utxo.hs:324-326`
    "utxo-collateral-script-locked": ("ScriptsNotPaidUTxO", CONWAY, CONWAY_UTXO + (13,)),
    "utxo-collateral-non-ada": ("CollateralContainsNonADA", CONWAY, CONWAY_UTXO + (15,)),
    # `Conway/Rules/Gov.hs:260-298`
    "gov-committee-update-conflicting": ("ConflictingCommitteeUpdate", CONWAY, GOV + (6,)),
    "gov-committee-update-expired-member": ("ExpirationEpochTooSmall", CONWAY, GOV + (7,)),
    "gov-treasury-withdrawal-unregistered-account": ("TreasuryWithdrawalReturnAccountsDoNotExist", CONWAY,
                                                     GOV + (17,)),
    "gov-parameter-change-guardrails-unexpected": ("InvalidGuardrailsScriptHash", CONWAY, GOV + (11,)),
    "gov-treasury-withdrawal-guardrails-unexpected": ("InvalidGuardrailsScriptHash", CONWAY, GOV + (11,)),
}
# The parameters `ppuWellFormed` refuses at zero from protocol version 10 (`Conway/PParams.hs:935-953`), by their
# key in `protocol_param_update` (the Conway CDDL). maxTxSize (3) is `conway-governance`'s case; nOpt (8) is refused
# only from version 11 (952-953) and is well-formed here, so it is not a case.
ZERO_FIELDS = {"max-block-body-size": 2, "max-block-header-size": 4, "pool-deposit": 6,
               "coins-per-utxo-byte": 17, "max-value-size": 22, "collateral-percentage": 23,
               "committee-max-term-length": 28, "gov-action-lifetime": 29, "gov-action-deposit": 30,
               "drep-deposit": 31}
for _field in ZERO_FIELDS:
    TAGS[f"gov-parameter-change-{_field}-zero"] = ("MalformedProposal", CONWAY, GOV + (1,))
TAGS["gov-parameter-change-empty"] = ("MalformedProposal", CONWAY, GOV + (1,))
# from protocol version 11 (`Conway/Era.hs:262-267`, `PParams.hs:952-953`)
TAGS.update({
    "deleg-reg-deposit-wrong-v11": ("DepositIncorrectDELEG", CONWAY, DELEG + (7,)),
    "deleg-unreg-refund-wrong-v11": ("RefundIncorrectDELEG", CONWAY, DELEG + (8,)),
    "gov-parameter-change-n-opt-zero-v11": ("MalformedProposal", CONWAY, GOV + (1,)),
    "gov-vote-unelected-committee-member-v11": ("UnelectedCommitteeVoters", CONWAY, GOV + (18,)),
})
for _era, _index in (("alonzo", ALONZO), ("babbage", BABBAGE)):
    for _label, _name in (("utxo-collateral-script-locked", "ScriptsNotPaidUTxO"),
                          ("utxo-collateral-non-ada", "CollateralContainsNonADA"),
                          ("utxo-body-network-id-wrong", "WrongNetworkInTxBody"),
                          ("utxo-mint-units-too-big", "ExUnitsTooBigUTxO")):
        TAGS[f"{_era}/{_label}"] = (_name, _index, PRE_CONWAY_UTXO[_era] + (ALONZO_UTXO[_name],))

COLLATERAL_FUND = 10_000_000
TOKEN = b"coverage"


def tags(era, label):
    """The (constructor, era index, tags) a case of `era` is built to provoke."""
    return TAGS[label] if era == "conway" else TAGS[f"{era}/{label}"]


def submit_and_await(b, era, tx, body_hash, submit):
    """Submit `tx` with the `submit` command of `cardano-cli` and wait until the reference holds its first output."""
    b.sh(f"echo '{eratx.envelope(era, tx)}' > /work/coverage-setup.tx")
    b.sh(f"{submit} --tx-file /work/coverage-setup.tx")
    for _ in range(120):
        if json.loads(b.sh(f"cardano-cli conway query utxo --tx-in {body_hash}#0 --testnet-magic {MAGIC} "
                           f"--socket-path {ENV}/socket/node1/sock --output-json")):
            return body_hash
        b.sh("sleep 1")
    raise RuntimeError("the setup transaction of the coverage cases did not reach a block")


def collateral_setup(b, era, funder_in, funder_lovelace, funder_key, token_address, submit, certs=(), signers=(),
                     deposit=0):
    """One setup transaction for the collateral cases: output 0 holds ada only at the address of a native script
    (a collateral input locked by a script), output 1 holds ada and a token at `token_address` (a key address),
    output 2 the funder's change. `certs` ride along (the DELEG cases' registration). Its id."""
    lock = eratx.sig_script(funder_key.hash)
    policy = eratx.all_script(eratx.sig_script(funder_key.hash))
    policy_id = eratx.script_hash(policy)
    token = {policy_id: {TOKEN: 1}}
    outs = [eratx.tx_out(era, eratx.enterprise_address(eratx.script_hash(lock), script=True), COLLATERAL_FUND),
            eratx.tx_out(era, token_address, COLLATERAL_FUND, token),
            eratx.tx_out(era, eratx.enterprise_address(funder_key.hash),
                         funder_lovelace - 2 * COLLATERAL_FUND - FEE - deposit)]
    tx, body_hash = eratx.build_tx(era, [funder_in], outs, FEE, [funder_key, *signers], mint=token,
                                   native_scripts=[policy], certs=list(certs) or None)
    return submit_and_await(b, era, tx, body_hash, submit)


def plutus_minting(era, spend, here, lovelace, language, models, collateral, signers, fee=PLUTUS_FEE, units=UNITS):
    """A transaction minting one token under the always-succeeding Plutus policy of `language`, spending `spend`'s
    input, with `collateral` as its collateral inputs: the shape of the `NoCollateralInputs` and
    `ExUnitsTooBigUTxO` cases of `refusalcases`, with the collateral the case varies."""
    script = eratx.plutus_always_succeeds() if language == 3 else OK
    policy = eratx.plutus_script_hash(script, language)
    token = {policy: {b"token": 1}}
    redeemers = [(1, 0, 0, units)]
    integrity = eratx.script_data_hash(eratx.enc(eratx.redeemers_cbor(era, redeemers)), b"", {language: models})
    outs = [eratx.tx_out(era, here, 3_000_000, token), eratx.tx_out(era, here, lovelace - fee - 3_000_000)]
    return spend(fee=fee, outputs=outs, mint=token, plutus={language: [script]}, redeemers=redeemers,
                 script_data=integrity, collateral=collateral, signers=signers)


def conway_deleg_collateral(name):
    """Conway DELEG, GOVCERT and collateral refusals at protocol version 10 on the `up` cluster.

    At version 10 a registration whose deposit is not `keyDeposit`, and an unregistration whose refund is not the
    deposit held, both fail `IncorrectDepositDELEG`: `DepositIncorrectDELEG` and `RefundIncorrectDELEG` replace it
    only from version 11 (`hardforkConwayDELEGIncorrectDepositsAndRefunds`, `Conway/Era.hs:266-267`;
    `Rules/Deleg.hs:199-211`, `240-258`). UTXO counts the deposit of a registration from the parameters and the refund
    of an unregistration from the state (`Shelley/TxCert.hs` `shelleyTotalDepositsTxCerts`,
    `shelleyTotalRefundsTxCerts`), but a DRep's refund as the certificate states it (`Conway/TxCert.hs`
    `conwayDRepRefundsTxCerts`), so each case balances with those amounts and the value is conserved.
    """
    socket = f"{ENV}/socket/node1/sock"
    b = Builder(name, socket)
    k1, k2, k3 = (read_key(b, b.wallet(n)["skey"]) for n in (1, 2, 3))
    kd1 = read_key(b, f"{ENV}/stake-delegators/delegator1/staking.skey")
    kdrep = read_key(b, f"{ENV}/drep-keys/drep1/drep.skey")
    fresh_stake, held_stake = seeded_key("coverage stake"), seeded_key("coverage held stake")
    fresh_drep = seeded_key("coverage drep")
    params = json.loads(b.sh(f"{CLI} query protocol-parameters --testnet-magic {MAGIC} --socket-path {socket}"))
    key_deposit = params["stakeAddressDeposit"]
    if params["protocolVersion"]["major"] != 10:
        raise RuntimeError(f"the DELEG cases are built for protocol version 10: {params['protocolVersion']}")
    d1 = json.loads(b.sh(f"{CLI} query stake-address-info --address "
                         f"{bech32('stake_test', eratx.reward_address(kd1.hash))} --testnet-magic {MAGIC} "
                         f"--socket-path {socket}"))[0]
    if d1["rewardAccountBalance"] == 0 or d1["stakeRegistrationDeposit"] != key_deposit:
        raise RuntimeError(f"delegator 1 needs rewards and the parameters' deposit: {d1}")
    drep = json.loads(b.sh(f"{CLI} query drep-state --drep-key-hash {kdrep.hash.hex()} --testnet-magic {MAGIC} "
                           f"--socket-path {socket}"))
    drep_deposit = drep[0][1]["deposit"]

    # The setup registers `held_stake` (no rewards, deposit held: the wrong refund's account) and creates the
    # collateral inputs: ada at a native script's address, and ada with a token at wallet 2's address.
    funder = b.wallet(3)
    setup = collateral_setup(b, "conway", txin(funder), funder["lovelace"], k3, eratx.enterprise_address(k2.hash),
                             f"{CLI} transaction submit --testnet-magic {MAGIC} --socket-path {socket}",
                             certs=[eratx.reg_cert(held_stake.hash, key_deposit)], signers=[held_stake],
                             deposit=key_deposit)
    w = b.wallet(1)
    here = eratx.enterprise_address(k1.hash)
    cases = []

    def add(label, **kw):
        tx, _ = eratx.build_tx("conway", **kw)
        cases.append(Case(label, tx, (TAGS[label][0],)))

    def spend(fee=FEE, delta=0, outputs=None, signers=None, **kw):
        outs = outputs if outputs is not None else [eratx.tx_out("conway", here, w["lovelace"] - fee + delta)]
        return dict(inputs=[txin(w)], outputs=outs, fee=fee, signers=signers or [k1], **kw)

    # IncorrectDepositDELEG: an unregistered credential registers with a deposit of 1 (`Rules/Deleg.hs:199-211`, run
    # at 233-234)
    add("deleg-reg-deposit-wrong", **spend(delta=-key_deposit, certs=[eratx.reg_cert(fresh_stake.hash, 1)],
                                           signers=[k1, fresh_stake]))
    # IncorrectDepositDELEG: a registered credential with no rewards unregisters claiming a refund of 1
    # (`Rules/Deleg.hs:240-258`, reported at 265, before the balance check at 266-268)
    add("deleg-unreg-refund-wrong", **spend(delta=key_deposit, certs=[eratx.unreg_cert(held_stake.hash, 1)],
                                            signers=[k1, held_stake]))
    # StakeKeyHasNonZeroAccountBalanceDELEG: delegator 1, whose account holds rewards, unregisters claiming the
    # deposit it holds (`Rules/Deleg.hs:260-264`, 266-268)
    add("deleg-unreg-reward-balance", **spend(delta=key_deposit, certs=[eratx.unreg_cert(kd1.hash, key_deposit)],
                                              signers=[k1, kd1]))
    # DelegateeDRepNotRegisteredDELEG: delegator 1 delegates its vote to a DRep that is not registered, after the
    # bootstrap phase (`Rules/Deleg.hs:219-226`, run at 279-280)
    add("deleg-vote-unregistered-drep",
        **spend(certs=[eratx.vote_deleg_cert(kd1.hash, eratx.drep_ref("key", fresh_drep.hash))], signers=[k1, kd1]))
    # ConwayDRepIncorrectRefund: DRep 1 unregisters claiming twice the deposit it holds (`Rules/GovCert.hs:234-242`)
    add("govcert-drep-unreg-refund-wrong",
        **spend(delta=2 * drep_deposit, certs=[eratx.drep_unreg_cert(kdrep.hash, 2 * drep_deposit)],
                signers=[k1, kdrep]))
    # The collateral checks run for a transaction with redeemers (`Babbage/Rules/Utxo.hs:194-196`, 216-238, run
    # from `Conway/Rules/Utxo.hs:238`): a Plutus V3 mint whose one collateral input breaks one of them
    v3 = params["costModels"]["PlutusV3"]
    # ScriptsNotPaidUTxO: the collateral input is locked by a native script (`Alonzo/Rules/Utxo.hs:327-333`)
    add("utxo-collateral-script-locked",
        **plutus_minting("conway", spend, here, w["lovelace"], 3, v3, [(setup, 0)], [k1]))
    # CollateralContainsNonADA: the collateral input holds a token and there is no collateral return
    # (`Babbage/Rules/Utxo.hs:253-280`)
    add("utxo-collateral-non-ada",
        **plutus_minting("conway", spend, here, w["lovelace"], 3, v3, [(setup, 1)], [k1, k2]))
    return cases


def conway_gov_proposals(name):
    """Conway GOV proposal refusals at protocol version 10 on the `up` cluster, whose governance roots are empty,
    whose committee has no members and whose constitution has no guardrails script, so that a proposal with no
    previous action and no policy passes every check but the one each case breaks (`Rules/Gov.hs:483-566`).
    The empty parameter change is last: it must decode for the reference to judge it at all.
    """
    socket = f"{ENV}/socket/node1/sock"
    b = Builder(name, socket)
    k1 = read_key(b, b.wallet(1)["skey"])
    kd1 = read_key(b, f"{ENV}/stake-delegators/delegator1/staking.skey")
    member, fresh_stake = seeded_key("coverage committee member"), seeded_key("coverage stake")
    params = json.loads(b.sh(f"{CLI} query protocol-parameters --testnet-magic {MAGIC} --socket-path {socket}"))
    if params["protocolVersion"]["major"] != 10:
        raise RuntimeError(f"the GOV cases are built for protocol version 10: {params['protocolVersion']}")
    gov_deposit = params["govActionDeposit"]
    epoch = json.loads(b.sh(f"{CLI} query tip --testnet-magic {MAGIC} --socket-path {socket}"))["epoch"]
    w = b.wallet(1)
    here = eratx.enterprise_address(k1.hash)
    d1_account = eratx.reward_address(kd1.hash)
    cases = []

    def add(label, action, **witnesses):
        tx, _ = eratx.build_tx("conway", inputs=[txin(w)],
                               outputs=[eratx.tx_out("conway", here, w["lovelace"] - FEE - gov_deposit)], fee=FEE,
                               signers=[k1], proposals=[eratx.proposal(gov_deposit, d1_account, action)], **witnesses)
        cases.append(Case(label, tx, (TAGS[label][0],)))

    # ConflictingCommitteeUpdate: a member both removed and added, with a term far ahead (`Rules/Gov.hs:550-553`)
    add("gov-committee-update-conflicting",
        eratx.update_committee_action({member.hash: epoch + 100}, removed=[member.hash]))
    # ExpirationEpochTooSmall: a member added whose term ends in the current epoch (`Rules/Gov.hs:555-556`)
    add("gov-committee-update-expired-member", eratx.update_committee_action({member.hash: epoch}))
    # TreasuryWithdrawalReturnAccountsDoNotExist: a withdrawal to an account of this network that is not registered
    # (`Rules/Gov.hs:508-520`, after the bootstrap phase, 504)
    add("gov-treasury-withdrawal-unregistered-account",
        eratx.treasury_withdrawal_action({eratx.reward_address(fresh_stake.hash): 1_000_000}))
    # InvalidGuardrailsScriptHash: a well-formed parameter change, and a withdrawal to a registered account, naming a
    # guardrails script where the constitution has none (`Rules/Gov.hs:557-558`, 547; 420-426). The script a proposal
    # names is one UTXOW requires a witness of, so the case carries it: a native script that `k1`'s signature meets.
    guardrails = eratx.sig_script(k1.hash)
    policy = eratx.script_hash(guardrails)
    add("gov-parameter-change-guardrails-unexpected", eratx.param_change_action({0: 44}, policy=policy),
        native_scripts=[guardrails])
    add("gov-treasury-withdrawal-guardrails-unexpected",
        eratx.treasury_withdrawal_action({d1_account: 1_000_000}, policy=policy), native_scripts=[guardrails])
    # MalformedProposal: a parameter change setting one parameter to zero (`Rules/Gov.hs:502`, 393-399;
    # `PParams.hs:935-953`)
    for field, key in ZERO_FIELDS.items():
        add(f"gov-parameter-change-{field}-zero", eratx.param_change_action({key: 0}))
    # MalformedProposal: a parameter change that changes nothing (`PParams.hs:951`)
    add("gov-parameter-change-empty", eratx.param_change_action({}))
    return cases


def tip(b):
    return json.loads(b.sh(f"{CLI} query tip --testnet-magic {MAGIC} --socket-path {ENV}/socket/node1/sock"))


def await_epoch(b, epoch):
    """Wait until the reference's tip is in `epoch` or later; the tip."""
    for _ in range(600):
        now = tip(b)
        if now["epoch"] >= epoch:
            return now
        b.sh("sleep 1")
    raise RuntimeError(f"the reference did not reach epoch {epoch}")


def early_in_epoch(b, slots=100):
    """The tip, once it is at most `slots` into its epoch: a proposal and its votes must land in one epoch."""
    now = tip(b)
    return now if now["slotInEpoch"] <= slots else await_epoch(b, now["epoch"] + 1)


def conway_submit(b, tx, body_hash):
    return submit_and_await(b, "conway", tx, body_hash, f"{CLI} transaction submit --testnet-magic {MAGIC} "
                                                        f"--socket-path {ENV}/socket/node1/sock")


def propose_and_vote(b, funder_key, return_account, deposit, action, voters, certs=(), signers=()):
    """Propose `action` from wallet 3 (whose key is `funder_key`), with `certs` in the same transaction, then vote yes
    on it with every (kind, key) of `voters`, both in one epoch. The action's id and that epoch."""
    start = early_in_epoch(b)["epoch"]
    here = eratx.enterprise_address(funder_key.hash)
    f = b.wallet(3)
    tx, txid = eratx.build_tx("conway", [txin(f)], [eratx.tx_out("conway", here, f["lovelace"] - FEE - deposit)], FEE,
                              [funder_key, *signers], certs=list(certs) or None,
                              proposals=[eratx.proposal(deposit, return_account, action)])
    conway_submit(b, tx, txid)
    action_id = eratx.gov_id(txid, 0)
    f = b.wallet(3)
    votes = eratx.voting_procedures([(eratx.voter(kind, key.hash), [(action_id, "yes")]) for kind, key in voters])
    tx, txid = eratx.build_tx("conway", [txin(f)], [eratx.tx_out("conway", here, f["lovelace"] - FEE)], FEE,
                              [funder_key, *(key for _, key in voters)], votes=votes)
    conway_submit(b, tx, txid)
    if tip(b)["epoch"] != start:
        raise RuntimeError(f"the votes on {action} missed the epoch of their proposal, {start}")
    return action_id, start


def to_protocol_version_11(b, k3, return_account, member_cold, member_hot):
    """Bring the `up` cluster from protocol version 10 to 11, the highest the reference supports
    (GetMaxMajorProtocolVersion): elect a committee of one member, then a hard fork that the committee, the three
    DReps and the three pools vote for. An action proposed and voted on in epoch e is enacted at the boundary into
    e + 2. A hard fork needs the committee's votes (`Conway/Rules/Ratify.hs`), so the committee comes first."""
    params = json.loads(b.sh(f"{CLI} query protocol-parameters --testnet-magic {MAGIC} "
                             f"--socket-path {ENV}/socket/node1/sock"))
    if params["protocolVersion"]["major"] >= 11:
        return
    deposit = params["govActionDeposit"]
    dreps = [("drep", read_key(b, f"{ENV}/drep-keys/drep{n}/drep.skey")) for n in (1, 2, 3)]
    pools = [("pool", read_key(b, f"{ENV}/pools-keys/pool{n}/cold.skey")) for n in (1, 2, 3)]
    epoch = tip(b)["epoch"]
    _, elected = propose_and_vote(b, k3, return_account, deposit, eratx.update_committee_action(
        {member_cold.hash: epoch + 150}, threshold=(1, 2)), dreps + pools)
    await_epoch(b, elected + 2)
    _, forked = propose_and_vote(b, k3, return_account, deposit, eratx.hard_fork_action(11, 0),
                                 [("committee", member_hot)] + dreps + pools,
                                 certs=[eratx.auth_committee_cert(member_cold.hash, member_hot.hash)],
                                 signers=[member_cold])
    await_epoch(b, forked + 2)
    major = json.loads(b.sh(f"{CLI} query protocol-parameters --testnet-magic {MAGIC} "
                            f"--socket-path {ENV}/socket/node1/sock"))["protocolVersion"]["major"]
    if major != 11:
        raise RuntimeError(f"the hard fork to protocol version 11 was not enacted: {major}")


def conway_v11(name):
    """The DELEG and GOV refusals that only protocol version 11 reports, on the `up` cluster brought from version 10
    to 11 first (`to_protocol_version_11`).

    A wrong deposit or refund is `DepositIncorrectDELEG` or `RefundIncorrectDELEG` from version 11
    (`hardforkConwayDELEGIncorrectDepositsAndRefunds`, `Conway/Era.hs:266-267`; `Rules/Deleg.hs:199-211`, 240-258);
    `nOpt` 0 is refused from 11 (`PParams.hs:952-953`); and from 11 a committee member's vote counts only if the
    member is elected (`hardforkConwayDisallowUnelectedCommitteeFromVoting`, `Era.hs:262-263`; `Rules/Gov.hs:478-481`,
    652-665). The unelected member is one an UpdateCommittee proposal names, so its hot key may be authorised
    (`Rules/GovCert.hs:205` searches the proposals too), and it votes on an info action, which the
    committee may vote on (`Rules/Gov.hs:371-376`).
    """
    socket = f"{ENV}/socket/node1/sock"
    b = Builder(name, socket)
    k1, k3 = read_key(b, b.wallet(1)["skey"]), read_key(b, b.wallet(3)["skey"])
    kd1 = read_key(b, f"{ENV}/stake-delegators/delegator1/staking.skey")
    d1_account = eratx.reward_address(kd1.hash)
    member_cold, member_hot = seeded_key("coverage committee cold"), seeded_key("coverage committee hot")
    to_protocol_version_11(b, k3, d1_account, member_cold, member_hot)
    params = json.loads(b.sh(f"{CLI} query protocol-parameters --testnet-magic {MAGIC} --socket-path {socket}"))
    key_deposit, gov_deposit = params["stakeAddressDeposit"], params["govActionDeposit"]
    unelected_cold, unelected_hot = seeded_key("coverage unelected cold"), seeded_key("coverage unelected hot")
    fresh_stake, held_stake = seeded_key("coverage stake"), seeded_key("coverage held stake v11")
    roots = json.loads(b.sh(f"{CLI} query gov-state --testnet-magic {MAGIC} --socket-path {socket}"))[
        "nextRatifyState"]["nextEnactState"]["prevGovActionIds"]
    committee_root = eratx.gov_id(roots["Committee"]["txId"], roots["Committee"]["govActionIx"])

    # Setup, early in an epoch so that its actions are live when the cases are judged: an info action and an
    # UpdateCommittee naming the unelected member (from the enacted committee), with `held_stake` registered; then
    # the unelected member's hot key authorised.
    epoch = early_in_epoch(b)["epoch"]
    here3 = eratx.enterprise_address(k3.hash)
    f = b.wallet(3)
    tx, setup = eratx.build_tx("conway", [txin(f)], [eratx.tx_out(
        "conway", here3, f["lovelace"] - FEE - 2 * gov_deposit - key_deposit)], FEE, [k3, held_stake],
        certs=[eratx.reg_cert(held_stake.hash, key_deposit)],
        proposals=[eratx.proposal(gov_deposit, d1_account, eratx.info_action()),
                   eratx.proposal(gov_deposit, d1_account, eratx.update_committee_action(
                       {unelected_cold.hash: epoch + 150}, prev=committee_root))])
    conway_submit(b, tx, setup)
    f = b.wallet(3)
    tx, txid = eratx.build_tx("conway", [txin(f)], [eratx.tx_out("conway", here3, f["lovelace"] - FEE)], FEE,
                              [k3, unelected_cold],
                              certs=[eratx.auth_committee_cert(unelected_cold.hash, unelected_hot.hash)])
    conway_submit(b, tx, txid)
    if tip(b)["epoch"] != epoch:
        raise RuntimeError(f"the setup of the version 11 cases left epoch {epoch}")

    w = b.wallet(1)
    here = eratx.enterprise_address(k1.hash)
    cases = []

    def add(label, **kw):
        tx, _ = eratx.build_tx("conway", **kw)
        cases.append(Case(label, tx, (TAGS[label][0],)))

    def spend(delta=0, signers=None, **kw):
        return dict(inputs=[txin(w)], outputs=[eratx.tx_out("conway", here, w["lovelace"] - FEE + delta)], fee=FEE,
                    signers=signers or [k1], **kw)

    # DepositIncorrectDELEG: an unregistered credential registers with a deposit of 1 (`Rules/Deleg.hs:199-211`)
    add("deleg-reg-deposit-wrong-v11", **spend(delta=-key_deposit, certs=[eratx.reg_cert(fresh_stake.hash, 1)],
                                               signers=[k1, fresh_stake]))
    # RefundIncorrectDELEG: a registered credential with no rewards unregisters claiming a refund of 1
    # (`Rules/Deleg.hs:240-258`)
    add("deleg-unreg-refund-wrong-v11", **spend(delta=key_deposit, certs=[eratx.unreg_cert(held_stake.hash, 1)],
                                                signers=[k1, held_stake]))
    # MalformedProposal: a parameter change setting nOpt (key 8) to zero (`PParams.hs:952-953`)
    add("gov-parameter-change-n-opt-zero-v11",
        **spend(delta=-gov_deposit, proposals=[eratx.proposal(gov_deposit, d1_account,
                                                              eratx.param_change_action({8: 0}))]))
    # UnelectedCommitteeVoters: the authorised hot key of a member no committee elected votes on the info action
    add("gov-vote-unelected-committee-member-v11",
        **spend(votes=eratx.voting_procedures([(eratx.voter("committee", unelected_hot.hash),
                                                [(eratx.gov_id(setup, 0), "yes")])]),
                signers=[k1, unelected_hot]))
    return cases


def preconway_collateral(name, era):
    """The collateral and body-network refusals of `era` (alonzo or babbage) on the era-walking cluster stopped in
    it (`up-eras --final-era <era>`). Alonzo's UTXO runs the collateral checks for a transaction with redeemers
    (`Alonzo/Rules/Utxo.hs:299-301`, `validateCollateral` 304-319); Babbage's its own (`Babbage/Rules/Utxo.hs:194-196`,
    216-238). A Plutus mint (V1 in Alonzo, V2 in Babbage) carries the collateral input each case varies.
    """
    from .util import SOCKET
    b = EraWallets(name)
    funder, wd, other = b.wallet(1), b.wallet(2), b.wallet(3)
    k, k_other = wd["key"], other["key"]
    params = json.loads(b.sh(f"cardano-cli query protocol-parameters {SOCKET}"))
    language = 1 if era == "alonzo" else 2
    models = params["costModels"][f"PlutusV{language}"]
    setup = collateral_setup(b, era, txin(funder), funder["lovelace"], funder["key"], other["address"],
                             f"cardano-cli conway transaction submit {SOCKET}")
    here = wd["address"]
    cases = []

    def add(label, **kw):
        tx, _ = eratx.build_tx(era, **kw)
        cases.append(Case(label, tx, (tags(era, label)[0],)))

    def spend(fee=FEE, outputs=None, signers=None, **kw):
        outs = outputs if outputs is not None else [eratx.tx_out(era, here, wd["lovelace"] - fee)]
        return dict(inputs=[txin(wd)], outputs=outs, fee=fee, signers=signers or [k], **kw)

    # ScriptsNotPaidUTxO: the collateral input is locked by a native script (`Alonzo/Rules/Utxo.hs:327-333`)
    add("utxo-collateral-script-locked",
        **plutus_minting(era, spend, here, wd["lovelace"], language, models, [(setup, 0)], [k]))
    # CollateralContainsNonADA: the collateral input holds a token (Alonzo `Rules/Utxo.hs:353-360`; Babbage,
    # with no collateral return, `Babbage/Rules/Utxo.hs:253-280`)
    add("utxo-collateral-non-ada",
        **plutus_minting(era, spend, here, wd["lovelace"], language, models, [(setup, 1)], [k, k_other]))
    # WrongNetworkInTxBody: the body's network id (key 15) is mainnet (`Alonzo/Rules/Utxo.hs:436-446`)
    add("utxo-body-network-id-wrong", **spend(network_id=1))
    # ExUnitsTooBigUTxO: the redeemer's units exceed maxTxExecutionUnits by one in each (`Alonzo/Rules/Utxo.hs:451-462`);
    # the fee and collateral cover the script's price at those units
    limit = params["maxTxExecutionUnits"]
    add("utxo-mint-units-too-big",
        **plutus_minting(era, spend, here, wd["lovelace"], language, models, [txin(other)], [k, k_other],
                         fee=10_000_000, units=(limit["memory"] + 1, limit["steps"] + 1)))
    return cases


def alonzo_collateral(name):
    return preconway_collateral(name, "alonzo")


def babbage_collateral(name):
    return preconway_collateral(name, "babbage")
