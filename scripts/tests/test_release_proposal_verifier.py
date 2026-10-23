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
            with self.assertRaises(ValueError):
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
    """Real generated alpha/beta proposals merged into dev, then carried by other heads; nothing stubs the verifier."""
    CONFIG = '{"release-type": "simple"}\n'

    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.fixture = fx = Fixture(scratch.name)
        self.repo = fx.repo
        self.base0 = fx.commit({'CHANGELOG.md': '# Changelog\n\n## [Unreleased]\n\nFragments supply new entries.\n\n## [0.1.0] - 2026-10-11\n\n### Added\n\n- Earlier.\n',
                                'changes/README.md': 'Keep.\n', 'release-please-config.json': self.CONFIG,
                                '.release-please-manifest.json': {'.': '0.1.0'}})
        self.authority = dict(AUTHORITY, source=self.base0)
        self.main = fx.commit({'release-please-authority.json': self.authority, 'changes/7-a.md': '### Added (#7)\n\n- Entry.\n'})
        fx.git('checkout', '-q', '-B', 'dev', self.main)
        self.alpha, self.alpha_receipt = self.propose('0.2.0-alpha.0', 'alpha', self.main)

    def propose(self, version, channel, source, extra=None):
        """Commit a generated proposal for `source` on the current branch."""
        fx = self.fixture
        notes_path = f'.release/notes/{version}.md'
        plan, notes, _ = fragments.build(self.repo, source, self.base0, version, '2026-10-21')
        manifest = fragments.json_bytes({'.': version})
        history = fx.git('rev-list', '--first-parent', f'{self.base0}..{source}').split()
        receipt = {'schemaVersion': 1, 'state': 'proposed', 'repository': 'KD5UJC-XYZ/scarab', 'tool': 'release-please-17.11.2',
                   'target': 'main', 'source': source, 'authority': self.authority, 'version': version, 'channel': channel,
                   'date': '2026-10-21', 'maintenance': False, 'nextVersion': None, 'overrideReason': None,
                   'configSha256': fragments.digest(self.CONFIG.encode()),
                   'commits': [{'sha': sha, 'messageSha256': fragments.digest(fx.git('show', '-s', '--format=%B', sha).rstrip().encode())}
                               for sha in history],
                   'fragmentPlan': plan, 'fragmentPlanSha256': fragments.digest(fragments.json_bytes(plan)),
                   'notesPath': notes_path, 'notesSha256': fragments.digest(notes),
                   'generatedFiles': {notes_path: fragments.identity(notes), '.release-please-manifest.json': fragments.identity(manifest)},
                   'pullRequestTitle': f'chore: release {version}', 'pullRequestBodySha256': '0' * 64}
        cut = fx.commit({notes_path: notes.decode(), '.release-please-manifest.json': manifest.decode(),
                         '.release/proposal.json': receipt, f'.release/proposals/{version}.json': receipt, **(extra or {})}, 'proposal ' + version)
        return cut, receipt

    def check(self, branch, cut, base=None, same_repo=True):
        return verifier.check_branch(self.repo, branch, base or self.main, cut, same_repo)

    def edit_on(self, parent, receipt, **changes):
        self.fixture.git('checkout', '-q', '-B', 'edit', parent)
        edited = dict(receipt, **changes)
        version = receipt['version']
        return self.fixture.commit({'.release/proposal.json': edited, f'.release/proposals/{version}.json': edited}, 'edit')

    def test_the_fixture_is_a_valid_proposal(self):
        self.assertEqual(verifier.load_proposal(self.repo, self.alpha)['receipt']['version'], '0.2.0-alpha.0')

    def test_valid_dev_to_main_promotion_after_later_dev_work_passes(self):
        later = self.fixture.commit({'docs/later.md': 'unrelated dev work after the proposal merged\n'})
        self.assertTrue(self.check('dev', later))

    def test_normal_merge_commit_with_later_dev_work_can_be_propagated_to_a_release_branch(self):
        later = self.fixture.commit({'docs/later.md': 'unrelated dev work after the proposal merged\n'})
        self.fixture.git('checkout', '-q', '-B', 'main', self.main)
        self.fixture.git('merge', '-q', '--no-ff', '-m', 'promote dev', later)
        merged = self.fixture.git('rev-parse', 'HEAD')
        self.assertTrue(self.check('main', merged))
        with self.assertRaisesRegex(ValueError, 'selected cut contains changes outside'):
            verifier.load_proposal(self.repo, merged)

    def test_two_proposals_travel_in_one_promotion(self):
        source = self.fixture.commit({'changes/8-b.md': '### Fixed (#8)\n\n- More.\n'})
        beta, _ = self.propose('0.2.0-beta.0', 'beta', source)
        self.assertTrue(self.check('dev', beta))
        stale = self.fixture.commit({'.release/proposal.json': self.alpha_receipt})
        with self.assertRaises(ValueError):
            self.check('dev', stale)

    def test_edited_receipt_with_unchecked_fields_is_refused(self):
        for changes in ({'target': 'unapproved-target', 'commits': []}, {'commits': []}, {'target': 'elsewhere'},
                        {'maintenance': 'yes'}, {'nextVersion': '9.9.9'}, {'extra': 1}, {'pullRequestBodySha256': 'x'}):
            cut = self.edit_on(self.alpha, self.alpha_receipt, **changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.check('dev', cut)

    def test_edited_receipt_committed_directly_with_a_valid_looking_cut_is_refused(self):
        self.fixture.git('checkout', '-q', '-B', 'forged', self.main)
        edited = dict(self.alpha_receipt, target='unapproved-target', commits=[])
        files = {'.release/proposal.json': edited, '.release/proposals/0.2.0-alpha.0.json': edited}
        for path in ('.release/notes/0.2.0-alpha.0.md', '.release-please-manifest.json'):
            files[path] = self.fixture.git('show', f'{self.alpha}:{path}') + '\n'
        cut = self.fixture.commit(files)
        with self.assertRaises(ValueError):
            self.check('dev', cut)

    def test_edit_of_an_already_merged_versioned_receipt_is_refused(self):
        cut = self.edit_on(self.alpha, self.alpha_receipt, date='2026-10-22')
        with self.assertRaisesRegex(ValueError, 'already-merged versioned receipt'):
            self.check('dev', cut, base=self.alpha)

    def test_receipt_that_was_never_a_verified_proposal_is_refused(self):
        self.fixture.git('checkout', '-q', '-B', 'dev', self.main)
        cut = self.fixture.commit({'.release/proposal.json': self.alpha_receipt, '.release/proposals/0.2.0-alpha.0.json': self.alpha_receipt})
        with self.assertRaisesRegex(ValueError, 'matches no verified merged proposal'):
            self.check('dev', cut)

    def test_proposal_json_must_equal_its_versioned_receipt_and_deleted_receipts_are_refused(self):
        cut = self.fixture.commit({'.release/proposal.json': {'x': 1}})
        with self.assertRaises(ValueError):
            self.check('dev', cut)
        self.fixture.git('checkout', '-q', '-B', 'dev', self.alpha)
        self.fixture.git('rm', '-q', '.release/proposals/0.2.0-alpha.0.json')
        self.fixture.git('commit', '-qm', 'drop')
        with self.assertRaises(ValueError):
            self.check('dev', self.fixture.git('rev-parse', 'HEAD'))

    def test_any_same_repository_head_may_carry_verified_receipts_but_not_a_fork(self):
        self.assertTrue(self.check('feat/reconcile', self.alpha))
        self.assertTrue(self.check('main', self.alpha))
        with self.assertRaisesRegex(ValueError, 'fork'):
            self.check('dev', self.alpha, same_repo=False)

    def test_junk_receipt_restored_by_a_valid_proposal_commit_is_refused(self):
        junk = '.release/proposals/9.9.9.json'
        self.fixture.git('checkout', '-q', '-B', 'junk', self.main)
        source = self.fixture.commit({junk: {'x': 1}})
        self.fixture.git('rm', '-q', junk)
        self.fixture.git('commit', '-qm', 'drop junk')
        cut, _ = self.propose('0.2.0-alpha.0', 'alpha', source, extra={junk: {'x': 1}})
        with self.assertRaises(ValueError):
            self.check('dev', cut)

    def test_resetting_proposal_json_to_an_older_retained_receipt_is_refused(self):
        source = self.fixture.commit({'changes/8-b.md': '### Fixed (#8)\n\n- More.\n'})
        beta, _ = self.propose('0.2.0-beta.0', 'beta', source)
        self.assertTrue(self.check('dev', beta, base=self.alpha))
        cut = self.fixture.commit({'.release/proposal.json': self.alpha_receipt})
        with self.assertRaisesRegex(ValueError, 'not the highest'):
            self.check('dev', cut, base=beta)

    def test_moving_a_receipt_out_of_the_protected_paths_is_seen(self):
        self.fixture.git('checkout', '-q', '-B', 'moved', self.alpha)
        (self.repo / 'docs').mkdir()
        self.fixture.git('mv', '.release/proposals/0.2.0-alpha.0.json', 'docs/old-receipt.json')
        self.fixture.git('commit', '-qm', 'move')
        with self.assertRaises(ValueError):
            self.check('dev', self.fixture.git('rev-parse', 'HEAD'), base=self.alpha)

    def test_command_line_checks_carried_receipts(self):
        base = [sys.executable, str(Path(verifier.__file__)), '--repo', str(self.repo), '--cut', self.alpha,
                '--branch', 'dev', '--base', self.main]
        self.assertEqual(subprocess.run(base + ['--same-repo'], capture_output=True).returncode, 0)
        self.assertNotEqual(subprocess.run(base, capture_output=True).returncode, 0)


if __name__ == '__main__':
    unittest.main()
