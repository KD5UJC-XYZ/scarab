# SPDX-License-Identifier: Apache-2.0
"""The pure parts of `pool --plutus-load` and of the pool's forge timing: blocks read for their transactions'
validity flags, where the load's transactions are on the chain, the refusal rule, and the timing summaries."""
import hashlib
import pathlib
import sys
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import ntclocal, ntcquery, util  # noqa: E402
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
    CHAIN = [("s1", [("a", True, True), ("pay", True, False)]), ("h1", [("b", False, True)]),
             ("s2", [("d", False, True)]), ("h2", [("c", True, True)])]

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
        chain = [("s1", [("a", True, True)]), ("h1", [("b", True, True)]), ("s2", [("c", False, True)]),
                 ("h2", [("d", False, True)])]
        placed = pool_plutus.inclusion(ROWS, chain, {"s1", "s2"})
        self.assertEqual(placed["wrongFlag"], [{"tx": "b", "block": "h1", "valid": True, "scalaForged": False},
                                               {"tx": "c", "block": "s2", "valid": False, "scalaForged": True}])
        # neither counts as evidence of a flag carried right
        self.assertEqual((placed["inScalaForgedBlocks"]["succeeding"], placed["inScalaForgedBlocks"]["flagFalse"]), (1, 0))
        self.assertEqual((placed["inOtherBlocks"]["succeeding"], placed["inOtherBlocks"]["flagFalse"]), (0, 1))

    def test_accepted_transactions_missing_from_the_chain_and_unknown_plutus_ones(self):
        chain = [("s1", [("a", True, True), ("x", True, True)])]
        placed = pool_plutus.inclusion(ROWS + [row("success", "e", passed=False)], chain, {"s1"})
        self.assertEqual(placed["acceptedNotOnChain"], ["b", "c", "d"])
        self.assertEqual(placed["otherPlutusInScalaForgedBlocks"], 1)


class CheckTest(unittest.TestCase):
    SHELLEY_AT = "2026-01-01T00:00:{:06.3f}Z"

    def runner(self, refusals, rows=ROWS, errors=()):
        load = types.SimpleNamespace(plutus_evidence=list(rows), scala_refusals=refusals)
        return types.SimpleNamespace(load=load, passes=1, errors=list(errors), ended=None)

    def refusal(self, **kw):
        return {"label": "legacy-plutus-conway-v1-reject-failure-valid", "tx": "b", "reference": [1, 0, 0, 0],
                "scala": [1, 0, 0, 0], "accepted": False, "attempts": 1, "sameBytes": False, "referenceMs": 2.5,
                "scalaMs": 4.25, **kw}

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
        failures, summary = pool_plutus.check(self.runner([self.refusal()]), InclusionTest.CHAIN, {"s1", "s2"},
                                              self.events())
        self.assertEqual(failures, [])
        self.assertEqual(summary["protocolVersions"], ["10.0"])
        self.assertEqual(summary["scalaLocalSubmission"]["refusedForTheSameRule"], 1)
        mempool = summary["mempool"]
        self.assertEqual((mempool["accepted"], mempool["seenInScalaMempool"], mempool["localSubmissions"]), (4, 2, 1))
        self.assertEqual(mempool["rejectedByReason"],
                         {"violates ValidationTagMismatch: Utxow(Utxo(Utxos(ValidationTagMismatch(true": 1,
                          "is already in the mempool": 1})
        self.assertEqual(mempool["scalaLedgerPhase2"], {"evaluatedSuccess": 4, "evaluatedFailure": 2, "scripts": 6})
        self.assertEqual(summary["admissionMs"]["scala"]["failingScriptFlagTrue"]["median"], 4.25)
        self.assertEqual(summary["admissionMs"]["reference"]["failingScriptFlagTrue"]["max"], 2.5)
        self.assertIsNone(summary["admissionMs"]["scala"]["succeedingScriptFlagFalse"])

    def test_what_fails_the_run(self):
        chain = [("h1", [("a", True, True), ("b", False, True), ("c", True, True), ("d", False, True)])]
        failures, _ = pool_plutus.check(self.runner([self.refusal(accepted=True, scala=None),
                                                     self.refusal(scala=[1, 0, 1], attempts=5)], errors=["boom"]),
                                        chain, {"s1"}, self.events() + [{"event": "blockNoVerdict"}])
        joined = "\n".join(failures)
        for expected in ("1 Plutus rounds failed: boom", "no Scala-forged block holds a transaction whose scripts succeed",
                         "no Scala-forged block holds a transaction with the validity flag false",
                         "the Scala node accepted 1 transactions the reference refused",
                         "did not refuse a transaction for the reference's rule", "1 blockNoVerdict events"):
            self.assertIn(expected, joined)

    def test_no_judgement_by_the_scala_node_fails_the_run(self):
        failures, _ = pool_plutus.check(self.runner([]), InclusionTest.CHAIN, {"s1", "s2"}, self.events())
        self.assertEqual(failures, ["no refused Plutus transaction was submitted to the Scala node"])


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
        timing = pool.forge_timing(events, self.SHELLEY, {"p"})
        self.assertEqual({k: v["max"] for k, v in timing["withPlutus"].items()},
                         {"toForged": 21, "checkToForgedBlock": 18, "leaderToForgedBlock": 16, "forgedToAdopted": 29,
                          "adoptedLatency": 50})
        self.assertEqual({k: v["max"] for k, v in timing["withoutPlutus"].items()},
                         {"toForged": 7, "checkToForgedBlock": 5, "leaderToForgedBlock": 4, "forgedToAdopted": 5,
                          "adoptedLatency": 12})
        self.assertEqual(timing["all"]["toForged"]["n"], 2)

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


class ReferenceInvalidBlocksTest(unittest.TestCase):
    INVALID = '{"ns":"ChainDB.AddBlockEvent.AddBlockValidation.InvalidBlock","data":{"kind":"InvalidBlock"}}'
    LOGS = {"/work/node2.log": ['{"ns":"Forge.Loop.ForgedBlock"}\n'], "/work/node3.log": ["x\n", INVALID + "\n"]}

    def test_pools_whose_log_traces_an_invalid_block_or_cannot_be_read(self):
        logs = self.LOGS

        class Read:
            def __init__(self, command, **kwargs):
                self.stdout = iter(logs.get(command[-1], []))
                self.returncode = 0 if command[-1] in logs else 1

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False
        with patch.object(pool.subprocess, "Popen", Read):
            self.assertEqual(pool.reference_invalid_blocks("n", [2, 3]), {"pool3": [self.INVALID]})
            self.assertEqual(pool.reference_invalid_blocks("n", [1]),
                             {"pool1": ["the log could not be read (exit status 1)"]})


if __name__ == "__main__":
    unittest.main()
