# SPDX-License-Identifier: Apache-2.0
"""Independent fixture commitment verification, including optimized Python execution."""
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERIFIER = ROOT / 'scripts/oracle/scls/verify.py'
FIXTURE = ROOT / 'fixtures/scls/conway-4593/reference.scls'
SPEC = importlib.util.spec_from_file_location('scls_fixture_verifier', VERIFIER)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SclsFixtureTest(unittest.TestCase):
    def test_reference_commitments_and_all_entries(self):
        manifest, entries = MODULE.verify(FIXTURE)
        self.assertEqual(manifest['slot'], 4593)
        self.assertEqual(manifest['entries'], 16)
        self.assertEqual(manifest['chunks'], 8)
        self.assertEqual(len(entries), 16)
        self.assertEqual(len(manifest['namespaces']), 12)
        self.assertEqual(manifest['root'], 'fb12b75ed9b1abcbd341bd751b0c45606e060fd714e6bb97ce5dcb0a')

    def test_optimized_python_still_refuses_corruption_and_duplicate_manifest(self):
        raw = FIXTURE.read_bytes()
        corrupt = bytearray(raw)
        corrupt[100] ^= 1
        manifest_size = int.from_bytes(raw[-4:], 'big')
        duplicate = raw + raw[-manifest_size-4:]
        for label, content in [('chunk mutation', corrupt), ('duplicate manifest', duplicate),
                               ('truncated footer', raw[:-1]), ('trailing garbage', raw+b'\0')]:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / 'bad.scls'
                path.write_bytes(content)
                for optimized in [False, True]:
                    command = [sys.executable] + (['-O'] if optimized else []) + [str(VERIFIER), str(path)]
                    result = subprocess.run(command, capture_output=True, text=True, timeout=10)
                    self.assertNotEqual(result.returncode, 0, label)

    def test_verification_leaves_input_directory_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'reference.scls'
            path.write_bytes(FIXTURE.read_bytes())
            result = subprocess.run([sys.executable, '-O', str(VERIFIER), str(path)],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(list(Path(tmp).iterdir()), [path])
            self.assertEqual(path.read_bytes(), FIXTURE.read_bytes())
