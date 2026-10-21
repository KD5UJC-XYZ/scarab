# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_native_reproducibility import (compare_artifacts, compare_build_inputs, recipe_identity,
                                         sha256, validate_artifact, verify, verify_signatures)


class NativeReproducibilityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_changed_and_truncated_libraries_fail_without_replacing_reference(self):
        reference = self.root / 'committed.so'
        rebuilt = self.root / 'rebuilt.so'
        reference.write_bytes(b'original native bytes')
        for data in (b'original native bytex', b'original native', b''):
            with self.subTest(data=data):
                rebuilt.write_bytes(data)
                details = self.root / 'mismatch.json'
                with self.assertRaisesRegex(ValueError, 'native library bytes differ'):
                    compare_artifacts(reference, rebuilt, details)
                result = json.loads(details.read_text())
                self.assertEqual(result['referenceSha256'], sha256(reference))
                self.assertEqual(result['rebuiltBytes'], len(data))
                self.assertEqual(reference.read_bytes(), b'original native bytes')
        rebuilt.write_bytes(reference.read_bytes())
        compare_artifacts(reference, rebuilt, self.root / 'unused.json')
        self.assertFalse((self.root / 'unused.json').exists())

    def test_package_or_source_input_drift_fails_even_with_equal_library(self):
        before = {'sourceTree': 'tree', 'installedPackagesSha256': 'packages',
                  'downloadedPackagesSha256': 'debs', 'signedIndexes': {'jammy': 'signed'}}
        compare_build_inputs(before, dict(before))
        for field in before:
            with self.subTest(field=field):
                with self.assertRaisesRegex(ValueError, field):
                    compare_build_inputs(before, dict(before, **{field: 'changed'}))

    def test_missing_build_evidence_is_not_a_comparison_pass(self):
        with self.assertRaisesRegex(ValueError, 'missing or non-regular build evidence'):
            validate_artifact(self.root, {}, 'unused')

    def evidence(self):
        directory = self.root / 'build'
        directory.mkdir()
        for name in ('libblst.so', 'blst-source.tar', 'exports.txt', 'symbols.txt', 'elf.txt', 'disassembly.txt'):
            (directory / name).write_bytes(b'synthetic comparison-control input')
        (directory / 'blst-commit.txt').write_text('a' * 40)
        (directory / 'blst-tree.txt').write_text('b' * 40)
        (directory / 'packages.tsv').write_text('compiler\t1.0\tamd64\n')
        (directory / 'downloaded-debs.sha256').write_text('c' * 64 + '  /var/cache/apt/archives/compiler.deb\n')
        (directory / 'raw-dst.sha256').write_text('d' * 64 + '  /src/raw-dst.c\n')
        (directory / 'apt').mkdir()
        for suite in ('jammy', 'jammy-security', 'jammy-updates'):
            (directory / f'apt/{suite}_InRelease').write_text(f'Suite: {suite}\n')
        return directory, {'sourceCommit': 'a' * 40,
                           'sourceArchiveSha256': sha256(directory / 'blst-source.tar')}

    def test_changed_source_archive_or_adapter_fails(self):
        directory, identity = self.evidence()
        validate_artifact(directory, identity, 'd' * 64)
        with self.assertRaisesRegex(ValueError, 'source archive'):
            validate_artifact(directory, dict(identity, sourceArchiveSha256='e' * 64), 'd' * 64)
        with self.assertRaisesRegex(ValueError, 'raw-DST adapter'):
            validate_artifact(directory, identity, 'e' * 64)
        (directory / 'blst-commit.txt').write_text('e' * 40)
        with self.assertRaisesRegex(ValueError, 'source commit'):
            validate_artifact(directory, identity, 'd' * 64)

    def test_duplicate_pocket_cannot_replace_a_missing_signed_index(self):
        directory, identity = self.evidence()
        (directory / 'apt/jammy-updates_InRelease').write_text('Suite: jammy\n')
        with self.assertRaisesRegex(ValueError, 'each configured Ubuntu pocket once'):
            validate_artifact(directory, identity, 'd' * 64)

    def test_output_never_reuses_a_directory_or_lives_in_checkout(self):
        for output in (self.root, self.root / 'generated'):
            with self.subTest(output=output):
                with self.assertRaisesRegex(ValueError, 'fresh and outside'):
                    verify(self.root, output)

    def test_failure_keeps_a_machine_readable_receipt(self):
        output = self.root / 'failure'
        source = self.root / 'source'
        source.mkdir()
        with self.assertRaises(FileNotFoundError):
            verify(source, output)
        receipt = json.loads((output / 'receipt.json').read_text())
        self.assertEqual(receipt['status'], 'failed')
        self.assertEqual(receipt['builds'], [])
        self.assertIn('error', receipt)

    def test_unsigned_or_partially_verified_indexes_cannot_pass(self):
        evidence = self.root / 'apt'
        evidence.mkdir()
        for name in ('jammy', 'jammy-updates', 'jammy-security'):
            (evidence / f'{name}_InRelease').write_text('test index, not native evidence')
        log = self.root / 'signatures.log'
        def reported(command, destination):
            self.assertIn('--network', command)
            self.assertIn('none', command)
            self.assertIn('--platform', command)
            self.assertIn('linux/amd64', command)
            self.assertIn('gpgv --status-fd 1 --keyring /usr/share/keyrings/ubuntu-archive-keyring.gpg', command[-1])
            self.assertIn('|| exit 1', command[-1])
            destination.write_text('[GNUPG:] VALIDSIG abc\n' * 2)
        with patch('check_native_reproducibility.run_logged', side_effect=reported):
            with self.assertRaisesRegex(ValueError, 'three verified'):
                verify_signatures(self.root, 'ubuntu@sha256:test', log)

    def test_recipe_identifies_the_actual_pinned_inputs(self):
        root = Path(__file__).resolve().parents[2]
        identity = recipe_identity(root)
        self.assertEqual(identity['sourceCommit'], '6d960cd05d6fe2b5bc9ba161edf0c1a131b87c4c')
        self.assertEqual(identity['snapshot'], ['20261020T000000Z'])
        self.assertEqual(identity['flags'], '-D__ADX__ -D__BLST_PORTABLE__')
