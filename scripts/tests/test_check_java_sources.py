# SPDX-License-Identifier: Apache-2.0
"""check_java_sources: the Java main sources are a recorded list, and their throw statements only go down."""
from contextlib import redirect_stdout
from pathlib import Path
import io
import json
import subprocess
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_java_sources as java

MAIN = 'mod/src/main/java/a/A.java'
OTHER = 'tools/src/main/java/b/B.java'


def sites(text):
    return java.sites(textwrap.dedent(text))


class ThrowsTest(unittest.TestCase):
    def test_a_throw_statement_counts_on_its_line(self):
        self.assertEqual(sites('''\
            class A {
                void f(int x) {
                    if (x < 0) {
                        throw new IllegalArgumentException("x");
                    }
                    throw new IllegalStateException();
                }
            }
            '''), [4, 6])

    def test_a_throws_clause_and_words_that_hold_throw_do_not_count(self):
        self.assertEqual(sites('void f() throws Exception { rethrow(e); Throwable t = throwable; $throw(); }\n'), [])

    def test_comments_do_not_count_and_do_not_nest(self):
        self.assertEqual(sites('// throw new X();\n/* throw\n throw */ int a;\n/** @throws X if throw */\n'), [])
        self.assertEqual(sites('/* a /* b */ throw x;\n'), [1])

    def test_string_and_character_literals_and_text_blocks_do_not_count(self):
        self.assertEqual(sites('String s = "throw \\" throw";\nchar c = \'"\'; throw x;\n'), [2])
        self.assertEqual(sites("char q = '\\''; String t = \"throw\";\n"), [])
        self.assertEqual(sites('String b = """\n    throw "quoted" \\""" throw\n    """; throw y;\n'), [3])

    def test_lines_are_kept_through_blanked_text(self):
        self.assertEqual(sites('/*\n\n*/\nString s = "a";\n// x\nthrow z;\n'), [6])


class GuardTest(unittest.TestCase):
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

    def remove(self, path):
        subprocess.run(['git', '-C', str(self.root), 'rm', '-q', '-f', path], check=True)

    def record(self, *sources):
        (self.root / java.SOURCES).write_text(json.dumps({'_comment': 'test', 'sources': list(sources)}))

    def run_check(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            code = java.main(list(args), self.root)
        return code, out.getvalue()

    def test_only_main_sources_of_tracked_java_files_are_read(self):
        self.write(MAIN, 'class A {}\n')
        self.write(OTHER, 'class B {}\n')
        self.write('mod/src/test/java/a/T.java', 'class T { void f() { throw new X(); } }\n')
        self.write('mod/src/main/scala/a/S.scala', 'object S\n')
        (self.root / 'mod/src/main/java/a/Untracked.java').write_text('class U {}\n')
        self.assertEqual(java.java_main_sources(self.root), [MAIN, OTHER])

    def test_every_tracked_java_file_under_a_src_main_directory_is_read(self):
        root_source = 'src/main/java/Root.java'
        generated = 'tools/src/main/java/src_managed/G.java'
        nested = 'a/b/src/main/java/c/N.java'
        for path in (root_source, generated, nested):
            self.write(path, 'class X {}\n')
        self.write('src/mainly/java/M.java', 'class M {}\n')
        self.write('docs/src/Main.java', 'class M {}\n')
        self.assertEqual(java.java_main_sources(self.root), sorted([root_source, generated, nested]))

    def test_a_source_added_or_removed_fails_until_the_list_changes(self):
        self.write(MAIN, 'class A {}\n')
        self.record(MAIN)
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.write(OTHER, 'class B {}\n')
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn(f'not listed: {OTHER}', out)
        self.assertIn(java.RULE, out)
        self.assertEqual(self.run_check('--write-baseline')[0], 1, 'the baseline does not change the list')
        self.record(MAIN, OTHER)
        self.assertEqual(self.run_check()[0], 0)
        self.remove(MAIN)
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn(f'listed, and no Java main source: {MAIN}', out)
        self.record(OTHER)
        self.assertEqual(self.run_check()[0], 0)

    def test_a_missing_or_malformed_list_fails(self):
        self.write(MAIN, 'class A {}\n')
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn(f'{java.SOURCES} is missing', out)
        (self.root / java.SOURCES).write_text(json.dumps({'sources': MAIN}))
        self.assertEqual(self.run_check()[0], 1)
        self.record(MAIN, MAIN)
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn(f'listed twice: {MAIN}', out)

    def test_a_unicode_escape_fails_wherever_it_is(self):
        self.write(MAIN, 'class A {}\n')
        self.record(MAIN)
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        spellings = [
            ('a throw spelled with an escape', 'class A { void f() { \\u0074hrow new X(); } }\n', 1),
            ('an escaped line break that ends a comment',
             'class A {\n  // a note \\u000a throw new X();\n}\n', 2),
            ('an escape with more than one u', 'class A {\n\n  String s = "\\uuuu0041";\n}\n', 3),
            ('an escape whose backslash is escaped', 'class A { String s = "\\\\u0041"; }\n', 1),
        ]
        for what, text, line in spellings:
            with self.subTest(what):
                self.write(MAIN, text)
                code, out = self.run_check()
                self.assertEqual(code, 1, f'{what} passed')
                self.assertIn('Unicode escapes', out)
                self.assertIn(f'{MAIN}:{line}', out)
        self.write(MAIN, 'class A { String s = "\\\\ u0041 \\\\n"; }\n')
        self.assertEqual(self.run_check()[0], 0, 'a backslash before no escape is text')

    def test_a_new_throw_fails_and_the_baseline_only_shrinks(self):
        self.write(MAIN, 'class A { void f() { throw new X(); } }\n')
        self.write(OTHER, 'class B {}\n')
        self.record(MAIN, OTHER)
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.assertEqual(self.run_check()[0], 0)
        self.write(OTHER, 'class B {\n  void g() { throw new Y(); }\n}\n')
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn(f'{OTHER}: 0 -> 1', out)
        self.assertIn(f'{OTHER}:2', out)
        self.assertEqual(self.run_check('--write-baseline')[0], 1, 'the baseline refuses an increase')
        self.write(OTHER, 'class B {}\n')
        self.write(MAIN, 'class A { java.util.Optional<X> f() { return java.util.Optional.empty(); } }\n')
        code, out = self.run_check()
        self.assertEqual(code, 1, 'a removed throw leaves no allowance to reuse')
        self.assertIn(f'{MAIN}: 1 -> 0', out)
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.assertEqual(self.run_check()[0], 0)
        self.assertEqual(json.loads((self.root / java.BASELINE.path).read_text())['files'], {})


class RepositoryTest(unittest.TestCase):
    def test_the_repository_passes(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = java.main([])
        self.assertEqual(code, 0, out.getvalue())


if __name__ == '__main__':
    unittest.main()
