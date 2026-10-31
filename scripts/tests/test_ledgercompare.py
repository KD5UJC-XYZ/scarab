# SPDX-License-Identifier: Apache-2.0
"""Unit tests for complete ledger-state comparison (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cbor, cborspan, ledgercompare  # noqa: E402

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

    def test_the_tally_counts_each_outcome_and_keeps_the_place_of_a_difference(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            tally = self.compare.Comparisons(pathlib.Path(d))
            tally.record_whole("babbage", "epoch 7", {"slot": 1, "matched": True})
            tally.record_whole("babbage", "epoch 8", {"slot": 2, "matched": False,
                                                     "firstDifference": {"offset": 9, "path": [1]},
                                                     "referenceBytes": 10, "scalaBytes": 10, "kept": "x"})
            tally.record_whole("babbage", "epoch 9", {"error": "socket"})
            tally.record_whole("babbage", "epoch 9", {"skipped": "no socket"})
            row = tally.whole["babbage"]
            self.assertEqual((row["matched"], row["mismatched"], row["errors"], row["skipped"]), (1, 1, 1, 1))
            self.assertEqual(row["differences"][0]["firstDifference"], {"offset": 9, "path": [1]})
            self.assertNotIn("byron", tally.whole)

    def test_a_whole_state_difference_error_or_shortfall_fails_the_run(self):
        from types import SimpleNamespace
        eras = self.compare.ERAS
        rows = {era: {"matched": 5, "mismatched": 0, "errors": 0, "skipped": 0} for era in eras}
        whole = {era: {"matched": 5, "mismatched": 0, "errors": 0, "skipped": 0} for era in eras[1:]}
        tally = SimpleNamespace(per_era=rows, whole=whole)
        self.assertEqual(self.compare.judge_comparisons(tally, 3, True), [])
        whole["mary"]["mismatched"] = 1
        whole["alonzo"]["errors"] = 1
        whole["babbage"]["matched"] = 2
        failures = self.compare.judge_comparisons(tally, 3, True)
        self.assertEqual(len(failures), 3, failures)
        self.assertTrue(failures[0].startswith("mary: 1 same-point DebugNewEpochState answers differ"))
        self.assertEqual(failures[1], "alonzo: 1 same-point DebugNewEpochState comparison errors")
        self.assertEqual(failures[2], "babbage: only 2 matched same-point DebugNewEpochState answers (need 3)")
