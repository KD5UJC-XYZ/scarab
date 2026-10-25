# SPDX-License-Identifier: Apache-2.0
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_docs
from check_docs import Budgets, Entry, Kind, Repo, Violation

FENCE = "`" * 3


def build(files: dict[str, str]) -> tuple[tempfile.TemporaryDirectory, Repo]:
    tmp = tempfile.TemporaryDirectory()
    for name, content in files.items():
        path = Path(tmp.name) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    return tmp, Repo.discover(Path(tmp.name))


class DocsCase(unittest.TestCase):
    def collect(self, files: dict[str, str], budgets: Budgets = Budgets()) -> list[Violation]:
        tmp, repo = build(files)
        self.addCleanup(tmp.cleanup)
        return check_docs.collect(repo, budgets)

    def kinds(self, files: dict[str, str], budgets: Budgets = Budgets()) -> list[tuple[Kind, str]]:
        return [(v.kind, v.key) for v in self.collect(files, budgets)]


class LinkTest(DocsCase):
    def test_links_and_anchors_resolve_or_are_reported(self):
        files = {
            "README.md": "# Top\n[ok](docs/a.md) [gone](docs/missing.md) [anchor](docs/a.md#second-part)\n"
                         "[bad](docs/a.md#nope) [self](#top) [selfbad](#zzz) [web](https://x.org/#q)\n",
            "docs/a.md": "# A\n## Second part\n## Second part\n[up](../README.md#top) [dir](../docs/) [esc](../../outside.md)\n",
        }
        self.assertEqual(sorted(self.kinds(files), key=str), sorted([
            (Kind.BROKEN_LINK, "docs/missing.md"),
            (Kind.BROKEN_ANCHOR, "docs/a.md#nope"),
            (Kind.BROKEN_ANCHOR, "#zzz"),
            (Kind.BROKEN_LINK, "../../outside.md"),
        ], key=str))

    def test_duplicate_headings_get_numbered_slugs_and_punctuation_is_dropped(self):
        self.assertEqual(check_docs.anchors_of("# A `b` & C\n## x\n## x\n### Why? (yes) — now\n"),
                         frozenset({"a-b--c", "x", "x-1", "why-yes--now"}))

    def test_fenced_code_and_code_spans_are_not_links(self):
        text = f"# T\n{FENCE}\n[a](nope.md)\n{FENCE}\n`[b](nope.md)` and `docs/nothing/here.md`\n"
        self.assertEqual(self.kinds({"README.md": text, "docs/x.md": "x"}), [(Kind.MISSING_PATH, "docs/nothing/here.md")])


class PathTest(DocsCase):
    def test_only_repo_rooted_paths_are_checked(self):
        files = {
            "README.md": "`node/src/Foo.scala` `node/src/Gone.scala` `scripts/x.py:12` `scripts/*.py` `/tmp/scarab/x` "
                         "`libs/upstream/Foo.hs` `node/src/...` `.workspace/notes/a.md` `Foo.scala` `Gone.scala`\n",
            "node/src/Foo.scala": "object Foo",
            "scripts/x.py": "",
        }
        self.assertEqual(sorted(self.kinds(files), key=str), sorted([
            (Kind.MISSING_PATH, "node/src/Gone.scala"),
            (Kind.MISSING_PATH, "Gone.scala"),
            (Kind.LINE_CITATION, "scripts/x.py:12"),
        ], key=str))


class SymbolTest(DocsCase):
    FILES = {
        "node/A.scala": "object Pool { def claim = 1 }\nobject Other",
        "node/B.scala": "object Elsewhere { def member = 2 }",
    }

    def test_symbols_must_co_occur_in_one_source_file(self):
        doc = "`Pool.claim` `Pool.claim()` `Pool.member` `Elsewhere.member` `Pool.gone(x)` `Cardano.Ledger.Foo`\n"
        self.assertEqual(sorted(self.kinds({"README.md": doc, **self.FILES}), key=str), sorted([
            (Kind.MISSING_SYMBOL, "Pool.member"),
            (Kind.MISSING_SYMBOL, "Pool.gone"),
        ], key=str))

    def test_upstream_module_paths_and_file_extensions_are_not_candidates(self):
        self.assertIsNone(check_docs.is_symbol_candidate("Ouroboros.Consensus.Block"))
        self.assertIsNone(check_docs.is_symbol_candidate("Foo.Bar.Baz"))
        self.assertIsNone(check_docs.is_symbol_candidate("Foo.json"))
        self.assertIsNone(check_docs.is_symbol_candidate("lowercase.member"))
        self.assertEqual(check_docs.is_symbol_candidate("Foo.bar()"), ["Foo", "bar"])
        self.assertEqual(check_docs.is_symbol_candidate("xyz.kd5ujc.scarab.node.Main"),
                         ["xyz", "kd5ujc", "scarab", "node", "Main"])


class LineCitationTest(DocsCase):
    def test_citations_into_repo_files_are_counted_and_unknown_files_ignored(self):
        files = {
            "README.md": "See `Foo.scala:12`, [x](node/Foo.scala#L9-L12), node/Foo.scala:3-4 and `Upstream.hs:77`, Gone.scala:5.\n",
            "node/Foo.scala": "object Foo",
        }
        cited = [v.key for v in self.collect(files) if v.kind is Kind.LINE_CITATION]
        self.assertEqual(sorted(cited), ["Foo.scala:12", "node/Foo.scala:3", "node/Foo.scala:9"])


class BudgetTest(DocsCase):
    def test_file_and_cell_budgets(self):
        text = "| a | b |\n|---|---|\n| short | " + "x" * 30 + " |\n"
        rows = self.collect({"README.md": text}, Budgets(max_file_bytes=40, max_cell_chars=20))
        self.assertEqual([(v.kind, v.measure) for v in rows],
                         [(Kind.FILE_SIZE, len(text.encode())), (Kind.TABLE_CELL, 30)])

    def test_only_the_size_kinds_are_report_only(self):
        self.assertEqual({k for k in Kind if not k.blocking}, {Kind.FILE_SIZE, Kind.TABLE_CELL})
        self.assertEqual(check_docs.warnings_of([Violation(Kind.BROKEN_LINK, "a.md", "x", 1)]), [])
        self.assertEqual(check_docs.warnings_of([Violation(Kind.TABLE_CELL, "a.md", "x", 3, 30)]),
                         ["warning: a.md:3: table-cell: x (30)"])

    def test_pipes_in_code_spans_and_escapes_do_not_split_cells(self):
        self.assertEqual(check_docs.split_cells("| `a|b` | c\\|d | e |"), ["`a|b`", "c\\|d", "e"])


class PinTest(DocsCase):
    PINS = {"schemaVersion": 1, "pins": [
        {"name": "cardano-node", "version": "11.1.3", "dependents": ["scripts/ref.py"]},
        {"name": "plutus", "aliases": ["plutus-core"], "revision": "507cab8cf6192e5143f12be5b2d3005a2928dc6d",
         "dependents": ["scripts/ref.py"]},
    ]}
    FILES = {"docs/data/reference-pins.json": json.dumps(PINS), "scripts/ref.py": "11.1.3 507cab8cf6192e5143f12be5b2d3005a2928dc6d"}

    def test_no_registry_means_no_pin_checks(self):
        self.assertEqual(self.kinds({"README.md": "cardano-node 1.2.3 plutus commit 507cab8c\n"}), [])

    def test_unregistered_versions_and_commits_are_reported(self):
        doc = ("cardano-node 11.1.3 ok, cardano-node 10.0.1 stale. plutus commit `507cab8c` ok, plutus `deadbee1` stale.\n"
               "[c](https://github.com/IntersectMBO/cardano-ledger/commit/0123456789abcdef)\nand `deadbee1` with no repo named.\n")
        self.assertEqual(sorted(self.kinds({"README.md": doc, **self.FILES}), key=str), sorted([
            (Kind.UNREGISTERED_PIN, "cardano-node 10.0.1"),
            (Kind.UNREGISTERED_PIN, "plutus@deadbee1"),
            (Kind.UNREGISTERED_PIN, "cardano-ledger@0123456789abcdef"),
        ], key=str))

    def test_registry_defects(self):
        pins = {"schemaVersion": 1, "pins": [
            {"name": "a", "version": "1.0", "dependents": ["gone.py"]},
            {"name": "b", "version": "2.0", "dependents": ["scripts/ref.py"]},
        ]}
        keys = [v.key for v in self.collect({"docs/data/reference-pins.json": json.dumps(pins), "scripts/ref.py": "x"})]
        self.assertEqual(keys, ["a 1.0: dependent gone.py does not exist", "b 2.0: dependent scripts/ref.py does not mention it"])

    def test_malformed_registry_is_an_error(self):
        with self.assertRaises(check_docs.PinRegistryError):
            self.collect({"docs/data/reference-pins.json": json.dumps({"pins": [{"name": "x"}]}), "README.md": ""})


class RatchetTest(unittest.TestCase):
    A = (Kind.MISSING_PATH, "README.md", "x/y")
    B = (Kind.BROKEN_LINK, "docs/a.md", "gone.md")

    def test_equal_is_ok_new_and_growth_fail(self):
        base = {self.A: Entry(1), self.B: Entry(1)}
        self.assertTrue(check_docs.ratchet(base, base).ok)
        grown = check_docs.ratchet({self.A: Entry(2), self.B: Entry(1)}, base)
        self.assertEqual(len(grown.new), 1)
        self.assertEqual(grown.stale, ())
        extra = check_docs.ratchet({**base, (Kind.BROKEN_LINK, "README.md", "z"): Entry(1)}, base)
        self.assertEqual(len(extra.new), 1)

    def test_fixed_entries_make_the_baseline_stale(self):
        base = {self.A: Entry(2), self.B: Entry(1)}
        result = check_docs.ratchet({self.A: Entry(1), self.B: Entry(1)}, base)
        self.assertEqual(result.new, ())
        self.assertEqual(len(result.stale), 1)
        self.assertEqual(len(check_docs.ratchet({}, base).stale), 2)

    def test_size_violations_are_not_part_of_the_ratcheted_state(self):
        size = [Violation(Kind.FILE_SIZE, "docs/a.md", "", measure=100), Violation(Kind.TABLE_CELL, "docs/a.md", "c", 4, 700)]
        self.assertEqual(check_docs.summarise(size), {})
        self.assertEqual(check_docs.summarise([*size, Violation(Kind.BROKEN_LINK, "docs/a.md", "gone.md", 2)]),
                         {self.B: Entry(1)})

    def test_branch_names_are_not_paths(self):
        tmp, repo = build({"release/x.json": "", "README.md": "`release/alpha` `release/gone`\n"})
        self.addCleanup(tmp.cleanup)
        self.assertEqual([v.key for v in check_docs.collect(repo, Budgets())], ["release/gone"])

    def test_baseline_round_trips(self):
        baseline = check_docs.Baseline(Budgets(5, 6), {self.A: Entry(2), self.B: Entry(1)})
        self.assertEqual(check_docs.Baseline.parse(baseline.render()), baseline)

    def test_report_only_entries_of_an_older_baseline_are_ignored(self):
        old = json.dumps({"schemaVersion": 1, "budgets": {"maxFileBytes": 5, "maxCellChars": 6}, "entries": [
            {"kind": "file-size", "path": "docs/a.md", "key": "", "count": 1, "measure": 100},
            {"kind": "broken-link", "path": "docs/a.md", "key": "gone.md", "count": 1},
        ]})
        self.assertEqual(check_docs.Baseline.parse(old).entries, {self.B: Entry(1)})


class EndToEndTest(unittest.TestCase):
    def run_main(self, root: str, *extra: str) -> int:
        return check_docs.main(["--root", root, *extra])

    def test_write_baseline_then_check_then_new_violation_then_fix(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "README.md").write_text("[a](gone.md)\n")
        (root / "docs").mkdir()
        self.assertEqual(self.run_main(tmp.name), 1)
        self.assertEqual(self.run_main(tmp.name, "--write-baseline"), 0)
        self.assertEqual(self.run_main(tmp.name), 0)
        (root / "README.md").write_text("[a](gone.md) [b](gone2.md)\n")
        self.assertEqual(self.run_main(tmp.name), 1)
        (root / "README.md").write_text("fixed\n")
        self.assertEqual(self.run_main(tmp.name), 1, "the baseline holds an entry that no longer occurs")
        self.assertEqual(self.run_main(tmp.name, "--write-baseline"), 0)
        self.assertEqual(json.loads((root / check_docs.BASELINE_PATH).read_text())["entries"], [])
        self.assertEqual(self.run_main(tmp.name), 0)


    def test_size_violations_warn_without_failing(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "README.md").write_text("| a |\n|---|\n| " + "x" * 30 + " |\n")
        self.assertEqual(self.run_main(tmp.name, "--max-file-bytes", "10", "--max-cell-chars", "20"), 0)
        self.assertEqual(self.run_main(tmp.name, "--max-file-bytes", "10", "--max-cell-chars", "20", "--write-baseline"), 0)
        self.assertEqual(json.loads((root / check_docs.BASELINE_PATH).read_text())["entries"], [])
        self.assertEqual(self.run_main(tmp.name), 0)
        (root / "README.md").write_text("| a |\n|---|\n| " + "x" * 30 + " |\n[a](gone.md)\n")
        self.assertEqual(self.run_main(tmp.name), 1, "a blocking violation still fails next to size warnings")


if __name__ == "__main__":
    unittest.main()
