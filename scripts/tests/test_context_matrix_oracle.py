# SPDX-License-Identifier: Apache-2.0
"""Guard the context matrix's independent oracle and its explicitly bounded evidence claims."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/oracle/plutus/context-matrix'
SPEC = importlib.util.spec_from_file_location('matrix_run', HERE / 'run.py')
RUN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN)


class ContextMatrixOracleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = RUN.validate()
        cls.by_name = {row['name']: row for row in cls.rows}

    def test_matrix_spans_all_legal_language_protocol_and_purpose_cells(self):
        cells = {(row['protocolMajor'], row['language'], tuple(row['purposes']))
                 for row in self.rows if row['name'].startswith('api-')}
        for pv in range(5, 12):
            for language in range(1, 2 if pv < 7 else 3 if pv < 9 else 4):
                for tag in range(4 if pv < 9 else 6):
                    self.assertIn((pv, language, (tag,)), cells)

    def test_governance_translation_refusals_are_measured_instead_of_excluded(self):
        for pv in (9, 10, 11):
            for language in (1, 2, 3):
                for purpose in ('voting', 'proposing'):
                    row = self.by_name[f'api-conway-pv{pv}-v{language}-{purpose}']
                    self.assertEqual(row['oracle']['collected'], language == 3)
                    if language != 3:
                        self.assertEqual(row['oracle']['failureKinds'], ['BadTranslation'])

    def test_inline_and_reference_constraints_are_not_silently_removed(self):
        for pv in range(7, 12):
            era = 'babbage' if pv < 9 else 'conway'
            for variant in ('inline-spending', 'reference-inline', 'reference-script'):
                v1 = self.by_name[f'api-{era}-pv{pv}-v1-spending-{variant}']
                self.assertEqual(v1['oracle']['collected'], pv >= 9 and variant == 'reference-script')
                v2 = self.by_name[f'api-{era}-pv{pv}-v2-spending-{variant}']
                self.assertTrue(v2['oracle']['collected'])

    def test_request_order_is_observable_and_not_hidden_by_zip_truncation(self):
        for pv in (9, 10, 11):
            row = self.by_name[f'api-conway-pv{pv}-v3-all-purposes']
            scripts = row['oracle']['scripts']
            self.assertEqual(len(scripts), 6)
            self.assertEqual(len({script['contextCbor'] for script in scripts}), 6)

    def test_derived_inputs_never_claim_signed_admission(self):
        for row in self.rows:
            self.assertFalse(row['provenance']['ledgerAdmissionClaim'])
            if row['provenance']['kind'] == 'api-only-derived-transaction':
                self.assertFalse(row['provenance']['signaturesRevalidated'])
                self.assertEqual(len(row['provenance']['sourceTransactionSha256']), 64)

    def test_stale_embedded_source_fails_before_execution(self):
        stale = subprocess.CompletedProcess([], 0, stdout='0' * 64 + '  /oracle/context-matrix/harness/Main.hs\n')
        with patch.object(RUN, 'image_id', return_value='sha256:' + 'a' * 64):
            with patch.object(RUN.subprocess, 'run', return_value=stale):
                with self.assertRaisesRegex(ValueError, 'stale oracle'):
                    RUN.image_binding('stale:local')

    def test_matching_sources_cannot_hide_a_stale_executable(self):
        files = {'Main.hs': '/oracle/context-matrix/harness/Main.hs',
                 'scarab-context-oracle.cabal': '/oracle/context-matrix/harness/scarab-context-oracle.cabal',
                 'cabal.project': '/oracle/context-matrix/cabal.project',
                 'cabal.project.freeze': '/oracle/context-matrix/cabal.project.freeze'}
        lines = [hashlib.sha256((HERE / source).read_bytes()).hexdigest() + '  ' + target
                 for source, target in files.items()]
        lines.append('0' * 64 + '  /usr/local/bin/scarab-context-matrix')
        stale = subprocess.CompletedProcess([], 0, stdout='\n'.join(lines) + '\n')
        with patch.object(RUN, 'image_id', return_value='sha256:' + 'a' * 64):
            with patch.object(RUN.subprocess, 'run', return_value=stale):
                with self.assertRaisesRegex(ValueError, 'executable differs'):
                    RUN.image_binding('stale-binary:local')

    def test_build_failure_cannot_fall_back_to_previous_tag(self):
        with patch.object(RUN, 'image_id', return_value=RUN.BASE_ID) as resolve:
            with patch.object(RUN.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'build')):
                with self.assertRaises(subprocess.CalledProcessError):
                    RUN.build('stale:local')
            self.assertEqual(resolve.call_count, 1)
            resolve.assert_called_with(RUN.BASE_IMAGE)

    def test_changed_base_image_refuses_a_new_build(self):
        with patch.object(RUN, 'image_id', return_value='sha256:' + '0' * 64):
            with patch.object(RUN.subprocess, 'run') as execute:
                with self.assertRaisesRegex(ValueError, 'base image changed'):
                    RUN.build('untrusted:local')
                execute.assert_not_called()


if __name__ == '__main__':
    unittest.main()
