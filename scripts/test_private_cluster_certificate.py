#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import hashlib
from pathlib import Path
import tempfile
import unittest
from private_cluster_certificate import CertificateRunner, SOURCES, checked_window, manifest, check_report


class CertificateGuards(unittest.TestCase):
    def point(self, block=10, slot=100, epoch=1):
        return {"block": block, "slot": slot, "epoch": epoch, "hash": "a" * 64, "era": "Conway"}

    def test_bounded_same_epoch_window(self):
        self.assertEqual(checked_window(self.point(), self.point(14, 120)), 4)
        self.assertEqual(checked_window(self.point(), self.point(18, 150)), 8)
        for post in (self.point(13, 120), self.point(19, 150), self.point(14, 99), self.point(14, 120, 2)):
            with self.assertRaises(ValueError): checked_window(self.point(), post)

    def test_incomplete_or_boolean_point_rejects(self):
        for key, value in (("hash", ""), ("block", True), ("slot", -1), ("era", "Babbage")):
            pre = self.point(); pre[key] = value
            with self.assertRaises(ValueError): checked_window(pre, self.point(14, 120))

    def test_manifest_requires_every_original_and_binds_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(FileNotFoundError): manifest(d)
            for name in SOURCES.values(): (Path(d) / name).write_bytes(b'{"x":1}\n')
            before = manifest(d)
            self.assertIn(hashlib.sha256(b'{"x":1}\n').hexdigest(), before)
            (Path(d) / "pre-protocol-state.md").write_bytes(b'{"x": 1}\n')
            self.assertNotEqual(manifest(d), before)

    def test_empty_and_oversized_source_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            for name in SOURCES.values(): (Path(d) / name).write_bytes(b'{}')
            for raw in (b'', b'x' * (4194304 + 1)):
                (Path(d) / "pre-protocol-state.md").write_bytes(raw)
                with self.assertRaises(ValueError): manifest(d)

    def rows(self):
        report = {"scope": "experimental-praos-certificate-state-v1", "passed": True, "blockCount": 4}
        report.update({k: True for k in ("opCertSignaturesChecked", "kesSignaturesChecked", "registrationChecked",
                      "finalCountersMatched", "rollbackReapplyMatched")})
        report.update({k: False for k in ("consensusValidated", "fullLedgerValidated", "vrfEligibilityChecked", "referenceSnapshotAtomic")})
        return [{"record": "transfer-range-block"} for _ in range(4)] + [report]

    def test_complete_scoped_report(self):
        self.assertTrue(check_report(self.rows(), 4)["passed"])

    def test_missing_predicate_and_broader_claim_reject(self):
        for key in ("opCertSignaturesChecked", "kesSignaturesChecked", "registrationChecked", "finalCountersMatched", "rollbackReapplyMatched",
                    "consensusValidated", "fullLedgerValidated", "vrfEligibilityChecked", "referenceSnapshotAtomic"):
            rows = self.rows(); rows[-1][key] = not rows[-1][key]
            with self.assertRaises(ValueError): check_report(rows, 4)

    def test_missing_original_and_wrong_count_reject(self):
        with self.assertRaises(ValueError): check_report(self.rows()[1:], 4)
        with self.assertRaises(ValueError): check_report(self.rows(), 5)

    def test_transaction_operations_forbidden_before_execution(self):
        runner = object.__new__(CertificateRunner)
        for op in ("build-raw", "sign", "submit"):
            with self.assertRaisesRegex(ValueError, "forbids"):
                runner.execute("cardano-cli", "conway", "transaction", op)


if __name__ == "__main__": unittest.main()
