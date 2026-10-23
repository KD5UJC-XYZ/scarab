# SPDX-License-Identifier: Apache-2.0
"""Guard the pre-Conway cost-model corpus: inputs, categories, hashes and the case shape (#332)."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/oracle/cost-models'
SPEC = importlib.util.spec_from_file_location('cost_models_run', HERE / 'run.py')
RUN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN)


class CostModelsOracleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = RUN.verify()

    def test_every_case_is_measured_in_both_eras_with_equal_verdicts(self):
        by_era = {era: {r['name'].split('/', 1)[1]: r for r in self.rows if r['era'] == era} for era in RUN.ERAS}
        self.assertEqual(by_era['alonzo'].keys(), by_era['babbage'].keys())
        for name, alonzo in by_era['alonzo'].items():
            babbage = by_era['babbage'][name]
            self.assertEqual(alonzo['tx'], babbage['tx'])
            self.assertEqual(alonzo['reference'].get('proposals'), babbage['reference'].get('proposals'), name)
            self.assertEqual(alonzo['reference']['accepted'], babbage['reference']['accepted'], name)

    def test_the_categories_cover_each_rule_the_issue_names(self):
        self.assertEqual({r['expect'] for r in self.rows}, set(RUN.CATEGORIES))

    def test_a_repeated_language_keeps_the_last_entry(self):
        row = next(r for r in self.rows if r['name'] == 'alonzo/v1-duplicate-distinct-values')
        known = row['reference']['proposals'][0]['costModels']['known']
        self.assertEqual(len(known), 1)
        self.assertEqual(known[0]['values'][0], 2)

    def test_a_changed_input_is_detected(self):
        changed = RUN.cases()
        changed[0]['tx'] = changed[0]['tx'][:-2] + '00'
        self.assertNotEqual(RUN.inputs(self.rows), changed)


if __name__ == '__main__':
    unittest.main()
