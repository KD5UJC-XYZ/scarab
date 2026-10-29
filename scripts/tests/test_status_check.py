# SPDX-License-Identifier: Apache-2.0
import contextlib
import io
import json
import re
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import status
from status import Kind, Violation

ROOT = Path(__file__).resolve().parents[2]
NOW = datetime(2026, 10, 29, 12, 0, tzinfo=timezone.utc)
TRIAGED = ("area:network", "priority:P2", "status:backlog")


def issue(number, *labels, milestone="reference-parity", updated="2026-10-28T09:00:00Z", title=None):
    return {
        "number": number,
        "title": title or f"Issue {number}",
        "labels": [{"name": name} for name in labels],
        "milestone": None if milestone is None else {"title": milestone},
        "updated_at": updated,
    }


def kinds(raw):
    return [(v.kind, v.number) for v in status.check(raw, NOW)]


class CheckCase(unittest.TestCase):
    def test_a_triaged_issue_is_clean(self):
        self.assertEqual(status.check([issue(1, *TRIAGED)], NOW), [])

    def test_each_missing_label_is_named(self):
        found = status.check([issue(2, "priority:P1", "status:backlog"), issue(3, "area:scripts")], NOW)
        self.assertEqual(found, [
            Violation(Kind.MISSING_LABEL, 2, "Issue 2", "missing area"),
            Violation(Kind.MISSING_LABEL, 3, "Issue 3", "missing priority, status"),
        ])

    def test_an_area_outside_the_modules_and_the_three_others_is_reported(self):
        found = status.check([issue(4, "area:network", "area:node", "area:networking", "priority:P2", "status:backlog")], NOW)
        self.assertEqual(found, [Violation(Kind.UNKNOWN_AREA, 4, "Issue 4", "area:networking")])

    def test_a_milestone_is_required_unless_idea_or_parked(self):
        raw = [
            issue(4, *TRIAGED, milestone=None),
            issue(5, "area:scripts", "priority:P2", "status:idea", milestone=None),
            issue(6, "area:scripts", "priority:P2", "status:parked", milestone=None),
            issue(7, "area:scripts", "priority:P2", milestone=None),
        ]
        self.assertEqual(kinds(raw), [(Kind.MISSING_LABEL, 7), (Kind.NO_MILESTONE, 4), (Kind.NO_MILESTONE, 7)])
        self.assertEqual([v.detail for v in status.check(raw, NOW) if v.kind is Kind.NO_MILESTONE],
                         ["status backlog", "status none"])

    def test_two_status_labels(self):
        found = status.check([issue(8, "area:scripts", "priority:P1", "status:backlog", "status:blocked")], NOW)
        self.assertEqual(found, [Violation(Kind.TWO_STATUSES, 8, "Issue 8", "status:backlog, status:blocked")])

    def test_in_progress_is_stale_after_seven_days(self):
        labels = ("area:scripts", "priority:P1", "status:in-progress")
        raw = [issue(9, *labels, updated="2026-10-22T13:00:00Z"), issue(10, *labels, updated="2026-10-22T11:00:00Z")]
        self.assertEqual(status.check(raw, NOW),
                         [Violation(Kind.STALE_IN_PROGRESS, 10, "Issue 10", "updated 7 days ago (2026-10-22)")])

    def test_needs_owner_is_stale_after_fourteen_days(self):
        labels = ("area:scripts", "priority:P1", "status:needs-owner")
        raw = [issue(11, *labels, updated="2026-10-16T12:00:00Z"), issue(12, *labels, updated="2026-10-01T12:00:00Z")]
        self.assertEqual(kinds(raw), [(Kind.STALE_NEEDS_OWNER, 12)])

    def test_other_statuses_never_go_stale(self):
        self.assertEqual(status.check([issue(13, *TRIAGED, updated="2025-01-01T00:00:00Z")], NOW), [])

    def test_violations_are_grouped_in_kind_order(self):
        raw = [issue(20, "status:needs-owner", updated="2026-09-01T00:00:00Z"), issue(14, milestone=None)]
        self.assertEqual(kinds(raw), [
            (Kind.MISSING_LABEL, 14), (Kind.MISSING_LABEL, 20), (Kind.NO_MILESTONE, 14), (Kind.STALE_NEEDS_OWNER, 20),
        ])


class RenderCase(unittest.TestCase):
    def test_clean(self):
        self.assertEqual(status.render_check([], 3), "triage check: clean (3 open issues)")

    def test_grouped_one_line_per_issue(self):
        raw = [issue(2, "area:scripts", title="  A title "), issue(3, *TRIAGED, milestone=None)]
        self.assertEqual(status.render_check(status.check(raw, NOW), 2).splitlines(), [
            "triage check: 2 violations over 2 open issues",
            f"{Kind.MISSING_LABEL.value} (1)",
            "  #2     A title [missing priority, status]",
            f"{Kind.NO_MILESTONE.value} (1)",
            "  #3     Issue 3 [status backlog]",
        ])


class SourceCase(unittest.TestCase):
    def test_pages_are_flattened_and_pull_requests_dropped(self):
        pages = [[issue(1, *TRIAGED), {**issue(2), "pull_request": {"url": "x"}}], [issue(3, *TRIAGED)]]
        self.assertEqual([i["number"] for i in status.open_issues(pages)], [1, 3])
        self.assertEqual([i["number"] for i in status.open_issues([issue(4)])], [4])

    def test_the_command_is_a_paginated_read(self):
        calls = []

        def run(command, **_):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, json.dumps([[issue(1, *TRIAGED)]]), "")

        self.assertEqual(len(status.fetch_for_check(None, run)), 1)
        self.assertEqual(calls, [status.CHECK_COMMAND])
        self.assertEqual(calls[0][:4], ["gh", "api", "--paginate", "--slurp"])
        self.assertNotIn("--method", calls[0])

    def test_exit_codes(self):
        def run_with(raw, code=0):
            return lambda command, **_: subprocess.CompletedProcess(command, code, json.dumps(raw), "denied")

        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(status.run_check(None, False, NOW, run_with([[issue(1, *TRIAGED)]])), 0)
            self.assertEqual(status.run_check(None, False, NOW, run_with([[issue(1)]])), 1)
            self.assertEqual(status.run_check(None, False, NOW, run_with([], 1)), 2)
        self.assertIn("triage check: clean (1 open issues)", out.getvalue())
        self.assertIn("gh api failed: denied", err.getvalue())

    def test_main_reads_a_file_and_prints_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "issues.json"
            source.write_text(json.dumps([[issue(5, "area:scripts", "priority:P0", "status:idea", milestone=None)]]))
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(status.main(["--check", "--from", str(source), "--json"]), 0)
        self.assertEqual(json.loads(out.getvalue()), [])


class VocabularyCase(unittest.TestCase):
    """The areas come from the code map; the rest of the vocabulary lives in docs/process.md; the issue forms follow."""

    def test_areas_are_the_code_map_modules_and_the_three_outside_them(self):
        modules = [m if isinstance(m, str) else m["name"]
                   for m in json.loads((ROOT / "docs/data/codemap.json").read_text(encoding="utf-8"))["modules"]]
        self.assertEqual(status.AREAS, (*modules, "build", "scripts", "docs"))

    def test_process_lists_the_same_labels_and_milestones(self):
        text = (ROOT / "docs/process.md").read_text(encoding="utf-8")
        self.assertEqual(set(re.findall(r"`area:([a-z][a-z-]*)`", text)), set(status.OUTSIDE_MODULES))
        self.assertEqual(set(re.findall(r"`status:([a-z][a-z-]*)`", text)), set(status.STATUSES))
        self.assertEqual(set(re.findall(r"`priority:(P\d)`", text)), set(status.PRIORITIES))
        for milestone in status.MILESTONES:
            self.assertIn(f"`{milestone}`", text)

    def test_issue_forms_offer_the_vocabulary_and_default_to_backlog(self):
        for name in ("audit", "bug", "feature"):
            text = (ROOT / f".github/ISSUE_TEMPLATE/{name}.yml").read_text(encoding="utf-8")
            with self.subTest(form=name):
                self.assertEqual(dropdown(text, "area"), ["area:" + a for a in status.AREAS])
                self.assertEqual(dropdown(text, "priority"), ["priority:" + p for p in status.PRIORITIES])
                labels = re.search(r"^labels: \[(.*)\]$", text, re.MULTILINE).group(1)
                self.assertIn("status:backlog", [label.strip() for label in labels.split(",")])


def dropdown(text: str, field: str) -> list[str]:
    """The options of the dropdown with this id, read line by line (the test venv has no YAML parser)."""
    lines = text.splitlines()
    start = lines.index(f"    id: {field}")
    self_type = lines[start - 1].strip()
    assert self_type == "- type: dropdown", self_type
    options, inside = [], False
    for line in lines[start + 1:]:
        if line.startswith("  - type:"):
            break
        if line.strip() == "options:":
            inside = True
        elif inside and line.strip().startswith("- "):
            options.append(line.strip()[2:])
        elif inside:
            inside = False
    return options


if __name__ == "__main__":
    unittest.main()
