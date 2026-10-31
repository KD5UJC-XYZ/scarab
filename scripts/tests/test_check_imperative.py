# SPDX-License-Identifier: Apache-2.0
"""check_imperative: try/catch/finally in main Scala sources may only go down."""
from pathlib import Path
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_imperative as imperative

MAIN = 'mod/src/main/scala/A.scala'


class ImperativeTest(unittest.TestCase):
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
            code = imperative.main(list(args), self.root)
        return code, out.getvalue()

    def test_comments_and_literals_do_not_count(self):
        text = '// try this\n/* finally */\nval s = "catch"\nval t = """try\nfinally"""\n'
        self.assertEqual(imperative.counts(text), {})
        self.assertEqual(imperative.counts('try f() catch { case _ => () } finally g()'),
                         {'try': 1, 'catch': 1, 'finally': 1})

    def test_code_inside_interpolation_and_around_comments_counts(self):
        self.assertEqual(imperative.counts('val s = s"${try work() finally cleanup()}"'), {'try': 1, 'finally': 1})
        self.assertEqual(imperative.counts('try/* x */work() finally/* y */cleanup()'), {'try': 1, 'finally': 1})
        self.assertEqual(imperative.counts('val s = "try ${x}"; val t = s"plain $x"'), {})

    def test_braces_in_comments_and_literals_do_not_end_an_interpolation(self):
        both = {'try': 1, 'finally': 1}
        self.assertEqual(imperative.counts('s"${ /* } */ try work() finally cleanup() }"'), both)
        self.assertEqual(imperative.counts("s\"${ f('}') ; try work() finally cleanup() }\""), both)
        self.assertEqual(imperative.counts('s"${ g("}") ; try work() finally cleanup() }"'), both)
        self.assertEqual(imperative.counts('s"${ s"${ "}" }" ; try work() finally cleanup() }"'), both)
        self.assertEqual(imperative.counts('s"${ Map(1 -> {2}) } try"'), {})

    def test_a_backslash_does_not_hide_an_interpolation(self):
        self.assertEqual(imperative.counts('raw"C:\\${try 1 catch { case _: Exception => 0 } finally ()}"'),
                         {'try': 1, 'catch': 1, 'finally': 1})
        self.assertEqual(imperative.counts('s"a\\"try" ; val b = "\\${try}"'), {})

    def test_a_dollar_quote_is_text(self):
        self.assertEqual(imperative.counts('val s = s"$"try$""'), {})
        self.assertEqual(imperative.counts('val s = s"$"ok$" ${try 1 catch { case _: Exception => 0 } finally ()}"'),
                         {'try': 1, 'catch': 1, 'finally': 1})

    def test_block_comments_nest(self):
        self.assertEqual(imperative.counts('s"${ /* outer /* inner */ } */ try 1 finally () }"'),
                         {'try': 1, 'finally': 1})
        self.assertEqual(imperative.counts('s"${ /* outer /* inner */ try finally */ 1 }"'), {})
        self.assertEqual(imperative.counts('/* a /* b */ try */ try f() finally g()'), {'try': 1, 'finally': 1})

    def test_a_new_try_fails_and_only_main_sources_count(self):
        self.write(MAIN, 'object A\n')
        self.write('mod/src/test/scala/T.scala', 'try f() finally g()\n')
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.write(MAIN, 'object A { try f() finally g() }\n')
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn(f'{MAIN}: try 0 -> 1', out)

    def test_the_baseline_only_shrinks(self):
        self.write(MAIN, 'object A { try f() finally g(); try h() finally i() }\n')
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.write(MAIN, 'object A { try f() finally g() }\n')
        # removing code without shrinking the baseline fails, so the old allowance cannot be reused
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn('--write-baseline', out)
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.write(MAIN, 'object A { try f() finally g(); try h() finally i() }\n')
        code, out = self.run_check('--write-baseline')
        self.assertEqual(code, 1)
        self.assertIn('try 1 -> 2', out)


if __name__ == '__main__':
    unittest.main()
