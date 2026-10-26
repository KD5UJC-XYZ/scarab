# SPDX-License-Identifier: Apache-2.0
"""Guard the genesis cost-model corpus: inputs, categories, hashes and the case shape (#369)."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/oracle/genesis-cost-models'
SPEC = importlib.util.spec_from_file_location('genesis_cost_models_run', HERE / 'run.py')
RUN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN)


class GenesisCostModelsOracleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = RUN.verify()

    def test_the_categories_cover_each_rule_the_issue_names(self):
        self.assertEqual({r['category'] for r in self.rows}, set(RUN.CATEGORIES))

    def test_the_strict_length_guard_is_measured_for_each_language_it_covers(self):
        refused = {r['name'] for r in self.rows if r['category'] == 'parameterCount'}
        self.assertTrue({'base-array-165', 'base-array-167', 'conway-250', 'conway-252'} <= refused)

    def test_a_short_or_long_list_is_accepted_where_the_parser_is_lenient(self):
        accepted = {r['name'] for r in self.rows if r['category'] == 'accepted'}
        self.assertTrue({'extra-v2-short-100', 'extra-v2-long-300', 'make-PlutusV3-251'} <= accepted)

    def test_the_sweep_covers_every_parameter_at_four_values(self):
        sweep = RUN.json.loads(RUN.SWEEP.read_text())
        self.assertEqual({k: v['observations'] for k, v in sweep.items()},
                         {'PlutusV1': 343 * 4, 'PlutusV2': 343 * 4, 'PlutusV3': 361 * 4})

    def test_a_changed_input_is_detected(self):
        changed = RUN.CASES.cases()
        changed[0]['input']['set'] = {'costModels': {}}
        self.assertNotEqual(RUN.inputs(self.rows), changed)


if __name__ == '__main__':
    unittest.main()
