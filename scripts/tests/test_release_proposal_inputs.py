# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_proposal_authority as authority
import release_proposal_fragments as bridge
from publication_common import PublicationError, digest


class ProposalInputsTests(unittest.TestCase):
    def test_original_receipt_bytes_are_pinned_before_authentication(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            relative = '.release/baselines/0.2.0/verification.json'
            target = root / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(b'{"original":"receipt bytes"}\n')
            selected = {'kind': 'published-stable', 'version': '0.2.0', 'source': '1' * 40,
                        'verification': {'receiptPath': relative, 'receiptSha256': digest(target.read_bytes())}}
            with patch.object(authority, 'authenticate', return_value={'verified': 'test seam only'}) as authenticate:
                authority.verify(root, selected, Path('/pinned-tools/gh'))
                authenticate.assert_called_once_with(target, authority.REPOSITORY, '1' * 40, '0.2.0', Path('/pinned-tools/gh'))
                target.write_bytes(b'altered')
                with self.assertRaises(PublicationError):
                    authority.verify(root, selected, Path('/pinned-tools/gh'))
                self.assertEqual(authenticate.call_count, 1)

    def test_unrelated_receipt_path_and_prerelease_baseline_refuse_without_authentication(self):
        selected = {'kind': 'published-stable', 'version': '0.2.0', 'source': '1' * 40,
                    'verification': {'receiptPath': 'elsewhere.json', 'receiptSha256': '2' * 64}}
        with patch.object(authority, 'authenticate') as authenticate:
            with self.assertRaises(PublicationError):
                authority.verify(Path('/unused'), selected, Path('/pinned-tools/gh'))
            with self.assertRaises(PublicationError):
                authority.verify(Path('/unused'), dict(selected, version='0.2.0-rc.1'), Path('/pinned-tools/gh'))
            authenticate.assert_not_called()

    def test_sparse_materialization_uses_real_checked_apply_and_leaves_source_checkout_unchanged(self):
        with tempfile.TemporaryDirectory() as scratch:
            repo = Path(scratch) / 'repo'
            repo.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL).decode().strip()
            git('init', '-q')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            (repo / 'changes').mkdir()
            (repo / 'changes/README.md').write_text('Instructions.\n')
            (repo / 'CHANGELOG.md').write_text('# Changelog\n\n## [Unreleased]\n\nFragments.\n\n## [0.1.0] - 2026-10-01\n\n### Added\n\n- Old.\n')
            git('add', '.')
            git('commit', '-qm', 'baseline')
            baseline = git('rev-parse', 'HEAD')
            (repo / 'changes/1-fix.md').write_text('### Fixed\n\n- Exact original wording.\n')
            git('add', '.')
            git('commit', '-qm', 'fix: repaired')
            source = git('rev-parse', 'HEAD')
            original = (repo / 'CHANGELOG.md').read_bytes()
            stable = bridge.materialize(repo, source, baseline, '0.1.1', '2026-10-21')
            self.assertIsNone(stable['files']['changes/1-fix.md']['content'])
            self.assertIn('Exact original wording.', stable['files']['CHANGELOG.md']['content'])
            self.assertEqual(json.loads(stable['files']['.release/changelog/0.1.1.json']['content'])['state'], 'pending-publication')
            for suffix in ('alpha', 'beta', 'rc'):
                preview = bridge.materialize(repo, source, baseline, f'0.1.1-{suffix}.0', '2026-10-21')
                self.assertEqual(set(preview['files']), {f'.release/notes/0.1.1-{suffix}.0.md'})
                self.assertIn('Exact original wording.', preview['files'][preview['notesPath']]['content'])
            self.assertEqual((repo / 'CHANGELOG.md').read_bytes(), original)
            self.assertTrue((repo / 'changes/1-fix.md').exists())
            self.assertEqual(git('status', '--porcelain'), '')
