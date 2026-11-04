# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_text_policy import ALLOWED, MODULE, OWNER, findings, unexplained

READER = MODULE + "/scala/xyz/kd5ujc/scarab/ledger/Block.scala"


class TextPolicyGuardTest(unittest.TestCase):
    def test_a_new_match_is_reported_with_its_file_and_line(self):
        source = b"object Block:\n  def read(n: Node): String =\n    n.value match\n      case Value.Text(v) => v\n"
        self.assertEqual(findings([(READER, source)]), [READER + ":4: Value.Text("])

    def test_the_owning_reader_passes(self):
        self.assertEqual(findings([(OWNER, b"case Value.Text(v) => v\n")]), [])

    def test_every_allowlisted_reader_passes(self):
        files = [(path, b"case Value.Text(v) => v\n") for path in ALLOWED]
        self.assertEqual(findings(files), [])

    def test_a_blank_allowlist_reason_does_not_allow_a_file(self):
        files = [(READER, b"case Value.Text(v) => v\n")]
        self.assertEqual(findings(files, {READER: ""}), [READER + ":1: Value.Text("])
        self.assertEqual(findings(files, {READER: "reads a key shape only"}), [])

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

    def test_a_type_reference_is_not_a_read(self):
        source = b"def isText(v: Value): Boolean = v.isInstanceOf[Value.Text]\n"
        self.assertEqual(findings([(READER, source)]), [])


if __name__ == "__main__":
    unittest.main()
