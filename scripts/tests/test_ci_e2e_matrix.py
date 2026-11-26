# SPDX-License-Identifier: Apache-2.0
"""The E2E devnet workflow's matrix (`ci_e2e_matrix`): every scenario, or the ones a dispatch names."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ci_e2e_matrix as m  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
NAMES = [s.name for s in m.SCENARIOS]


class SelectionCase(unittest.TestCase):
    def test_no_selection_runs_every_scenario(self):
        for selection in ("", "  ", ", ,"):
            with self.subTest(selection=selection):
                self.assertEqual(m.select(selection), list(m.SCENARIOS))

    def test_a_selection_runs_only_its_scenarios_in_the_listed_order_once_each(self):
        self.assertEqual([s.name for s in m.select("restart, ntc ntc,relay")], ["relay", "ntc", "restart"])

    def test_an_unknown_name_is_refused_with_every_unknown_one(self):
        with self.assertRaises(m.UnknownScenarios) as raised:
            m.select("ntc nct soak-test nct")
        self.assertEqual(raised.exception.names, ("nct", "soak-test"))

    def test_the_names_are_distinct(self):
        self.assertEqual(len(NAMES), len(set(NAMES)))

    def test_every_scenario_is_a_devnet_command_with_its_arguments(self):
        # `--help` parses the sub-command and its options and exits before running anything
        for s in m.SCENARIOS:
            with self.subTest(scenario=s.name):
                done = subprocess.run([sys.executable, str(ROOT / "scripts" / "devnet.py"), *s.args.split(), "--help"],
                                      capture_output=True, text=True, check=False)
                self.assertEqual(done.returncode, 0, done.stderr[-500:])


class OutputCase(unittest.TestCase):
    def run_main(self, *argv):
        with tempfile.TemporaryDirectory() as d:
            output = Path(d) / "output"
            output.touch()
            with mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), mock.patch("builtins.print"):
                code = m.main(list(argv))
            return code, output.read_text()

    def test_the_matrix_is_written_as_the_step_output(self):
        code, written = self.run_main("ntc")
        self.assertEqual(code, 0)
        key, _, value = written.strip().partition("=")
        self.assertEqual(key, "matrix")
        self.assertEqual(json.loads(value), {"include": [{"scenario": "ntc", "args": "ntc"}]})

    def test_an_unknown_name_writes_nothing_and_exits_2(self):
        self.assertEqual(self.run_main("ntc", "nct"), (2, ""))


class WorkflowCase(unittest.TestCase):
    def test_the_workflow_runs_the_selected_matrix(self):
        text = (ROOT / ".github" / "workflows" / "e2e.yml").read_text()
        self.assertIn('python3 scripts/ci_e2e_matrix.py "$SCENARIOS"', text)
        self.assertIn("matrix: ${{ fromJSON(needs.plan.outputs.matrix) }}", text)
        self.assertNotIn("include:", text)


if __name__ == "__main__":
    unittest.main()
