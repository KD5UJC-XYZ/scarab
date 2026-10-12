# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the pure parts of the db-analyser oracle (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import pathlib
import struct
import sys
import unittest
from fractions import Fraction

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from oracle import analyse, compare, digest, ledger, mempack  # noqa: E402


class DigestTest(unittest.TestCase):
    ENTRIES = [("keyHash-aa", {"b": 1, "a": [1, "z", None, True], "c": {"y": "é\"q", "x": 0}}),
               ("txid#0", "abc"), ("zz", 12345678901234567890)]

    def test_canonical_text(self):
        self.assertEqual(digest.canon(self.ENTRIES[0][1]), '{"a":[1,"z",null,true],"b":1,"c":{"x":0,"y":"é\\"q"}}')

    def test_vector_shared_with_the_scala_suite(self):
        # node/src/test/scala/lab/node/StateDumpSuite.scala asserts the same digest
        d = digest.digest_of(self.ENTRIES)
        self.assertEqual(d["count"], 3)
        self.assertEqual(d["digest"], "a5b1f3f957573bb07fd0d8397839851cbc5245c80cac5d46ca04e58e0dda1cad")
        self.assertEqual([i for i, b in enumerate(d["buckets"]) if not b.startswith("0:")], [0, 9, 10])
        self.assertEqual(d["buckets"][0], "1:3d307e8b04b3a97dc95e798cf0204230ddaf8425115050259ce1801807418247")

    def test_order_does_not_matter(self):
        self.assertEqual(digest.digest_of(self.ENTRIES), digest.digest_of(reversed(self.ENTRIES)))

    def test_reduce_threshold(self):
        small = {f"k{i}": i for i in range(digest.THRESHOLD)}
        big = {f"k{i}": i for i in range(digest.THRESHOLD + 1)}
        out = digest.reduce({"small": small, "big": big, "list": [big]})
        self.assertEqual(out["small"], small)
        self.assertEqual(out["big"]["count"], digest.THRESHOLD + 1)
        self.assertEqual(out["list"][0]["count"], digest.THRESHOLD + 1)


class MemPackTest(unittest.TestCase):
    def test_varlen_is_most_significant_group_first(self):
        self.assertEqual(mempack.Reader(bytes.fromhex("82b19640")).varlen(), 5_000_000)
        self.assertEqual(mempack.Reader(bytes([0x7f])).varlen(), 127)
        self.assertEqual(mempack.Reader(bytes([0x81, 0x00])).varlen(), 128)

    def test_alonzo_ada_only_output_with_a_compact_base_address(self):
        value = bytes.fromhex("0201ba149e2e2379097e65f0c03f2733d3103151e7f100d36dfdb01a0b22b33943c9f08e804f40ea4f06"
                              "3de3afc98d3ea5864f7acbab01000000e578e8130096deb183e98000")
        out = mempack.tx_out(4, value)
        self.assertEqual(out["lovelace"], 100000000000000)
        self.assertEqual(out["address"], "004f808ef0c94339b3c9afe33d064fea40abcb7a4f86a53e8d13e878e5"
                                         "ba149e2e2379097e65f0c03f2733d3103151e7f100d36dfdb01a0b22")

    def test_conway_output_with_an_inline_datum_has_no_datum_tag(self):
        value = bytes.fromhex("0439002303edceebc0fdc68bebade2d158af4eb5af0f397975f94e372256dee7cf166ce689d6ca8546027c"
                              "511462c695a769ecf74128a163c900e40082b196400a82182a46696e6c696e65")
        out = mempack.tx_out(6, value)
        self.assertEqual(out["lovelace"], 5_000_000)
        self.assertEqual(out["inlineDatum"], "82182a46696e6c696e65")

    def test_multi_asset_value(self):
        # two assets with the same policy; names "ab" and "" (the empty name points past the end)
        policy = bytes(range(28))
        names = b"ab"
        header = 8 * 2 + 2 * 2 + 2 * 2
        pid_at = header
        name_at = pid_at + 28
        rep = struct.pack("<2Q2H2H", 7, 9, pid_at, pid_at, name_at, name_at + len(names)) + policy + names
        addr = bytes([0x61]) + bytes(28)
        value = bytes([0]) + bytes([len(addr)]) + addr + bytes([1, 5, 2, len(rep)]) + rep
        out = mempack.tx_out(5, value)
        self.assertEqual(out["lovelace"], 5)
        self.assertEqual(out["assets"], {policy.hex(): {b"ab".hex(): 7, "": 9}})

    def test_reference_script_hash_uses_the_language_prefix(self):
        addr = bytes([0x61]) + bytes(28)
        # AlonzoScript tag 1 (Plutus), PlutusV2 (tag 1), five bytes
        script = bytes([1]) + bytes([1]) + bytes([5]) + b"\x01\x02\x03\x04\x05"
        value = bytes([5, len(addr)]) + addr + bytes([0, 9]) + bytes([0]) + script
        out = mempack.tx_out(5, value)
        self.assertEqual(out["scriptRef"], mempack.blake2b224(bytes([2]) + b"\x01\x02\x03\x04\x05"))

    def test_tables_with_definite_and_indefinite_maps(self):
        key = bytes(32) + b"\x00\x01"
        entry = bytes([0x58, 34]) + key + bytes([0x41]) + b"\x00"
        definite = bytes([0x81, 0xa1]) + entry
        indefinite = bytes([0x81, 0xbf]) + entry + bytes([0xff])
        self.assertEqual(list(mempack.read_tables(definite)), [(f"{'00' * 32}#1", b"\x00")])
        self.assertEqual(list(mempack.read_tables(indefinite)), [(f"{'00' * 32}#1", b"\x00")])


class CompareTest(unittest.TestCase):
    def test_digest_difference_names_the_buckets(self):
        a = digest.digest_of([("a", 1), ("b", 2)])
        b = digest.digest_of([("a", 1), ("b", 3)])
        found = compare.diff({"m": a}, {"m": b})
        self.assertEqual(len(found), 1)
        self.assertIn("buckets", found[0][1])

    def test_a_digest_is_compared_with_an_unreduced_object(self):
        plain = {"a": 1, "b": 2}
        self.assertEqual(compare.diff({"m": digest.digest_of(plain.items())}, {"m": plain}), [])
        self.assertEqual(len(compare.diff({"m": digest.digest_of(plain.items())}, {"m": {"a": 1}})), 1)

    def test_the_scala_projection_may_have_more_keys_but_not_fewer(self):
        self.assertEqual(compare.diff({"x": 1}, {"x": 1, "y": 2}), [])
        self.assertEqual(compare.diff({"x": 1, "y": 2}, {"x": 1}), [("/y", 2, "<absent>")])

    def test_doubles_are_compared_to_ten_digits(self):
        self.assertEqual(compare.diff({"d": 0.3333333333}, {"d": 0.33333333333333}), [])
        self.assertEqual(len(compare.diff({"d": 0.3}, {"d": 0.31})), 1)


class LedgerPiecesTest(unittest.TestCase):
    def test_credentials_dreps_and_reward_accounts(self):
        h = bytes(range(28))
        self.assertEqual(ledger.cred((0, h)), f"keyHash-{h.hex()}")
        self.assertEqual(ledger.cred((1, h)), f"scriptHash-{h.hex()}")
        self.assertEqual(ledger.drep((2,)), "drep-alwaysAbstain")
        self.assertEqual(ledger.drep([3]), "drep-alwaysNoConfidence")
        self.assertEqual(ledger.drep((1, h)), f"drep-scriptHash-{h.hex()}")
        self.assertEqual(ledger.reward_account_cred(bytes([0xe1]) + h), f"keyHash-{h.hex()}")
        self.assertEqual(ledger.reward_account_cred(bytes([0xf0]) + h), f"scriptHash-{h.hex()}")

    def test_rationals(self):
        self.assertEqual(ledger.ratio(Fraction(6, 8)), "3/4")
        self.assertEqual(ledger.ratio([1, 3]), "1/3")
        self.assertEqual(ledger.double(Fraction(1, 3)), 0.3333333333)

    def test_anchor_encodings(self):
        a = ["https://x", bytes(32)]
        self.assertIsNone(ledger.maybe_anchor(None))
        self.assertIsNone(ledger.maybe_anchor([]))
        self.assertEqual(ledger.maybe_anchor([a]), ledger.anchor(a))
        self.assertEqual(ledger.maybe_anchor(a), ledger.anchor(a))

    def test_babbage_parameters_keep_the_era_names(self):
        pp = [44, 155381, 65536, 16384, 1100, 2000000, 500000000, 18, 150, Fraction(3, 10), Fraction(3, 1000),
              Fraction(1, 5), [8, 0], 340000000, 4310, {}, [Fraction(577, 10000), Fraction(721, 10000000)],
              [14000000, 10000000000], [62000000, 20000000000], 5000, 150, 3]
        out = ledger.params(5, pp)
        self.assertEqual(out["protocolVersion"], {"major": 8, "minor": 0})
        self.assertEqual(out["utxoCostPerByte"], 4310)
        self.assertEqual(out["executionUnitPrices"], {"priceMemory": 0.0577, "priceSteps": 0.0000721})
        self.assertNotIn("costModels", out)
        with self.assertRaises(ValueError):
            ledger.params(6, pp)

    def test_the_analyser_output_is_parsed(self):
        self.assertEqual(analyse.STORED.findall("[1.5s] Snapshot stored at SlotNo 4600\n")[-1], "4600")
        tip = "ImmutableDB tip: Point (At (Block {blockPointSlot = SlotNo 12862, blockPointHash = ab}))"
        self.assertEqual(analyse.TIP.search(tip).group(1), "12862")


class ExportTest(unittest.TestCase):
    def block(self, header_len):
        # `82 <era> 85 <header: a byte string of header_len bytes> 80 80 80 80`
        from devnet import cbor
        header = cbor.enc(bytes(header_len))
        return bytes([0x82, 0x07, 0x85]) + header + bytes([0x80] * 4)

    def test_header_span_is_the_first_element_of_the_block_array(self):
        from oracle import export
        b = self.block(200)
        offset, size = export.header_span(b)
        self.assertEqual(offset, 3)
        self.assertEqual(size, 202)  # byte string head (0x58 0xc8) and 200 bytes
        self.assertEqual(b[offset:offset + size][:2], bytes([0x58, 200]))

    def test_primary_index_marks_filled_relative_slots(self):
        from oracle import export
        # chunk 2 of 10-slot chunks holds slots 20..29; slots 20 and 23 are filled
        p = export.primary_index(2, 10, [20, 23])
        self.assertEqual(p[0], 1)
        offsets = struct.unpack(f">{(len(p) - 1) // 4}I", p[1:])
        self.assertEqual(len(offsets), 10 + 2)
        # index 0: epoch boundary block (none); index 1: slot 20; index 4: slot 23
        self.assertEqual(offsets, (0, 0, 56, 56, 56, 112, 112, 112, 112, 112, 112, 112))

    def test_secondary_entry_layout(self):
        from oracle import export
        b = self.block(10)
        e = export.secondary_entry(1234, b, bytes(range(32)), 0xdeadbeef, 99)
        self.assertEqual(len(e), 56)
        self.assertEqual(struct.unpack(">QHHI", e[:16]), (1234, 3, 11, 0xdeadbeef))
        self.assertEqual(e[16:48], bytes(range(32)))
        self.assertEqual(struct.unpack(">Q", e[48:])[0], 99)


class CompareDirsTest(unittest.TestCase):
    def test_pairs_by_block_and_reports_waiting_and_mismatches(self):
        import json
        import tempfile
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t)
            ref, dump = t / "ref", t / "dump"
            ref.mkdir()
            dump.mkdir()
            rows = []
            for epoch, slot, treasury in ((1, 100, 5), (2, 200, 6), (3, 300, 7)):
                h = f"{epoch:064x}"
                rows.append({"epoch": epoch, "slot": slot, "hash": h, "era": "babbage", "utxoSize": 1})
                (ref / f"{slot:012d}-{h}.json").write_text(json.dumps({"treasury": treasury, "era": "babbage"}))
            (ref / "index.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
            (dump / f"{100:012d}-{1:064x}.json").write_text(json.dumps({"treasury": 5, "extra": 1}))
            (dump / f"{200:012d}-{2:064x}.json").write_text(json.dumps({"treasury": 99}))
            summary = compare.compare_dirs(ref, dump, t / "results.json")
            self.assertEqual((summary["compared"], summary["matched"], summary["mismatched"],
                              summary["waitingForScala"]), (2, 1, 1, 1))
            self.assertEqual(summary["firstMismatch"]["epoch"], 2)
            self.assertEqual(summary["firstMismatch"]["differences"][0]["path"], "/treasury")


if __name__ == "__main__":
    unittest.main()
