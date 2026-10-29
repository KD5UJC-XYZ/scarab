# SPDX-License-Identifier: Apache-2.0
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import dispatch_e2e as de
import flaky_tests as ft
from flaky_tests import Flake

LOG = ("2026-10-29T23:30:55Z \x1b[31m==> X xyz.kd5ujc.scarab.node.PipelineBackpressureSuite.a failed prefetch is "
       "landed while draining  0.038s munit.ComparisonFailException\n"
       "==> X xyz.kd5ujc.scarab.node.PipelineBackpressureSuite.a failed prefetch is landed while draining  0.040s x\n"
       "[error] Failed: Total 3111, Failed 1\n")


class FlakyCase(unittest.TestCase):
    def test_munit_failures_are_read_once_each_without_colour(self):
        self.assertEqual(ft.failures(LOG), [("PipelineBackpressureSuite", "a failed prefetch is landed while draining")])

    def test_a_commit_that_failed_and_passed_is_flaky(self):
        runs = [{"id": 1, "head_sha": "a", "conclusion": "failure"}, {"id": 2, "head_sha": "a", "conclusion": "success"},
                {"id": 3, "head_sha": "b", "conclusion": "failure"},
                {"id": 4, "head_sha": "c", "conclusion": "success", "first_attempt": "failure"}]
        self.assertEqual([r["id"] for r in ft.flaky_runs(runs)], [1, 4])

    def test_an_existing_issue_gets_one_comment_and_a_new_suite_one_issue(self):
        f1 = Flake("PipelineBackpressureSuite", "t", "https://run/1", "a" * 40)
        f2 = Flake("PipelineBackpressureSuite", "t", "https://run/2", "b" * 40)
        f3 = Flake("NewSuite", "u", "https://run/3", "c" * 40)
        f4 = Flake("NewSuite", "v", "https://run/4", "d" * 40)
        issues = [{"number": 538, "title": "Flaky: PipelineBackpressureSuite"}]
        steps = ft.plan([f1, f2, f3, f4], issues, lambda n, url: url == "https://run/1", {"NewSuite": "node"})
        self.assertEqual([(s.number, s.flake.run_url) for s in steps], [(538, "https://run/2"), (None, "https://run/3")])
        self.assertEqual(steps[1].labels, ("bug", "area:node", "priority:P2", "status:backlog"))
        self.assertEqual(steps[1].title, 'Flaky: NewSuite "u"')

    def test_a_suite_whose_issue_closed_after_the_run_is_skipped(self):
        flake = Flake("SessionFailureSuite", "t", "https://run/5", "e" * 40, "2026-10-29T18:00:00Z")
        closed = [{"title": "test: SessionFailureSuite hangs", "closed_at": "2026-10-29T22:00:00Z"}]
        self.assertEqual(ft.plan([flake], [], lambda n, u: False, {}, closed), [])
        early = [{"title": "test: SessionFailureSuite hangs", "closed_at": "2026-10-29T17:00:00Z"}]
        self.assertEqual(len(ft.plan([flake], [], lambda n, u: False, {}, early)), 1)

    def test_suites_map_to_the_module_of_their_test_file(self):
        files = ["node/src/test/scala/xyz/kd5ujc/scarab/node/PipelineBackpressureSuite.scala", "node/src/main/A.scala"]
        self.assertEqual(ft.suite_modules(files), {"PipelineBackpressureSuite": "node"})


class DispatchCase(unittest.TestCase):
    def fake(self, waiting_per_check):
        calls, checks = [], iter(waiting_per_check)
        state = {"current": []}

        def run(args):
            calls.append(list(args))
            if args[0] == "api":
                if "status=queued" in args[1]:
                    state["current"] = next(checks)
                return json.dumps({"workflow_runs": [{"id": i} for i in state["current"]] if "status=queued" in args[1] else []})
            return ""
        return run, calls

    def test_it_dispatches_only_once_nothing_is_waiting(self):
        run, calls = self.fake([[7], [7], []])
        slept = []
        self.assertTrue(de.dispatch_when_free("feature", 600, 30, run, slept.append, lambda: 0))
        self.assertEqual(slept, [30, 30])
        self.assertEqual([c for c in calls if c[0] == "workflow"],
                         [["workflow", "run", "e2e.yml", "--repo", de.REPOSITORY, "--ref", "feature"]])

    def test_it_gives_up_at_the_timeout_without_dispatching(self):
        run, calls = self.fake([[7]] * 10)
        times = iter([0, 100, 700])
        self.assertFalse(de.dispatch_when_free("feature", 600, 30, run, lambda s: None, lambda: next(times)))
        self.assertFalse(any(c[0] == "workflow" for c in calls))


if __name__ == "__main__":
    unittest.main()
