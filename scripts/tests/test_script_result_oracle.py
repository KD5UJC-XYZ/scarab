# SPDX-License-Identifier: Apache-2.0
"""Byte-bound provenance and semantic coverage of the independent script-result API corpus."""
import importlib.util
import hashlib
import json
import subprocess
from unittest.mock import patch
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('script_result_inputs', ROOT / 'scripts/oracle/plutus/aggregation/prepare.py')
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)
RUN_SPEC = importlib.util.spec_from_file_location('script_result_runner', ROOT / 'scripts/oracle/plutus/aggregation/run.py')
RUN = importlib.util.module_from_spec(RUN_SPEC)
RUN_SPEC.loader.exec_module(RUN)


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

    def test_successful_budgets_are_required_and_have_an_exact_numeric_schema(self):
        RUN.validate_budget({'success': True, 'used': {'mem': 800, 'steps': 161100}})
        invalid = [None, {}, {'mem': 800}, {'mem': 800, 'steps': '161100'},
                   {'mem': 800, 'steps': 1.5}, {'mem': True, 'steps': 1},
                   {'mem': -1, 'steps': 1}, {'mem': 800, 'steps': 161100, 'other': 0}]
        for used in invalid:
            with self.subTest(used=used), self.assertRaises(ValueError):
                RUN.validate_budget({'success': True, 'used': used})
        with self.assertRaises(ValueError):
            RUN.validate_budget({'success': True})

    def test_failed_budgets_are_explicit_api_absence(self):
        RUN.validate_budget({'success': False, 'used': None})
        for row in ({'success': False}, {'success': False, 'used': {}},
                    {'success': False, 'used': {'mem': 0, 'steps': 0}}):
            with self.subTest(row=row), self.assertRaises(ValueError):
                RUN.validate_budget(row)

    def test_failed_build_never_resolves_or_executes_a_previous_tag(self):
        with patch.object(RUN.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'docker build')):
            with patch.object(RUN, 'image_id') as resolve:
                with self.assertRaises(subprocess.CalledProcessError):
                    RUN.build_image('stale-tag:local')
                resolve.assert_not_called()

    def test_stale_image_wrapper_is_rejected_before_replay(self):
        digest = 'sha256:' + 'a' * 64
        stale = subprocess.CompletedProcess([], 0, stdout='0' * 64 + '  /oracle/aggregation/harness/Main.hs\n')
        with patch.object(RUN, 'image_id', return_value=digest):
            with patch.object(RUN.subprocess, 'run', return_value=stale):
                with self.assertRaises(ValueError):
                    RUN.image_binding('old-tag:local')

    def test_original_control_is_byte_identical_to_a_retained_signed_admission(self):
        corpus = json.loads((ROOT / 'fixtures/reference/script-results/aggregation.json').read_text())
        originals = [row for row in corpus if row['provenance']['ledgerAdmissionClaim']]
        self.assertEqual(len(originals), 1)
        control = originals[0]
        submitted = json.loads((ROOT / 'fixtures/reference/context-errors/submissions/conway-pv10.json').read_text())
        source = next(row for row in submitted if row['name'] == control['provenance']['sourceCase'])
        self.assertTrue(source['reference']['accepted'])
        self.assertEqual(control['transaction'], source['contextCase']['transaction'])
        self.assertTrue(source['contextCase']['inclusion'])

    def test_stale_executable_is_rejected_even_with_matching_embedded_sources(self):
        directory = ROOT / 'scripts/oracle/plutus/aggregation'
        paths = {'Main.hs': '/oracle/aggregation/harness/Main.hs',
                 'scarab-aggregation-oracle.cabal': '/oracle/aggregation/harness/scarab-aggregation-oracle.cabal',
                 'cabal.project': '/oracle/aggregation/cabal.project',
                 'cabal.project.freeze': '/oracle/aggregation/cabal.project.freeze'}
        rows = [hashlib.sha256((directory / source).read_bytes()).hexdigest() + '  ' + path for source, path in paths.items()]
        rows.append('0' * 64 + '  /usr/local/bin/scarab-aggregation-oracle')
        stale = subprocess.CompletedProcess([], 0, stdout='\n'.join(rows) + '\n')
        with patch.object(RUN, 'image_id', return_value='sha256:' + 'a' * 64):
            with patch.object(RUN.subprocess, 'run', return_value=stale):
                with self.assertRaises(ValueError):
                    RUN.image_binding('stale-binary:local')
