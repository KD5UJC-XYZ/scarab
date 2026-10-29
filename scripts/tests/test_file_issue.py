# SPDX-License-Identifier: Apache-2.0
import contextlib
import io
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import file_issue

BASE = ["--title", "Network: a gap", "--body", "What and why."]


class FakeRunner:
    def __init__(self, code=0):
        self.code, self.calls = code, []

    def __call__(self, command, **_):
        self.calls.append(command)
        return subprocess.CompletedProcess(command, self.code)


def refused(argv):
    err = io.StringIO()
    with contextlib.redirect_stderr(err), unittest.TestCase().assertRaises(SystemExit) as raised:
        file_issue.command(argv)
    return raised.exception.code, err.getvalue()


class CommandCase(unittest.TestCase):
    def test_a_triaged_issue_becomes_one_gh_issue_create(self):
        runner, err = FakeRunner(), io.StringIO()
        argv = BASE + ["--area", "area:networking", "--priority", "P2", "--status", "backlog",
                       "--milestone", "reference-parity", "--label", "audit"]
        with contextlib.redirect_stderr(err):
            self.assertEqual(file_issue.main(argv, runner), 0)
        expected = ["gh", "issue", "create", "--title", "Network: a gap", "--body", "What and why.",
                    "--label", "area:networking", "--label", "priority:P2", "--label", "status:backlog",
                    "--label", "audit", "--milestone", "reference-parity"]
        self.assertEqual(runner.calls, [expected])
        self.assertEqual(err.getvalue(), "+ gh issue create --title 'Network: a gap' --body 'What and why.' "
                                         "--label area:networking --label priority:P2 --label status:backlog "
                                         "--label audit --milestone reference-parity\n")

    def test_idea_and_parked_need_no_milestone_and_gh_exit_status_is_returned(self):
        for status in ("idea", "status:parked"):
            runner = FakeRunner(code=4)
            with self.subTest(status=status), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(file_issue.main(BASE + ["--area", "ops", "--priority", "P1", "--status", status],
                                                 runner), 4)
                self.assertNotIn("--milestone", runner.calls[0])

    def test_dry_run_prints_and_runs_nothing(self):
        runner, err = FakeRunner(), io.StringIO()
        argv = ["--title", "t", "--body-file", "b.md", "--area", "ops", "--priority", "P0", "--status", "idea",
                "--dry-run"]
        with contextlib.redirect_stderr(err):
            self.assertEqual(file_issue.main(argv, runner), 0)
        self.assertEqual(runner.calls, [])
        self.assertIn("--body-file b.md", err.getvalue())

    def test_the_triplet_is_required(self):
        triplet = {"--area": "ops", "--priority": "P2", "--status": "idea"}
        for missing in triplet:
            argv = BASE + [part for flag, v in triplet.items() if flag != missing for part in (flag, v)]
            with self.subTest(missing=missing):
                code, err = refused(argv)
                self.assertEqual(code, 2)
                self.assertIn(missing, err)

    def test_values_outside_the_vocabulary_are_refused(self):
        for flag, bad in (("--area", "area:nowhere"), ("--priority", "P3"), ("--status", "doing")):
            options = {"--area": "ops", "--priority": "P2", "--status": "idea"}
            options[flag] = bad
            argv = BASE + [part for item in options.items() for part in item]
            with self.subTest(flag=flag):
                code, err = refused(argv)
                self.assertEqual(code, 2)
                self.assertIn("is not one of", err)

    def test_the_milestone_follows_the_status(self):
        backlog = BASE + ["--area", "ops", "--priority", "P2", "--status", "backlog"]
        self.assertIn("needs --milestone", refused(backlog)[1])
        self.assertIn("is not one of", refused(backlog + ["--milestone", "v0.2"])[1])
        idea = BASE + ["--area", "ops", "--priority", "P2", "--status", "idea", "--milestone", "reference-parity"]
        self.assertIn("carries no milestone", refused(idea)[1])

    def test_triage_labels_go_through_their_own_flags(self):
        argv = BASE + ["--area", "ops", "--priority", "P2", "--status", "idea", "--label", "status:blocked"]
        self.assertIn("give it with --area, --priority or --status", refused(argv)[1])


if __name__ == "__main__":
    unittest.main()
