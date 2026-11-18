# SPDX-License-Identifier: Apache-2.0
"""Deep query fixtures must not depend on Python's recursion limit either."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import cbor, ntcquery, ntcsafety  # noqa: E402


class QuerySafetyTest(unittest.TestCase):
    def test_query_bytes_are_standard_envelopes_at_shallow_depth(self):
        raw = ntcsafety.encoded_query(6, 7, 2)
        self.assertEqual(ntcquery.decode_all(raw), [0, [0, [6, [9, [9, [7]]]]]])

    def test_unknown_tag_probe_is_the_shallow_refusal_form(self):
        self.assertEqual(ntcsafety.INVALID_RAW["unknown-ledger-query-tag"],
                         ntcsafety.encoded_query(6, ntcsafety.UNKNOWN_TAG, 0))
        self.assertEqual(ntcquery.decode_all(ntcsafety.INVALID_RAW["unknown-ledger-query-tag"]),
                         [0, [0, [6, [40]]]])

    def test_query_build_and_answer_unwrap_exceed_python_recursion_limit(self):
        raw = ntcsafety.encoded_query(6, 7, ntcsafety.DEPTH)
        self.assertEqual(len(raw), ntcsafety.DEPTH * 2 + 8)
        leaf = cbor.enc([123, b"hash"])
        size, headers = len(leaf), []
        for _ in range(ntcsafety.DEPTH):
            header = b"\xd8\x18" + cbor.head(2, size)
            headers.append(header)
            size += len(header)
        answer = b"".join(reversed(headers)) + leaf
        self.assertEqual(ntcsafety.unwrap(answer, ntcsafety.DEPTH), leaf)

    def test_unwrap_rejects_wrong_tag_and_trailing_embedded_item(self):
        for raw in (cbor.enc(cbor.Tag(23, b"x")), cbor.enc(cbor.Tag(24, b"x")) + b"\x00"):
            with self.assertRaisesRegex(RuntimeError, "malformed GetCBOR"):
                ntcsafety.unwrap(raw, 1)

    def test_each_invalid_prefix_is_not_a_whole_query(self):
        for label, raw in ntcsafety.INVALID_PREFIXES.items():
            with self.subTest(label=label), self.assertRaises(ntcquery.Incomplete):
                ntcquery.decode(raw)
