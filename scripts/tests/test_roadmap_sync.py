# SPDX-License-Identifier: Apache-2.0
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import roadmap_sync as rs


def pr(**overrides):
    base = {"headRefName": rs.BRANCH, "headRefOid": "a" * 40, "author": {"login": "app/scarab-sync"},
            "files": [{"path": "docs/data/issue-states.json"}, {"path": "docs/roadmap.md"}],
            "statusCheckRollup": [{"name": "scripts", "status": "COMPLETED", "conclusion": "SUCCESS"},
                                  {"name": "preview", "status": "COMPLETED", "conclusion": "SKIPPED"}],
            "mergeable": "MERGEABLE"}
    base.update(overrides)
    return base


class VerdictCase(unittest.TestCase):
    def test_the_apps_green_snapshot_pull_request_merges(self):
        self.assertTrue(rs.verdict(pr(), "scarab-sync").merge)
        self.assertTrue(rs.verdict(pr(author={"login": "scarab-sync[bot]"}, files=[{"path": "docs/roadmap.md"}]),
                                   "scarab-sync").merge)

    def test_anything_else_stays_open(self):
        for case, reason in [
            (pr(headRefName="feature"), "not the bot/roadmap-sync branch"),
            (pr(author={"login": "ottobot-ai"}), "is not the sync App"),
            (pr(files=[{"path": "docs/roadmap.md"}, {"path": "scripts/render_roadmap.py"}]), "scripts/render_roadmap.py"),
            (pr(files=[]), "changes files other"),
            (pr(statusCheckRollup=[]), "no checks have run"),
            (pr(statusCheckRollup=[{"name": "scripts", "status": "IN_PROGRESS", "conclusion": ""}]), "pending"),
            (pr(statusCheckRollup=[{"name": "scripts", "status": "COMPLETED", "conclusion": "FAILURE"}]), "FAILURE"),
            (pr(mergeable="CONFLICTING"), "CONFLICTING"),
        ]:
            with self.subTest(reason=reason):
                decided = rs.verdict(case, "scarab-sync")
                self.assertFalse(decided.merge)
                self.assertIn(reason, decided.reason)


def no_sleep(seconds):
    raise AssertionError(f"slept {seconds}s for a pull request whose mergeable state was known")


class MergeCase(unittest.TestCase):
    def test_the_merge_is_pinned_to_the_checked_head(self):
        calls = []

        def run(args):
            calls.append(list(args))
            if args[:2] == ["pr", "list"]:
                return json.dumps([{"number": 77}])
            if args[:2] == ["pr", "view"]:
                return json.dumps(pr())
            return ""
        self.assertIn("merged #77", rs.merge("scarab-sync", False, run, no_sleep))
        self.assertEqual(calls[-1], ["pr", "merge", "77", "--repo", rs.REPOSITORY, "--squash", "--admin",
                                     "--match-head-commit", "a" * 40])
        calls.clear()
        self.assertIn("would merge #77", rs.merge("scarab-sync", True, run, no_sleep))
        self.assertFalse(any(c[:2] == ["pr", "merge"] for c in calls))

    def test_an_unknown_mergeable_state_is_read_again(self):
        answers = iter(["UNKNOWN", "UNKNOWN", "MERGEABLE"])
        calls, slept = [], []

        def run(args):
            calls.append(list(args))
            if args[:2] == ["pr", "list"]:
                return json.dumps([{"number": 77}])
            if args[:2] == ["pr", "view"]:
                return json.dumps(pr(mergeable=next(answers)))
            return ""
        self.assertIn("merged #77", rs.merge("scarab-sync", False, run, slept.append))
        self.assertEqual(sum(c[:2] == ["pr", "view"] for c in calls), 3)
        self.assertEqual(slept, [rs.MERGEABLE_WAIT_SECONDS] * 2)

    def test_a_state_that_stays_unknown_leaves_it_open(self):
        calls, slept = [], []

        def run(args):
            calls.append(list(args))
            if args[:2] == ["pr", "list"]:
                return json.dumps([{"number": 77}])
            return json.dumps(pr(mergeable="UNKNOWN"))
        self.assertIn("left open: GitHub reports it UNKNOWN", rs.merge("scarab-sync", False, run, slept.append))
        self.assertEqual(sum(c[:2] == ["pr", "view"] for c in calls), rs.MERGEABLE_ATTEMPTS)
        self.assertEqual(len(slept), rs.MERGEABLE_ATTEMPTS - 1)
        self.assertFalse(any(c[:2] == ["pr", "merge"] for c in calls))

    def test_no_open_pull_request_is_nothing_to_do(self):
        self.assertIn("no open", rs.merge("scarab-sync", False, lambda args: "[]", no_sleep))


if __name__ == "__main__":
    unittest.main()
