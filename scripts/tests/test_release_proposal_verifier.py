# SPDX-License-Identifier: Apache-2.0
"""The proposal verifier checks a receipt against the reviewed authority file and refuses stray proposal state."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_fragments as fragments
import release_proposal as verifier

AUTHORITY = {"schemaVersion": 1, "kind": "bootstrap", "source": "1" * 40, "version": "0.1.0",
             "reason": "Unpublished test fixture"}
BRANCH = "release-please--branches--main"


def receipt_for(source, authority):
    return {"schemaVersion": 1, "state": "proposed", "repository": "KD5UJC-XYZ/scarab", "tool": "release-please-17.11.2",
            "source": source, "authority": authority, "version": "0.1.1", "channel": "prod"}


class Fixture:
    def __init__(self, scratch):
        self.repo = Path(scratch)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], stderr=subprocess.DEVNULL).decode().strip()

    def commit(self, files, message='fixture'):
        for name, content in files.items():
            target = self.repo / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content if isinstance(content, str) else json.dumps(content, indent=2, sort_keys=True) + '\n')
        self.git('add', '.')
        self.git('commit', '-qm', message)
        return self.git('rev-parse', 'HEAD')


class VerifierTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.fixture = Fixture(self.scratch.name)
        self.source = self.fixture.commit({'release-please-authority.json': AUTHORITY, 'README.md': 'base\n'})

    def proposal_cut(self, authority):
        receipt = receipt_for(self.source, authority)
        return self.fixture.commit({'.release/proposal.json': receipt, '.release/proposals/0.1.1.json': receipt})

    def test_matching_authority_passes_the_authority_check(self):
        verifier.require_authority(self.fixture.repo, self.source, receipt_for(self.source, dict(AUTHORITY)))

    def test_mismatched_authority_is_refused_even_when_the_receipt_is_self_consistent(self):
        for forged in (dict(AUTHORITY, version='0.9.0'), dict(AUTHORITY, source='2' * 40), dict(AUTHORITY, kind='published-stable'),
                       {k: v for k, v in AUTHORITY.items() if k != 'reason'}, dict(AUTHORITY, extra=True)):
            with self.assertRaisesRegex(ValueError, 'differs from the reviewed authority file'):
                verifier.require_authority(self.fixture.repo, self.source, receipt_for(self.source, forged))

    def test_missing_or_absent_receipt_authority_is_refused(self):
        with self.assertRaisesRegex(ValueError, 'no baseline authority'):
            verifier.require_authority(self.fixture.repo, self.source, receipt_for(self.source, None))
        with tempfile.TemporaryDirectory() as other:
            bare = Fixture(other)
            source = bare.commit({'README.md': 'no authority file\n'})
            with self.assertRaisesRegex(ValueError, 'authority file is missing'):
                verifier.require_authority(bare.repo, source, receipt_for(source, dict(AUTHORITY)))

    def test_load_proposal_refuses_a_forged_authority_before_anything_else_is_trusted(self):
        cut = self.proposal_cut(dict(AUTHORITY, version='0.9.0'))
        with self.assertRaisesRegex(ValueError, 'differs from the reviewed authority file'):
            verifier.load_proposal(self.fixture.repo, cut)

    def test_load_proposal_refuses_an_authority_file_edited_after_the_source(self):
        receipt = receipt_for(self.source, dict(AUTHORITY))
        cut = self.fixture.commit({'release-please-authority.json': dict(AUTHORITY, version='0.9.0'),
                                   '.release/proposal.json': receipt, '.release/proposals/0.1.1.json': receipt})
        with self.assertRaisesRegex(ValueError, 'authority file changed'):
            verifier.load_proposal(self.fixture.repo, cut)

    def test_proposal_branch_is_fully_verified_not_skipped(self):
        cut = self.proposal_cut(dict(AUTHORITY, version='0.9.0'))
        with self.assertRaisesRegex(ValueError, 'differs from the reviewed authority file'):
            verifier.check_branch(self.fixture.repo, BRANCH, self.source, cut, True)

    def test_proposal_branch_without_a_receipt_is_refused(self):
        cut = self.fixture.commit({'README.md': 'changed\n'})
        with self.assertRaises(ValueError):
            verifier.check_branch(self.fixture.repo, BRANCH, self.source, cut, True)

    def test_proposal_branch_name_from_a_fork_is_refused(self):
        cut = self.fixture.commit({'README.md': 'changed\n'})
        with self.assertRaisesRegex(ValueError, 'must be in this repository'):
            verifier.check_branch(self.fixture.repo, BRANCH, self.source, cut, False)

    def test_other_branch_carrying_proposal_state_is_refused(self):
        for files in ({'.release/proposal.json': {'x': 1}}, {'.release/proposals/0.1.1.json': {'x': 1}}):
            self.fixture.git('checkout', '-q', '-B', 'work', self.source)
            cut = self.fixture.commit(files)
            with self.assertRaisesRegex(ValueError, 'proposal receipts changed'):
                verifier.check_branch(self.fixture.repo, 'feat/anything', self.source, cut, True)

    def test_other_branch_without_proposal_changes_passes_unverified(self):
        self.fixture.git('checkout', '-q', '-B', 'work', self.source)
        cut = self.fixture.commit({'README.md': 'changed\n'})
        self.assertFalse(verifier.check_branch(self.fixture.repo, 'feat/anything', self.source, cut, True))

    def test_command_line_applies_the_branch_rule(self):
        self.fixture.git('checkout', '-q', '-B', 'work', self.source)
        cut = self.fixture.commit({'.release/proposal.json': {'x': 1}})
        script = Path(verifier.__file__)
        base = [sys.executable, str(script), '--repo', str(self.fixture.repo), '--cut', cut]
        self.assertNotEqual(subprocess.run(base + ['--branch', 'feat/anything', '--base', self.source, '--same-repo'],
                                           capture_output=True).returncode, 0)
        self.assertNotEqual(subprocess.run(base + ['--branch', 'feat/anything'], capture_output=True).returncode, 0)


class PromotionTests(unittest.TestCase):
    """A real generated alpha proposal merged into dev, then promoted; nothing here stubs the verifier."""
    VERSION = '0.2.0-alpha.0'
    NOTES = f'.release/notes/{VERSION}.md'
    RECEIPT = f'.release/proposals/{VERSION}.json'

    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.fixture = fx = Fixture(scratch.name)
        self.repo = fx.repo
        config = '{"release-type": "simple"}\n'
        base = fx.commit({'CHANGELOG.md': '# Changelog\n\n## [Unreleased]\n\nFragments supply new entries.\n\n## [0.1.0] - 2026-10-11\n\n### Added\n\n- Earlier.\n',
                          'changes/README.md': 'Keep.\n', 'release-please-config.json': config,
                          '.release-please-manifest.json': {'.': '0.1.0'}})
        authority = dict(AUTHORITY, source=base)
        self.source = fx.commit({'release-please-authority.json': authority, 'changes/7-a.md': '### Added (#7)\n\n- Entry.\n'})
        plan, notes, _ = fragments.build(self.repo, self.source, base, self.VERSION, '2026-10-21')
        manifest = fragments.json_bytes({'.': self.VERSION})
        receipt = {'schemaVersion': 1, 'state': 'proposed', 'repository': 'KD5UJC-XYZ/scarab', 'tool': 'release-please-17.11.2',
                   'source': self.source, 'authority': authority, 'version': self.VERSION, 'channel': 'alpha', 'date': '2026-10-21',
                   'fragmentPlan': plan, 'fragmentPlanSha256': fragments.digest(fragments.json_bytes(plan)),
                   'notesSha256': fragments.digest(notes), 'notesPath': self.NOTES,
                   'configSha256': fragments.digest(config.encode()),
                   'generatedFiles': {self.NOTES: fragments.identity(notes), '.release-please-manifest.json': fragments.identity(manifest)}}
        self.main = self.source
        fx.git('checkout', '-q', '-B', 'dev', self.source)
        self.proposal = fx.commit({self.NOTES: notes.decode(), '.release-please-manifest.json': manifest.decode(),
                                   '.release/proposal.json': receipt, self.RECEIPT: receipt}, 'proposal')
        self.receipt = receipt

    def check(self, branch, cut, target, same_repo=True):
        return verifier.check_branch(self.repo, branch, self.main, cut, same_repo, target)

    def test_the_fixture_is_a_valid_proposal(self):
        self.assertEqual(verifier.load_proposal(self.repo, self.proposal)['receipt']['version'], self.VERSION)

    def test_valid_dev_to_main_promotion_of_a_merged_proposal_passes(self):
        later = self.fixture.commit({'docs/later.md': 'unrelated dev work after the proposal merged\n'})
        self.assertTrue(self.check('dev', later, 'main'))

    def test_promotion_keeps_passing_through_a_merge_commit_of_the_proposal(self):
        self.fixture.git('checkout', '-q', '-B', 'integrate', self.main)
        self.fixture.git('merge', '-q', '--no-ff', '-m', 'merge proposal', self.proposal)
        self.assertTrue(self.check('dev', self.fixture.git('rev-parse', 'HEAD'), 'main'))

    def test_main_to_release_prod_propagation_is_accepted_but_not_from_dev(self):
        self.assertTrue(self.check('main', self.proposal, 'release/prod'))
        with self.assertRaisesRegex(ValueError, 'neither named'):
            self.check('dev', self.proposal, 'release/prod')

    def test_edited_receipt_in_a_promotion_is_refused(self):
        edited = dict(self.receipt, date='2026-10-22')
        for files in ({self.RECEIPT: edited}, {'.release/proposal.json': edited},
                      {'.release/proposal.json': edited, self.RECEIPT: edited}):
            self.fixture.git('checkout', '-q', '-B', 'dev', self.proposal)
            cut = self.fixture.commit(files, 'edit receipt')
            with self.subTest(files=sorted(files)), self.assertRaises(ValueError):
                self.check('dev', cut, 'main')

    def test_receipt_that_never_merged_as_a_verified_proposal_is_refused(self):
        self.fixture.git('checkout', '-q', '-B', 'dev', self.main)
        cut = self.fixture.commit({'.release/proposal.json': self.receipt, self.RECEIPT: self.receipt})
        with self.assertRaises(ValueError):
            self.check('dev', cut, 'main')

    def test_promotion_carrying_another_receipt_file_is_refused(self):
        cut = self.fixture.commit({'.release/proposals/9.9.9.json': {'x': 1}})
        with self.assertRaisesRegex(ValueError, 'other than the one merged proposal'):
            self.check('dev', cut, 'main')

    def test_deleted_receipt_is_refused(self):
        self.fixture.git('rm', '-q', self.RECEIPT)
        self.fixture.git('commit', '-qm', 'drop')
        with self.assertRaises(ValueError):
            self.check('dev', self.fixture.git('rev-parse', 'HEAD'), 'main')

    def test_feature_branch_or_fork_dev_with_the_same_valid_receipts_is_refused(self):
        with self.assertRaisesRegex(ValueError, 'neither named'):
            self.check('feat/stray', self.proposal, 'main')
        with self.assertRaisesRegex(ValueError, 'neither named'):
            self.check('dev', self.proposal, 'main', same_repo=False)
        with self.assertRaisesRegex(ValueError, 'neither named'):
            self.check('dev', self.proposal, 'dev')
        with self.assertRaisesRegex(ValueError, 'neither named'):
            verifier.check_branch(self.repo, 'dev', self.main, self.proposal, True)

    def test_command_line_accepts_the_promotion_only_with_a_target(self):
        base = [sys.executable, str(Path(verifier.__file__)), '--repo', str(self.repo), '--cut', self.proposal,
                '--branch', 'dev', '--base', self.main, '--same-repo']
        self.assertEqual(subprocess.run(base + ['--target', 'main'], capture_output=True).returncode, 0)
        self.assertNotEqual(subprocess.run(base, capture_output=True).returncode, 0)


if __name__ == '__main__':
    unittest.main()
