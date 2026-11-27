# SPDX-License-Identifier: Apache-2.0
"""check_differential_fuzz: each differential target must have run, on at least the cases its floor share leaves,
with each tally of its random run at or above its fraction of them."""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_differential_fuzz as check

FLOORS = {
    "t-verify": check.Floor(2, {"accepted": 0.1, "refused": 0.5, "key2": 0.01}),
    "t-prove": check.Floor(4, {"accepted": 0.9}),
}


def line(target, cases, run, seeds=None):
    record = {"target": target, "cases": cases, "seeds": seeds or {}, "run": run}
    return f"  differential {json.dumps(record, separators=(',', ':'))}"


GOOD = "\n".join([
    "[info] compiling",
    "  t-verify: 1000 cases, 200 accepted and 800 refused by both",
    line("t-verify", 1000, {"accepted": 200, "refused": 800, "key2": 20}),
    line("t-prove", 500, {"accepted": 500}),
])


class FloorsTest(unittest.TestCase):
    def test_a_complete_run_meets_its_floors(self):
        self.assertEqual(check.shortfalls(check.read_lines(GOOD), 1000, 2, FLOORS), [])

    def test_a_target_without_its_line_fails(self):
        text = GOOD.replace(line("t-prove", 500, {"accepted": 500}), "  t-prove: skipped: not set")
        self.assertEqual(check.shortfalls(check.read_lines(text), 1000, 2, FLOORS),
                         ["t-prove: no differential line, so it did not run against the libsodium oracle"])

    def test_a_share_raised_past_the_floor_fails_on_its_case_count(self):
        text = GOOD.replace(line("t-prove", 500, {"accepted": 500}), line("t-prove", 100, {"accepted": 100}))
        problems = check.shortfalls(check.read_lines(text), 1000, 2, FLOORS)
        self.assertEqual(problems[0], "t-prove: 100 cases, fewer than 500 (1000 // 4 * 2)")

    def test_a_tally_under_its_fraction_fails_and_a_missing_one_counts_as_zero(self):
        text = GOOD.replace(line("t-verify", 1000, {"accepted": 200, "refused": 800, "key2": 20}),
                            line("t-verify", 1000, {"accepted": 99, "refused": 901}))
        self.assertEqual(check.shortfalls(check.read_lines(text), 1000, 2, FLOORS), [
            "t-verify: 99 accepted in the random run, fewer than 100 (0.1 of 1000)",
            "t-verify: 0 key2 in the random run, fewer than 10 (0.01 of 1000)",
        ])

    def test_a_short_run_needs_only_the_fractions_that_round_to_a_case(self):
        text = "\n".join([line("t-verify", 2, {"refused": 1}), line("t-prove", 1, {})])
        self.assertEqual(check.shortfalls(check.read_lines(text), 4, 1, FLOORS), [])

    def test_the_last_line_of_a_target_counts(self):
        text = GOOD + "\n" + line("t-prove", 1, {"accepted": 1})
        self.assertEqual(check.read_lines(text)["t-prove"]["cases"], 1)

    def test_main_reads_the_log_and_exits_one_on_a_shortfall(self):
        with tempfile.TemporaryDirectory() as scratch:
            log = Path(scratch) / "fuzz.log"
            log.write_text("nothing ran\n")
            with contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(check.main([str(log), "--cases", "10", "--seeds", "1"]), 1)
            self.assertIn("::error::sodium-ed25519: no differential line", out.getvalue())

    def test_the_floors_name_the_three_targets(self):
        self.assertEqual(sorted(check.FLOORS), ["sodium-ed25519", "sodium-vrf-prove", "sodium-vrf-verify"])


if __name__ == "__main__":
    unittest.main()
