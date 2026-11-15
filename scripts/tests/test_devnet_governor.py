# SPDX-License-Identifier: Apache-2.0
"""The live churn gate requires the complete native twelve-step round."""
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from devnet.scenarios import governor


class GovernorScenarioTest(unittest.TestCase):
    def rounds(self, steps):
        slow = governor.SLOW
        return [{"steps": list(steps), "timeouts": [],
                 "demoted": [{"peers": [slow], "hotBefore": [slow, "fast-a"]}],
                 "hotAfter": after} for after in (["fast-a", "fast-b"], [slow, "fast-b"])]

    def test_all_twelve_steps_preserve_demotion_and_churn_assertions(self):
        failures, summary = [], {}
        governor.check_rounds(self.rounds(governor.STEPS), failures, summary)
        self.assertEqual(failures, [])
        self.assertEqual(summary["churnRounds"], 2)
        self.assertEqual(summary["slowPeerDemotedWhenHot"], 2)

    def test_ordinary_six_steps_are_not_a_complete_native_round(self):
        failures, summary = [], {}
        governor.check_rounds(self.rounds(governor.ORDINARY_STEPS), failures, summary)
        self.assertEqual(summary["churnRounds"], 0)
        self.assertTrue(failures)

    def test_reordered_twelve_steps_fail(self):
        failures, summary = [], {}
        governor.check_rounds(self.rounds(list(reversed(governor.STEPS))), failures, summary)
        self.assertTrue(any("all twelve steps" in failure for failure in failures))

    @staticmethod
    def churn(step, action, took):
        return {"event": "peerSelectionChurn", "action": action, "step": step, "tookSeconds": took}

    def test_churn_duration_gauges_follow_the_steps_that_met_their_targets(self):
        log = [self.churn("DecreaseActive", "step", 2.5), self.churn("IncreaseKnown", "timeout", 80.0),
               self.churn("DecreaseActive", "step", 0.75)]
        samples = {("cardano_node_metrics_peerSelection_churnDecreasedActivePeers_duration_real", ()): 0.7509}
        failures, summary = [], {}
        governor.check_churn_durations(samples, log, log, failures, summary)
        self.assertEqual(failures, [])
        self.assertEqual(summary["churnDurationGauges"], {"DecreaseActive": 0.7509})

    def test_a_churn_duration_gauge_of_a_timed_out_step_or_another_duration_fails(self):
        log = [self.churn("DecreaseActive", "step", 2.5), self.churn("IncreaseKnown", "timeout", 80.0)]
        samples = {("cardano_node_metrics_peerSelection_churnDecreasedActivePeers_duration_real", ()): 3.0,
                   ("cardano_node_metrics_peerSelection_churnIncreasedKnownPeers_duration_real", ()): 80.0}
        failures = []
        governor.check_churn_durations(samples, log, log, failures, {})
        self.assertTrue(any("churnDecreasedActivePeers" in f and "no DecreaseActive step" in f for f in failures),
                        failures)
        self.assertTrue(any("churnIncreasedKnownPeers" in f and "no IncreaseKnown step met" in f for f in failures),
                        failures)

    def test_a_missing_churn_duration_gauge_fails_only_for_a_step_met_before_the_scrape(self):
        before = [self.churn("DecreaseActive", "step", 2.5)]
        after = before + [self.churn("IncreaseActive", "step", 1.0)]
        samples = {("cardano_node_metrics_peerSelection_churnDecreasedActivePeers_duration_real", ()): 2.5004}
        failures = []
        governor.check_churn_durations(samples, before, after, failures, {})
        self.assertEqual(failures, [])
        failures = []
        governor.check_churn_durations({}, before, after, failures, {})
        self.assertTrue(any("churnDecreasedActivePeers_duration_real is absent" in f for f in failures), failures)

    def test_every_step_has_the_reference_churn_action_name(self):
        self.assertEqual(sorted(governor.CHURN_ACTIONS), sorted(governor.STEPS))
        self.assertEqual(governor.churn_duration_gauge("IncreaseEstablishedBig"),
                         "cardano_node_metrics_peerSelection_churnIncreasedEstablishedBigLedgerPeers_duration_real")

    def test_small_network_profile_explicitly_configures_zero_big_ledger_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "genesis").mkdir()
            (root / "genesis" / "configuration.yaml").write_text("{}")
            governor.write_files(SimpleNamespace(), root, {"2": 3002, "3": 3003})
            config = json.loads((root / "cfg" / "config.json").read_text())
            for population in ("Known", "Established", "Active"):
                self.assertEqual(config[f"TargetNumberOf{population}BigLedgerPeers"], 0)
            self.assertEqual(config["TargetNumberOfActivePeers"], 2)


if __name__ == "__main__":
    unittest.main()
