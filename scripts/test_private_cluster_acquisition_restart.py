# SPDX-License-Identifier: Apache-2.0
import unittest
from private_cluster_acquisition_restart import assess_phases, PROFILE


class ProcessEvidenceGuards(unittest.TestCase):
    def evidence(self):
        context = {"upstreamSource": "a" * 64, "genesisDigest": "b" * 64, "profile": PROFILE,
                   "anchor": {"slot": 1, "hash": "c" * 64}, "networkMagic": 1082026}
        common = {**context, "scope": "bounded-acquisition-process-phase", "complete": True,
                  "ledgerValidated": False, "consensusValidated": False, "segmentStoreReused": False}
        a = {**common, "phase": "a", "loadedCount": 0, "publishedCount": 2,
             "publishedGeneration": 3, "publishedRevision": "d" * 64, "publishedSource": "e" * 64}
        b = {**common, "phase": "b", "loadedCount": 2, "publishedCount": 4,
             "loadedGeneration": 3, "loadedRevision": "d" * 64, "loadedSource": "e" * 64,
             "publishedGeneration": 6, "publishedRevision": "f" * 64, "publishedSource": "9" * 64}
        return a, b, context

    def test_accepts_only_same_context_exact_revision_and_changed_branch_identity(self):
        assess_phases(*self.evidence())
        for field, value in [("upstreamSource", "0" * 64), ("genesisDigest", "0" * 64),
                             ("loadedRevision", "0" * 64), ("loadedGeneration", 2),
                             ("publishedGeneration", 3), ("publishedSource", "e" * 64),
                             ("ledgerValidated", True), ("consensusValidated", True),
                             ("segmentStoreReused", True), ("publishedCount", 3), ("complete", False)]:
            a, b, context = self.evidence()
            b[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): assess_phases(a, b, context)

    def test_context_cannot_be_replaced_by_matching_mutated_phase_reports(self):
        for field in ("upstreamSource", "genesisDigest", "profile", "anchor", "networkMagic"):
            a, b, context = self.evidence()
            a[field] = b[field] = "mutated"
            with self.subTest(field=field), self.assertRaises(ValueError): assess_phases(a, b, context)


if __name__ == "__main__": unittest.main()
