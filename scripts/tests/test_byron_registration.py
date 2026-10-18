# SPDX-License-Identifier: Apache-2.0
"""The full Byron reference fixture keeps crypto, ledger and decoder verdicts distinct."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from oracle.byron.full.run import FIXTURES, validate


class ByronRegistrationTests(unittest.TestCase):
    def test_retained_original_bytes_and_all_acceptance_layers(self):
        self.assertEqual(len(validate(FIXTURES)["results"]), 22)

    def altered(self, change):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "full-registration"
            shutil.copytree(FIXTURES, target)
            shutil.copyfile(FIXTURES.parent / "registration.json", parent / "registration.json")
            path = target / "reference.json"
            report = json.loads(path.read_text())
            change(report)
            path.write_text(json.dumps(report))
            # Rebind the file hash: these controls exercise policy beyond accidental byte corruption.
            manifest_path = target / "provenance.json"
            manifest = json.loads(manifest_path.read_text())
            row = next(row for row in manifest["files"] if row["path"] == "reference.json")
            row["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaises(AssertionError):
                validate(target)

    def test_ledger_acceptance_does_not_replace_header_crypto(self):
        def change(report):
            row = next(row for row in report["results"] if row["caseName"] == "invalid-header-signature")
            row["compositeBlock"]["verdict"] = "Accepted"
        self.altered(change)

    def test_accepted_block_must_retain_the_transaction_state_effects(self):
        def change(report):
            report["results"][0]["inputSpent"] = False
        self.altered(change)

    def test_backend_or_unexpected_failure_is_not_a_version_refusal(self):
        def change(report):
            report["results"][6]["registration"]["verdict"] = "OtherFailure"
        self.altered(change)

    def test_decoded_overflow_cannot_count_as_an_executed_crypto_verdict(self):
        def change(report):
            row = next(row for row in report["results"] if row["caseName"] == "proposal-alt-256")
            row["proposalSignature"] = False
        self.altered(change)
