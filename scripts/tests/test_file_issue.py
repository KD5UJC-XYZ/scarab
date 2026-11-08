# SPDX-License-Identifier: Apache-2.0
import contextlib
import datetime
import io
import json
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import file_issue

BASE = ["--title", "Network: a gap", "--body", "What and why."]
OWN = ["--own-issue", "the owner asked for it"]
MARK = f"\n\n{file_issue.OWN_ISSUE} the owner asked for it\n"
LINK = "https://github.com/KD5UJC-XYZ/scarab/issues/124#issuecomment-1"


class FakeRunner:
    def __init__(self, code=0):
        self.code, self.calls = code, []

    def __call__(self, command, **_):
        self.calls.append(command)
        return subprocess.CompletedProcess(command, self.code)


class FakeGitHub:
    """`gh issue view/comment/edit` against one parent issue held in memory."""

    def __init__(self, body, state="OPEN", keeps_edit=True):
        self.body, self.state, self.keeps_edit, self.calls = body, state, keeps_edit, []

    def __call__(self, command, input=None, **_):
        self.calls.append((command[2], input))
        if command[2] == "view":
            return subprocess.CompletedProcess(command, 0, json.dumps({"state": self.state, "body": self.body}), "")
        if command[2] == "edit" and self.keeps_edit:
            self.body = input
        return subprocess.CompletedProcess(command, 0, LINK + "\n" if command[2] == "comment" else "", "")


def refused(argv):
    err = io.StringIO()
    with contextlib.redirect_stderr(err), unittest.TestCase().assertRaises(SystemExit) as raised:
        file_issue.options(argv)
    return raised.exception.code, err.getvalue()


def to_parent(github, argv=()):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = file_issue.main(BASE + ["--priority", "P2", "--parent", "124", *argv], github)
    return code, err.getvalue()


def line():
    return f"- [ ] Network: a gap ({datetime.date.today().isoformat()}, {LINK})"


class CommandCase(unittest.TestCase):
    def test_a_triaged_issue_becomes_one_gh_issue_create(self):
        runner, err = FakeRunner(), io.StringIO()
        argv = BASE + ["--area", "area:network", "--priority", "P1", "--status", "backlog",
                       "--milestone", "reference-parity", "--label", "audit"]
        with contextlib.redirect_stderr(err):
            self.assertEqual(file_issue.main(argv, runner), 0)
        expected = ["gh", "issue", "create", "--title", "Network: a gap", "--body", "What and why.",
                    "--label", "area:network", "--label", "priority:P1", "--label", "status:backlog",
                    "--label", "audit", "--milestone", "reference-parity"]
        self.assertEqual(runner.calls, [expected])
        self.assertEqual(err.getvalue(), "+ gh issue create --title 'Network: a gap' --body 'What and why.' "
                                         "--label area:network --label priority:P1 --label status:backlog "
                                         "--label audit --milestone reference-parity\n")

    def test_work_that_spans_modules_takes_each_area_once(self):
        runner = FakeRunner()
        argv = BASE + ["--area", "network", "--area", "area:node", "--area", "network", "--priority", "P1",
                       "--status", "backlog", "--milestone", "reference-parity"]
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(file_issue.main(argv, runner), 0)
        self.assertEqual(runner.calls[0][7:11], ["--label", "area:network", "--label", "area:node"])

    def test_idea_and_parked_need_no_milestone_and_gh_exit_status_is_returned(self):
        for status in ("idea", "status:parked"):
            runner = FakeRunner(code=4)
            with self.subTest(status=status), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(file_issue.main(BASE + ["--area", "scripts", "--priority", "P1", "--status", status],
                                                 runner), 4)
                self.assertNotIn("--milestone", runner.calls[0])

    def test_dry_run_prints_and_runs_nothing(self):
        runner, err = FakeRunner(), io.StringIO()
        argv = ["--title", "t", "--body-file", "b.md", "--area", "scripts", "--priority", "P0", "--status", "idea",
                "--dry-run"]
        with contextlib.redirect_stderr(err):
            self.assertEqual(file_issue.main(argv, runner), 0)
        self.assertEqual(runner.calls, [])
        self.assertIn("--body-file b.md", err.getvalue())

    def test_the_triplet_is_required(self):
        triplet = {"--area": "scripts", "--priority": "P1", "--status": "idea"}
        for missing in triplet:
            argv = BASE + [part for flag, v in triplet.items() if flag != missing for part in (flag, v)]
            with self.subTest(missing=missing):
                code, err = refused(argv)
                self.assertEqual(code, 2)
                self.assertIn(missing, err)

    def test_values_outside_the_vocabulary_are_refused(self):
        for flag, bad in (("--area", "area:nowhere"), ("--priority", "P3"), ("--status", "doing")):
            options = {"--area": "scripts", "--priority": "P1", "--status": "idea"}
            options[flag] = bad
            argv = BASE + [part for item in options.items() for part in item]
            with self.subTest(flag=flag):
                code, err = refused(argv)
                self.assertEqual(code, 2)
                self.assertIn("is not one of", err)

    def test_the_milestone_follows_the_status(self):
        backlog = BASE + ["--area", "scripts", "--priority", "P1", "--status", "backlog"]
        self.assertIn("needs --milestone", refused(backlog)[1])
        self.assertIn("is not one of", refused(backlog + ["--milestone", "v0.2"])[1])
        idea = BASE + ["--area", "scripts", "--priority", "P1", "--status", "idea", "--milestone", "reference-parity"]
        self.assertIn("carries no milestone", refused(idea)[1])

    def test_triage_labels_go_through_their_own_flags(self):
        argv = BASE + ["--area", "scripts", "--priority", "P1", "--status", "idea", "--label", "status:blocked"]
        self.assertIn("give it with --area, --priority or --status", refused(argv)[1])

    def test_a_p2_item_needs_a_parent_or_a_reason(self):
        code, err = refused(BASE + ["--area", "scripts", "--priority", "P2", "--status", "idea"])
        self.assertEqual(code, 2)
        self.assertIn("give --parent N, or --own-issue", err)
        self.assertIn("not allowed with argument", refused(BASE + ["--priority", "P2", "--parent", "1", *OWN])[1])

    def test_a_p2_issue_of_its_own_records_its_reason_in_the_body(self):
        runner = FakeRunner()
        argv = BASE + ["--area", "scripts", "--priority", "priority:P2", "--status", "idea", *OWN]
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(file_issue.main(argv, runner), 0)
        self.assertEqual(runner.calls[0][:7], ["gh", "issue", "create", "--title", "Network: a gap", "--body",
                                               "What and why." + MARK])


class ParentCase(unittest.TestCase):
    def test_a_line_goes_after_the_last_item_of_the_checklist_and_the_details_into_a_comment(self):
        github = FakeGitHub("Intro\n\n## Checklist (as of 2026-10-17)\n- [x] #126 done\n- [ ] #127 open\n\n## Notes\nend\n")
        self.assertEqual(to_parent(github)[0], 0)
        self.assertEqual(github.body, "Intro\n\n## Checklist (as of 2026-10-17)\n- [x] #126 done\n- [ ] #127 open\n"
                                      + line() + "\n\n## Notes\nend\n")
        self.assertEqual([step for step, _ in github.calls], ["view", "comment", "edit", "view"])
        self.assertEqual(github.calls[1][1], "Follow-up (priority:P2): **Network: a gap**\n\nWhat and why.\n")

    def test_a_parent_without_a_checklist_gets_a_follow_ups_section(self):
        github = FakeGitHub("What the epic is.\n")
        self.assertEqual(to_parent(github)[0], 0)
        self.assertEqual(github.body, "What the epic is.\n\n## Follow-ups\n\n" + line() + "\n")
        github = FakeGitHub("Top\n\n## Follow-ups\n\n## Done when\nx\n")
        self.assertEqual(to_parent(github)[0], 0)
        self.assertEqual(github.body, "Top\n\n## Follow-ups\n\n" + line() + "\n\n## Done when\nx\n")

    def test_a_closed_parent_is_refused_before_anything_is_posted(self):
        github = FakeGitHub("body", state="CLOSED")
        code, err = to_parent(github)
        self.assertEqual(code, 1)
        self.assertIn("#124 is closed", err)
        self.assertEqual([step for step, _ in github.calls], ["view"])

    def test_a_line_lost_to_a_racing_edit_fails_and_says_where_the_details_are(self):
        github = FakeGitHub("body\n", keeps_edit=False)
        code, err = to_parent(github)
        self.assertEqual(code, 1)
        self.assertIn("not in #124 after the edit", err)
        self.assertIn(LINK, err)

    def test_the_parent_keeps_its_labels_and_milestone(self):
        for extra in (["--area", "scripts"], ["--status", "idea"], ["--milestone", "reference-parity"],
                      ["--label", "audit"]):
            with self.subTest(extra=extra):
                code, err = refused(BASE + ["--priority", "P2", "--parent", "124", *extra])
                self.assertEqual(code, 2)
                self.assertIn("keeps the labels and milestone of #124", err)

    def test_dry_run_on_the_parent_path_prints_three_commands_and_runs_nothing(self):
        github = FakeGitHub("body")
        code, err = to_parent(github, ["--dry-run"])
        self.assertEqual((code, github.calls), (0, []))
        self.assertEqual(err.count("+ gh issue "), 3)


if __name__ == "__main__":
    unittest.main()
