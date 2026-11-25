# SPDX-License-Identifier: Apache-2.0
"""Refusal cases for the constructors of Conway's era failure encoder that no earlier corpus reaches, for the order
in which the reference lists the items of a GOV or LEDGER failure, and for Conway's MEMPOOL rule (#85).

Four corpora, recorded in this order on one `up` cluster, since the protocol version only goes up:

- `conway_constructors` (protocol version 10): one failure each, the constructors of Conway's UTXOW, UTXO, POOL and
  GOVCERT that no corpus reached, and the empty input set, which MEMPOOL refuses before LEDGER runs;
- `conway_gov_order` (10): votes, voters and withdrawals that arrive out of the reference's key order;
- `conway_mempool` (10): MEMPOOL's unelected-committee-voter refusal, alone and with LEDGER's failures, and
  refusals judged against proposals and votes the reference's mempool admitted first (`Case.before`);
- `conway_constructors_v11` (11): the withdrawal, VRF-key, pool-cost and integrity-hash failures of version 11.

`TAGS` names each case's failures by their constructor tags (era index, then the tag at each level, outermost
first), in the order the reference lists them, and `scripts/tests/test_refusalconstructors.py` requires every recorded
refusal to be exactly that. Citations are to cardano-ledger f649f975 (`eras/<era>/impl/src/Cardano/Ledger/<Era>/...`)
unless another revision is named. The commands that record the corpora are in `fixtures/reference/refusals/README.md`.
"""
import json

from . import eratx
from .cbor import Raw, enc, head
from .eraplutus import OK
from .ntccases import CLI, Builder
from .refusalcases import (FEE, PLUTUS_FEE, WINDOW_MARGIN, Case, await_epoch, bech32, gov_actions, propose,
                           read_key, seeded_key, setup_outputs, tip, txin)
from .refusalcoverage import conway_submit, early_in_epoch, plutus_minting, propose_and_vote, to_protocol_version_11
from .util import ENV, MAGIC

CONWAY = 6
# Conway's ledger failure (`Conway/Rules/Ledger.hs:229-259`): 1 UTXOW (its 0 UTXO, whose 0 UTXOS), 2 CERTS (its 1
# CERT: 2 POOL, 3 GOVCERT), 3 GOV, 7 MEMPOOL's text, 8 and 9 the withdrawal failures of version 11.
UTXOW, UTXO, POOL, GOVCERT, GOV = (1,), (1, 0), (2, 1, 2), (2, 1, 3), (3,)
MEMPOOL, MISSING_ACCOUNTS, INCOMPLETE_WITHDRAWALS = (7,), (8,), (9,)

# the label of each case and the (constructor, tags) of each failure the reference lists, in its order
TAGS = {
    # ---- conway_constructors, version 10
    # `Conway/Rules/Utxow.hs:219-245` writes UTXOW's tags; Shelley's checks, `Shelley/Rules/Utxow.hs`
    "utxow-signature-invalid": [("InvalidWitnessesUTXOW", UTXOW + (1,))],
    "utxow-native-script-fails": [("ScriptWitnessNotValidatingUTXOW", UTXOW + (4,))],
    "utxow-metadata-hash-missing": [("MissingTxBodyMetadataHash", UTXOW + (5,))],
    "utxow-metadata-missing": [("MissingTxMetadata", UTXOW + (6,))],
    "utxow-metadata-hash-conflicting": [("ConflictingMetadataHash", UTXOW + (7,))],
    "utxow-metadata-invalid": [("InvalidMetadata", UTXOW + (8,))],
    "utxow-unspendable-no-datum": [("UnspendableUTxONoDatumHash", UTXOW + (14,))],
    # `Conway/Rules/Utxo.hs:309-334` writes UTXO's tags
    "utxo-collateral-inputs-too-many": [("TooManyCollateralInputs", UTXO + (18,))],
    "utxo-total-collateral-incorrect": [("IncorrectTotalCollateralField", UTXO + (20,))],
    "utxo-reference-input-spent": [("BabbageNonDisjointRefInputs", UTXO + (22,))],
    # `Conway/Rules/Mempool.hs:113-118`
    "mempool-inputs-empty": [("ConwayMempoolFailure", MEMPOOL)],
    # `Shelley/Rules/Pool.hs:160-170` writes POOL's tags
    "pool-reward-account-wrong-network": [("WrongNetworkPOOL", POOL + (4,))],
    "pool-metadata-hash-too-big": [("PoolMedataHashTooBig", POOL + (5,))],
    # `Conway/Rules/GovCert.hs` (`EncCBOR (ConwayGovCertPredFailure era)`)
    "govcert-committee-member-resigned": [("ConwayCommitteeHasPreviouslyResigned", GOVCERT + (3,))],
    # ---- conway_gov_order, version 10 (`Conway/Rules/Gov.hs:259-298`)
    "gov-votes-on-unknown-actions-out-of-order": [("GovActionsDoNotExist", GOV + (0,))],
    "gov-voters-unknown-out-of-order": [("VotersDoNotExist", GOV + (14,))],
    "gov-votes-on-expired-actions-out-of-order": [("VotingOnExpiredGovAction", GOV + (9,))],
    "gov-pool-votes-disallowed-out-of-order": [("DisallowedVoters", GOV + (5,))],
    "gov-treasury-withdrawal-accounts-out-of-order": [("TreasuryWithdrawalReturnAccountsDoNotExist", GOV + (17,))],
    # ---- conway_mempool, version 10
    "mempool-unelected-committee-voter": [("ConwayMempoolFailure", MEMPOOL)],
    "mempool-unelected-voters-with-ledger-failures": [("GovActionsDoNotExist", GOV + (0,)),
                                                      ("FeeTooSmallUTxO", UTXO + (5,)),
                                                      ("ConwayMempoolFailure", MEMPOOL)],
    "mempool-vote-on-admitted-proposal": [("DisallowedVoters", GOV + (5,))],
    "mempool-authorization-after-admitted-proposal": [("FeeTooSmallUTxO", UTXO + (5,))],
    # ---- conway_constructors_v11, version 11
    "ledger-withdrawal-amounts-wrong-v11": [("ConwayIncompleteWithdrawals", INCOMPLETE_WITHDRAWALS)],
    "ledger-withdrawal-accounts-missing-v11": [("ConwayWithdrawalsMissingAccounts", MISSING_ACCOUNTS)],
    "pool-vrf-key-registered-v11": [("VRFKeyHashAlreadyRegistered", POOL + (6,))],
    "pool-cost-too-low-v11": [("StakePoolCostTooLowPOOL", POOL + (3,))],
    "utxow-script-integrity-hash-mismatch-v11": [("ScriptIntegrityHashMismatch", UTXOW + (18,))],
}
# the protocol version each corpus is built for
VERSIONS = {"conway-constructors": 10, "conway-gov-order": 10, "conway-mempool": 10, "conway-constructors-v11": 11}

# the setup outputs at wallet 1's address: inputs of their own for the collateral, prefix and Plutus cases
SMALL = 20_000_000
# a pool registration's minimum cost from version 11 in `conway_constructors_v11`, set by a parameter change
MIN_POOL_COST = 1_000_000
# `protocol_param_update`'s key of minPoolCost (the Conway CDDL)
MIN_POOL_COST_KEY = 16


def expected(label):
    """The constructors a case's rendering must name, in the reference's order."""
    return tuple(name for name, _ in TAGS[label])


def unsorted_map(pairs):
    """A CBOR map of `pairs` in the order given; `cbor.enc` writes a dict's keys in canonical order."""
    return Raw(head(5, len(pairs)) + b"".join(enc(k) + enc(v) for k, v in pairs))


# ---- the reference's key order. Each key type derives `Ord`: a constructor sorts before the ones declared after
# it, then by its fields (`Credential`, `libs/cardano-ledger-core/src/Cardano/Ledger/Credential.hs`: ScriptHashObj
# before KeyHashObj; `Voter`, `Conway/Governance/Procedures.hs`: CommitteeVoter, DRepVoter, StakePoolVoter;
# `GovActionId`, the transaction id then the index; `AccountAddress`, the network then the credential). A hash
# compares by its bytes.

def credential_order(kind_script, h):
    return (0 if kind_script else 1, h)


def voter_order(voter):
    """The reference's order of a voter as `eratx.voter` writes it, `[tag, hash]`: tags 0 and 1 a committee member's
    key or script, 2 and 3 a DRep's, 4 a stake pool."""
    tag, h = voter
    return {0: (0, 1), 1: (0, 0), 2: (1, 1), 3: (1, 0), 4: (2, 0)}[tag], h


def gov_id_order(gov_id):
    return tuple(gov_id)


def account_order(account):
    """The reference's order of a reward account's bytes: the network (the header's low bits), then the credential
    (bit 4 of the header set for a script)."""
    return account[0] & 0x0F, credential_order(account[0] & 0x10, account[1:])


def votes_out_of_order(cast):
    """Voting procedures `[(voter, [(gov_id, vote)])]` written in descending key order, voters and their actions."""
    return unsorted_map([(voter, unsorted_map([(i, [eratx.VOTE[v], None])
                                                for i, v in sorted(ids, key=lambda p: gov_id_order(p[0]),
                                                                   reverse=True)]))
                         for voter, ids in sorted(cast, key=lambda p: voter_order(p[0]), reverse=True)])


class ForgedWitness:
    """`key`, whose witness signs other bytes than the transaction body's hash: a witness its key does not verify."""

    def __init__(self, key):
        self.key, self.hash = key, key.hash

    def witness(self, body_hash):
        return [self.key.vkey, eratx.sign(self.key.seed, eratx.blake2b(b"not the body " + body_hash))]


def vrf_hash(b, path):
    """The hash of the VRF verification key in the text envelope at `path` (`hashVerKeyVRF`, Blake2b-256)."""
    return eratx.blake2b(bytes.fromhex(json.loads(b.sh(f"cat {path}"))["cborHex"])[2:])


def pool_params(cold, vrf, cost=0, network=0, metadata=None):
    """A pool registered by `cold` with no owners, no pledge and its reward account on `network`."""
    return eratx.pool_registration_cert({"operator": cold.hash, "vrf": vrf, "pledge": 0, "cost": cost,
                                         "margin": [0, 1], "reward_account": eratx.reward_address(cold.hash, network),
                                         "owners": [], "relays": [], "metadata": metadata})


def parameters(b):
    return json.loads(b.sh(f"{CLI} query protocol-parameters --testnet-magic {MAGIC} --socket-path {b.socket}"))


def require_version(b, major):
    version = parameters(b)["protocolVersion"]
    if version["major"] != major:
        raise RuntimeError(f"the cases are built for protocol version {major}: {version}")


def small_outputs(b, key, count):
    """`count` outputs of SMALL lovelace at `key`'s enterprise address, from wallet 3; their inputs."""
    here = eratx.enterprise_address(key.hash)
    k3 = read_key(b, b.wallet(3)["skey"])
    setup = setup_outputs(b, b.wallet(3), k3, [(eratx.tx_out("conway", here, SMALL), SMALL)] * count)
    return [(setup, i) for i in range(count)]


def conway_constructors(name):
    """One failure each at protocol version 10 on the `up` cluster: the constructors of Conway's UTXOW, UTXO, POOL and
    GOVCERT that no earlier corpus reached (#85), and the empty input set.

    The empty input set is MEMPOOL's: it refuses a transaction none of whose inputs the UTxO holds as probably
    included already and skips LEDGER (`Conway/Rules/Mempool.hs:113-122`), so `InputSetEmptyUTxO`
    (`Shelley/Rules/Utxo.hs` `validateInputSetEmptyUTxO`, run from `Babbage/Rules/Utxo.hs:367`) never reaches a
    submitter.
    """
    socket = f"{ENV}/socket/node1/sock"
    b = Builder(name, socket)
    require_version(b, 10)
    params = parameters(b)
    v2, v3 = params["costModels"]["PlutusV2"], params["costModels"]["PlutusV3"]
    gov_deposit = params["govActionDeposit"]
    k1, k3 = read_key(b, b.wallet(1)["skey"]), read_key(b, b.wallet(3)["skey"])
    kd1 = read_key(b, f"{ENV}/stake-delegators/delegator1/staking.skey")
    d1_account = eratx.reward_address(kd1.hash)
    here = eratx.enterprise_address(k1.hash)
    absent, resigned, resigned_hot = (seeded_key(f"constructors {n}") for n in ("absent signer", "resigned cold",
                                                                                 "resigned hot"))
    pool_wrong_network, pool_big_metadata = seeded_key("constructors pool network"), seeded_key("constructors pool md")
    # Setup: an output at a Plutus V2 script's address with no datum, five small outputs at wallet 1's address (the
    # collateral inputs), and an UpdateCommittee proposal naming `resigned`, so that it may resign
    # (`Rules/GovCert.hs:190-207` searches the proposals)
    script = eratx.enterprise_address(eratx.plutus_script_hash(OK, 2), script=True)
    locked = setup_outputs(b, b.wallet(3), k3, [(eratx.tx_out("conway", script, SMALL), SMALL)])
    collateral = small_outputs(b, k1, 5)
    epoch = tip(b)["epoch"]
    propose(b, b.wallet(3), k3, [eratx.proposal(gov_deposit, d1_account, eratx.update_committee_action(
        {resigned.hash: epoch + 100}))], gov_deposit)

    w = b.wallet(1)
    cases = []

    def add(label, **kw):
        tx, _ = eratx.build_tx("conway", **kw)
        cases.append(Case(label, tx, expected(label)))

    def spend(fee=FEE, delta=0, outputs=None, signers=None, **kw):
        outs = outputs if outputs is not None else [eratx.tx_out("conway", here, w["lovelace"] - fee + delta)]
        return dict(inputs=kw.pop("inputs", [txin(w)]), outputs=outs, fee=fee, signers=signers or [k1], **kw)

    metadata = {674: "scarab refusal corpus"}
    # ---- UTXOW (`Shelley/Rules/Utxow.hs`, run from `Babbage/Rules/Utxow.hs` and `Conway/Rules/Utxow.hs`)
    # InvalidWitnessesUTXOW: the one witness's signature is not over the body's hash (`validateVerifiedWits`)
    add("utxow-signature-invalid", **spend(signers=[ForgedWitness(k1)]))
    # ScriptWitnessNotValidatingUTXOW: a mint whose native policy needs a key that did not sign
    # (`validateFailedNativeScripts`)
    policy = eratx.all_script(eratx.sig_script(absent.hash))
    token = {eratx.script_hash(policy): {b"absent": 1}}
    add("utxow-native-script-fails", **spend(outputs=[eratx.tx_out("conway", here, w["lovelace"] - FEE, token)],
                                             mint=token, native_scripts=[policy]))
    # The metadata checks (`validateMetadata`, `Shelley/Rules/Utxow.hs:430-443`): auxiliary data with no hash in the
    # body; a hash in the body with no auxiliary data; a hash that is not the auxiliary data's; and auxiliary data
    # holding a Plutus V3 script that does not deserialise (`validateAlonzoTxAuxData`, `Alonzo/TxAuxData.hs:363-373`).
    # A metadatum over 64 bytes, the other invalid metadata, is refused by the decoder before any rule
    # (`decodeMetadatum`, `libs/cardano-ledger-core/src/Cardano/Ledger/Metadata.hs:153-179`): the node closes the
    # connection.
    add("utxow-metadata-hash-missing", **spend(metadata=metadata, aux_hash=None))
    add("utxow-metadata-missing", **spend(aux_hash=eratx.blake2b(enc(metadata))))
    add("utxow-metadata-hash-conflicting", **spend(metadata=metadata, aux_hash=eratx.blake2b(b"other metadata")))
    add("utxow-metadata-invalid", **spend(metadata=metadata, aux_plutus={3: [enc(b"\x00")]}))
    # UnspendableUTxONoDatumHash: the Plutus V2 script's output has no datum (`Alonzo/Rules/Utxow.hs`
    # `missingRequiredDatums`, run from `Babbage/Rules/Utxow.hs`); V3 would not need one
    inputs = sorted([txin(w), (locked, 0)])
    redeemers = [(0, inputs.index((locked, 0)), 0, (1_000_000, 500_000_000))]
    add("utxow-unspendable-no-datum", **spend(
        inputs=inputs, fee=PLUTUS_FEE, outputs=[eratx.tx_out("conway", here, w["lovelace"] + SMALL - PLUTUS_FEE)],
        plutus={2: [OK]}, redeemers=redeemers, collateral=[collateral[0]],
        script_data=eratx.script_data_hash(enc(eratx.redeemers_cbor("conway", redeemers)), b"", {2: v2})))
    # ---- UTXO (`Babbage/Rules/Utxo.hs:342-410`)
    # TooManyCollateralInputs: four collateral inputs, where maxCollateralInputs is 3; checked with or without
    # redeemers (`Alonzo/Rules/Utxo.hs` `validateTooManyCollateralInputs`, run at 409)
    if params["maxCollateralInputs"] != 3:
        raise RuntimeError(f"the collateral case is built for maxCollateralInputs 3: {params['maxCollateralInputs']}")
    add("utxo-collateral-inputs-too-many", **spend(collateral=collateral[1:5]))
    # IncorrectTotalCollateralField: a Plutus V3 mint whose collateral input holds SMALL and whose body declares one
    # lovelace less (`validateTotalCollateral`, run from `feesOK` for a transaction with redeemers)
    add("utxo-total-collateral-incorrect", **plutus_minting(
        "conway", lambda **kw: spend(**kw, total_collateral=SMALL - 1), here, w["lovelace"], 3, v3, [collateral[1]],
        [k1]))
    # BabbageNonDisjointRefInputs: the spent input is also a reference input, refused at versions 9 and 10
    # (`disjointRefInputs`, 200-214, run at 356)
    add("utxo-reference-input-spent", **spend(reference_inputs=[txin(w)]))
    # ---- MEMPOOL: no input at all
    add("mempool-inputs-empty", inputs=[], outputs=[], fee=0, signers=[])
    # ---- POOL (`Shelley/Rules/Pool.hs:220-262`); the pool deposit is 0 on this cluster
    if params["stakePoolDeposit"] != 0:
        raise RuntimeError(f"the pool cases are built for a pool deposit of 0: {params['stakePoolDeposit']}")
    # WrongNetworkPOOL: a new pool's reward account is a mainnet account (231-242)
    add("pool-reward-account-wrong-network", **spend(
        certs=[pool_params(pool_wrong_network, eratx.blake2b(b"constructors vrf network"), network=1)],
        signers=[k1, pool_wrong_network]))
    # PoolMedataHashTooBig: a new pool's metadata hash is 33 bytes (244-249)
    add("pool-metadata-hash-too-big", **spend(
        certs=[pool_params(pool_big_metadata, eratx.blake2b(b"constructors vrf metadata"),
                           metadata=["https://example.invalid/pool.json", bytes(33)])],
        signers=[k1, pool_big_metadata]))
    # ---- GOVCERT: a committee member the proposal names resigns, then authorises a hot key in the same transaction
    # (`Rules/GovCert.hs:190-197`)
    add("govcert-committee-member-resigned", **spend(
        certs=[eratx.resign_committee_cert(resigned.hash), eratx.auth_committee_cert(resigned.hash,
                                                                                      resigned_hot.hash)],
        signers=[k1, resigned]))
    return cases


def conway_gov_order(name):
    """GOV refusals at protocol version 10 whose items arrive out of the reference's key order (#85, the flat GOV's
    body order). The reference lists them in key order: `GovActionsDoNotExist` and the votes of
    `VotingOnExpiredGovAction` and `DisallowedVoters` fold the voting procedures' maps (`foldrVotingProcedures`,
    `Conway/Rules/Gov.hs:573-603`; `checkDisallowedVotes`, 356-376), `VotersDoNotExist` is a set (595-604), and
    `TreasuryWithdrawalReturnAccountsDoNotExist` is a map's keys (508-519). Each case's map is written in descending
    key order (`votes_out_of_order`); a credential's script sorts before a key, where its CBOR tag sorts after.

    The vote cases need actions on chain. `govActionLifetime` is 1, so the timing is `conway_governance`'s: two info
    actions proposed in epoch e are expired but still present in e + 2, when two treasury withdrawals proposed in
    e + 1 are live. `DisallowedVotesDuringBootstrap` is version 9's only (`hardforkConwayBootstrapPhase`,
    `Conway/Era.hs:257-258`); the cluster starts at 10.
    """
    socket = f"{ENV}/socket/node1/sock"
    b = Builder(name, socket)
    require_version(b, 10)
    params = parameters(b)
    gov_deposit = params["govActionDeposit"]
    k1, k3 = read_key(b, b.wallet(1)["skey"]), read_key(b, b.wallet(3)["skey"])
    kd1 = read_key(b, f"{ENV}/stake-delegators/delegator1/staking.skey")
    d1_account = eratx.reward_address(kd1.hash)
    dreps = [read_key(b, f"{ENV}/drep-keys/drep{n}/drep.skey") for n in (1, 2, 3)]
    pools = [read_key(b, f"{ENV}/pools-keys/pool{n}/cold.skey") for n in (1, 2, 3)]
    unknown_drep, unknown_pool = seeded_key("gov order drep"), seeded_key("gov order pool")
    unknown_accounts = [seeded_key(f"gov order account {n}") for n in (1, 2)]
    voting_script, account_script = eratx.sig_script(k1.hash), eratx.all_script(eratx.sig_script(k1.hash))

    # two proposals of one transaction differ (its proposals are a set): by their anchors
    info = propose(b, b.wallet(3), k3, [eratx.proposal(gov_deposit, d1_account, eratx.info_action(), eratx.anchor(
        f"https://example.invalid/info-{n}")) for n in (1, 2)], gov_deposit)
    proposed, expires = gov_actions(b)[(info, 0)]
    await_epoch(b, proposed + 1)
    withdrawals = propose(b, b.wallet(3), k3, [eratx.proposal(gov_deposit, d1_account, eratx.treasury_withdrawal_action(
        {d1_account: amount})) for amount in (1_000_000, 2_000_000)], gov_deposit)
    window = expires + 1
    now = await_epoch(b, window)
    actions = gov_actions(b)
    if now["epoch"] != window or now["slotsToEpochEnd"] < WINDOW_MARGIN:
        raise RuntimeError(f"missed the voting window, epoch {window} with {WINDOW_MARGIN} slots to spare: {now}")
    if any(a not in actions for a in ((info, 0), (info, 1), (withdrawals, 0), (withdrawals, 1))) \
            or actions[(withdrawals, 0)][1] < window:
        raise RuntimeError(f"the setup actions are not where the vote cases need them: {actions}")

    w = b.wallet(1)
    here = eratx.enterprise_address(k1.hash)
    cases = []

    def add(label, delta=0, signers=(), **kw):
        tx, _ = eratx.build_tx("conway", inputs=[txin(w)],
                               outputs=[eratx.tx_out("conway", here, w["lovelace"] - FEE + delta)], fee=FEE,
                               signers=[k1, *signers], **kw)
        cases.append(Case(label, tx, expected(label)))

    expired = [eratx.gov_id(info, 0), eratx.gov_id(info, 1)]
    live = [eratx.gov_id(withdrawals, 0), eratx.gov_id(withdrawals, 1)]
    unknown = [eratx.gov_id("11" * 32, 1), eratx.gov_id("22" * 32, 0), eratx.gov_id("33" * 32, 0),
               eratx.gov_id("11" * 32, 0)]
    # ---- the expired votes first, judged at the start of the window
    # VotingOnExpiredGovAction: the three DReps vote on both expired info actions (`Rules/Gov.hs:356-362`)
    add("gov-votes-on-expired-actions-out-of-order",
        votes=votes_out_of_order([(eratx.voter("drep", k.hash), [(i, "yes") for i in expired]) for k in dreps]),
        signers=dreps)
    # GovActionsDoNotExist: two DReps vote on actions that do not exist, two each (`Rules/Gov.hs:579-605`)
    add("gov-votes-on-unknown-actions-out-of-order",
        votes=votes_out_of_order([(eratx.voter("drep", dreps[0].hash), [(unknown[0], "yes"), (unknown[1], "no")]),
                                  (eratx.voter("drep", dreps[1].hash), [(unknown[2], "yes"), (unknown[3], "no")])]),
        signers=dreps[:2])
    # VotersDoNotExist: a DRep script, a DRep key and a stake pool that are not registered vote on a live action
    # (`Rules/Gov.hs:595-604`); the script is witnessed as UTXOW requires
    add("gov-voters-unknown-out-of-order",
        votes=votes_out_of_order([(eratx.voter("drep", eratx.script_hash(voting_script), script=True),
                                   [(live[0], "yes")]),
                                  (eratx.voter("drep", unknown_drep.hash), [(live[0], "yes")]),
                                  (eratx.voter("pool", unknown_pool.hash), [(live[0], "yes")])]),
        native_scripts=[voting_script], signers=[unknown_drep, unknown_pool])
    # DisallowedVoters: the three stake pools vote on both treasury withdrawals, which no pool may vote on
    # (`Rules/Gov.hs:364-376`; `Governance/Internal.hs:405`)
    add("gov-pool-votes-disallowed-out-of-order",
        votes=votes_out_of_order([(eratx.voter("pool", k.hash), [(i, "yes") for i in live]) for k in pools]),
        signers=pools)
    # TreasuryWithdrawalReturnAccountsDoNotExist: a treasury withdrawal to two key accounts and a script account,
    # none registered, written in neither the reference's order nor the canonical CBOR order (`Rules/Gov.hs:508-519`)
    accounts = sorted([eratx.reward_address(k.hash) for k in unknown_accounts]
                      + [eratx.reward_address(eratx.script_hash(account_script), script=True)], key=account_order)
    arrival = [accounts[2], accounts[0], accounts[1]]
    add("gov-treasury-withdrawal-accounts-out-of-order", delta=-gov_deposit,
        proposals=[eratx.proposal(gov_deposit, d1_account,
                                  [2, unsorted_map([(a, 1_000_000) for a in arrival]), None])])
    return cases


def conway_mempool(name):
    """Conway's MEMPOOL rule at protocol version 10 (#85): its refusal of a vote by an unelected committee member
    (`Conway/Rules/Mempool.hs:123-135`, the text of the hot credentials' `show`), alone and with the failures LEDGER
    still reports after it (138): the reference lists LEDGER's first and MEMPOOL's text last, though MEMPOOL's check
    runs first; and refusals judged against proposals and votes the reference's mempool
    admitted first (`Case.before`), so that the proposals the mempool writes back are what GOV and GOVCERT read.

    An unelected member is one an UpdateCommittee proposal names, so its hot key may be authorised
    (`Rules/GovCert.hs:190-207`); there is no elected committee on this cluster. A case with transactions before it
    is taken again from other inputs (`Case.retry`) when the tip moves while it is judged, since its prefix is then
    admitted already.
    """
    socket = f"{ENV}/socket/node1/sock"
    b = Builder(name, socket)
    require_version(b, 10)
    params = parameters(b)
    gov_deposit = params["govActionDeposit"]
    k1, k3 = read_key(b, b.wallet(1)["skey"]), read_key(b, b.wallet(3)["skey"])
    kd1 = read_key(b, f"{ENV}/stake-delegators/delegator1/staking.skey")
    d1_account = eratx.reward_address(kd1.hash)
    kdrep = read_key(b, f"{ENV}/drep-keys/drep1/drep.skey")
    kpool = read_key(b, f"{ENV}/pools-keys/pool1/cold.skey")
    cold = [seeded_key(f"mempool unelected cold {n}") for n in (1, 2)]
    hot = [seeded_key(f"mempool unelected hot {n}") for n in (1, 2)]
    here = eratx.enterprise_address(k1.hash)

    # Setup, early in an epoch, so that the info action is live when the cases are judged: inputs for the cases with a
    # prefix, an info action and an UpdateCommittee naming both unelected members, then their hot keys authorised.
    small = iter(small_outputs(b, k1, 12))
    epoch = early_in_epoch(b)["epoch"]
    setup = propose(b, b.wallet(3), k3, [eratx.proposal(gov_deposit, d1_account, eratx.info_action()),
                                         eratx.proposal(gov_deposit, d1_account, eratx.update_committee_action(
                                             {c.hash: epoch + 100 for c in cold}))], gov_deposit)
    f = b.wallet(3)
    tx, txid = eratx.build_tx("conway", [txin(f)], [eratx.tx_out("conway", eratx.enterprise_address(k3.hash),
                                                                 f["lovelace"] - FEE)], FEE, [k3, *cold],
                              certs=[eratx.auth_committee_cert(c.hash, h.hash) for c, h in zip(cold, hot)])
    conway_submit(b, tx, txid)
    if tip(b)["epoch"] != epoch:
        raise RuntimeError(f"the setup of the MEMPOOL cases left epoch {epoch}")
    info = eratx.gov_id(setup, 0)

    w = b.wallet(1)
    cases = []

    def build(inputs, lovelace, fee=FEE, delta=0, signers=(), **kw):
        return eratx.build_tx("conway", inputs=inputs, outputs=[eratx.tx_out("conway", here, lovelace - fee + delta)],
                              fee=fee, signers=[k1, *signers], **kw)

    # ConwayMempoolFailure: an unelected member's hot key votes on the info action, which the committee may vote on
    tx, _ = build([txin(w)], w["lovelace"], signers=[hot[0]],
                  votes=eratx.voting_procedures([(eratx.voter("committee", hot[0].hash), [(info, "yes")])]))
    cases.append(Case("mempool-unelected-committee-voter", tx, expected("mempool-unelected-committee-voter")))
    # The same refusal with LEDGER's: both unelected members vote on the info action, one also on an action
    # that does not exist (GOV), and the fee is 0 (UTXO); the value is conserved
    label = "mempool-unelected-voters-with-ledger-failures"
    tx, _ = build([txin(w)], w["lovelace"], fee=0, signers=hot, votes=votes_out_of_order(
        [(eratx.voter("committee", hot[0].hash), [(info, "yes"), (eratx.gov_id("11" * 32, 0), "yes")]),
         (eratx.voter("committee", hot[1].hash), [(info, "yes")])]))
    cases.append(Case(label, tx, expected(label)))

    def vote_on_admitted_proposal():
        """A treasury withdrawal proposed and a DRep's vote on it, both admitted; then a stake pool's vote on it,
        which DisallowedVoters refuses only if the mempool holds the proposal (else GovActionsDoNotExist)."""
        a, c, d = next(small), next(small), next(small)
        proposal, proposal_id = build([a], SMALL, delta=-gov_deposit, proposals=[eratx.proposal(
            gov_deposit, d1_account, eratx.treasury_withdrawal_action({d1_account: 1_000_000}))])
        action = eratx.gov_id(proposal_id, 0)
        vote, _ = build([c], SMALL, signers=[kdrep],
                        votes=eratx.voting_procedures([(eratx.voter("drep", kdrep.hash), [(action, "yes")])]))
        refused, _ = build([d], SMALL, signers=[kpool],
                           votes=eratx.voting_procedures([(eratx.voter("pool", kpool.hash), [(action, "yes")])]))
        return Case("mempool-vote-on-admitted-proposal", refused, expected("mempool-vote-on-admitted-proposal"),
                    before=(proposal, vote), retry=vote_on_admitted_proposal)

    def authorization_after_admitted_proposal():
        """An UpdateCommittee proposal naming a new member, admitted; then that member's hot key authorised, with a
        fee of 0: FeeTooSmallUTxO alone only if the mempool holds the proposal (else ConwayCommitteeIsUnknown too)."""
        a, c = next(small), next(small)
        member, member_hot = seeded_key(f"mempool admitted member {a}"), seeded_key(f"mempool admitted hot {a}")
        proposal, _ = build([a], SMALL, delta=-gov_deposit, proposals=[eratx.proposal(
            gov_deposit, d1_account, eratx.update_committee_action({member.hash: epoch + 100}))])
        refused, _ = build([c], SMALL, fee=0, signers=[member],
                           certs=[eratx.auth_committee_cert(member.hash, member_hot.hash)])
        return Case("mempool-authorization-after-admitted-proposal", refused,
                    expected("mempool-authorization-after-admitted-proposal"), before=(proposal,),
                    retry=authorization_after_admitted_proposal)

    cases += [vote_on_admitted_proposal(), authorization_after_admitted_proposal()]
    return cases


def conway_constructors_v11(name):
    """The failures only protocol version 11 reports (#85), on the `up` cluster brought to it first
    (`refusalcoverage.to_protocol_version_11`): the withdrawal failures LEDGER reports from 11
    (`hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule`, `Conway/Era.hs:283-284`; `Rules/Ledger.hs:383-391`;
    `Shelley/Rules/Ledger.hs` `testIncompleteAndMissingWithdrawals`), a VRF key registered twice
    (`hardforkConwayDisallowDuplicatedVRFKeys`, `Shelley/Rules/Pool.hs:264-267`), and the integrity hash's mismatch
    (`Alonzo/Rules/Utxow.hs:283-286`). A pool's cost under minPoolCost (`Shelley/Rules/Pool.hs:251-260`) needs a
    minimum above 0, which the cluster's genesis does not set: a parameter change that the committee and the three
    DReps vote for sets it first. The withdrawals' maps are written in descending key order: the reference lists
    their accounts in key order.
    """
    socket = f"{ENV}/socket/node1/sock"
    b = Builder(name, socket)
    k1, k3 = read_key(b, b.wallet(1)["skey"]), read_key(b, b.wallet(3)["skey"])
    delegators = [read_key(b, f"{ENV}/stake-delegators/delegator{n}/staking.skey") for n in (1, 2)]
    d1_account = eratx.reward_address(delegators[0].hash)
    member_cold, member_hot = seeded_key("coverage committee cold"), seeded_key("coverage committee hot")
    to_protocol_version_11(b, k3, d1_account, member_cold, member_hot)
    require_version(b, 11)
    params = parameters(b)
    if params["minPoolCost"] < MIN_POOL_COST:
        dreps = [("drep", read_key(b, f"{ENV}/drep-keys/drep{n}/drep.skey")) for n in (1, 2, 3)]
        _, voted = propose_and_vote(b, k3, d1_account, params["govActionDeposit"], eratx.param_change_action(
            {MIN_POOL_COST_KEY: MIN_POOL_COST}), [("committee", member_hot)] + dreps)
        await_epoch(b, voted + 2)
        params = parameters(b)
        if params["minPoolCost"] != MIN_POOL_COST:
            raise RuntimeError(f"the parameter change of minPoolCost was not enacted: {params['minPoolCost']}")
    v3 = params["costModels"]["PlutusV3"]
    collateral = small_outputs(b, k1, 1)

    def account(key):
        return eratx.reward_address(key.hash)

    def balance(key):
        address = bech32("stake_test", account(key))
        info = json.loads(b.sh(f"{CLI} query stake-address-info --address {address} --testnet-magic {MAGIC} "
                               f"--socket-path {socket}"))[0]
        if info.get("voteDelegation") is None:
            raise RuntimeError(f"the withdrawal cases need accounts delegated to a DRep: {info}")
        return info["rewardAccountBalance"]

    for _ in range(10):
        balances = [balance(k) for k in delegators]
        if all(balances):
            break
        await_epoch(b, tip(b)["epoch"] + 1)
    if not all(balances):
        raise RuntimeError(f"the withdrawal cases need rewards in both accounts: {balances}")
    w = b.wallet(1)
    here = eratx.enterprise_address(k1.hash)
    cases = []

    def add(label, delta=0, signers=(), **kw):
        tx, _ = eratx.build_tx("conway", inputs=[txin(w)],
                               outputs=[eratx.tx_out("conway", here, w["lovelace"] - FEE + delta)], fee=FEE,
                               signers=[k1, *signers], **kw)
        cases.append(Case(label, tx, expected(label)))

    # ConwayIncompleteWithdrawals: both delegators withdraw one lovelace more than their balance
    wrong = [(account(k), bal + 1) for k, bal in zip(delegators, balances)]
    add("ledger-withdrawal-amounts-wrong-v11", delta=sum(a for _, a in wrong), signers=delegators,
        withdrawals=unsorted_map(sorted(wrong, key=lambda p: account_order(p[0]), reverse=True)))
    # ConwayWithdrawalsMissingAccounts: two script accounts that are not registered; a script account is not one
    # `validateWithdrawalsDelegated` reads (`Rules/Ledger.hs:473-490`)
    scripts = [eratx.sig_script(k1.hash), eratx.all_script(eratx.sig_script(k1.hash))]
    missing = [(eratx.reward_address(eratx.script_hash(s), script=True), n + 1) for n, s in enumerate(scripts)]
    add("ledger-withdrawal-accounts-missing-v11", delta=3, native_scripts=scripts,
        withdrawals=unsorted_map(sorted(missing, key=lambda p: account_order(p[0]), reverse=True)))
    # VRFKeyHashAlreadyRegistered: a new pool registers pool 1's VRF key
    duplicate = seeded_key("constructors v11 pool vrf")
    add("pool-vrf-key-registered-v11", signers=[duplicate],
        certs=[pool_params(duplicate, vrf_hash(b, f"{ENV}/pools-keys/pool1/vrf.vkey"), cost=MIN_POOL_COST)])
    # StakePoolCostTooLowPOOL: a new pool's cost is one lovelace under minPoolCost
    cheap = seeded_key("constructors v11 pool cost")
    add("pool-cost-too-low-v11", signers=[cheap],
        certs=[pool_params(cheap, eratx.blake2b(b"constructors v11 vrf cost"), cost=MIN_POOL_COST - 1)])
    # ScriptIntegrityHashMismatch: a Plutus V3 mint whose body's integrity hash is not its data's
    def wrong_integrity(**kw):
        return dict(inputs=[txin(w)], **{**kw, "script_data": eratx.blake2b(b"not the integrity hash")})

    add("utxow-script-integrity-hash-mismatch-v11", **plutus_minting(
        "conway", wrong_integrity, here, w["lovelace"], 3, v3, collateral, [k1]))
    return cases
