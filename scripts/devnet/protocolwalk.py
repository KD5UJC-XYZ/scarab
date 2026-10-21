# SPDX-License-Identifier: Apache-2.0
"""The Conway protocol versions of the `e2e`: drive the chain from the version Conway starts at to the highest version the
reference supports, one HardFork governance action per version, and report where each one took effect.

It runs inside the extended-governance thread (`govextra.Extended.step`, light set), with that thread's wallets, and it
is the only source of HardFork proposals of the run: `EraLoad.dynamic_forks` removes the scripted hops to 10 and 11 from
the base governance rounds. Versions below Conway come from the update mechanism and need nothing here.

The rules that shape the schedule (cardano-ledger, Conway; the devnet genesis has `govActionLifetime` 1):

* A proposal made in epoch e is ratified at the boundary into e + 2 on the votes cast in e (the ratification result
  of the boundary into e + 1 is the one that counts), and is enacted there. A vote cast later in e + 1 is too late, so a
  hop is proposed and voted in one epoch, early enough in it (`WINDOW`).
* Enacting a HardFork delays every other action of that ratification run, and the next hop must name its predecessor as
  `prev`. A hop proposed in the same epoch as its predecessor would be delayed past its lifetime; one proposed in the
  next epoch, while the predecessor is still pending, is ratified one boundary after it. So hops can follow each other
  one epoch apart, never closer.
* While the protocol version is 9 (the bootstrap phase) DReps and the committee cannot vote and the thresholds of
  DReps are zero: the hop to 10 needs the pools' votes only. From 10 a hop needs the DReps, the committee (which the base
  governance round elects, `gcommittee`) and the pools. Which rules a hop is ratified under is decided by the version in
  force when it is ratified, which for a hop proposed one epoch after its predecessor is the predecessor's.
"""
import json
import time
from dataclasses import dataclass, field

from . import eratx, protocolversions as pvs
from .util import data_root

POST_BOOTSTRAP = 10  # the first version with DRep and committee votes (cardano-ledger HardForks.bootstrapPhase is version 9)
WINDOW = 0.6  # a hop is proposed only in the first 60 % of an epoch, so that its votes land in the same epoch
LOST_PASSES = 3  # consecutive passes a past-due hop must be missing before it is reported lost


class HardForkLost(pvs.ProtocolVersionError):
    """A proposed hop is no longer pending and the chain did not reach its version: it expired or was dropped."""


class WalkStalled(pvs.ProtocolVersionError):
    """The walk could not finish within the time derived from the governance parameters."""


@dataclass
class Hop:
    target: int
    epoch: int  # the epoch it was proposed in
    txid: str
    voted: bool = False
    missing: int = 0
    full: bool = False  # DReps and committee vote, not only the pools

    @property
    def action_id(self):
        return eratx.gov_id(self.txid, 0)


def hop_decision(pv, epoch, in_window, hops, limit, full_ready):
    """What the next pass does: ("done",), ("wait", reason) or ("propose", target, full). `hops` are the pending hops
    in order. Pure."""
    last = hops[-1] if hops else None
    target = last.target + 1 if last else pv + 1
    if target > limit:
        return ("done",)
    if last is not None and epoch <= last.epoch:
        return ("wait", f"the hop to {last.target} was proposed in this epoch")
    ratified_under = last.target if last is not None and last.epoch + 1 == epoch else pv
    full = ratified_under >= POST_BOOTSTRAP
    if full and not full_ready:
        return ("wait", "the committee and the DRep votes are not ready")
    if not in_window:
        return ("wait", "too late in the epoch for the votes to count")
    return ("propose", target, full)


def settle(hops, pv):
    """(still pending, enacted) hops once the chain is at version `pv`. Pure."""
    return [h for h in hops if h.target > pv], [h for h in hops if h.target <= pv]


def lost_hops(hops, pv, epoch, pending_ids):
    """The hops that should have been enacted by now (two boundaries after their epoch) and are not pending any more.
    `pending_ids` are the transaction ids of the proposals the reference still holds. Pure."""
    return [h for h in hops if h.target > pv and epoch >= h.epoch + 2 and h.txid not in pending_ids]


def walk_bound_epochs(lifetime, hops_remaining):
    """Epochs the walk may take from now: for each remaining hop the lifetime of a proposal plus the two boundaries it
    needs, once more for the committee election the hops from 10 depend on, and two epochs of slack. Derived from the
    governance parameters (`govActionLifetime`), not from a stopwatch."""
    return (hops_remaining + 1) * (lifetime + 2) + 2


@dataclass
class ProtocolWalk:
    ext: object  # the govextra.Extended whose wallets and helpers it uses
    plan: pvs.ProtocolPlan | None = None
    hops: list = field(default_factory=list)
    reached: dict = field(default_factory=dict)  # protocol version -> first epoch seen in force
    enacted: list = field(default_factory=list)
    failure: Exception | None = None
    delegations_epoch: int | None = None
    bound_epochs: int | None = None
    started: float | None = None
    complete: bool = False
    genesis: dict | None = None

    # ------------------------------------------------------------ state
    def log(self, **kv):
        self.ext.log(walk=True, **kv)

    def fail(self, error):
        self.failure = error
        self.log(failed=type(error).__name__, error=str(error))

    def finished(self, pv):
        return self.plan is not None and pv >= self.plan.limit

    def pending(self):
        """True while the run must keep waiting for the walk: it has neither reached the limit nor failed."""
        return self.failure is None and not self.complete

    def shelley(self):
        if self.genesis is None:
            self.genesis = json.loads((data_root(self.ext.l.name) / "genesis" / "shelley-genesis.json").read_text())
        return self.genesis

    def epoch_length(self):
        return self.shelley()["epochLength"]

    def epoch_seconds(self):
        return self.epoch_length() * float(self.shelley()["slotLength"])

    def deadline_seconds(self):
        """Seconds the walk may take from its start, from the derived bound in epochs; None before it is known."""
        if self.bound_epochs is None:
            return None
        return self.bound_epochs * self.epoch_seconds()

    def overdue(self):
        d = self.deadline_seconds()
        return d is not None and self.started is not None and time.monotonic() - self.started > d

    def summary(self):
        return {"plan": self.plan.summary() if self.plan else None,
                "hops": [{"target": h.target, "proposedEpoch": h.epoch, "txid": h.txid, "votedByDRepsAndCommittee": h.full,
                          "voted": h.voted} for h in self.hops + self.enacted],
                "firstEpochInForce": {str(k): v for k, v in sorted(self.reached.items())},
                "boundEpochs": self.bound_epochs,
                "failure": None if self.failure is None else f"{type(self.failure).__name__}: {self.failure}",
                "complete": self.complete}

    # ------------------------------------------------------------ one pass
    def step(self, tip, pp):
        """One pass of the extended thread: settle the hops, then propose and vote the next one when the rules allow."""
        if self.failure is not None:
            return
        pv, epoch = pp["protocolVersion"]["major"], tip["epoch"]
        self.reached.setdefault(pv, epoch)
        if self.plan is None and not self.determine_plan(pv, pp, epoch):
            return
        self.hops, done = settle(self.hops, pv)
        self.enacted += [h for h in done if h not in self.enacted]
        if self.finished(pv):
            self.complete = True
            return
        self.note_delegations(epoch)
        due = any(h.target > pv and epoch >= h.epoch + 2 for h in self.hops)
        missing = lost_hops(self.hops, pv, epoch, self.pending_ids() if due else set())
        for h in self.hops:
            h.missing = h.missing + 1 if h in missing else 0
            if h.missing >= LOST_PASSES:
                self.fail(HardForkLost(f"the hop to protocol version {h.target} (proposed in epoch {h.epoch}, "
                                       f"tx {h.txid}) is not pending any more in epoch {epoch} and the chain is at "
                                       f"version {pv}"))
                return
        for h in self.hops:
            if not h.voted and epoch == h.epoch:
                h.voted = self.vote(h, pv)
        if "xrewards" not in self.ext.done:
            return
        slot_in_epoch = tip.get("slotInEpoch", 0)
        in_window = slot_in_epoch <= WINDOW * self.epoch_length()
        action = hop_decision(pv, epoch, in_window, self.hops, self.plan.limit, self.full_ready(epoch))
        if action[0] == "propose":
            self.propose(action[1], action[2], pv, epoch)

    def determine_plan(self, pv, pp, epoch):
        """Ask the reference for its highest version once the tip is in Conway; the guard's verdict is logged at once."""
        l = self.ext.l
        try:
            eras = data_root(l.name) / "eras.json"  # written when up-eras returns, after the first Conway block
            first = pvs.conway_start_version(json.loads(eras.read_text())) if eras.exists() else pv
            with l.lsq_factory() as session:
                session.acquire()
                reference_max = pvs.max_protocol_version(session)
            self.plan = pvs.ProtocolPlan.make(first, reference_max)
        except pvs.ProtocolVersionError as e:
            self.fail(e)
            return False
        except (RuntimeError, EOFError, OSError) as e:  # no answer at all from the socket
            self.fail(pvs.MaxProtocolUnknown(f"the reference's protocol versions could not be read: {e!r}"))
            return False
        lifetime = pp["govActionLifetime"]
        self.bound_epochs = walk_bound_epochs(lifetime, max(self.plan.limit - pv, 0))
        self.started = time.monotonic()
        self.log(plan=self.plan.summary(), epoch=epoch, protocol=pv, govActionLifetime=lifetime,
                 boundEpochs=self.bound_epochs)
        return True

    def pending_ids(self):
        """Transaction ids of the proposals the reference holds now."""
        return {p["actionId"]["txId"] for p in self.ext.gov()["proposals"]}

    # ------------------------------------------------------------ the hop
    def note_delegations(self, epoch):
        if self.delegations_epoch is None and {"gdeleg", "gdeleg2"} <= self.ext.l.done:
            self.delegations_epoch = epoch

    def full_ready(self, epoch):
        """DReps with delegated stake in the distribution and an elected, authorised committee: the votes a hop from
        version 10 on needs. A delegation counts from the boundary after its transaction."""
        if self.delegations_epoch is None or epoch <= self.delegations_epoch:
            return False
        return "gcommittee" in self.ext.l.done and bool(self.ext.committee_hot_keys())

    def propose(self, target, full, pv, epoch):
        ext, l = self.ext, self.ext.l
        prev = self.hops[-1].action_id if self.hops else l.hard_fork_root()
        label = f"xhardfork-{target}"
        txid = l.propose(label, ext.xw[0], [eratx.hard_fork_action(target, 0, prev)])
        if not txid:
            self.log(proposalFailed=target, epoch=epoch)
            return
        hop = Hop(target, epoch, txid, full=full)
        self.hops.append(hop)
        self.log(proposed=target, epoch=epoch, protocol=pv, txid=txid, prev=None if prev is None else prev[0].hex(),
                 votedBy="pools, DReps and committee" if full else "pools")
        hop.voted = self.vote(hop, pv)

    def vote(self, hop, pv):
        """The yes votes the hop needs, in one transaction of the extended thread's voter wallet."""
        ext, l = self.ext, self.ext.l
        ids = [hop.action_id]
        casts = [(eratx.voter("pool", k.hash), [(i, "yes") for i in ids]) for k in l.pool_cold]
        signers = list(l.pool_cold)
        if hop.full:
            committee = ext.committee_hot_keys()
            dreps = l.live_dreps()
            casts += [(eratx.voter("drep", k.hash), [(i, "yes") for i in ids]) for k in dreps]
            casts += [(eratx.voter("committee", k.hash), [(i, "yes") for i in ids]) for k in committee]
            signers += list(dreps) + list(committee)
        ok = bool(l.gov_votes(f"xhardfork-{hop.target}-votes", ext.xw[1], casts, signers))
        self.log(voted=hop.target, ok=ok, protocol=pv)
        return ok



def walk_failures(walk):
    """The run's verdict on the walk: a failure of the walk itself, the coverage guard's findings, and a chain that did
    not get to the version the walk was to reach in the time derived from the governance parameters. Pure."""
    failures = []
    if walk.failure is not None:
        failures.append(f"protocol walk failed: {type(walk.failure).__name__}: {walk.failure}")
    elif walk.plan is None:
        failures.append("the protocol walk never learned the reference's highest protocol version")
    elif not walk.complete:
        failures.append(str(WalkStalled(
            f"the chain did not reach protocol version {walk.plan.limit} within {walk.bound_epochs} epochs "
            f"(derived from govActionLifetime and the hops left); hops: {[(h.target, h.epoch) for h in walk.hops]}")))
    if walk.plan is not None:
        failures += list(walk.plan.failures)
    return failures
