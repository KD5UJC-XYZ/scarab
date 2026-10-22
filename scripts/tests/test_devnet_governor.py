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
