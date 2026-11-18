# SPDX-License-Identifier: Apache-2.0
"""Canonical backlog candidates and the narrowly bounded stale-intersection retry."""
import copy
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import cbor, cborspan, ntclocal, ntcquery  # noqa: E402


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



class MonitorSession:
    """A LocalTxMonitor session that answers `HasTx` false, or ends the connection on the ids
    `closes` names once it has read them. `acquires=False` ends it at the acquire, and `writes`
    is the error, if any, that writing a `HasTx` request raises. `sent` counts the requests written."""

    def __init__(self, closes, answer=b"\x82\x08\xf4", acquires=True, writes=None):
        self.closes, self.answer, self.acquires, self.writes = closes, answer, acquires, writes
        self.sent, self.pending = 0, None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def acquire_snapshot(self):
        if not self.acquires:
            raise EOFError("node closed the connection")
        return 1

    def release_snapshot(self):
        pass

    def send_has_item(self, item):
        if self.writes is not None:
            raise self.writes
        self.sent += 1
        self.pending = item

    def has_reply(self):
        if self.pending in self.closes:
            raise EOFError("node closed the connection")
        return self.answer

    def has_item(self, item):
        self.send_has_item(item)
        return self.has_reply()

    def has_tx(self, txid, era=6):
        return self.has_item(b"\x82" + bytes([era]) + cbor.enc(txid))


class HasTxIdsTest(unittest.TestCase):
    refused = set(ntclocal.REFUSED_IDS.values())

    def test_chunked_is_two_definite_chunks_in_an_indefinite_string(self):
        self.assertEqual(ntclocal.chunked(b"\x01\x02\x03\x04"), bytes.fromhex("5f420102420304ff"))

    def test_every_refused_id_closes_on_both_and_the_accepted_ones_agree(self):
        report, failures = ntclocal.has_tx_ids(lambda: MonitorSession(self.refused),
                                               lambda: MonitorSession(self.refused))
        self.assertEqual(failures, [])
        self.assertEqual(set(report["refused"]), set(ntclocal.REFUSED_IDS))
        self.assertTrue(report["healthyAfterRefusals"])

    def test_an_answered_refused_id_fails(self):
        answered = set(self.refused) - {ntclocal.REFUSED_IDS["era-8"]}
        _, failures = ntclocal.has_tx_ids(lambda: MonitorSession(self.refused), lambda: MonitorSession(answered))
        self.assertEqual(len(failures), 1)
        self.assertIn("era-8", failures[0])

    def test_a_connection_that_ends_before_the_id_is_sent_is_not_a_refusal(self):
        closing = [MonitorSession(self.refused, acquires=False)] * len(ntclocal.REFUSED_IDS)
        refusing = iter([MonitorSession(self.refused)] + closing + [MonitorSession(self.refused)])
        report, failures = ntclocal.has_tx_ids(lambda: MonitorSession(self.refused), lambda: next(refusing))
        self.assertEqual(len(failures), len(ntclocal.REFUSED_IDS))
        self.assertTrue(all(row["scala"]["stage"] == "acquire" and not row["scala"]["closed"]
                            for row in report["refused"].values()))

    def test_a_request_that_cannot_be_written_is_not_a_refusal(self):
        for error in (BrokenPipeError("pipe"), ConnectionResetError("reset")):
            with self.subTest(error=type(error).__name__):
                sessions = [MonitorSession(self.refused, writes=error) for _ in ntclocal.REFUSED_IDS]
                refusing = iter([MonitorSession(self.refused)] + sessions + [MonitorSession(self.refused)])
                report, failures = ntclocal.has_tx_ids(lambda: MonitorSession(self.refused),
                                                       lambda: next(refusing))
                self.assertEqual(sum(session.sent for session in sessions), 0)
                self.assertEqual(len(failures), len(ntclocal.REFUSED_IDS))
                self.assertTrue(all(row["scala"]["stage"] == "send" and not row["scala"]["closed"]
                                    for row in report["refused"].values()))

    def test_an_end_while_reading_the_reply_is_a_refusal(self):
        sessions = [MonitorSession(self.refused) for _ in ntclocal.REFUSED_IDS]
        refusing = iter([MonitorSession(self.refused)] + sessions + [MonitorSession(self.refused)])
        report, failures = ntclocal.has_tx_ids(lambda: MonitorSession(self.refused), lambda: next(refusing))
        self.assertEqual(failures, [])
        self.assertEqual([session.sent for session in sessions], [1] * len(ntclocal.REFUSED_IDS))
        self.assertTrue(all(row["scala"] == {"closed": True} for row in report["refused"].values()))

    def test_a_differing_answer_to_an_accepted_id_fails(self):
        _, failures = ntclocal.has_tx_ids(lambda: MonitorSession(self.refused),
                                          lambda: MonitorSession(self.refused, answer=b"\x82\x08\xf5"))
        self.assertEqual(len(failures), len(ntclocal.ACCEPTED_IDS) + 1)


class SubmissionSession:
    """A LocalTxSubmission session that ends the connection on the items `closes` names once it has read them, and
    otherwise answers `reply` (`MsgAcceptTx` by default). `opens=False` ends it before anything is sent."""

    def __init__(self, closes, opens=True, reply=b"\x81\x01"):
        self.closes, self.opens, self.sent, self.last_raw, self.reply = closes, opens, [], b"", reply

    def __enter__(self):
        if not self.opens:
            raise EOFError("node closed the connection")
        return self

    def __exit__(self, *_):
        return False

    def _send(self, protocol, payload):
        self.sent.append((protocol, payload))

    def _recv(self, protocol):
        if self.sent[-1][1][2:] in self.closes:
            raise EOFError("node closed the connection")
        self.last_raw = self.reply
        return [1]


class SubmissionPrefixesTest(unittest.TestCase):
    sent = {**ntclocal.REFUSED_SUBMISSIONS, **ntclocal.REFUSED_WHOLE_SUBMISSIONS}
    refused = set(sent.values())

    def test_each_prefix_is_sent_as_a_submit_tx_on_a_connection_of_its_own(self):
        sessions = [SubmissionSession(self.refused) for _ in self.sent]
        opened = iter(sessions)
        report, failures = ntclocal.submission_prefixes(lambda: SubmissionSession(self.refused), lambda: next(opened))
        self.assertEqual(failures, [])
        self.assertEqual(set(report), set(self.sent))
        self.assertEqual([s.sent for s in sessions], [[(6, b"\x82\x00" + item)] for item in self.sent.values()])

    def test_an_answered_prefix_fails(self):
        answered = self.refused - {ntclocal.REFUSED_SUBMISSIONS["era-8-prefix"]}
        _, failures = ntclocal.submission_prefixes(lambda: SubmissionSession(self.refused),
                                                   lambda: SubmissionSession(answered))
        self.assertEqual(len(failures), 1)
        self.assertIn("era-8-prefix", failures[0])

    def test_a_connection_that_ends_before_the_prefix_is_sent_is_not_a_refusal(self):
        report, failures = ntclocal.submission_prefixes(lambda: SubmissionSession(self.refused),
                                                        lambda: SubmissionSession(self.refused, opens=False))
        self.assertEqual(len(failures), len(self.sent))
        self.assertTrue(all(row["scala"]["stage"] == "open" and not row["scala"]["closed"]
                            for row in report.values()))

    def test_each_prefix_is_not_a_whole_item(self):
        for label, item in ntclocal.REFUSED_SUBMISSIONS.items():
            with self.subTest(label=label), self.assertRaises(ntcquery.Incomplete):
                ntcquery.decode(item)

    def test_each_whole_transaction_is_one_whole_item(self):
        for label, item in ntclocal.REFUSED_WHOLE_SUBMISSIONS.items():
            with self.subTest(label=label):
                self.assertEqual(ntcquery.decode(item)[1], len(item))


class ByronSubmissionsTest(unittest.TestCase):
    refused, answered = ntclocal.byron_submissions()
    recorded = ntclocal.byron_recorded()

    def test_each_is_one_whole_byron_gentx_of_its_kind(self):
        for label, item in {**self.refused, **self.answered}.items():
            kind = next(k for k in ntclocal.BYRON_KINDS if label.startswith(f"byron-{k}"))
            with self.subTest(label=label):
                self.assertEqual(ntcquery.decode(item)[1], len(item))
                self.assertEqual(item[:4], b"\x82\x00\x82" + bytes([ntclocal.BYRON_KINDS[kind]]))

    def test_the_recorded_payloads_have_the_items_of_their_kind(self):
        # TxAux [tx, witnesses], Certificate [epoch, issuer, delegate, signature], Proposal of seven items and
        # Vote [voter, proposal id, decision, signature]; each key and signature 64 bytes, the proposal id 32
        sizes = {"transaction": 2, "certificate": 4, "proposal": 7, "vote": 4}
        for kind, payload in self.recorded.items():
            with self.subTest(kind=kind):
                self.assertEqual(len(cborspan.children(payload, 0)), sizes[kind])
                self.assertEqual(self.answered[f"byron-{kind}"], b"\x82\x00\x82" + bytes([ntclocal.BYRON_KINDS[kind]])
                                 + payload)
        vote = [self.recorded["vote"][s:e] for s, e in cborspan.children(self.recorded["vote"], 0)]
        self.assertEqual([v[:2] for v in vote], [b"\x58\x40", b"\x58\x20", b"\xf5", b"\x58\x40"])

    def test_each_refused_form_differs_from_the_recorded_one_as_its_label_says(self):
        vote, cert = self.recorded["vote"], self.recorded["certificate"]
        voter = vote[1:67]
        self.assertEqual(voter[:2], b"\x58\x40")
        self.assertEqual(self.refused["byron-vote-wide-voter-key-whole"],
                         b"\x82\x00\x82\x03\x84\x59\x00\x40" + voter[2:] + vote[67:])
        # the decision follows the list's head, the voter's key (2 + 64 bytes) and the proposal id (2 + 32)
        self.assertEqual(vote[101:102], b"\xf5")
        self.assertEqual(self.refused["byron-vote-not-bool-whole"],
                         b"\x82\x00\x82\x03" + vote[:101] + b"\x01" + vote[102:])
        self.assertEqual(self.refused["byron-certificate-negative-epoch-whole"],
                         b"\x82\x00\x82\x01\x84\x20" + cert[2:])
        self.assertEqual(cert[:2], b"\x84\x00", "the recorded certificate is of epoch 0")
        self.assertEqual(self.refused["byron-proposal-alt-256-whole"],
                         b"\x82\x00\x82\x02\x87\x83\x01\x00\x19\x01\x00" + self.recorded["proposal"][5:])
        self.assertEqual(self.recorded["proposal"][:5], b"\x87\x83\x01\x00\x00", "protocol version 1.0.0")
        self.assertEqual(self.refused["byron-transaction-of-3-whole"],
                         b"\x82\x00\x82\x00\x83" + self.recorded["transaction"][1:] + b"\x00")
        self.assertEqual(self.answered["byron-vote-wide-signature"],
                         b"\x82\x00\x82\x03" + vote[:-66] + b"\x59\x00\x40" + vote[-64:])

    def test_the_refused_forms_are_sent_whole_and_must_end_the_connection_on_both_nodes(self):
        closes = set(self.refused.values())
        sessions = []

        def scala():
            sessions.append(SubmissionSession(closes | set(ntclocal.REFUSED_SUBMISSIONS.values())
                                              | set(ntclocal.REFUSED_WHOLE_SUBMISSIONS.values())))
            return sessions[-1]

        report, failures = ntclocal.submission_prefixes(scala, scala, self.refused)
        self.assertEqual(failures, [])
        self.assertTrue(set(self.refused) <= set(report))
        self.assertTrue(all((6, b"\x82\x00" + item) in [m for s in sessions for m in s.sent]
                            for item in self.refused.values()))
        answered = closes - {self.refused["byron-vote-wide-voter-key-whole"]}
        _, failures = ntclocal.submission_prefixes(scala, lambda: SubmissionSession(
            answered | set(ntclocal.REFUSED_SUBMISSIONS.values()) | set(ntclocal.REFUSED_WHOLE_SUBMISSIONS.values())),
            self.refused)
        self.assertEqual(len(failures), 1)
        self.assertIn("byron-vote-wide-voter-key-whole", failures[0])


class SubmissionAnswersTest(unittest.TestCase):
    items = {"one": b"\x82\x00\x82\x00\x00", "two": b"\x82\x00\x82\x03\x00"}
    mismatch = b"\x82\x02\x81\x00"

    def test_each_item_answered_alike_with_msg_reject_tx_passes(self):
        sessions = []

        def opener():
            sessions.append(SubmissionSession(set(), reply=self.mismatch))
            return sessions[-1]

        report, failures = ntclocal.submission_answers(opener, opener, self.items)
        self.assertEqual(failures, [])
        self.assertEqual(set(report), set(self.items))
        self.assertTrue(all(row["equal"] for row in report.values()))
        # one connection per item and node
        self.assertEqual(sorted(s.sent for s in sessions),
                         sorted([[(6, b"\x82\x00" + i)] for i in self.items.values()] * 2))

    def test_a_close_fails(self):
        closes = {self.items["two"]}
        _, failures = ntclocal.submission_answers(lambda: SubmissionSession(set(), reply=self.mismatch),
                                                  lambda: SubmissionSession(closes, reply=self.mismatch), self.items)
        self.assertEqual(len(failures), 1)
        self.assertIn("two", failures[0])

    def test_different_replies_fail(self):
        _, failures = ntclocal.submission_answers(lambda: SubmissionSession(set(), reply=self.mismatch),
                                                  lambda: SubmissionSession(set(), reply=b"\x82\x02\x81\x01"),
                                                  self.items)
        self.assertEqual(len(failures), 2)

    def test_an_answer_that_is_not_msg_reject_tx_fails(self):
        _, failures = ntclocal.submission_answers(lambda: SubmissionSession(set()), lambda: SubmissionSession(set()),
                                                  self.items)
        self.assertEqual(len(failures), 2)


class HeldFoundTest(unittest.TestCase):
    def test_each_way_of_asking_is_counted_and_true_only_when_both_say_so(self):
        yes, no = b"\x82\x08\xf5", b"\x82\x08\xf4"
        h = {"steps": [("sizes", b"x"), ("has-absent", no), ("has 0", yes), ("has 0 in Babbage", yes),
                       ("has 1", yes), ("has 1 in Babbage", yes), ("has 0 as a Byron transaction id", yes)]}
        s = {"steps": [("sizes", b"x"), ("has-absent", no), ("has 0", yes), ("has 0 in Babbage", yes),
                       ("has 1", yes), ("has 1 in Babbage", no), ("has 0 as a Byron transaction id", yes)]}
        stats = {}
        ntclocal.count_held_found(stats, h, s)
        self.assertEqual(stats["heldFound"], {"in its own era": {"asked": 2, "bothTrue": 2},
                                              "in Babbage": {"asked": 2, "bothTrue": 1},
                                              "as a Byron transaction id": {"asked": 1, "bothTrue": 1}})


if __name__ == "__main__":
    unittest.main()
