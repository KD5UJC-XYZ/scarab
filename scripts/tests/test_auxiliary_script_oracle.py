# SPDX-License-Identifier: Apache-2.0
"""Independent auxiliary evidence guards, including decoder/phase1 distinction."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/oracle/plutus/auxiliary'
SPEC = importlib.util.spec_from_file_location('auxiliary_run', HERE / 'run.py')
RUN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN)


class AuxiliaryScriptOracleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = RUN.validate()

    def test_language_gates_apply_during_structure_decoding_even_to_empty_lists(self):
        for row in self.rows:
            if row['kind'] in ['v1-empty-list', 'v2-empty-list', 'v3-empty-list']:
                language = int(row['kind'][1])
                self.assertEqual(row['oracle']['decoded'], row['protocolMajor'] >= {1: 5, 2: 7, 3: 9}[language])

    def test_duplicate_auxiliary_fields_are_refused_by_the_actual_sparse_map_decoder(self):
        rows = [row for row in self.rows if 'duplicate' in row['kind'] and not row['kind'].startswith('metadata-')]
        self.assertEqual(len(rows), 98)
        self.assertTrue(all(not row['oracle']['decoded'] for row in rows))

    def test_malformed_flat_is_a_validity_failure_after_envelope_decoding(self):
        for row in self.rows:
            if row['kind'].endswith('-malformed') and row['oracle']['decoded']:
                self.assertFalse(row['oracle']['valid'])
            if row['kind'].endswith('-runtime-error-is-wellformed') and row['oracle']['decoded']:
                self.assertTrue(row['oracle']['valid'])

    def test_metadata_byte_limits_and_native_word64_tag_are_real_decoder_boundaries(self):
        for row in self.rows:
            if row['kind'] in ['metadata-bytes64', 'metadata-utf8-64', 'metadata-integer-max', 'metadata-integer-min']:
                self.assertTrue(row['oracle']['decoded'])
            if row['kind'] in ['metadata-bytes65', 'metadata-utf8-66', 'metadata-null', 'metadata-bool',
                               'metadata-tagged-bignum', 'metadata-label-negative', 'native-wrapped-tag']:
                self.assertFalse(row['oracle']['decoded'])

    def test_failed_build_cannot_replay_an_old_tag(self):
        with patch.object(RUN, 'image_id', return_value=RUN.BASE_ID) as resolve:
            with patch.object(RUN.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'build')):
                with self.assertRaises(subprocess.CalledProcessError):
                    RUN.build('old:local')
            self.assertEqual(resolve.call_count, 1)

    def test_stale_observer_is_rejected_before_execution(self):
        result = subprocess.CompletedProcess([], 0, stdout='0' * 64 + '  /oracle/auxiliary/harness/Main.hs\n')
        with patch.object(RUN, 'image_id', return_value='sha256:' + 'a' * 64):
            with patch.object(RUN.subprocess, 'run', return_value=result):
                with self.assertRaisesRegex(ValueError, 'stale oracle'):
                    RUN.image_binding('old:local')



class AuxiliaryMetadataPolicyTests(unittest.TestCase):
    def test_duplicate_labels_and_nested_map_duplicates_have_distinct_protocol_policies(self):
        for row in RUN.validate():
            if row['kind'] == 'metadata-duplicate-label':
                self.assertEqual(row['oracle']['decoded'], row['protocolMajor'] < 9)
            if row['kind'] == 'metadata-duplicate-nested-map':
                self.assertTrue(row['oracle']['decoded'])
            if row['kind'] in ['metadata-chunked-bytes64', 'metadata-chunked-text64']:
                self.assertTrue(row['oracle']['decoded'])
            if row['kind'] in ['metadata-chunked-bytes65', 'metadata-chunked-text66']:
                self.assertFalse(row['oracle']['decoded'])


if __name__ == '__main__':
    unittest.main()
