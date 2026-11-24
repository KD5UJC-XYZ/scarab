# SPDX-License-Identifier: Apache-2.0
"""Tests of scripts/conformance/turbo_memory.py: the memory dimension of a uplc-turbo report."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from conformance import turbo_memory  # noqa: E402


def item(bench, mem_bytes, budget_mem=1_000_000):
    return {"bench": bench, "budget_cpu": 1, "budget_mem": budget_mem, "cpu_us": 1.0, "mem_bytes": mem_bytes}


class SummarizeTest(unittest.TestCase):
    def test_counts_follow_the_sites_memory_rule(self):
        report = {"benchmarks": [
            item("a", 2_000_000),            # twice the budget: within
            item("b", 2_400_000),            # past twice and past the tolerance: not within
            item("c", 300_000, 100_000),     # three times, but within the tolerance in bytes: within
            item("d", None),                 # not measured
            item("unknown", 1),              # not of the corpus
        ]}
        summary = turbo_memory.summarize(report, ("a", "b", "c", "d", "e"))
        self.assertEqual(summary["programs"], 5)
        self.assertEqual(summary["measured"], 3)
        self.assertEqual(summary["within_memory_limit"], 2)
        self.assertEqual(summary["bytes_per_memory_unit_median"], 2.4)
        self.assertIsNone(summary["bytes_per_memory_unit_p95"])
        self.assertEqual(summary["allocated_bytes"], 4_700_000)

    def test_an_empty_report_has_no_ratios(self):
        summary = turbo_memory.summarize({"benchmarks": []}, ("a",))
        self.assertEqual((summary["measured"], summary["within_memory_limit"]), (0, 0))
        self.assertIsNone(summary["bytes_per_memory_unit_median"])

    def test_the_markdown_names_the_commit_and_the_counts(self):
        text = turbo_memory.markdown(turbo_memory.summarize({"benchmarks": [item("a", 5)]}, ("a",)), "c" * 40)
        self.assertIn("`ccccccccc`", text)
        self.assertIn("| 1 | 1 | 1 |", text)


if __name__ == "__main__":
    unittest.main()
