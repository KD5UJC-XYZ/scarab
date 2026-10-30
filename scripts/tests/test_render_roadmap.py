# SPDX-License-Identifier: Apache-2.0
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_roadmap as rr
from render_roadmap import IssueRecord, IssueState, Kind, Status, Unavailable

ROOT = Path(__file__).resolve().parents[2]
OPEN, CLOSED = IssueState.OPEN, IssueState.CLOSED


def rec(state, reason="", in_progress=False):
    return IssueRecord(state, reason, in_progress)


DONE, OPEN_BACKLOG = rec(CLOSED, "completed"), rec(OPEN)


def item(**overrides):
    base = {"id": "A1", "section": "A", "title": "Alpha thing", "priority": "P1",
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

    def test_a_status_is_stored_exactly_where_no_issue_gives_it(self):
        self.assertEqual(kinds(rr.validate(register(item(status="done")))), [("bad-status", "A1")])
        self.assertIn("derived from the issues", rr.validate(register(item(status="planned")))[0].detail)
        self.assertEqual(kinds(rr.validate(register(item(issues=[])))), [("bad-status", "A1")])
        self.assertEqual(rr.validate(register(item(issues=[], status="done"), item(id="A2", status="decision"))), [])

    def test_folded_into_exactly_on_folded_items(self):
        self.assertEqual(rr.validate(register(item(status="folded", folded_into=73))), [])
        self.assertEqual(kinds(rr.validate(register(item(status="folded")))), [("bad-folded-into", "A1")])
        self.assertEqual(kinds(rr.validate(register(item(folded_into=73)))), [("bad-folded-into", "A1")])

    def test_a_text_section_holds_no_items(self):
        self.assertEqual(kinds(rr.validate(register(item(section="order")))), [("unknown-section", "A1")])

    def test_wrongly_typed_values_are_problems_not_tracebacks(self):
        found = kinds(rr.validate(register(item(section=["A"], status={"x": 1}, priority=[1], parent=["A1"]))))
        self.assertEqual([k for k, _ in found], ["bad-parent", "bad-priority", "bad-status", "unknown-section"])

    def test_unknown_and_missing_fields_are_refused(self):
        self.assertEqual(kinds(rr.validate(register(item(extra=1)))), [("unknown-field", "A1")])
        self.assertEqual(kinds(rr.validate(register(item(priority=...)))), [("schema", "A1")])

    def test_duplicate_json_keys_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.json"
            path.write_text('{"schemaVersion": 1, "schemaVersion": 1}', encoding="utf-8")
            with self.assertRaises(ValueError):
                rr.read_json(path)


class DeriveTest(unittest.TestCase):
    """The status an item's issues give, from a fixed snapshot: no network."""

    def test_done_declined_in_progress_planned_and_not_synced(self):
        self.assertIs(rr.derive(item(issues=[7, 8]), {7: DONE, 8: rec(CLOSED, "not_planned")}), Status.DONE)
        self.assertIs(rr.derive(item(issues=[7, 8]), {7: rec(CLOSED, "not_planned"), 8: rec(CLOSED, "not_planned")}),
                      Status.DECLINED)
        self.assertIs(rr.derive(item(issues=[7, 8]), {7: rec(OPEN, "", True), 8: DONE}), Status.IN_PROGRESS)
        self.assertIs(rr.derive(item(issues=[7, 8]), {7: DONE, 8: rec(OPEN, "", True)}), Status.PLANNED)
        self.assertIs(rr.derive(item(issues=[7]), {7: rec(OPEN, "reopened")}), Status.PLANNED)
        self.assertIsNone(rr.derive(item(issues=[7, 8]), {7: DONE}))

    def test_a_stored_status_is_kept(self):
        self.assertIs(rr.derive(item(status="decision"), {7: OPEN_BACKLOG}), Status.DECISION)
        self.assertIs(rr.derive(item(issues=[], status="planned"), {}), Status.PLANNED)

    def test_a_folded_item_with_its_primary_open_is_a_problem(self):
        folded = register(item(status="folded", folded_into=73))
        self.assertEqual(rr.snapshot_problems(folded, {7: DONE}), [])
        self.assertEqual([p.kind for p in rr.snapshot_problems(folded, {7: OPEN_BACKLOG})], [Kind.FOLDED_ISSUE_OPEN])
        self.assertEqual(rr.snapshot_problems(folded, {}), [])


class SnapshotTest(unittest.TestCase):
    """The committed snapshot, and its refresh over a fake `gh`."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        (self.root / "docs/data").mkdir(parents=True)
        (self.root / rr.REGISTER).write_text(json.dumps(register(item(issues=[7, 8]), item(id="A2", issues=[9]))),
                                             encoding="utf-8")

    def test_write_and_read_round_trip_one_line_per_issue(self):
        rr.write_snapshot(self.root, {8: rec(OPEN, "", True), 7: DONE})
        text = (self.root / rr.SNAPSHOT).read_text(encoding="utf-8")
        self.assertIn('  "7": ["closed", "completed", false],\n  "8": ["open", "", true]\n', text)
        self.assertEqual(rr.read_snapshot(self.root), ({7: DONE, 8: rec(OPEN, "", True)}, []))

    def test_a_malformed_snapshot_is_a_defect(self):
        for bad in ('{"schemaVersion": 1, "issues": {"7": ["closed", "maybe", false]}}', '{"issues": {}}', "{"):
            (self.root / rr.SNAPSHOT).write_text(bad, encoding="utf-8")
            with self.subTest(bad=bad):
                self.assertEqual([p.kind for p in rr.read_snapshot(self.root)[1]], [Kind.BAD_SNAPSHOT])

    def fake(self, calls, missing=()):
        def run(args):
            calls.append(list(args))
            if "--paginate" in args:
                return "7\tclosed\tcompleted\tfalse\n8\topen\t\ttrue\n99\topen\t\tfalse\n"
            if args[1].endswith("/issues/9") and 9 not in missing:
                return "9\tclosed\tnot_planned\tfalse\n"
            raise Unavailable("HTTP 404")
        return run

    def test_refresh_all_reads_every_issue_and_keeps_only_the_named_ones(self):
        calls = []
        self.assertEqual(rr.refresh(self.root, True, self.fake(calls)), [])
        snapshot, _ = rr.read_snapshot(self.root)
        self.assertEqual(snapshot, {7: DONE, 8: rec(OPEN, "", True), 9: rec(CLOSED, "not_planned")})
        self.assertTrue(all(arg[0] == "api" and "-X" not in arg and "--method" not in arg for arg in calls))

    def test_refresh_without_all_adds_only_the_missing_issues(self):
        rr.write_snapshot(self.root, {7: rec(OPEN), 8: rec(OPEN), 50: DONE})
        calls = []
        rr.refresh(self.root, False, self.fake(calls))
        snapshot, _ = rr.read_snapshot(self.root)
        self.assertEqual(snapshot, {7: rec(OPEN), 8: rec(OPEN), 9: rec(CLOSED, "not_planned")})

    def test_an_issue_github_does_not_know_is_reported(self):
        problems = rr.refresh(self.root, True, self.fake([], missing={9}))
        self.assertEqual([(p.kind, p.detail) for p in problems], [(Kind.UNKNOWN_ISSUE, f"#9 is not an issue of {rr.REPOSITORY}")])

    def test_unavailable_github_fails_the_refresh_and_not_the_check(self):
        def offline(args):
            raise Unavailable("not logged in")
        original = rr.gh
        rr.gh = offline
        try:
            self.assertEqual(rr.main(["--root", str(self.root), "--refresh-states"]), 2)
        finally:
            rr.gh = original
        self.assertEqual(rr.main(["--root", str(self.root)]), 0)
        self.assertEqual(rr.main(["--root", str(self.root), "--check"]), 0)


class RenderTest(unittest.TestCase):
    def test_item_line_shape_matches_status_py(self):
        line = rr.item_line(item(issues=[7, 8], detail="; merged in PR #9"), {7: DONE, 8: DONE})
        self.assertEqual(line, "- **A1** [#7](https://github.com/KD5UJC-XYZ/scarab/issues/7), "
                               "[#8](https://github.com/KD5UJC-XYZ/scarab/issues/8) (P1, done; merged in PR #9) "
                               "Alpha thing: [guide](guide.md).")
        import status
        self.assertEqual(status.roadmap_ids(line), {7: "A1", 8: "A1"})

    def test_labels_children_and_text_sections(self):
        child = item(id="A1a", parent="A1", status="folded", folded_into=73, priority=None, notes=": rest.")
        text = rr.render(register(item(), child, item(id="A2", issues=[8])), {7: rec(OPEN, "", True)})
        self.assertIn("Do not edit by hand", text)
        self.assertIn("(P1, in progress)", text)
        self.assertIn("(P1, state not yet synced)", text)
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

    def test_the_check_reads_only_the_committed_snapshot(self):
        _, rendered = rr.run(self.root, check=False)
        self.assertIn("state not yet synced", rendered)
        (self.root / rr.OUTPUT).write_text(rendered, encoding="utf-8")
        self.assertEqual(rr.run(self.root, check=True)[0], [])
        rr.write_snapshot(self.root, {7: DONE})
        self.assertEqual([p.kind for p in rr.run(self.root, check=True)[0]], [Kind.STALE_RENDER])
        _, rendered = rr.run(self.root, check=False)
        self.assertIn("(P1, done)", rendered)


class RepositoryTest(unittest.TestCase):
    """The committed register, snapshot and markdown agree."""

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
