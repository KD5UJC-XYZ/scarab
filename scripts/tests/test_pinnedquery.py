# SPDX-License-Identifier: Apache-2.0
"""The acquire rewrite that makes every query of a comparison read one ledger block (#355)."""
import json
import pathlib
import struct
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import compare, util  # noqa: E402
from devnet import pinnedquery as pq  # noqa: E402
from devnet.cbor import enc  # noqa: E402

POINT = [14124, bytes(range(32))]


def segment(protocol_field, payload, timestamp=7):
    return struct.pack(">IHH", timestamp, protocol_field, len(payload)) + payload


class AcquirePinTest(unittest.TestCase):
    def pin(self):
        return pq.AcquirePin(lambda: POINT)

    def test_acquire_volatile_tip_becomes_acquire_point(self):
        pin = self.pin()
        self.assertEqual(pin.feed(enc([8])), [enc([0, POINT])])
        self.assertEqual(pin.acquires, 1)

    def test_reacquire_volatile_tip_becomes_reacquire_point(self):
        pin = self.pin()
        self.assertEqual(pin.feed(enc([9])), [enc([6, POINT])])
        self.assertEqual(pin.acquires, 1)

    def test_other_messages_pass_byte_identical(self):
        pin = self.pin()
        messages = [enc([3, [0, [2, [1]]]]), enc([5]), enc([7]), enc([1]), enc([2])]
        self.assertEqual(pin.feed(b"".join(messages)), messages)
        self.assertEqual(pin.acquires, 0)

    def test_explicit_point_acquire_and_reacquire_are_pinned_and_a_foreign_point_is_counted(self):
        pin = self.pin()
        other = [1, bytes(32)]
        self.assertEqual(pin.feed(enc([0, POINT]) + enc([6, POINT])), [enc([0, POINT]), enc([6, POINT])])
        self.assertEqual((pin.acquires, pin.foreign), (2, 0))
        self.assertEqual(pin.feed(enc([0, other]) + enc([6, other]) + enc([0, []])),
                         [enc([0, POINT]), enc([6, POINT]), enc([0, POINT])])
        self.assertEqual((pin.acquires, pin.foreign), (5, 3))

    def test_immutable_tip_acquire_and_reacquire_are_pinned_and_counted_as_foreign(self):
        pin = self.pin()
        self.assertEqual(pin.feed(enc([10]) + enc([11])), [enc([0, POINT]), enc([6, POINT])])
        self.assertEqual((pin.acquires, pin.foreign), (2, 2))

    def test_messages_split_across_segments_are_found(self):
        pin = self.pin()
        query = enc([3, [0, [2, [1, "x" * 40]]]])
        out = pin.feed(query[:5])
        out += pin.feed(query[5:])
        out += pin.feed(enc([8]))
        self.assertEqual(out, [query, enc([0, POINT])])

    def test_a_message_run_together_with_the_next_is_separated(self):
        pin = self.pin()
        self.assertEqual(pin.feed(enc([8]) + enc([3, [0, [2, [1]]]]) + enc([5])),
                         [enc([0, POINT]), enc([3, [0, [2, [1]]]]), enc([5])])

    def test_the_point_is_read_when_the_acquire_arrives(self):
        chosen = []
        pin = pq.AcquirePin(lambda: chosen[0])
        chosen.append([5, bytes(32)])
        self.assertEqual(pin.feed(enc([8])), [enc([0, [5, bytes(32)]])])


class SegmentsTest(unittest.TestCase):
    def test_other_mini_protocols_and_the_mode_bit_are_untouched(self):
        pin, stream = pq.AcquirePin(lambda: POINT), pq.MuxStream()
        handshake = segment(0, b"\x82\x00\xa1\x01\x02")
        chain_sync = segment(5, b"\x81\x08")  # not LocalStateQuery: an [8] here is not ours to rewrite
        self.assertEqual(pq.pin_client_segments(stream, pin, handshake + chain_sync), handshake + chain_sync)
        self.assertEqual(pin.acquires, 0)

    def test_local_state_query_acquire_is_rewritten_and_reframed(self):
        pin, stream = pq.AcquirePin(lambda: POINT), pq.MuxStream()
        out = pq.pin_client_segments(stream, pin, segment(7, enc([8]), timestamp=99))
        self.assertEqual(out, segment(7, enc([0, POINT]), timestamp=99))
        self.assertEqual(pin.acquires, 1)

    def test_a_segment_arriving_in_pieces(self):
        pin, stream = pq.AcquirePin(lambda: POINT), pq.MuxStream()
        raw = segment(7, enc([8])) + segment(7, enc([3, [0, [2, [1]]]]))
        out = b"".join(pq.pin_client_segments(stream, pin, raw[i:i + 3]) for i in range(0, len(raw), 3))
        self.assertEqual(out, segment(7, enc([0, POINT])) + segment(7, enc([3, [0, [2, [1]]]])))

    def test_a_large_message_is_split_into_segments_of_the_mux_limit(self):
        message = enc([3, b"x" * (pq.MAX_PAYLOAD + 100)])
        framed = pq.segments(7, 1, message)
        stream = pq.MuxStream()
        parts = stream.feed(framed)
        self.assertEqual([len(p) for _, _, p in parts], [pq.MAX_PAYLOAD, len(message) - pq.MAX_PAYLOAD])
        self.assertEqual(b"".join(p for _, _, p in parts), message)


class ReferenceSocketTest(unittest.TestCase):
    def test_the_pinned_queries_use_the_socket_the_scenario_selected(self):
        saved = util.SOCKET
        self.addCleanup(setattr, util, "SOCKET", saved)
        util.SOCKET = f"--testnet-magic {util.MAGIC} --socket-path {util.ENV}/socket/node2/sock"
        self.assertEqual(compare.reference_socket(), f"{util.ENV}/socket/node2/sock")


if __name__ == "__main__":
    unittest.main()


class QueryBundleVerdictTest(unittest.TestCase):
    """`query_bundle` accepts a query only on a recorded exit status of 0 (#355)."""
    KEYS = [k for k, _ in compare.BUNDLE]

    def run_bundle(self, rc, acquires=None, json_body="{}", foreign=None, tip_read=None, bundle_exit=0):
        """The bundle verdict when every query prints `json_body`; `rc(key)` is its recorded status (None records none).
        `tip_read` replaces the tip read's result, `bundle_exit` is the exit status of the bundle's script."""
        out = ""
        for key in self.KEYS:
            status = rc(key)
            out += f"@@{key}\n{json_body}\n@@{key}.err\nboom\n"
            if status is not None:
                out += f"@@{key}.rc\n{status}\n"
            out += f"@@{key}.ms\n12\n"
        pin = Mock()
        pin.ready_script.return_value = "true"
        pin.path.side_effect = lambda key: f"/p/{key}"
        pin.foreign.return_value = foreign or {}
        pin.acquires.return_value = acquires if acquires is not None else {k: 1 for k in self.KEYS}
        tip = json.dumps({"slot": 5, "hash": bytes(32).hex(), "block": 1, "epoch": 0})
        with patch.object(compare.pinnedquery, "Pin", return_value=pin), \
                patch.object(compare, "in_ref", side_effect=[tip_read or Mock(stdout=tip, returncode=0),
                                                             Mock(stdout=out, stderr="gone", returncode=bundle_exit)]), \
                patch.object(compare, "ref", return_value="ref"), \
                patch.object(compare, "reference_socket", return_value="/s"):
            return compare.query_bundle("n")

    def test_a_failed_tip_read_is_a_failed_attempt_not_an_exit(self):
        # A raised SystemExit ended the era-load worker without a trace and its comparisons stopped (#591).
        bundle, why = self.run_bundle(lambda k: "0", tip_read=Mock(stdout="", stderr="socket gone\n", returncode=1))
        self.assertIsNone(bundle)
        self.assertEqual(why, {"query": "tip", "exit": "1", "stderr": "socket gone"})

    def test_a_malformed_tip_is_a_failed_attempt(self):
        bundle, why = self.run_bundle(lambda k: "0", tip_read=Mock(stdout="not json", stderr="", returncode=0))
        self.assertIsNone(bundle)
        self.assertEqual(why, {"query": "tip", "unparsable": "JSONDecodeError"})

    def test_a_failed_bundle_script_is_a_failed_attempt(self):
        bundle, why = self.run_bundle(lambda k: "0", bundle_exit=125)
        self.assertIsNone(bundle)
        self.assertEqual(why, {"query": "bundle", "exit": "125", "stderr": "gone", "slot": 5})

    def test_a_failed_tip_read_is_retried_by_the_comparison(self):
        calls = iter([(None, {"query": "tip", "exit": "1", "stderr": ""})] * 2)
        with patch.object(compare, "query_bundle", side_effect=lambda name: next(calls)):
            result, failures = compare.compare_once_detailed("n", attempts=2, scala_wait=1)
        self.assertIsNone(result)
        self.assertEqual([f["query"] for f in failures], ["tip", "tip"])

    def test_zero_exit_with_json_is_accepted(self):
        bundle, why = self.run_bundle(lambda k: "0")
        self.assertIsNone(why)
        self.assertEqual(bundle["ls"], {})

    def test_valid_json_with_nonzero_exit_fails_with_its_record(self):
        bundle, why = self.run_bundle(lambda k: "1" if k == "gov" else "0")
        self.assertIsNone(bundle)
        self.assertEqual(why, {"query": "gov", "exit": "1", "stderr": "boom", "ms": "12", "slot": 5, "acquires": 1,
                               "failed": "exit status not zero"})

    def test_missing_exit_status_fails(self):
        bundle, why = self.run_bundle(lambda k: None if k == "utxo" else "0")
        self.assertIsNone(bundle)
        self.assertEqual((why["query"], why["exit"], why["failed"]), ("utxo", None, "exit status missing"))

    def test_malformed_exit_status_fails(self):
        bundle, why = self.run_bundle(lambda k: "x" if k == "ls" else "0")
        self.assertIsNone(bundle)
        self.assertEqual((why["query"], why["exit"], why["failed"]), ("ls", "x", "exit status malformed"))

    def test_unparsable_output_with_zero_exit_fails(self):
        bundle, why = self.run_bundle(lambda k: "0", json_body="not json")
        self.assertIsNone(bundle)
        self.assertEqual(why["unparsable"], "JSONDecodeError")

    def test_a_foreign_explicit_point_fails_the_attempt(self):
        bundle, why = self.run_bundle(lambda k: "0", foreign={"utxo": 1, "ls": 0})
        self.assertIsNone(bundle)
        self.assertEqual(why["foreignAcquires"], {"utxo": 1})

    def test_unpinned_query_fails_even_when_every_query_succeeded(self):
        bundle, why = self.run_bundle(lambda k: "0", acquires={**{k: 1 for k in self.KEYS}, "drep": 0})
        self.assertIsNone(bundle)
        self.assertEqual(why["unpinned"], ["drep"])


class CompareOrErrorTest(unittest.TestCase):
    def test_a_comparison_that_could_not_be_taken_is_an_error_record(self):
        failures = [{"query": "ls", "exit": "1"}] * 4
        with patch.object(compare, "compare_once_detailed", return_value=(None, failures)):
            result = compare.compare_or_error("n")
        self.assertEqual(result["queryFailureCount"], 4)
        self.assertEqual(len(result["queryFailures"]), 3)
        self.assertIn("error", result)

    def test_a_taken_comparison_passes_through(self):
        with patch.object(compare, "compare_once_detailed", return_value=({"matched": True}, None)):
            self.assertEqual(compare.compare_or_error("n"), {"matched": True})


class FinalComparisonsTest(unittest.TestCase):
    def test_a_failed_try_is_kept_when_a_later_try_succeeds(self):
        from devnet.scenarios import pool
        tries = iter([{"error": "scala node never reached the reference tip"}, {"matched": True}, {"matched": True}])
        self.assertEqual(pool.final_comparisons(lambda: next(tries)),
                         [{"error": "scala node never reached the reference tip"}, {"matched": True}])

    def test_every_failed_try_is_kept_when_all_fail(self):
        from devnet.scenarios import pool
        self.assertEqual(len(pool.final_comparisons(lambda: {"error": "x"})), 3)


class CompareOnceForeignTest(unittest.TestCase):
    def test_a_foreign_acquire_is_not_retried_away_by_a_clean_attempt(self):
        clean = ({"tip": {}}, None)
        foreign = (None, {"foreignAcquires": {"utxo": 1}, "slot": 5})
        calls = iter([foreign, clean])
        with patch.object(compare, "query_bundle", side_effect=lambda name: next(calls)):
            result, failures = compare.compare_once_detailed("n")
        self.assertIsNone(result)
        self.assertEqual(failures, [foreign[1]])
        calls = iter([foreign, clean])
        with patch.object(compare, "query_bundle", side_effect=lambda name: next(calls)):
            self.assertIn("error", compare.compare_or_error("n"))
