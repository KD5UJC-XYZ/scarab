# SPDX-License-Identifier: Apache-2.0
"""The pure parts of `pool --plutus-load` and of the pool's forge timing: blocks read for their transactions'
validity flags, where the load's transactions are on the chain, the refusal rule, and the timing summaries."""
import contextlib
import dataclasses
import hashlib
import io
import json
import pathlib
import tempfile
import threading
import sys
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import ntclocal, ntcquery, reference_tracing, util  # noqa: E402
from devnet.cbor import Raw, Tag, enc  # noqa: E402
from devnet.scenarios import pool, pool_plutus  # noqa: E402


def blake(data):
    return hashlib.blake2b(data, digest_size=32).digest()


def block_item(slot, bodies, witnesses, invalid):
    """A Conway block item `#6.24([7, [header, bodies, witnesses, auxiliary data, invalid]])`."""
    header = [[1, slot, None, b"\x01" * 32], b"\x02" * 64]
    return enc(Tag(24, enc([7, [header, bodies, witnesses, {}, invalid]])))


def header_hash(item):
    return ntclocal.block_point(item)[1]


class BlockTransactionsTest(unittest.TestCase):
    def test_ids_are_hashes_of_the_original_body_bytes_and_flags_come_from_the_invalid_indices(self):
        payment = {0: [[b"\x00" * 32, 0]], 1: [], 2: 170_000}
        # an indefinite-length map: the id must be the hash of these bytes, not of a canonical re-encoding
        script = Raw(b"\xbf" + enc(0) + enc([[b"\x01" * 32, 1]]) + enc(1) + enc([]) + enc(2) + enc(1_500_000) + b"\xff")
        item = block_item(42, [payment, script], [{0: []}, {0: [], 5: {(0, 0): [0, [1, 2]]}}], [1])
        self.assertEqual(ntclocal.block_transactions(item),
                         [(blake(enc(payment)).hex(), True, False), (blake(script.data).hex(), False, True)])

    def test_a_block_without_transactions(self):
        self.assertEqual(ntclocal.block_transactions(block_item(7, [], [], [])), [])

    def test_the_block_number_of_the_header(self):
        self.assertEqual(ntclocal.block_number(block_item(7, [], [], [])), 1)


class ChainFromOriginTest(unittest.TestCase):
    def test_the_chain_up_to_the_reported_tip_with_rollbacks_applied(self):
        b1, b2, b3 = (block_item(s, [], [], []) for s in (1, 2, 3))
        h1, h3 = header_hash(b1), header_hash(b3)
        tip = [[3, h3], 2]
        replies = [enc([3, [], tip]), enc([2, Raw(b1), tip]), enc([2, Raw(b2), tip]), enc([3, [1, h1], tip]),
                   enc([2, Raw(b3), tip])]

        class Session:
            def find_intersect(self, points):
                assert points == [None]
                return [5, [], tip]

            def request_next(self):
                raw = replies.pop(0)
                return ntcquery.decode(raw, 0)[0], raw
        blocks = ntclocal.chain_from_origin(Session())
        self.assertEqual([(slot, ident) for slot, ident, _ in blocks], [(1, h1), (3, h3)])
        self.assertEqual(blocks[1][2], b3)
        self.assertEqual(replies, [])


class FailurePathTest(unittest.TestCase):
    def test_the_constructors_down_to_validation_tag_mismatch(self):
        mismatch = enc([[6, [[1, [0, [0, [0, True, [1, ["the script failed"]]]]]]]]])
        self.assertEqual(pool_plutus.failure_path(mismatch), [1, 0, 0, 0])

    def test_another_rule_and_what_is_no_refusal(self):
        bad_inputs = enc([[6, [[1, [0, [1, Tag(258, [[b"\x00" * 32, 0]])]]]]]])
        self.assertEqual(pool_plutus.failure_path(bad_inputs), [1, 0, 1])
        self.assertIsNone(pool_plutus.failure_path(None))
        self.assertIsNone(pool_plutus.failure_path(b"\x80"))


def row(case, txid, language=1, passed=True):
    return {"case": case, "txid": txid, "language": language, "passed": passed, "errors": [],
            "referenceBefore": {"protocolVersion": {"major": 10, "minor": 0}}}


ROWS = [row("reject-success-invalid", "a"), row("success", "a"), row("reject-failure-valid", "b"), row("collateral", "b"),
        row("reject-success-invalid", "c", 2), row("success", "c", 2), row("reject-failure-valid", "d", 2),
        row("collateral", "d", 2)]


class InclusionTest(unittest.TestCase):
    CHAIN = [("s1", 10, 1, [("a", True, True), ("pay", True, False)]), ("h1", 20, 2, [("b", False, True)]),
             ("s2", 30, 3, [("d", False, True)]), ("h2", 40, 4, [("c", True, True)])]

    def test_counts_by_kind_and_language_in_scala_forged_and_other_blocks(self):
        placed = pool_plutus.inclusion(ROWS, self.CHAIN, {"s1", "s2"})
        self.assertEqual(placed["submitted"], {"success": 2, "reject-success-invalid": 2, "collateral": 2,
                                               "reject-failure-valid": 2})
        self.assertEqual(placed["inScalaForgedBlocks"],
                         {"succeeding": 1, "flagFalse": 1, "byLanguage": {"V1": {"succeeding": 1, "flagFalse": 0},
                                                                          "V2": {"succeeding": 0, "flagFalse": 1}}})
        self.assertEqual((placed["inOtherBlocks"]["succeeding"], placed["inOtherBlocks"]["flagFalse"]), (1, 1))
        self.assertEqual(placed["wrongFlag"], [])
        self.assertEqual(placed["acceptedNotOnChain"], [])
        self.assertEqual(placed["otherPlutusInScalaForgedBlocks"], 0)

    def test_a_failing_script_with_the_flag_true_is_a_wrong_flag_wherever_it_is(self):
        chain = [("s1", 10, 1, [("a", True, True)]), ("h1", 20, 2, [("b", True, True)]),
                 ("s2", 30, 3, [("c", False, True)]), ("h2", 40, 4, [("d", False, True)])]
        placed = pool_plutus.inclusion(ROWS, chain, {"s1", "s2"})
        self.assertEqual(placed["wrongFlag"], [{"tx": "b", "block": "h1", "valid": True, "scalaForged": False},
                                               {"tx": "c", "block": "s2", "valid": False, "scalaForged": True}])
        # neither counts as evidence of a flag carried right
        self.assertEqual((placed["inScalaForgedBlocks"]["succeeding"], placed["inScalaForgedBlocks"]["flagFalse"]), (1, 0))
        self.assertEqual((placed["inOtherBlocks"]["succeeding"], placed["inOtherBlocks"]["flagFalse"]), (0, 1))

    def test_accepted_transactions_missing_from_the_chain_and_unknown_plutus_ones(self):
        chain = [("s1", 10, 1, [("a", True, True), ("x", True, True)])]
        placed = pool_plutus.inclusion(ROWS + [row("success", "e", passed=False)], chain, {"s1"})
        self.assertEqual(placed["acceptedNotOnChain"], ["b", "c", "d"])
        self.assertEqual(placed["otherPlutusInScalaForgedBlocks"], 1)


class CheckTest(unittest.TestCase):
    SHELLEY_AT = "2026-01-01T00:00:{:06.3f}Z"

    def runner(self, refusals, rows=ROWS, errors=(), **how):
        """The evidence of a load that completed one round and ended in time (`how` changes the ending)."""
        return pool_plutus.Evidence(rows=tuple(rows), refusals=tuple(refusals), plutus_txids=frozenset(), passes=1,
                                    errors=tuple(errors),
                                    ended=None, **{"finished": True, "stopped": True, **how})

    def refusal(self, **kw):
        return {"label": "legacy-plutus-conway-v1-reject-failure-valid", "tx": "b", "reference": [1, 0, 0, 0],
                "scala": [1, 0, 0, 0], "accepted": False, "attempts": 1, "sameBytes": False, "referenceMs": 2.5,
                "scalaMs": 4.25, **kw}

    def judgements(self):
        """One judgement per refused case of `ROWS`: each language, the succeeding script with the flag false and the
        failing one with the flag true."""
        return [self.refusal(label=f"legacy-plutus-conway-v{r['language']}-{r['case']}", tx=r["txid"],
                             sameBytes=r["case"] == "reject-success-invalid", scalaMs=4.25 + i)
                for i, r in enumerate(r for r in ROWS if r["case"].startswith("reject-"))]

    @staticmethod
    def forged(hashes=("s1", "s2")):
        slots = {"s1": (10, 1, None), "s2": (30, 3, "h1")}
        return [{"event": "forged", "hash": h, "slot": slots[h][0], "blockNo": slots[h][1], "prev": slots[h][2],
                 "txs": []} for h in hashes]

    def events(self):
        return [{"event": "mempoolAdded", "tx": "a", "at": self.SHELLEY_AT.format(0.25)},
                {"event": "mempoolAdded", "tx": "d", "at": self.SHELLEY_AT.format(1.5)},
                {"event": "mempoolRejected", "tx": "b", "at": self.SHELLEY_AT.format(2),
                 "reason": "tx b violates ValidationTagMismatch: Utxow(Utxo(Utxos(ValidationTagMismatch(true,Some(x)))))"},
                {"event": "mempoolRejected", "tx": "c", "reason": "tx c is already in the mempool",
                 "at": self.SHELLEY_AT.format(2)},
                {"event": "ledgerEpoch", "phase2": {"evaluatedSuccess": 4, "evaluatedFailure": 2, "scripts": 6},
                 "at": self.SHELLEY_AT.format(3)}]

    def test_a_healthy_load_passes_and_reports_the_scala_mempool(self):
        failures, summary = pool_plutus.check(self.runner(self.judgements()), InclusionTest.CHAIN, self.forged(),
                                              self.events())
        self.assertEqual(failures, [])
        self.assertEqual(summary["protocolVersions"], ["10.0"])
        judged = summary["scalaLocalSubmission"]
        self.assertEqual((judged["expected"], judged["refusedForTheSameRule"], judged["sameBytes"], judged["missing"],
                          judged["problems"], judged["unmatched"]), (4, 4, 2, [], [], 0))
        mempool = summary["mempool"]
        self.assertEqual((mempool["accepted"], mempool["seenInScalaMempool"], mempool["localSubmissions"]), (4, 2, 4))
        self.assertEqual(mempool["rejectedByReason"],
                         {"violates ValidationTagMismatch: Utxow(Utxo(Utxos(ValidationTagMismatch(true": 1,
                          "is already in the mempool": 1})
        self.assertEqual(mempool["scalaLedgerPhase2"], {"evaluatedSuccess": 4, "evaluatedFailure": 2, "scripts": 6})
        self.assertEqual(summary["admissionMs"]["scala"]["succeedingScriptFlagFalse"],
                         {"n": 2, "median": 6.25, "p95": 6.25, "max": 6.25, "mean": 5.25})
        self.assertEqual(summary["admissionMs"]["scala"]["failingScriptFlagTrue"]["max"], 7.25)
        self.assertEqual(summary["admissionMs"]["reference"]["failingScriptFlagTrue"]["max"], 2.5)

    def test_what_fails_the_run(self):
        # the Scala block s1 is not on the chain, and a Haskell pool's log named it as invalid
        chain = [("h1", 30, 1, [("a", True, True), ("b", False, True), ("c", True, True), ("d", False, True)])]
        judged = self.judgements()
        judged[1] = {**judged[1], "accepted": True, "scala": None}
        judged[2] = {**judged[2], "scala": [1, 0, 1], "attempts": 5}
        failures, _ = pool_plutus.check(self.runner(judged, errors=["boom"]),
                                        chain, self.forged(["s1"]), self.events() + [{"event": "blockNoVerdict"}], {"s1"})
        joined = "\n".join(failures)
        for expected in ("1 Plutus rounds failed: boom", "the Haskell pools refused 1 Scala-forged blocks",
                         "no Scala-forged block holds a transaction whose scripts succeed",
                         "no Scala-forged block holds a transaction with the validity flag false",
                         "the Scala node accepted 1 transactions the reference refused",
                         "does not show a refusal for the reference's ValidationTagMismatch", "1 blockNoVerdict events"):
            self.assertIn(expected, joined)

    def test_an_unread_refusal_on_both_nodes_is_not_a_refusal_for_the_same_rule(self):
        # the reference's refusal unread (None) and the Scala node's unread too, not accepted
        judged = self.judgements()
        judged[0] = {**judged[0], "reference": None, "scala": None, "accepted": False}
        failures, summary = pool_plutus.check(self.runner(judged), InclusionTest.CHAIN, self.forged(), self.events())
        self.assertEqual(summary["scalaLocalSubmission"]["refusedForTheSameRule"], 3)
        self.assertEqual(len(failures), 1)
        self.assertIn("the reference's refusal is not ValidationTagMismatch: None", failures[0])

    def test_both_nodes_refusing_for_another_rule_and_an_unread_scala_refusal_fail_the_run(self):
        judged = self.judgements()
        judged[0] = {**judged[0], "reference": [1, 0, 1], "scala": [1, 0, 1]}
        judged[3] = {**judged[3], "scala": None}
        failures, _ = pool_plutus.check(self.runner(judged), InclusionTest.CHAIN, self.forged(), self.events())
        self.assertEqual(len(failures), 1)
        self.assertIn("the reference's refusal is not ValidationTagMismatch: [1, 0, 1]", failures[0])
        self.assertIn("the Scala node's refusal could not be read", failures[0])

    def test_a_refused_case_without_its_own_judgement_fails_the_run(self):
        # three judgements and one more for the first case: the V2 failing script with the flag true has none
        judged = self.judgements()[:3] + [self.judgements()[0]]
        failures, summary = pool_plutus.check(self.runner(judged), InclusionTest.CHAIN, self.forged(), self.events())
        self.assertEqual(summary["scalaLocalSubmission"]["missing"],
                         [{"case": "reject-failure-valid", "language": 2, "tx": "d"}])
        self.assertEqual(failures, ["1 refused cases have no judgement by the Scala node: "
                                    "[{'case': 'reject-failure-valid', 'language': 2, 'tx': 'd'}]"])

    def test_no_judgement_by_the_scala_node_fails_the_run(self):
        failures, _ = pool_plutus.check(self.runner([]), InclusionTest.CHAIN, self.forged(), self.events())
        self.assertIn("4 refused cases have no judgement by the Scala node", failures[0])
        self.assertEqual(failures[1], "no refused Plutus transaction was submitted to the Scala node")


# 64-hex-character stand-ins for block hashes, one hex digit repeated per name
H = {name: digit * 64 for name, digit in zip("abcdvwxyz", "abcd01234")}
NAME = {h: name for name, h in H.items()}


class RunnerTest(unittest.TestCase):
    """`Runner.finish` takes the evidence once the thread has ended; a load that had to be stopped in the middle of a
    round, or that never stopped, fails the run."""

    @staticmethod
    def load():
        return types.SimpleNamespace(ready=threading.Event(), stop=threading.Event(), wallets=[types.SimpleNamespace(address=b"w")],
                                     act=lambda *a, **k: "f0", delegator=lambda n: object(), log=lambda m: None,
                                     out=io.StringIO(), plutus_evidence=[], scala_refusals=[], plutus_txids=set())

    def test_a_load_that_ends_after_its_round(self):
        load, first = self.load(), threading.Event()

        def round_(load_, era):
            load_.plutus_evidence.append(row("success", "a"))
            first.set()
            load_.stop.wait(0.001)
        runner = pool_plutus.Runner(load, 3, round_).start()
        first.wait(5)
        evidence = runner.finish(timeout=5)
        self.assertEqual((evidence.finished, evidence.stopped, evidence.ended), (True, True, None))
        self.assertGreaterEqual(evidence.passes, 1)
        failures, _ = pool_plutus.check(evidence, [], [], [])
        self.assertFalse([f for f in failures if "stop" in f], failures)

    def test_a_round_cut_short_by_the_stop_fails_the_run(self):
        load, rounds, second = self.load(), [], threading.Event()

        def round_(load_, era):
            rounds.append(era)
            if len(rounds) == 1:
                load_.plutus_evidence.append(row("success", "a"))
                return
            second.set()
            load_.stop.wait(5)  # the round under way ends only when the load is told to stop
            load_.plutus_evidence.append(row("collateral", "b", passed=False))
        runner = pool_plutus.Runner(load, 3, round_).start()
        second.wait(5)
        evidence = runner.finish(timeout=0.05, stop_timeout=5)
        self.assertEqual((evidence.finished, evidence.stopped), (False, True))
        self.assertEqual([r["case"] for r in evidence.rows], ["success", "collateral"])
        failures, _ = pool_plutus.check(evidence, [], [], [])
        self.assertIn("the Plutus load did not end its round in time and was stopped in the middle of one", failures)
        self.assertTrue(any(f.startswith("1 Plutus cases did not pass") for f in failures), failures)

    def test_a_thread_that_does_not_stop_fails_the_run_and_later_records_do_not_reach_the_evidence(self):
        load, release, inside = self.load(), threading.Event(), threading.Event()

        def round_(load_, era):
            load_.plutus_evidence.append(row("success", "a"))
            inside.set()
            release.wait(5)  # deaf to the stop
            load_.plutus_evidence.append(row("collateral", "b"))
        runner = pool_plutus.Runner(load, 3, round_).start()
        inside.wait(5)
        evidence = runner.finish(timeout=0.01, stop_timeout=0.01)
        release.set()
        runner.thread.join(5)
        self.assertEqual((evidence.finished, evidence.stopped), (False, False))
        self.assertEqual(len(evidence.rows), 1)
        self.assertEqual(len(load.plutus_evidence), 2)
        failures, _ = pool_plutus.check(evidence, [], [], [])
        self.assertIn("the Plutus load's thread did not stop when asked to; its evidence is what it had recorded",
                      failures)


class LostBlocksTest(unittest.TestCase):
    """Every Scala-forged block that is not on the observer's chain is classified by the Haskell pools' own traces;
    the three cases a timing rule cannot tell from a lost slot battle are refusals when a pool named the block."""
    CHAIN = [(H["a"], 5, 1, []), (H["b"], 12, 2, []), (H["c"], 40, 3, []), (H["d"], 44, 4, [])]

    @staticmethod
    def forged(name, slot, number, prev, txs=()):
        return {"event": "forged", "hash": H[name], "slot": slot, "blockNo": number, "prev": prev and H[prev],
                "txs": list(txs)}

    # a replacement within ten slots on the same parent, a block on a parent that left the chain too, and a block at a
    # height the observer had not reached when its chain was read
    BLIND_SPOTS = (("w", 35, 3, "b"), ("z", 43, 4, "y"), ("v", 50, 5, "d"))

    def test_a_block_a_haskell_pool_named_as_invalid_is_refused_wherever_the_chain_put_its_height(self):
        for spot in self.BLIND_SPOTS:
            with self.subTest(block=spot[0]):
                (lost,) = pool_plutus.lost_blocks([self.forged(*spot, ["t"])], self.CHAIN, {"t"}, {H[spot[0]]}, set())
                self.assertEqual((lost["class"], lost["plutusTxs"]), (pool_plutus.REFUSED, 1))

    def test_without_a_trace_a_lost_block_is_unconfirmed_and_with_a_tip_change_it_was_adopted(self):
        forged = [self.forged("a", 5, 1, None), self.forged("x", 12, 2, "a", ["t", "pay"])] + \
            [self.forged(*spot) for spot in self.BLIND_SPOTS]
        lost = pool_plutus.lost_blocks(forged, self.CHAIN, {"t"}, set(), {H["w"]})
        self.assertEqual([(NAME[b["hash"]], b["class"]) for b in lost],
                         [("x", pool_plutus.UNCONFIRMED), ("w", pool_plutus.ADOPTED), ("z", pool_plutus.UNCONFIRMED),
                          ("v", pool_plutus.UNCONFIRMED)])
        self.assertEqual([(NAME[b["hash"]], b["chainSlotAtHeight"], b["slotsLater"], b["parentOnChain"], b["afterChainRead"])
                          for b in lost],
                         [("x", 12, 0, True, False), ("w", 40, 5, True, False), ("z", 44, 1, False, False),
                          ("v", None, None, True, True)])

    def test_a_refused_block_fails_the_run_and_an_unconfirmed_one_is_reported(self):
        # the load's transactions "a" and "d" (ROWS)
        forged = [self.forged("a", 5, 1, None), self.forged("w", 35, 3, "b", ["a"]), self.forged("z", 43, 4, "y", ["d"])]
        runner = CheckTest().runner([])
        failures, summary = pool_plutus.check(runner, self.CHAIN, forged, [], {H["w"]}, set())
        self.assertTrue(any(f.startswith("the Haskell pools refused 1 Scala-forged blocks") for f in failures), failures)
        self.assertEqual(summary["lostScalaBlocksByClass"],
                         {pool_plutus.REFUSED: 1, pool_plutus.ADOPTED: 0, pool_plutus.UNCONFIRMED: 1})
        self.assertEqual(summary["unconfirmedWithPlutus"], 1)


class BlockKindsTest(unittest.TestCase):
    """Each Scala-forged block's timing class comes from its own contents, a lost one's too."""
    CHAIN = [(H["a"], 5, 1, [("pay", True, False)]), (H["b"], 12, 2, [("t", True, True)])]

    @staticmethod
    def evidence(stopped=True):
        return dataclasses.replace(CheckTest().runner([], stopped=stopped), plutus_txids=frozenset({"t", "u"}))

    def forged(self, name, txs):
        event = {"event": "forged", "hash": H[name], "slot": 1, "blockNo": 1, "prev": None}
        return event if txs is None else {**event, "txs": txs}

    def test_on_the_chain_by_the_bytes_and_lost_by_the_transactions_the_load_submitted(self):
        forged = [self.forged("a", ["pay"]), self.forged("b", ["t"]), self.forged("x", ["u", "pay"]),
                  self.forged("y", ["pay"]), self.forged("z", None)]
        self.assertEqual(pool_plutus.block_kinds(forged, self.CHAIN, self.evidence()),
                         {H["a"]: "withoutPlutus", H["b"]: "withPlutus", H["x"]: "withPlutus", H["y"]: "withoutPlutus",
                          H["z"]: "unknown"})

    def test_a_lost_block_is_unknown_when_the_load_may_not_have_recorded_every_submission(self):
        forged = [self.forged("x", ["u"]), self.forged("y", ["pay"])]
        self.assertEqual(pool_plutus.block_kinds(forged, None, self.evidence(stopped=False)),
                         {H["x"]: "withPlutus", H["y"]: "unknown"})

    def test_the_load_records_every_plutus_transaction_before_it_goes_out(self):
        plain = enc([{0: [], 1: [], 2: 1}, {0: []}, True, None])
        plutus = enc([{0: [], 1: [], 2: 1}, {0: [], 5: {(0, 0): [0, [1, 2]]}}, False, None])
        self.assertEqual((ntclocal.has_redeemers(plain), ntclocal.has_redeemers(plutus)), (False, True))
        with tempfile.TemporaryDirectory() as d, \
                patch.object(pool_plutus.eraload.EraLoad, "submit", return_value=False):
            load = pool_plutus.Load("n", pathlib.Path(d), "/sock", "/scala.sock")
            load.submit("conway", plain, "transfer")
            load.submit("conway", plutus, "legacy-plutus-conway-v1-collateral")
            load.out.close()
        self.assertEqual(load.plutus_txids, {ntclocal.raw_tx_id(plutus).hex()})


class ForgeTimingTest(unittest.TestCase):
    SHELLEY = {"systemStart": "2026-01-01T00:00:00Z", "slotLength": 0.1}

    @staticmethod
    def at(seconds):
        return f"2026-01-01T00:00:{seconds:09.6f}Z"

    def slot(self, slot, offsets, hash_, latency):
        start = slot * 0.1
        names = ("startLeadershipCheck", "nodeIsLeader", "forgedBlock", "forged", "adoptedBlock")
        events = [{"event": n, "at": self.at(start + o / 1000), "slot": slot, **({"hash": hash_} if n == "forged" else {})}
                  for n, o in zip(names, offsets)]
        return events + [{"event": "adopted", "at": self.at(start + offsets[-1] / 1000), "block": {"hash": hash_},
                          "latencyMs": latency}]

    def test_each_phase_of_a_forged_slot_and_the_split_by_plutus_blocks(self):
        events = self.slot(10, (2, 4, 20, 21, 50), "p", 50) + self.slot(30, (1, 2, 6, 7, 12), "q", 12)
        events.append({"event": "startLeadershipCheck", "at": self.at(3.1), "slot": 31})
        timing = pool.forge_timing(events, self.SHELLEY, {"p": pool_plutus.WITH_PLUTUS, "q": pool_plutus.WITHOUT_PLUTUS})
        self.assertEqual({k: v["max"] for k, v in timing["withPlutus"].items()},
                         {"toForged": 21, "checkToForgedBlock": 18, "leaderToForgedBlock": 16, "forgedToAdopted": 29,
                          "adoptedLatency": 50})
        self.assertEqual({k: v["max"] for k, v in timing["withoutPlutus"].items()},
                         {"toForged": 7, "checkToForgedBlock": 5, "leaderToForgedBlock": 4, "forgedToAdopted": 5,
                          "adoptedLatency": 12})
        self.assertEqual(timing["all"]["toForged"]["n"], 2)
        self.assertIsNone(timing["unknown"]["toForged"])

    def test_without_plutus_hashes_only_the_whole_and_missing_traces_are_left_out(self):
        events = [e for e in self.slot(10, (2, 4, 20, 21, 50), "p", 50) if e["event"] != "nodeIsLeader"]
        timing = pool.forge_timing(events, self.SHELLEY)
        self.assertEqual(set(timing), {"all"})
        self.assertIsNone(timing["all"]["leaderToForgedBlock"])
        self.assertEqual(timing["all"]["checkToForgedBlock"]["n"], 1)


class SpreadTest(unittest.TestCase):
    def test_order_statistics(self):
        self.assertEqual(util.spread(range(20, 0, -1)), {"n": 20, "median": 11, "p95": 20, "max": 20, "mean": 10})
        self.assertEqual(util.spread([0.1234, 2.5], 2), {"n": 2, "median": 2.5, "p95": 2.5, "max": 2.5, "mean": 1.31})
        self.assertIsNone(util.spread([]))


class ReasonKindTest(unittest.TestCase):
    def test_the_rule_without_the_transaction_or_the_script_details(self):
        self.assertEqual(pool_plutus.reason_kind("tx 0123abcd violates ValidationTagMismatch: Utxow(Utxo(Utxos("
                                                 "ValidationTagMismatch(false,None))))"),
                         "violates ValidationTagMismatch: Utxow(Utxo(Utxos(ValidationTagMismatch(false")
        self.assertEqual(pool_plutus.reason_kind("the mempool is full"), "the mempool is full")


class TraceOptionsTest(unittest.TestCase):
    """The Haskell pools' trace options: the defaults of cardano-node 11.1.3 and `InvalidBlock`."""

    def test_the_pool_options_are_the_defaults_and_one_entry(self):
        options = reference_tracing.POOL_TRACE_OPTIONS
        self.assertEqual({k: v for k, v in options.items() if k != reference_tracing.INVALID_BLOCK},
                         reference_tracing.DEFAULT_TRACE_OPTIONS)
        self.assertEqual(options[reference_tracing.INVALID_BLOCK], {"severity": "Info"})

    def test_every_trace_the_harness_reads_is_emitted_and_invalid_block_was_not_before(self):
        for namespace, severity in reference_tracing.READ.items():
            with self.subTest(namespace=namespace):
                self.assertTrue(reference_tracing.emitted(reference_tracing.POOL_TRACE_OPTIONS, namespace, severity))
        self.assertFalse(reference_tracing.emitted(reference_tracing.DEFAULT_TRACE_OPTIONS,
                                                   reference_tracing.INVALID_BLOCK, "Notice"))

    def test_every_other_namespace_keeps_its_default_options(self):
        # namespaces of the 11.1.3 documentation under each default entry, the root and the entry's own siblings
        namespaces = ["Forge.Loop.StartLeadershipCheck", "Forge.Loop.NodeNotLeader", "Mempool.AddedTx",
                      "Mempool.RejectedTx", "ChainDB.AddBlockEvent.AddedToCurrentChain",
                      "ChainDB.AddBlockEvent.AddedBlockToQueue", "ChainDB.AddBlockEvent.AddBlockValidation.ValidCandidate",
                      "ChainDB.AddBlockEvent.AddBlockValidation.CandidateContainsFutureBlocks",
                      "ChainDB.AddBlockEvent.AddBlockValidation.UpdateLedgerDb", "BlockFetch.Decision.Decline",
                      "Net.PeerSelection.Selection", "Net.Mux.Local.CleanExit", "Startup.Info", "Resources"]
        for namespace in namespaces:
            for kind in ("severity", "detail", "backends", "maxFrequency"):
                with self.subTest(namespace=namespace, kind=kind):
                    self.assertEqual(reference_tracing.effective(reference_tracing.POOL_TRACE_OPTIONS, namespace, kind),
                                     reference_tracing.effective(reference_tracing.DEFAULT_TRACE_OPTIONS, namespace, kind))
        self.assertEqual(reference_tracing.effective(reference_tracing.POOL_TRACE_OPTIONS,
                                                     "ChainDB.AddBlockEvent.AddBlockValidation.ValidCandidate", "severity"),
                         "Silence")
        self.assertEqual(reference_tracing.effective(reference_tracing.POOL_TRACE_OPTIONS,
                                                     reference_tracing.INVALID_BLOCK, "backends"),
                         ["Stdout MachineFormat", "EKGBackend"])

    def test_a_configuration_that_sets_trace_options_is_refused(self):
        self.assertEqual(reference_tracing.pool_configuration({"Protocol": "Cardano", "TraceOptions": {}})["TraceOptions"],
                         reference_tracing.POOL_TRACE_OPTIONS)
        with self.assertRaises(ValueError):
            reference_tracing.pool_configuration({"TraceOptions": {"Mempool": {"severity": "Debug"}}})

    def test_the_pools_get_the_options_before_they_start_from_a_reference_of_the_pinned_version(self):
        written = {}
        version = types.SimpleNamespace(stdout="cardano-node 11.1.3 - linux-x86_64 - ghc-9.6\ngit rev 938cba99\n")
        with patch.object(pool, "in_ref", return_value=version), \
                patch.object(pool, "read_json", return_value={"Protocol": "Cardano", "TraceOptions": {}}), \
                patch.object(pool, "put_file", side_effect=lambda name, path, text: written.update({path: text})):
            pool.pool_trace_options("n")
        self.assertEqual(json.loads(written[f"{pool.ENV}/configuration.yaml"])["TraceOptions"],
                         reference_tracing.POOL_TRACE_OPTIONS)
        with patch.object(pool, "in_ref", return_value=types.SimpleNamespace(stdout="cardano-node 11.2.0 - linux\n")), \
                self.assertRaises(SystemExit):
            pool.pool_trace_options("n")

    def test_the_pool_scenario_sets_the_options_right_after_the_environment(self):
        calls = []
        args = types.SimpleNamespace(name="n", pool=1, keep=False)
        with tempfile.TemporaryDirectory() as d, \
                patch.object(pool, "data_root", return_value=pathlib.Path(d) / "run"), \
                patch.object(pool, "start_reference"), patch.object(pool, "host_load", return_value={}), \
                patch.object(pool, "pool_environment", side_effect=lambda a: calls.append("environment") or ({}, {})), \
                patch.object(pool, "pool_trace_options",
                             side_effect=lambda name: calls.append("traceOptions") or (_ for _ in ()).throw(SystemExit("stop"))), \
                patch.object(pool, "docker", return_value=types.SimpleNamespace(returncode=0, stdout="", stderr="")), \
                patch.object(pool, "keep_reference_logs"), patch.object(util, "SOCKET", util.SOCKET), \
                contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
            pool.pool(args)
        self.assertEqual(calls, ["environment", "traceOptions"])


class LogViewTest(unittest.TestCase):
    INVALID = json.dumps({"at": "2026-11-13T22:00:00Z", "ns": reference_tracing.INVALID_BLOCK,
                          "data": {"kind": "InvalidBlock", "block": {"hash": H["w"], "slot": 35},
                                   "error": f"ValidationTagMismatch in a block whose parent is {H['b']}"}})
    TIP = json.dumps({"ns": "ChainDB.AddBlockEvent.AddedToCurrentChain",
                      "data": {"kind": "AddedToCurrentChain", "newtip": f"{H['x']}@12", "headers": []}})
    FORK = json.dumps({"ns": "ChainDB.AddBlockEvent.SwitchedToAFork",
                       "data": {"kind": "TraceAddBlockEvent.SwitchedToAFork", "newtip": f"{H['y']}@41"}})
    EXCEPTION = json.dumps({"ns": "ChainSync.Client.Exception",
                            "data": {"kind": "Exception", "exception": f"InvalidBlock (At (Block 43 {H['z']}))"}})

    def test_refused_blocks_tips_and_counts(self):
        view = reference_tracing.log_view(["Node configuration: NodeConfiguration {InvalidBlock-free text}",
                                           self.INVALID + "\n", self.TIP, self.FORK, self.EXCEPTION,
                                           json.dumps({"ns": "Forge.Loop.ForgedBlock", "data": {"block": H["v"]}})])
        # the invalid block's own field names it; its error text's other hash is not a refusal of that block
        self.assertEqual(view["refused"], {H["w"], H["z"]})
        self.assertEqual(view["tips"], {H["x"], H["y"]})
        self.assertEqual(len(view["invalid"]), 3)
        self.assertEqual(view["counts"], {reference_tracing.INVALID_BLOCK: 1, "ChainDB.AddBlockEvent.AddedToCurrentChain": 1,
                                          "ChainDB.AddBlockEvent.SwitchedToAFork": 1, "ChainSync.Client.Exception": 1})

    def test_the_logs_of_each_pool_and_one_that_cannot_be_read(self):
        logs = {"/work/node2.log": [self.TIP + "\n"], "/work/node3.log": ["x\n", self.INVALID + "\n"]}

        class Read:
            def __init__(self, command, **kwargs):
                self.stdout = iter(logs.get(command[-1], []))
                self.returncode = 0 if command[-1] in logs else 1

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False
        with patch.object(pool.subprocess, "Popen", Read):
            views = pool.reference_logs("n", [1, 2, 3])
        self.assertEqual(views["pool1"], {"unreadable": 1})
        self.assertEqual(views["pool2"]["tips"], {H["x"]})
        self.assertEqual(pool.reference_invalid_blocks(views),
                         {"pool1": ["the log could not be read (exit status 1)"], "pool3": [self.INVALID]})


if __name__ == "__main__":
    unittest.main()
