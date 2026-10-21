# SPDX-License-Identifier: Apache-2.0
"""Extended Conway governance scenarios (`soak --governance-extended`, the Conway epochs of `e2e`).

They run in a thread of the governance load, next to `EraLoad.conway_features`, with wallets and keys
of their own, and are driven by the state of the reference chain rather than by epoch numbers. Each
one is compared by the ordinary ledger comparison; the ones marked "refused" additionally require the
reference to refuse a transaction with a named predicate failure (`EraLoad.expect_rejected`), and the
refused bytes are kept in `negative-txs.jsonl` so the Scala rules can be replayed on them.

  B2  The pools' reward accounts are registered and delegated to AlwaysAbstain (pool 1),
      AlwaysNoConfidence (pool 2) and a DRep key (pool 3). The base rounds then leave the pools
      without votes on a no-confidence action, and with a single vote on a hard fork and on
      committee updates, so the outcome depends on the default votes. The pool thresholds are first
      lowered to 0.45 by a parameter change, so that no outcome sits on a threshold.
  B3  The committee election carries a committee update whose terms exceed the maximum term, and a
      committee member that expires soon; after `committeeMinSize` is raised to 2 and the member
      has expired, a treasury withdrawal that every voter accepts must stay unratified.
  B4  Treasury withdrawals to a script reward account, and withdrawing from it; a script DRep
      (registered, delegated stake, voting) guarded by a Plutus V3 script; a script stake credential.
  B5  Reference inputs that are also inputs (refused before protocol version 11, accepted without
      scripts at 11, refused for Plutus V3 at 11); votes of committee hot keys that are not elected
      (refused at 11, accepted once elected); pool registrations with a VRF key hash another pool
      holds (refused at 11).
"""
import json
import random

from . import eratx, govview
from .eratx import Key, Tag

ENV = "/work/env"
MAGIC = 1082026
UNITS = [1_000_000, 400_000_000]


class ReferenceDiverged(Exception):
    """The reference accepted a transaction the scenario requires it to refuse. Retrying cannot help (the
    accepted transaction changed the state the case depends on), so the stage ends failed."""


# What `e2e` requires of the light extended set, stage -> the protocol version from which the stage applies.
# A stage whose version the chain did not reach before the run ended is reported in the summary as not
# applicable, with this reason; any other stage must be done, and a stage that ended with ok false fails the run.
LIGHT_STAGES = {"xfund": (0, ""), "xrewards": (0, ""), "xscriptreg": (0, ""),
                "xvrf": (11, "the VRF key-hash uniqueness rule applies from protocol version 11 "
                             "(hardforkConwayDisallowDuplicatedVRFKeys, Shelley/Rules/Pool.hs:265); the chain "
                             "did not reach it")}


def extended_outcome(results, done, max_protocol, negative_unexpected, stages=None):
    """(failures, not applicable): the verdict of `e2e` on the extended stages. `results` is stage -> {"ok", "tries"}
    of its last attempt, `done` the set of finished stages, `max_protocol` the highest protocol version seen.
    Fails on a stage whose last attempt reported ok false, on an applicable stage that is not done and on any
    refusal case the reference did not answer as expected. Pure."""
    stages = LIGHT_STAGES if stages is None else stages
    failures, not_applicable = [], {}
    for key, last in sorted(results.items()):
        if key not in done and not last["ok"]:
            failures.append(f"extended stage {key} reported ok:false (after {last['tries']} tries)"
                            + (f": {last['error']}" if last.get("error") else ""))
    for key, (since, reason) in stages.items():
        if key in done or key in results and not results[key]["ok"]:
            continue
        if max_protocol is None or max_protocol < since:
            not_applicable[key] = reason
        else:
            failures.append(f"extended stage {key} did not complete (protocol version {max_protocol} reached)")
    if negative_unexpected:
        failures.append(f"{negative_unexpected} transactions the reference must refuse were not refused as expected")
    return failures, not_applicable


def extended_pending(ext, stages=None):
    """The applicable stages of the light set that have not finished: `e2e` waits for them after its target epoch."""
    stages = LIGHT_STAGES if stages is None else stages
    return [k for k, (since, _) in stages.items()
            if ext.max_protocol is not None and ext.max_protocol >= since and k not in ext.done
            and ext.attempts.get(k, 0) < ext.TRIES and k not in ext.failed]


def bech32_from_values(hrp, values):
    """BIP 173 bech32 of 5-bit `values`."""
    charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    def polymod(vs):
        generator = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
        chk = 1
        for v in vs:
            top = chk >> 25
            chk = (chk & 0x1ffffff) << 5 ^ v
            for i in range(5):
                if (top >> i) & 1:
                    chk ^= generator[i]
        return chk
    expanded = [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp]
    checksum = polymod(expanded + list(values) + [0] * 6) ^ 1
    values = list(values) + [(checksum >> 5 * (5 - i)) & 31 for i in range(6)]
    return hrp + "1" + "".join(charset[v] for v in values)


def bech32_stake_address(h, script=False, network=0, hrp="stake_test"):
    data = bytes([(0xf0 if script else 0xe0) | network]) + h
    bits = "".join(f"{b:08b}" for b in data)
    bits += "0" * (-len(bits) % 5)
    return bech32_from_values(hrp, [int(bits[i:i + 5], 2) for i in range(0, len(bits), 5)])


class Extended:
    """The extended governance scenarios of one `EraLoad`."""

    def __init__(self, load, full=True):
        from .eraload import Wallet  # eraload imports this module lazily, never the other way round
        self.l = load
        self.full = full
        r = random.Random(23)
        mk = lambda: Key(json.dumps({"cborHex": "5820" + r.randbytes(32).hex()}))
        self.xw = [Wallet(r) for _ in range(5)]
        self.xp = [Wallet(r) for _ in range(2)]  # operators of the VRF scenarios
        self.xcold = [mk() for _ in range(3)]
        self.xhot = [mk() for _ in range(3)]
        self.script = eratx.plutus_always_succeeds()
        self.script_hash = eratx.plutus_script_hash(self.script)
        self.script_account = eratx.reward_address(self.script_hash, script=True)
        self.done = set()
        self.attempts = {}
        self.epochs = {}  # the epoch a stage proposed in
        self.info = {}  # facts the base rounds record (committee election epoch, expiry)
        self.ids = {}
        self.counters = {}
        self.results = {}  # stage -> {"ok", "tries", "error"} of its last attempt
        self.failed = {}  # stages that ended failed for good (stage -> reason), never retried
        self.max_protocol = None  # highest protocol version seen on the reference

    TRIES = 5

    # ------------------------------------------------------------ plumbing
    def log(self, **kv):
        self.l.log({"extended": True, **kv})

    def count(self, key, n=1):
        self.counters[key] = self.counters.get(key, 0) + n
        self.l.stats["x." + key] = self.counters[key]
        self.log(counted=key, total=self.counters[key])

    def stage(self, key, run, tries=TRIES, when=True):
        """Run `run` until it returns something truthy, at most `tries` times; a stage whose precondition
        `when` does not hold yet is not attempted (and costs no try). `run` raising `ReferenceDiverged` ends
        the stage failed for good."""
        if key in self.done or key in self.failed or self.attempts.get(key, 0) >= tries or not when:
            return None
        self.attempts[key] = self.attempts.get(key, 0) + 1
        error = ""
        try:
            result = run()
        except ReferenceDiverged as e:
            result, error = None, str(e)
            self.failed[key] = error
        if result:
            self.done.add(key)
            self.count(key)
        self.results[key] = {"ok": bool(result), "tries": self.attempts[key], "error": error}
        self.log(stage=key, ok=bool(result), tries=self.attempts[key], **({"error": error} if error else {}))
        return result

    def act(self, label, wallet, *a, **kw):
        return self.l.act("conway", label, wallet, *a, fee=kw.pop("fee", 500_000), **kw)

    def collateral(self, wallet):
        """An ADA-only output of `wallet` kept apart as collateral, splitting one off when needed."""
        coll = [u for u in self.l.funds(wallet.address) if not u[2]]
        if len(coll) < 2:
            self.act("xcollateral-split", wallet, [(wallet.address, 40_000_000, None, {})])
            return None
        return coll[-1]

    def plutus_args(self, pp, redeemers):
        models = self.l.v3_models(pp)

        def script_data(red, dat):
            return eratx.script_data_hash(eratx.enc({(t, i): [dd, list(u)] for t, i, dd, u in red}), b"", models)
        return {"plutus": {3: [self.script]}, "redeemers": redeemers, "script_data": script_data}

    def gov(self):
        return self.l.gov_state()

    def committee_json(self):
        return json.loads(self.l.sh(f"cardano-cli conway query committee-state --testnet-magic {MAGIC} "
                                    f"--socket-path {ENV}/socket/node1/sock", timeout=120).stdout)

    def hot_authorised(self):
        """{cold key hash hex: hot key hash hex} of the committee state."""
        out = {}
        for k, v in self.committee_json().get("committee", {}).items():
            status = v["hotCredsAuthStatus"]
            if status["tag"] == "MemberAuthorized":
                out[govview.cred(k).split("-")[-1]] = govview.cred(status["contents"]).split("-")[-1]
        return out

    def param_root(self):
        previous = self.gov()["nextRatifyState"]["nextEnactState"]["prevGovActionIds"]["PParamUpdate"]
        return None if previous is None else eratx.gov_id(previous["txId"], previous["govActionIx"])

    def committee_hot_keys(self):
        """Hot keys of the base committee members that are authorised now (and so may vote)."""
        l = self.l
        authorised = self.hot_authorised()
        keys = []
        for cold, hot in zip(l.cold, l.hot):
            if authorised.get(cold.hash.hex()) == hot.hash.hex():
                keys.append(hot)
        return keys

    def vote_all(self, label, ids, committee=(), dreps=None):
        """DRep (and committee) yes votes on `ids`, signed and sent by xw[1]."""
        l = self.l
        dreps = l.live_dreps() if dreps is None else dreps
        casts = [(eratx.voter("drep", k.hash), [(i, "yes") for i in ids]) for k in dreps]
        casts += [(eratx.voter("committee", k.hash), [(i, "yes") for i in ids]) for k in committee]
        return l.gov_votes(label, self.xw[1], casts, list(dreps) + list(committee))

    # ------------------------------------------------------------ one pass
    def step(self):
        l = self.l
        if "greg" not in l.done or not hasattr(l, "attempts"):
            return
        tip = l.epoch_info()
        epoch = tip["epoch"]
        pp = l.protocol_params()
        pv = pp["protocolVersion"]["major"]
        self.max_protocol = pv if self.max_protocol is None else max(self.max_protocol, pv)
        deposit, drep_deposit = pp["stakeAddressDeposit"], pp["dRepDeposit"]
        self.stage("xfund", lambda: l.act("conway", "xfund", l.delegator(1),
                                          [(w.address, 150_000_000, None, {}) for w in self.xw],
                                          fee=500_000))
        if "xfund" not in self.done:
            return
        self.stage("xrewards", lambda: self.pool_reward_accounts(deposit))
        self.stage("xscriptreg", lambda: self.script_registration(pp, deposit, drep_deposit))
        if not self.full:
            if "xscriptreg" in self.done:
                self.reference_input_cases(pp, pv)
            if pv >= 11:
                self.stage("xvrf", lambda: self.vrf_cases(pp))
            return
        if {"ground2", "gcommittee"} <= l.done and {"xrewards", "xscriptreg"} <= self.done and pv >= 10:
            self.stage("xpc1", lambda: self.pool_threshold_proposal(epoch))
        if "xpc1" in self.done:
            self.stage("xscriptvote", lambda: self.script_drep_vote(pp))
            self.stage("xscriptwithdraw", lambda: self.script_withdrawal(pp), when=bool(self.script_balance()))
        if "xscriptreg" in self.done:
            self.reference_input_cases(pp, pv)
        if pv >= 11:
            self.stage("xvrf", lambda: self.vrf_cases(pp))
        self.unelected_voters(pv, epoch)
        self.committee_cases(pp, pv, epoch)
        self.trace_ratification(epoch)

    def trace_ratification(self, epoch):
        """Once per epoch, what the reference's ratification (the pulser result for the next boundary) decided:
        the evidence that default votes, terms and the committee size changed the outcome."""
        if self.epochs.get("trace") == epoch:
            return
        self.epochs["trace"] = epoch
        g = self.gov()
        ratify = g["nextRatifyState"]

        def short(a):
            return f"{a['txId'][:8]}#{a['govActionIx']}"
        kinds = {short(p["actionId"]): p["proposalProcedure"]["govAction"]["tag"] for p in g["proposals"]}
        self.log(trace=epoch,
                 enacted=[f"{short(e['actionId'])}:{e['proposalProcedure']['govAction']['tag']}"
                          for e in ratify["enactedGovActions"]],
                 expired=[short(e) for e in ratify["expiredGovActions"]],
                 pending=kinds, committee=len(g["committee"]["members"]) if g.get("committee") else None,
                 minSize=g["currentPParams"]["committeeMinSize"],
                 protocol=g["currentPParams"]["protocolVersion"]["major"])

    # ------------------------------------------------------------ B2
    def pool_reward_accounts(self, deposit):
        """Register the reward accounts of the three pools: pool 1 -> always abstain, pool 2 -> always
        no confidence, pool 3 -> the DRep key gdrep[1] (whose default vote is No)."""
        l = self.l
        rk = [l.read_key(f"{ENV}/pools-keys/pool{n}/staking-reward.skey") for n in (1, 2, 3)]
        certs = [eratx.reg_vote_deleg_cert(rk[0].hash, eratx.drep_ref("abstain"), deposit),
                 eratx.reg_vote_deleg_cert(rk[1].hash, eratx.drep_ref("noconfidence"), deposit),
                 eratx.reg_vote_deleg_cert(rk[2].hash, eratx.drep_ref("key", l.gdrep[1].hash), deposit)]
        certs += [eratx.reg_cert(w.stake.hash, deposit) for w in self.xw[:4]]
        self.reward_hashes = [k.hash for k in rk]
        return self.act("xrewards", self.xw[0], [], certs=certs, deposit=7 * deposit,
                        extra_signers=rk + [w.stake for w in self.xw[:4]])

    def pool_threshold_proposal(self, epoch):
        """Lower the pool thresholds (0.45, hard fork and security group stay 0.5) so the default votes
        decide the pool outcome of the later rounds, and send 5 ADA of treasury to the script account (B4).
        The committee and the DReps accept both."""
        l = self.l
        low, half = Tag(30, [9, 20]), Tag(30, [1, 2])
        actions = [eratx.param_change_action({25: [low, low, low, half, half]}, prev=self.param_root()),
                   eratx.treasury_withdrawal_action({self.script_account: 5_000_000})]
        committee = self.committee_hot_keys()
        if not committee:
            return None
        txid = l.propose("xpc1", self.xw[0], actions)
        if not txid:
            return None
        self.epochs["pc1"] = epoch
        ids = [eratx.gov_id(txid, i) for i in range(len(actions))]
        self.ids["pc1"] = ids
        self.log(stage="xpc1", proposed=txid)
        self.vote_all("xpc1-votes", ids, committee)
        return txid

    # ------------------------------------------------------------ B4
    def script_registration(self, pp, deposit, drep_deposit):
        """Register the script stake credential and a script DRep, delegate the script credential to it,
        and give it 30 ADA of stake: three certificates, each witnessed by the Plutus V3 script."""
        sh = self.script_hash
        collateral = self.collateral(self.xw[1])
        if collateral is None:
            return None
        certs = [eratx.reg_cert(sh, deposit, script=True),
                 eratx.drep_reg_cert(sh, drep_deposit, eratx.anchor("https://drep.invalid/script"), script=True),
                 eratx.vote_deleg_cert(sh, eratx.drep_ref("key", sh, script=True), script=True)]
        staked = eratx.base_address(self.xw[1].pay.hash, sh, stake_script=True)
        return self.act("xscriptreg", self.xw[1], [(staked, 30_000_000, None, {})], certs=certs,
                        deposit=deposit + drep_deposit, collateral=[collateral[0]], avoid=[collateral[0]],
                        fee=2_000_000, **self.plutus_args(pp, [(2, 0, 0, UNITS), (2, 1, 0, UNITS), (2, 2, 0, UNITS)]))

    def script_drep_vote(self, pp):
        """The script DRep votes yes on the parameter change and the withdrawal (Voting purpose)."""
        collateral = self.collateral(self.xw[2])
        if collateral is None:
            return None
        votes = eratx.voting_procedures([(eratx.voter("drep", self.script_hash, script=True),
                                          [(i, "yes") for i in self.ids["pc1"]])])
        return self.act("xscriptvote", self.xw[2], [], votes=votes, collateral=[collateral[0]],
                        avoid=[collateral[0]], fee=1_500_000, **self.plutus_args(pp, [(4, 0, 0, UNITS)]))

    def script_balance(self):
        out = self.l.sh(f"cardano-cli conway query stake-address-info --address "
                        f"{bech32_stake_address(self.script_hash, script=True)} --testnet-magic {MAGIC} "
                        f"--socket-path {ENV}/socket/node1/sock").stdout
        info = json.loads(out or "[]")
        return info[0].get("rewardAccountBalance", 0) if info else None

    def script_withdrawal(self, pp):
        """Withdraw what the enacted treasury withdrawal paid into the script account (Rewarding purpose)."""
        balance = self.script_balance()
        if not balance:
            return None
        collateral = self.collateral(self.xw[3])
        if collateral is None:
            return None
        self.log(stage="xscriptwithdraw", balance=balance)
        return self.act("xscriptwithdraw", self.xw[3], [], withdrawals={self.script_account: balance},
                        collateral=[collateral[0]], avoid=[collateral[0]], fee=1_500_000,
                        **self.plutus_args(pp, [(3, 0, 0, UNITS)]))

    # ------------------------------------------------------------ B5: reference inputs
    def reference_input_cases(self, pp, pv):
        l = self.l
        w = self.xw[4]
        key = f"xrefinput{pv}"
        coll = None
        if pv < 11:
            if self.attempts.get(key, 0) < 3 and key not in self.done:
                self.attempts[key] = self.attempts.get(key, 0) + 1
                u = self.spare_output(w)
                if u:
                    ok = l.act("conway", "xrefoverlap-pv10", w, [(w.address, 3_000_000, None, {})], spend_extra=[u],
                               avoid=[u[0]], reference_inputs=[u[0]], fee=500_000,
                               expect_reject=["BabbageNonDisjointRefInputs"])
                    if ok:
                        self.done.add(key)
                        self.count("refoverlap_refused_pv10")
            return
        if key not in self.done and self.attempts.get(key, 0) < 4:
            self.attempts[key] = self.attempts.get(key, 0) + 1
            u = self.spare_output(w)
            if u and l.act("conway", "xrefoverlap-pv11", w, [(w.address, 3_000_000, None, {})], spend_extra=[u],
                           avoid=[u[0]], reference_inputs=[u[0]], fee=500_000):
                self.count("refoverlap_accepted_pv11")
                coll = self.collateral(w)
                u2 = self.spare_output(w) if coll else None
                if coll and u2:
                    mint = {self.script_hash: {b"xv3": 1}}
                    ok = l.act("conway", "xrefoverlap-v3", w, [], mint=mint, spend_extra=[u2], avoid=[u2[0], coll[0]],
                               reference_inputs=[u2[0]], collateral=[coll[0]], fee=1_500_000,
                               expect_reject=["ReferenceInputsNotDisjointFromInputs"],
                               **self.plutus_args(pp, [(1, 0, 0, UNITS)]))
                    if ok:
                        self.done.add(key)
                        self.count("refoverlap_refused_v3_pv11")

    def spare_output(self, wallet):
        """A second ADA-only output of `wallet` (the biggest is the one `act` spends itself)."""
        outs = [u for u in self.l.funds(wallet.address) if not u[2]]
        if len(outs) < 3:
            self.act("xspare-split", wallet, [(wallet.address, 12_000_000, None, {}), (wallet.address, 12_000_000, None, {})])
            return None
        return outs[-1]

    # ------------------------------------------------------------ B5: VRF key-hash uniqueness
    def vrf_cases(self, pp):
        """Pool registrations at protocol version 11: a key hash another pool holds is refused, a free one,
        the pool's own and a scheduled-then-restored one are accepted."""
        l = self.l
        deposit = pp["stakePoolDeposit"]
        pool1_vrf = bytes.fromhex(l.sh(f"cardano-cli node key-hash-VRF --verification-key-file "
                                       f"{ENV}/pools-keys/pool1/vrf.vkey").stdout.strip())
        pool2_vrf = bytes.fromhex(l.sh(f"cardano-cli node key-hash-VRF --verification-key-file "
                                       f"{ENV}/pools-keys/pool2/vrf.vkey").stdout.strip())
        f1, f2 = eratx.blake2b(b"x-vrf-1"), eratx.blake2b(b"x-vrf-2")
        if "xvrf-context" not in self.done:
            self.done.add("xvrf-context")
            l.keep_tx("vrf-txs.jsonl", {"context": "genesis pools", "protocolMajor": pp["protocolVersion"]["major"],
                                        "pools": {l.pool_cold[i].hash.hex(): v.hex()
                                                  for i, v in enumerate((pool1_vrf, pool2_vrf))}})
        a, b = self.xp
        payer = self.xw[2]

        def register(label, op, vrf, cost, refused, fresh):
            params = {"operator": op.pay.hash, "vrf": vrf, "pledge": 0, "cost": cost, "margin": [1, 10],
                      "reward_account": self.xw[3].reward, "owners": [op.stake.hash]}
            cert = eratx.pool_registration_cert(params)
            kw = {"expect_reject": ["VRFKeyHashAlreadyRegistered"]} if refused else {}
            l.last_verdict = None  # set again only when the transaction reaches the reference
            return l.act("conway", label, payer, [], certs=[cert], deposit=deposit if fresh else 0,
                         extra_signers=[op.pay, op.stake], fee=500_000, record="vrf-txs.jsonl", **kw)
        steps = [("xvrf-dup-active", a, pool1_vrf, 400_000, True, True),
                 ("xvrf-new", a, f1, 400_000, False, True),
                 ("xvrf-dup-pool", b, f1, 400_000, True, True),
                 ("xvrf-same", a, f1, 400_001, False, False),
                 ("xvrf-dup-active-rereg", a, pool2_vrf, 400_002, True, False),
                 ("xvrf-schedule", a, f2, 400_003, False, False),
                 ("xvrf-dup-scheduled", b, f2, 400_000, True, True),
                 ("xvrf-restore", a, f1, 400_004, False, False),
                 ("xvrf-freed", b, f2, 400_000, False, True),
                 ("xvrf-dup-rereg", b, f1, 400_001, True, False)]
        for i, (label, op, vrf, cost, refused, fresh) in enumerate(steps):
            if f"{label}" in self.done:
                continue
            if not register(label, op, vrf, cost, refused, fresh):
                verdict = l.last_verdict if refused else None
                if verdict is not None and verdict.kind == "accepted":
                    raise ReferenceDiverged(f"{label}: the reference accepted a pool registration that must be "
                                            f"refused with VRFKeyHashAlreadyRegistered")
                return None  # retried from here in the next pass
            self.done.add(label)
            self.count("vrf_refused" if refused else "vrf_accepted")
        return True

    # ------------------------------------------------------------ B5: unelected committee voters
    def pending_proposals(self):
        return self.gov()["proposals"]

    def pending_info_action(self):
        for p in self.pending_proposals():
            if p["proposalProcedure"]["govAction"]["tag"] == "InfoAction":
                return eratx.gov_id(p["actionId"]["txId"], p["actionId"]["govActionIx"])
        return None

    def unelected_voters(self, pv, epoch):
        """While the committee election is pending (no committee), the hot keys of the proposed members
        are authorised but not elected: from protocol version 11 their votes are refused."""
        l = self.l
        if pv < 11 or "xunelected" in self.done or self.attempts.get("xunelected", 0) >= 4:
            return
        proposals = json.dumps(self.pending_proposals())
        if self.xcold[0].hash.hex() not in proposals or l.committee_members():
            return
        self.attempts["xunelected"] = self.attempts.get("xunelected", 0) + 1
        authorised = self.hot_authorised()
        pairs = [(self.xcold[0], self.xhot[0]), (l.cold[0], l.hot[0])]
        certs = [eratx.auth_committee_cert(c.hash, h.hash) for c, h in pairs if authorised.get(c.hash.hex()) != h.hash.hex()]
        if certs and not self.act("xauth", self.xw[2], [], certs=certs, extra_signers=[c for c, _ in pairs]):
            return
        info = self.pending_info_action()
        if info is None:
            return
        refused = 0
        for name, hot in (("xhot0", self.xhot[0]), ("hot0", l.hot[0])):
            votes = eratx.voting_procedures([(eratx.voter("committee", hot.hash), [(info, "yes")])])
            if self.act(f"xunelected-{name}", self.xw[2], [], votes=votes, extra_signers=[hot],
                        expect_reject=["UnelectedCommitteeVoters"]):
                refused += 1
                self.count("unelected_refused")
        if refused == 2:
            self.done.add("xunelected")

    # ------------------------------------------------------------ B3 / B5: the elected committee
    def committee_election(self, dreps, epoch):
        """The base round that elects a committee after the no-confidence action (called by `fourth_round`):
        three committee updates side by side, and an info action for the committee-vote cases.
          X1 adds xcold[2]; no pool votes it, so the default votes (all No or Abstain) reject it.
          X3 adds xcold[1] with a term beyond `committeeMaxTermLength`; every body accepts it, only the term rejects it.
          X2 elects cold[0] and xcold[0] (expiring in 4 epochs); pool 3 votes yes, pool 1 abstains by default,
             pool 2 defaults to No: only the abstention keeps the ratio at one half of the voting stake.
        Only X2 is enacted."""
        l = self.l
        root = l.committee_root()
        expiry = epoch + 4
        self.info.update(epoch=epoch, expiry=expiry)
        actions = [eratx.update_committee_action({self.xcold[2].hash: epoch + 30}, threshold=(1, 2), prev=root),
                   eratx.update_committee_action({self.xcold[1].hash: epoch + 205}, threshold=(1, 2), prev=root),
                   eratx.update_committee_action({l.cold[0].hash: epoch + 30, self.xcold[0].hash: expiry},
                                                 threshold=(1, 2), prev=root),
                   eratx.info_action()]
        txid = l.propose("gproposals4", l.gw[1], actions)
        if not txid:
            return None
        ids = [eratx.gov_id(txid, i) for i in range(len(actions))]
        casts = [(eratx.voter("drep", k.hash), [(i, "yes") for i in ids]) for k in dreps]
        casts += [(eratx.voter("pool", p.hash), [(ids[1], "yes")]) for p in l.pool_cold]
        casts.append((eratx.voter("pool", l.pool_cold[2].hash), [(ids[2], "yes")]))
        self.count("committee_updates_proposed", 3)
        return l.gov_votes("gproposals4-votes", l.gw[0], casts, dreps + l.pool_cold)

    def committee_cases(self, pp, pv, epoch):
        """Once the committee holds xcold[0]: the elected hot keys vote (accepted); the minimum size
        is raised to 2 together with a withdrawal to the script account; after the member expired a
        withdrawal every voter accepts must stay unratified."""
        l = self.l
        try:
            members = json.dumps(self.gov()["committee"])
        except Exception:
            return
        elected = self.xcold[0].hash.hex() in members and "expiry" in self.info
        if not elected:
            return
        # an epoch boundary drops the hot keys of everyone who is not a member then: authorise afresh
        pairs = [(l.cold[0], l.hot[0]), (self.xcold[0], self.xhot[0])]
        authorised = self.hot_authorised()
        missing = [(c, h) for c, h in pairs if authorised.get(c.hash.hex()) != h.hash.hex()]
        if missing:
            self.act("xauth-elected", self.xw[2], [], certs=[eratx.auth_committee_cert(c.hash, h.hash) for c, h in missing],
                     extra_signers=[c for c, _ in missing])
            authorised = self.hot_authorised()
        hot = [h for c, h in pairs if authorised.get(c.hash.hex()) == h.hash.hex()]
        if "xminsize" not in self.done and len(hot) == 2:
            actions = [eratx.param_change_action({27: 2}, prev=self.param_root()),
                       eratx.treasury_withdrawal_action({self.script_account: 3_000_000}), eratx.info_action()]
            txid = self.stage("xminsize", lambda: l.propose("xminsize", self.xw[0], actions))
            if txid:
                ids = [eratx.gov_id(txid, i) for i in range(len(actions))]
                self.ids["minsize"] = ids
                self.epochs["minsize"] = epoch
                if self.vote_all("xminsize-votes", ids, hot):
                    self.count("committee_votes_accepted", len(hot))
        expiry = self.info["expiry"]
        if ("xminsize" in self.done and "xstuck" not in self.done and pp["committeeMinSize"] == 2
                and epoch > expiry + 1):
            actions = [eratx.treasury_withdrawal_action({self.xw[3].reward: 1_000_000})]
            txid = self.stage("xstuck", lambda: l.propose("xstuck", self.xw[0], actions))
            if txid:
                self.ids["stuck"] = [eratx.gov_id(txid, 0)]
                self.epochs["stuck"] = epoch
                self.vote_all("xstuck-votes", self.ids["stuck"], hot[:1])
        if "xstuck" in self.done and epoch > self.epochs["stuck"] and "xstuck-checked" not in self.done:
            pending = json.dumps(self.pending_proposals())
            txid = self.ids["stuck"][0][0].hex()
            self.done.add("xstuck-checked")
            self.log(stage="xstuck-checked", stillPendingOrExpired=txid in pending, committeeMinSize=pp["committeeMinSize"])
