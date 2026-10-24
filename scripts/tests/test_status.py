# SPDX-License-Identifier: Apache-2.0
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import status

ROADMAP = """\
- **B1** [#3](https://github.com/o/r/issues/3) (done) see [#9](https://github.com/o/r/issues/9) for more
- **REF-02/03** [#164](https://github.com/o/r/issues/164), [#165](https://github.com/o/r/issues/165) (done)
- **C1** (planned) prose cites [#7](https://github.com/o/r/issues/7)
"""


def issue(number, title, *labels):
    return {"number": number, "title": title, "labels": [{"name": n} for n in labels]}


RAW = [
    issue(164, "Context translation for V1 scripts across every era and protocol version", "area:plutus", "priority:P1", "status:backlog"),
    issue(3, "Tie break", "area:networking", "priority:P0", "status:in-progress"),
    issue(7, "No labels but a roadmap line"),
    issue(50, "Another network item", "area:networking", "priority:P2"),
]


class RoadmapCase(unittest.TestCase):
    def test_leading_links_and_fallback(self):
        ids = status.roadmap_ids(ROADMAP)
        self.assertEqual(ids[3], "B1")
        self.assertEqual((ids[164], ids[165]), ("REF-02/03", "REF-02/03"))
        self.assertEqual(ids[9], "B1")
        self.assertEqual(ids[7], "C1")
        self.assertNotIn(50, ids)


class DigestCase(unittest.TestCase):
    def setUp(self):
        self.items = status.digest(RAW, status.roadmap_ids(ROADMAP))

    def test_fields_and_defaults(self):
        by_number = {d.number: d for d in self.items}
        self.assertEqual(by_number[164], status.Digest(164, "P1", "backlog", "plutus", "REF-02/03", RAW[0]["title"]))
        self.assertEqual((by_number[7].priority, by_number[7].status, by_number[7].area), ("-", "-", status.NO_AREA))
        self.assertEqual(by_number[50].roadmap, "-")

    def test_sorted_by_area_priority_number(self):
        self.assertEqual([d.number for d in self.items], [3, 50, 7, 164])

    def test_select(self):
        self.assertEqual([d.number for d in status.select(self.items, "networking", None)], [3, 50])
        self.assertEqual([d.number for d in status.select(self.items, "area:networking", "priority:p0")], [3])
        self.assertEqual([d.number for d in status.select(self.items, None, "P1")], [164])
        self.assertEqual(status.select(self.items, "ops", None), [])

    def test_render_groups_and_truncates(self):
        lines = status.render_text(self.items, 60).splitlines()
        self.assertEqual([l for l in lines if not l.startswith(" ")], ["networking (2)", "none (1)", "plutus (1)"])
        self.assertTrue(all(len(l) <= 60 for l in lines))
        long_line = next(l for l in lines if "#164" in l)
        self.assertTrue(long_line.endswith("..."))
        self.assertIn("REF-02/03", long_line)

    def test_render_empty(self):
        self.assertEqual(status.render_text([], 80), "")


class CliCase(unittest.TestCase):
    def run_main(self, *args):
        buffer = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(buffer):
            source = Path(tmp) / "issues.json"
            source.write_text(json.dumps(RAW), encoding="utf-8")
            roadmap = Path(tmp) / "roadmap.md"
            roadmap.write_text(ROADMAP, encoding="utf-8")
            code = status.main(["--from", str(source), "--roadmap", str(roadmap), *args])
        self.assertEqual(code, 0)
        return buffer.getvalue()

    def test_json_offline_with_filters(self):
        out = json.loads(self.run_main("--json", "--area", "networking", "--priority", "P0"))
        self.assertEqual([(d["number"], d["roadmap"]) for d in out], [(3, "B1")])

    def test_text_offline(self):
        out = self.run_main("--width", "80")
        self.assertIn("plutus (1)", out)
        self.assertIn("#3", out)


if __name__ == "__main__":
    unittest.main()
