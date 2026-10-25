# SPDX-License-Identifier: Apache-2.0
"""Unit tests of the protocol-version registry, the coverage guard and the protocol walk (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import dataclasses
import json
import pathlib
import re
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import eratx, govextra, protocolversions as pvs, protocolwalk as walk  # noqa: E402


def check(stage, since, until=None, verified=None, scenarios=("e2e", "soak")):
    return pvs.VersionCheck(stage, since, until, until if verified is None else verified, "why not", "what",
                            scenarios=scenarios)


class VersionCheckTest(unittest.TestCase):
    def test_a_window_applies_inside_it_only(self):
        c = check("a", 9, 10)
        self.assertEqual([v for v in range(8, 13) if c.applies_at(v)], [9, 10])
        self.assertEqual(c.applicability(), "9-10")

    def test_an_open_check_applies_from_its_version_and_covers_up_to_what_was_verified(self):
        c = check("a", 11, verified=11)
        self.assertEqual([v for v in range(9, 14) if c.applies_at(v)], [11, 12, 13])
        self.assertEqual([v for v in range(9, 14) if c.covers(v)], [11])
        self.assertEqual(c.applicability(), ">=11")

    def test_a_bounded_check_is_verified_through_its_last_version(self):
        with self.assertRaises(ValueError):
            pvs.VersionCheck("a", 9, 10, 11, "r", "w")
        with self.assertRaises(ValueError):
            pvs.VersionCheck("a", 9, None, 8, "r", "w")


class RegistryTest(unittest.TestCase):
    def test_the_registry_has_no_problem(self):
        self.assertEqual(pvs.registry_problems(), [])

    def test_the_shipped_registry_covers_every_version_the_pinned_reference_supports(self):
        self.assertEqual(pvs.coverage_failures(9, 11), [])

    def test_a_reference_above_the_registry_fails_the_guard_until_the_registry_is_extended(self):
        failures = pvs.coverage_failures(9, 12)
        self.assertEqual(len(failures), 1)
        self.assertIn("protocol version 12", failures[0])
        self.assertIn("protocolversions.py", failures[0])

    def test_every_uncovered_version_is_reported(self):
        failures = pvs.coverage_failures(9, 14)
        self.assertEqual([f.split()[2] for f in failures], ["12", "13", "14"])

    def test_registering_a_version_as_having_no_checks_satisfies_the_guard(self):
        self.assertEqual(pvs.coverage_failures(9, 12, no_checks={12: "same rules as 11"}), [])

    def test_extending_a_check_to_the_new_version_satisfies_the_guard(self):
        checks = tuple(check(c.stage, c.since, c.until, c.verified_through if c.until is not None else 12)
                       for c in pvs.VERSION_CHECKS)
        self.assertEqual(pvs.coverage_failures(9, 12, checks=checks), [])

    def test_a_check_the_scenario_does_not_run_does_not_cover(self):
        checks = (check("only-soak", 9, verified=11, scenarios=("soak",)),)
        self.assertEqual(len(pvs.coverage_failures(9, 11, checks=checks)), 3)
        self.assertEqual(pvs.coverage_failures(9, 11, checks=checks, scenario="soak"), [])

    def test_the_walk_never_goes_past_the_first_version_the_registry_does_not_know(self):
        self.assertEqual(pvs.walk_limit(9, 11), 11)
        self.assertEqual(pvs.walk_limit(9, 13), 11)
        self.assertEqual(pvs.walk_limit(9, 13, no_checks={12: "x", 13: "y"}), 13)
        self.assertEqual(pvs.walk_limit(9, 13, no_checks={13: "y"}), 11)  # a hole at 12 stops the walk at 11
        self.assertEqual(pvs.walk_limit(9, 9, checks=()), 9)

    def test_problems_in_a_registry_are_named(self):
        checks = (check("a", 9, 10), check("a", 11, verified=11))
        problems = pvs.registry_problems(checks, {10: "dup", 12: " "})
        self.assertIn("check a is registered twice", problems)
        self.assertTrue(any("version 10" in p and "check covers it" in p for p in problems))
        self.assertTrue(any("version 12" in p and "without a reason" in p for p in problems))

    def test_the_stages_the_e2e_judges_come_from_the_registry(self):
        stages = govextra.light_stages()
        self.assertEqual(stages["xvrf"][0], 11)
        self.assertEqual(stages["xrefoverlap-pre11"][0], 9)
        self.assertEqual(stages["xrefoverlap-11"][0], 11)
        self.assertNotIn("xunelected", stages)  # the committee election is not in the e2e
        self.assertEqual(stages["xfund"], (0, ""))
        self.assertEqual(govextra.LIGHT_STAGES, stages)

    def test_check_for_names_the_missing_stage(self):
        self.assertEqual(pvs.check_for("xvrf").since, 11)
        with self.assertRaises(KeyError):
            pvs.check_for("nope")


class NodeMaximumTest(unittest.TestCase):
    def test_the_registry_and_the_nodes_own_maximum_move_together(self):
        """The Scala node refuses blocks above `HardForkConfig.MaxMajorProtVer`; the registry's highest verified version
        must be that constant, or a walk to a version the node cannot follow (or a version never walked to) goes unnoticed."""
        source = (pathlib.Path(__file__).resolve().parents[2] /
                  "consensus/src/main/scala/xyz/kd5ujc/scarab/consensus/HardFork.scala").read_text()
        match = re.search(r"val MaxMajorProtVer: Int = (\d+)", source)
        self.assertIsNotNone(match)
        highest = max(max(c.verified_through for c in pvs.VERSION_CHECKS), max(pvs.NO_VERSION_CHECKS, default=0))
        self.assertEqual(int(match.group(1)), highest)


class FakeSession:
    def __init__(self, agreed=23, answer=11, error=None):
        self.agreed_version, self.answer, self.error, self.asked = agreed, answer, error, []

    def ledger_query(self, era_index, query):
        self.asked.append((era_index, query))
        if self.error:
            raise self.error
        return self.answer


class MaxProtocolTest(unittest.TestCase):
    def test_the_reference_is_asked_get_max_major_protocol_version_in_conway(self):
        s = FakeSession()
        self.assertEqual(pvs.max_protocol_version(s), 11)
        self.assertEqual(s.asked, [(6, [38])])

    def test_an_old_ntc_version_is_refused(self):
        with self.assertRaises(pvs.MaxProtocolUnknown):
            pvs.max_protocol_version(FakeSession(agreed=20))
        with self.assertRaises(pvs.MaxProtocolUnknown):
            pvs.max_protocol_version(types.SimpleNamespace())

    def test_an_answer_that_is_not_a_version_is_refused(self):
        for bad in (None, "11", True, 0, [11]):
            with self.subTest(bad=bad), self.assertRaises(pvs.MaxProtocolUnknown):
                pvs.max_protocol_version(FakeSession(answer=bad))

    def test_a_failed_query_is_refused_with_its_cause(self):
        for error in (RuntimeError("era mismatch"), EOFError("closed")):
            with self.subTest(error=error), self.assertRaises(pvs.MaxProtocolUnknown) as caught:
                pvs.max_protocol_version(FakeSession(error=error))
            self.assertIs(caught.exception.__cause__, error)


class PlanTest(unittest.TestCase):
    ERAS = {"eras": [{"era": "byron", "epoch": 0}, {"era": "conway", "epoch": 10}],
            "protocolMajorByEpoch": {"9": 8, "10": 9}}

    def test_conway_starts_at_the_version_of_its_first_epoch(self):
        self.assertEqual(pvs.conway_start_version(self.ERAS), 9)

    def test_an_eras_file_without_it_is_refused(self):
        for bad in ({}, {"eras": [{"era": "byron", "epoch": 0}], "protocolMajorByEpoch": {}},
                    {"eras": self.ERAS["eras"], "protocolMajorByEpoch": {"9": 8}}):
            with self.subTest(bad=bad), self.assertRaises(pvs.ErasUnreadable):
                pvs.conway_start_version(bad)

    def test_the_plan_walks_to_the_reference_maximum_when_the_registry_knows_it(self):
        plan = pvs.ProtocolPlan.make(9, 11)
        self.assertEqual((plan.limit, plan.failures), (11, ()))
        self.assertEqual(plan.summary(), {"conwayStart": 9, "referenceMax": 11, "walkLimit": 11, "uncovered": []})

    def test_the_plan_of_a_newer_reference_stops_at_the_registry_and_carries_the_guard_verdict(self):
        plan = pvs.ProtocolPlan.make(9, 12)
        self.assertEqual(plan.limit, 11)
        self.assertEqual(len(plan.failures), 1)

    def test_a_maximum_below_where_conway_starts_is_refused(self):
        with self.assertRaises(pvs.MaxProtocolUnknown):
            pvs.ProtocolPlan.make(9, 8)

    def test_the_report_says_what_ran_at_which_version(self):
        results = {"xvrf": {"ok": True, "tries": 2, "protocol": 11},
                   "xrefoverlap-pre11": {"ok": True, "tries": 1, "protocol": 9}}
        report = pvs.version_check_report(results, {"xvrf", "xrefoverlap-pre11"}, 11)
        self.assertEqual(report["xvrf"]["status"], "done")
        self.assertEqual(report["xvrf"]["ranAtProtocol"], 11)
        self.assertEqual(report["xrefoverlap-pre11"]["ranAtProtocol"], 9)
        self.assertEqual(report["xrefoverlap-pre11"]["applies"], "9-10")
        self.assertEqual(report["xrefoverlap-11"]["status"], "missed")  # applicable at 11, never run
        self.assertNotIn("xunelected", report)

    def test_the_report_tells_not_applicable_failed_and_missed_apart(self):
        report = pvs.version_check_report({"xvrf": {"ok": False, "tries": 1, "protocol": 11}}, set(), 10)
        self.assertEqual(report["xvrf"]["status"], "failed")
        self.assertEqual(report["xrefoverlap-11"]["status"], "notApplicable")
        self.assertEqual(report["xrefoverlap-pre11"]["status"], "missed")
        self.assertEqual(pvs.version_check_report({}, set(), None)["xvrf"]["status"], "notApplicable")


class HopDecisionTest(unittest.TestCase):
    NOT_READY = walk.Wait(walk.WaitReason.COMMITTEE_NOT_AUTHORISED, missing=("gcommittee",))

    def decide(self, pv, epoch, hops=(), limit=11, ready=True, window=True):
        asked = []

        def readiness():
            asked.append(True)
            return None if ready else self.NOT_READY
        decision = walk.hop_decision(pv, epoch, window, list(hops), limit, readiness)
        self.asked = bool(asked)
        return decision

    def test_the_first_hop_needs_the_pools_only_while_the_bootstrap_phase_lasts(self):
        self.assertEqual(self.decide(9, 10, ready=False), walk.Propose(10, False))
        self.assertFalse(self.asked)  # the committee state is not queried for a bootstrap hop

    def test_a_late_slot_waits_for_the_next_epoch(self):
        self.assertEqual(self.decide(9, 10, window=False), walk.Wait(walk.WaitReason.LATE_IN_EPOCH, hop=10))

    def test_a_hop_from_version_10_waits_for_the_committee_and_the_dreps(self):
        self.assertEqual(self.decide(10, 14, ready=False), dataclasses.replace(self.NOT_READY, hop=11))
        self.assertEqual(self.decide(10, 14, ready=True), walk.Propose(11, True))

    def test_the_votes_are_reported_before_the_window(self):
        self.assertEqual(self.decide(10, 14, ready=False, window=False).reason, walk.WaitReason.COMMITTEE_NOT_AUTHORISED)

    def test_the_next_hop_is_proposed_one_epoch_after_its_predecessor_never_in_the_same_epoch(self):
        first = walk.Hop(10, 10, "aa")
        self.assertEqual(self.decide(9, 10, [first]), walk.Wait(walk.WaitReason.SAME_EPOCH, hop=10))
        # one epoch later the predecessor is still pending (version 9) and is ratified before the new hop: it is
        # ratified under version 10, whose rules need the DReps and the committee, who cannot vote at version 9
        self.assertEqual(self.decide(9, 11, [first], ready=False).reason, walk.WaitReason.COMMITTEE_NOT_AUTHORISED)

    def test_a_chained_hop_proposed_while_its_predecessor_is_pending_needs_the_votes_of_the_predecessors_version(self):
        pending = walk.Hop(11, 14, "bb")
        self.assertEqual(self.decide(10, 15, [pending], limit=12, ready=True), walk.Propose(12, True))
        self.assertIsInstance(self.decide(10, 15, [pending], limit=12, ready=False), walk.Wait)

    def test_nothing_is_proposed_at_the_limit_or_when_every_hop_is_out(self):
        self.assertEqual(self.decide(11, 20), walk.Done())
        self.assertEqual(self.decide(10, 20, [walk.Hop(11, 19, "cc")]), walk.Wait(walk.WaitReason.ENACTMENT, hop=11))


class FullReadinessTest(unittest.TestCase):
    KEY = types.SimpleNamespace(hash=bytes([9]) * 28)

    def ready(self, done, delegations_epoch=None, epoch=14, keys=(KEY,)):
        return walk.full_readiness(set(done), delegations_epoch, epoch, lambda: list(keys))

    def test_each_precondition_is_reported_in_order(self):
        W = walk.WaitReason
        self.assertEqual(self.ready(set()), walk.Wait(W.DELEGATIONS_NOT_MADE, missing=("gdeleg", "gdeleg2")))
        self.assertEqual(self.ready({"gdeleg"}), walk.Wait(W.DELEGATIONS_NOT_MADE, missing=("gdeleg2",)))
        self.assertEqual(self.ready({"gdeleg", "gdeleg2"}, 14),
                         walk.Wait(W.DELEGATIONS_NOT_IN_DISTRIBUTION, since=14))
        self.assertEqual(self.ready({"gdeleg", "gdeleg2"}, 13),
                         walk.Wait(W.COMMITTEE_NOT_AUTHORISED, missing=("gcommittee",)))
        self.assertEqual(self.ready({"gdeleg", "gdeleg2", "gcommittee"}, 13, keys=()),
                         walk.Wait(W.NO_AUTHORISED_MEMBER))
        self.assertIsNone(self.ready({"gdeleg", "gdeleg2", "gcommittee"}, 13))

    def test_the_committee_state_is_queried_only_once_the_stages_are_done(self):
        def query():
            raise AssertionError("queried")
        self.assertEqual(walk.full_readiness({"gdeleg", "gdeleg2"}, 13, 14, query).reason,
                         walk.WaitReason.COMMITTEE_NOT_AUTHORISED)

    def test_every_wait_reason_has_a_distinct_log_name(self):
        self.assertEqual(len({r.name for r in walk.WaitReason}), len(walk.WaitReason))
        fields = walk.Wait(walk.WaitReason.DELEGATIONS_NOT_IN_DISTRIBUTION, hop=11, since=13).fields()
        self.assertEqual(fields["wait"], "DELEGATIONS_NOT_IN_DISTRIBUTION")
        self.assertEqual((fields["hop"], fields["delegationsEpoch"]), (11, 13))
        json.dumps(fields)


class HopBookkeepingTest(unittest.TestCase):
    def test_settle_splits_enacted_from_pending(self):
        hops = [walk.Hop(10, 10, "a"), walk.Hop(11, 11, "b")]
        pending, enacted = walk.settle(hops, 10)
        self.assertEqual([h.target for h in pending], [11])
        self.assertEqual([h.target for h in enacted], [10])

    def test_a_hop_is_lost_two_epochs_after_its_proposal_when_the_reference_no_longer_holds_it(self):
        hop = walk.Hop(10, 10, "aa")
        self.assertEqual(walk.lost_hops([hop], 9, 11, set()), [])  # the boundary has not passed twice yet
        self.assertEqual(walk.lost_hops([hop], 9, 12, set()), [hop])
        self.assertEqual(walk.lost_hops([hop], 9, 12, {"aa"}), [])  # still pending
        self.assertEqual(walk.lost_hops([hop], 10, 12, set()), [])  # enacted

    def test_the_time_bound_is_derived_from_the_lifetime_and_the_hops_left(self):
        self.assertEqual(walk.walk_bound_epochs(1, 2), 11)
        self.assertEqual(walk.walk_bound_epochs(6, 0), 10)
        self.assertGreater(walk.walk_bound_epochs(6, 2), walk.walk_bound_epochs(1, 2))


class FakeLoad:
    """What the walk uses of `EraLoad`: proposals, votes, the keys, the done stages and a reference session."""

    def __init__(self, name, session):
        self.name, self.session = name, session
        self.done = set()
        self.pool_cold = [types.SimpleNamespace(hash=bytes([i]) * 28) for i in (1, 2, 3)]
        self.proposals, self.votes, self.roots = [], [], None
        self.dreps = [types.SimpleNamespace(hash=bytes([7]) * 28)]
        self.ok = True
        self.epoch_now = 0

    def lsq_factory(self):
        return self.session

    def epoch_info(self):
        return {"epoch": self.epoch_now}

    def propose(self, label, wallet, actions):
        self.proposals.append((label, actions))
        return f"{len(self.proposals):02x}" * 32

    def hard_fork_root(self):
        return self.roots

    def live_dreps(self):
        return self.dreps

    def gov_votes(self, label, wallet, casts, signers):
        self.votes.append((label, casts))
        return "vote" if self.ok else None


class FakeSessionContext(FakeSession):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def acquire(self):
        pass


class WalkTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(self.tmp, ignore_errors=True))
        (self.tmp / "genesis").mkdir()
        (self.tmp / "genesis" / "shelley-genesis.json").write_text('{"epochLength": 500, "slotLength": 0.1}')
        patcher = patch.object(walk, "data_root", lambda name: self.tmp)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.load = FakeLoad("t", FakeSessionContext(answer=11))
        self.proposals = []
        self.ext = types.SimpleNamespace(
            l=self.load, done={"xrewards"}, pv=None, xw=[object() for _ in range(5)], logs=[], committee=[],
            gov=lambda: {"proposals": [{"actionId": {"txId": t, "govActionIx": 0}} for t in self.proposals]},
            committee_hot_keys=lambda: self.ext.committee,
            log=lambda **kv: self.ext.logs.append(kv))
        self.walk = walk.ProtocolWalk(self.ext)
        self.pp = lambda pv: {"protocolVersion": {"major": pv}, "govActionLifetime": 1}

    def step(self, pv, epoch, slot=10):
        self.ext.pv = pv
        self.load.epoch_now = epoch
        self.walk.step({"epoch": epoch, "slotInEpoch": slot}, self.pp(pv))

    def test_the_whole_walk_from_9_to_11(self):
        self.step(9, 10)
        self.assertEqual(self.walk.plan.limit, 11)
        self.assertEqual(self.walk.bound_epochs, walk.walk_bound_epochs(1, 2))
        # the hop to 10, proposed and voted by the three pools in the epoch Conway starts
        (label, actions), = self.load.proposals
        self.assertEqual(label, "xhardfork-10")
        self.assertEqual(actions, [eratx.hard_fork_action(10, 0, None)])
        (_, casts), = self.load.votes
        self.assertEqual([c[0][0] for c in casts], [4, 4, 4])
        self.proposals.append("01" * 32)
        self.step(9, 10)  # the same epoch: nothing more
        self.step(9, 11)
        self.assertEqual(len(self.load.proposals), 1)
        self.assertTrue(self.walk.pending())
        # version 10 is in force: the committee and the DReps are not ready, so the next hop waits
        self.proposals.clear()
        self.step(10, 12)
        self.step(10, 13)
        self.assertEqual(len(self.load.proposals), 1)
        # delegations done in epoch 13, the committee elected and authorised in 14
        self.load.done |= {"gdeleg", "gdeleg2", "gcommittee"}
        self.ext.committee = [types.SimpleNamespace(hash=bytes([9]) * 28)]
        self.step(10, 13)
        self.assertEqual(len(self.load.proposals), 1)  # the delegations count from the next boundary
        self.step(10, 14)
        self.assertEqual(len(self.load.proposals), 2)
        self.assertEqual(self.load.proposals[1][1], [eratx.hard_fork_action(11, 0, None)])
        kinds = sorted(c[0][0] for c in self.load.votes[1][1])
        self.assertEqual(kinds, [0, 2, 4, 4, 4])  # committee, DRep, three pools
        self.proposals.append("02" * 32)
        self.step(10, 15)
        self.step(11, 16)
        self.assertTrue(self.walk.complete)
        self.assertFalse(self.walk.pending())
        self.assertEqual(self.walk.failure, None)
        self.assertEqual(walk.walk_failures(self.walk), [])
        summary = self.walk.summary()
        self.assertEqual([h["target"] for h in summary["hops"]], [10, 11])
        self.assertEqual(summary["firstEpochInForce"], {"9": 10, "10": 12, "11": 16})
        self.assertTrue(summary["complete"])

    def waits(self):
        return [(e["wait"], e.get("hop"), tuple(e.get("missing", ())), e["sinceEpoch"]) for e in self.ext.logs
                if "wait" in e]

    def test_every_pass_that_proposes_nothing_logs_its_typed_reason(self):
        self.ext.done.clear()
        self.step(9, 10)
        self.ext.done.add("xrewards")
        self.step(9, 10)  # proposes the hop to 10
        self.proposals.append("01" * 32)
        self.step(9, 10)
        self.proposals.clear()
        self.step(10, 12)
        self.step(10, 12)
        self.load.done |= {"gdeleg", "gdeleg2"}
        self.step(10, 13)
        self.assertEqual(self.waits(), [
            ("REWARD_ACCOUNTS", None, ("xrewards",), 10),
            ("SAME_EPOCH", 10, (), 10),
            ("DELEGATIONS_NOT_MADE", 11, ("gdeleg", "gdeleg2"), 12),
            ("DELEGATIONS_NOT_MADE", 11, ("gdeleg", "gdeleg2"), 12),
            ("DELEGATIONS_NOT_IN_DISTRIBUTION", 11, (), 13)])
        self.assertEqual(self.walk.summary()["waiting"]["wait"], "DELEGATIONS_NOT_IN_DISTRIBUTION")
        self.assertEqual(self.walk.summary()["waiting"]["delegationsEpoch"], 13)

    def test_a_stalled_walk_names_what_it_waited_for(self):
        self.step(9, 10)
        self.proposals.clear()
        self.step(10, 12)
        self.step(10, 13)
        self.step(10, 13)
        # the same reason over a boundary is one wait: it counts its passes from the epoch it began in
        self.assertEqual((self.walk.waiting.passes, self.walk.waiting.since_epoch), (3, 12))
        (failure,) = walk.walk_failures(self.walk)
        self.assertTrue(failure.startswith("the chain did not reach protocol version 11 within 11 epochs"))
        self.assertIn("waiting since epoch 12 (3 passes)", failure)
        self.assertIn("enacted: [(10, 10)]", failure)
        self.assertIn("DELEGATIONS_NOT_MADE", failure)
        self.assertIn("missing gdeleg, gdeleg2", failure)

    def test_a_boundary_between_the_tip_and_the_parameters_does_not_date_a_version_a_epoch_early(self):
        self.ext.pv = 9
        self.load.epoch_now = 11  # the tip said 10, the parameters are already those of epoch 11
        self.walk.step({"epoch": 10, "slotInEpoch": 499}, self.pp(9))
        self.assertEqual(self.walk.reached[9], 11)

    def test_a_late_slot_proposes_nothing(self):
        self.step(9, 10, slot=400)
        self.assertEqual(self.load.proposals, [])
        self.step(9, 11, slot=5)
        self.assertEqual(len(self.load.proposals), 1)

    def test_nothing_is_proposed_before_the_reward_accounts_exist(self):
        self.ext.done.clear()
        self.step(9, 10)
        self.assertEqual(self.load.proposals, [])
        self.assertIsNotNone(self.walk.plan)

    def test_a_vote_that_failed_is_cast_again_in_the_same_epoch(self):
        self.load.ok = False
        self.step(9, 10)
        self.assertFalse(self.walk.hops[0].voted)
        self.load.ok = True
        self.proposals.append("01" * 32)
        self.step(9, 10)
        self.assertTrue(self.walk.hops[0].voted)

    def test_a_hop_that_disappears_fails_the_walk_with_a_typed_error(self):
        self.step(9, 10)
        for _ in range(walk.LOST_PASSES):
            self.step(9, 12)
        self.assertIsInstance(self.walk.failure, walk.HardForkLost)
        self.assertFalse(self.walk.pending())
        self.assertIn("HardForkLost", walk.walk_failures(self.walk)[0])
        calls = len(self.load.proposals)
        self.step(9, 13)
        self.assertEqual(len(self.load.proposals), calls)  # a failed walk stays quiet

    def test_a_reference_that_does_not_answer_fails_loudly_and_proposes_nothing(self):
        self.load.session = FakeSessionContext(error=RuntimeError("no era"))
        self.step(9, 10)
        self.assertIsInstance(self.walk.failure, pvs.MaxProtocolUnknown)
        self.assertEqual(self.load.proposals, [])
        self.assertIn("MaxProtocolUnknown", walk.walk_failures(self.walk)[0])

    def test_a_newer_reference_walks_to_the_registry_and_fails_the_guard(self):
        self.load.session = FakeSessionContext(answer=12)
        self.step(9, 10)
        self.assertEqual(self.walk.plan.limit, 11)
        failures = walk.walk_failures(self.walk)
        self.assertEqual(len(failures), 2)  # the walk is not complete, and version 12 is uncovered
        self.assertTrue(any("protocol version 12" in f for f in failures))

    def test_eras_json_gives_the_version_conway_starts_at(self):
        (self.tmp / "eras.json").write_text('{"eras": [{"era": "conway", "epoch": 10}], "protocolMajorByEpoch": {"10": 9}}')
        self.step(10, 12)
        self.assertEqual(self.walk.plan.first, 9)

    def test_a_walk_that_never_started_is_a_failure(self):
        self.assertEqual(len(walk.walk_failures(self.walk)), 1)
        self.assertIsNone(self.walk.deadline_seconds())
        self.assertFalse(self.walk.overdue())

    def test_the_deadline_is_the_derived_bound_in_epochs_of_the_genesis(self):
        self.step(9, 10)
        self.assertEqual(self.walk.deadline_seconds(), walk.walk_bound_epochs(1, 2) * 500 * 0.1)
        self.assertFalse(self.walk.overdue())
        self.walk.started -= self.walk.deadline_seconds() + 1
        self.assertTrue(self.walk.overdue())

class BlockedThreadsTest(unittest.TestCase):
    def test_only_a_thread_that_is_still_running_is_reported_with_its_stack(self):
        import threading
        from devnet.scenarios import e2e
        release = threading.Event()

        def blocked_in_a_comparison():
            release.wait(30)
        stuck = threading.Thread(target=blocked_in_a_comparison, daemon=True)
        ended = threading.Thread(target=lambda: None, daemon=True)
        stuck.start()
        ended.start()
        ended.join()
        try:
            stacks = e2e.blocked_stacks({"worker": stuck, "extended": ended, "governance": None})
        finally:
            release.set()
            stuck.join()
        self.assertEqual(sorted(stacks), ["worker"])
        self.assertTrue(any("blocked_in_a_comparison" in line for line in stacks["worker"]))



class GovernanceThreadTest(unittest.TestCase):
    """The base governance pass that the walk's hops from version 10 depend on runs beside the comparisons."""

    def test_the_pass_runs_once_per_conway_epoch_and_the_batch_leaves_it_to_the_thread(self):
        from devnet import eraload
        load = eraload.EraLoad("t", lambda: None, log=lambda m: None)
        tips = iter([{"era": "Babbage", "epoch": 9, "slotInEpoch": 10}, {"era": "Conway", "epoch": 10, "slotInEpoch": 1},
                     {"era": "Conway", "epoch": 10, "slotInEpoch": 5}, {"era": "Conway", "epoch": 10, "slotInEpoch": 9},
                     {"era": "Conway", "epoch": 11, "slotInEpoch": 4}])

        def epoch_info():
            tip = next(tips, None)
            if tip is None:
                load.stop.set()
                return {}
            return tip
        passes, batch = [], []
        load.epoch_info, load.conway_features = epoch_info, passes.append
        with patch.object(eraload, "time", types.SimpleNamespace(sleep=lambda seconds: None)):
            load.start_governance()
            load.governance_thread.join(30)
        self.assertFalse(load.governance_thread.is_alive())
        self.assertEqual(passes, [10, 11])
        load.conway_features, load.transfers, load.once = batch.append, lambda era, n: None, lambda key: False
        load.epoch_batch("conway", 12)
        self.assertEqual(batch, [])

    def test_without_the_thread_the_batch_runs_the_pass(self):
        from devnet import eraload
        load = eraload.EraLoad("t", lambda: None, log=lambda m: None)
        batch = []
        load.conway_features, load.transfers, load.once = batch.append, lambda era, n: None, lambda key: False
        load.epoch_batch("conway", 12)
        self.assertEqual(batch, [12])

if __name__ == "__main__":
    unittest.main()
