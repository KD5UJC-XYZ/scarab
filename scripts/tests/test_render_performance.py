# SPDX-License-Identifier: Apache-2.0
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_performance as rp

ROOT = Path(__file__).resolve().parents[2]
FENCE = "`" * 3
REGISTER = {
    "pages": [{"file": "docs/performance/a.md", "title": "A page", "prefix": "a"}],
    "entries": [{
        "id": "perf-01", "section": "1", "heading": "1. First (#9)", "subject": "First (#9)", "date": "2026-10-01",
        "issues": ["#9"], "page": "a",
        "results": [{"id": "perf-01.t1", "header": ["n", "ms"], "separator": "|---|---:|", "rows": [["1", "2"], ["3", "4"]]},
                    {"id": "perf-01.t2", "raw": ["|a|", "|-|", "|b|"]}],
        "listings": [{"id": "perf-01.c1", "fence": FENCE + "bash", "lines": ["run it"], "close": FENCE}],
    }],
}


class RenderTest(unittest.TestCase):
    def test_regions_are_filled_from_the_register(self):
        page = "# A\n\n## 1. First (#9)\n<!-- register:table perf-01.t1 -->\nstale\n<!-- /register -->\n"
        text, problems = rp.rewrite("p.md", page, REGISTER, {})
        self.assertEqual([], problems)
        self.assertIn("| n | ms |\n|---|---:|\n| 1 | 2 |\n| 3 | 4 |\n<!-- /register -->", text)
        self.assertNotIn("stale", text)

    def test_listing_and_raw_table_render_verbatim(self):
        self.assertEqual([FENCE + "bash", "run it", FENCE], rp.listing_lines(REGISTER["entries"][0]["listings"][0]))
        self.assertEqual(["|a|", "|-|", "|b|"], rp.table_lines(REGISTER["entries"][0]["results"][1]))

    def test_unknown_and_unclosed_regions_are_problems(self):
        _, problems = rp.rewrite("p.md", "<!-- register:table nope -->\nx\n<!-- /register -->", REGISTER, {})
        self.assertEqual(1, len(problems))
        _, problems = rp.rewrite("p.md", "<!-- register:table perf-01.t1 -->\nx", REGISTER, {})
        self.assertEqual(1, len(problems))

    def test_log_row_links_to_the_page_anchor(self):
        page = "# A\n\n## 1. First (#9)\n"
        rows = rp.log_lines(REGISTER, {"docs/performance/a.md": page})
        self.assertEqual("| 1 | 2026-10-01 | First (#9) | #9 | [A page](performance/a.md#1-first-9) |", rows[2])


class RepositoryTest(unittest.TestCase):
    def test_committed_pages_match_the_register(self):
        register = json.loads((ROOT / rp.REGISTER).read_text(encoding="utf-8"))
        files, problems = rp.audit(ROOT, register)
        for path, text in files.items():
            new, found = rp.rewrite(path, text, register, files)
            problems += found
            self.assertEqual(text, new, f"{path} is out of date: run scripts/render_performance.py --write")
        self.assertEqual([], problems)


if __name__ == "__main__":
    unittest.main()
