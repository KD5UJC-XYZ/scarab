# SPDX-License-Identifier: Apache-2.0
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_compatibility as rc
from render_compatibility import Kind, Status

ROOT = Path(__file__).resolve().parents[2]


def entry(**overrides):
    base = {"id": "alpha", "component": "Alpha thing (#7)", "status": "implemented", "summary": "Does alpha",
            "evidence": {"suites": ["AlphaSuite"], "paths": ["fixtures/alpha/README.md"],
                         "docs": ["docs/guide.md#second-part"], "issues": [7]},
            "limitations": {"issues": [8], "roadmap": ["L1"]},
            "page": "docs/compatibility/alpha.md"}
    base.update(overrides)
    return base


def register(*components, sections=None):
    return {"schemaVersion": 1, "intro": "Intro line.",
            "sections": sections or [{"id": "matrix", "heading": "Matrix", "matrix": True},
                                     {"id": "log", "heading": "Log", "summary": "Runs.", "pages": ["log"]}],
            "components": list(components)}


class RepoCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.write("node/src/test/scala/AlphaSuite.scala", "class AlphaSuite\nobject Helper\ntrait Shared")
        self.write("fixtures/alpha/README.md", "# alpha\n")
        self.write("docs/guide.md", "# Guide\n## Second part\n")
        self.write("docs/compatibility/alpha.md", "# Alpha thing\n")
        self.write("docs/compatibility/log.md", "# Run log\n")

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def save(self, data):
        self.write(rc.REGISTER, json.dumps(data))

    def kinds(self, data):
        return sorted((p.kind.value, p.subject) for p in rc.validate(self.root, data))


class ValidationTest(RepoCase):
    def test_sound_register_has_no_problems(self):
        self.assertEqual(rc.validate(self.root, register(entry())), [])

    def test_status_enum_is_closed(self):
        self.assertEqual({s.value for s in Status}, {"implemented", "partial", "not implemented"})
        self.assertEqual(self.kinds(register(entry(status="done"))), [("bad-status", "alpha")])

    def test_ids_are_unique_and_file_name_safe(self):
        self.assertIn(("duplicate-id", "alpha"), self.kinds(register(entry(), entry())))
        self.assertIn(("bad-id", "Not_Safe"), self.kinds(register(entry(id="Not_Safe", page=None))))

    def test_suites_paths_anchors_must_exist(self):
        bad = entry(evidence={"suites": ["GoneSuite", "Helper"], "paths": ["fixtures/gone", "../etc/passwd"],
                              "docs": ["docs/guide.md#nope", "docs/missing.md", "README.md"]})
        found = rc.validate(self.root, register(bad))
        self.assertEqual(sorted((p.kind, p.detail) for p in found), sorted([
            (Kind.MISSING_SUITE, "GoneSuite"),
            (Kind.MISSING_PATH, "fixtures/gone"),
            (Kind.MISSING_PATH, "../etc/passwd"),
            (Kind.BAD_ANCHOR, "docs/guide.md#nope"),
            (Kind.MISSING_PATH, "docs/missing.md"),
            (Kind.MISSING_PATH, "README.md"),
        ]))

    def test_a_suite_only_under_main_sources_does_not_count(self):
        self.write("node/src/main/scala/OnlyMain.scala", "class MainOnlySuite")
        self.assertEqual(self.kinds(register(entry(evidence={"suites": ["MainOnlySuite"]}))),
                         [("missing-suite", "alpha")])

    def test_issue_numbers_are_positive_integers(self):
        self.assertEqual(self.kinds(register(entry(limitations={"issues": ["8"]}))), [("bad-issue", "alpha")])
        self.assertEqual(self.kinds(register(entry(evidence={"issues": [0]}))), [("bad-issue", "alpha")])

    def test_unknown_fields_are_refused(self):
        self.assertEqual(self.kinds(register(entry(extra=1))), [("unknown-field", "alpha")])

    def test_pages_must_exist_and_be_referenced(self):
        self.write("docs/compatibility/stray.md", "# Stray\n")
        self.assertEqual(self.kinds(register(entry())), [("orphan-page", "docs/compatibility/stray.md")])
        (self.root / "docs/compatibility/alpha.md").unlink()
        self.assertIn(("missing-page", "alpha"), self.kinds(register(entry())))
        self.assertIn(("missing-page", "alpha"), self.kinds(register(entry(page="docs/compatibility/other.md"))))

    def test_a_component_without_page_is_allowed(self):
        (self.root / "docs/compatibility/alpha.md").unlink()
        self.assertEqual(rc.validate(self.root, register(entry(page=None))), [])

    def test_duplicate_json_keys_are_refused(self):
        self.write(rc.REGISTER, '{"schemaVersion": 1, "schemaVersion": 1}')
        with self.assertRaises(ValueError):
            rc.read_json(self.root / rc.REGISTER)

    def test_exactly_one_matrix_section(self):
        self.assertIn(("schema", "sections"), self.kinds(register(entry(), sections=[{"id": "log", "heading": "Log", "summary": "x"}])))


class RenderTest(RepoCase):
    def test_render_has_notice_table_and_pages(self):
        text = rc.render(self.root, register(entry()))
        self.assertIn("Do not edit by hand", text)
        self.assertIn("| Component | Status | Summary | Evidence | Limitations |", text)
        row = next(line for line in text.splitlines() if line.startswith("| **Alpha"))
        self.assertIn("| implemented |", row)
        self.assertIn("[details](compatibility/alpha.md)", row)
        self.assertIn("`AlphaSuite`", row)
        self.assertIn("[alpha/README.md](../fixtures/alpha/README.md)", row)
        self.assertIn("[guide.md#second-part](guide.md#second-part)", row)
        self.assertIn("[#8](https://github.com/KD5UJC-XYZ/scarab/issues/8), roadmap L1", row)
        self.assertIn("- [Run log](compatibility/log.md)", text)
        self.assertIn("## Matrix", text)
        self.assertIn("## Log", text)

    def test_pipes_in_cells_are_escaped(self):
        text = rc.render(self.root, register(entry(component="A | B")))
        self.assertIn("**A \\| B**", text)

    def test_long_evidence_is_cut_with_a_count(self):
        suites = [f"Suite{i:03d}" for i in range(100)]
        cell = rc.evidence_cell({"evidence": {"suites": suites}, "page": "docs/compatibility/alpha.md"})
        self.assertLessEqual(len(cell), rc.EVIDENCE_CELL_CHARS + 40)
        self.assertRegex(cell, r"and \d+ more on the page$")
        shown = cell.count("`Suite")
        self.assertEqual(int(cell.rsplit("and ", 1)[1].split()[0]), 100 - shown)

    def test_empty_pointers_say_so(self):
        bare = {"id": "x", "component": "X", "status": "implemented", "summary": "s"}
        self.assertEqual(rc.evidence_cell(bare), "none recorded")
        self.assertEqual(rc.limitation_cell(bare), "none recorded")
        self.assertEqual(rc.limitation_cell({**bare, "page": "p"}), "see the page")

    def test_oversized_cell_is_an_error_not_truncated(self):
        self.save(register(entry(summary="x" * 700)))
        problems, rendered = rc.run(self.root, check=False)
        self.assertIsNone(rendered)
        self.assertEqual([p.kind for p in problems], [Kind.CELL_TOO_LONG])


class CheckModeTest(RepoCase):
    def test_check_fails_until_the_render_is_committed(self):
        self.save(register(entry()))
        problems, rendered = rc.run(self.root, check=True)
        self.assertEqual([p.kind for p in problems], [Kind.STALE_RENDER])
        self.write(rc.OUTPUT, rendered)
        self.assertEqual(rc.run(self.root, check=True), ([], rendered))

    def test_a_hand_edit_is_caught(self):
        self.save(register(entry()))
        _, rendered = rc.run(self.root, check=True)
        self.write(rc.OUTPUT, rendered + "hand edit\n")
        problems, _ = rc.run(self.root, check=True)
        self.assertEqual([p.kind for p in problems], [Kind.STALE_RENDER])

    def test_main_writes_then_checks(self):
        self.save(register(entry()))
        self.assertEqual(rc.main(["--root", str(self.root), "--check"]), 1)
        self.assertEqual(rc.main(["--root", str(self.root)]), 0)
        self.assertEqual(rc.main(["--root", str(self.root), "--check"]), 0)


class RepositoryTest(unittest.TestCase):
    """The committed register, pages and markdown agree."""

    def test_committed_register_validates_and_markdown_is_current(self):
        problems, _ = rc.run(ROOT, check=True)
        self.assertEqual([str(p) for p in problems], [])

    def test_every_component_page_and_cell_respects_the_budgets(self):
        register = rc.read_json(ROOT / rc.REGISTER)
        self.assertEqual(len({c["id"] for c in register["components"]}), len(register["components"]))
        for page in (ROOT / rc.PAGE_DIR).glob("*.md"):
            self.assertLessEqual(page.stat().st_size, 60_000, page.name)


if __name__ == "__main__":
    unittest.main()
