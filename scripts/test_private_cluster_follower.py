#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import copy
import unittest
from private_cluster_follower import check_report


class FollowerReportTests(unittest.TestCase):
    def records(self):
        points = [{"slot": i, "hash": str(i) * 64} for i in (2, 1, 0)]
        originals = [{"record": "acquisition-original", "phase": phase,
                      "headerEnvelopeHex": str(i), "blockHex": str(i), "index": i}
                     for phase, count in (("initial", 2), ("resumed", 4)) for i in range(count)]
        return originals + [
            {"record": "resume-intersection-offer", "attempt": i, "points": points}
            for i in range(2)] + [
            {"record": "resume-intersection-selected", "attempt": i, "point": points[0]}
            for i in range(2)] + [{
                "scope": "bounded-private-cluster-acquisition", "complete": True,
                "initialCount": 2, "resumedCount": 4, "disconnectInjectedLocally": True,
                "resumeAttempts": 2, "ledgerValidated": False, "consensusValidated": False,
                "initialPrefixPresentAtEnd": True}]

    def test_complete_receipt(self):
        self.assertTrue(check_report(self.records())["complete"])

    def test_incomplete_and_validation_claim_reject(self):
        for key, value in (("complete", False), ("disconnectInjectedLocally", False),
                           ("ledgerValidated", True), ("resumeAttempts", 1)):
            rows = self.records()
            rows[-1][key] = value
            with self.assertRaises(ValueError):
                check_report(rows)

    def test_changed_original_rejects(self):
        rows = self.records()
        rows[2]["blockHex"] = "different"
        with self.assertRaises(ValueError):
            check_report(rows)

    def test_anchor_fallback_or_missing_selection_rejects_acceptance(self):
        for missing in (False, True):
            rows = copy.deepcopy(self.records())
            if missing:
                rows.pop(-2)
            else:
                rows[-2]["point"] = {"slot": 0, "hash": "0" * 64}
            with self.assertRaises(ValueError):
                check_report(rows)


if __name__ == "__main__":
    unittest.main()
