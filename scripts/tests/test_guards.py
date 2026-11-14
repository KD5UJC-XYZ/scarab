# SPDX-License-Identifier: Apache-2.0
"""guards: the one registry of the Python guards, which preflight and CI both run."""
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import guards

ROOT = Path(__file__).resolve().parents[2]
# The Python guards that scripts/preflight and CI's scripts job each named in a step of their own before the
# registry: none of them may be lost.
FORMER = {
    ('scripts/check_test_coverage.py',), ('scripts/plutus_reference.py', 'check'),
    ('scripts/check_error_channels.py',), ('scripts/check_text_policy.py',), ('scripts/check_imperative.py',),
    ('scripts/check_test_sleeps.py',), ('scripts/check_local_paths.py',), ('scripts/check_notice.py',),
    ('scripts/render_compatibility.py', '--check'), ('scripts/render_roadmap.py', '--check'),
    ('scripts/render_performance.py',), ('scripts/check_flags_doc.py',), ('scripts/check_docs.py',),
    ('scripts/render_codemap.py', '--check'),
}


class RegistryTest(unittest.TestCase):
    def test_the_registry_is_valid_and_keeps_every_guard_run_before_it(self):
        registered, trouble = guards.load(ROOT)
        self.assertEqual(trouble, [])
        self.assertEqual(FORMER - {g.argv for g in registered}, set())
        for script in ('scripts/check_pinned_citations.py', 'scripts/check_parameter_comments.py'):
            self.assertIn(script, {g.argv[0] for g in registered})

    def test_preflight_and_ci_run_the_registry_and_name_no_registered_guard_of_their_own(self):
        registered, _ = guards.load(ROOT)
        for path in ('scripts/preflight', '.github/workflows/ci.yml'):
            text = (ROOT / path).read_text()
            with self.subTest(path=path):
                self.assertIn('scripts/guards.py', text)
                named = [g.argv[0] for g in registered if g.argv[0] in text]
                self.assertEqual(named, [], f'{path} runs a registered guard itself; register it only once')

    def test_a_malformed_registry_is_refused(self):
        entries = [
            {'id': 'a', 'title': 'one', 'argv': ['scripts/guards.py']},
            {'id': 'a', 'title': 'two', 'argv': ['scripts/guards.py']},
            {'id': 'Bad Id', 'title': 'three', 'argv': ['scripts/guards.py']},
            {'id': 'tab', 'title': 'a\ttitle', 'argv': ['scripts/guards.py']},
            {'id': 'missing', 'title': 'four', 'argv': ['scripts/no_such_guard.py']},
            {'id': 'outside', 'title': 'five', 'argv': ['README.md']},
        ]
        trouble = guards.problems(ROOT, entries)
        for expected in ('a: registered twice', "'Bad Id'", 'tab: a title', 'missing: the command',
                         'outside: the command'):
            self.assertTrue(any(expected in line for line in trouble), f'{expected!r} not in {trouble}')
        self.assertEqual(len(trouble), 5)


class RunTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        scripts = self.root / 'scripts'
        scripts.mkdir()
        (scripts / 'passes.py').write_text('')
        (scripts / 'fails.py').write_text('import sys\nsys.exit(3)\n')
        (scripts / 'guards.json').write_text(json.dumps({'guards': [
            {'id': 'first', 'title': 'the first', 'argv': ['scripts/passes.py']},
            {'id': 'second', 'title': 'the second', 'argv': ['scripts/fails.py', '--flag']},
            {'id': 'third', 'title': 'the third', 'argv': ['scripts/passes.py']},
        ]}))

    def main(self, *args, github=False):
        out, err = io.StringIO(), io.StringIO()
        environment = dict(os.environ, GITHUB_ACTIONS='true' if github else 'false')
        with redirect_stdout(out), redirect_stderr(err), mock.patch.dict(os.environ, environment):
            code = guards.main(list(args), self.root)
        return code, out.getvalue(), err.getvalue()

    def test_every_guard_runs_to_its_end_and_the_failed_ones_are_named(self):
        code, out, _ = self.main()
        self.assertEqual(code, 1)
        self.assertEqual(out.splitlines()[:6], ['== the first', '   ok', '== the second', '   FAILED: the second',
                                                '== the third', '   ok'])
        self.assertIn('guards FAILED: the second', out)

    def test_under_github_actions_each_guard_is_a_group(self):
        code, out, _ = self.main(github=True)
        self.assertEqual(code, 1)
        self.assertIn('::group::the first\n::endgroup::\n::group::the second\n::endgroup::\n'
                      '::error::the second failed\n::group::the third', out)

    def test_list_and_run_one(self):
        code, out, _ = self.main('--list')
        self.assertEqual((code, out), (0, 'first\tthe first\nsecond\tthe second\nthird\tthe third\n'))
        self.assertEqual(self.main('--run', 'first')[0], 0)
        self.assertEqual(self.main('--run', 'second')[0], 3, 'the guard runs with its arguments and its status')
        code, _, err = self.main('--run', 'fourth')
        self.assertEqual(code, 2)
        self.assertIn("no guard 'fourth'", err)

    def test_an_invalid_registry_runs_nothing(self):
        (self.root / 'scripts' / 'guards.json').write_text(json.dumps({'guards': [
            {'id': 'first', 'title': 'the first', 'argv': ['scripts/absent.py']}]}))
        for args in ((), ('--list',), ('--run', 'first')):
            with self.subTest(args=args):
                code, out, err = self.main(*args)
                self.assertEqual((code, out), (2, ''))
                self.assertIn('not a valid guard registry', err)


if __name__ == '__main__':
    unittest.main()
