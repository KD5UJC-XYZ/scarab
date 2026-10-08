#!/usr/bin/env python3
"""Dependency-free harness tests. Native tool behavior is mocked, never downloaded."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('reference_cli', Path(__file__).with_name('reference-cli-checks.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class HarnessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cli = self.root / 'cardano-cli'
        self.cli.write_text('not a real executable')
        self.cli.chmod(0o700)
        self.out = self.root / 'evidence'
        self.fixtures = m.ROOT / 'fixtures'
        self.original_digest = m.digest
        self.addCleanup(patch.stopall)
        patch.object(m.platform, 'system', return_value='Linux').start()
        patch.object(m.platform, 'machine', return_value='x86_64').start()
        patch.object(m, 'digest', side_effect=lambda p: m.BINARY_SHA256 if p == self.cli else self.original_digest(p)).start()
        self.calls = []

    def tool(self, cli, args, cwd):
        self.calls.append(args)
        if args == ['--version']:
            return subprocess.CompletedProcess(args, 0, m.VERSION, '')
        manifest = json.loads((self.fixtures / 'extraction-manifest.json').read_text())
        rec = next(r for r in manifest['records'] if f"{r['case_index']:03d}" in args[args.index('--tx-file') + 1])
        text = rec['expected_txid'] + '\n' if args[0] == 'conway' else '{"era":"Conway","fee":"4 Lovelace"}'
        return subprocess.CompletedProcess(args, 0, text, '')

    def run_checks(self, tool=None):
        with patch.object(m, 'invoke', side_effect=tool or self.tool):
            return m.checks(self.cli, self.fixtures, self.out)

    def test_success_exact_bytes_and_six_results(self):
        result = self.run_checks()
        self.assertEqual(result['status'], 'passed')
        self.assertEqual(len(result['results']), 6)
        self.assertEqual(len(self.calls), 7)
        for entry in result['results']:
            envelope = json.loads((self.out / entry['text_envelope_path']).read_text())
            self.assertEqual(envelope['type'], 'Tx ConwayEra')
            self.assertEqual(bytes.fromhex(envelope['cborHex']), (self.fixtures / entry['fixture']).read_bytes())

    def expect_tool_failure(self, replacement):
        def tool(cli, args, cwd):
            return self.tool(cli, args, cwd) if args == ['--version'] else replacement(args)
        with self.assertRaises(m.CheckError):
            self.run_checks(tool)
        report = json.loads((self.out / 'results.json').read_text())
        self.assertEqual(report['status'], 'harness_error')
        self.assertNotIn('ledger_rejected', json.dumps(report))
        self.assertNotEqual(report['results'][-1]['status'], 'passed')

    def test_nonzero_exit_not_ledger_rejection(self):
        self.expect_tool_failure(lambda a: subprocess.CompletedProcess(a, 1, '', 'failure'))

    def test_stderr_is_failure(self):
        self.expect_tool_failure(lambda a: subprocess.CompletedProcess(a, 0, '', 'warning'))

    def test_wrong_hash_is_failure(self):
        self.expect_tool_failure(lambda a: subprocess.CompletedProcess(a, 0, '0' * 64, ''))

    def test_execution_error_not_ledger_rejection(self):
        def fail(args):
            raise m.CheckError('tool execution failed: permission denied')
        self.expect_tool_failure(fail)

    def test_bad_views(self):
        for bad in ('not-json', 'null', '[]', '{"era":"Babbage"}'):
            with self.subTest(view=bad):
                self.out = self.root / ('case' + str(len(list(self.root.iterdir()))))
                def tool(cli, args, cwd):
                    if args[0] == 'debug':
                        return subprocess.CompletedProcess(args, 0, bad, '')
                    return self.tool(cli, args, cwd)
                with self.assertRaises(m.CheckError):
                    self.run_checks(tool)
                self.assertEqual(json.loads((self.out / 'results.json').read_text())['status'], 'harness_error')

    def test_bad_version_stops_before_transactions(self):
        with self.assertRaises(m.CheckError):
            self.run_checks(lambda c, a, d: subprocess.CompletedProcess(a, 0, 'wrong version', ''))
        self.assertEqual(json.loads((self.out / 'results.json').read_text())['results'], [])

    def test_bad_binary_never_executes(self):
        with patch.object(m, 'digest', return_value='bad'), patch.object(m, 'invoke') as invoke:
            with self.assertRaises(m.CheckError):
                m.checks(self.cli, self.fixtures, self.out)
            invoke.assert_not_called()

    def test_bad_fixture_and_manifest_never_execute(self):
        fixtures = self.root / 'fixtures'
        shutil.copytree(self.fixtures / 'raw', fixtures / 'raw')
        shutil.copy(self.fixtures / 'extraction-manifest.json', fixtures)
        self.fixtures = fixtures
        for file in (next((fixtures / 'raw').glob('*-tx.cbor')), fixtures / 'extraction-manifest.json'):
            original = file.read_bytes()
            file.write_bytes(original + b'X')
            with patch.object(m, 'invoke') as invoke:
                with self.assertRaises(m.CheckError):
                    m.checks(self.cli, fixtures, self.out)
                invoke.assert_not_called()
            file.write_bytes(original)

    def test_same_size_fixture_hash_corruption(self):
        fixtures = self.root / 'fixtures'
        shutil.copytree(self.fixtures / 'raw', fixtures / 'raw')
        shutil.copy(self.fixtures / 'extraction-manifest.json', fixtures)
        file = next((fixtures / 'raw').glob('*-tx.cbor'))
        raw = file.read_bytes()
        file.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
        with patch.object(m, 'invoke') as invoke:
            with self.assertRaisesRegex(m.CheckError, 'fixture checksum mismatch'):
                m.checks(self.cli, fixtures, self.out)
            invoke.assert_not_called()

    def test_existing_output_is_not_overwritten(self):
        self.out.mkdir()
        with self.assertRaises(FileExistsError):
            self.run_checks()
        self.assertEqual(self.calls, [])

    def test_unsupported_platform(self):
        with patch.object(m.platform, 'machine', return_value='arm64'):
            with self.assertRaises(m.CheckError):
                self.run_checks()

    def test_invocation_bounded_minimal_environment_and_no_socket_argument(self):
        with patch.object(m.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '', '')) as run:
            m.invoke(self.cli, ['--version'], self.root)
        kwargs = run.call_args.kwargs
        self.assertEqual(kwargs['timeout'], 20)
        self.assertEqual(kwargs['env'], m.ENV)
        self.assertNotIn('CARDANO_NODE_SOCKET_PATH', kwargs['env'])
        self.assertEqual(kwargs['stdin'], subprocess.DEVNULL)
        self.assertTrue(kwargs['close_fds'])

    def test_timeout_oserror_and_encoding_error_are_harness_errors(self):
        for error in (subprocess.TimeoutExpired('cli', 20), PermissionError('denied'),
                      UnicodeDecodeError('utf8', b'\xff', 0, 1, 'invalid')):
            with self.subTest(error=type(error).__name__), patch.object(m.subprocess, 'run', side_effect=error):
                with self.assertRaises(m.CheckError):
                    m.invoke(self.cli, ['--version'], self.root)

if __name__ == '__main__':
    unittest.main(verbosity=2)
