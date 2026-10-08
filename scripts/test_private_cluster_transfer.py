# SPDX-License-Identifier: Apache-2.0
"""Observation guards only; these tests do not claim reference interoperability."""
import unittest
import json
from unittest.mock import patch
from types import SimpleNamespace
from private_cluster_transfer import TransferRunner, converged_tips

class ObservationGuards(unittest.TestCase):
    def test_origin_relay_tip_waits_until_all_three_nodes_have_matching_hashes(self):
        point = {"hash": "a" * 64}
        self.assertFalse(converged_tips([point, point, {"slot": 0}]))
        self.assertFalse(converged_tips([{}, {}, {}]))
        self.assertTrue(converged_tips([point, point, point]))

    def test_incomplete_malformed_and_divergent_convergence_samples_are_not_ready(self):
        point = {"hash": "a" * 64}
        for tips in [[point, point], [point, point, None],
                     [point, point, {"hash": "b" * 64}],
                     [{"hash": "g" * 64}] * 3, [{"hash": None}] * 3]:
            self.assertFalse(converged_tips(tips))

    def runner(self):
        r = object.__new__(TransferRunner)
        r.records = {}
        r.save = lambda name, text: r.records.update({name: text})
        r.pause_evidence = lambda: [{"state": "T"}, {"state": "T"}]
        r.query = lambda *args: {"hash": "point", "slot": 1, "era": "Conway", "epoch": 0}
        r.relay_query = lambda *args: json.dumps(r.query("tip")) if args[0] == "tip" else "{}"
        return r

    @patch("private_cluster_transfer.time.sleep")
    def test_quiescent_queries_remain_explicitly_non_atomic(self, _):
        r = self.runner()
        point, outputs = r.snapshot("pre")
        self.assertEqual(len(outputs), 5)
        self.assertIn('"singleAcquiredSnapshot": false', r.records["pre-binding.md"])
        self.assertIn("pre-producer-brackets.md", r.records)
        originals = [r.records[f"pre-tip-original-{i}.md"] for i in range(8)]
        self.assertEqual(r.records["pre-tips.md"], "[" + ",".join(originals) + "]")

    @patch("private_cluster_transfer.time.sleep")
    def test_tip_change_during_queries_rejected(self, _):
        r = self.runner()
        count = 0
        def query(*args):
            nonlocal count
            count += 1
            return {"hash": "changed" if count > 3 else "point", "slot": 1, "era": "Conway", "epoch": 0}
        r.query = query
        with self.assertRaisesRegex(ValueError, "tip changed"):
            r.snapshot("pre")

    @patch("private_cluster_transfer.time.sleep")
    def test_producer_resume_during_queries_rejected(self, _):
        r = self.runner()
        count = 0
        def paused():
            nonlocal count
            count += 1
            if count > 1: raise ValueError("producer progressed during state queries")
            return []
        r.pause_evidence = paused
        with self.assertRaisesRegex(ValueError, "producer progressed"):
            r.snapshot("pre")

    def test_actual_non_stopped_process_rejected(self):
        r = self.runner()
        r.read = lambda *args: "42"
        r.execute = lambda *args: SimpleNamespace(stdout="State:\tS (sleeping)\n")
        with self.assertRaisesRegex(ValueError, "producer progressed"):
            TransferRunner.pause_evidence(r)

if __name__ == "__main__": unittest.main()
