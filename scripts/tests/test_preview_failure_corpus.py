# SPDX-License-Identifier: Apache-2.0
"""Original-byte and bounded-window capture refusals; no Docker or chain archive needed."""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

spec = importlib.util.spec_from_file_location('preview_failure_corpus', Path(__file__).resolve().parents[1] / 'preview_failure_corpus.py')
corpus = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = corpus
spec.loader.exec_module(corpus)


class PreviewFailureCorpusTest(unittest.TestCase):
    def refused(self, problem, call):
        with self.assertRaises(corpus.CaptureRefusal) as caught:
            call()
        self.assertEqual(caught.exception.problem, problem)

    def test_deep_external_cbor_is_stack_safe(self):
        raw = b'\x81' * 50000 + b'\x00'
        self.assertEqual(corpus.item_end(raw, 0), len(raw))

    def test_indefinite_original_spans_are_preserved(self):
        raw = bytes.fromhex('9f5f4201024103ffbf0001ffff')
        self.assertEqual(corpus.item_end(raw, 0), len(raw))
        self.assertEqual([raw[a:b].hex() for a, b in corpus.children(raw, 0, 4)], ['5f4201024103ff', 'bf0001ff'])

    def test_truncated_and_invalid_cbor_refused(self):
        for raw in (b'', b'\xff', b'\x81', b'\x43\x01', b'\x1c', b'\x1f', b'\x5f\x01\xff'):
            self.refused(corpus.Problem.CBOR, lambda: corpus.item_end(raw, 0))

    def test_odd_indefinite_map_refused(self):
        self.refused(corpus.Problem.CBOR, lambda: corpus.children(bytes.fromhex('bf00ff'), 0, 5))

    @staticmethod
    def block():
        # One original empty body/witness, marked invalid, no auxiliary data.
        header = b'\x82\x82\x19' + struct.pack('>H', 52578) + b'\x1a' + struct.pack('>I', 1156258) + b'\x40'
        raw = bytes.fromhex('820685') + header + bytes.fromhex('81a081a0a08100')
        digest = hashlib.blake2b(header, digest_size=32).hexdigest()
        return raw, corpus.Record('00000.chunk', 0, len(raw), zlib.crc32(raw), 1156258, 52578, digest)

    def test_invalid_tx_uses_original_spans_and_body_hash(self):
        import io
        raw, record = self.block()
        original, era, parts = corpus.read_block(io.BytesIO(raw), record)
        rows = corpus.invalid_transactions(original, era, parts, record)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['body'], 'a0')
        self.assertEqual(rows[0]['witnesses'], 'a0')
        self.assertIsNone(rows[0]['auxiliary'])
        self.assertEqual(rows[0]['txId'], hashlib.blake2b(b'\xa0', digest_size=32).hexdigest())
        self.assertFalse(rows[0]['isValid'])

    def test_corrupt_truncated_or_wrong_header_refused(self):
        import io
        from dataclasses import replace
        raw, record = self.block()
        self.refused(corpus.Problem.BLOCK_BOUNDS, lambda: corpus.read_block(io.BytesIO(raw[:-1]), record))
        self.refused(corpus.Problem.BLOCK_CRC, lambda: corpus.read_block(io.BytesIO(raw[:-1] + b'\x01'), record))
        self.refused(corpus.Problem.HEADER_HASH, lambda: corpus.read_block(io.BytesIO(raw), replace(record, hash='00' * 32)))
        self.refused(corpus.Problem.HEADER_POINT, lambda: corpus.read_block(io.BytesIO(raw), replace(record, slot=record.slot + 1)))
        self.refused(corpus.Problem.HEADER_POINT, lambda: corpus.read_block(io.BytesIO(raw), replace(record, block=record.block + 1)))

    def test_duplicate_and_out_of_bounds_invalid_indices_refused(self):
        raw, record = self.block()
        for invalid in ('820000', '8101'):
            changed = raw[:-2] + bytes.fromhex(invalid)
            parts = corpus.children(changed, corpus.children(changed, 0, 4)[1][0], 4)
            self.refused(corpus.Problem.BLOCK_SHAPE, lambda: corpus.invalid_transactions(changed, 6, parts, record))

    def test_index_format_order_flags_and_gaps_refused(self):
        def entry(offset=0, slot=1, block=1, flags=0):
            return struct.pack('>QIIQQ', offset, 1, 0, slot, block) + bytes(32) + bytes([flags, 0, 0, 0])
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / '00000.index'
            path.write_bytes(b'SCRBIDX2' + entry() + entry(1, 2, 2))
            self.assertEqual(len(corpus.read_index(path)[0]), 2)
            for raw, problem in ((b'bad', corpus.Problem.INDEX_FORMAT), (b'SCRBIDX2' + entry(flags=1), corpus.Problem.INDEX_ORDER),
                                 (b'SCRBIDX2' + entry() + entry(2, 2, 2), corpus.Problem.INDEX_ORDER),
                                 (b'SCRBIDX2' + entry() + entry(1, 1, 2), corpus.Problem.INDEX_ORDER),
                                 (b'SCRBIDX2' + entry() + entry(1, 2, 3), corpus.Problem.INDEX_ORDER)):
                path.write_bytes(raw)
                self.refused(problem, lambda: corpus.read_index(path))

    def test_export_binds_native_snapshot_separate_checkpoint_and_complete_window(self):
        import io
        from unittest.mock import patch
        raw, record = self.block()
        _, era, parts = corpus.read_block(io.BytesIO(raw), record)
        invalid = corpus.invalid_transactions(raw, era, parts, record)[0]
        keys = ('slot', 'block', 'hash', 'era', 'epoch', 'txIndex', 'txId', 'bodySha256', 'witnessesSha256')
        golden = {'schema': 1, 'network': 'preview', 'originalFinalTip': {k: invalid[k] for k in ('slot', 'block', 'hash')},
                  'transactions': [{k: invalid[k] for k in keys}]}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            immutable, snapshots, checkpoints = [root / name for name in ('immutable', 'snapshots', 'checkpoints')]
            for directory in (immutable, snapshots, checkpoints):
                directory.mkdir()
            (immutable / record.chunk).write_bytes(raw)
            entry = struct.pack('>QIIQQ', 0, len(raw), record.crc, record.slot, record.block) + bytes.fromhex(record.hash) + bytes(4)
            (immutable / '00000.index').write_bytes(b'SCRBIDX2' + entry)
            baseline = snapshots / f'snapshot-{1123199:020d}-{record.block - 1}.bin'
            baseline.write_bytes(b'SCRBSNP5' + b'native fixture')
            (checkpoints / baseline.stem).mkdir()
            # A snapshot at the failing epoch's start is too late and must be ignored.
            late = snapshots / 'snapshot-00000000000001123200-52578.bin'
            late.write_bytes(b'bad late snapshot')
            golden_path = root / 'golden.json'
            golden_path.write_text(json.dumps(golden))
            with patch.object(corpus, 'load_golden', return_value=golden):
                manifest = corpus.export(immutable, snapshots, checkpoints, golden_path, root / 'output')
                window = manifest['windows'][0]
                self.assertEqual(window['snapshot'], str(baseline))
                self.assertEqual(window['checkpoint'], str(checkpoints / baseline.stem))
                self.assertEqual(window['failedTransactions'], 1)
                self.assertEqual(window['blocks'], 1)
                self.assertEqual(Path(window['capture'], 'immutable').resolve(), immutable)
                self.assertEqual(json.loads((root / 'output/windows.jsonl').read_text())['expectedFailures'], window['expectedInvalidTransactions'])
                self.refused(corpus.Problem.OUTPUT_EXISTS, lambda: corpus.export(immutable, snapshots, checkpoints, golden_path, root / 'output'))
                baseline.write_bytes(b'LEGACY00')
                self.refused(corpus.Problem.SNAPSHOT, lambda: corpus.export(immutable, snapshots, checkpoints, golden_path, root / 'legacy-output'))
                baseline.write_bytes(b'SCRBSNP5')
                (checkpoints / baseline.stem).rmdir()
                self.refused(corpus.Problem.CHECKPOINT, lambda: corpus.export(immutable, snapshots, checkpoints, golden_path, root / 'missing-output'))

    def test_golden_exact_unique_ordered_242(self):
        path = Path(__file__).resolve().parents[2] / 'fixtures/preview/phase2-failures/identities.json'
        golden = corpus.load_golden(path)
        rows = golden['transactions']
        self.assertEqual(len(rows), 242)
        self.assertEqual({era: sum(r['era'] == era for r in rows) for era in ('Babbage', 'Conway')}, {'Babbage': 137, 'Conway': 105})
        self.assertTrue(all(r['slot'] <= golden['originalFinalTip']['slot'] for r in rows))
        with tempfile.TemporaryDirectory() as temp:
            bad = Path(temp) / 'golden.json'
            for changed in (rows[:-1], rows[:-1] + rows[:1], list(reversed(rows))):
                bad.write_text(json.dumps({**golden, 'transactions': changed}))
                with self.assertRaises(corpus.CaptureRefusal):
                    corpus.load_golden(bad)


if __name__ == '__main__':
    unittest.main()
