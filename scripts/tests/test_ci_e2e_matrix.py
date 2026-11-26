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


class RepeatCase(unittest.TestCase):
    def test_one_run_names_each_job_by_its_scenario(self):
        self.assertEqual(m.matrix(m.select("ntc relay")),
                         {"include": [{"job": "relay", "scenario": "relay", "args": "relay --seconds 300"},
                                      {"job": "ntc", "scenario": "ntc", "args": "ntc"}]})

    def test_copies_are_jobs_of_their_own_kept_together(self):
        jobs = m.matrix(m.select("ntc relay"), 2)["include"]
        self.assertEqual([(j["job"], j["scenario"]) for j in jobs],
                         [("relay-1", "relay"), ("relay-2", "relay"), ("ntc-1", "ntc"), ("ntc-2", "ntc")])
        self.assertEqual(len({j["job"] for j in m.matrix(m.SCENARIOS, m.MAX_REPEAT)["include"]}),
                         len(m.SCENARIOS) * m.MAX_REPEAT)

    def test_a_repeat_is_a_whole_number_from_1_to_the_maximum(self):
        self.assertEqual([m.repeat_of(t) for t in ("", " ", "1", "3", str(m.MAX_REPEAT))], [1, 1, 1, 3, m.MAX_REPEAT])
        for text in ("0", str(m.MAX_REPEAT + 1), "-1", "2.0", "two", "\u00b2"):
            with self.subTest(text=text):
                with self.assertRaises(m.BadRepeat):
                    m.repeat_of(text)


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
        self.assertEqual(json.loads(value), {"include": [{"job": "ntc", "scenario": "ntc", "args": "ntc"}]})

    def test_the_workflow_s_arguments_on_a_run_not_started_by_hand_select_every_scenario_once(self):
        code, written = self.run_main("--repeat", "", "")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(written.strip().partition("=")[2]), m.matrix(m.SCENARIOS))

    def test_a_repeat_is_written_as_copies(self):
        code, written = self.run_main("--repeat", "3", "ntc")
        self.assertEqual(code, 0)
        self.assertEqual([j["job"] for j in json.loads(written.strip().partition("=")[2])["include"]],
                         ["ntc-1", "ntc-2", "ntc-3"])

    def test_an_unknown_name_or_a_repeat_out_of_range_writes_nothing_and_exits_2(self):
        self.assertEqual(self.run_main("ntc", "nct"), (2, ""))
        self.assertEqual(self.run_main("--repeat", "9", "ntc"), (2, ""))

    def test_a_selection_that_starts_with_a_dash_is_a_name_after_the_separator(self):
        # as the workflow passes it: an option's spelling is refused as an unknown name, never taken as an option
        for selection in ("--repeat=3", "--help", "-h ntc"):
            with self.subTest(selection=selection):
                self.assertEqual(self.run_main("--repeat", "1", "--", selection), (2, ""))
        code, written = self.run_main("--repeat", "", "--", "")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(written.strip().partition("=")[2]), m.matrix(m.SCENARIOS))


class WorkflowCase(unittest.TestCase):
    def test_the_workflow_runs_the_selected_matrix(self):
        text = (ROOT / ".github" / "workflows" / "e2e.yml").read_text()
        self.assertIn('python3 scripts/ci_e2e_matrix.py --repeat "$REPEAT" -- "$SCENARIOS"', text)
        self.assertIn("matrix: ${{ fromJSON(needs.plan.outputs.matrix) }}", text)
        self.assertNotIn("include:", text)

    def test_the_jobs_names_their_devnet_log_and_artifact_by_the_job(self):
        # copies of one scenario differ only by `job`: anything named by the scenario would collide
        text = (ROOT / ".github" / "workflows" / "e2e.yml").read_text()
        self.assertNotIn("matrix.scenario", text)
        self.assertIn("name: devnet-${{ matrix.job }}-${{ github.run_id }}", text)
        self.assertIn("DEVNET_NAME: scarab-ci-${{ matrix.job }}", text)


if __name__ == "__main__":
    unittest.main()
