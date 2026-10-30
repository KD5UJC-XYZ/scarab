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


def event(kind, actor="someone", label=None, at="2026-10-28T00:00:00Z"):
    return {"event": kind, "actor": {"login": actor}, "label": {"name": label} if label else None, "created_at": at}


STALE = issue(4, "area:node", "priority:P2", "status:in-progress", updated="2026-10-20")


def kinds(actions):
    return [(a.fix, a.number) for a in actions]


class FixesCase(unittest.TestCase):
    def test_a_form_filed_issue_takes_its_answers_and_a_backlog_status(self):
        actions = tf.plan([issue(1, "bug", body=FORM)], [], NOW)
        self.assertEqual(actions[0].add, ("area:node", "area:ledger", "priority:P1", "status:backlog"))
        self.assertEqual(tf.plan([issue(2, "area:node", "priority:P2", "status:backlog", body=FORM)], [], NOW), [])
        self.assertEqual(tf.plan([issue(3, "priority:P2", "status:backlog", body=FORM)], [], NOW)[0].add,
                         ("area:node", "area:ledger"))

    def test_stale_work_is_flagged_and_never_relabelled(self):
        actions = tf.plan([STALE], [], NOW)
        self.assertEqual(kinds(actions), [(Fix.STALE_WORK, 4)])
        self.assertEqual((actions[0].add, actions[0].remove), ((), ()))

    def test_stale_work_is_left_alone_when_a_pull_request_branch_comment_or_owner_wait_says_it_is_alive(self):
        for prs, branches in [([pr(9, body="Fixes #4.")], []), ([pr(9, ref="claude/4-thing")], []),
                              ([pr(9, body="issues/4")], []), ([], ["claude/4-investigation"])]:
            with self.subTest(prs=prs, branches=branches):
                self.assertEqual(tf.plan([STALE], prs, NOW, branches=branches), [])
        self.assertEqual(len(tf.plan([STALE], [pr(9, body="#40 and #14")], NOW, branches=["claude/44-x"])), 1)
        recent = lambda n: [event("commented", at="2026-10-25T00:00:00Z")]
        self.assertEqual(tf.plan([STALE], [], NOW, timeline=recent), [])
        waiting = issue(5, "area:node", "priority:P2", "status:in-progress", "status:needs-owner", updated="2026-10-01")
        self.assertNotIn(Fix.STALE_WORK, [a.fix for a in tf.plan([waiting], [], NOW)])

    def test_a_shaped_issue_without_a_milestone_gets_its_bucket(self):
        found = tf.plan([issue(6, "area:node", "priority:P2", "status:backlog", milestone=None, title="Mainnet: tip"),
                         issue(7, "area:network", "priority:P2", "status:backlog", milestone=None, title="Leios fetch"),
                         issue(8, "area:node", "priority:P2", "status:backlog", milestone=None),
                         issue(9, "area:node", "priority:P2", "status:idea", milestone=None),
                         issue(10, "bug", milestone=None)], [], NOW)
        self.assertEqual([(a.number, a.milestone) for a in found],
                         [(6, "mainnet-evidence"), (7, "leios-dijkstra"), (8, "reference-parity")])

    def test_a_closed_issue_loses_the_statuses_that_claim_work(self):
        found = tf.plan([issue(11, "status:in-progress", "status:blocked", state="closed"),
                         issue(12, "status:backlog", state="closed")], [], NOW)
        self.assertEqual([(a.number, a.remove) for a in found], [(11, ("status:in-progress", "status:blocked"))])

    def test_an_empty_pull_request_is_found(self):
        found = tf.plan([], [pr(20), pr(21)], NOW, empty=lambda p: p["number"] == 21)
        self.assertEqual(kinds(found), [(Fix.EMPTY_PR, 21)])
        self.assertIn(tf.EMPTY_PR_MARKER, found[0].note)


class GuardsCase(unittest.TestCase):
    def test_triage_hold_stops_every_fix(self):
        held = [issue(1, "bug", tf.HOLD, body=FORM, milestone=None), issue(11, "status:in-progress", tf.HOLD, state="closed")]
        self.assertEqual(tf.plan(held, [], NOW), [])

    def test_a_fix_a_person_undid_is_not_made_again(self):
        bot = "github-actions[bot]"
        closed = issue(11, "status:in-progress", state="closed")
        set_before = lambda n: [event("labeled", label="status:in-progress", at="2026-10-01T00:00:00Z")]
        self.assertEqual(len(tf.plan([closed], [], NOW, timeline=set_before)), 1)
        undone = lambda n: [event("unlabeled", actor=bot, label="status:in-progress", at="2026-10-20T00:00:00Z"),
                            event("labeled", label="status:in-progress", at="2026-10-21T00:00:00Z")]
        self.assertEqual(tf.plan([closed], [], NOW, timeline=undone), [])
        form = issue(1, "bug", body=FORM)
        removed = lambda n: [event("labeled", actor=bot, label="area:ledger", at="2026-10-20T00:00:00Z"),
                             event("unlabeled", label="area:ledger", at="2026-10-21T00:00:00Z")]
        self.assertEqual(tf.plan([form], [], NOW, timeline=removed), [])
        unshaped = issue(8, "area:node", "priority:P2", "status:backlog", milestone=None)
        cleared = lambda n: [event("milestoned", actor=bot, at="2026-10-20T00:00:00Z"),
                             event("demilestoned", at="2026-10-21T00:00:00Z")]
        self.assertEqual(tf.plan([unshaped], [], NOW, timeline=cleared), [])
        self.assertEqual(len(tf.plan([unshaped], [], NOW, timeline=lambda n: [event("demilestoned")])), 1)


class ApplyCase(unittest.TestCase):
    def test_one_comment_per_issue_lists_every_fix_and_how_to_undo_it(self):
        calls = []
        tf.apply([Action(Fix.FORM_LABELS, 4, add=("area:node",), note="added `area:node` (undo: remove the label)"),
                  Action(Fix.MILESTONE, 4, milestone="reference-parity", note="set the milestone (undo: change it)"),
                  Action(Fix.CLOSED_STATUS, 9, remove=("status:blocked",), note="removed `status:blocked`")],
                 lambda args: calls.append(list(args)) or "")
        self.assertEqual([c[:3] for c in calls], [["issue", "edit", "4"], ["issue", "edit", "4"], ["issue", "comment", "4"],
                                                 ["issue", "edit", "9"], ["issue", "comment", "9"]])
        body = calls[2][-1]
        self.assertIn("- added `area:node` (undo: remove the label).\n- set the milestone (undo: change it).", body)
        self.assertIn(tf.HOLD, body)
        self.assertEqual(sum(1 for c in calls if c[1] == "comment"), 2)

    def test_only_enabled_fixes_are_applied(self):
        import io
        from contextlib import redirect_stdout
        import json
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "issues.json"
            source.write_text(json.dumps([[issue(11, "status:in-progress", state="closed")]]))
            out = io.StringIO()
            with redirect_stdout(out):
                self.assertEqual(tf.main(["--from", str(source)]), 0)
            self.assertIn("report    #11", out.getvalue())
            self.assertIn("0 applied, 1 reported only (enabled: none)", out.getvalue())


class EmptyMergeCase(unittest.TestCase):
    def test_a_merge_that_leaves_mains_tree_is_empty(self):
        def git(tree):
            return lambda args: {"fetch": "", "merge-tree": f"{tree}\n", "rev-parse": "aaa\n"}[args[0]]
        self.assertTrue(tf.merge_is_empty({"number": 5}, git("aaa")))
        self.assertFalse(tf.merge_is_empty({"number": 5}, git("bbb")))
        self.assertFalse(tf.merge_is_empty({"number": 5, "draft": True}, git("aaa")))
        self.assertFalse(tf.merge_is_empty({"number": 5, "_comments": [tf.EMPTY_PR_MARKER]}, git("aaa")))

        def conflict(args):
            raise RuntimeError("git merge-tree: conflict")
        self.assertFalse(tf.merge_is_empty({"number": 5}, conflict))


if __name__ == "__main__":
    unittest.main()
