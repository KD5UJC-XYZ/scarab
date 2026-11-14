# SPDX-License-Identifier: Apache-2.0
"""Negative controls for whole native fixture identities and observation boundaries."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / "scripts/oracle/plutus/error-presentation"
sys.path.insert(0, str(HERE))
spec = importlib.util.spec_from_file_location("error_presentation_oracle", HERE / "run.py")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)
sys.path.pop(0)


class NativeErrorOracle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((oracle.CORPUS / "manifest.json").read_text())
        cls.requests = json.loads((oracle.CORPUS / "requests.json").read_text())
        cls.observations = json.loads((oracle.CORPUS / "observations.json").read_text())

    def test_complete_committed_inputs_and_native_outputs_bind(self):
        oracle.verify_files(self.manifest)

    def test_missing_and_reordered_observations_fail_closed(self):
        for bad in [self.observations[:-1], list(reversed(self.observations))]:
            with self.assertRaisesRegex(ValueError, "identities"):
                oracle.validate(self.requests, bad)

    def test_duplicate_ids_fail_even_when_both_sides_match(self):
        with self.assertRaisesRegex(ValueError, "identities"):
            oracle.validate(self.requests + [self.requests[0]], self.observations + [self.observations[0]])

    def test_missing_native_family_fails_instead_of_becoming_an_exclusion(self):
        pairs = [(r, o) for r, o in zip(self.requests, self.observations)
                 if not (r["kind"] == "EvaluationError" and o["subject"]["tag"] == "InvalidReturnValue")]
        with self.assertRaisesRegex(ValueError, "families"):
            oracle.validate([r for r, _ in pairs], [o for _, o in pairs])

    def test_a_single_changed_fixture_byte_is_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["files"]["observations.json"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "fixture identity"):
            oracle.verify_files(manifest)

    def test_stale_observer_image_is_rejected_before_execution(self):
        with patch.object(oracle, "execute", return_value=b"unrelated observer"):
            with self.assertRaisesRegex(ValueError, "stale native observer"):
                oracle.binding("sha256:unrelated")

    def test_missing_historical_profile_fails(self):
        pairs = [(r, o) for r, o in zip(self.requests, self.observations)
                 if not (r["kind"] == "Evaluate" and r["language"] == 1 and r["protocol"] == "5")]
        with self.assertRaisesRegex(ValueError, "historical legal profiles"):
            oracle.validate([r for r, _ in pairs], [o for _, o in pairs])


if __name__ == "__main__":
    unittest.main()
