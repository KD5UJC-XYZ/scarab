# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import project_health as health  # noqa: E402


def run(number, conclusion, created, finished):
    return {"id": number, "conclusion": conclusion, "created_at": created, "updated_at": finished,
            "html_url": f"https://github.com/example/node/actions/runs/{number}", "head_sha": "a" * 40}


class ProjectHealthTest(unittest.TestCase):
    def test_failures_have_recovery_links_without_treating_cancelled_as_passed(self):
        day = '2026-11-15T'
        runs = [run(1, 'failure', day+'10:00:00Z', day+'10:10:00Z'),
                run(2, 'cancelled', day+'10:12:00Z', day+'10:13:00Z'),
                run(3, 'success', day+'10:20:00Z', day+'10:35:00Z'),
                run(4, 'failure', day+'11:00:00Z', day+'11:10:00Z')]
        result = health.failures(runs, True)
        self.assertEqual(result['failed_push_runs'], 2)
        self.assertEqual(result['unknown_runs'], 1)
        self.assertEqual(result['intervals'][0]['recovery_minutes'], 25)
        self.assertIsNone(result['intervals'][1]['recovery_run'])

    def test_out_of_order_completion_is_unknown_not_negative_red_time(self):
        runs = [run(1, 'failure', '2026-11-15T10:00:00Z', '2026-11-15T11:00:00Z'),
                run(2, 'success', '2026-11-15T10:10:00Z', '2026-11-15T10:20:00Z')]
        self.assertIsNone(health.failures(runs, True)['intervals'][0]['recovery_minutes'])

    def test_skips_require_all_planned_suites_and_are_counted_from_junit(self):
        plan = {'shards': [{'suites': [['ledger', 'A'], ['node', 'B']]}]}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for module, suite, skip in [('ledger', 'A', 1), ('node', 'B', 0)]:
                path = root / '1' / module / f'TEST-{suite}.xml'
                path.parent.mkdir(parents=True, exist_ok=True)
                child = '<skipped message="dataset not configured"/>' if skip else ''
                path.write_text(f'<testsuite name="{suite}" tests="1" skipped="{skip}">'
                                f'<testcase name="case">{child}</testcase></testsuite>')
            result = health.skipped_reports(root, plan)
            self.assertEqual(result['skipped_testcases'], 1)
            self.assertEqual(result['planned_suites'], 2)
            (root/'1/node/TEST-B.xml').unlink()
            with self.assertRaises(health.EvidenceError):
                health.skipped_reports(root, plan)

    def test_missing_artifacts_are_unknown_and_not_zero_skips(self):
        run_data = run(1, 'success', '2026-11-15T10:00:00Z', '2026-11-15T10:10:00Z')
        with mock.patch.object(health, 'gh', side_effect=health.EvidenceError('artifact expired')):
            result = health.skips('example/node', [run_data])
        self.assertEqual(result['status'], 'unknown')
        self.assertNotIn('skipped_testcases', result)
        self.assertIn('artifact expired', result['reason'])

    def test_baselines_count_occurrences_and_definitions_not_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root/'docs/data'
            directory.mkdir(parents=True)
            examples = {'imperative': {'files': {'A.scala': {'try': 2, 'catch': 1}}},
                        'pinned-citation': {'files': {'B.scala': 4}},
                        'test-sleep': {'files': {'C.scala': {'sleep': 2}}},
                        'parameter-comment': {'files': {'D.scala': 1}},
                        'docs-check': {'entries': [{'kind': 'missing-symbol', 'count': 3}]},
                        'correspondence': {'areas': {'ledger': {'a': None, 'b': None}}}}
            for name, data in examples.items():
                (directory/f'{name}-baseline.json').write_text(json.dumps(data))
            result = health.baselines(root)
            self.assertEqual({k:v['count'] for k,v in result.items()},
                             {'imperative': 3, 'pinned-citation': 4, 'test-sleep': 2, 'parameter-comment': 1,
                              'docs-check': 3, 'correspondence': 2})

    def test_api_failure_still_generates_an_honest_page(self):
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(health, 'main_runs', side_effect=health.EvidenceError('API unavailable')):
            self.assertEqual(health.main(['--out', tmp]), 0)
            data = json.loads(Path(tmp, 'health.json').read_text())
            self.assertEqual(data['main_ci']['status'], 'unknown')
            page = Path(tmp, 'health.md').read_text()
            self.assertIn('Unknown: API unavailable', page)
            self.assertIn('**Unknown**', page)


if __name__ == '__main__':
    unittest.main()
