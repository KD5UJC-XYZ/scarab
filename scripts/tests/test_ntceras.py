# SPDX-License-Identifier: Apache-2.0
"""The same-point node-to-client battery in every Shelley-based era (`ntcqueries`), without a devnet."""
import hashlib
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import cbor, ntcqueries, ntcquery  # noqa: E402
from devnet.ntcqueries import Outcome, Row  # noqa: E402

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


class BatteryTest(unittest.TestCase):
    def test_the_conway_battery_is_the_one_the_ntc_scenario_asked_before(self):
        # every label, query byte and mark of the Conway battery, as it was before the battery took an era
        digest = hashlib.sha256()
        for label, query, implemented in ntcqueries.battery(universe()):
            digest.update(label.encode() + b"\0" + cbor.enc(query) + bytes([implemented]))
        self.assertEqual(digest.hexdigest(), "8b9b3a3d58d9aacda3bb322c6720e737313d23b4863c0456264487eebef2a5d7")
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

    def test_before_conway_the_scala_node_answers_the_ledger_states_the_chain_state_and_the_utxo(self):
        answered = {label for label, _, implemented in ntcqueries.battery(universe(), 4) if implemented}
        self.assertEqual(answered, {
            "top:GetSystemStart", "top:GetChainBlockNo", "top:GetChainPoint", "hfc:GetInterpreter",
            "hfc:GetCurrentEra", "GetUTxOWhole", "GetUTxOByAddress", "GetUTxOByAddress/none", "GetUTxOByTxIn",
            "GetCBOR/GetUTxOWhole", "DebugChainDepState", "GetCBOR/DebugChainDepState", "DebugEpochState",
            "DebugNewEpochState", "GetCBOR/DebugEpochState", "GetCBOR/DebugNewEpochState"})
        self.assertTrue(all(implemented for _, _, implemented in ntcqueries.battery(universe(), 6)))

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
        self.assertEqual({implemented for _, _, implemented in ntcqueries.legacy_battery(5)}, {False})
        self.assertEqual({implemented for _, _, implemented in ntcqueries.legacy_battery(6)}, {True})


class JudgeTest(unittest.TestCase):
    def outcome(self, implemented, refused, reference, scala, label="GetEpochNo"):
        return ntcqueries.judge(Row(label, 23, implemented, refused, reference, scala))[0]

    def test_each_outcome(self):
        a, b = b"\x82\x04\x01", b"\x82\x04\x02"
        self.assertIs(self.outcome(True, False, a, a), Outcome.MATCHED)
        self.assertIs(self.outcome(False, False, a, a), Outcome.MATCHED)
        self.assertIs(self.outcome(True, False, a, b), Outcome.DIFFERS)
        self.assertIs(self.outcome(False, False, a, b), Outcome.DIFFERS)
        self.assertIs(self.outcome(False, False, a, None), Outcome.INCOMPLETE)
        self.assertIs(self.outcome(True, False, a, None), Outcome.SCALA_REFUSED)
        self.assertIs(self.outcome(False, True, None, None), Outcome.REFUSED_BY_BOTH)
        self.assertIs(self.outcome(False, True, None, a), Outcome.SCALA_ANSWERED)
        self.assertIs(self.outcome(True, False, None, None), Outcome.REFERENCE_REFUSED)
        self.assertIs(self.outcome(True, False, None, a), Outcome.REFERENCE_REFUSED)
        self.assertIs(self.outcome(False, True, a, None), Outcome.REFERENCE_ANSWERED)

    def test_only_a_match_an_incomplete_row_and_a_refusal_by_both_pass(self):
        self.assertEqual({o for o in Outcome if o.passes},
                         {Outcome.MATCHED, Outcome.INCOMPLETE, Outcome.REFUSED_BY_BOTH})

    def test_a_ledger_state_is_judged_on_its_framing_too(self):
        # equal bytes that are not a ledger-state answer do not match
        self.assertIs(self.outcome(True, False, b"\x82\x04\x01", b"\x82\x04\x01", "DebugNewEpochState"),
                      Outcome.DIFFERS)


class FakeNode:
    """A node at `POINT` whose state is of the era `era`: it answers every query with a value naming it, ends the
    connection on a block query whose tag is in `closes`, and refuses to acquire once `lost` is set."""

    def __init__(self, era, closes=(), answers=None, version=23):
        self.era, self.closes, self.answers, self.version = era, set(closes), answers or {}, version
        self.sent, self.opened, self.lost = [], 0, False

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
            if tag in (8, 12):
                # a ledger-state answer as `ledgercompare` frames one: an epoch state of 4 fields, a new one of 7
                state = [era] * (4 if tag == 8 else 7)
                return [cbor.Tag(24, cbor.enc(state)) if q[0] == 9 else state]
            return [[era, tag]]
        return [query[0]]


class FakeSession:
    nodes = {}

    def __init__(self, container, socket, magic, host=False, version=None, deadline=None):
        self.node, self.version = self.nodes["scala" if host else "reference"], version
        self.closed, self.pending, self.last_raw = False, None, None

    def __enter__(self):
        self.node.opened += 1
        self.agreed_version = self.version or self.node.version
        return self

    def __exit__(self, *_):
        self.closed = True

    def acquire(self, point=None):
        if self.node.lost:
            raise ntcquery.AcquireRefused(0)

    def _send(self, protocol, payload):
        if self.closed:
            raise BrokenPipeError()
        self.pending = ntcquery.decode_all(payload)[1]
        self.node.sent.append((self.agreed_version, self.pending))

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
            scala_closes |= {0, 1, 2, 3, 10, 11, 16, 17, 18, 19, 20, 22, 24, 29, 34, 36, 37, 38}
        return FakeNode(era, refused, answers), FakeNode(era, scala_closes, answers)

    def test_a_babbage_round_asks_babbage_queries_and_judges_every_row(self):
        reference, scala = self.nodes(5)
        rnd = self.run_round(reference, scala)
        self.assertEqual((rnd.point, rnd.era, rnd.version), (POINT, 5, 23))
        block_eras = {q[1][1][0] for _, q in reference.sent + scala.sent if q[:1] == [0] and q[1][:1] == [0]}
        self.assertEqual(block_eras, {5})
        outcomes = {(row.label, row.version): ntcqueries.judge(row)[0] for row in rnd.rows}
        self.assertTrue(all(o.passes for o in outcomes.values()), [k for k, o in outcomes.items() if not o.passes])
        self.assertIs(outcomes[("GetUTxOByTxIn", 23)], Outcome.MATCHED)
        self.assertIs(outcomes[("GetEpochNo", 23)], Outcome.INCOMPLETE)
        self.assertIs(outcomes[("GetConstitution", 23)], Outcome.REFUSED_BY_BOTH)
        self.assertIs(outcomes[("GetGenesisConfig", 20)], Outcome.INCOMPLETE)
        # the arguments were read with queries both decoders take before Conway: the pools, the registered
        # credentials and the UTxO, then the battery's first form
        blocks = [q[1][1][1][0] for _, q in reference.sent if q[:1] == [0] and q[1][:1] == [0]]
        self.assertEqual(blocks[:4], [16, 22, 7, 0])
        # the legacy forms were asked at V20 too, on sessions of that version
        self.assertEqual([block(q) for v, q in reference.sent if v == ntcqueries.LEGACY_VERSION],
                         [[3], [11], [9, [3]], [9, [11]]])
        # every session was acquired before the first query: the reference's main one, one for each query it
        # refuses and one more, and two at V20
        refused = sum(1 for row in rnd.rows if row.refused)
        self.assertEqual(reference.opened, 1 + refused + 1 + 2)

    def test_a_conway_round_compares_every_row(self):
        reference, scala = self.nodes(6)
        rnd = self.run_round(reference, scala)
        self.assertEqual({ntcqueries.judge(row)[0] for row in rnd.rows}, {Outcome.MATCHED})

    def test_the_round_stops_before_any_block_query_when_the_eras_differ(self):
        reference, scala = FakeNode(5), FakeNode(4)
        with self.assertRaises(ntcqueries.Incomparable):
            self.run_round(reference, scala)
        self.assertEqual([q for _, q in reference.sent], [[0, [2, [1]]]])
        self.assertEqual([q for _, q in scala.sent], [[3], [0, [2, [1]]]])

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
        at = ntcqueries.AtPoint(lambda: FakeSession(None, None, None), POINT, spares=1)
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
        self.assertEqual(len(at.live), 2)
        self.assertEqual(FakeSession.nodes["reference"].opened, 2)

    def test_a_deadline_is_an_error_of_the_round_not_a_refusal(self):
        class Slow(FakeSession):
            def _recv(self, protocol):
                raise ntcquery.Deadline("no answer")

        FakeSession.nodes = {"reference": FakeNode(5)}
        at = ntcqueries.AtPoint(lambda: Slow(None, None, None), POINT)
        ntcqueries.acquire_all([at])
        with self.assertRaises(ntcquery.Deadline):
            at.ask([1])



if __name__ == "__main__":
    unittest.main()
