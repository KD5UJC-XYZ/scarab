# SPDX-License-Identifier: Apache-2.0
"""check_parameter_comments: no comment between the parameters or arguments of a list; the count only shrinks."""
from contextlib import redirect_stdout
from pathlib import Path
import io
import subprocess
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_parameter_comments as comments

SOURCE = 'mod/src/main/scala/A.scala'


def sites(text):
    return comments.sites(textwrap.dedent(text))


class ListsTest(unittest.TestCase):
    def test_definitions_constructors_and_calls(self):
        self.assertEqual(sites('''\
            def f(
                // why a
                a: Int,
                b: Int
            ): Int = a
            '''), [2])
        self.assertEqual(sites('''\
            final case class C(
                a: Int, // the a
                /** the b */
                b: Int
            )
            '''), [2, 3])
        self.assertEqual(sites('val x = f(a, /* b = */ 3)\n'), [1])
        self.assertEqual(sites('val x = f[Int](a, // a\n  b)\n'), [1])
        self.assertEqual(sites('val x = f(a)(b, // b\n  c)\n'), [1])
        self.assertEqual(sites('extension (a: Int // a\n)\n  def g = a\n'), [1])
        self.assertEqual(sites('libraryDependencies ++= Seq(\n  // why\n  a,\n  b\n)\n'), [2])

    def test_a_comment_that_ends_an_argument_before_the_closing_parenthesis(self):
        self.assertEqual(sites('val x = f(\n  a // the a\n)\n'), [2])
        self.assertEqual(sites('val x = f(\n  a = 1,\n  b = 2 // the b\n)\n'), [3])

    def test_a_run_of_line_comments_counts_once(self):
        self.assertEqual(sites('val x = f(\n  a,\n  // one\n  // two\n  b\n)\n'), [3])

    def test_bodies_inside_the_parentheses_are_not_lists(self):
        # a braced lambda, a lambda without braces, and its last line
        self.assertEqual(sites('val x = f(xs.map { x =>\n  // why\n  x\n})\n'), [])
        self.assertEqual(sites('val x = f(x =>\n  // why\n  x + 1\n)\n'), [])
        self.assertEqual(sites('val x = f(x =>\n  x + 1 // why\n)\n'), [])
        self.assertEqual(sites('val x = f(_ match\n  case 1 => 2\n  // why\n  case _ => 3\n)\n'), [])
        # an anonymous class with a colon body
        self.assertEqual(sites('''\
            Some(new Iterator[(Int, Int)]:
              // the entries to come
              private val left = 1
              def next = left // the next
            )
            '''), [])
        self.assertEqual(sites('val x = f {\n  // why\n  a\n}\n'), [])

    def test_an_if_then_inside_the_parentheses(self):
        self.assertEqual(sites('val x = f(\n  if a then\n    // why\n    b\n  else c\n)\n'), [])
        # the documented misfire: the comment that ends a body opened with no `=>` or `:` before a `)`
        self.assertEqual(sites('val x = f(\n  if a then b\n  else c // why\n)\n'), [3])

    def test_a_comment_between_chained_calls_explains_the_next_call(self):
        self.assertEqual(sites('''\
            val x = g(
              ws.traverse(read)
                // why the map
                .map(_.toList)
            )
            '''), [])

    def test_conditions_groupings_tuples_and_patterns_after_a_keyword(self):
        for text in ('if (\n  // why\n  a\n) b\n', 'while (a, // why\n  b) do ()\n',
                     'x match\n  case (a, // why\n    b) => a\n', 'val (a, // why\n  b) = t\n',
                     'val t = (1, // why\n  2)\n', 'val y = a + (b, // why\n  c)\n',
                     'for\n  a <- xs\n  (b, // why\n    c) <- ys\nyield a\n'):
            with self.subTest(text=text):
                self.assertEqual(sites(text), [])
        # the documented misfire: an extractor pattern reads as a call
        self.assertEqual(sites('x match\n  case Some(\n    // why\n    a) => a\n'), [3])

    def test_type_parameters_and_comments_after_the_list(self):
        self.assertEqual(sites('val x = f[\n  // why\n  A\n](a)\n'), [])
        self.assertEqual(sites('val x = f(a) // why\nval y = g(\n  b\n) // why\n'), [])

    def test_literals_are_not_code(self):
        self.assertEqual(sites('val x = f("(") // why\n'), [])
        self.assertEqual(sites("val x = f('(') // why\nval y = g(')', a, // a\n  b)\n"), [2])
        self.assertEqual(sites('val x = f("""(\n)) //""") // why\n'), [])
        self.assertEqual(sites('val x = f(s"${g(")")} (") // why\n'), [])
        self.assertEqual(sites('val x = f("// not a comment", a)\n'), [])


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
            code = comments.main(list(args), self.root)
        return code, out.getvalue()

    def test_a_new_comment_in_a_list_fails_and_the_baseline_only_shrinks(self):
        self.write(SOURCE, 'val x = f(a, // a\n  b)\n')
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.write('build.sbt', 'val s = Seq(\n  // why\n  a\n)\n')
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn(comments.RULE, out)
        self.assertIn('build.sbt: 0 -> 1', out)
        self.assertIn('build.sbt:2', out)
        self.write('build.sbt', '// why\nval s = Seq(a)\n')
        self.write(SOURCE, '// a: what it is\nval x = f(a, b)\n')
        code, out = self.run_check()
        self.assertEqual(code, 1, 'a removed site leaves no allowance to reuse')
        self.assertIn(f'{SOURCE}: 1 -> 0', out)
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.assertEqual(self.run_check()[0], 0)


if __name__ == '__main__':
    unittest.main()
