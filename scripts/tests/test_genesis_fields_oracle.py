# SPDX-License-Identifier: Apache-2.0
"""Guard the genesis-fields corpus: the generated cases, the base files and the verdicts' shape (#497)."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/oracle/genesis-fields'
SPEC = importlib.util.spec_from_file_location('genesis_fields_run', HERE / 'run.py')
RUN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN)


class GenesisFieldsOracleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = RUN.recorded()

    def test_the_fixture_holds_the_generated_cases(self):
        self.assertEqual([RUN.strip(r) for r in self.rows], RUN.generate())

    def test_the_base_files_are_the_recorded_ones(self):
        self.assertEqual(json.loads(RUN.PROVENANCE.read_text())['bases'], RUN.bases_hashes())

    def test_every_whole_file_is_accepted(self):
        whole = [r for r in self.rows if r['op'] == 'none']
        self.assertEqual(len(whole), len(RUN.bases()))
        self.assertTrue(all(r['reference']['accepted'] for r in whole))

    def test_every_top_level_field_of_the_mutated_files_is_removed_once(self):
        for era in RUN.ERAS:
            removed = {tuple(r['path']) for r in self.rows
                       if r['op'] == 'remove' and r['base'] == RUN.base_path(RUN.MUTATED, era)}
            self.assertTrue({(k,) for k in RUN.load(RUN.MUTATED, era)} <= removed, era)

    def test_only_the_optional_fields_may_be_removed(self):
        accepted = {(r['kind'], tuple(r['path'])) for r in self.rows
                    if r['op'] == 'remove' and r['reference']['accepted']}
        self.assertEqual(accepted, {
            ('shelleyGenesis', ('protocolParams', 'minUTxOValue')),
            ('shelleyGenesis', ('protocolParams', 'minPoolCost')),
            ('shelleyGenesis', ('staking',)),
            ('shelleyGenesis', ('extraConfig',)),
            ('alonzoGenesis', ('extraConfig',)),
            ('conwayGenesis', ('extraConfig',)),
            ('conwayGenesis', ('constitution', 'script')),
        })

    def test_a_changed_case_is_detected(self):
        changed = RUN.generate()
        changed[0]['op'] = 'remove'
        self.assertNotEqual([RUN.strip(r) for r in self.rows], changed)


if __name__ == '__main__':
    unittest.main()
