# SPDX-License-Identifier: Apache-2.0
import itertools
import os
from pathlib import Path
import re
import subprocess
import sys
import textwrap
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import promotion_rules


class CiGateTests(unittest.TestCase):
    workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/ci.yml").read_text()

    def job(self, name):
        match = re.search(r"^  " + re.escape(name) + r":\n(.*?)(?=^  [\w-]+:|\Z)",
                          self.workflow, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(match, f"missing CI job {name}")
        return match.group(1)

    def test_required_name_aggregates_both_unconditional_jobs(self):
        gate = self.job("scala")
        name = "scalafmt, compile and test"
        self.assertIn(name, promotion_rules.CHECKS)
        self.assertEqual(len(re.findall(r"^    name: " + re.escape(name) + r"$",
                                        self.workflow, re.MULTILINE)), 1)
        self.assertIn("    needs: [scala-tests, build-identity]\n", gate)
        self.assertIn("    if: ${{ always() }}\n", gate)
        self.assertIn("SCALA_RESULT: ${{ needs.scala-tests.result }}", gate)
        self.assertIn("IDENTITY_RESULT: ${{ needs.build-identity.result }}", gate)
        for name in ("scala-tests", "build-identity"):
            job = self.job(name)
            self.assertNotRegex(job, r"(?m)^    (?:if|needs):")
            self.assertNotIn("continue-on-error", job)
        self.assertNotIn("continue-on-error", gate)

    def test_actual_gate_script_refuses_failure_cancellation_skip_and_missing_results(self):
        match = re.search(r"^        run: \|\n((?:^          .*\n)+)",
                          self.job("scala"), re.MULTILINE)
        self.assertIsNotNone(match, "aggregate must execute its result checks")
        script = textwrap.dedent(match.group(1))
        states = ("success", "failure", "cancelled", "skipped", "", "unknown")
        for scala, identity in itertools.product(states, repeat=2):
            with self.subTest(scala=scala, identity=identity):
                result = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", script],
                                        env=dict(os.environ, SCALA_RESULT=scala, IDENTITY_RESULT=identity),
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, scala == identity == "success")

    def test_split_keeps_full_controls_and_native_guards_with_separate_budgets(self):
        scala = self.job("scala-tests")
        identity = self.job("build-identity")
        self.assertIn("timeout-minutes: 30", scala)
        self.assertIn("bash scripts/sbtw check node/runtimeClasspathFile", scala)
        self.assertIn("scripts/check_native_identity.py", scala)
        self.assertIn("scripts/check_native_isa.py", scala)
        self.assertNotIn("scripts/check_build_identity.py", scala)
        self.assertIn("timeout-minutes: 20", identity)
        self.assertIn("python3 -O scripts/check_build_identity.py\n", identity)
        self.assertNotIn("--case", identity)
        self.assertIn("path: .cache/scarab", identity)
        self.assertRegex(identity, r"SCARAB_CACHE_DIR=/work/\.cache(?:\s|$)")
        self.assertIn("key: build-identity-", identity)


if __name__ == "__main__":
    unittest.main()
