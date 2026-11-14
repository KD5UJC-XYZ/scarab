# SPDX-License-Identifier: Apache-2.0
"""Unit tests for complete ledger-state comparison (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cbor, cborspan, ledgercompare, ntcquery  # noqa: E402

POOL = b"\x01" * 28


def pool_snapshot(delegators):
    """A StakePoolSnapShot: ten fields, `numDelegators` the ninth."""
    return [10, [1, 2], [], 0, b"\x02" * 32, 5, 6, [1, 10], delegators, [0, b"\x03" * 28]]


def nes(delegators=2, vrf=None, likelihoods=None, pot=0, update=None, extra_balance=100):
    """A NewEpochState in the Conway layout, with historically waived fields and ordinary balances."""
    snapshot = [{}, {POOL: pool_snapshot(delegators)}]
    es = [[1, 2],
          [[{}, [vrf or {}, {}, {}, {}], {}], [{}, 7, 8, extra_balance, 9]],
          [snapshot, snapshot, snapshot, 11],
          [likelihoods or {}, pot]]
    return [5, {}, {POOL: 3}, es, [] if update is None else [update], [{}, 1], None]


def answer(state, wrapped=False, estate=False):
    body = state[3] if estate else state
    inner = cbor.enc(body)
    return cbor.enc([4, [cbor.Tag(24, inner) if wrapped else cbor.Raw(inner)]])


def verdict(label, reference, scala):
    return ledgercompare.compare(label, answer(reference, label.startswith("GetCBOR/"), label.endswith("DebugEpochState")),
                           answer(scala, label.startswith("GetCBOR/"), label.endswith("DebugEpochState")))


class CborSpanTest(unittest.TestCase):
    def test_children_and_ends_of_every_kind_of_item(self):
        item = cbor.enc([1, {b"k": [2, 3]}, cbor.Tag(24, b"xy"), "t", None, 0])
        spans = cborspan.children(item, 0)
        self.assertEqual(len(spans), 6)
        self.assertEqual(spans[-1][1], len(item))
        start = item.index(b"\x82\x02\x03")
        self.assertEqual(cborspan.resolve(item, [1, "v*"]), [(start, start + 3)])
        self.assertEqual(cborspan.resolve(item, [1, "v*", (1,)]), [(start + 2, start + 3)])

    def test_indefinite_items_and_floats_have_their_extent(self):
        item = bytes.fromhex("9f01bf6161f90000ff5f4161ffff")  # [_ 1, {_ "a": 0.0}, (_ h'61')]
        self.assertEqual(cborspan.item_end(item, 0), len(item))
        self.assertEqual(len(cborspan.children(item, 0)), 3)

    def test_a_missing_child_is_malformed(self):
        with self.assertRaises(cborspan.Malformed):
            cborspan.resolve(cbor.enc([1, 2]), [5])
        with self.assertRaises(cborspan.Malformed):
            cborspan.item_end(b"\x82\x01", 0)


class LedgerComparisonTest(unittest.TestCase):
    def test_identical_complete_answers_pass_for_every_wire_wrapper(self):
        for label in ledgercompare.LABELS:
            with self.subTest(label=label):
                v = verdict(label, nes(), nes())
                self.assertTrue(v.equal, v.problems)
                self.assertTrue(v.raw_equal)

    def test_each_previously_waived_field_is_a_failing_comparison(self):
        changes = {
            "NonMyopic": nes(likelihoods={POOL: [b"likelihood"]}, pot=19),
            "SnapshotNumDelegators": nes(delegators=4),
            "VrfKeyHashes": nes(vrf={b"v" * 32: 1}),
            "RewardUpdate": nes(update=[1, 2, {}]),
        }
        for field_name, changed in changes.items():
            for label in ledgercompare.LABELS:
                if field_name == "RewardUpdate" and label.endswith("DebugEpochState"):
                    continue
                with self.subTest(field=field_name, label=label):
                    reference = nes(update=[1, 2, {POOL: 5}]) if field_name == "RewardUpdate" else nes()
                    v = verdict(label, reference, changed)
                    self.assertFalse(v.equal, "a historical waiver must not turn different bytes into success")
                    self.assertFalse(v.raw_equal)
                    self.assertIn("the complete ledger-state answer bytes differ", v.problems)

    def test_ordinary_field_differences_remain_failures(self):
        for label in ledgercompare.LABELS:
            with self.subTest(label=label):
                self.assertFalse(verdict(label, nes(), nes(extra_balance=101)).equal)

    def test_identical_malformed_answers_do_not_pass(self):
        for malformed in (b"", b"\x01", b"\x82\x04\x81\x01", answer(nes()) + b"\x00",
                          cbor.enc([4, [[]]]), cbor.enc([5, [[]]]), cbor.enc([4, [[], []]]), cbor.enc([4, [[]], 0])):
            with self.subTest(raw=malformed):
                v = ledgercompare.compare("DebugNewEpochState", malformed, malformed)
                self.assertFalse(v.equal)
                self.assertTrue(v.problems)

    def test_nonminimal_or_reordered_wire_forms_are_not_silently_canonicalized(self):
        canonical = answer(nes())
        longer_outer_array = b"\x98\x02" + canonical[1:]
        v = ledgercompare.compare("DebugNewEpochState", canonical, longer_outer_array)
        self.assertFalse(v.equal)
        self.assertFalse(v.raw_equal)

    def test_summary_has_one_exact_success_count(self):
        total = {}
        ledgercompare.add_to(total, verdict("DebugNewEpochState", nes(), nes(delegators=9)))
        ledgercompare.add_to(total, verdict("DebugNewEpochState", nes(), nes()))
        self.assertEqual(total, {"answers": 2, "byteEqual": 1, "matched": 1})


if __name__ == "__main__":
    unittest.main()


class WholeStateComparisonTest(unittest.TestCase):
    """The per-era whole-state comparison of `e2e` (`compare.whole_state`): where two answers first differ,
    and the tally each era reports."""

    def setUp(self):
        from devnet import compare
        self.compare = compare

    def test_equal_answers_have_no_difference(self):
        a = answer(nes())
        self.assertIsNone(self.compare.first_difference(a, a))

    def test_the_path_leads_to_the_deepest_differing_item(self):
        a, b = answer(nes(pot=0)), answer(nes(pot=1))
        found = self.compare.first_difference(a, b)
        # MsgResult [4, [state]]: the result, the singleton, then the epoch state (3), its fourth part (3),
        # and in it the pot (1)
        self.assertEqual(found["path"], [1, 0, 3, 3, 1])
        self.assertEqual(a[:found["offset"]], b[:found["offset"]])
        self.assertNotEqual(a[found["offset"]], b[found["offset"]])

    def test_a_difference_in_length_stops_at_the_container(self):
        a, b = cbor.enc([1, [2, 3]]), cbor.enc([1, [2, 3, 4]])
        self.assertEqual(self.compare.first_difference(a, b)["path"], [1])

    def test_the_tally_counts_each_outcome_of_each_query_and_keeps_the_place_of_a_difference(self):
        import tempfile
        nes_label, utxo_label = "DebugNewEpochState", ledgercompare.UTXO_LABEL
        self.assertEqual(set(self.compare.WHOLE_QUERIES), {nes_label, utxo_label})
        with tempfile.TemporaryDirectory() as d:
            tally = self.compare.Comparisons(pathlib.Path(d))
            tally.record_whole("babbage", "epoch 7", {"slot": 1, "answers": {
                nes_label: {"matched": True}, utxo_label: {"matched": True}}})
            tally.record_whole("babbage", "epoch 8", {"slot": 2, "answers": {
                nes_label: {"matched": True},
                utxo_label: {"matched": False, "firstDifference": {"offset": 9, "path": [1]},
                             "referenceBytes": 10, "scalaBytes": 10, "kept": "x"}}})
            tally.record_whole("babbage", "epoch 9", {"error": "socket"})
            tally.record_whole("babbage", "epoch 9", {"skipped": "no socket"})
            nes_row, utxo_row = tally.whole[nes_label]["babbage"], tally.whole[utxo_label]["babbage"]
            self.assertEqual((nes_row["matched"], nes_row["mismatched"], nes_row["errors"], nes_row["skipped"]),
                             (2, 0, 1, 1))
            self.assertEqual((utxo_row["matched"], utxo_row["mismatched"], utxo_row["errors"], utxo_row["skipped"]),
                             (1, 1, 1, 1))
            self.assertEqual(utxo_row["differences"][0]["firstDifference"], {"offset": 9, "path": [1]})
            self.assertEqual(utxo_row["differences"][0]["slot"], 2)
            self.assertNotIn("byron", tally.whole[nes_label])

    def test_a_whole_state_difference_error_or_shortfall_of_either_query_fails_the_run(self):
        from types import SimpleNamespace
        eras = self.compare.ERAS
        rows = {era: {"matched": 5, "mismatched": 0, "errors": 0, "skipped": 0} for era in eras}
        whole = {q: {era: {"matched": 5, "mismatched": 0, "errors": 0, "skipped": 0} for era in eras[1:]}
                 for q in self.compare.WHOLE_QUERIES}
        tally = SimpleNamespace(per_era=rows, whole=whole)
        self.assertEqual(self.compare.judge_comparisons(tally, 3, True), [])
        whole["DebugNewEpochState"]["mary"]["mismatched"] = 1
        whole["DebugNewEpochState"]["alonzo"]["errors"] = 1
        whole[ledgercompare.UTXO_LABEL]["babbage"]["matched"] = 2
        whole[ledgercompare.UTXO_LABEL]["shelley"]["mismatched"] = 1
        failures = self.compare.judge_comparisons(tally, 3, True)
        self.assertEqual(len(failures), 4, failures)
        self.assertTrue(failures[0].startswith("shelley: 1 same-point GetUTxOWhole answers differ"))
        self.assertTrue(failures[1].startswith("mary: 1 same-point DebugNewEpochState answers differ"))
        self.assertEqual(failures[2], "alonzo: 1 same-point DebugNewEpochState comparison errors")
        self.assertEqual(failures[3], "babbage: only 2 matched same-point GetUTxOWhole answers (need 3)")

    def test_a_skipped_complete_comparison_or_none_at_all_fails_even_with_no_minimum(self):
        from types import SimpleNamespace
        whole = {q: {"conway": {"matched": 3, "mismatched": 0, "errors": 0, "skipped": 0}}
                 for q in self.compare.WHOLE_QUERIES}
        tally = SimpleNamespace(whole=whole)
        self.assertEqual(self.compare.whole_failures(tally, "conway", 0), [])
        whole["GetUTxOWhole"]["conway"]["skipped"] = 1
        self.assertEqual(self.compare.whole_failures(tally, "conway", 0),
                         ["conway: 1 same-point GetUTxOWhole comparisons skipped"])
        none = {q: {"conway": {"matched": 0, "mismatched": 0, "errors": 0, "skipped": 0}}
                for q in self.compare.WHOLE_QUERIES}
        self.assertEqual(len(self.compare.whole_failures(SimpleNamespace(whole=none), "conway", 0)), 2)


class WholeStateAnchorTest(unittest.TestCase):
    """`compare.whole_state` asks the anchor's tip first and the other node at that point, taken again while the
    other has not reached it; the reference's answers are always the expected ones."""

    def setUp(self):
        import tempfile
        from unittest import mock
        from devnet import compare
        self.compare, self.mock = compare, mock
        self.dir = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.dir.name)
        (self.root / "scala-sock").mkdir()
        (self.root / "scala-sock" / "node.socket").write_text("")
        self.calls = []
        self.tip = 6

    def tearDown(self):
        self.dir.cleanup()

    def sessions(self, refuse_scala_acquires=0, scala_answer=None):
        """Fake node-to-client sessions: the reference answers `ref`, the Scala node `scala_answer` (default the
        same), and the Scala node refuses its first `refuse_scala_acquires` acquires of a point."""
        test, refusals = self, [refuse_scala_acquires]
        utxo = cbor.enc([4, [{}]])

        class Session:
            def __init__(self, container, socket, magic, host=False, deadline=None):
                self.side = "scala" if host else "reference"
                self.last_raw = None
                self.asked = 0

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def acquire(self, point=None):
                test.calls.append((self.side, "acquire", point))
                if point is None:
                    test.tip += 1  # each acquire of a tip finds it a block further on
                if self.side == "scala" and point is not None and refusals[0] > 0:
                    refusals[0] -= 1
                    raise ntcquery.AcquireRefused(1)

            def query_raw(self, query):
                point = [test.tip, b"\x07" * 32]
                self.last_raw = cbor.enc([4, point])
                return point

            def _send(self, protocol, payload):
                pass

            def _recv(self, protocol):
                # each session asks the new-epoch state first, then the UTxO (`WHOLE_QUERIES` order)
                state = scala_answer if self.side == "scala" and scala_answer is not None else answer(nes())
                self.last_raw = utxo if self.asked else state
                self.asked += 1
                return [4, None]
        return Session

    def run_whole(self, **kwargs):
        with self.mock.patch.object(self.compare.ntcquery, "LocalStateQuery", self.sessions(**kwargs.pop("fake", {}))), \
                self.mock.patch.object(self.compare.util, "data_root", lambda name: self.root), \
                self.mock.patch.object(self.compare, "ref", lambda name: "ref-container"), \
                self.mock.patch.object(self.compare.time, "sleep", lambda s: None):
            return self.compare.whole_state("x", "conway", **kwargs)

    def test_the_reference_anchor_asks_the_reference_first_and_the_scala_node_at_its_point(self):
        result = self.run_whole(anchor="reference")
        self.assertEqual(self.calls, [("reference", "acquire", None), ("scala", "acquire", [7, b"\x07" * 32])])
        self.assertEqual(result["slot"], 7)
        self.assertTrue(all(a["matched"] for a in result["answers"].values()), result)

    def test_the_scala_node_is_asked_again_at_the_same_point_while_the_reference_moves_on(self):
        result = self.run_whole(anchor="reference", attempts=3, fake={"refuse_scala_acquires": 2})
        self.assertEqual(self.calls, [("reference", "acquire", None)] + [("scala", "acquire", [7, b"\x07" * 32])] * 3)
        self.assertEqual(result["slot"], 7)
        self.assertTrue(all(a["matched"] for a in result["answers"].values()), result)

    def test_a_point_never_reached_is_given_up_once_for_a_new_one(self):
        result = self.run_whole(anchor="reference", attempts=2, fake={"refuse_scala_acquires": 2})
        self.assertEqual([(side, point and point[0]) for side, _, point in self.calls],
                         [("reference", None), ("scala", 7), ("scala", 7), ("reference", None), ("scala", 8)])
        self.assertEqual(result["slot"], 8)

    def test_after_the_last_attempt_at_the_new_point_the_refusal_is_an_error(self):
        result = self.run_whole(anchor="reference", attempts=2, fake={"refuse_scala_acquires": 5})
        self.assertIn("acquire failed", result["error"])
        self.assertEqual(sum(1 for side, _, point in self.calls if side == "scala"), 4)

    def test_the_scala_anchor_is_one_attempt_and_the_reference_is_expected(self):
        different = answer(nes(pot=9))
        result = self.run_whole(fake={"scala_answer": different})
        self.assertEqual(self.calls, [("scala", "acquire", None), ("reference", "acquire", [7, b"\x07" * 32])])
        nes_result = result["answers"]["DebugNewEpochState"]
        self.assertFalse(nes_result["matched"])
        self.assertEqual(nes_result["scalaBytes"], len(different))


class WholeStateForkTest(unittest.TestCase):
    """#785: a same-point comparison whose sampled point the other node refuses, under a scripted schedule of the
    two acquisitions: no cluster, and the pauses are recorded instead of waited for."""

    NOT_ON_CHAIN = ntcquery.AcquireRefused(1)
    TOO_OLD = ntcquery.AcquireRefused(0)

    def setUp(self):
        import tempfile
        from unittest import mock
        from devnet import compare
        self.compare, self.mock = compare, mock
        self.dir = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.dir.name)
        (self.root / "scala-sock").mkdir()
        (self.root / "scala-sock" / "node.socket").write_text("")
        self.calls, self.pauses, self.tip = [], [], 6

    def tearDown(self):
        self.dir.cleanup()

    def point(self, slot):
        return [slot, bytes([slot]) * 32]

    def run_whole(self, script, scala_answer=None, point=..., query_reply=None, **kwargs):
        """`whole_state` against fake sessions. Each sample of the anchor's tip finds it a block further on (slot 7,
        8, ...), unless `point` gives the anchor's chain point; `script` holds, in order, what each acquisition of a
        point by the other node does: None acquires, an exception is raised. `query_reply(side, asked)` replaces the
        reply to an answer query."""
        test, outcomes = self, iter(script)
        utxo = cbor.enc([4, [{}]])

        class Session:
            def __init__(self, container, socket, magic, host=False, deadline=None):
                self.side, self.asked = ("scala" if host else "reference"), 0

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def acquire(self, point=None):
                test.calls.append((self.side, point and point[0]))
                if point is None:
                    test.tip += 1
                    return
                outcome = next(outcomes)
                if outcome is not None:
                    raise outcome

            def query_raw(self, query):
                answered = test.point(test.tip) if point is ... else point
                self.last_raw = cbor.enc([4, answered])
                return answered

            def _send(self, protocol, payload):
                pass

            def _recv(self, protocol):
                state = scala_answer if self.side == "scala" and scala_answer is not None else answer(nes())
                self.last_raw = utxo if self.asked else state
                self.asked += 1
                instead = None if query_reply is None else query_reply(self.side, self.asked)
                if instead is not None:  # the message the node sends instead of the answer
                    self.last_raw = cbor.enc(instead)
                    return instead
                return [4, None]
        with self.mock.patch.object(self.compare.ntcquery, "LocalStateQuery", Session), \
                self.mock.patch.object(self.compare.util, "data_root", lambda name: self.root), \
                self.mock.patch.object(self.compare, "ref", lambda name: "ref-container"), \
                self.mock.patch.object(self.compare.time, "sleep", self.pauses.append):
            return self.compare.whole_state("x", "conway", **kwargs)

    def outcomes(self, result):
        return [(a["stage"], a["point"]["slot"] if a.get("point") else None, a["outcome"],
                 (a.get("refusal") or {}).get("failure")) for a in result["acquisitions"]]

    def test_a_point_a_fork_took_off_the_reference_is_sampled_again_and_compared_at_one_point(self):
        result = self.run_whole([self.NOT_ON_CHAIN, None], reselections=2)
        self.assertEqual(self.calls, [("scala", None), ("reference", 7), ("scala", None), ("reference", 8)])
        self.assertEqual(self.outcomes(result), [("anchor", 7, "acquired", None),
                                                 ("other", 7, "refused", "PointNotOnChain"),
                                                 ("anchor", 8, "acquired", None), ("other", 8, "acquired", None)])
        self.assertEqual((result["slot"], result["point"]), (8, {"slot": 8, "hash": "08" * 32}))
        self.assertEqual((result["anchor"], result["other"]), ("scala", "reference"))
        self.assertTrue(all(a["matched"] for a in result["answers"].values()), result)
        self.assertEqual(self.pauses, [self.compare.RESELECTION_PAUSE])

    def test_a_persistent_refusal_is_an_error_once_the_bound_is_spent(self):
        result = self.run_whole([self.NOT_ON_CHAIN] * 4, reselections=2)
        self.assertEqual([side for side, _ in self.calls].count("reference"), 3, "1 + 2 reselections, no more")
        self.assertIn("acquire failed", result["error"])
        self.assertNotIn("answers", result)
        self.assertEqual((result["stage"], result["point"]["slot"], result["exhausted"]), ("other-acquire", 9, True))
        self.assertEqual(result["kind"], "refused")
        self.assertEqual(result["refusal"], {"reply": "[2, 1]", "failure": "PointNotOnChain"})
        self.assertEqual(result["bounds"], {"attempts": 1, "reselections": 2})
        self.assertEqual([o[2] for o in self.outcomes(result)], ["acquired", "refused"] * 3)

    def test_a_byte_difference_after_a_recovery_stays_a_mismatch(self):
        tallies = self.compare.Comparisons(self.root)
        result = self.run_whole([self.NOT_ON_CHAIN, None], scala_answer=answer(nes(pot=9)), reselections=2)
        self.assertFalse(result["answers"]["DebugNewEpochState"]["matched"])
        self.assertIsNotNone(result["answers"]["DebugNewEpochState"]["firstDifference"])
        with self.mock.patch("builtins.print"):
            tallies.record_whole("conway", "mid", result)
        row = tallies.whole["DebugNewEpochState"]["conway"]
        self.assertEqual((row["mismatched"], row["matched"], row["afterRefusal"]), (1, 0, 1))
        self.assertIn("1 same-point DebugNewEpochState answers differ",
                      " ".join(self.compare.whole_failures(tallies, "conway", 1)))

    def test_a_point_the_other_node_reaches_late_is_acquired_at_the_same_point(self):
        result = self.run_whole([self.NOT_ON_CHAIN, self.NOT_ON_CHAIN, None], anchor="reference", attempts=3)
        self.assertEqual(self.calls, [("reference", None)] + [("scala", 7)] * 3)
        self.assertEqual(self.pauses, [self.compare.SAME_POINT_PAUSE] * 2)
        self.assertEqual(result["slot"], 7)
        self.assertTrue(all(a["matched"] for a in result["answers"].values()), result)

    def test_without_reselections_asked_for_the_first_refusal_is_the_error_and_keeps_its_point(self):
        tallies = self.compare.Comparisons(self.root)
        result = self.run_whole([self.NOT_ON_CHAIN])
        self.assertEqual(self.calls, [("scala", None), ("reference", 7)])
        self.assertEqual((result["stage"], result["point"]["slot"]), ("other-acquire", 7))
        with self.mock.patch("builtins.print"):
            tallies.record_whole("conway", "mid", result)
        failures = [f for f in self.compare.whole_failures(tallies, "conway", 0) if "comparison errors" in f]
        self.assertEqual(len(failures), 2)  # one per query of `WHOLE_QUERIES`
        self.assertIn("1 same-point DebugNewEpochState comparison errors", failures[0])
        self.assertIn("'stage': 'other-acquire'", failures[0])
        self.assertIn("'failure': 'PointNotOnChain'", failures[0])
        self.assertEqual(tallies.whole["GetUTxOWhole"]["conway"]["errorDetails"][0]["point"]["slot"], 7)

    def test_a_point_too_old_for_the_reference_is_not_sampled_again(self):
        # a follower more than k blocks behind the reference stays behind: an error, as before the reselection
        result = self.run_whole([self.TOO_OLD, None], reselections=2)
        self.assertEqual([side for side, _ in self.calls].count("reference"), 1)
        self.assertEqual(result["refusal"], {"reply": "[2, 0]", "failure": "PointTooOld"})
        self.assertEqual((result["stage"], result["point"]["slot"]), ("other-acquire", 7))
        self.assertNotIn("exhausted", result)
        self.assertEqual(self.pauses, [])

    def test_a_point_once_too_old_ends_the_comparison_whatever_follows(self):
        # too old, then not on the chain, then acquired: the first answer already ends it
        result = self.run_whole([self.TOO_OLD, self.NOT_ON_CHAIN, None], anchor="reference", attempts=3)
        self.assertEqual(self.calls, [("reference", None), ("scala", 7)])
        self.assertEqual(result["refusal"]["failure"], "PointTooOld")
        self.assertNotIn("answers", result)

    def test_a_sampled_point_that_is_not_a_block_point_ends_the_comparison(self):
        result = self.run_whole([None], reselections=2, point=None)
        self.assertEqual(self.calls, [("scala", None)], "the other node is never asked for its own tip")
        self.assertEqual((result["kind"], result["stage"], result["point"]), ("malformed", "anchor-point", None))
        self.assertEqual(self.outcomes(result), [("anchor", None, "acquired", None)])

    def test_a_query_that_fails_after_the_point_was_read_keeps_the_point(self):
        result = self.run_whole([None], query_reply=lambda side, asked: [5] if side == "scala" else None)
        self.assertEqual((result["kind"], result["stage"], result["point"]["slot"]), ("malformed", "anchor-query", 7))
        self.assertEqual(self.outcomes(result), [("anchor", 7, "acquired", None)])

    def test_a_defect_of_the_harness_is_an_error_with_its_evidence(self):
        with self.mock.patch.object(self.compare.ledgercompare, "compare", side_effect=TypeError("a defect")):
            result = self.run_whole([None])
        self.assertEqual((result["kind"], result["stage"], result["point"]["slot"]), ("defect", "judge", 7))
        self.assertEqual([o[2] for o in self.outcomes(result)], ["acquired", "acquired"])

    def test_a_reply_that_is_neither_an_acquisition_nor_a_refusal_is_not_retried(self):
        result = self.run_whole([ntcquery.MalformedReply("acquire failed: unexpected reply [9]"), None], reselections=2)
        self.assertEqual([side for side, _ in self.calls].count("reference"), 1)
        self.assertEqual(self.outcomes(result)[-1], ("other", 7, "failed", None))
        self.assertNotIn("exhausted", result)

    def test_a_closed_connection_is_not_retried(self):
        result = self.run_whole([EOFError("node closed the connection"), None], reselections=2)
        self.assertEqual([side for side, _ in self.calls].count("reference"), 1)
        self.assertIn("EOFError", result["error"])
        self.assertIsNone(result["refusal"])

    def test_a_refusal_is_parsed_only_from_a_well_formed_failure(self):
        self.assertEqual(self.compare.acquire_refusal(self.TOO_OLD), {"reply": "[2, 0]", "failure": "PointTooOld"})
        self.assertIsNone(self.compare.acquire_refusal(RuntimeError("acquire failed: [2, 1]")))
        self.assertIsNone(self.compare.acquire_refusal(ntcquery.MalformedReply("acquire failed: unexpected reply [2]")))


def frames(*messages):
    """The bytes a node sends on LocalStateQuery (mini-protocol 7, the responder's direction): one mux frame per
    message, each message CBOR (given as a value, or as its bytes)."""
    import struct
    out = b""
    for message in messages:
        payload = message if isinstance(message, bytes) else ntcquery.enc(message)
        out += struct.pack(">IHH", 0, 0x8000 | 7, len(payload)) + payload
    return out


class TransportRepliesTest(unittest.TestCase):
    """Acquire and query replies decoded from the bytes a node sends, through `ntcquery`'s own decoder: only the
    shapes the protocol allows are accepted, by message tag and by type."""

    def session(self, *messages):
        import io
        import types
        session = ntcquery.LocalStateQuery(None, "socket", 1)
        session.proc = types.SimpleNamespace(stdin=io.BytesIO(), stdout=io.BytesIO(frames(*messages)))
        session.buffers, session.expires = {}, None
        return session

    def test_an_acquisition_and_the_two_refusals(self):
        self.assertIsNone(self.session([1]).acquire([7, bytes(32)]))
        for code, name in ((0, "PointTooOld"), (1, "PointNotOnChain")):
            with self.assertRaises(ntcquery.AcquireRefused) as refused:
                self.session([2, code]).acquire([7, bytes(32)])
            self.assertEqual((refused.exception.failure, refused.exception.reply), (name, [2, code]))

    def test_any_other_acquire_reply_is_malformed(self):
        for reply in ([2, True], [1, 999], [2, []], [2, 5], [True], [1, None], [3], 7, [2]):
            with self.subTest(reply=reply), self.assertRaises(ntcquery.MalformedReply):
                self.session(reply).acquire([7, bytes(32)])

    # bytes the decoder reads as `[1]` or `[2, 1]`, but that are not the definite array of unsigned words the
    # reference's codec reads: a simple value, a bignum, set tag 258, an indefinite array
    TYPE_ERASED = {"81e1": "an array holding simple(1)", "8202e1": "a failure whose code is simple(1)",
                   "81c24101": "a bignum 1", "d901028101": "tag 258 around [1]", "9f01ff": "an indefinite [1]"}

    def test_a_reply_is_judged_on_its_bytes_not_on_what_the_decoder_makes_of_them(self):
        for raw, what in self.TYPE_ERASED.items():
            with self.subTest(raw=raw, what=what), self.assertRaises(ntcquery.MalformedReply):
                self.session(bytes.fromhex(raw)).acquire([7, bytes(32)])
        for raw in ("9f0400ff", "82c2410400", "8104"):
            with self.subTest(raw=raw), self.assertRaises(ntcquery.MalformedReply):
                self.session(bytes.fromhex(raw)).query_raw([3])

    def test_a_chain_point_is_judged_on_its_bytes(self):
        block = cbor.enc([4, [7, b"\x07" * 32]])
        self.assertEqual(ntcquery.chain_point(block), [7, b"\x07" * 32])
        self.assertIsNone(ntcquery.chain_point(cbor.enc([4, []])), "Origin, valid protocol data")
        for raw in ("8204f6", "820482c241075820" + "07" * 32, "82049f075820" + "07" * 32 + "ff",
                    "820482075f5820" + "07" * 32 + "ff", "820482075819" + "07" * 31, block.hex() + "00"):
            with self.subTest(raw=raw[:24]), self.assertRaises(ntcquery.MalformedReply):
                ntcquery.chain_point(bytes.fromhex(raw))

    def test_only_a_result_answers_a_query(self):
        self.assertEqual(self.session([4, [7, b"\x01" * 32]]).query_raw([3]), [7, b"\x01" * 32])
        for reply in ([4], [2, 1], [True, 1], 4):
            with self.subTest(reply=reply), self.assertRaises(ntcquery.MalformedReply):
                self.session(reply).query_raw([3])


class WholeStateTransportTest(unittest.TestCase):
    """`whole_state` with each session's bytes scripted and decoded by the real `ntcquery` session code: a reply the
    protocol does not allow ends the comparison as a failure, never a recovery or a success."""

    POINT7, POINT8 = [7, b"\x07" * 32], [8, b"\x08" * 32]

    def setUp(self):
        import tempfile
        from unittest import mock
        from devnet import compare
        self.compare, self.mock = compare, mock
        self.dir = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.dir.name)
        (self.root / "scala-sock").mkdir()
        (self.root / "scala-sock" / "node.socket").write_text("")

    def tearDown(self):
        self.dir.cleanup()

    def anchor(self, point):
        """The bytes of the follower's session: its tip acquired, its chain point, then both answers."""
        return frames([1], [4, point], answer(nes()), cbor.enc([4, [{}]]))

    def other(self, *acquire):
        """The bytes of the reference's session: its reply to the acquire, and both answers after a success."""
        return frames(*acquire, answer(nes()), cbor.enc([4, [{}]]))

    def run_whole(self, sessions, **kwargs):
        """(result, sessions opened) of `whole_state` whose sessions send `sessions`' bytes in order."""
        import io
        import types
        scripts, opened = list(sessions), []

        class Scripted(ntcquery.LocalStateQuery):
            def __enter__(self):
                opened.append(self.host)
                self.proc = types.SimpleNamespace(stdin=io.BytesIO(), stdout=io.BytesIO(scripts.pop(0)))
                self.buffers, self.expires = {}, None
                return self

            def __exit__(self, *exc):
                return False
        with self.mock.patch.object(self.compare.ntcquery, "LocalStateQuery", Scripted), \
                self.mock.patch.object(self.compare.util, "data_root", lambda name: self.root), \
                self.mock.patch.object(self.compare, "ref", lambda name: "ref-container"), \
                self.mock.patch.object(self.compare.time, "sleep"):
            return self.compare.whole_state("x", "conway", **kwargs), len(opened)

    def test_a_refusal_and_a_new_sample_decoded_from_the_bytes_recover_at_one_point(self):
        result, opened = self.run_whole([self.anchor(self.POINT7), frames([2, 1]), self.anchor(self.POINT8),
                                         self.other([1])], reselections=2)
        self.assertEqual((opened, result["slot"]), (4, 8))
        self.assertTrue(all(a["matched"] for a in result["answers"].values()), result)

    def test_a_failure_whose_code_is_true_is_malformed_not_a_point_off_the_chain(self):
        # `[2, true]` is no refusal: before, `true == 1` made it PointNotOnChain and the next sample a recovery
        result, opened = self.run_whole([self.anchor(self.POINT7), frames([2, True]), self.anchor(self.POINT8),
                                         self.other([1])], reselections=2)
        self.assertEqual(opened, 2)
        self.assertEqual((result.get("kind"), result.get("stage")), ("malformed", "other-acquire"), result)
        self.assertEqual(result["point"]["slot"], 7)

    def test_an_acquisition_with_a_payload_is_malformed(self):
        result, opened = self.run_whole([self.anchor(self.POINT7), self.other([1, 999])])
        self.assertEqual((result.get("kind"), result.get("stage")), ("malformed", "other-acquire"), result)
        self.assertNotIn("answers", result)

    def test_a_failure_whose_code_is_a_list_ends_as_a_failure_not_an_exception(self):
        result, _ = self.run_whole([self.anchor(self.POINT7), frames([2, []]), self.anchor(self.POINT8),
                                    self.other([1])], reselections=2)
        self.assertEqual((result.get("kind"), result.get("stage")), ("malformed", "other-acquire"), result)

    def test_an_acquire_reply_the_decoder_would_read_as_one_ends_the_comparison(self):
        # before, `81e1` was an acquisition with two matched answers, and `8202e1` a refusal, a new sample and a match
        for raw in TransportRepliesTest.TYPE_ERASED:
            with self.subTest(raw=raw):
                result, opened = self.run_whole([self.anchor(self.POINT7), self.other(bytes.fromhex(raw)),
                                                 self.anchor(self.POINT8), self.other([1])], reselections=2)
                self.assertEqual(opened, 2)
                self.assertEqual((result.get("kind"), result.get("stage")), ("malformed", "other-acquire"), result)

    def test_a_chain_point_with_a_tagged_slot_ends_the_comparison(self):
        tagged = bytes.fromhex("820482c241075820") + b"\x07" * 32
        result, opened = self.run_whole([frames([1], tagged, answer(nes()), cbor.enc([4, [{}]])), self.other([1])])
        self.assertEqual((opened, result.get("kind"), result.get("stage")), (1, "malformed", "anchor-point"), result)

    def test_an_anchor_at_origin_has_no_block_to_compare_at(self):
        result, opened = self.run_whole([self.anchor([]), self.other([1])], reselections=2)
        self.assertEqual((opened, result.get("kind"), result.get("stage")), (1, "origin", "anchor-point"), result)

    def test_a_refused_anchor_acquisition_is_in_the_history(self):
        result, _ = self.run_whole([frames([2, 1])])
        self.assertEqual((result.get("kind"), result.get("stage")), ("refused", "anchor-acquire"), result)
        self.assertEqual(result["acquisitions"], [{"pick": 0, "stage": "anchor", "node": "scala", "point": None,
                                                   "outcome": "refused",
                                                   "refusal": {"reply": "[2, 1]", "failure": "PointNotOnChain"}}])

    def test_a_failed_anchor_acquisition_is_in_the_history(self):
        result, _ = self.run_whole([frames([1, 999])])
        self.assertEqual((result.get("kind"), result.get("stage")), ("malformed", "anchor-acquire"), result)
        self.assertEqual([(a["stage"], a["outcome"]) for a in result["acquisitions"]], [("anchor", "failed")])

    def test_an_anchor_without_a_block_point_is_never_compared_with_the_other_nodes_tip(self):
        # a null point would have the other node acquire its own volatile tip: no same-point comparison at all
        result, opened = self.run_whole([self.anchor(None), self.other([1])], reselections=2)
        self.assertEqual(opened, 1)
        self.assertEqual((result.get("kind"), result.get("stage"), result.get("point")),
                         ("malformed", "anchor-point", None), result)


class UtxoComparisonTest(unittest.TestCase):
    """`GetUTxOWhole` answers are compared on every byte, and must be a UTxO answer."""

    UTXO = {cbor.Raw(cbor.enc([b"\x01" * 32, 0])): [b"\x60" + b"\x02" * 28, 5]}

    def utxo_answer(self, utxo):
        return cbor.enc([4, [utxo]])

    def test_equal_answers_match(self):
        a = self.utxo_answer(self.UTXO)
        self.assertTrue(ledgercompare.compare_utxo(a, a).equal)

    def test_one_byte_differs(self):
        a = self.utxo_answer(self.UTXO)
        b = self.utxo_answer({cbor.Raw(cbor.enc([b"\x01" * 32, 0])): [b"\x60" + b"\x02" * 28, 6]})
        v = ledgercompare.compare_utxo(a, b)
        self.assertFalse(v.equal)
        self.assertIn("the complete UTxO answer bytes differ", v.problems)

    def test_an_era_mismatch_or_a_non_map_is_not_a_utxo_answer(self):
        mismatch = cbor.enc([4, [[5, "Babbage"], [6, "Conway"]]])
        v = ledgercompare.compare_utxo(mismatch, mismatch)
        self.assertFalse(v.equal)
        self.assertTrue(v.raw_equal)
        self.assertTrue(any("not a UTxO answer" in p for p in v.problems), v.problems)
        listed = cbor.enc([4, [[1, 2]]])
        self.assertFalse(ledgercompare.compare_utxo(listed, listed).equal)
