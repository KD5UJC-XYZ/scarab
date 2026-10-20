# SPDX-License-Identifier: Apache-2.0
"""Scratch staging must never expose writable archive inodes to recovery or migration."""
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location('preview_replay_db', Path(__file__).resolve().parents[1] / 'preview_replay_db.py')
staging = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = staging
spec.loader.exec_module(staging)


class PreviewReplayDbTest(unittest.TestCase):
    @staticmethod
    def archive(root, gap=False):
        immutable = root / 'archive/immutable'
        immutable.mkdir(parents=True)
        (immutable / 'format').write_text('cardano-scala-lab immutable store v1 chunkSlots=21600\n')
        for chunk in range(2):
            data = b''
            entries = b'CSLIDX01'
            for i in range(2):
                number = chunk * 2 + i + (1 if gap and chunk else 0)
                slot = number * 20
                header = bytes([0x82, 0x82, number]) + (bytes([slot]) if slot < 24 else bytes([0x18, slot])) + b'\x40'
                block = b'\x82\x06\x85' + header + bytes.fromhex('8080a080')
                digest = hashlib.blake2b(header, digest_size=32).digest()
                entries += struct.pack('>QIIQQ', len(data), len(block), zlib.crc32(block), slot, number) + digest + bytes(4)
                data += block
            (immutable / f'{chunk:05d}.index').write_bytes(entries)
            (immutable / f'{chunk:05d}.chunk').write_bytes(data + (b'crash-tail' if chunk else b''))
        return immutable

    def refused(self, problem, function):
        with self.assertRaises(staging.StageRefusal) as caught:
            function()
        self.assertEqual(caught.exception.problem, problem)

    def test_prefix_uses_private_metadata_and_final_chunk_and_preserves_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.archive(root)
            before = {p.name: (p.stat().st_ino, p.read_bytes()) for p in source.iterdir()}
            result = staging.stage(source, root / 'db', PurePosixPath('/input/immutable'), 3)
            target = root / 'db/immutable'
            self.assertEqual(result['blocks'], 3)
            self.assertEqual((result['first']['block'], result['last']['block']), (0, 2))
            self.assertEqual((target / '00000.chunk').readlink(), Path('/input/immutable/00000.chunk'))
            self.assertFalse((target / '00001.chunk').is_symlink())
            self.assertEqual(len((target / '00001.index').read_bytes()), 8 + 68)
            rows, _ = staging.read_index(target / '00001.index')
            end = rows[-1].offset + rows[-1].length
            self.assertEqual((target / '00001.chunk').stat().st_size, end)
            self.assertEqual((target / '00001.chunk').read_bytes(), (source / '00001.chunk').read_bytes()[:end])
            for name in ('format', '00000.index', '00001.index', '00001.chunk'):
                self.assertNotEqual((target / name).stat().st_ino, (source / name).stat().st_ino)
            self.assertEqual(before, {p.name: (p.stat().st_ino, p.read_bytes()) for p in source.iterdir()})
            # Recovery/migration can overwrite every private writable file without touching the source.
            for name in ('format', '00000.index', '00001.index', '00001.chunk'):
                (target / name).write_bytes(b'recovery changed scratch only')
            self.assertEqual(before, {p.name: (p.stat().st_ino, p.read_bytes()) for p in source.iterdir()})
            self.assertEqual(result['files'][-1]['stagedChunkSha256'], hashlib.sha256(before['00001.chunk'][1][:end]).hexdigest())

    def test_full_archive_capability_and_trailing_bytes_removed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.archive(root)
            result = staging.stage(source, root / 'db')
            self.assertEqual(result['blocks'], 4)
            self.assertEqual(result['last']['block'], 3)
            last = result['files'][-1]
            self.assertLess(last['selectedChunkBytes'], last['sourceChunkBytes'])
            self.assertEqual(json.loads((root / 'db/staging-manifest.json').read_text()), result)

    def test_first_chunk_is_private_when_it_is_the_only_selected_chunk(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.archive(root)
            result = staging.stage(source, root / 'db', max_blocks=1)
            self.assertEqual(result['last']['block'], 0)
            self.assertFalse((root / 'db/immutable/00000.chunk').is_symlink())
            self.assertFalse((root / 'db/immutable/00001.index').exists())

    def test_prefix_gaps_refused_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.archive(root, gap=True)
            self.refused(staging.StageProblem.PREFIX_ORDER, lambda: staging.stage(source, root / 'db'))
            self.assertFalse((root / 'db').exists())

    def test_existing_output_archive_destination_bad_limit_and_mount_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.archive(root)
            (root / 'existing').mkdir()
            self.refused(staging.StageProblem.OUTPUT_EXISTS, lambda: staging.stage(source, root / 'existing'))
            self.refused(staging.StageProblem.OUTPUT_IN_ARCHIVE, lambda: staging.stage(source, source.parent / 'scratch'))
            self.refused(staging.StageProblem.CONTAINER_PATH, lambda: staging.stage(source, root / 'db', PurePosixPath('relative')))
            self.refused(staging.StageProblem.CONTAINER_PATH, lambda: staging.stage(source, root / 'db', PurePosixPath('/input/../immutable')))
            for count in (0, -1, True, 10):
                self.refused(staging.StageProblem.PREFIX_LIMIT, lambda: staging.stage(source, root / 'db', max_blocks=count))
            self.assertFalse((root / 'db').exists())


if __name__ == '__main__':
    unittest.main()
