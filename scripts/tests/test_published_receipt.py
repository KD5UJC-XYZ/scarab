# SPDX-License-Identifier: Apache-2.0
"""Receipt semantics are tested independently of unavailable Scarab publication credentials."""
from copy import deepcopy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import published_receipt as receipt
from publication_common import PublicationError


class PublishedReceiptTests(unittest.TestCase):
    def setUp(self):
        self.value = {"schemaVersion": 1, "status": "verified", "phase": "public", "repository": "example/scarab",
                      "tag": "v1.2.3", "sourceCommit": "a" * 40, "version": "1.2.3", "channel": "prod",
                      "candidateSha256": "b" * 64,
                      "subjects": {"jarSha256": "c" * 64, "imageDigest": "sha256:" + "d" * 64,
                                   "bomSha256": ["e" * 64, "f" * 64]},
                      "publisher": {"workflow": receipt.WORKFLOW, "workflowCommit": "a" * 40, "runId": 12},
                      "checks": {key: True for key in receipt.CHECKS}}

    def validate(self, value):
        return receipt.validate(value, "example/scarab", "a" * 40, "1.2.3")

    def test_semantic_validation_preserves_complete_receipt_without_claiming_authentication(self):
        self.assertEqual(self.validate(self.value), self.value)

    def test_staged_failed_missing_and_partial_receipts_are_not_complete(self):
        for change in [{"phase": "staged"}, {"status": "failed"}, {"channel": "preprod"},
                       {"sourceCommit": "b" * 40}, {"tag": "v1.2.4"}, {"checks": {}}]:
            with self.subTest(change=change), self.assertRaises(PublicationError):
                self.validate(dict(self.value, **change))
        for key in receipt.CHECKS:
            changed = deepcopy(self.value)
            changed["checks"][key] = False
            with self.subTest(check=key), self.assertRaises(PublicationError):
                self.validate(changed)

    def test_wrong_publisher_and_mutable_or_missing_artifact_subjects_fail(self):
        for path, value in [("workflow", ".github/workflows/untrusted.yml"), ("workflowCommit", "b" * 40), ("runId", True)]:
            changed = deepcopy(self.value)
            changed["publisher"][path] = value
            with self.subTest(field=path), self.assertRaises(PublicationError):
                self.validate(changed)
        for change in [{"imageDigest": "latest"}, {"bomSha256": ["e" * 64]}, {"jarSha256": "short"}]:
            changed = deepcopy(self.value)
            changed["subjects"].update(change)
            with self.subTest(change=change), self.assertRaises(PublicationError):
                self.validate(changed)

    def test_cryptographic_verifier_command_binds_exact_authority_and_subject(self):
        actual = receipt.attestation_command(Path("verification.json"), "example/scarab", "a" * 40, "v1.2.3")
        self.assertEqual(actual, ["gh", "attestation", "verify", "verification.json", "--repo", "example/scarab",
                                 "--signer-workflow", "example/scarab/" + receipt.WORKFLOW, "--signer-digest", "a" * 40,
                                 "--source-digest", "a" * 40, "--source-ref", "refs/tags/v1.2.3",
                                 "--cert-oidc-issuer", "https://token.actions.githubusercontent.com",
                                 "--deny-self-hosted-runners", "--predicate-type", "https://slsa.dev/provenance/v1", "--format", "json"])
        self.assertNotIn("--bundle", actual, "online lookup must check the currently named repository")

    def test_notes_transport_preserves_original_bytes_but_is_not_authentication(self):
        import base64
        from publication_common import digest
        raw = b'{"public":"original bytes"}\n'
        marker = receipt.RECEIPT_MARKER + digest(raw) + ':' + base64.b64encode(raw).decode() + ' -->'
        self.assertEqual(receipt.extract_receipt('Release notes\n' + marker), raw)
        with self.assertRaises(PublicationError):
            receipt.extract_receipt(marker + marker)
        with self.assertRaises(PublicationError):
            receipt.extract_receipt(marker.replace(digest(raw), 'f' * 64))
        with self.assertRaises(PublicationError):
            receipt.AuthenticatedReceipt(raw, object())


if __name__ == "__main__":
    unittest.main()
