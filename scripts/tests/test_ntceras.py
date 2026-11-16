# SPDX-License-Identifier: Apache-2.0
"""The same-point node-to-client battery in every Shelley-based era (`ntcqueries`, `ntceras`), without a devnet."""
import hashlib
import os
import pathlib
import socket
import sys
import time
import tempfile
import threading
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import cbor, ledgercompare, ntceras, ntcqueries, ntcquery  # noqa: E402
from devnet.ntcqueries import Outcome, Row  # noqa: E402
from devnet.scenarios import ntc  # noqa: E402
from devnet.util import FollowerSite  # noqa: E402

CRED = [0, b"\xab" * 28]
POOL = b"\x01" * 28
POINT = [120, b"\x07" * 32]


def universe():
    return ntcqueries.Universe(pools=[POOL, b"\x02" * 28], creds=[tuple(CRED)], dreps=[CRED], drep_keys=[CRED],
                               addresses=[b"\x61" + b"\x00" * 28], txins=[[b"\x11" * 32, 0]],
                               actions=[[b"\x22" * 32, 0]], committee=[CRED])


def block(query):
    """The block query of a hard-fork query `[0, [0, [era, q]]]`."""
    return query[1][1][1]


# The forms the battery gained after it took an era, with the queries the Scala node answers in every Shelley-based era
# since (#924): the proposed parameter updates and the `GetCBOR` forms of the queries that read nothing of the era's own.
ADDED_FORMS = {"GetProposedPParamsUpdates": [4], "GetCBOR/GetLedgerTip": [9, [0]], "GetCBOR/GetEpochNo": [9, [1]],
               "GetCBOR/GetProposedPParamsUpdates": [9, [4]], "GetCBOR/GetGenesisConfig": [9, [11]],
               "GetCBOR/GetAccountState": [9, [29]], "GetCBOR/GetMaxMajorProtocolVersion": [9, [38]]}


class BatteryTest(unittest.TestCase):
    def test_the_conway_battery_is_the_one_the_ntc_scenario_asked_before(self):
        # every label, query byte and mark of the Conway battery, as it was before the battery took an era, in the
        # same order, and the forms added since
        digest = hashlib.sha256()
        added = {}
        for label, query, implemented in ntcqueries.battery(universe()):
            if label in ADDED_FORMS:
                added[label] = (block(query), implemented)
                continue
            digest.update(label.encode() + b"\0" + cbor.enc(query) + bytes([implemented]))
        self.assertEqual(digest.hexdigest(), "ba0a0a090db62b2cc2e7e3833f8485dbea0aabae0b882c725b43b95bcb10493f")
        self.assertEqual(added, {label: (query, True) for label, query in ADDED_FORMS.items()})
        self.assertEqual(cbor.enc([q for _, q, _ in ntcqueries.battery(universe())]),
                         cbor.enc([q for _, q, _ in ntcqueries.battery(universe(), ntcqueries.ERA_CONWAY)]))

    def test_every_block_query_names_the_era_it_is_built_for(self):
        for era in range(1, 7):
            for label, query, _ in ntcqueries.battery(universe(), era) + ntcqueries.legacy_battery(era):
                if not label.startswith(("top:", "hfc:")):
                    self.assertEqual(query[:2], [0, [0, [era, block(query)]]][:2], label)
                    self.assertEqual(query[1][1][0], era, label)

    def test_sets_read_by_the_ledger_decoder_lose_their_tag_before_conway(self):
        def first_argument(era, label):
            return dict((l, q) for l, q, _ in ntcqueries.battery(universe(), era))[label][1][1][1][1]

        for label in ("GetUTxOByAddress", "GetUTxOByTxIn", "GetFilteredDelegationsAndRewardAccounts"):
            self.assertIsInstance(first_argument(5, label), list, label)
            self.assertEqual(cbor.enc(first_argument(6, label))[:3], bytes.fromhex("d90102"), label)
            self.assertEqual(cbor.enc(cbor.Tag(258, first_argument(5, label))), cbor.enc(first_argument(6, label)))
        # a set read with cardano-binary's `fromCBOR` keeps its tag in every era
        for label in ("GetStakePoolParams", "GetStakeDelegDeposits/some", "GetNonMyopicMemberRewards"):
            self.assertEqual(cbor.enc(first_argument(1, label))[:3], bytes.fromhex("d90102"), label)

    def test_before_conway_the_scala_node_implements_the_ledger_states_the_utxo_the_era_free_queries_and_the_refusals(
            self):
        forms = ntcqueries.battery(universe(), 4)
        implemented = {label for label, _, mark in forms if mark}
        answered = {label for label, query, mark in forms
                    if mark and not ntcqueries.conway_only(ntcqueries.inner_of(query), 4)}
        self.assertEqual(answered, {
            "top:GetSystemStart", "top:GetChainBlockNo", "top:GetChainPoint", "hfc:GetInterpreter",
            "hfc:GetCurrentEra", "GetUTxOWhole", "GetUTxOByAddress", "GetUTxOByAddress/none", "GetUTxOByTxIn",
            "GetCBOR/GetUTxOWhole", "DebugChainDepState", "GetCBOR/DebugChainDepState", "DebugEpochState",
            "DebugNewEpochState", "GetCBOR/DebugEpochState", "GetCBOR/DebugNewEpochState", "GetRewardProvenance",
            "GetCBOR/GetRewardProvenance", "GetLedgerTip", "GetEpochNo", "GetProposedPParamsUpdates", "GetGenesisConfig",
            "GetAccountState", "GetMaxMajorProtocolVersion", "GetCBOR/GetLedgerTip", "GetCBOR/GetEpochNo",
            "GetCBOR/GetProposedPParamsUpdates", "GetCBOR/GetGenesisConfig", "GetCBOR/GetAccountState",
            "GetCBOR/GetMaxMajorProtocolVersion"})
        # the queries `requireCG` guards are implemented as the refusal both decoders make before Conway
        self.assertEqual(implemented - answered,
                         {label for label, query, _ in forms if ntcqueries.conway_only(ntcqueries.inner_of(query), 4)})
        self.assertEqual(len(implemented - answered), 18)
        self.assertTrue(all(mark for _, _, mark in ntcqueries.battery(universe(), 6)))

    def test_the_queries_both_decoders_refuse_before_conway(self):
        refused = {label for label, query, _ in ntcqueries.battery(universe(), 3)
                   if ntcqueries.conway_only(ntcqueries.inner_of(query), 3)}
        self.assertEqual({label.split("/")[0] for label in refused}, {
            "GetConstitution", "GetDRepState", "GetDRepStakeDistr", "GetCommitteeMembersState",
            "GetFilteredVoteDelegatees", "GetSPOStakeDistr", "GetProposals", "GetRatifyState", "GetFuturePParams",
            "QueryStakePoolDefaultVote", "GetDRepDelegations"})
        self.assertFalse(any(ntcqueries.conway_only(ntcqueries.inner_of(q), 6)
                             for _, q, _ in ntcqueries.battery(universe(), 6)))
        self.assertFalse(ntcqueries.conway_only(ntcqueries.inner_of([1]), 2))

    def test_the_legacy_forms(self):
        self.assertEqual([(label, block(query)) for label, query, _ in ntcqueries.legacy_battery(2)],
                         [("GetCurrentPParams", [3]), ("GetGenesisConfig", [11]),
                          ("GetCBOR/GetCurrentPParams", [9, [3]]), ("GetCBOR/GetGenesisConfig", [9, [11]])])
        self.assertEqual([(label, implemented) for label, _, implemented in ntcqueries.legacy_battery(5)],
                         [("GetCurrentPParams", False), ("GetGenesisConfig", True),
                          ("GetCBOR/GetCurrentPParams", False), ("GetCBOR/GetGenesisConfig", True)])
        self.assertEqual({implemented for _, _, implemented in ntcqueries.legacy_battery(6)}, {True})


class JudgeTest(unittest.TestCase):
    def outcome(self, implemented, refused, reference, scala, label="GetEpochNo"):
        return ntcqueries.judge(Row(label, 23, implemented, refused, reference, scala))[0]

    def test_each_outcome(self):
        a, b = b"\x82\x04\x01", b"\x82\x04\x02"
        self.assertIs(self.outcome(True, False, a, a), Outcome.MATCHED)
        self.assertIs(self.outcome(True, False, a, b), Outcome.DIFFERS)
        self.assertIs(self.outcome(True, False, a, None), Outcome.SCALA_REFUSED)
        self.assertIs(self.outcome(True, True, None, None), Outcome.REFUSED_BY_BOTH)
        self.assertIs(self.outcome(True, True, None, a), Outcome.SCALA_ANSWERED)
        # a query not implemented is incomplete whatever the Scala node did: equal, other, no answer, a refusal
        for scala in (a, b, None):
            self.assertIs(self.outcome(False, False, a, scala), Outcome.INCOMPLETE)
        for scala in (a, None):
            self.assertIs(self.outcome(False, True, None, scala), Outcome.INCOMPLETE)
        # what the reference does is the battery's own check, whatever the mark
        for implemented in (True, False):
            for scala in (a, None):
                self.assertIs(self.outcome(implemented, False, None, scala), Outcome.REFERENCE_REFUSED)
                self.assertIs(self.outcome(implemented, True, a, scala), Outcome.REFERENCE_ANSWERED)

    def test_an_incomplete_row_neither_agrees_nor_fails(self):
        self.assertEqual({o for o in Outcome if o.agrees}, {Outcome.MATCHED, Outcome.REFUSED_BY_BOTH})
        self.assertEqual({o for o in Outcome if o.incomplete}, {Outcome.INCOMPLETE})
        self.assertEqual({o for o in Outcome if o.fails},
                         set(Outcome) - {Outcome.MATCHED, Outcome.REFUSED_BY_BOTH, Outcome.INCOMPLETE})

    def test_a_ledger_state_is_judged_on_its_framing_too(self):
        # equal bytes that are not a ledger-state answer do not match
        self.assertIs(self.outcome(True, False, b"\x82\x04\x01", b"\x82\x04\x01", "DebugNewEpochState"),
                      Outcome.DIFFERS)


class FakeNode:
    """A node at `POINT` whose state is of the era `era` and protocol major version `major`: it answers every query
    with a value naming it, ends the connection on a block query whose tag is in `closes`, and refuses to acquire
    once `lost` is set. `events` records, in order, each acquisition and each query sent (shared by two nodes)."""

    def __init__(self, era, closes=(), answers=None, version=23, major=7, events=None, name="node"):
        self.era, self.closes, self.answers, self.version = era, set(closes), answers or {}, version
        self.major, self.name = major, name
        self.sent, self.opened, self.lost, self.sessions = [], 0, False, []
        self.events = [] if events is None else events

    def answer(self, query):
        if query == [3]:
            return POINT
        if query == [0, [2, [1]]]:
            return self.era
        if query[:1] == [0] and query[1][:1] == [0]:
            era, q = query[1][1]
            tag = ntcqueries.inner(q)[0]
            if tag in self.closes:
                return None
            if tag in self.answers:
                return self.answers[tag]
            if q == [3]:
                # the protocol version where the ledger's encoding of the parameters has it
                pparams = [0] * 20
                pparams[14 if era < 5 else 12] = [self.major, 0]
                return [pparams]
            if tag in (8, 12):
                # a ledger-state answer as `ledgercompare` frames one: an epoch state of 4 fields, a new one of 7
                state = [era] * (4 if tag == 8 else 7)
                return [cbor.Tag(24, cbor.enc(state)) if q[0] == 9 else state]
            return [[era, tag]]
        return [query[0]]


class FakeSession:
    nodes = {}

    def __init__(self, container, socket, magic, host=False, version=None, deadline=None, until=None):
        self.node, self.version, self.until = self.nodes["scala" if host else "reference"], version, until
        self.closed, self.pending, self.last_raw = False, None, None

    def __enter__(self):
        if self.until is not None and time.monotonic() >= self.until:
            raise ntcquery.Deadline("the session's deadline passed before it started")
        self.node.opened += 1
        self.node.sessions.append(self)
        self.agreed_version = self.version or self.node.version
        return self

    def __exit__(self, *_):
        self.closed = True

    def acquire(self, point=None):
        if self.node.lost:
            raise ntcquery.AcquireRefused(0)
        self.node.events.append(("acquire", self.node.name, point))

    def _send(self, protocol, payload):
        if self.closed:
            raise BrokenPipeError()
        self.pending = ntcquery.decode_all(payload)[1]
        self.node.sent.append((self.agreed_version, self.pending))
        self.node.events.append(("query", self.node.name, self.pending))

    def _recv(self, protocol):
        value = self.node.answer(self.pending)
        if value is None:
            self.closed = True
            raise EOFError("node closed the connection")
        self.last_raw = cbor.enc([4, value])
        return [4, value]

    def query_raw(self, query):
        self._send(7, cbor.enc([3, query]))
        return self._recv(7)[1]


class SamePointTest(unittest.TestCase):
    UTXO = {(b"\x11" * 32, 0): [b"\x61" + b"\x01" * 28, 5]}

    def run_round(self, reference, scala):
        FakeSession.nodes = {"reference": reference, "scala": scala}
        with mock.patch.object(ntcquery, "LocalStateQuery", FakeSession):
            return ntcqueries.same_point("n", "ref.sock", "scala.sock", legacy=ntcqueries.legacy_battery)

    def nodes(self, era):
        before = era < ntcqueries.ERA_CONWAY
        answers = {16: [[POOL]], 22: [{tuple(CRED): 2000000}], 7: [self.UTXO], 28: [{}], 25: [{}], 26: [{}],
                   31: [[]], 27: [[{}, None, 0]]}
        refused = ntcqueries.CONWAY_ONLY if before else ()
        scala_closes = set(refused)
        if before:
            scala_closes |= {2, 3, 10, 16, 17, 18, 19, 20, 22, 24, 34, 36, 37}
        events = []
        return (FakeNode(era, refused, answers, events=events, name="reference"),
                FakeNode(era, scala_closes, answers, events=events, name="scala"))

    def test_a_babbage_round_asks_babbage_queries_and_judges_every_row(self):
        reference, scala = self.nodes(5)
        rnd = self.run_round(reference, scala)
        self.assertEqual((rnd.point, rnd.era, rnd.major, rnd.version), (POINT, 5, 7, 23))
        block_eras = {q[1][1][0] for _, q in reference.sent + scala.sent if q[:1] == [0] and q[1][:1] == [0]}
        self.assertEqual(block_eras, {5})
        outcomes = {(row.label, row.version): ntcqueries.judge(row)[0] for row in rnd.rows}
        self.assertFalse([k for k, o in outcomes.items() if o.fails])
        self.assertIs(outcomes[("GetUTxOByTxIn", 23)], Outcome.MATCHED)
        self.assertIs(outcomes[("GetRewardProvenance", 23)], Outcome.MATCHED)
        self.assertIs(outcomes[("GetCBOR/GetRewardProvenance", 23)], Outcome.MATCHED)
        self.assertIs(outcomes[("GetEpochNo", 23)], Outcome.MATCHED)
        self.assertIs(outcomes[("GetCBOR/GetMaxMajorProtocolVersion", 23)], Outcome.MATCHED)
        self.assertIs(outcomes[("GetCurrentPParams", 23)], Outcome.INCOMPLETE)
        self.assertIs(outcomes[("GetConstitution", 23)], Outcome.REFUSED_BY_BOTH)
        self.assertIs(outcomes[("GetGenesisConfig", 20)], Outcome.MATCHED)
        self.assertIs(outcomes[("GetCurrentPParams", 20)], Outcome.INCOMPLETE)
        # the protocol version was read, then the arguments, with queries both decoders take before Conway: the
        # pools, the registered credentials and the UTxO; then the battery's first form
        blocks = [q[1][1][1][0] for _, q in reference.sent if q[:1] == [0] and q[1][:1] == [0]]
        self.assertEqual(blocks[:5], [3, 16, 22, 7, 0])
        # the legacy forms were asked at V20 too, on sessions of that version
        self.assertEqual([block(q) for v, q in reference.sent if v == ntcqueries.LEGACY_VERSION],
                         [[3], [11], [9, [3]], [9, [11]]])
        # the sessions at the round's point: the reference's main one, one for each query it refuses in the widest
        # battery, and one at V20; the Scala node's one for each query it refuses (its probe, which chose the point,
        # acquired its tip), and one at V20 for the legacy forms it answers. All of them acquired before the first
        # block query was sent to either node
        refused = sum(1 for _, q, _ in ntcqueries.battery(ntcqueries.WIDEST, 5)
                      if ntcqueries.conway_only(ntcqueries.inner_of(q), 5))
        self.assertEqual(refused, 18)
        self.assertEqual(reference.opened, 1 + refused + 1)
        events = reference.events
        first_block = next(i for i, e in enumerate(events) if e[0] == "query" and e[2][:1] == [0] and e[2][1][:1] == [0])
        at_point = [i for i, e in enumerate(events) if e[0] == "acquire" and e[2] == POINT]
        self.assertEqual(len(at_point), reference.opened + refused + 1)
        self.assertLess(max(at_point), first_block)
        self.assertEqual({tuple(e[2] or []) for e in events if e[0] == "acquire" and e[1] == "reference"},
                         {tuple(POINT)})
        # a query the Scala node does not implement is asked of it on a session of its tip, one each, as it ends the
        # connection on every one: at V23 and at V20
        not_implemented = [row for row in rnd.rows if not row.implemented]
        self.assertEqual(len(not_implemented), 30)
        at_tip = [e for e in events if e[0] == "acquire" and e[1] == "scala" and e[2] is None]
        self.assertEqual(len(at_tip), 1 + 30)
        self.assertEqual(scala.opened, 1 + refused + 30 + 1)

    def test_a_conway_round_compares_every_row(self):
        reference, scala = self.nodes(6)
        reference.major = scala.major = 10
        rnd = self.run_round(reference, scala)
        self.assertEqual({ntcqueries.judge(row)[0] for row in rnd.rows}, {Outcome.MATCHED})
        self.assertEqual(rnd.major, 10)

    def test_an_error_after_the_acquisitions_keeps_the_rows_asked_before_it(self):
        reference, scala = self.nodes(5)
        scala.answers = {**scala.answers, 7: [[b"other"]]}

        class Slow(FakeNode):
            def answer(self, query):
                if query[:1] == [0] and query[1][:1] == [0] and query[1][1][1] == [12]:
                    raise ntcquery.Deadline("no answer")
                return super().answer(query)

        slow = Slow(5, scala.closes, scala.answers, events=scala.events, name="scala")
        with self.assertRaises(ntcqueries.RoundError) as raised:
            self.run_round(reference, slow)
        partial = raised.exception.round
        self.assertEqual(raised.exception.form[0], "DebugNewEpochState")
        outcomes = {row.label: ntcqueries.judge(row)[0] for row in partial.rows}
        self.assertIs(outcomes["GetUTxOWhole"], Outcome.DIFFERS)
        self.assertNotIn("DebugNewEpochState", outcomes)
        # every session was closed
        self.assertTrue(all(s.closed for s in reference.sessions + slow.sessions))

    def test_an_ntc_round_that_ended_early_counts_its_rows_and_the_query_it_ended_on(self):
        reference, scala = self.nodes(6)

        class Slow(FakeNode):
            def answer(self, query):
                if query[:1] == [0] and query[1][:1] == [0] and query[1][1][1] == [12]:
                    raise ntcquery.Deadline("no answer")
                return super().answer(query)

        FakeSession.nodes = {"reference": reference,
                             "scala": Slow(6, scala.closes, scala.answers, events=scala.events, name="scala")}
        with mock.patch.object(ntcquery, "LocalStateQuery", FakeSession), \
                mock.patch("builtins.print"):
            point, results = ntcqueries.compare_round("n", "ref.sock", "scala.sock")
        labels = [label for label, _, _, _, _ in results]
        self.assertEqual(labels[-1], "DebugNewEpochState")
        self.assertEqual(results[-1], ("DebugNewEpochState", True, False, 0, 0))
        self.assertTrue(all(matched for _, _, matched, _, _ in results[:-1]))
        # the rows asked before it, in the battery's order, and none after it
        self.assertEqual(labels[-2:], ["DebugEpochState", "DebugNewEpochState"])
        self.assertNotIn("GetCBOR/DebugEpochState", labels)

    def test_an_ntc_round_whose_sessions_were_not_acquired_counts_as_not_matched(self):
        reference, scala = self.nodes(6)
        reference.lost = True
        FakeSession.nodes = {"reference": reference, "scala": scala}
        with mock.patch.object(ntcquery, "LocalStateQuery", FakeSession), mock.patch("builtins.print"):
            point, results = ntcqueries.compare_round("n", "ref.sock", "scala.sock")
        self.assertEqual((point, results), (None, [(ntcqueries.LOST_ROUND, True, False, 0, 0)]))
        # and the scenario counts it: a query that differs, which fails its verdict
        stats = {}
        clock = iter([0, 1])
        args = type("Args", (), {"name": "n"})()
        with mock.patch.object(ntc, "compare_round", return_value=(point, results)), \
                mock.patch.object(ntc.time, "monotonic", side_effect=lambda: next(clock)), \
                mock.patch.object(ntc.time, "sleep"), mock.patch("builtins.print"):
            rounds = ntc.query_rounds(args, pathlib.Path("."), stats, 0.5, 0)
        self.assertEqual(rounds, 1)
        self.assertEqual(stats[ntcqueries.LOST_ROUND]["matched"], 0)
        self.assertEqual(stats[ntcqueries.LOST_ROUND]["total"], 1)

    def test_every_failure_before_the_first_row_loses_the_round(self):
        # the Scala node's connection, the reference's acquisition, a malformed answer on the way: each a lost round,
        # which the ntc scenario counts as a query not matched
        class Refusing(FakeSession):
            def __enter__(self):
                if self.node.name == "scala":
                    raise ConnectionRefusedError("no listener")
                return super().__enter__()

        class Slow(FakeSession):
            def acquire(self, point=None):
                if self.node.name == "reference":
                    raise ntcquery.Deadline("no answer")
                super().acquire(point)

        class Malformed(FakeSession):
            def query_raw(self, query):
                if query == [0, [2, [1]]] and self.node.name == "reference":
                    raise ntcquery.MalformedReply("not a result")
                return super().query_raw(query)

        for session in (Refusing, Slow, Malformed):
            with self.subTest(session=session.__name__):
                reference, scala = self.nodes(6)
                FakeSession.nodes = {"reference": reference, "scala": scala}
                with mock.patch.object(ntcquery, "LocalStateQuery", session):
                    with self.assertRaises(ntcqueries.PointLost):
                        ntcqueries.same_point("n", "ref.sock", "scala.sock")
                    with mock.patch("builtins.print"):
                        self.assertEqual(ntcqueries.compare_round("n", "ref.sock", "scala.sock"),
                                         (None, [(ntcqueries.LOST_ROUND, True, False, 0, 0)]))
                self.assertTrue(all(s.closed for s in reference.sessions + scala.sessions))

    def test_the_round_ends_by_its_deadline(self):
        reference, scala = self.nodes(5)
        FakeSession.nodes = {"reference": reference, "scala": scala}
        with mock.patch.object(ntcquery, "LocalStateQuery", FakeSession), mock.patch.object(ntcqueries, "DEADLINE", 0):
            with self.assertRaises(ntcqueries.PointLost) as raised:
                ntcqueries.same_point("n", "ref.sock", "scala.sock")
        self.assertIsInstance(raised.exception.__cause__, ntcquery.Deadline)

    def test_a_point_lost_while_the_sessions_are_acquired_closes_every_one(self):
        reference, scala = self.nodes(5)

        class Losing(FakeSession):
            def acquire(self, point=None):
                if len(self.node.sessions) > 4:
                    raise ntcquery.AcquireRefused(1)
                super().acquire(point)

        FakeSession.nodes = {"reference": reference, "scala": scala}
        with mock.patch.object(ntcquery, "LocalStateQuery", Losing), self.assertRaises(ntcqueries.PointLost):
            ntcqueries.same_point("n", "ref.sock", "scala.sock", legacy=ntcqueries.legacy_battery)
        self.assertTrue(all(s.closed for s in reference.sessions + scala.sessions))
        self.assertFalse([e for e in reference.events if e[0] == "query" and e[2][:1] == [0] and e[2][1][:1] == [0]])

    def test_the_round_stops_before_any_block_query_when_the_eras_differ(self):
        reference, scala = FakeNode(5), FakeNode(4)
        with self.assertRaises(ntcqueries.Incomparable):
            self.run_round(reference, scala)
        self.assertEqual([q for _, q in reference.sent], [[0, [2, [1]]]])
        self.assertEqual([q for _, q in scala.sent], [[3], [0, [2, [1]]]])

    def test_a_malformed_answer_to_a_query_not_implemented_ends_the_round(self):
        # its value is not judged, but the Scala node's protocol is: an answer that is not a result fails
        reference, scala = self.nodes(5)

        class Malformed(FakeSession):
            def _recv(self, protocol):
                if self.node.name == "scala" and self.pending[:1] == [0] and self.pending[1][:1] == [0] \
                        and self.pending[1][1][1] == [3]:
                    self.last_raw = cbor.enc([9, 9])
                    return [9, 9]
                return super()._recv(protocol)

        scala.closes.discard(3)
        FakeSession.nodes = {"reference": reference, "scala": scala}
        with mock.patch.object(ntcquery, "LocalStateQuery", Malformed):
            with self.assertRaises(ntcqueries.RoundError) as raised:
                ntcqueries.same_point("n", "ref.sock", "scala.sock", legacy=ntcqueries.legacy_battery)
        self.assertEqual((raised.exception.form[0], raised.exception.form[2]), ("GetCurrentPParams", False))
        self.assertIsInstance(raised.exception.__cause__, ntcquery.MalformedReply)

    def test_an_answer_the_scala_node_gets_wrong_differs(self):
        reference, scala = self.nodes(5)
        scala.answers = {**scala.answers, 15: [[b"other"]]}
        rnd = self.run_round(reference, scala)
        outcomes = {row.label: ntcqueries.judge(row)[0] for row in rnd.rows if row.version == 23}
        self.assertIs(outcomes["GetUTxOByTxIn"], Outcome.DIFFERS)

    def test_the_ntc_round_reports_each_query_as_before(self):
        reference, scala = self.nodes(6)
        scala.answers = {**scala.answers, 37: [[b"other"]]}
        FakeSession.nodes = {"reference": reference, "scala": scala}
        with tempfile.TemporaryDirectory() as d, mock.patch.object(ntcquery, "LocalStateQuery", FakeSession):
            ledger_states = {}
            point, results = ntcqueries.compare_round("n", "ref.sock", "scala.sock", dump_dir=pathlib.Path(d),
                                                      ledger_states=ledger_states)
            kept = sorted(p.name for p in pathlib.Path(d).iterdir())
        self.assertEqual(point, POINT)
        by_label = {label: (implemented, matched) for label, implemented, matched, _, _ in results}
        self.assertEqual(by_label["GetStakeDistribution2"], (True, False))
        self.assertEqual(by_label["GetEpochNo"], (True, True))
        self.assertEqual(kept, [f"diff-{label}-120.{node}" for label in ("GetCBOR_GetStakeDistribution2",
                                                                           "GetStakeDistribution2")
                                for node in ("reference", "scala")])
        self.assertEqual(ledger_states["DebugNewEpochState"]["matched"], 1)
        # no V20 forms in the ntc round: its version matrix asks them (`ntcversions`)
        self.assertEqual(len(results), len(ntcqueries.battery(ntcqueries.universe(FakeSession(None, None, None).__enter__(), 6))))

    def test_a_point_the_nodes_no_longer_hold_ends_the_round(self):
        reference, scala = self.nodes(5)
        reference.lost = True
        with self.assertRaises(ntcqueries.PointLost):
            self.run_round(reference, scala)


class AtPointTest(unittest.TestCase):
    def test_a_refusal_takes_the_next_session_and_a_late_one_may_find_the_point_gone(self):
        node = FakeNode(5, closes={23})
        FakeSession.nodes = {"reference": node}
        at = ntcqueries.AtPoint(lambda: FakeSession(None, None, None), POINT, spares=2)
        ntcqueries.acquire_all([at])
        self.assertEqual(len(at.live), 3)
        hfc = ntcqueries.hfc
        self.assertIsNone(at.ask(hfc([23], 5)))
        self.assertIsNone(at.ask(hfc([23], 5)))
        self.assertIsNotNone(at.ask(hfc([1], 5)))
        self.assertIsNone(at.ask(hfc([23], 5)))
        self.assertEqual(at.late, 0)
        node.lost = True
        with self.assertRaises(ntcqueries.PointLost):
            at.ask(hfc([1], 5))

    def test_a_session_that_ended_before_its_query_was_sent_is_not_a_refusal(self):
        FakeSession.nodes = {"reference": FakeNode(5)}
        at = ntcqueries.AtPoint(lambda: FakeSession(None, None, None), POINT)
        ntcqueries.acquire_all([at])
        at.live[0].closed = True
        self.assertIsNotNone(at.ask(ntcqueries.hfc([1], 5)))
        self.assertEqual(len(at.live), 1)

    def test_a_connection_a_full_queue_refuses_is_tried_again(self):
        refusals = iter([BlockingIOError(11, "Resource temporarily unavailable")] * 3)

        class Busy(FakeSession):
            def __enter__(self):
                error = next(refusals, None)
                if error is not None:
                    raise error
                return super().__enter__()

        FakeSession.nodes = {"reference": FakeNode(5)}
        at = ntcqueries.AtPoint(lambda: Busy(None, None, None), POINT)
        with mock.patch.object(ntcqueries, "CONNECT_PAUSE", 0):
            ntcqueries.acquire_all([at])
        self.assertEqual(len(at.live), 1)
        self.assertEqual(FakeSession.nodes["reference"].opened, 1)
        self.assertEqual(at.refused_connects, 3)

    def test_the_scala_node_is_connected_to_at_most_four_sessions_at_once(self):
        # each acquisition waits until four are in progress together, which only the gate's width lets happen; a
        # fifth one let through would raise the peak
        together = threading.Barrier(ntcqueries.SCALA_CONNECTIONS, timeout=10)

        class Counting(FakeSession):
            active = peak = 0
            lock = threading.Lock()

            def acquire(self, point=None):
                with Counting.lock:
                    Counting.active += 1
                    Counting.peak = max(Counting.peak, Counting.active)
                together.wait()
                with Counting.lock:
                    Counting.active -= 1

        FakeSession.nodes = {"scala": FakeNode(5)}
        gate = threading.BoundedSemaphore(ntcqueries.SCALA_CONNECTIONS)
        at = ntcqueries.AtPoint(lambda: Counting(None, None, None, host=True), POINT, spares=7, gate=gate)
        ntcqueries.acquire_all([at])
        self.assertEqual(len(at.live), 8)
        self.assertEqual(Counting.peak, ntcqueries.SCALA_CONNECTIONS)

    def test_no_session_is_asked_or_acquired_past_the_rounds_deadline(self):
        FakeSession.nodes = {"reference": FakeNode(5, closes={23})}
        at = ntcqueries.AtPoint(lambda: FakeSession(None, None, None), POINT, spares=3, expires=float("inf"))
        ntcqueries.acquire_all([at])
        at.expires = 0
        with self.assertRaises(ntcquery.Deadline):
            at.ask(ntcqueries.hfc([1], 5))
        # nor acquired after it: an acquisition the deadline passes is a lost point
        late = ntcqueries.AtPoint(lambda: FakeSession(None, None, None), POINT, spares=3, expires=0)
        with self.assertRaises(ntcqueries.PointLost):
            ntcqueries.acquire_all([late], expires=0)
        self.assertEqual(late.live, [])

    def test_a_session_writes_nothing_past_its_deadline(self):
        session = ntcquery.LocalStateQuery(None, "node.socket", 1, host=True, deadline=5)
        near, far = socket.socketpair()
        self.addCleanup(near.close)
        self.addCleanup(far.close)
        session.proc = type("Transport", (), {"stdin": near, "fileno": near.fileno})()
        session.expires = 0
        with self.assertRaises(ntcquery.Deadline):
            session._send(7, cbor.enc([3, [1]]))
        far.setblocking(False)
        with self.assertRaises(BlockingIOError):
            far.recv(1)  # nothing was written

    def test_a_write_the_pipe_does_not_take_ends_by_the_deadline(self):
        # a relay's pipe that nobody reads: the session's writes go as far as it takes, and no further
        read_end, write_end = os.pipe()
        self.addCleanup(os.close, read_end)
        stdin = os.fdopen(write_end, "wb", buffering=0)
        self.addCleanup(stdin.close)
        os.set_blocking(write_end, False)
        session = ntcquery.LocalStateQuery("container", "node.socket", 1, deadline=0.2)
        session.proc = type("Transport", (), {"stdin": stdin})()
        session.expires = time.monotonic() + 0.2
        outcome = []

        def send():
            try:
                for _ in range(64):  # more than any pipe holds
                    session._send(7, bytes(12288))
            except ntcquery.Deadline as e:
                outcome.append(e)

        sender = threading.Thread(target=send)
        sender.start()
        sender.join(10)
        self.assertFalse(sender.is_alive())
        self.assertEqual(len(outcome), 1)

    def test_a_write_the_transport_takes_only_after_the_deadline_is_not_made(self):
        # the transport becomes writable, but only once the deadline has passed: nothing is written
        near, far = socket.socketpair()
        self.addCleanup(near.close)
        self.addCleanup(far.close)
        session = ntcquery.LocalStateQuery(None, "node.socket", 1, host=True, deadline=1)
        session.proc = type("Transport", (), {"stdin": near, "fileno": near.fileno, "flush": lambda: None})()
        session.expires = 1
        clock = iter([0, 2])
        with mock.patch.object(ntcquery.time, "monotonic", side_effect=lambda: next(clock)), \
                mock.patch.object(ntcquery.select, "select", return_value=([], [near], [])):
            with self.assertRaises(ntcquery.Deadline):
                session._write(b"frame")
        far.setblocking(False)
        with self.assertRaises(BlockingIOError):
            far.recv(1)

    def test_a_session_ends_by_the_instant_it_was_given_however_late_it_starts(self):
        class Transport:
            def __init__(self, path, timeout=120):
                self.timeout = timeout
                raise ConnectionRefusedError("no listener")

        started = time.monotonic()
        late = ntcquery.LocalStateQuery(None, "node.socket", 1, host=True, until=started - 1)
        with self.assertRaises(ntcquery.Deadline):
            late.__enter__()
        session = ntcquery.LocalStateQuery(None, "node.socket", 1, host=True, until=started + 60)
        with mock.patch.object(ntcquery, "_HostSocket", Transport), self.assertRaises(ConnectionRefusedError):
            session.__enter__()
        # the expiry is the instant given, not one counted from when the session started
        self.assertEqual(session.expires, started + 60)
        self.assertLessEqual(session.deadline, 60)

    def test_a_query_is_not_sent_on_a_session_acquired_past_the_rounds_deadline(self):
        node = FakeNode(5)
        FakeSession.nodes = {"reference": node}
        at = ntcqueries.AtPoint(lambda: Expiring(None, None, None), POINT, expires=float("inf"))

        class Expiring(FakeSession):
            def acquire(self, point=None):
                super().acquire(point)
                at.expires = 0  # the acquisition took the rest of the round

        with self.assertRaises(ntcquery.Deadline):
            at.ask(ntcqueries.hfc([1], 5))
        self.assertEqual(node.sent, [])
        at.close()

    def test_a_host_session_connects_and_writes_within_its_deadline(self):
        timeouts = []

        class Recording:
            def __init__(self, path, timeout=120):
                timeouts.append(timeout)
                raise ConnectionRefusedError("no listener")

        with mock.patch.object(ntcquery, "_HostSocket", Recording):
            for deadline in (7, None):
                with self.assertRaises(ConnectionRefusedError):
                    ntcquery.LocalStateQuery(None, "node.socket", 1, host=True, deadline=deadline).__enter__()
        self.assertEqual(timeouts, [7, 120])

    def test_a_deadline_is_an_error_of_the_round_not_a_refusal(self):
        class Slow(FakeSession):
            def _recv(self, protocol):
                raise ntcquery.Deadline("no answer")

        FakeSession.nodes = {"reference": FakeNode(5)}
        at = ntcqueries.AtPoint(lambda: Slow(None, None, None), POINT)
        ntcqueries.acquire_all([at])
        with self.assertRaises(ntcquery.Deadline):
            at.ask([1])


class EraBatteryTest(unittest.TestCase):
    SAME = b"\x82\x04\x81\x01"

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.dir.name)
        self.site = FollowerSite()

    def tearDown(self):
        self.dir.cleanup()

    def battery(self, rounds):
        return ntceras.EraBattery("n", self.root, [self.site], threading.Event(), rounds=rounds)

    @staticmethod
    def round_of(era, rows, major=5):
        return ntcqueries.Round(POINT, era, major, 23, rows)

    def rounds(self, *outcomes):
        """A `same_point` that gives each of `outcomes` in turn: a round, or an exception to raise. `self.calls`
        counts the rounds asked."""
        calls = iter(outcomes)
        self.calls = 0

        def next_round(*a, **k):
            self.calls += 1
            outcome = next(calls)
            if isinstance(outcome, BaseException):
                raise outcome
            return outcome
        return next_round

    def matching(self, era, major=5):
        return self.round_of(era, [Row("GetUTxOWhole", 23, True, False, self.SAME, self.SAME, 7)], major)

    def differing(self, era, major=5):
        return self.round_of(era, [Row("GetUTxOByTxIn", 23, True, False, b"\x82\x04\x01", b"\x82\x04\x02", 15)],
                             major)

    def test_a_round_reports_its_rows_by_class(self):
        rows = [Row("GetUTxOWhole", 23, True, False, self.SAME, self.SAME, 7),
                Row("GetCurrentPParams", 23, False, False, self.SAME, None, 3),
                Row("GetStakePools", 23, False, False, self.SAME, self.SAME, 16),
                Row("GetConstitution", 23, True, True, None, None, 23)]
        battery = self.battery(self.rounds(self.round_of(4, rows)))
        battery.request("alonzo")
        battery.step(battery.requests.get_nowait())
        report = battery.report(self.site)
        alonzo = report["perEra"]["alonzo"]
        self.assertEqual({k: alonzo[k] for k in ("steps", "rounds", "compared", "matched", "incomplete",
                                                 "refusedByBoth")},
                         {"steps": [5], "rounds": 1, "compared": 1, "matched": 1, "incomplete": 2, "refusedByBoth": 1})
        self.assertEqual(alonzo["incompleteRows"], ["GetCurrentPParams@v23", "GetStakePools@v23"])
        # a query marked not implemented stays incomplete even when the Scala node's answer is equal
        self.assertEqual(report["rounds"][0]["rows"]["GetStakePools@v23"]["outcome"], "incomplete")
        # the other eras have no complete round: each is a failure, and this one's rows are not
        self.assertEqual(battery.failures(self.site),
                         [f"node-to-client battery: no complete round in {era}"
                          for era in ("shelley", "allegra", "mary", "babbage", "conway")])

    def every_era(self, rows_of):
        """A battery with one complete round in each Shelley-based era, its rows `rows_of(era)`."""
        battery = self.battery(self.rounds(*[self.round_of(era, rows_of(era)) for era in range(1, 7)]))
        for era in ntceras.ERA_NAMES.values():
            battery.request(era)
            battery.step(era)
        return battery

    def test_the_verdict_is_incomplete_while_a_query_is_not_implemented_and_never_a_pass(self):
        matched = Row("GetUTxOWhole", 23, True, False, self.SAME, self.SAME, 7)
        missing = Row("GetCurrentPParams", 23, False, False, self.SAME, None, 3)
        passed = self.every_era(lambda era: [matched])
        self.assertEqual((passed.report(self.site)["verdict"], passed.failures(self.site),
                          passed.incomplete(self.site)), ("passed", [], []))
        incomplete = self.every_era(lambda era: [matched] + ([missing] if era < 6 else []))
        report = incomplete.report(self.site)
        self.assertEqual((report["verdict"], incomplete.failures(self.site)), ("incomplete", []))
        self.assertEqual(incomplete.incomplete(self.site),
                         ["node-to-client battery: 5 incomplete rows, the queries the Scala node does not implement "
                          "yet (distinct per era: {'shelley': 1, 'allegra': 1, 'mary': 1, 'alonzo': 1, 'babbage': 1}); "
                          "they are not compared, and the battery does not pass"])
        self.assertIn("INCOMPLETE", ntceras.table(report).splitlines()[0])
        failed = self.every_era(lambda era: [missing, self.differing(era).rows[0]])
        self.assertEqual(failed.report(self.site)["verdict"], "failed")

    def test_the_ratchet_holds_with_the_expected_incomplete_set(self):
        # every era's battery as it is marked today: nothing grows, falls back or shrinks (the ledger states, which
        # need framed answers to match, are left out)
        def rows_of(era):
            forms = [(23, form) for form in ntcqueries.battery(ntcqueries.WIDEST, era)] + \
                [(ntcqueries.LEGACY_VERSION, form) for form in ntcqueries.legacy_battery(era)]
            return [Row(label, version, mark, ntcqueries.conway_only(ntcqueries.inner_of(q), era),
                        None if ntcqueries.conway_only(ntcqueries.inner_of(q), era) else self.SAME,
                        self.SAME if mark and not ntcqueries.conway_only(ntcqueries.inner_of(q), era) else None,
                        ntcqueries.inner(ntcqueries.inner_of(q))[0])
                    for version, (label, q, mark) in forms if not ledgercompare.applies(label)]
        battery = self.every_era(rows_of)
        report = battery.report(self.site)
        self.assertEqual(report["ratchet"], {"grows": [], "fallsBack": [], "shrinks": [], "shrinkTags": []})
        self.assertEqual((report["verdict"], battery.failures(self.site)), ("incomplete", []))
        self.assertEqual([len(report["perEra"][era]["incompleteRows"]) for era in ntceras.ERA_NAMES.values()],
                         [30, 30, 30, 30, 30, 0])
        # and in each era before Conway the expected set is exactly the queries the battery does not mark implemented
        for era in range(1, 6):
            unmarked = {ntcqueries.inner(ntcqueries.inner_of(q))[0]
                        for _, q, mark in ntcqueries.battery(ntcqueries.WIDEST, era) + ntcqueries.legacy_battery(era)
                        if not mark}
            self.assertEqual(unmarked, set(ntcqueries.INCOMPLETE_BEFORE_CONWAY), era)

    def test_an_incomplete_row_the_expectation_does_not_list_fails(self):
        # a query added to the battery that the Scala node does not answer before Conway (tag 5), or one the battery
        # no longer marks answered (tag 14): the incomplete set grows
        for tag, label in ((5, "GetStakeDistribution"), (14, "GetRewardProvenance")):
            with self.subTest(tag=tag):
                battery = self.every_era(lambda era: [self.matching(era).rows[0],
                                                      Row(label, 23, era == 6, False, self.SAME,
                                                          self.SAME if era == 6 else None, tag)])
                report = battery.report(self.site)
                self.assertEqual(report["ratchet"]["grows"],
                                 sorted(f"{era} {label}@v23" for era in ("shelley", "allegra", "mary", "alonzo",
                                                                          "babbage")))
                self.assertEqual(report["verdict"], "failed")
                self.assertTrue([f for f in battery.failures(self.site) if "the incomplete set grows" in f])

    def test_a_row_treated_as_answered_that_is_not_compared_fails(self):
        # the Scala node no longer answers a query the battery marks answered: never incomplete, a fall-back
        battery = self.every_era(lambda era: [Row("GetUTxOWhole", 23, True, False, self.SAME, None, 7)])
        report = battery.report(self.site)
        self.assertEqual(len(report["ratchet"]["fallsBack"]), 6)
        self.assertEqual(report["rounds"][0]["rows"]["GetUTxOWhole@v23"]["outcome"], "scalaRefused")
        self.assertTrue([f for f in battery.failures(self.site) if "fall back" in f])
        # a query `ANSWERED_BEFORE_CONWAY` lists that a battery leaves unmarked falls back too, before Conway
        battery = self.every_era(lambda era: [self.matching(era).rows[0],
                                              Row("DebugChainDepState", 23, era == 6, False, self.SAME,
                                                  self.SAME if era == 6 else None, 13)])
        held = battery.report(self.site)["ratchet"]
        self.assertEqual(len(held["fallsBack"]), 5)
        self.assertEqual(held["grows"], held["fallsBack"])

    def test_a_listed_query_now_compared_and_matched_shrinks_the_set_and_passes(self):
        battery = self.every_era(lambda era: [self.matching(era).rows[0],
                                              Row("GetCurrentPParams", 23, True, False, self.SAME, self.SAME, 3)])
        report = battery.report(self.site)
        self.assertEqual(report["ratchet"]["grows"], [])
        self.assertEqual(report["ratchet"]["fallsBack"], [])
        self.assertEqual(report["ratchet"]["shrinks"],
                         sorted(f"{era} GetCurrentPParams@v23" for era in ("shelley", "allegra", "mary", "alonzo",
                                                                            "babbage")))
        self.assertEqual((report["verdict"], battery.failures(self.site)), ("passed", []))
        self.assertEqual(report["ratchet"]["shrinkTags"], [3])
        self.assertIn("RATCHET", ntceras.table(report))
        self.assertIn("[3]", ntceras.table(report).splitlines()[-1])

    def test_a_shrink_after_a_round_that_ended_on_the_same_query_is_reported(self):
        # a round that ended on a listed query, then a round in which that query is compared and matched: the end
        # fails, and the shrink is reported all the same
        ended = ntcqueries.RoundError(self.matching(5), ("GetCurrentPParams", [0, [0, [5, [3]]]], True), 23,
                                      ntcquery.Deadline("no answer"))
        listed = Row("GetCurrentPParams", 23, True, False, self.SAME, self.SAME, 3)
        with mock.patch.object(ntceras, "RETRY_PAUSE", 0):
            battery = self.battery(self.rounds(ended, self.round_of(5, [listed])))
            battery.request("babbage")
            battery.step("babbage")
        report = battery.report(self.site)
        self.assertEqual(report["rounds"][0]["rows"]["GetCurrentPParams@v23"]["tag"], 3)
        self.assertEqual((report["ratchet"]["shrinks"], report["ratchet"]["shrinkTags"]),
                         (["babbage GetCurrentPParams@v23"], [3]))
        self.assertIn("RATCHET", ntceras.table(report))
        self.assertEqual(report["verdict"], "failed")

    def test_answers_that_differ_fail_and_are_kept(self):
        battery = self.battery(self.rounds(self.differing(5)))
        battery.request("babbage")
        battery.step("babbage")
        report = battery.report(self.site)["rounds"][0]
        self.assertEqual(report["rows"]["GetUTxOByTxIn@v23"]["scalaHex"], "820402")
        self.assertIn("babbage (protocol 5) GetUTxOByTxIn@v23: differs", battery.failures(self.site))
        self.assertEqual((self.root / "ntc-eras" / "babbage-5-120-GetUTxOByTxIn-v23.scala").read_bytes(),
                         b"\x82\x04\x02")

    def test_a_later_round_of_the_same_step_never_erases_a_difference(self):
        battery = self.battery(self.rounds(self.differing(5, 7), self.matching(5, 7)))
        for _ in range(2):
            battery.request("babbage")
            battery.step("babbage")
        self.assertEqual(battery.report(self.site)["perEra"]["babbage"]["rounds"], 2)
        self.assertIn("babbage (protocol 7) GetUTxOByTxIn@v23: differs", battery.failures(self.site))

    def test_a_round_that_ended_early_keeps_its_failures_and_the_query_it_ended_on_fails(self):
        partial = self.differing(5)
        ended = ntcqueries.RoundError(partial, ("DebugNewEpochState", [12], True), 23,
                                      ntcquery.MalformedReply("query failed: unexpected reply"))
        with mock.patch.object(ntceras, "RETRY_PAUSE", 0):
            battery = self.battery(self.rounds(ended, self.matching(5)))
            battery.request("babbage")
            battery.step("babbage")
        babbage = battery.report(self.site)["perEra"]["babbage"]
        self.assertEqual((babbage["rounds"], babbage["partialRounds"]), (1, 1))
        failures = battery.failures(self.site)
        self.assertIn("babbage (protocol 5) GetUTxOByTxIn@v23: differs", failures)
        self.assertTrue([f for f in failures if f.startswith("babbage (protocol 5) DebugNewEpochState@v23: the round "
                                                              "ended on it")], failures)
        # an early end whose rows all matched fails all the same, through the query it ended on
        battery = self.battery(self.rounds(ntcqueries.RoundError(self.matching(5), ("DebugNewEpochState", [12], True),
                                                                 23, ntcquery.Deadline("no answer")),
                                           self.matching(5)))
        with mock.patch.object(ntceras, "RETRY_PAUSE", 0):
            battery.request("babbage")
            battery.step("babbage")
        self.assertEqual(battery.report(self.site)["verdict"], "failed")

    def test_a_request_without_a_complete_round_fails(self):
        lost = [ntcqueries.PointLost("gone")] * ntceras.ATTEMPTS
        with mock.patch.object(ntceras, "RETRY_PAUSE", 0):
            battery = self.battery(self.rounds(*lost, self.matching(3, 4)))
            for _ in range(2):
                battery.request("mary")
                battery.step("mary")
        self.assertEqual(self.calls, ntceras.ATTEMPTS + 1)
        # a later round of the era does not make up for the request that had none
        self.assertEqual([f for f in battery.failures(self.site) if "request" in f],
                         ["node-to-client battery: a mary request without a complete round: "
                          "[{'attempt': 3, 'error': \"PointLost('gone')\", 'kind': 'transient'}]"])

    def test_each_request_gets_one_complete_round_whatever_its_step(self):
        # the walk asked in Babbage, and the follower's state is in Conway by then: the round is Conway's
        with mock.patch.object(ntceras, "RETRY_PAUSE", 0):
            battery = self.battery(self.rounds(ntcqueries.PointLost("gone"), self.matching(6, 9), self.matching(6, 9)))
            for _ in range(2):
                battery.request("babbage")
                battery.step("babbage")
        self.assertEqual(self.calls, 3)
        report = battery.report(self.site)
        self.assertEqual(report["perEra"]["conway"]["steps"], [9])
        self.assertEqual([r["attempts"] for r in report["rounds"]], [2, 1])
        self.assertEqual([r["requestedIn"] for r in report["rounds"]], ["babbage", "babbage"])
        self.assertFalse([f for f in battery.failures(self.site) if "request" in f])

    def test_a_stop_cancels_the_queued_requests_and_one_it_cuts_short(self):
        battery = self.battery(self.rounds())
        battery.request("babbage")
        battery.request("conway")
        battery.stop.set()
        battery.run()
        report = battery.report(self.site)
        self.assertEqual((report["requested"], report["cancelled"], report["unaccounted"]),
                         (2, ["babbage", "conway"], 0))
        # once the thread has stopped, a request is not taken, nor counted
        battery.request("conway")
        self.assertEqual(battery.report(self.site)["requested"], 2)

        def stopping(*a, **k):
            battery.stop.set()
            raise ntcqueries.PointLost("gone")

        battery = self.battery(stopping)
        battery.request("babbage")
        battery.step(battery.requests.get_nowait())
        report = battery.report(self.site)
        self.assertEqual((report["cancelled"], report["notJudged"], report["unaccounted"]), (["babbage"], [], 0))
        self.assertFalse([f for f in battery.failures(self.site) if "request" in f])

    def test_a_request_is_counted_and_queued_under_the_lock_the_thread_closes_with(self):
        # so the thread's end, which closes and drains the queue under that lock, sees every request it counted
        battery = self.battery(self.rounds())
        held = []

        class Watched(type(battery.requests)):
            def put(self, item, *a, **k):
                held.append(battery.lock.locked())
                super().put(item, *a, **k)

        battery.requests = Watched()
        battery.request("babbage")
        self.assertEqual(held, [True])
        battery.stop.set()
        battery.run()
        self.assertTrue(battery.closed)
        battery.request("conway")
        report = battery.report(self.site)
        self.assertEqual((report["requested"], report["cancelled"], report["unaccounted"]), (1, ["babbage"], 0))

    def test_a_request_neither_judged_nor_cancelled_fails(self):
        battery = self.battery(self.rounds())
        battery.request("babbage")
        report = battery.report(self.site)
        self.assertEqual(report["unaccounted"], 1)
        self.assertIn("node-to-client battery: 1 requests neither judged nor cancelled", battery.failures(self.site))

    def test_nodes_that_cannot_be_compared_are_not_retried(self):
        battery = self.battery(self.rounds(ntcqueries.Incomparable("5 and 4")))
        battery.request("babbage")
        battery.step("babbage")
        unjudged = battery.report(self.site)["notJudged"]
        self.assertEqual([e["kind"] for e in unjudged[0]["attemptErrors"]], ["incomparable"])

    def test_the_thread_runs_the_requests_and_ends_on_the_stop(self):
        done = threading.Event()

        def judged(*a, **k):
            done.set()
            return self.matching(6, 10)

        battery = self.battery(judged)
        battery.thread.start()
        self.addCleanup(battery.thread.join, 10)
        self.addCleanup(battery.stop.set)
        battery.request("conway")
        reached = done.wait(10)
        battery.stop.set()
        battery.thread.join(10)
        self.assertTrue(reached)
        self.assertFalse(battery.thread.is_alive())
        self.assertEqual(battery.report(self.site)["perEra"]["conway"]["steps"], [10])

if __name__ == "__main__":
    unittest.main()
