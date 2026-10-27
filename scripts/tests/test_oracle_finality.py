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
from oracle.finality import Block, Cause, ChainRebuildError, Dump, FollowerChain, Verdict  # noqa: E402

K = 3


def h(n):
    return f"{n:064x}"


def blk(slot, n, no):
    """Block `n` (its hash h(n)) at `slot`, block number `no`."""
    return Block(slot, h(n), no)


def started():
    return {"event": "started"}


def connected(at=None):
    return {"event": "connected", "intersection": {"origin": True} if at is None else {"slot": at.slot, "hash": at.hash}}


def extended(b, era="Conway"):
    return {"event": "extended", "block": {"slot": b.slot, "blockNo": b.block_no, "hash": b.hash}, "era": era}


def rolled_back(tip, to, depth):
    return {"event": "rolledBack", "from": {"slot": tip.slot, "hash": tip.hash},
            "to": {"slot": to.slot, "hash": to.hash}, "depth": depth}


def numbered(events):
    return list(enumerate(events, start=1))


def session(*blocks):
    """A session from Origin that extends `blocks` in order."""
    return [started(), connected(), *[extended(b) for b in blocks]]


def chain_of(events):
    return FollowerChain.from_events(numbered(events))


def linear(n, first=1, slot_step=10, base=0, offset=0):
    """Blocks with hashes first..first+n-1 and numbers base..base+n-1 at slots (base + i + 1) * slot_step + offset."""
    return [blk((base + i + 1) * slot_step + offset, first + i, base + i) for i in range(n)]


def dump_of(b):
    return Dump(b.slot, b.hash, pathlib.Path(f"{b.slot:012d}-{b.hash}.json"))


class ReferenceChainTest(unittest.TestCase):
    def test_the_show_slot_block_no_lines_are_parsed(self):
        text = (f"[0.1s] Started ShowSlotBlockNo\n[0.2s] BlockNo 0\tSlotNo 0\t{h(1)}\n"
                f"[0.2s] BlockNo 1\tSlotNo 0\t{h(2)}\n[0.2s] BlockNo 2\tSlotNo 45\t{h(3)}\n[0.3s] Done\n")
        chain = finality.reference_chain(text)
        self.assertEqual(chain, [blk(0, 1, 0), blk(0, 2, 1), blk(45, 3, 2)])
        finality.check_reference(chain, 45)

    def test_a_listing_that_is_not_one_chain_to_the_tip_is_refused(self):
        chain = [blk(0, 1, 0), blk(10, 2, 1), blk(30, 4, 3)]
        for bad, tip in ((chain, 30), (chain[:2], 30), ([], 10), (chain[:1], None)):
            with self.subTest(blocks=len(bad), tip=tip), self.assertRaises(ChainRebuildError) as e:
                finality.check_reference(bad, tip)
            self.assertIs(e.exception.cause, Cause.REFERENCE_LISTING)
        finality.check_reference([], None)

    def test_the_first_block_at_or_after_a_slot(self):
        chain = [blk(20, 1, 0), blk(45, 2, 1)]
        self.assertEqual(finality.first_reference_block(chain, 21), {"slot": 45, "hash": h(2)})
        self.assertIsNone(finality.first_reference_block(chain, 46))


class ParseLogTest(unittest.TestCase):
    def benign(self, line):
        return line.startswith("SLF4J")

    def test_json_lines_and_known_output(self):
        text = '{"event": "started"}\nSLF4J: banner\n\n{"event": "connected", "intersection": {"origin": true}}\n'
        self.assertEqual([n for n, _ in finality.parse_log(text, self.benign)], [1, 4])

    def test_a_malformed_line_fails(self):
        # Codex's case: extensions at slots 10, 20, 30 with the slot-20 line damaged
        lines = [json.dumps(e) for e in session(*linear(3))]
        lines[3] = lines[3][:-5]
        with self.assertRaises(ChainRebuildError) as e:
            finality.parse_log("\n".join(lines) + "\n", self.benign)
        self.assertEqual((e.exception.cause, e.exception.line), (Cause.MALFORMED_LINE, 4))

    def test_a_log_that_ends_inside_a_line_fails(self):
        with self.assertRaises(ChainRebuildError) as e:
            finality.parse_log('{"event": "started"}\n{"event": "exte', self.benign)
        self.assertIs(e.exception.cause, Cause.PARTIAL_LINE)


class FollowerChainTest(unittest.TestCase):
    def test_a_rollback_removes_its_depth_back_to_its_point(self):
        a, b, c = linear(3)
        d = blk(31, 4, 2)
        chain = chain_of([*session(a, b, c), rolled_back(c, b, 1), extended(d)])
        self.assertEqual(chain.blocks, [a, b, d])
        self.assertIsNone(chain.depth(c.hash))
        self.assertEqual(chain.depth(a.hash), 2)
        self.assertEqual(chain.seen, {a.hash, b.hash, c.hash, d.hash})

    def test_a_byron_boundary_block_may_share_its_successors_slot(self):
        ebb, first = blk(0, 1, 0), blk(0, 2, 1)
        chain = FollowerChain.from_events(numbered([started(), connected(), extended(ebb, "Byron"),
                                                    extended(first, "Byron")]), 50)
        self.assertEqual(chain.blocks, [ebb._replace(boundary=True), first])
        with self.assertRaises(ChainRebuildError) as e:
            chain_of([started(), connected(), extended(ebb, "Shelley"), extended(first, "Shelley")])
        self.assertIs(e.exception.cause, Cause.DISCONTINUOUS)

    def test_a_later_byron_boundary_block_repeats_its_predecessors_number(self):
        # regular -> boundary at the first slot of Byron epoch 1 -> regular at that slot, with 50-slot epochs
        regular, boundary, after = blk(49, 1, 7), blk(50, 2, 7), blk(50, 3, 8)
        events = [started(), connected(), extended(blk(0, 9, 0), "Byron"), *[extended(blk(i * 7, 10 + i, i), "Byron")
                  for i in range(1, 7)], extended(regular, "Byron"), extended(boundary, "Byron"), extended(after, "Byron")]
        chain = FollowerChain.from_events(numbered(events), 50)
        self.assertEqual([b.hash for b in chain.blocks[-3:]], [regular.hash, boundary.hash, after.hash])
        self.assertTrue(chain.blocks[-2].boundary)
        for bad in (blk(51, 2, 7), blk(50, 2, 6)):  # a repeated number off the epoch's first slot; a lower number
            with self.subTest(block=bad), self.assertRaises(ChainRebuildError) as e:
                FollowerChain.from_events(numbered(events[:-2] + [extended(bad, "Byron")]), 50)
            self.assertIs(e.exception.cause, Cause.DISCONTINUOUS)
        with self.assertRaises(ChainRebuildError):  # the same slot after a block that is not a boundary block
            FollowerChain.from_events(numbered(events[:-2] + [extended(blk(49, 4, 8), "Byron")]), 50)

    def test_a_missing_extension_is_a_gap(self):
        a, _, c = linear(3)
        with self.assertRaises(ChainRebuildError) as e:
            chain_of(session(a, c))
        self.assertIs(e.exception.cause, Cause.DISCONTINUOUS)

    def test_a_missing_rollback_line_is_a_gap(self):
        a, b, c = linear(3)
        with self.assertRaises(ChainRebuildError) as e:
            chain_of([*session(a, b, c), extended(blk(31, 9, 2))])
        self.assertIs(e.exception.cause, Cause.DISCONTINUOUS)

    def test_rollbacks_must_start_at_the_tip_remove_their_depth_and_end_on_the_chain(self):
        a, b, c = linear(3)
        cases = ((rolled_back(b, a, 1), Cause.ROLLBACK_FROM), (rolled_back(c, a, 1), Cause.ROLLBACK_DEPTH),
                 (rolled_back(c, blk(15, 9, 1), 1), Cause.ROLLBACK_TO))
        for event, cause in cases:
            with self.subTest(cause=cause), self.assertRaises(ChainRebuildError) as e:
                chain_of([*session(a, b, c), event])
            self.assertIs(e.exception.cause, cause)

    def test_a_restart_from_origin_starts_a_new_chain(self):
        # Codex's case: a run followed A -> B, the next run A -> C; B is not on the final chain
        a, b = linear(2)
        c = blk(21, 3, 1)
        chain = chain_of([*session(a, b), *session(a, c)])
        self.assertEqual(chain.blocks, [a, c])
        self.assertIsNone(chain.depth(b.hash))

    def test_replayed_hashes_and_a_fork_that_changes_across_a_restart(self):
        a, b, c = linear(3)
        d = blk(31, 4, 2)
        events = [*session(a, b, c), rolled_back(c, b, 1), extended(d), *session(a, b, c)]
        chain = chain_of(events)
        self.assertEqual(chain.blocks, [a, b, c])
        self.assertIsNone(chain.depth(d.hash))
        with self.assertRaises(ChainRebuildError) as e:
            chain_of([*session(a, b), extended(b)])
        self.assertIs(e.exception.cause, Cause.DUPLICATE_BLOCK)

    def test_a_session_restored_from_a_database_cannot_be_modelled(self):
        a, b, c = linear(3)
        with self.assertRaises(ChainRebuildError) as e:
            chain_of([*session(a, b), started(), connected(b), extended(c)])
        self.assertIs(e.exception.cause, Cause.INTERSECTION)

    def test_a_reconnection_intersects_on_the_chain(self):
        a, b, c = linear(3)
        self.assertEqual(chain_of([*session(a, b), connected(b), extended(c)]).blocks, [a, b, c])
        with self.assertRaises(ChainRebuildError) as e:
            chain_of([*session(a, b), connected(blk(15, 9, 1))])
        self.assertIs(e.exception.cause, Cause.INTERSECTION)

    def test_a_reconnection_at_origin_before_the_first_block(self):
        a = blk(10, 1, 0)
        self.assertEqual(chain_of([started(), connected(), connected(), extended(a)]).blocks, [a])

    def test_chain_events_before_the_first_start_fail(self):
        with self.assertRaises(ChainRebuildError) as e:
            chain_of([connected(), extended(blk(10, 1, 0))])
        self.assertIs(e.exception.cause, Cause.BEFORE_START)

    def test_the_final_tip_must_be_the_followers(self):
        a, b = linear(2)
        chain = chain_of(session(a, b))
        chain.check_final_tip({"slot": b.slot, "hash": b.hash})
        for final, cause in (({"slot": a.slot, "hash": a.hash}, Cause.FINAL_TIP), (None, Cause.NO_FINAL_TIP)):
            with self.subTest(cause=cause), self.assertRaises(ChainRebuildError) as e:
                chain.check_final_tip(final)
            self.assertIs(e.exception.cause, cause)

    def test_the_immutable_tip_has_k_blocks_after_it(self):
        blocks = linear(5)
        self.assertEqual(chain_of(session(*blocks)).immutable_tip(K), blocks[1])
        self.assertIsNone(chain_of(session(*blocks[:3])).immutable_tip(K))


class ClassifyTest(unittest.TestCase):
    """Reference final chain: blocks 1..6 at slots 10..60 (numbers 0..5)."""
    REFERENCE = linear(6)
    TIP = 60

    def verdict(self, b, events, reference=None):
        reference = self.REFERENCE if reference is None else reference
        chain = chain_of(events)
        agree = finality.final_chains_agree(reference, chain, K)
        return finality.classify(dump_of(b), {r.hash: r for r in reference}, reference[-1].slot, chain, K, agree)

    def test_a_block_on_both_final_chains_is_compared(self):
        blocks = linear(7)
        self.assertEqual(self.verdict(blocks[2], session(*blocks)), Verdict.COMPARE)

    def test_the_one_block_fork_of_issue_91_is_excluded(self):
        # the follower digested block 30 at slot 30 and rolled it back for the reference's block 3 at the same slot
        a, b, c = self.REFERENCE[:3]
        orphan = blk(30, 30, 2)
        events = [*session(a, b, orphan), rolled_back(orphan, b, 1), extended(c), *[extended(x) for x in linear(4, 4, base=3)]]
        self.assertEqual(self.verdict(orphan, events), Verdict.ROLLED_BACK)

    def test_a_block_the_follower_held_less_than_k_deep_is_excluded(self):
        fork = [blk(60, 66, 5), blk(70, 77, 6)]
        self.assertEqual(self.verdict(fork[0], session(*self.REFERENCE[:5], *fork)), Verdict.NOT_FINAL_ON_FOLLOWER)

    def test_a_block_the_follower_holds_k_deep_and_the_reference_does_not_is_a_divergence(self):
        a, b = self.REFERENCE[:2]
        fork = linear(5, first=100, base=2, offset=5)
        self.assertEqual(self.verdict(fork[0], session(a, b, *fork)), Verdict.DIVERGED)

    def test_a_block_after_the_immutable_tip_is_not_judged(self):
        blocks = linear(8)
        self.assertEqual(self.verdict(blocks[7], session(*blocks)), Verdict.NOT_YET_IMMUTABLE)

    def test_exactly_k_later_blocks_do_not_finalize_a_fork(self):
        # Codex's case, k = 3: shared A@10, the reference's immutable B@20, the follower A@10 -> C@30 -> D@40 -> E@50.
        # C has only two descendants: the follower's immutable tip is A, and it can still roll back to A and adopt B.
        a, b = blk(10, 1, 0), blk(20, 2, 1)
        c, d, e = blk(30, 3, 1), blk(40, 4, 2), blk(50, 5, 3)
        events = [*session(a, b), rolled_back(b, a, 1), extended(c), extended(d), extended(e)]
        chain = chain_of(events)
        self.assertEqual(chain.immutable_tip(K), a)
        self.assertTrue(finality.final_chains_agree([a, b], chain, K))
        self.assertEqual(self.verdict(b, events, [a, b]), Verdict.ROLLED_BACK_ON_FOLLOWER)

    def test_a_finalized_divergent_fork(self):
        # one block more than the exact-k case: C has three descendants and is immutable on the follower
        a, b = blk(10, 1, 0), blk(20, 2, 1)
        fork = [blk(30, 3, 1), blk(40, 4, 2), blk(50, 5, 3), blk(60, 6, 4)]
        events = [*session(a, b), rolled_back(b, a, 1), *[extended(x) for x in fork]]
        chain = chain_of(events)
        self.assertFalse(finality.final_chains_agree([a, b], chain, K))
        self.assertEqual(self.verdict(b, events, [a, b]), Verdict.DIVERGED)

    def test_a_restart_does_not_keep_a_block_of_an_earlier_session(self):
        # Codex's case: B, digested in the first run, is immutable on the reference; the final run went A -> C
        a, b = self.REFERENCE[:2]
        fork = linear(4, first=100, base=1, offset=5)
        events = [*session(a, b), *session(a, *fork)]
        self.assertNotEqual(self.verdict(b, events), Verdict.COMPARE)
        self.assertEqual(self.verdict(b, events), Verdict.DIVERGED)

    def test_same_slot_is_not_same_block(self):
        blocks = linear(7)
        twin = blk(30, 99, 2)
        self.assertEqual(self.verdict(twin, [*session(*blocks[:2], twin), rolled_back(twin, blocks[1], 1),
                                             *[extended(x) for x in blocks[2:]]]), Verdict.ROLLED_BACK)

    def test_a_digest_the_log_never_extended_is_unknown(self):
        blocks = linear(7)
        self.assertEqual(self.verdict(blk(30, 99, 2), session(*blocks)), Verdict.UNLOGGED)


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

    def summarize(self, rows, agree=True):
        return finality.summarize(rows, len(rows), K, 25, blk(31, 31, 3), agree)

    def test_matched_and_excluded_digests_are_counted_with_their_reasons(self):
        rows = [self.compared(10, 1, self.STATE), self.row(20, Verdict.ROLLED_BACK, 2),
                self.compared(21, 2, self.STATE), self.row(30, Verdict.NOT_YET_IMMUTABLE, 3)]
        summary, failures = self.summarize(rows)
        self.assertEqual(failures, [])
        self.assertEqual((summary["compared"], summary["matched"], summary["excluded"]), (2, 2, 2))
        self.assertEqual(summary["excludedByReason"], {"rolledBack": 1, "notYetImmutable": 1})
        self.assertEqual([e["slot"] for e in summary["exclusions"]], [20, 30])
        self.assertEqual(summary["epochsWithoutMatch"], [3])
        self.assertEqual(summary["followerTip"], {"slot": 31, "hash": h(31), "blockNo": 3})
        written = json.loads(json.dumps(summary))
        self.assertEqual([r["verdict"] for r in written["results"]],
                         ["compare", "rolledBack", "compare", "notYetImmutable"])
        self.assertEqual(written["exclusions"][0]["verdict"], "rolledBack")

    def test_a_run_where_every_digest_is_excluded_fails(self):
        _, failures = self.summarize([self.row(20, Verdict.ROLLED_BACK, 2), self.row(30, Verdict.NOT_YET_IMMUTABLE, 3)])
        self.assertEqual(len(failures), 1)
        self.assertIn("no epoch-boundary state was compared and matched", failures[0])

    def test_a_perturbed_nonce_fails(self):
        perturbed = {**self.STATE, "protocolState": {**self.STATE["protocolState"], "epochNonce": h(8)}}
        row = self.compared(10, 1, perturbed)
        self.assertFalse(row["matched"])
        _, failures = self.summarize([self.compared(20, 2, self.STATE), row])
        self.assertEqual(len(failures), 1)
        self.assertIn("1 of 2 compared epoch-boundary states differ", failures[0])

    def test_a_divergence_fails(self):
        _, failures = self.summarize([self.compared(10, 1, self.STATE), self.row(20, Verdict.DIVERGED, 2)])
        self.assertEqual(len(failures), 1)
        self.assertIn("the other cannot adopt", failures[0])

    def test_final_chains_that_disagree_fail(self):
        _, failures = self.summarize([self.compared(10, 1, self.STATE)], agree=False)
        self.assertEqual(len(failures), 1)
        self.assertIn("the final chains disagree", failures[0])

    def test_an_unlogged_digest_fails(self):
        _, failures = self.summarize([self.compared(10, 1, self.STATE), self.row(20, Verdict.UNLOGGED, 2)])
        self.assertEqual(len(failures), 1)
        self.assertIn("never extended", failures[0])

    def test_an_error_in_a_compared_digest_fails(self):
        rows = [self.compared(10, 1, self.STATE), self.row(20, Verdict.COMPARE, 2, error="db-analyser failed")]
        summary, failures = self.summarize(rows)
        self.assertEqual(summary["errors"], 1)
        self.assertEqual(len(failures), 1)


class RunTest(unittest.TestCase):
    def test_an_undecodable_log_fails_the_comparison_instead_of_raising(self):
        from oracle import devnet
        with tempfile.TemporaryDirectory() as t:
            root = pathlib.Path(t)
            (root / "dump").mkdir()
            (root / "dump" / f"{10:012d}-{h(1)}.json").write_text("{}")
            (root / "follower.jsonl").write_bytes(b'{"event": "started"}\n\xff\xfe\n')
            summary, failures = devnet.run("pw-test", root)
        self.assertEqual(summary, {"digests": 1})
        self.assertEqual(len(failures), 1)
        self.assertIn("cannot be rebuilt from its log", failures[0])

    def test_an_unreadable_final_tip_fails_the_comparison(self):
        from oracle import devnet
        with tempfile.TemporaryDirectory() as t:
            root = pathlib.Path(t)
            (root / "dump").mkdir()
            (root / "dump" / f"{10:012d}-{h(1)}.json").write_text("{}")
            events = session(blk(10, 1, 0))
            (root / "follower.jsonl").write_text("".join(json.dumps(e) + "\n" for e in events))
            (root / devnet.FINAL_TIP).write_text("{not json")
            _, failures = devnet.run("pw-test", root)
        self.assertEqual(len(failures), 1)
        self.assertIn("cannot be rebuilt from its log", failures[0])


class StopFollowerTest(unittest.TestCase):
    """`stop_follower` against a scripted docker: SIGCONT and the follower's removal in every outcome."""
    TIP = {"point": {"slot": 40, "hash": h(4)}}

    def run_stop(self, stop=0, remove=0):
        """stop/remove: 0 (exit 0), a nonzero exit code, or "timeout" (after delivery, for stop)."""
        import subprocess
        from types import SimpleNamespace
        from oracle import devnet
        calls = []

        def docker(*args, check=True, capture=True, timeout=None):
            calls.append(args)
            if args[0] == "exec" and args[1].endswith("-ref") and "kill -STOP" in args[-1]:
                if stop == "timeout":
                    raise subprocess.TimeoutExpired(args, timeout)
                return SimpleNamespace(returncode=stop, stdout="", stderr="")
            if args[0] == "exec" and args[1].endswith("-scala"):
                return SimpleNamespace(returncode=0, stdout=json.dumps(self.TIP), stderr="")
            if args[0] == "rm":
                if remove == "timeout":
                    raise subprocess.TimeoutExpired(args, timeout)
                return SimpleNamespace(returncode=remove, stdout="", stderr="")
            if args[0] == "ps":
                return SimpleNamespace(returncode=0, stdout="" if remove == 0 else "abc\n", stderr="")
            return SimpleNamespace(returncode=0, stdout="", stderr="")

        listing = SimpleNamespace(stdout=f"7 /bin/sh -c cardano-node run --database-path {devnet.NODE_DB} \n"
                                         f"9 cardano-node run --database-path {devnet.NODE_DB} --port 1\n")
        saved = devnet.docker, devnet.in_ref
        devnet.docker, devnet.in_ref = docker, lambda name, script, timeout=300: listing
        try:
            with tempfile.TemporaryDirectory() as t:
                root = pathlib.Path(t)
                try:
                    devnet.stop_follower("pw-test", root, settle_seconds=0, attempts=2)
                    raised = None
                except SystemExit as e:
                    raised = str(e)
                recorded = (root / devnet.FINAL_TIP).exists()
        finally:
            devnet.docker, devnet.in_ref = saved
        kills = [c[-1] for c in calls if c[0] == "exec" and "kill" in c[-1]]
        return kills, recorded, raised

    def test_the_settled_tip_is_recorded_and_the_node_continued(self):
        kills, recorded, raised = self.run_stop()
        self.assertEqual(kills, ["kill -STOP 9", "kill -CONT 9"])
        self.assertTrue(recorded)
        self.assertIsNone(raised)

    def test_a_timeout_after_the_stop_was_delivered_still_continues_the_node(self):
        kills, recorded, raised = self.run_stop(stop="timeout")
        self.assertEqual(kills, ["kill -STOP 9", "kill -CONT 9"])
        self.assertFalse(recorded)
        self.assertIsNone(raised)

    def test_a_removal_that_times_out_or_fails_records_no_tip_and_fails(self):
        for remove in ("timeout", 1):
            with self.subTest(remove=remove):
                kills, recorded, raised = self.run_stop(remove=remove)
                self.assertEqual(kills, ["kill -STOP 9", "kill -CONT 9"])
                self.assertFalse(recorded)
                self.assertIn("could not be confirmed removed", raised)


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
