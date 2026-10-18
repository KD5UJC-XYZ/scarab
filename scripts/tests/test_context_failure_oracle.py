# SPDX-License-Identifier: Apache-2.0
"""The context oracle keeps whole-transaction admission separate from derived API proof."""
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


oracle = load("context_oracle_run", "scripts/oracle/plutus/context/run.py")
capture = load("context_failure_capture", "scripts/oracle/plutus/context_failures.py")


class ContextFailureOracleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.actual = json.loads((oracle.DIRECTORY / "submissions/conway-pv10.json").read_text())

    def test_manifest_and_reproducible_inputs_bind_all_original_and_derived_cases(self):
        cases = oracle.validate()
        self.assertEqual(sum(not case["oracle"]["collected"] for case in cases), 91)
        self.assertEqual(sum(case["provenance"]["kind"] == "api-only-derived-transaction" for case in cases), 79)
        self.assertEqual(sum(len(case["oracle"]["certificateEncodings"]) for case in cases), 14)

    def test_changed_signed_bytes_are_never_accepted_as_the_original_submission(self):
        case = deepcopy(self.actual[0])
        case["contextCase"]["transaction"] += "00"
        with self.assertRaisesRegex(ValueError, "original transaction bytes"):
            oracle.validate_submission(case)

    def test_witness_only_derivations_preserve_body_without_claiming_signature_validity(self):
        derived = [case for case in oracle.validate()
                   if case["provenance"]["kind"] == "api-only-derived-transaction"]
        self.assertEqual({case["name"] for case in derived if case["provenance"]["bodyMatchesOriginal"]},
                         {"api-v2-extra-redeemer-pointer", "api-v3-extra-redeemer-pointer",
                          "api-missing-script-witness-ignored-by-collector"})
        for case in derived:
            self.assertIs(case["provenance"]["signaturesRevalidated"], False)
            self.assertNotIn("signatureValidForDerivedBody", case["provenance"])

    def test_invalid_tag_success_requires_bound_collateral_confirmation(self):
        original = next(case for case in self.actual if case["reference"]["accepted"] and not case["isValid"])
        for field, value, message in (("collateralSpent", False, "collateral disposition"),
                                       ("txId", "00" * 32, "bound to the submitted"),
                                       ("confirmedBlock", 0, "shared k\\+1")):
            case = deepcopy(original)
            case["contextCase"]["inclusion"][field] = value
            with self.assertRaisesRegex(ValueError, message):
                oracle.validate_submission(case)

    def test_refusal_requires_actual_raw_error_and_cannot_claim_inclusion(self):
        original = next(case for case in self.actual if not case["reference"]["accepted"])
        case = deepcopy(original)
        case["reference"]["reasonCbor"] = None
        with self.assertRaisesRegex(ValueError, "raw ApplyTxErr"):
            oracle.validate_submission(case)
        case = deepcopy(original)
        case["contextCase"]["inclusion"] = {}
        with self.assertRaisesRegex(ValueError, "inclusion claim"):
            oracle.validate_submission(case)

    def confirm_collateral(self, observations):
        clock = SimpleNamespace(seconds=0)
        driver = SimpleNamespace(name="private", whole_utxo=lambda: observations[min(clock.seconds, len(observations) - 1)])
        resolved = {"spent": [("fund", "0", b"")], "collateral": [("collateral", "0", b"")]}

        def pause(seconds):
            clock.seconds += seconds

        def tip(*args, **kwargs):
            return {"block": clock.seconds + 2, "hash": f"block{clock.seconds + 2}"}

        with patch.object(capture.time, "monotonic", lambda: clock.seconds), \
                patch.object(capture.time, "sleep", pause), patch.object(capture, "query", tip):
            return capture.confirmed_collateral(driver, "original", resolved, 1, timeout=10)

    def test_empty_utxo_never_proves_invalid_tag_collateral_disposition(self):
        with self.assertRaisesRegex(RuntimeError, "funding/collateral disposition"):
            self.confirm_collateral([{}])

    def test_rollback_of_collateral_effect_resets_shared_confirmation_window(self):
        fund = {("fund", "0"): {}}
        both = {**fund, ("collateral", "0"): {}}
        proof = self.confirm_collateral([fund, both, fund])
        self.assertEqual((proof["firstSharedBlock"], proof["confirmedBlock"]), (4, 6))
        self.assertTrue(proof["collateralSpent"])
