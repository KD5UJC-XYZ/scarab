# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the pure parts of the devnet package (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import json
import pathlib
import sys
import unittest
from fractions import Fraction

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cbor, compare, encoding, eratx, eraview, govview, ntcquery  # noqa: E402

RFC8032_SEED = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
RFC8032_PUB = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
RFC8032_SIG = ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46b"
               "d25bf5f0595bbe24655141438e7a100b")


def bech32_text(hrp, data):
    """Encode bytes as the bech32 data part with a dummy checksum (the decoder ignores the checksum)."""
    acc = int.from_bytes(data, "big")
    bits = len(data) * 8
    pad = (-bits) % 5
    acc <<= pad
    chars = [encoding.BECH32[(acc >> (5 * i)) & 31] for i in reversed(range((bits + pad) // 5))]
    return hrp + "1" + "".join(chars) + "qqqqqq"


class EncodingTest(unittest.TestCase):
    def test_bech32_roundtrip(self):
        for data in (b"\x01\x02\x03\x04\x05", bytes(range(29)), bytes(57)):
            self.assertEqual(encoding.bech32_bytes(bech32_text("addr_test", data))[:len(data)], data)

    def test_base58(self):
        self.assertEqual(encoding.base58_bytes("JxF12TrwUP45BMd"), b"Hello World")
        self.assertEqual(encoding.base58_bytes("112"), b"\0\0\x01")

    def test_address_hex_dispatch(self):
        text = bech32_text("addr_test", bytes(range(29)))
        self.assertTrue(encoding.address_hex(text).startswith(bytes(range(29)).hex()))
        self.assertEqual(encoding.address_hex("JxF12TrwUP45BMd"), b"Hello World".hex())


class CborTest(unittest.TestCase):
    def test_scalars(self):
        self.assertEqual(cbor.enc(0).hex(), "00")
        self.assertEqual(cbor.enc(23).hex(), "17")
        self.assertEqual(cbor.enc(24).hex(), "1818")
        self.assertEqual(cbor.enc(256).hex(), "190100")
        self.assertEqual(cbor.enc(1 << 32).hex(), "1b0000000100000000")
        self.assertEqual(cbor.enc(-1).hex(), "20")
        self.assertEqual(cbor.enc(b"ab").hex(), "426162")
        self.assertEqual(cbor.enc("a").hex(), "6161")
        self.assertEqual(cbor.enc(None).hex(), "f6")
        self.assertEqual(cbor.enc([True, False]).hex(), "82f5f4")

    def test_containers_and_tags(self):
        self.assertEqual(cbor.enc([1, 2]).hex(), "820102")
        self.assertEqual(cbor.enc({1: 2}).hex(), "a10102")
        self.assertEqual(cbor.enc(cbor.Tag(24, b"")).hex(), "d81840")
        self.assertEqual(cbor.enc(cbor.Raw(b"\x01")).hex(), "01")

    def test_map_keys_are_canonically_ordered(self):
        self.assertEqual(cbor.enc({"bb": 1, 10: 2, 1: 3}), cbor.enc({1: 3, 10: 2, "bb": 1}))

    def test_decode_roundtrip(self):
        value = [0, {32784: [1, False]}, b"\x00\xff", -5, 70000, [None]]
        decoded, pos = ntcquery.decode(ntcquery_bytes(value))
        self.assertEqual(decoded, value)
        self.assertEqual(pos, len(ntcquery_bytes(value)))

    def test_decode_incomplete(self):
        with self.assertRaises(ntcquery.Incomplete):
            ntcquery.decode(cbor.enc([1, 2, 3])[:-1])

    def test_decode_tag(self):
        tagged, _ = ntcquery.decode(cbor.enc(cbor.Tag(24, [1, 3])))
        self.assertIsInstance(tagged, ntcquery.Tagged)
        self.assertEqual((tagged.tag, tagged.value), (24, [1, 3]))
        ratio, _ = ntcquery.decode(cbor.enc(cbor.Tag(30, [1, 3])))
        self.assertEqual(ratio, Fraction(1, 3))


def ntcquery_bytes(x):
    return cbor.enc(x)


class Ed25519Test(unittest.TestCase):
    def test_rfc8032_vector_1(self):
        self.assertEqual(eratx.public_key(RFC8032_SEED).hex(), RFC8032_PUB)
        self.assertEqual(eratx.sign(RFC8032_SEED, b"").hex(), RFC8032_SIG)


class TxBuilderTest(unittest.TestCase):
    def key(self):
        return eratx.Key(json.dumps({"cborHex": "5820" + RFC8032_SEED.hex()}))

    def test_key_hash_is_blake2b_224_of_vkey(self):
        key = self.key()
        self.assertEqual(key.vkey.hex(), RFC8032_PUB)
        self.assertEqual(key.hash, eratx.blake2b(key.vkey, 28))
        self.assertEqual(len(key.hash), 28)

    def test_addresses(self):
        h = bytes(28)
        self.assertEqual(eratx.enterprise_address(h)[0], 0x60)
        self.assertEqual(eratx.enterprise_address(h, script=True)[0], 0x70)
        self.assertEqual(eratx.reward_address(h)[0], 0xe0)
        self.assertEqual(eratx.base_address(h, h, 1)[0], 1)
        self.assertEqual(len(eratx.base_address(h, h)), 57)

    def test_native_script_hash_has_tag_prefix(self):
        script = eratx.all_script(eratx.sig_script(bytes(28)), eratx.after_script(10))
        self.assertEqual(eratx.script_hash(script), eratx.blake2b(b"\x00" + cbor.enc(script), 28))

    def test_build_tx_conway_structure(self):
        key = self.key()
        address = eratx.enterprise_address(key.hash)
        out = eratx.tx_out("conway", address, 5_000_000)
        tx, txid = eratx.build_tx("conway", [("11" * 32, 0)], [out], 200_000, [key], ttl=1000)
        decoded, end = ntcquery.decode(tx)
        self.assertEqual(end, len(tx))
        body, witnesses, valid, aux = decoded
        self.assertEqual(body[2], 200_000)
        self.assertEqual(body[3], 1000)
        self.assertEqual(body[0], [[bytes.fromhex("11" * 32), 0]])
        self.assertEqual(valid, True)
        self.assertIsNone(aux)
        vkey, signature = witnesses[0][0]
        self.assertEqual(vkey, key.vkey)
        self.assertEqual(len(signature), 64)
        self.assertEqual(txid, eratx.blake2b(cbor.enc(body)).hex())

    def test_build_tx_is_deterministic_and_pre_alonzo_has_three_elements(self):
        key = self.key()
        out = eratx.tx_out("shelley", eratx.enterprise_address(key.hash), 1_000_000)
        first = eratx.build_tx("shelley", [("22" * 32, 1)], [out], 170_000, [key], ttl=5)
        self.assertEqual(first, eratx.build_tx("shelley", [("22" * 32, 1)], [out], 170_000, [key], ttl=5))
        decoded, _ = ntcquery.decode(first[0])
        self.assertEqual(len(decoded), 3)

    def test_envelope(self):
        env = json.loads(eratx.envelope("conway", b"\x01"))
        self.assertEqual(env["cborHex"], "01")
        self.assertEqual(env["type"], eratx.ERA_TYPE["conway"])


class ProjectionTest(unittest.TestCase):
    def test_diff_reports_only_differences(self):
        self.assertEqual(compare.diff({"a": 1, "b": {"c": [1, 2]}}, {"a": 1, "b": {"c": [1, 2]}}), [])
        self.assertEqual(compare.diff({"a": 1, "b": {"c": 2}, "x": 1}, {"a": 1, "b": {"c": 3}, "y": 1}),
                         [("/b/c", 2, 3), ("/x", 1, "<absent>"), ("/y", "<absent>", 1)])

    def test_conway_output(self):
        text = bech32_text("addr_test", bytes(range(29)))
        view = compare.conway_output({"address": text, "value": {"lovelace": 7, "ab": {"cd": 3}}})
        self.assertEqual(view["lovelace"], 7)
        self.assertEqual(view["assets"], {"ab": {"cd": 3}})
        self.assertNotIn("assets", compare.conway_output({"address": text, "value": {"lovelace": 7}}))

    def test_era_view_helpers(self):
        self.assertEqual(eraview.ratio(0.25), "1/4")
        self.assertEqual(eraview.cred([0, b"\x01"]), "keyHash-01")
        self.assertEqual(eraview.cred([1, b"\x02"]), "scriptHash-02")
        self.assertEqual(eraview.nonce([1, b"\xab"]), "ab")
        self.assertIsNone(eraview.nonce([0]))
        self.assertEqual(eraview._float({"a": 0.1 + 0.2}), {"a": 0.3})

    def test_gov_view_helpers(self):
        self.assertEqual(govview.ratio(0.5), "1/2")
        self.assertEqual(govview.ratio({"numerator": 2, "denominator": 6}), "1/3")
        self.assertEqual(govview.cred({"keyHash": "ab"}), "keyHash-ab")
        self.assertEqual(govview.cred("scriptHash-cd"), "scriptHash-cd")
        self.assertEqual(govview.action_id({"txId": "aa", "govActionIx": 2}), "aa#2")
        self.assertIsNone(govview.opt_id(None))
        self.assertEqual(Fraction("0.5"), Fraction(1, 2))


class ReferenceImageTest(unittest.TestCase):
    def test_pinned_constants_and_rejection(self):
        import tempfile
        import prepare_reference_image as pri
        self.assertEqual(len(pri.ARCHIVE_SHA256), 64)
        self.assertTrue(pri.ARCHIVE_URL.startswith("https://github.com/IntersectMBO/cardano-node/releases/download/11.1.3/"))
        self.assertIn("@sha256:", pri.BASE)
        with tempfile.TemporaryDirectory() as d:
            bad = pathlib.Path(d) / "x.tar.gz"
            bad.write_bytes(b"not the archive")
            with self.assertRaises(ValueError):
                pri.verify_archive(bad)


if __name__ == "__main__":
    unittest.main()
