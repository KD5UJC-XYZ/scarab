# SPDX-License-Identifier: Apache-2.0
"""check_pinned_citations: a Scala comment that cites a Haskell file and line names its pin; the count only shrinks."""
from contextlib import redirect_stdout
from pathlib import Path
import io
import json
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_pinned_citations as citations

SOURCE = 'mod/src/main/scala/A.scala'


class UnpinnedTest(unittest.TestCase):
    def test_a_citation_without_a_pin_counts_on_its_own_line(self):
        text = 'object A:\n  /** The rule.\n    *\n    * `applyRule` (`Rules/Utxo.hs:170-192`).\n    */\n  val a = 1\n'
        self.assertEqual(citations.unpinned(text), [4])
        self.assertEqual(citations.unpinned('// `Foo.hs:12`\nval a = 1\n'), [1])
        self.assertEqual(citations.unpinned('// Codec.hs:53–63, en dash\n'), [1])

    def test_every_pin_form_of_the_tree_counts(self):
        for pin in ('cardano-ledger f649f975', 'c45735a5:', '@f649f975', '@ 8e2bba9442cb388089f53d708cc4aedcb47726ea',
                    'plutus-1.70.0.0', 'ghc-9.6', 'cardano-node 11.1.3', 'GHC 9.6.7', 'aeson 2.2.5.0', '@ 11.1.3'):
            with self.subTest(pin=pin):
                self.assertEqual(citations.unpinned(f'// `Foo.hs:12` ({pin})\n'), [])

    def test_pins_as_the_tree_writes_them_in_prose(self):
        # CheckpointsFile, Vocabulary, Interns and FlatReferenceCorporaSuite: a full stop, backquotes, a line break
        for text in ('/** Cardano/Node/Protocol/Checkpoints.hs:56–114 at the pinned cardano-node 11.1.3. */\n',
                     '/** `WithOrigin a` (cardano-slotting `Slot.hs:71-82`).\n  * Source version: cardano-slotting\n'
                     '  * 0.2.2.0.\n  */\n',
                     '/** (`Sharing.hs:53-66,\n  * 111-135` of `cardano-ledger-binary` 1.9.1.0).\n  */\n',
                     '/** the pinned Haskell reference (plutus-ledger-api\n  * 1.70.0.0, `Codec.hs:1`), row by row\n  */\n',
                     '// `Foo.hs:12` (ouroboros-network\n// 2fb69829)\n'):
            with self.subTest(text=text):
                self.assertEqual(citations.unpinned(text), [])

    def test_what_is_not_a_pin(self):
        for word in ('4294967295', 'defaced', 'version 1.1', 'the pinned revision', 'section 3.1',
                     '0123456789abcdef0123456789abcdef0123456789abcdef', '0b10001110',
                     'ouroboros-consensus-cardano 4.2.0.x'):
            with self.subTest(word=word):
                self.assertEqual(citations.unpinned(f'// `Foo.hs:12`, {word}\n'), [1])
        # Availability and TextError: the version of a UPLC program, not a release
        self.assertEqual(citations.unpinned('/** `constr` in a program older than 1.1.0 (`Flat.hs:167-171`). */\n'), [1])
        self.assertEqual(citations.unpinned('// a program of version 1.2.0 or later (`Parser.hs:40`)\n'), [1])

    def test_every_citation_spelling_of_the_tree(self):
        for citation in ('`Foo.hs:12`', '`Foo.hs: 12`', 'Foo.hs:L12', 'Foo.hs#L12', '`Foo.hs` lines 12-34',
                         'ExBudgetMode.hs lines 126-172', '`Conway/PParams.hs` line 1095', 'Foo.hs, lines 3-4'):
            with self.subTest(citation=citation):
                self.assertEqual(citations.unpinned(f'// {citation}\n'), [1])

    def test_a_run_of_line_comments_is_one_block(self):
        self.assertEqual(citations.unpinned('// `Foo.hs:12` and `Bar.hs:3`\n// (cardano-ledger f649f975)\n'), [])
        # one block, counted once
        self.assertEqual(citations.unpinned('// `Foo.hs:12`\n//   and `Bar.hs:3`\n'), [1])
        # a blank line, code or another kind of comment ends the block
        self.assertEqual(citations.unpinned('// `Foo.hs:12`\n\n// cardano-ledger f649f975\n'), [1])
        self.assertEqual(citations.unpinned('// `Foo.hs:12`\nval a = 1\n// cardano-ledger f649f975\n'), [1])
        self.assertEqual(citations.unpinned('// `Foo.hs:12`\n/* cardano-ledger f649f975 */\n'), [1])

    def test_a_pin_elsewhere_in_the_file_does_not_count(self):
        text = ('/** Citations are at cardano-ledger f649f975. */\nobject A:\n'
                '  // `Foo.hs:12`\n  val a = 1\n  /** `Bar.hs:3` */\n  val b = 2\n')
        self.assertEqual(citations.unpinned(text), [3, 5])

    def test_the_spdx_licence_is_not_a_pin(self):
        self.assertEqual(citations.unpinned('// SPDX-License-Identifier: Apache-2.0\n// `Foo.hs:12`\n'), [2])

    def test_literals_and_other_citations_are_not_read(self):
        text = ('val a = "Foo.hs:12"\nval b = """\n// Foo.hs:12\n"""\nval c = s"${x} // Foo.hs:12"\n'
                '// `Foo.lhs:12`, `Spec.agda:3`, `Foo.hs` with no line, `foo.cabal:4`\n')
        self.assertEqual(citations.unpinned(text), [])


class BaselineTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / 'docs/data').mkdir(parents=True)

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)
        subprocess.run(['git', '-C', str(self.root), 'add', path], check=True)

    def run_check(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            code = citations.main(list(args), self.root)
        return code, out.getvalue()

    def test_a_new_unpinned_citation_fails_with_the_rule_and_every_site_of_its_file(self):
        self.write(SOURCE, '// `Foo.hs:1`\nval a = 1\n')
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.assertEqual(self.run_check()[0], 0)
        self.write(SOURCE, '// `Foo.hs:1`\nval a = 1\n// `Bar.hs:2`\n')
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn(citations.RULE, out)
        self.assertIn(f'{SOURCE}: 1 -> 2', out)
        self.assertIn(f'{SOURCE}:1', out)
        self.assertIn(f'{SOURCE}:3', out)
        # pinning the new one is enough
        self.write(SOURCE, '// `Foo.hs:1`\nval a = 1\n// `Bar.hs:2`, cardano-ledger f649f975\n')
        self.assertEqual(self.run_check()[0], 0)

    def test_the_baseline_only_shrinks(self):
        self.write(SOURCE, '// `Foo.hs:1`\nval a = 1\n// `Bar.hs:2`\n')
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.write(SOURCE, '// `Foo.hs:1`\n')
        code, out = self.run_check()
        self.assertEqual(code, 1, 'a removed site leaves no allowance to reuse')
        self.assertIn('--write-baseline', out)
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        baseline = json.loads((self.root / citations.BASELINE.path).read_text())
        self.assertEqual(baseline['files'], {SOURCE: 1})
        self.write(SOURCE, '// `Foo.hs:1`\nval a = 1\n// `Bar.hs:2`\n')
        code, out = self.run_check('--write-baseline')
        self.assertEqual(code, 1, '--write-baseline refuses an increase')

    def test_scope_is_tracked_scala_and_sbt_sources_but_the_fixture_archives(self):
        self.write('build.sbt', '// `Foo.hs:1`\n')
        self.write('mod/src/test/scala/ASuite.scala', '// `Foo.hs:1`\n')
        self.write('fixtures/witness/archive/Verify.scala', '// `Foo.hs:1`\n')
        (self.root / 'Untracked.scala').write_text('// `Foo.hs:1`\n')
        self.assertEqual(sorted(citations.current(self.root)), ['build.sbt', 'mod/src/test/scala/ASuite.scala'])


if __name__ == '__main__':
    unittest.main()
