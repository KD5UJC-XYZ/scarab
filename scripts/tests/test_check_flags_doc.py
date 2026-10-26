# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_flags_doc as cfd

ROOT = Path(__file__).resolve().parents[2]
MAIN = '''object Main:
  private val Usage =
    "usage: follow [--a <x>] [--b N] " + LogSettings.Usage

  private val Other = "--not-this"
  private enum RemovedFlag(val flag: String):
    case Old extends RemovedFlag(
      "gone"
    )
'''
LOG = '''object LogSettings:
  val Usage: String =
    "[--log-x json|off] " +
      "[--log-y N]"

  val Other = "--nope"
'''
PAGE = "| Flag | Default |\n|---|---|\n| `--a <x>` | off |\n| `--b N`, `--gone` | 1 |\n| `--log-x` | json |\n"


class FlagsTest(unittest.TestCase):
    def test_usage_text_is_read_from_both_literals(self):
        self.assertEqual({"--a", "--b", "--log-x", "--log-y"}, cfd.usage_flags(MAIN, LOG))

    def test_removed_flags_are_the_ones_main_refuses(self):
        self.assertEqual({"--gone"}, cfd.removed_flags(MAIN))

    def test_first_cell_of_a_table_row_names_the_documented_flags(self):
        self.assertEqual({"--a", "--b", "--gone", "--log-x"}, cfd.documented_flags([PAGE]))

    def test_both_directions_are_reported(self):
        usage = cfd.usage_flags(MAIN, LOG)
        result = cfd.compare(usage, cfd.removed_flags(MAIN), cfd.documented_flags([PAGE]))
        self.assertEqual(("--log-y",), result.undocumented)
        self.assertEqual((), result.unknown)
        result = cfd.compare(usage, frozenset(), cfd.documented_flags([PAGE]))
        self.assertEqual(("--gone",), result.unknown)

    def test_missing_usage_is_an_error(self):
        with self.assertRaises(ValueError):
            cfd.usage_literal("object X")


class RepositoryTest(unittest.TestCase):
    def test_flags_reference_matches_main(self):
        self.assertEqual(0, cfd.main(["--root", str(ROOT)]))


if __name__ == "__main__":
    unittest.main()
