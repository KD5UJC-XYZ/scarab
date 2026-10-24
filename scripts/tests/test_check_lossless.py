# SPDX-License-Identifier: Apache-2.0
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_lossless
from check_lossless import Category

FENCE = "`" * 3
OLD = f"""# Doc

Prose line with `Symbol.member` and #123 and https://example.org/x and 1,234 blocks, commit abcdef1.

| a | b |
|---|---|
| cell one | 53.8% |

See [other](../adr/0001.md) and [self](#part).

{FENCE}
python3 scripts/run.py --flag
{FENCE}
"""


def compare(old: str, *new: str, old_path: str = "docs/doc.md", new_base: str | None = None):
    base = old_path.rpartition("/")[0] if new_base is None else new_base
    return check_lossless.compare(old, old_path, [check_lossless.resolve_links(n, base) for n in new])


class CompareTest(unittest.TestCase):
    def test_identical_text_loses_nothing(self):
        report = compare(OLD, OLD)
        self.assertEqual((), report.losses)
        self.assertEqual((), report.duplicates)

    def test_split_across_pages_loses_nothing(self):
        report = compare(OLD, "Prose line with `Symbol.member` and #123 and https://example.org/x and 1,234 blocks, "
                              "commit abcdef1.\nSee [other](../adr/0001.md) and [self](#part).\n",
                         "| cell one | 53.8% |\npython3 scripts/run.py --flag\n")
        self.assertEqual((), report.losses)

    def test_missing_tokens_are_losses_by_category(self):
        report = compare(OLD, "Prose line only.")
        lost = {f.category for f in report.losses}
        self.assertTrue({Category.ISSUE, Category.SPAN, Category.URL, Category.NUMBER, Category.CELL,
                         Category.LINE, Category.COMMIT} <= lost)

    def test_command_in_a_fence_must_survive(self):
        report = compare(OLD, OLD.replace("--flag", "--other"))
        self.assertIn("python3 scripts/run.py --flag", [f.text for f in report.losses if f.category is Category.LINE])

    def test_spans_never_cross_a_fence(self):
        old = f"a `x`\n{FENCE}\ncmd `y\n{FENCE}\nb `z`\n"
        report = compare(old, f"a `x`\n\n<!-- m -->\n{FENCE}\ncmd `y\n{FENCE}\nb `z`\n")
        self.assertEqual((), report.losses)

    def test_dropped_count_is_a_duplicate_not_a_loss(self):
        report = compare("`x` and `x`\n", "`x` and\n`x`\n")
        self.assertEqual((), report.losses)
        self.assertEqual((), report.duplicates)
        report = compare("`x`\n`x`\n", "`x`\n")
        self.assertEqual((), report.losses)
        self.assertEqual([(Category.SPAN, "x", 2, 1)],
                         [(f.category, f.text, f.old_count, f.new_count) for f in report.duplicates])

    def test_links_compare_after_resolution(self):
        old = "[a](../adr/0001.md)\n"
        self.assertEqual((), compare(old, "[a](../adr/0001.md)\n", old_path="docs/design/x.md").losses)
        self.assertEqual("[a](docs/adr/0001.md)", check_lossless.resolve_links("[a](../../adr/0001.md)", "docs/design/x"))
        report = compare(old, "[a](../../adr/0001.md)\n", old_path="docs/design/x.md", new_base="docs/design/x")
        self.assertEqual([], [f for f in report.losses if f.category is Category.PATH])

    def test_code_that_looks_like_a_link_is_not_resolved(self):
        text = f"`[t](Severity)` and [real](x.md)\n{FENCE}\n[t](Warning)\n{FENCE}\n"
        resolved = check_lossless.resolve_links(text, "docs/running")
        self.assertIn("`[t](Severity)`", resolved)
        self.assertIn("[real](docs/running/x.md)", resolved)
        self.assertIn("\n[t](Warning)\n", resolved)

    def test_kept_old_path_is_not_a_loss(self):
        report = check_lossless.compare("[me](x.md#a)\n", "docs/x.md", ["nothing\n"], frozenset({"docs/x.md"}))
        self.assertEqual([], [f for f in report.losses if f.category is Category.PATH])


class FilesTest(unittest.TestCase):
    def test_json_numbers_and_strings_are_part_of_the_corpus(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "docs/data").mkdir(parents=True)
        (root / "docs/data/r.json").write_text(json.dumps({"a": ["53.8%", 526726, {"k": "#9"}]}), encoding="utf-8")
        (root / "docs/p.md").write_text("see [d](data/r.json)\n", encoding="utf-8")
        files = check_lossless.new_files(root, [Path("docs")])
        joined = "\n".join(check_lossless.corpus_parts(root, files))
        self.assertIn("53.8%", joined)
        self.assertIn("526726", joined)
        self.assertIn("docs/data/r.json", joined)

    def test_missing_new_path_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileNotFoundError):
                check_lossless.new_files(Path(tmp), [Path("nope")])


if __name__ == "__main__":
    unittest.main()
