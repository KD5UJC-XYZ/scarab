# SPDX-License-Identifier: Apache-2.0
"""Which epoch-boundary digests the devnet oracle compares (`scripts/oracle/finality.py`, issue 91).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from oracle import compare, finality  # noqa: E402
from oracle.finality import Block, Dump, FollowerChain, Verdict  # noqa: E402

K = 3


def h(n):
    return f"{n:064x}"


def extended(slot, n):
    return {"event": "extended", "block": {"slot": slot, "blockNo": n, "hash": h(n)}}


def rolled_back(from_slot, from_n, to_slot, to_n):
    return {"event": "rolledBack", "from": {"slot": from_slot, "hash": h(from_n)},
            "to": {"slot": to_slot, "hash": h(to_n)}, "depth": 1}


def dump(slot, n):
    return Dump(slot, h(n), pathlib.Path(f"{slot:012d}-{h(n)}.json"))


class ReferenceChainTest(unittest.TestCase):
    def test_the_show_slot_block_no_lines_are_parsed(self):
        text = (f"[0.1s] Started ShowSlotBlockNo\n[0.2s] BlockNo 1\tSlotNo 20\t{h(1)}\n"
                f"[0.2s] BlockNo 2\tSlotNo 45\t{h(2)}\n[0.3s] Done\n")
        self.assertEqual(finality.reference_chain(text), [Block(20, h(1)), Block(45, h(2))])

    def test_the_first_block_at_or_after_a_slot(self):
        chain = [Block(20, h(1)), Block(45, h(2))]
        self.assertEqual(finality.first_reference_block(chain, 21), {"slot": 45, "hash": h(2)})
        self.assertIsNone(finality.first_reference_block(chain, 46))


class FollowerChainTest(unittest.TestCase):
    def test_a_rollback_truncates_to_its_point(self):
        events = [extended(10, 1), extended(20, 2), extended(30, 3), rolled_back(30, 3, 20, 2), extended(31, 4)]
        chain = FollowerChain.from_events(events)
        self.assertEqual([b.hash for b in chain.blocks], [h(1), h(2), h(4)])
        self.assertIsNone(chain.depth(h(3)))
        self.assertEqual(chain.depth(h(1)), 2)
        self.assertEqual(chain.tip, Block(31, h(4)))

    def test_a_rollback_to_origin_empties_the_chain(self):
        chain = FollowerChain.from_events([extended(10, 1), {"event": "rolledBack", "from": {"slot": 10, "hash": h(1)},
                                                             "to": {"origin": True}, "depth": 1}])
        self.assertEqual(chain.blocks, [])

    def test_a_rollback_to_a_block_never_held_is_an_error(self):
        with self.assertRaises(finality.ChainRebuildError):
            FollowerChain.from_events([extended(10, 1), rolled_back(10, 1, 5, 9)])


class ClassifyTest(unittest.TestCase):
    """Reference final chain 1..6 at slots 10..60; the follower's chain varies per case."""
    REFERENCE = {h(n): Block(10 * n, h(n)) for n in range(1, 7)}
    TIP = 60

    def verdict(self, d, events):
        return finality.classify(d, self.REFERENCE, self.TIP, FollowerChain.from_events(events), K)

    def test_a_block_on_both_final_chains_is_compared(self):
        events = [extended(10 * n, n) for n in range(1, 8)]
        self.assertEqual(self.verdict(dump(30, 3), events), Verdict.COMPARE)

    def test_the_one_block_fork_of_issue_91_is_excluded(self):
        # the follower dumped block 30 (hash 30), the reference rolled it back and made block 3 at slot 30 again
        events = [extended(10, 1), extended(20, 2), extended(30, 30), rolled_back(30, 30, 20, 2),
                  *[extended(10 * n, n) for n in range(3, 8)]]
        self.assertEqual(self.verdict(dump(30, 30), events), Verdict.ROLLED_BACK)

    def test_a_block_the_follower_held_less_than_k_deep_is_excluded(self):
        events = [extended(10 * n, n) for n in range(1, 6)] + [extended(60, 66), extended(70, 77)]
        self.assertEqual(self.verdict(dump(60, 66), events), Verdict.NOT_FINAL_ON_FOLLOWER)

    def test_a_block_the_follower_holds_k_deep_and_the_reference_does_not_is_a_divergence(self):
        events = [extended(10, 1), extended(20, 2), extended(30, 30), *[extended(10 * n, 100 + n) for n in range(4, 8)]]
        self.assertEqual(self.verdict(dump(30, 30), events), Verdict.DIVERGED)

    def test_a_block_after_the_immutable_tip_is_not_judged(self):
        events = [extended(10 * n, n) for n in range(1, 9)]
        self.assertEqual(self.verdict(dump(80, 8), events), Verdict.NOT_YET_IMMUTABLE)
        self.assertEqual(finality.classify(dump(10, 1), {}, None, FollowerChain.from_events(events), K),
                         Verdict.NOT_YET_IMMUTABLE)

    def test_an_immutable_reference_block_the_follower_left(self):
        recent = [extended(10, 1), extended(20, 2), rolled_back(20, 2, 10, 1), extended(25, 50)]
        self.assertEqual(self.verdict(dump(20, 2), recent), Verdict.ROLLED_BACK_ON_FOLLOWER)
        deep = [extended(10, 1), extended(20, 2), rolled_back(20, 2, 10, 1),
                *[extended(20 + n, 50 + n) for n in range(1, 5)]]
        self.assertEqual(self.verdict(dump(20, 2), deep), Verdict.DIVERGED)

    def test_same_slot_is_not_same_block(self):
        events = [extended(10 * n, n) for n in range(1, 8)]
        self.assertEqual(self.verdict(dump(30, 99), events), Verdict.ROLLED_BACK)


class SummaryTest(unittest.TestCase):
    STATE = {"epoch": 4, "treasury": 7,
             "protocolState": {"evolvingNonce": h(5), "candidateNonce": h(6), "epochNonce": h(7)}}

    def row(self, slot, verdict, epoch, **fields):
        return {"slot": slot, "hash": h(slot), "epoch": epoch, "verdict": verdict, "followerDepth": None,
                "referenceBlock": None, **fields}

    def compared(self, slot, epoch, scala):
        with tempfile.TemporaryDirectory() as t:
            path = pathlib.Path(t) / "digest.json"
            path.write_text(json.dumps(scala))
            differences = compare.compare_files({**self.STATE, "era": "conway"}, path)
        return self.row(slot, Verdict.COMPARE, epoch, matched=not differences, differenceCount=len(differences))

    def test_matched_and_excluded_digests_are_counted_with_their_reasons(self):
        rows = [self.compared(10, 1, self.STATE), self.row(20, Verdict.ROLLED_BACK, 2),
                self.compared(21, 2, self.STATE), self.row(30, Verdict.NOT_YET_IMMUTABLE, 3)]
        summary, failures = finality.summarize(rows, 4, K, 25, Block(31, h(31)))
        self.assertEqual(failures, [])
        self.assertEqual((summary["compared"], summary["matched"], summary["excluded"]), (2, 2, 2))
        self.assertEqual(summary["excludedByReason"], {"rolledBack": 1, "notYetImmutable": 1})
        self.assertEqual([e["slot"] for e in summary["exclusions"]], [20, 30])
        self.assertEqual(summary["epochsWithoutMatch"], [3])
        self.assertEqual(summary["followerTip"], {"slot": 31, "hash": h(31)})
        written = json.loads(json.dumps(summary))
        self.assertEqual([r["verdict"] for r in written["results"]],
                         ["compare", "rolledBack", "compare", "notYetImmutable"])
        self.assertEqual(written["exclusions"][0]["verdict"], "rolledBack")

    def test_a_run_where_every_digest_is_excluded_fails(self):
        rows = [self.row(20, Verdict.ROLLED_BACK, 2), self.row(30, Verdict.NOT_YET_IMMUTABLE, 3)]
        _, failures = finality.summarize(rows, 2, K, 25, None)
        self.assertEqual(len(failures), 1)
        self.assertIn("no epoch-boundary state was compared and matched", failures[0])

    def test_a_perturbed_nonce_fails(self):
        perturbed = {**self.STATE, "protocolState": {**self.STATE["protocolState"], "epochNonce": h(8)}}
        row = self.compared(10, 1, perturbed)
        self.assertFalse(row["matched"])
        _, failures = finality.summarize([self.compared(20, 2, self.STATE), row], 2, K, 25, None)
        self.assertEqual(len(failures), 1)
        self.assertIn("1 of 2 compared epoch-boundary states differ", failures[0])

    def test_a_divergence_fails(self):
        rows = [self.compared(10, 1, self.STATE), self.row(20, Verdict.DIVERGED, 2)]
        _, failures = finality.summarize(rows, 2, K, 25, None)
        self.assertEqual(len(failures), 1)
        self.assertIn("at least 3 deep", failures[0])

    def test_an_error_in_a_compared_digest_fails(self):
        rows = [self.compared(10, 1, self.STATE), self.row(20, Verdict.COMPARE, 2, error="db-analyser failed")]
        summary, failures = finality.summarize(rows, 2, K, 25, None)
        self.assertEqual(summary["errors"], 1)
        self.assertEqual(len(failures), 1)


class LoadTest(unittest.TestCase):
    def test_digests_that_share_a_slot_are_separate(self):
        with tempfile.TemporaryDirectory() as t:
            for n in (1, 2):
                (pathlib.Path(t) / f"{100:012d}-{h(n)}.json").write_text("{}")
            (pathlib.Path(t) / f"{100:012d}-{h(1)}.full").write_text("{}")
            dumps = finality.load(t)
        self.assertEqual([(d.slot, d.hash) for d in dumps], [(100, h(1)), (100, h(2))])


if __name__ == "__main__":
    unittest.main()
