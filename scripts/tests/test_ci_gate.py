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

ROOT = Path(__file__).resolve().parents[2]
STATES = ("success", "failure", "cancelled", "skipped", "")


class CiGateTests(unittest.TestCase):
    workflow = (ROOT / ".github/workflows/ci.yml").read_text()
    build = (ROOT / "build.sbt").read_text()

    def job(self, name):
        match = re.search(r"^  " + re.escape(name) + r":\n(.*?)(?=^  [\w-]+:|\Z)",
                          self.workflow, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(match, f"missing CI job {name}")
        return match.group(1)

    def first_script(self, job):
        match = re.search(r"^        run: \|\n((?:^          .*\n)+)", self.job(job), re.MULTILINE)
        self.assertIsNotNone(match, f"{job} must execute its result checks")
        return textwrap.dedent(match.group(1))

    def assert_requires_exactly_success(self, job, variables):
        script = self.first_script(job)
        for states in itertools.product(STATES, repeat=len(variables)):
            with self.subTest(job=job, states=states):
                result = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", script],
                                        env=dict(os.environ, **dict(zip(variables, states))),
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, all(s == "success" for s in states))

    def test_required_name_aggregates_the_build_every_shard_and_their_summary(self):
        gate = self.job("scala")
        name = "scalafmt, compile and test"
        self.assertIn(name, promotion_rules.CHECKS)
        self.assertEqual(len(re.findall(r"^    name: " + re.escape(name) + r"$",
                                        self.workflow, re.MULTILINE)), 1)
        self.assertIn("    needs: [scala-build, scala-shards, scala-tests]\n", gate)
        self.assertIn("    if: ${{ always() }}\n", gate)
        for variable, job in (("BUILD_RESULT", "scala-build"), ("SHARDS_RESULT", "scala-shards"),
                              ("SCALA_RESULT", "scala-tests")):
            self.assertIn(f"{variable}: ${{{{ needs.{job}.result }}}}", gate)
        build = self.job("scala-build")
        self.assertNotRegex(build, r"(?m)^    (?:if|needs):")
        self.assertNotIn("continue-on-error", build)
        for name in ("scala", "scala-tests", "scala-shards"):
            self.assertNotIn("continue-on-error", self.job(name))

    def test_actual_gate_scripts_refuse_failure_cancellation_skip_and_missing_results(self):
        self.assert_requires_exactly_success("scala", ("BUILD_RESULT", "SHARDS_RESULT", "SCALA_RESULT"))
        self.assert_requires_exactly_success("scala-tests", ("BUILD_RESULT", "SHARDS_RESULT"))

    def test_summary_keeps_its_name_requires_build_and_shards_and_checks_every_suite_ran(self):
        summary = self.job("scala-tests")
        self.assertIn("    name: Scala tests and native guards\n", summary)
        self.assertIn("    needs: [scala-build, scala-shards]\n", summary)
        self.assertIn("    if: ${{ always() }}\n", summary)
        self.assertIn("BUILD_RESULT: ${{ needs.scala-build.result }}", summary)
        self.assertIn("SHARDS_RESULT: ${{ needs.scala-shards.result }}", summary)
        self.assertIn("pattern: scala-test-reports-*", summary)
        self.assertIn("python3 scripts/ci_test_shards.py verify ", summary)

    def test_build_runs_check_without_its_tests_the_native_guards_and_hands_on_its_output(self):
        build = self.job("scala-build")
        self.assertIn("timeout-minutes: 30", build)
        self.assertIn("run: bash scripts/sbtw checkBuild node/runtimeClasspathFile testSuiteInventory\n", build)
        self.assertIn("python3 scripts/ci_test_shards.py plan --inventory target/ci/test-suites.tsv", build)
        self.assertIn("scripts/check_native_identity.py", build)
        self.assertIn("scripts/check_native_isa.py", build)
        self.assertNotIn("scripts/check_build_identity.py", build)
        self.assertIn("uses: actions/cache@", build, "the build job saves the dependency cache")
        self.assertIn("tar --format=posix", build)
        self.assertIn("name: scala-build\n", build)
        self.assertIn("name: scala-test-plan\n", build)

    def test_shards_run_the_plan_on_the_build_output_without_compiling(self):
        shards = self.job("scala-shards")
        self.assertIn("    needs: [scala-build]\n", shards)
        self.assertNotRegex(shards, r"(?m)^    if:")
        self.assertIn("      fail-fast: false\n", shards)
        self.assertIn("uses: actions/cache/restore@", shards, "the shards only read the dependency cache")
        self.assertNotRegex(shards, r"uses: actions/cache@")
        self.assertIn("python3 scripts/ci_test_shards.py commands ", shards)
        self.assertIn('bash scripts/sbtw "${commands[@]}" 2>&1 | tee "$RUNNER_TEMP/shard.log"', shards)
        self.assertIn('python3 scripts/ci_test_shards.py compiled "$RUNNER_TEMP/shard.log"', shards)
        self.assertIn("name: scala-test-reports-${{ matrix.shard }}", shards)
        self.assertNotIn("sbtw check", shards)
        key = re.compile(r"^          key: (sbt-.*)$", re.MULTILINE)
        self.assertEqual(key.findall(shards), key.findall(self.job("scala-build")), "the shards read what the build saved")
        self.assertEqual(len(key.findall(shards)), 1)

    def test_check_is_the_build_alias_with_every_module_tests_in_between(self):
        self.assertIn('val checkTests = modules.map(m => s"$m/Test/test")\n', self.build)
        self.assertIn('addCommandAlias("check", (checkBeforeTests ++ checkTests ++ checkAfterTests)'
                      '.mkString(";", ";", ""))\n', self.build)
        self.assertIn('addCommandAlias("checkBuild", (checkBeforeTests ++ checkAfterTests).mkString(";", ";", ""))\n',
                      self.build)
        self.assertEqual(len(re.findall(r"addCommandAlias\(\s*\"check", self.build)), 2)
        before = re.search(r"^val checkBeforeTests =\n  (.*)$", self.build, re.MULTILINE)
        self.assertIsNotNone(before)
        self.assertEqual(before.group(1), 'Seq("scalafmtCheckAll", "headerCheckAll", "checkUplcClasspath") ++ '
                                          'modules.map(m => s"$m/Test/compile")')
        self.assertIn('val checkAfterTests = Seq("scalafixAll --check")\n', self.build)

    def test_identity_controls_are_paused_to_manual_runs_until_releases_start_from_main(self):
        # docs/design/releases.md, "Release checks paused until releases start from main", says how to restore them.
        identity = self.job("build-identity")
        self.assertIn("    if: github.event_name == 'workflow_dispatch'\n", identity)
        self.assertNotIn("build-identity", self.job("scala"))
        self.assertRegex(self.workflow, r"(?m)^  workflow_dispatch:$")
        self.assertNotIn("continue-on-error", identity)

    def test_identity_controls_keep_their_own_budget_and_cache(self):
        identity = self.job("build-identity")
        self.assertIn("timeout-minutes: 20", identity)
        self.assertIn("python3 -O scripts/check_build_identity.py\n", identity)
        self.assertNotIn("--case", identity)
        self.assertIn("path: .cache/scarab", identity)
        self.assertRegex(identity, r"SCARAB_CACHE_DIR=/work/\.cache(?:\s|$)")
        self.assertIn("key: build-identity-", identity)


if __name__ == "__main__":
    unittest.main()
