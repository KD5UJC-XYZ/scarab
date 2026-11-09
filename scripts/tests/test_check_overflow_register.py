# SPDX-License-Identifier: Apache-2.0
"""check_overflow_register: wrap sites in main sources and the rows of the overflow register agree."""
from pathlib import Path
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_overflow_register as register

MAIN = 'mod/src/main/scala/A.scala'
PAGE = '# Register\n\n| <a id="first-row"></a>First | x |\n| <a id="second-row"></a>Second | y |\n'


class OverflowRegisterTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.write(register.PAGE, PAGE)

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def messages(self):
        return [v.message for v in register.check(self.root)]

    def both_rows(self, extra=''):
        self.write(MAIN, '// overflow-improvements.md#first-row\n'
                         '// overflow-improvements.md#second-row\n' + extra)

    def test_every_row_named_and_no_idiom_passes(self):
        self.both_rows('val x = 1\n')
        self.assertEqual(self.messages(), [])
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(register.main([], self.root), 0)

    def test_a_reference_to_no_row_fails(self):
        self.both_rows('// overflow-improvements.md#third-row\n')
        self.assertEqual(len(self.messages()), 1)
        self.assertIn('"third-row"', self.messages()[0])

    def test_a_row_no_source_names_fails(self):
        self.write(MAIN, '// overflow-improvements.md#first-row\n')
        self.assertEqual(self.messages(), ['row "second-row" is named by no main source'])

    def test_a_modulus_without_a_row_fails_and_with_one_passes(self):
        idiom = 'val y = (a + b).mod(BigInt(1) << 64)\n'
        self.both_rows('\n' * 20 + idiom)
        self.assertEqual(self.messages(), ['a modular idiom names no row of ' + register.PAGE])
        self.both_rows('\n' * 20 + '// the reference wraps (overflow-improvements.md#first-row)\n' + idiom)
        self.assertEqual(self.messages(), [])

    def test_each_code_idiom_is_seen(self):
        for line in ('private val Word64Modulus = BigInt(1) << 64',
                     'Word64.wrappingAdd(a, b)',
                     'val m = n.mod(BigInt(2).pow(32))',
                     'val p = (c + o) & UInt64.Max'):
            with self.subTest(line=line):
                self.both_rows('\n' * 20 + line + '\n')
                self.assertEqual(len(self.messages()), 1)

    def test_a_comment_on_a_machine_word_wrap_needs_a_row_in_its_block(self):
        self.both_rows('\n/** The sum is a `Word64`, which wraps. */\n')
        self.assertEqual(self.messages(), ['a comment on a wrap names no row of ' + register.PAGE])
        self.both_rows('\n/** The sum is a `Word64`, which wraps\n  * (overflow-improvements.md#second-row). */\n')
        self.assertEqual(self.messages(), [])
        self.both_rows('\n// taken modulo 2^64 here\n')
        self.assertEqual(len(self.messages()), 1)

    def test_other_wraps_are_not_sites(self):
        self.both_rows('\n// the transaction wrapped in its era\nval b = Bytes.wrap(a)\n'
                       '// a wrapper of a Word64 codec\n// modulo 2^256, a digest\n')
        self.assertEqual(self.messages(), [])

    def test_an_allowed_line_passes(self):
        path, text = sorted(register.ALLOWED)[0]
        self.both_rows()
        self.write(path, '/** a `Word64` */\n' + text + '\n')
        self.assertEqual(self.messages(), [])

    def test_a_missing_page_fails(self):
        (self.root / register.PAGE).unlink()
        self.assertEqual(self.messages(), ['the overflow register is missing'])

    def test_the_repository_passes(self):
        self.assertEqual([str(v) for v in register.check(register.ROOT)], [])


if __name__ == '__main__':
    unittest.main()
