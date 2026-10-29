# SPDX-License-Identifier: Apache-2.0
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_roadmap as rr
from render_roadmap import IssueState, Kind, Status, Unavailable

ROOT = Path(__file__).resolve().parents[2]
OPEN, CLOSED = IssueState.OPEN, IssueState.CLOSED


def item(**overrides):
    base = {"id": "A1", "section": "A", "title": "Alpha thing", "status": "done", "priority": "P1",
            "issues": [7], "notes": "Alpha thing: [guide](guide.md)."}
    base.update(overrides)
    return {k: v for k, v in base.items() if v is not ...}


def register(*items, sections=None):
    return {"schemaVersion": 1, "intro": "Intro line.",
            "sections": sections or [{"id": "A", "heading": "A. Alpha (P1)"},
                                     {"id": "order", "heading": "Order of work", "text": "First alpha."}],
            "items": list(items)}


def kinds(problems):
    return sorted((p.kind.value, p.subject) for p in problems)


class ValidationTest(unittest.TestCase):
    def test_sound_register_has_no_problems(self):
        self.assertEqual(rr.validate(register(item(), item(id="A1a", parent="A1", status="planned", issues=[]))), [])

    def test_status_enum_is_closed(self):
        self.assertEqual({s.value for s in Status},
                         {"planned", "in-progress", "done", "declined", "folded", "decision"})
        self.assertEqual(kinds(rr.validate(register(item(status="idea")))), [("bad-status", "A1")])

    def test_priority_enum_is_closed_and_may_be_null(self):
        self.assertEqual(rr.validate(register(item(priority=None))), [])
        self.assertEqual(kinds(rr.validate(register(item(priority="P3")))), [("bad-priority", "A1")])

    def test_ids_are_unique(self):
        self.assertEqual(kinds(rr.validate(register(item(), item()))), [("duplicate-id", "A1")])
        self.assertEqual(kinds(rr.validate(register(item(id="A*1")))), [("bad-id", "A*1")])

    def test_every_issue_number_is_a_positive_int(self):
        for bad in (["7"], [0], [True], [7.0], 7, [7, 7]):
            self.assertEqual(kinds(rr.validate(register(item(issues=bad)))), [("bad-issue", "A1")], bad)

    def test_notes_are_nonempty(self):
        self.assertEqual(kinds(rr.validate(register(item(notes=" ")))), [("empty-notes", "A1")])

    def test_title_is_one_bounded_line(self):
        for bad in ("", "two\nlines", "x" * 121, "a | b"):
            self.assertEqual(kinds(rr.validate(register(item(title=bad)))), [("bad-title", "A1")], bad)

    def test_detail_starts_with_its_separator(self):
        self.assertEqual(rr.validate(register(item(detail="; merged in PR #9"))), [])
        self.assertEqual(kinds(rr.validate(register(item(detail="merged")))), [("bad-detail", "A1")])

    def test_sections_and_parents_must_exist(self):
        self.assertEqual(kinds(rr.validate(register(item(section="Z")))), [("unknown-section", "A1")])
        self.assertEqual(kinds(rr.validate(register(item(), item(id="A2", parent="A9")))), [("bad-parent", "A2")])
        nested = register(item(), item(id="A2", parent="A1"), item(id="A3", parent="A2"))
        self.assertEqual(kinds(rr.validate(nested)), [("bad-parent", "A3")])

    def test_folded_into_exactly_on_folded_items(self):
        self.assertEqual(rr.validate(register(item(status="folded", folded_into=73))), [])
        self.assertEqual(kinds(rr.validate(register(item(status="folded")))), [("bad-folded-into", "A1")])
        self.assertEqual(kinds(rr.validate(register(item(folded_into=73)))), [("bad-folded-into", "A1")])

    def test_unknown_and_missing_fields_are_refused(self):
        self.assertEqual(kinds(rr.validate(register(item(extra=1)))), [("unknown-field", "A1")])
        self.assertEqual(kinds(rr.validate(register(item(priority=...)))), [("schema", "A1")])

    def test_duplicate_json_keys_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.json"
            path.write_text('{"schemaVersion": 1, "schemaVersion": 1}', encoding="utf-8")
            with self.assertRaises(ValueError):
                rr.read_json(path)


class StateTest(unittest.TestCase):
    """One negative case per state rule, with a fixed provider: no network."""

    def problems(self, entry, states):
        return [(p.kind, p.subject) for p in rr.state_problems(register(entry), states)]

    def test_done_needs_every_issue_closed(self):
        self.assertEqual(self.problems(item(issues=[7, 8]), {7: CLOSED, 8: CLOSED}), [])
        found = rr.state_problems(register(item(issues=[7, 8])), {7: CLOSED, 8: OPEN})
        self.assertEqual([(p.kind, p.subject) for p in found], [(Kind.DONE_ISSUE_OPEN, "A1")])
        self.assertIn("#8 open", found[0].detail)
        self.assertIn("status done", found[0].detail)

    def test_planned_and_in_progress_need_the_primary_open(self):
        for status in ("planned", "in-progress"):
            self.assertEqual(self.problems(item(status=status, issues=[7, 8]), {7: OPEN, 8: CLOSED}), [])
            self.assertEqual(self.problems(item(status=status), {7: CLOSED}), [(Kind.ACTIVE_ISSUE_CLOSED, "A1")])

    def test_folded_needs_the_primary_closed_and_a_known_target(self):
        folded = item(status="folded", folded_into=73)
        self.assertEqual(self.problems(folded, {7: CLOSED, 73: OPEN}), [])
        self.assertEqual(self.problems(folded, {7: CLOSED, 73: CLOSED}), [])
        self.assertEqual(self.problems(folded, {7: OPEN, 73: OPEN}), [(Kind.FOLDED_ISSUE_OPEN, "A1")])
        self.assertEqual(self.problems(folded, {7: CLOSED}), [(Kind.UNKNOWN_ISSUE, "A1")])

    def test_declined_needs_the_primary_closed(self):
        self.assertEqual(self.problems(item(status="declined"), {7: CLOSED}), [])
        self.assertEqual(self.problems(item(status="declined"), {7: OPEN}), [(Kind.DECLINED_ISSUE_OPEN, "A1")])

    def test_a_decision_and_an_item_without_issues_are_not_checked(self):
        self.assertEqual(self.problems(item(status="decision"), {7: OPEN}), [])
        self.assertEqual(self.problems(item(issues=[]), {}), [])

    def test_an_unknown_issue_is_reported(self):
        self.assertEqual(self.problems(item(), {}), [(Kind.UNKNOWN_ISSUE, "A1")])

    def test_issues_a_pull_request_closes_count_as_closed(self):
        merged = rr.after_merge({7: OPEN, 8: OPEN}, {7})
        self.assertEqual(merged, {7: CLOSED, 8: OPEN})
        self.assertEqual(self.problems(item(), merged), [])
        self.assertEqual(self.problems(item(status="in-progress"), merged), [(Kind.ACTIVE_ISSUE_CLOSED, "A1")])


class GithubTest(unittest.TestCase):
    """The provider and the pull-request reader over a fake `gh`."""

    def test_states_come_from_one_listing_and_misses_by_number(self):
        calls = []

        def fake(args):
            calls.append(list(args))
            if "--paginate" in args:
                return "7\topen\n8\tclosed\n"
            if args[1].endswith("/issues/9"):
                return "closed\n"
            raise Unavailable("HTTP 404")
        self.assertEqual(rr.github_states({7, 8, 9, 10}, fake), {7: OPEN, 8: CLOSED, 9: CLOSED})
        self.assertTrue(all(arg[0] == "api" and "-X" not in arg and "--method" not in arg for arg in calls))

    def test_closing_keywords_of_a_pull_request(self):
        def fake(args):
            return "fix(x): thing (#5)\nFixes #7, closes: #8 and resolved KD5UJC-XYZ/scarab#9; part of #400; #10\n"
        self.assertEqual(rr.closed_by("12", fake), {7, 8, 9})

    def test_unavailable_github_is_a_notice_not_a_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs/data").mkdir(parents=True)
            (root / rr.REGISTER).write_text(json.dumps(register(item())), encoding="utf-8")
            self.assertEqual(rr.main(["--root", str(root)]), 0)
            original = rr.github_states

            def offline(wanted, run=None):
                raise Unavailable("not logged in")
            rr.github_states = offline
            try:
                self.assertEqual(rr.main(["--root", str(root), "--check"]), 0)
                self.assertEqual(rr.main(["--root", str(root), "--check", "--require-issue-state"]), 1)
            finally:
                rr.github_states = original


class RenderTest(unittest.TestCase):
    def test_item_line_shape_matches_status_py(self):
        line = rr.item_line(item(issues=[7, 8], detail="; merged in PR #9"))
        self.assertEqual(line, "- **A1** [#7](https://github.com/KD5UJC-XYZ/scarab/issues/7), "
                               "[#8](https://github.com/KD5UJC-XYZ/scarab/issues/8) (P1, done; merged in PR #9) "
                               "Alpha thing: [guide](guide.md).")
        import status
        self.assertEqual(status.roadmap_ids(line), {7: "A1", 8: "A1"})

    def test_labels_children_and_text_sections(self):
        child = item(id="A1a", parent="A1", status="folded", folded_into=73, priority=None, notes=": rest.")
        text = rr.render(register(item(status="in-progress"), child))
        self.assertIn("Do not edit by hand", text)
        self.assertIn("(P1, in progress)", text)
        self.assertIn("  - **A1a** [#7](https://github.com/KD5UJC-XYZ/scarab/issues/7) "
                      "(folded into [#73](https://github.com/KD5UJC-XYZ/scarab/issues/73)): rest.", text)
        self.assertIn("## Order of work\n\nFirst alpha.", text)
        self.assertLess(text.index("**A1**"), text.index("**A1a**"))


class CheckModeTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        (self.root / "docs/data").mkdir(parents=True)
        (self.root / rr.REGISTER).write_text(json.dumps(register(item())), encoding="utf-8")

    def test_check_fails_until_the_render_is_committed(self):
        problems, rendered = rr.run(self.root, check=True)
        self.assertEqual([p.kind for p in problems], [Kind.STALE_RENDER])
        (self.root / rr.OUTPUT).write_text(rendered, encoding="utf-8")
        self.assertEqual(rr.run(self.root, check=True), ([], rendered))
        (self.root / rr.OUTPUT).write_text(rendered + "hand edit\n", encoding="utf-8")
        self.assertEqual([p.kind for p in rr.run(self.root, check=True)[0]], [Kind.STALE_RENDER])

    def test_state_problems_fail_the_check(self):
        _, rendered = rr.run(self.root, check=False)
        (self.root / rr.OUTPUT).write_text(rendered, encoding="utf-8")
        self.assertEqual(rr.run(self.root, check=True, states={7: CLOSED})[0], [])
        self.assertEqual([p.kind for p in rr.run(self.root, check=True, states={7: OPEN})[0]], [Kind.DONE_ISSUE_OPEN])


class RepositoryTest(unittest.TestCase):
    """The committed register and markdown agree (issue states are CI's, not the unit tests')."""

    def test_committed_register_validates_and_markdown_is_current(self):
        problems, _ = rr.run(ROOT, check=True)
        self.assertEqual([str(p) for p in problems], [])

    def test_every_item_of_the_register_reaches_status_py(self):
        register_ = rr.read_json(ROOT / rr.REGISTER)
        import status
        ids = status.roadmap_ids((ROOT / rr.OUTPUT).read_text(encoding="utf-8"))
        for entry in register_["items"]:
            if entry["issues"]:
                self.assertIn(entry["issues"][0], ids, entry["id"])


if __name__ == "__main__":
    unittest.main()
