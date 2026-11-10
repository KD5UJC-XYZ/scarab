# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the case builder of the Dijkstra rules oracle (`scripts/oracle/dijkstra-rules/run.py`,
#54): it reads the parts of a block with an explicit stack, whatever their nesting, rewrites a header's body
hash, and builds the cases the fixture records. No Docker.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import hashlib
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load():
    spec = importlib.util.spec_from_file_location("dijkstra_rules_run", ROOT / "scripts/oracle/dijkstra-rules/run.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


run = load()


def nested(depth):
    """`depth` singleton lists around 0: the auxiliary data a peer may nest without bound."""
    return bytes([0x81]) * depth + b"\x00"


def block(aux, body_hash=b"\x07" * 32, body_size=5, flag=b"\xf5"):
    """A `[8, [header, body]]` block whose one transaction carries `aux` as its auxiliary data and `flag` as
    its validity flag."""
    fields = [run.uint(i) for i in range(6)] + [run.uint(body_size), run.bstr(body_hash)] + \
        [run.uint(0)] * 4
    header = run.arr(run.arr(*fields), run.bstr(b"s" * 4))
    tx = run.arr(run.cmap([]), run.cmap([]), aux, flag)
    body = run.arr(run.arr(tx), run.NULL, run.NULL)
    return run.arr(run.uint(8), run.arr(header, body)), tx, body


class BlockPartsTest(unittest.TestCase):
    def test_a_transaction_nested_beyond_the_recursion_limit_is_read(self):
        for depth in (1500, sys.getrecursionlimit() * 20):
            aux = nested(depth)
            raw, _, _ = block(aux)
            self.assertEqual(run.devnet_tx(raw), [run.cmap([]), run.cmap([]), aux, b"\xf5"], depth)

    def test_the_body_its_hash_and_its_size(self):
        raw, _, body = block(nested(1500), body_hash=b"\x09" * 32)
        self.assertEqual(run.block_body(raw), body)
        self.assertEqual(run.declared_body_hash(raw), (b"\x09" * 32).hex())
        resized = run.with_body_size(raw, 1)
        self.assertEqual(run.declared_body_hash(resized), (b"\x09" * 32).hex())
        self.assertEqual(resized, raw.replace(run.uint(5) + run.bstr(b"\x09" * 32), run.uint(6) + run.bstr(b"\x09" * 32)))

    def test_the_body_hash_replaced(self):
        raw, _, _ = block(nested(1500))
        expected, _, _ = block(nested(1500), body_hash=b"\x0a" * 32)
        self.assertEqual(run.with_body_hash(raw, b"\x0a" * 32), expected)
        resized, _, _ = block(nested(1500), body_hash=b"\x0a" * 32, body_size=6)
        self.assertEqual(run.with_body_hash(run.with_body_size(raw, 1), b"\x0a" * 32), resized)

    def test_the_invalid_flag_and_the_hash_of_the_body_so_changed(self):
        raw, _, _ = block(nested(1500))
        _, _, flipped_body = block(nested(1500), flag=b"\xf4")
        digest = hashlib.blake2b(flipped_body, digest_size=32).digest()
        expected, _, _ = block(nested(1500), body_hash=digest, flag=b"\xf4")
        self.assertEqual(run.with_invalid_flag(raw), expected)

    def test_the_devnet_block_flagged_invalid(self):
        # The Blake2b-256 of block 37's body with its transaction's flag `f5` made `f4`, computed apart
        # from the oracle; the reference finds no body hash mismatch in the recorded case.
        rehashed = "45e5c2d073f29e4e5220dbf2bd4e73ec825a2f13c1233646757dfc267266653c"
        rows = [line.split("\t") for line in run.BLOCKS.read_text().splitlines()[1:] if line]
        raw = bytes.fromhex(next(r[4] for r in rows if r[1] == str(run.DEVNET_TX_BLOCK)))
        name = f"bbody/devnet-{run.DEVNET_TX_BLOCK}-invalid-flag"
        flipped = bytes.fromhex(next(c["block"] for c in run.generate() if c["name"] == name))
        self.assertEqual(run.declared_body_hash(flipped), rehashed)
        self.assertNotEqual(run.declared_body_hash(raw), rehashed)
        self.assertEqual(run.devnet_tx(flipped)[3], b"\xf4")
        # with the old hash put back, the flag is the only byte that differs
        restored = flipped.replace(bytes.fromhex(rehashed), bytes.fromhex(run.declared_body_hash(raw)))
        self.assertEqual(len(restored), len(raw))
        self.assertEqual([(a, b) for a, b in zip(raw, restored) if a != b], [(0xF5, 0xF4)])

    def test_the_fixture_holds_the_generated_cases(self):
        self.assertEqual([run.strip(c) for c in run.recorded()], run.generate())

    def test_the_devnet_block_with_a_transaction(self):
        rows = [line.split("\t") for line in run.BLOCKS.read_text().splitlines()[1:] if line]
        raw = bytes.fromhex(next(r[4] for r in rows if r[1] == str(run.DEVNET_TX_BLOCK)))
        body = run.block_body(raw)
        self.assertEqual(run.declared_body_hash(raw), hashlib.blake2b(body, digest_size=32).hexdigest())
        self.assertEqual(len(run.devnet_tx(raw)), 4)


if __name__ == "__main__":
    unittest.main()
