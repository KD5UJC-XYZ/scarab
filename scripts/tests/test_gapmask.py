# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the gap mask of the ledger-state answers (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cbor, cborspan, gapmask  # noqa: E402

POOL = b"\x01" * 28


def pool_snapshot(delegators):
    """A StakePoolSnapShot: ten fields, `numDelegators` the ninth."""
    return [10, [1, 2], [], 0, b"\x02" * 32, 5, 6, [1, 10], delegators, [0, b"\x03" * 28]]


def nes(delegators=2, vrf=None, likelihoods=None, pot=0, update=None, extra_balance=100):
    """A NewEpochState in the Conway layout, with the places of the mask and a few others."""
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
    return gapmask.compare(label, answer(reference, label.startswith("GetCBOR/"), label.endswith("DebugEpochState")),
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


class GapMaskTest(unittest.TestCase):
    LABELS = gapmask.LABELS

    def test_equal_answers_are_equal_and_report_the_places_masked(self):
        for label in self.LABELS:
            v = verdict(label, nes(), nes())
            self.assertTrue(v.equal and v.raw_equal, (label, v.problems))
            self.assertEqual(v.masked["NonMyopic"]["items"], 1)
            self.assertEqual(v.masked["SnapshotNumDelegators"]["items"], 3)
            self.assertEqual(v.masked["VrfKeyHashes"]["items"], 1)

    def test_the_documented_gaps_are_allowed(self):
        reference = nes(delegators=2, vrf={}, likelihoods={POOL: [b"\x3f\xf8\x00\x00\x00\x00\x00\x00"]}, pot=123456789012)
        scala = nes(delegators=4, vrf={b"\x07" * 32: 1}, likelihoods={}, pot=0)
        for label in self.LABELS:
            v = verdict(label, reference, scala)
            self.assertTrue(v.equal, (label, v.problems))
            self.assertFalse(v.raw_equal)
            self.assertEqual(v.masked["NonMyopic"]["differing"], 1)
            self.assertEqual(v.masked["SnapshotNumDelegators"]["differing"], 3)
            self.assertEqual(v.masked["VrfKeyHashes"]["differing"], 1)
            self.assertGreater(v.masked["NonMyopic"]["referenceBytes"], v.masked["NonMyopic"]["scalaBytes"])

    def test_a_difference_anywhere_else_is_not_allowed(self):
        for label in self.LABELS:
            v = verdict(label, nes(extra_balance=100), nes(extra_balance=101))
            self.assertFalse(v.equal, label)
            self.assertIn("the answers differ outside the masked places", v.problems)

    def test_the_reward_update_is_masked_only_while_the_reference_holds_one(self):
        held = verdict("DebugNewEpochState", nes(update=[1, 2, {POOL: 5}]), nes(update=[1, 2, {}]))
        self.assertTrue(held.equal, held.problems)
        self.assertEqual(held.masked["RewardUpdate"]["differing"], 1)
        # the reference has none: a Scala update is a difference
        extra = verdict("DebugNewEpochState", nes(), nes(update=[1, 2, {}]))
        self.assertFalse(extra.equal)
        self.assertNotIn("RewardUpdate", extra.masked)
        # an EpochState has no reward update to mask
        self.assertNotIn("RewardUpdate", verdict("DebugEpochState", nes(update=[1]), nes(update=[1])).masked)

    def test_a_mask_that_hides_too_much_fails(self):
        huge = {bytes([i]) * 28: [b"\x3f\xf8\x00\x00\x00\x00\x00\x00"] * 40 for i in range(200)}
        v = verdict("DebugNewEpochState", nes(likelihoods=huge), nes())
        self.assertFalse(v.equal)
        self.assertTrue(any("hides" in p for p in v.problems), v.problems)

    def test_a_place_that_is_missing_or_of_another_kind_fails(self):
        wrong_kind = nes()
        wrong_kind[3][3] = 7  # NonMyopic is not an array
        v = verdict("DebugNewEpochState", wrong_kind, nes())
        self.assertFalse(v.equal)
        self.assertTrue(any("NonMyopic" in p for p in v.problems), v.problems)
        short = nes()
        short[3] = short[3][:3]  # no non-myopic field at all
        v = verdict("DebugNewEpochState", short, nes())
        self.assertFalse(v.equal)
        self.assertTrue(any("missing" in p for p in v.problems), v.problems)

    def test_a_different_number_of_places_fails(self):
        scala = nes()
        scala[3][2][0][1] = {}  # the mark snapshot has no pools: two places fewer than the reference
        v = verdict("DebugNewEpochState", nes(), scala)
        self.assertFalse(v.equal)

    def test_something_that_is_not_an_answer_fails(self):
        v = gapmask.compare("DebugNewEpochState", b"\x01", b"\x01")
        self.assertFalse(v.equal)
        self.assertTrue(v.problems)

    def test_the_gap_list_is_the_documented_one(self):
        self.assertEqual(gapmask.GAPS, ("NonMyopic", "SnapshotNumDelegators", "VrfKeyHashes", "RewardUpdate"))

    def test_totals_accumulate_per_label(self):
        total = {}
        gapmask.add_to(total, verdict("DebugNewEpochState", nes(), nes(delegators=9)))
        gapmask.add_to(total, verdict("DebugNewEpochState", nes(), nes()))
        self.assertEqual((total["answers"], total["byteEqual"], total["equalMasked"]), (2, 1, 2))
        self.assertEqual(total["rules"]["SnapshotNumDelegators"]["items"], 6)
        self.assertEqual(total["rules"]["SnapshotNumDelegators"]["differing"], 3)


if __name__ == "__main__":
    unittest.main()
