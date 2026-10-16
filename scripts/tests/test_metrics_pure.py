# SPDX-License-Identifier: Apache-2.0
"""Unit tests for reading the node's Prometheus endpoint (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import metrics  # noqa: E402

TEXT = (
    "# HELP a_total Counts a.\\nSecond line.\n"
    "# TYPE a_total counter\n"
    'a_total{path="/tip"} 3\n'
    'a_total{path="a \\"q\\" \\\\ b\\nc"} 1.5\n'
    "# HELP g A gauge.\n"
    "# TYPE g gauge\n"
    "g NaN\n"
    "# HELP h_seconds A histogram.\n"
    "# TYPE h_seconds histogram\n"
    'h_seconds_bucket{le="0.5"} 1\n'
    'h_seconds_bucket{le="+Inf"} 2\n'
    "h_seconds_sum 3.25\n"
    "h_seconds_count 2\n"
)


def exposition(**overrides):
    """A scrape holding every required series, at the values of a node that has applied 10 blocks."""
    values = {name: 1.0 for name in metrics.REQUIRED}
    values.update({"cardano_node_metrics_blockNum_int": 10.0, "cardano_node_metrics_epoch_int": 2.0,
                   "scarab_blocks_applied_total": 10.0, "scarab_block_apply_seconds_count": 10.0})
    values.update(overrides)
    lines = []
    for name, v in values.items():
        family = name.removesuffix("_count")
        lines += [f"# HELP {family} x", f"# TYPE {family} {'histogram' if name != family else 'gauge'}"]
        lines.append(f"{name} {v}" if name != "scarab_era" else f'{name}{{era="Conway"}} {v}')
    return "\n".join(lines) + "\n"


class ParseTests(unittest.TestCase):
    def test_reads_values_labels_and_escapes(self):
        samples = metrics.parse(TEXT)
        self.assertEqual(metrics.value(samples, "a_total", path="/tip"), 3.0)
        self.assertEqual(metrics.value(samples, "a_total", path='a "q" \\ b\nc'), 1.5)
        self.assertNotEqual(metrics.value(samples, "g"), metrics.value(samples, "g"))  # NaN
        self.assertEqual(metrics.value(samples, "h_seconds_bucket", le="+Inf"), 2.0)
        self.assertEqual(metrics.total(samples, "a_total"), 4.5)

    def test_refuses_what_the_format_does_not_allow(self):
        for bad in ("a 1\n",                                                    # no TYPE
                    "# HELP a x\n# TYPE a counter\n# TYPE a counter\n",         # TYPE twice
                    "# TYPE a counter\n",                                       # TYPE before HELP
                    "# HELP a x\n# TYPE a counter\na{b=1} 1\n",                 # unquoted label value
                    "# HELP a x\n# TYPE a counter\na 1 2 3\n",                  # a timestamp and more
                    "# HELP a x\n# TYPE a counter\na 1\na 2\n",                 # the same sample twice
                    "# HELP a x\n# TYPE a counter\na one\n",                    # not a number
                    "# HELP a x\n# TYPE a counter\n\na 1\n",                    # a blank line
                    "# EOF\n"):
            with self.assertRaises(ValueError, msg=bad):
                metrics.parse(bad)


class CheckTests(unittest.TestCase):
    def test_a_consistent_scrape_has_no_failure(self):
        samples = metrics.parse(exposition())
        self.assertEqual(metrics.check(samples, (9, 2, 9), (11, 2, 11)), [])

    def test_a_missing_series_is_named(self):
        samples = metrics.parse(exposition().replace("jvm_threads_current", "jvm_threads"))
        self.assertIn("missing series jvm_threads_current", metrics.check(samples, (9, 2, 9), (11, 2, 11)))

    def test_values_that_disagree_with_the_log_fail(self):
        samples = metrics.parse(exposition())
        self.assertTrue(any("blockNum" in f for f in metrics.check(samples, (11, 2, 11), (12, 2, 12))))
        self.assertTrue(any("epoch" in f for f in metrics.check(samples, (9, 3, 9), (11, 3, 11))))
        self.assertTrue(any("blocks_applied" in f for f in metrics.check(samples, (9, 2, 11), (11, 2, 12))))

    def test_a_validation_error_or_a_block_not_timed_fails(self):
        errors = metrics.parse(exposition() + "# HELP scarab_validation_errors_total x\n"
                               "# TYPE scarab_validation_errors_total counter\n"
                               'scarab_validation_errors_total{rule="ledger"} 1\n')
        self.assertTrue(any("validation_errors" in f for f in metrics.check(errors, (9, 2, 9), (11, 2, 11))))
        untimed = metrics.parse(exposition(scarab_block_apply_seconds_count=3.0))
        self.assertTrue(any("timed" in f for f in metrics.check(untimed, (9, 2, 9), (11, 2, 11))))

    def test_log_tip_is_the_last_extended_block(self):
        events = [{"event": "extended", "block": {"blockNo": 4}, "epoch": 1},
                  {"event": "epoch", "epoch": 2},
                  {"event": "extended", "block": {"blockNo": 5}, "epoch": 2}]
        self.assertEqual(metrics.log_tip(events), (5, 2, 2))
        self.assertEqual(metrics.log_tip([]), (0, 0, 0))


if __name__ == "__main__":
    unittest.main()
