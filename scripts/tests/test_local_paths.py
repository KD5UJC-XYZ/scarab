# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_local_paths import findings
from repo_paths import relative_argv, repo_relative

ROOT = Path("/work/checkout")
# Built by concatenation so this file does not itself contain a home path.
LINUX = "/ho" + "me/alice/repo/file"
MAC = "/Us" + "ers/bob/repo/file"


class GuardTest(unittest.TestCase):
    def test_home_paths_are_reported_with_location(self):
        rows = findings([("a.json", f'{{"p": "{LINUX}"}}\n'.encode()), ("b.md", f"x\n{MAC}\n".encode())])
        self.assertEqual(rows, ["a.json:1: /ho" + "me/alice/", "b.md:2: /Us" + "ers/bob/"])

    def test_clean_files_placeholders_and_relative_paths_pass(self):
        files = [("c.md", b"deploy /home/<user>/scarab and .workspace/data and /tmp/scarab\n"),
                 ("d.bin", bytes(range(256)))]
        self.assertEqual(findings(files), [])

    def test_a_justified_allowlist_entry_is_honoured_and_a_blank_one_is_not(self):
        files = [("e.md", LINUX.encode())]
        self.assertEqual(findings(files, {"e.md": "documents the layout"}), [])
        self.assertEqual(len(findings(files, {"e.md": ""})), 1)


class RelativisationTest(unittest.TestCase):
    def test_root_prefix_is_removed_everywhere_in_an_argument(self):
        self.assertEqual(repo_relative("/work/checkout/.workspace/x", ROOT), ".workspace/x")
        self.assertEqual(repo_relative("/work/checkout/a:/work/checkout/b", ROOT), "a:b")
        self.assertEqual(repo_relative("/work/checkout", ROOT), ".")

    def test_outside_paths_and_lookalike_siblings_are_unchanged(self):
        self.assertEqual(repo_relative("/tmp/probe", ROOT), "/tmp/probe")
        self.assertEqual(repo_relative("/work/checkout-other/x", ROOT), "/work/checkout-other/x")

    def test_argv_relativises_paths_and_names_the_interpreter(self):
        argv = [sys.executable, "/work/checkout/scripts/x.py", "--flag", "-C", "/work/checkout/.cache"]
        self.assertEqual(relative_argv(argv, ROOT), [Path(sys.executable).name, "scripts/x.py", "--flag", "-C", ".cache"])
        self.assertEqual(relative_argv([Path("/work/checkout/y")], ROOT), ["y"])


if __name__ == "__main__":
    unittest.main()
