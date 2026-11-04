# SPDX-License-Identifier: Apache-2.0
import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_text_policy import ALLOWED, MODULE, OWNER, findings, main, reads, sources, unexplained

REPO = Path(__file__).resolve().parents[2]
READER = MODULE + "/scala/xyz/kd5ujc/scarab/ledger/Block.scala"


class TextPolicyGuardTest(unittest.TestCase):
    def test_a_new_match_is_reported_with_its_file_and_line(self):
        source = b"object Block:\n  def read(n: Node): String =\n    n.value match\n      case Value.Text(v) => v\n"
        self.assertEqual(findings([(READER, source)]), [READER + ":4: Value.Text"])

    def test_a_type_test_and_a_spaced_argument_list_are_reads(self):
        self.assertEqual(findings([(READER, b"case t: Value.Text => t.v\n")]), [READER + ":1: Value.Text"])
        self.assertEqual(findings([(READER, b"case Value.Text (v) => v\n")]), [READER + ":1: Value.Text"])
        self.assertEqual(findings([(READER, b"case Value.Text /* c */ (v) => v\n")]),
                         [READER + ":1: Value.Text"])

    def test_a_qualified_name_is_not_a_mention(self):
        # A longer identifier that ends in `Value` is a different type, not a read of this one.
        self.assertEqual(findings([(READER, b"case t: SomeValue.Text => t.v\n")]), [])

    def test_the_owning_reader_passes(self):
        self.assertEqual(findings([(OWNER, b"case Value.Text(v) => v\n")]), [])

    def test_every_allowlisted_reader_passes(self):
        files = [(path, b"case Value.Text(v) => v\n") for path in ALLOWED]
        self.assertEqual(findings(files), [])

    def test_a_blank_allowlist_reason_does_not_allow_a_file(self):
        files = [(READER, b"case Value.Text(v) => v\n")]
        self.assertEqual(findings(files, {READER: ""}), [READER + ":1: Value.Text"])
        self.assertEqual(findings(files, {READER: "names a key shape only"}), [])

    def test_comments_and_literals_are_not_reads(self):
        source = (b"// Value.Text(v) is the policy\n/* a /* nested */ Value.Text(v) */\n"
                  b'val doc = "Value.Text("\nval quote = \'"\'\nval paren = \'(\'\n')
        self.assertEqual(findings([(READER, source)]), [])

    def test_only_ledger_main_scala_sources_are_read(self):
        files = [("node/src/main/scala/x/Relay.scala", b"case Value.Text(v) => v\n"),
                 (MODULE + "/scala/x/Notes.md", b"case Value.Text(v) => v\n"),
                 ("ledger/src/test/scala/x/BlockSuite.scala", b"case Value.Text(v) => v\n")]
        self.assertEqual(findings(files), [])

    def test_every_allowlist_entry_carries_a_reason(self):
        self.assertEqual(unexplained(), [])


class MainTest(unittest.TestCase):
    """`main` on a root of its own, so each non-vacuity exit is driven rather than assumed."""

    def run_on(self, root: str) -> tuple[int, str]:
        argv = sys.argv
        sys.argv = ["check_text_policy.py", root]
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                return main(), out.getvalue()
        finally:
            sys.argv = argv

    def test_a_module_without_sources_fails(self):
        with tempfile.TemporaryDirectory() as root:
            (Path(root) / MODULE).mkdir(parents=True)
            code, out = self.run_on(root)
        self.assertEqual(code, 1)
        self.assertIn(f"{MODULE}: no Scala sources found under it", out)

    def test_a_module_without_the_owning_reader_fails_with_its_message(self):
        with tempfile.TemporaryDirectory() as root:
            reader = Path(root) / MODULE / "scala/x/Block.scala"
            reader.parent.mkdir(parents=True)
            reader.write_text("object Block:\n")
            code, out = self.run_on(root)
        self.assertEqual(code, 1)
        self.assertIn(f"{OWNER}: the owning reader is not among the module's sources", out)


class TreeTest(unittest.TestCase):
    """The guard over the real module, so a wrong owner path or a misspelt allowlist key is not silent."""

    def test_the_tree_passes_the_guard(self):
        self.assertEqual(findings(sources(REPO)), [])

    def test_the_owning_reader_is_among_the_scanned_sources(self):
        self.assertIn(OWNER, {path for path, _ in sources(REPO)})

    def test_every_allowlist_entry_has_a_live_match(self):
        found = reads(sources(REPO))
        self.assertEqual([path for path in ALLOWED if not found.get(path)], [])


if __name__ == "__main__":
    unittest.main()
