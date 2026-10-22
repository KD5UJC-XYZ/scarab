# SPDX-License-Identifier: Apache-2.0
"""The acquire rewrite that makes every query of a comparison read one ledger block (#355)."""
import pathlib
import struct
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
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
        messages = [enc([3, [0, [2, [1]]]]), enc([5]), enc([7]), enc([10]), enc([0, [1, bytes(32)]])]
        self.assertEqual(pin.feed(b"".join(messages)), messages)
        self.assertEqual(pin.acquires, 0)

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


if __name__ == "__main__":
    unittest.main()
