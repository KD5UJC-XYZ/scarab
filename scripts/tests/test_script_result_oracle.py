# SPDX-License-Identifier: Apache-2.0
"""Byte-bound provenance and semantic coverage of the independent script-result API corpus."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('script_result_inputs', ROOT / 'scripts/oracle/plutus/aggregation/prepare.py')
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class ScriptResultOracleTests(unittest.TestCase):
    def test_inputs_are_reproducible_and_exactly_bound_to_goldens(self):
        retained = json.loads((ROOT / 'fixtures/reference/script-results/aggregation.json').read_text())
        strip_oracle = lambda row: {key: value for key, value in row.items() if key != 'oracle'}
        self.assertEqual([strip_oracle(row) for row in retained], [strip_oracle(row) for row in PREPARE.prepare()])

    def test_all_languages_cover_every_failure_position_and_multiple_failures(self):
        retained = json.loads((ROOT / 'fixtures/reference/script-results/aggregation.json').read_text())
        probes = [row for row in retained if row['name'].startswith('api-')]
        self.assertEqual(len(probes), 24)
        for language in (1, 2, 3):
            cases = [row for row in probes if row['language'] == language]
            patterns = [tuple(not script['success'] for script in row['oracle']['scripts']) for row in cases]
            self.assertEqual(len(set(patterns)), 8)
            for row in cases:
                self.assertFalse(row['provenance']['ledgerAdmissionClaim'])
                self.assertFalse(row['provenance']['signaturesRevalidated'])
                self.assertEqual(row['oracle']['logs'], [])
                self.assertEqual([trace for script in row['oracle']['scripts'] for trace in script['verboseLogs']], row['expectedExecutionLabels'])
                self.assertEqual(len(row['oracle']['failures']), len(row['expectedFailures']))
