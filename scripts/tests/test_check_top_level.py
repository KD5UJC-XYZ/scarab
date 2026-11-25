# SPDX-License-Identifier: Apache-2.0
"""check_top_level: no tracked file in a top-level directory that is not on the list."""
from contextlib import redirect_stdout
from pathlib import Path
import io
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_top_level as top

ROOT = Path(__file__).resolve().parents[2]


class UnlistedTest(unittest.TestCase):
    def test_an_unlisted_directory_is_named_with_its_files_and_listed_ones_and_top_level_files_pass(self):
        paths = ['docs/process.md', 'specs/1381/tasks.md', 'README.md', 'specs/1381/plan.md', '.specify/memory.md',
                 'flake.nix', 'node/src/main/scala/A.scala']
        self.assertEqual(top.unlisted(paths), {'.specify': ['.specify/memory.md'],
                                               'specs': ['specs/1381/plan.md', 'specs/1381/tasks.md']})

    def test_only_the_first_segment_counts_and_case_is_kept(self):
        self.assertEqual(top.unlisted(['docs/specs/plan.md', 'scripts/tests/x.py']), {})
        self.assertEqual(top.unlisted(['Docs/x.md', 'docs-old/y.md']), {'Docs': ['Docs/x.md'],
                                                                        'docs-old': ['docs-old/y.md']})

    def test_a_list_is_honoured(self):
        self.assertEqual(top.unlisted(['specs/a.md'], frozenset({'specs'})), {})

    def test_a_top_level_submodule_is_a_directory_and_a_path_counts_once(self):
        self.assertEqual(top.unlisted(['vendor', 'notes.md', 'docs/sub'], submodules=frozenset({'vendor', 'docs/sub'})),
                         {'vendor': ['vendor']})
        self.assertEqual(top.unlisted(['specs/a.md'] * 3), {'specs': ['specs/a.md']})

    def test_the_index_listing_gives_every_path_and_marks_submodules(self):
        listing = (b'100644 aa 1\tspecs/a.md\x00100644 bb 2\tspecs/a.md\x00100644 cc 3\tspecs/a.md\x00'
                   b'160000 dd 0\tvendor\x00120000 ee 0\tlink\x00100644 ff 0\tsp\xffecs/b.md\x00')
        paths, submodules = top.index(listing)
        self.assertEqual(paths, ['specs/a.md'] * 3 + ['vendor', 'link', 'sp\udcffecs/b.md'])
        self.assertEqual(submodules, frozenset({'vendor'}))

    def test_a_path_that_is_not_printable_is_shown_escaped(self):
        self.assertEqual(top.shown('specs/plan.md'), 'specs/plan.md')
        self.assertEqual(top.shown('a\nb'), "'a\\nb'")
        self.assertEqual(top.shown('sp\udcffecs'), "'sp\\udcffecs'")
        self.assertEqual(top.shown('caf\u00e9/plan.md'), "'caf\\xe9/plan.md'")

    def test_the_list_holds_single_names_and_today_s_tree_passes(self):
        self.assertEqual([d for d in top.DIRECTORIES if not d or '/' in d], [])
        paths, submodules = top.tracked(ROOT)
        self.assertEqual(top.unlisted(paths, submodules=submodules), {})


class RepositoryTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / '.gitignore').write_text('/.workspace/\ntarget/\n.cache/\n')
        self.write('docs/process.md')

    def write(self, path, add=True):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text('x\n')
        if add:
            subprocess.run(['git', '-C', str(self.root), 'add', path], check=True)

    def run_check(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = top.main([], self.root)
        return code, out.getvalue()

    def test_ignored_and_untracked_directories_never_count(self):
        for path in ('.workspace/notes/plan.md', 'target/a.class', 'node/target/b.class', '.cache/c.jar'):
            self.write(path, add=False)
        self.write('specs/draft.md', add=False)
        code, out = self.run_check()
        self.assertEqual(code, 0, out)
        self.assertIn('every tracked top-level directory is on the list', out)

    def test_a_tracked_unlisted_directory_fails_and_says_where_notes_go_and_how_to_list_one(self):
        self.write('specs/1381-scala-nix/spec.md')
        self.write('specs/1381-scala-nix/tasks.md')
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn('  specs/: 2 tracked paths, such as specs/1381-scala-nix/spec.md', out)
        self.assertIn('working note', out)
        self.assertIn('docs/process.md', out)
        self.assertIn('design document', out)
        self.assertIn('is added to DIRECTORIES', out)
        self.assertIn('in the pull request that adds it', out)

    def test_a_force_added_ignored_directory_is_tracked_and_counts(self):
        self.write('.workspace/notes/plan.md', add=False)
        subprocess.run(['git', '-C', str(self.root), 'add', '-f', '.workspace/notes/plan.md'], check=True)
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn('  .workspace/: 1 tracked path, such as .workspace/notes/plan.md', out)

    def test_a_top_level_submodule_fails_and_a_top_level_file_does_not(self):
        self.write('notes.md')
        self.assertEqual(self.run_check()[0], 0)
        # an object id of the repository's own hash; a gitlink's commit need not exist
        commit = subprocess.run(['git', '-C', str(self.root), 'hash-object', '--stdin'], input=b'',
                                check=True, capture_output=True).stdout.decode().strip()
        subprocess.run(['git', '-C', str(self.root), 'update-index', '--add', '--cacheinfo',
                        f'160000,{commit},vendor'], check=True)
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn('  vendor/: 1 tracked path, such as vendor\n', out)

    def test_a_name_with_a_newline_or_bytes_that_are_not_utf_8_is_reported_on_one_line(self):
        # in the index only, so that a file system that refuses such names does not matter
        blob = subprocess.run(['git', '-C', str(self.root), 'hash-object', '-w', '--stdin'], input=b'x\n',
                              check=True, capture_output=True).stdout.strip()
        for name in (b'new\nline', b'sp\xffecs'):
            subprocess.run([b'git', b'-C', os.fsencode(self.root), b'update-index', b'--add', b'--cacheinfo',
                            b'100644,' + blob + b',' + name + b'/a.md'], check=True)
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("  'new\\nline'/: 1 tracked path, such as 'new\\nline/a.md'\n", out)
        self.assertIn("  'sp\\udcffecs'/: 1 tracked path, such as 'sp\\udcffecs/a.md'\n", out)
        out.encode('ascii')


if __name__ == '__main__':
    unittest.main()
