# SPDX-License-Identifier: Apache-2.0
"""Canonical backlog candidates and the narrowly bounded stale-intersection retry."""
import copy
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import cbor, ntclocal  # noqa: E402


class Session:
    def __init__(self, instructions, intersect=5, error=None):
        self.instructions = iter(instructions)
        self.intersect = intersect
        self.error = error

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def find_intersect(self, points):
        if self.error:
            raise self.error
        return [self.intersect]

    def request_next(self):
        return next(self.instructions)


class CanonicalPointsTest(unittest.TestCase):
    @staticmethod
    def forward(slot, body=0):
        header = cbor.enc([[slot, slot, bytes(32), bytes(32)], b"signature"])
        block = bytes([0x82, 0x06, 0x85]) + header + cbor.enc([body]) + cbor.enc([]) * 3
        item = cbor.enc(cbor.Tag(24, block))
        reply = [2, cbor.Tag(24, block), [[], 0]]
        return (reply, cbor.enc(reply)), (slot, ntclocal.blake(header).hex())

    @staticmethod
    def rollback(point):
        wire = [] if point is None else [point[0], bytes.fromhex(point[1])]
        reply = [3, wire, [[], 0]]
        return reply, cbor.enc(reply)

    def test_rollback_prunes_candidates_but_retains_orphan_byte_comparisons(self):
        a, pa = self.forward(1)
        b, pb = self.forward(2)
        c, pc = self.forward(3)
        different_b, _ = self.forward(2, body=1)
        h = [self.rollback(None), a, b, self.rollback(pa), c]
        s = [self.rollback(None), a, different_b, self.rollback(pa), c]
        result = ntclocal.compare_follow(lambda: Session(h), lambda: Session(s), None, 3, 1)
        self.assertEqual(result["points"], [pa, pc])
        self.assertNotIn(pb, result["points"])
        self.assertEqual(result["compared"], 3)
        self.assertEqual(result["differing"], [pb[1]])
        self.assertEqual(result["reference"]["rollbacks"], 2)
        self.assertIsNone(result["reference"]["error"])

    def test_candidate_must_remain_canonical_on_each_node(self):
        a, pa = self.forward(1)
        b, _ = self.forward(2)
        c, pc = self.forward(3)
        for h, s in (([a, b, self.rollback(pa), c], [a, b, c]),
                     ([a, b, c], [a, b, self.rollback(pa), c])):
            with self.subTest(h=h):
                result = ntclocal.compare_follow(lambda: Session(h), lambda: Session(s), None, 3, 1)
                self.assertEqual(result["points"], [pa, pc])
                self.assertEqual(result["compared"], 3)

    def test_origin_or_unobserved_rollback_discards_all_observed_candidates(self):
        a, _ = self.forward(2)
        b, _ = self.forward(3)
        c, pc = self.forward(4)
        for point in (None, (1, "00" * 32)):
            instructions = [a, b, self.rollback(point), c]
            result = ntclocal.compare_follow(lambda: Session(instructions), lambda: Session(instructions),
                                            None, 3, 1)
            self.assertEqual(result["points"], [pc])
            self.assertEqual(result["compared"], 3)

    def test_transport_errors_and_asymmetric_intersections_are_reported(self):
        result = ntclocal.compare_follow(lambda: Session([], intersect=6),
                                        lambda: Session([], error=OSError("closed")), None, 3, 1)
        self.assertEqual(result["reference"]["intersect"], 6)
        self.assertIn("closed", result["scala"]["error"])
        self.assertEqual(result["compared"], 0)


class BacklogRetryTest(unittest.TestCase):
    POINTS = [(slot, f"{slot:064x}") for slot in range(1, 13)]

    @staticmethod
    def stale():
        return {"reference": {"intersect": 6, "error": None, "first": None},
                "scala": {"intersect": 6, "error": None, "first": None},
                "compared": 0, "differing": [], "sameFirstInstruction": True,
                "referenceBlocks": 0, "scalaBlocks": 0}

    @staticmethod
    def success():
        return {"reference": {"intersect": 5, "error": None, "first": [3, "[]"]},
                "scala": {"intersect": 5, "error": None, "first": [3, "[]"]},
                "compared": 4, "differing": [], "sameFirstInstruction": True,
                "referenceBlocks": 4, "scalaBlocks": 4}

    def run_follow(self, first, second=None, points=None):
        answers = iter([first, second or self.success()])
        seen = []
        result = ntclocal.backlog_follow(self.POINTS if points is None else points,
                                        lambda point: seen.append(point) or copy.deepcopy(next(answers)))
        return result, seen

    def test_only_symmetric_stale_point_retries_from_an_older_candidate(self):
        result, seen = self.run_follow(self.stale())
        self.assertEqual(seen, [(8, bytes.fromhex(self.POINTS[7][1])),
                                (4, bytes.fromhex(self.POINTS[3][1]))])
        self.assertEqual(result["attempts"], 2)
        self.assertEqual(result["compared"], 4)
        self.assertEqual([a["point"] for a in result["attemptResults"]], [self.POINTS[7], self.POINTS[3]])
        self.assertEqual([a["comparison"]["compared"] for a in result["attemptResults"]], [0, 4])
        self.assertEqual(result["attemptResults"][0]["comparison"], self.stale())

    def test_retry_is_bounded_and_does_not_make_an_empty_backlog_pass(self):
        result, seen = self.run_follow(self.stale(), self.stale())
        self.assertEqual(len(seen), 2)
        self.assertEqual(result["compared"], 0)
        result, seen = self.run_follow(self.stale(), points=self.POINTS[:5])
        self.assertEqual(len(seen), 1)
        self.assertEqual(result["compared"], 0)
        self.assertEqual(self.run_follow(self.stale(), points=self.POINTS[:4]), (None, []))

    def test_short_candidate_history_uses_the_oldest_point_only_once(self):
        result, seen = self.run_follow(self.stale(), points=self.POINTS[:6])
        self.assertEqual([point[0] for point in seen], [2, 1])
        self.assertEqual(result["compared"], 4)

    def test_asymmetry_differences_errors_or_instructions_never_retry(self):
        for change in (lambda r: r["reference"].update(intersect=5),
                       lambda r: r["scala"].update(intersect=5),
                       lambda r: r["reference"].update(error="transport"),
                       lambda r: r["scala"].update(error="protocol"),
                       lambda r: r.update(differing=["hash"]),
                       lambda r: r.update(compared=1),
                       lambda r: r.update(referenceBlocks=1),
                       lambda r: r.update(scalaBlocks=1),
                       lambda r: r.update(sameFirstInstruction=False),
                       lambda r: r["reference"].update(first=[3, "[]"]),
                       lambda r: r["scala"].update(first=[3, "[]"])):
            first = self.stale()
            change(first)
            with self.subTest(first=first):
                result, seen = self.run_follow(first)
                self.assertEqual(len(seen), 1)
                self.assertEqual({k: v for k, v in result.items() if k not in ("attempts", "attemptResults")}, first)

    def test_a_failed_retry_remains_the_final_failure(self):
        failure = self.success()
        failure["differing"] = ["bad block"]
        result, seen = self.run_follow(self.stale(), failure)
        self.assertEqual(len(seen), 2)
        self.assertEqual(result["differing"], ["bad block"])

    def test_a_success_never_retries(self):
        result, seen = self.run_follow(self.success())
        self.assertEqual(len(seen), 1)
        self.assertEqual(result["compared"], 4)


if __name__ == "__main__":
    unittest.main()
