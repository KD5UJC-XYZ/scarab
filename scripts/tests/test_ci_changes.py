# SPDX-License-Identifier: Apache-2.0
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ci_changes
import check_ci_parity

ROOT = Path(__file__).resolve().parents[2]


class ChangesTest(unittest.TestCase):
    def test_narrow_docs_allowlist_and_unknown_paths(self):
        self.assertTrue(ci_changes.docs_only(['AGENTS.md', 'docs/a.md', 'changes/123.md']))
        for path in ('build.sbt', 'scripts/a.py', '.github/workflows/ci.yml', 'docs/data/a.json',
                     'docs/a.scala', 'fixtures/a.md', 'new.md', '../docs/a.md', '/docs/a.md'):
            with self.subTest(path=path):
                self.assertFalse(ci_changes.docs_only(['README.md', path]))
        self.assertFalse(ci_changes.docs_only([]))

    def test_non_pr_runs_and_unreadable_diffs_run_checks(self):
        def missing(*args, **kwargs):
            return subprocess.CompletedProcess(args, 128, b'', b'missing')
        for name in ('schedule', 'workflow_dispatch', 'unknown'):
            self.assertTrue(ci_changes.required(name, {}))
        self.assertTrue(ci_changes.required('push', {'before': '0' * 40}))
        self.assertTrue(ci_changes.required('pull_request', {}, missing))

    def test_conservative_output_parsing(self):
        for output in (b'', b'docs/a.md', b'\xff\0', b'docs/a.md\0code.scala\0'):
            run = lambda *a, **k: subprocess.CompletedProcess(a, 0, output, b'')
            self.assertTrue(ci_changes.required('pull_request', {}, run))

    def test_real_git_renames_deletions_and_multicommit_push(self):
        with tempfile.TemporaryDirectory() as tmp:
            def git(*args):
                return subprocess.check_output(['git', '-C', tmp, *args]).decode().strip()
            def commit():
                git('add', '-A')
                git('-c', 'user.name=Test', '-c', 'user.email=test@example.com', 'commit', '-qm', 'test')
                return git('rev-parse', 'HEAD')
            def run(command, **kwargs):
                return subprocess.run(command, cwd=tmp, **kwargs)
            p = Path(tmp)
            git('init', '-q')
            (p/'source.scala').write_text('source')
            (p/'README.md').write_text('docs')
            first = commit()
            (p/'source.scala').write_text('changed source')
            commit()
            (p/'README.md').write_text('changed docs')
            commit()
            self.assertFalse(ci_changes.required('pull_request', {}, run))
            self.assertTrue(ci_changes.required('push', {'before': first}, run))
            (p/'source.scala').rename(p/'AGENTS.md')
            commit()
            self.assertTrue(ci_changes.required('pull_request', {}, run))
            (p/'AGENTS.md').unlink()
            commit()
            self.assertFalse(ci_changes.required('pull_request', {}, run))
            (p/'unknown').write_text('x')
            commit()
            (p/'unknown').unlink()
            commit()
            self.assertTrue(ci_changes.required('pull_request', {}, run))

    def test_bad_event_falls_back_to_full_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            event, output = Path(tmp)/'event', Path(tmp)/'output'
            event.write_text('invalid')
            with patch.dict(os.environ, GITHUB_EVENT_PATH=str(event), GITHUB_OUTPUT=str(output)):
                ci_changes.main()
            self.assertEqual(output.read_text(), 'run=true\n')

    def test_workflows_keep_named_jobs_and_gate_expensive_steps(self):
        for filename, ids in [('ci.yml', ('scala-build', 'scala-shards', 'scala-tests')),
                              ('cuddle-crosscheck.yml', ('crosscheck',))]:
            spec = check_ci_parity.read_yaml((ROOT/'.github/workflows'/filename).read_text())
            jobs = spec['jobs']
            for key in ids:
                job = jobs[key]
                self.assertIn('changes', job['needs'])
                first, *steps = job['steps']
                self.assertNotIn('if', first)
                self.assertIn('test "$CLASSIFY_RESULT" = success', first['run'])
                self.assertTrue(all("needs.changes.outputs.run == 'true'" in s['if'] for s in steps))
        ci = check_ci_parity.read_yaml((ROOT/'.github/workflows/ci.yml').read_text())
        self.assertEqual(ci['jobs']['scala']['name'], 'scalafmt, compile and test')
        for key in ('scripts', 'workflows', 'secrets'):
            self.assertNotIn('needs', ci['jobs'][key])


if __name__ == '__main__':
    unittest.main()
