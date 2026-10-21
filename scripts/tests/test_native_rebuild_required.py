# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import subprocess
import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_native_reproducibility import INPUTS
from native_rebuild_required import NATIVE_PATHS, rebuild_required


class NativeRebuildRequiredTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Native detector control')
        self.git('config', 'user.email', 'native-control@example.invalid')
        for name in NATIVE_PATHS:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('synthetic detector input\n')
        (self.root / 'unrelated.md').write_text('original\n')
        self.before = self.commit()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.PIPE,
                                       text=True).strip()

    def commit(self):
        self.git('add', '-A')
        self.git('commit', '-qm', 'synthetic detector control')
        return self.git('rev-parse', 'HEAD')

    def decision(self, before, after, event_name='pull_request'):
        event = ({'pull_request': {'base': {'sha': before}, 'head': {'sha': after}}}
                 if event_name == 'pull_request' else {'before': before, 'after': after})
        return rebuild_required(self.root, event_name, event)[0]

    def test_every_declared_builder_input_and_control_change_requires_rebuild(self):
        self.assertTrue(set(INPUTS) <= NATIVE_PATHS)
        for name in sorted(NATIVE_PATHS):
            with self.subTest(path=name):
                before = self.git('rev-parse', 'HEAD')
                (self.root / name).write_text('changed native detector input\n')
                self.assertTrue(self.decision(before, self.commit()))

    def test_unrelated_changes_and_unchanged_native_inputs_do_not_rebuild(self):
        (self.root / 'unrelated.md').write_text('documentation only\n')
        after = self.commit()
        self.assertFalse(self.decision(self.before, after))
        self.assertFalse(self.decision(self.before, after, 'push'))
        self.assertFalse(self.decision(after, after))

    def test_pr_merge_base_excludes_native_changes_only_on_newer_main(self):
        self.git('checkout', '-qb', 'unrelated-pr')
        (self.root / 'unrelated.md').write_text('PR documentation change\n')
        pr = self.commit()
        self.git('checkout', '-qb', 'main-advanced', self.before)
        native = sorted(NATIVE_PATHS)[0]
        (self.root / native).write_text('native input changed only on main\n')
        main = self.commit()
        self.assertFalse(self.decision(main, pr))
        self.assertTrue(self.decision(main, pr, 'push'))
        self.git('checkout', '-q', 'unrelated-pr')
        (self.root / native).write_text('native input changed by this PR\n')
        self.assertTrue(self.decision(main, self.commit()))

    def test_unrelated_histories_force_rebuild_when_merge_base_is_missing(self):
        self.git('checkout', '--orphan', 'independent-history')
        (self.root / 'unrelated.md').write_text('independent root tree\n')
        unrelated = self.commit()
        self.assertTrue(self.decision(self.before, unrelated))

    def test_native_deletion_and_both_rename_directions_are_detected(self):
        source = sorted(NATIVE_PATHS)[0]
        (self.root / source).rename(self.root / 'moved-away.txt')
        renamed = self.commit()
        self.assertTrue(self.decision(self.before, renamed))
        (self.root / 'moved-away.txt').rename(self.root / source)
        restored = self.commit()
        self.assertTrue(self.decision(renamed, restored, 'push'))
        (self.root / source).unlink()
        self.assertTrue(self.decision(restored, self.commit()))

    def test_manual_missing_invalid_and_unavailable_ranges_rebuild(self):
        self.assertTrue(rebuild_required(self.root, 'workflow_dispatch', {})[0])
        for event in ({}, [], {'pull_request': 'invalid'}):
            self.assertTrue(rebuild_required(self.root, 'pull_request', event)[0])
        for before in ('0' * 40, 'f' * 40, '--output=unsafe', None):
            self.assertTrue(self.decision(before, self.before))
        self.assertTrue(self.decision(self.before, '0' * 40, 'push'))
        self.assertTrue(rebuild_required(self.root, 'unknown', {})[0])

    def test_failed_git_diff_is_a_rebuild_not_an_empty_change_set(self):
        original = subprocess.run
        def fail_diff(command, **kwargs):
            if command[1] == 'diff':
                return subprocess.CompletedProcess(command, 128, b'', b'unavailable range')
            return original(command, **kwargs)
        with patch('native_rebuild_required.subprocess.run', side_effect=fail_diff):
            self.assertTrue(self.decision(self.before, self.before))

    def test_missing_event_writes_conservative_output_and_missing_output_fails(self):
        script = Path(__file__).resolve().parents[1] / 'native_rebuild_required.py'
        output = self.root / 'step-output'
        event = self.root / 'event.json'
        event.write_text(json.dumps({'pull_request': {}}))
        env = dict(os.environ, GITHUB_EVENT_NAME='pull_request', GITHUB_EVENT_PATH=str(event),
                   GITHUB_OUTPUT=str(output))
        result = subprocess.run([sys.executable, str(script)], env=env,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output.read_text(), 'rebuild=true\n')
        env.pop('GITHUB_OUTPUT')
        result = subprocess.run([sys.executable, str(script)], env=env,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('GITHUB_OUTPUT is required', result.stderr)
