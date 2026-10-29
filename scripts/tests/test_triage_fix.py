# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import triage_fix as tf
from triage_fix import Action, Fix

NOW = datetime(2026, 10, 29, tzinfo=timezone.utc)
FORM = "### Area\n\narea:node, area:ledger, area:nowhere\n\n### Priority\n\npriority:P1\n\n### Boundary\n\nx\n"


def issue(number, *labels, state="open", title=None, body="", milestone="reference-parity", updated="2026-10-28"):
    return {"number": number, "title": title or f"Issue {number}", "state": state, "body": body,
            "labels": [{"name": l} for l in labels], "updated_at": f"{updated}T00:00:00Z",
            "milestone": {"title": milestone} if milestone else None}


def pr(number, title="", body="", ref="feature"):
    return {"number": number, "title": title, "body": body, "head": {"ref": ref}}


class PlanCase(unittest.TestCase):
    def test_a_form_filed_issue_takes_its_answers_and_a_backlog_status(self):
        actions = tf.plan([issue(1, "bug", body=FORM)], [], NOW)
        self.assertEqual(actions[0], Action(Fix.FORM_LABELS, 1, add=("area:node", "area:ledger", "priority:P1",
                                                                    "status:backlog")))

    def test_a_labelled_issue_and_a_present_priority_are_left_alone(self):
        self.assertEqual(tf.plan([issue(2, "area:node", "priority:P2", "status:backlog", body=FORM)], [], NOW), [])
        actions = tf.plan([issue(3, "priority:P2", "status:backlog", body=FORM)], [], NOW)
        self.assertEqual(actions, [Action(Fix.FORM_LABELS, 3, add=("area:node", "area:ledger"))])

    def test_stale_work_moves_to_backlog_unless_a_pull_request_names_it(self):
        old = issue(4, "area:node", "priority:P2", "status:in-progress", updated="2026-10-20")
        actions = tf.plan([old], [], NOW)
        self.assertEqual([(a.fix, a.add, a.remove) for a in actions],
                         [(Fix.STALE_WORK, ("status:backlog",), ("status:in-progress",))])
        self.assertIn("Re-add `status:in-progress`", actions[0].comment)
        for named in (pr(9, body="Fixes #4."), pr(9, ref="claude/4-thing"), pr(9, body="issues/4")):
            with self.subTest(pr=named):
                self.assertEqual(tf.plan([old], [named], NOW), [])
        self.assertEqual(len(tf.plan([old], [pr(9, body="#40 and #14"), pr(10, ref="claude/44-x")], NOW)), 1)
        recent = issue(5, "area:node", "priority:P2", "status:in-progress", updated="2026-10-27")
        self.assertEqual(tf.plan([recent], [], NOW), [])

    def test_a_shaped_issue_without_a_milestone_gets_its_bucket(self):
        found = tf.plan([issue(6, "area:node", "priority:P2", "status:backlog", milestone=None,
                               title="Mainnet: follow the tip"),
                         issue(7, "area:network", "priority:P2", "status:backlog", milestone=None, title="Leios fetch"),
                         issue(8, "area:node", "priority:P2", "status:backlog", milestone=None),
                         issue(9, "area:node", "priority:P2", "status:idea", milestone=None)], [], NOW)
        self.assertEqual([(a.number, a.milestone) for a in found],
                         [(6, "mainnet-evidence"), (7, "leios-dijkstra"), (8, "reference-parity")])
        self.assertIn("the default bucket", found[2].comment)

    def test_an_issue_with_no_status_gets_no_milestone(self):
        self.assertEqual(tf.plan([issue(10, "bug", milestone=None)], [], NOW), [])

    def test_a_closed_issue_loses_the_statuses_that_claim_work(self):
        found = tf.plan([issue(11, "area:node", "status:in-progress", "status:blocked", state="closed"),
                         issue(12, "area:node", "status:backlog", state="closed")], [], NOW)
        self.assertEqual(found, [Action(Fix.CLOSED_STATUS, 11, remove=("status:in-progress", "status:blocked"))])

    def test_an_empty_pull_request_gets_one_marked_comment(self):
        found = tf.plan([], [pr(20), pr(21)], NOW, empty=lambda p: p["number"] == 21)
        self.assertEqual([(a.fix, a.number) for a in found], [(Fix.EMPTY_PR, 21)])
        self.assertTrue(found[0].comment.startswith(tf.EMPTY_PR_MARKER))


class EmptyMergeCase(unittest.TestCase):
    def test_a_merge_that_leaves_mains_tree_is_empty(self):
        def git(tree):
            def run(args):
                return {"fetch": "", "merge-tree": f"{tree}\n", "rev-parse": "aaa\n"}[args[0]]
            return run
        self.assertTrue(tf.merge_is_empty({"number": 5}, git("aaa")))
        self.assertFalse(tf.merge_is_empty({"number": 5}, git("bbb")))
        self.assertFalse(tf.merge_is_empty({"number": 5, "draft": True}, git("aaa")))
        self.assertFalse(tf.merge_is_empty({"number": 5, "_comments": [tf.EMPTY_PR_MARKER]}, git("aaa")))

        def conflict(args):
            raise RuntimeError("git merge-tree: conflict")
        self.assertFalse(tf.merge_is_empty({"number": 5}, conflict))


class ApplyCase(unittest.TestCase):
    def test_each_part_of_an_action_is_one_gh_call(self):
        calls = []
        tf.apply(Action(Fix.STALE_WORK, 4, add=("status:backlog",), remove=("status:in-progress",),
                        milestone="reference-parity", comment="why"), lambda args: calls.append(list(args)) or "")
        self.assertEqual(calls, [["issue", "edit", "4", "--add-label", "status:backlog"],
                                 ["issue", "edit", "4", "--remove-label", "status:in-progress"],
                                 ["issue", "edit", "4", "--milestone", "reference-parity"],
                                 ["issue", "comment", "4", "--body", "why"]])


if __name__ == "__main__":
    unittest.main()
