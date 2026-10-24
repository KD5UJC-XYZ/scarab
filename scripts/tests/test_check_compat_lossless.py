# SPDX-License-Identifier: Apache-2.0
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_compat_lossless as cl
from check_compat_lossless import Category

OLD = """# Compat

Intro with a link to [roadmap](roadmap.md) and a path `scripts/devnet.py`.

| Component | Status | Evidence | Limits |
|---|---|---|---|
| **Thing (#12)** | implemented | `FooSuite`, 1,234 cases, 8a9ea6b2 | see [#13](https://github.com/o/r/issues/13), fixtures/x/README.md |

## Evidence log

| Run | Result |
|---|---|
| `devnet.py relay` | passed, 99.5 percent, https://example.org/run/7 |
"""


class LosslessTest(unittest.TestCase):
    def losses(self, parts):
        return {(l.category, l.text) for l in cl.lost(OLD, parts)}

    def full_page(self):
        return ("Intro with a link to [roadmap](../roadmap.md) and a path `scripts/devnet.py`.\n"
                "- **Thing (#12)** implemented `FooSuite`, 1,234 cases, 8a9ea6b2 see [#13](https://github.com/o/r/issues/13), "
                "fixtures/x/README.md\n"
                "`devnet.py relay` passed, 99.5 percent, https://example.org/run/7\n")

    def test_nothing_lost_when_moved_verbatim_with_links_rebased(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "docs/compatibility").mkdir(parents=True)
        (root / "docs/compatibility/p.md").write_text(self.full_page(), encoding="utf-8")
        self.assertEqual(cl.lost(OLD, cl.corpus_parts(root)), [])

    def test_each_category_of_loss_is_reported(self):
        page = (self.full_page().replace("#12", "").replace("scripts/devnet.py", "").replace("FooSuite", "")
                .replace("1,234", "").replace("8a9ea6b2", "").replace("https://example.org/run/7", ""))
        found = self.losses([cl.resolve_links(page, "docs/compatibility")])
        categories = {c for c, _ in found}
        for expected in (Category.ISSUE, Category.PATH, Category.SPAN, Category.NUMBER, Category.COMMIT, Category.URL,
                         Category.CELL):
            self.assertIn(expected, categories)
        self.assertIn((Category.ISSUE, "#12"), found)
        self.assertIn((Category.URL, "https://example.org/run/7"), found)

    def test_a_dropped_row_and_a_dropped_line_are_caught(self):
        page = "Intro with a link to [roadmap](../roadmap.md) only.\n"
        found = self.losses([cl.resolve_links(page, "docs/compatibility")])
        self.assertTrue(any(c is Category.CELL and "devnet.py relay" in t for c, t in found))
        self.assertTrue(any(c is Category.LINE for c, _ in found))

    def test_a_link_to_another_target_is_a_lost_path(self):
        page = self.full_page().replace("../roadmap.md", "../other.md")
        found = self.losses([cl.resolve_links(page, "docs/compatibility")])
        self.assertIn((Category.PATH, "docs/roadmap.md"), found)

    def test_resolve_links(self):
        self.assertEqual(cl.resolve_links("[a](x.md#h) [b](../y/z.md) [c](https://e.org/q) [d](#h)", "docs"),
                         "[a](docs/x.md#h) [b](y/z.md) [c](https://e.org/q) [d](#h)")

    def test_json_issue_numbers_stand_for_references(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "docs/data").mkdir(parents=True)
        (root / "docs/data/compatibility.json").write_text(json.dumps(
            {"components": [{"evidence": {"issues": [12]}, "limitations": {"issues": [13]}}]}), encoding="utf-8")
        parts = cl.corpus_parts(root)
        self.assertIn("#12", parts)
        self.assertIn("#13", parts)


if __name__ == "__main__":
    unittest.main()
