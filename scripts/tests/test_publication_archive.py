# SPDX-License-Identifier: Apache-2.0
"""Synthetic mechanism controls, never native build/publication evidence."""
import contextlib
import functools
import http.server
import io
from pathlib import Path
import sys
import tarfile
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import candidate_manifest
from candidate_fixture import make_candidate
import publication_archive as transport
from publication_download import download


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass


class PublicationArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.candidate = self.root / 'candidate'
        self.manifest = make_candidate(self.candidate)
        self.archive = self.root / 'candidate.tar'

    def test_complete_tree_survives_real_http_download_and_actual_validator(self):
        descriptor = transport.pack(self.candidate, self.archive)
        handler = functools.partial(QuietHandler, directory=str(self.root))
        with http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler) as server:
            worker = threading.Thread(target=server.serve_forever)
            worker.start()
            try:
                received = download(f'http://127.0.0.1:{server.server_port}/candidate.tar',
                                    self.root / 'received.tar', descriptor, local=True)
            finally:
                server.shutdown()
                worker.join()
        restored = self.root / 'restored'
        transport.unpack(received, restored)
        self.assertEqual(candidate_manifest.validate(restored, require_release=True), self.manifest)
        self.assertEqual({p.relative_to(self.candidate): p.read_bytes() for p in self.candidate.rglob('*') if p.is_file()},
                         {p.relative_to(restored): p.read_bytes() for p in restored.rglob('*') if p.is_file()})
        second = self.root / 'second.tar'
        self.assertEqual(transport.pack(self.candidate, second)['sha256'], descriptor['sha256'])

    def test_original_libstdc_plus_plus_legal_package_path_survives_roundtrip(self):
        relative = Path('evidence/legal/usr/share/doc/libstdc++6/copyright')
        original = self.candidate / relative
        original.parent.mkdir(parents=True)
        original.write_bytes(b'Public package legal evidence mechanism control.\n')
        transport.pack(self.candidate, self.archive)
        restored = self.root / 'plus-restored'
        transport.unpack(self.archive, restored)
        self.assertEqual((restored / relative).read_bytes(), original.read_bytes())
        self.assertEqual(candidate_manifest.validate(restored, require_release=True), self.manifest)

    def test_plus_does_not_allow_traversal_absolute_backslash_or_empty_components(self):
        for index, name in enumerate(['libstdc++6/../escape', '../libstdc++6/copyright', '/libstdc++6',
                                      r'libstdc++6\copyright', 'libstdc++6//copyright', 'libstdc++6/./copyright']):
            self.write_tar([(name, tarfile.REGTYPE, b'x')])
            with self.subTest(name=name), self.assertRaises(ValueError):
                transport.unpack(self.archive, self.root / f'plus-invalid-{index}')
        self.assertFalse((self.root / 'escape').exists())

    def test_missing_nested_independent_proof_files_fail_actual_validator(self):
        descriptor = transport.pack(self.candidate, self.archive)
        self.assertGreater(descriptor['size'], 0)
        nested = [p.relative_to(self.candidate) for p in self.candidate.rglob('*') if p.is_file()]
        # Select dependencies outside the manifest's top-level descriptors.
        choices = [next(p for p in nested if 'native' in p.parts and 'build-1' in p.parts),
                   next(p for p in nested if 'legal' in p.parts),
                   next(p for p in nested if 'repro' in p.parts and p.name == 'build-1.jar')]
        for index, missing in enumerate(choices):
            with self.subTest(missing=missing):
                restored = self.root / f'missing-{index}'
                transport.unpack(self.archive, restored)
                (restored / missing).unlink()
                with self.assertRaises((ValueError, OSError)):
                    candidate_manifest.validate(restored, require_release=True)

    def write_tar(self, entries):
        with tarfile.open(self.archive, 'w', format=tarfile.USTAR_FORMAT) as archive:
            for name, kind, data in entries:
                member = tarfile.TarInfo(name)
                member.type = kind
                member.linkname = 'target' if kind in {tarfile.SYMTYPE, tarfile.LNKTYPE} else ''
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))

    def test_unsafe_members_and_duplicate_paths_are_refused(self):
        for index, entries in enumerate([
            [('../escape', tarfile.REGTYPE, b'x')], [('/escape', tarfile.REGTYPE, b'x')],
            [('a', tarfile.SYMTYPE, b'')], [('a', tarfile.LNKTYPE, b'')],
            [('a', tarfile.DIRTYPE, b'')], [('a', tarfile.FIFOTYPE, b'')],
            [('a', tarfile.XHDTYPE, b'path=escape')], [('a', tarfile.GNUTYPE_LONGNAME, b'escape')],
            [('a', tarfile.REGTYPE, b'x'), ('a', tarfile.REGTYPE, b'y')],
            [('.git/config', tarfile.REGTYPE, b'x')],
            [('a', tarfile.REGTYPE, b'x'), ('a/b', tarfile.REGTYPE, b'y')],
        ]):
            with self.subTest(entries=entries):
                self.write_tar(entries)
                with self.assertRaises((ValueError, OSError)):
                    transport.unpack(self.archive, self.root / f'unsafe-{index}')
        self.assertFalse((self.root / 'escape').exists())

    def test_truncation_header_checksum_and_trailer_fail_closed(self):
        transport.pack(self.candidate, self.archive)
        original = self.archive.read_bytes()
        for index, data in enumerate([original[:512], original[:513], b'X' + original[1:],
                                      original[:-1], original + b'not archive padding']):
            with self.subTest(index=index):
                self.archive.write_bytes(data)
                with self.assertRaises((ValueError, OSError, tarfile.TarError)):
                    transport.unpack(self.archive, self.root / f'broken-{index}')

    def test_member_count_individual_total_and_archive_budgets(self):
        for index, limits in enumerate([{'MAX_FILES': 1}, {'MAX_FILE_BYTES': 1}, {'MAX_BYTES': 1}]):
            with self.subTest(limits=limits), contextlib.ExitStack() as stack:
                for key, value in limits.items():
                    stack.enter_context(patch.object(transport, key, value))
                with self.assertRaisesRegex(ValueError, 'budget'):
                    transport.pack(self.candidate, self.root / f'large-{index}.tar')
        self.write_tar([('a', tarfile.REGTYPE, b'ab'), ('b', tarfile.REGTYPE, b'cd')])
        for index, limits in enumerate([{'MAX_FILES': 1}, {'MAX_FILE_BYTES': 1}, {'MAX_BYTES': 3}, {'MAX_ARCHIVE_BYTES': 1}]):
            with self.subTest(limits=limits), contextlib.ExitStack() as stack:
                for key, value in limits.items():
                    stack.enter_context(patch.object(transport, key, value))
                with self.assertRaisesRegex(ValueError, 'budget|oversized'):
                    transport.unpack(self.archive, self.root / f'large-unpack-{index}')

    def test_fresh_destinations_links_and_cache_refusal(self):
        transport.pack(self.candidate, self.archive)
        with self.assertRaisesRegex(ValueError, 'outside'):
            transport.pack(self.candidate, self.candidate / 'recursive.tar')
        with self.assertRaisesRegex(ValueError, 'fresh'):
            transport.pack(self.candidate, self.archive)
        with self.assertRaisesRegex(ValueError, 'fresh'):
            transport.unpack(self.archive, self.candidate)
        for name, directory in [('linked-file', False), ('linked-dir', True)]:
            link = self.candidate / name
            link.symlink_to(self.candidate if directory else self.candidate / 'candidate.json', target_is_directory=directory)
            with self.assertRaisesRegex(ValueError, 'symlink|regular'):
                transport.pack(self.candidate, self.root / f'{name}.tar')
            link.unlink()
        cache = self.candidate / '.cache'
        cache.mkdir()
        with self.assertRaisesRegex(ValueError, 'cache'):
            transport.pack(self.candidate, self.root / 'cache.tar')


if __name__ == '__main__':
    unittest.main()
