# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_local_paths import findings, local_retention
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


class LocalRetentionTest(unittest.TestCase):
    def test_prose_that_keeps_evidence_in_a_local_directory_is_reported_where_the_path_is(self):
        files = [("a.md", b"Logs are retained under `/tmp/scarab/run-1`.\n"),
                 ("b.md", b"intro\nFull states remain outside Git\nunder `/tmp/scarab-9/out` for review.\n"),
                 ("c.md", b"Evidence at `/tmp/scarab/run` is retained for review.\n"),
                 ("d.md", b"Inputs are kept in `.workspace/data/2026-11-02-run`.\n"),
                 ("e.md", b"Logs are retained outside Git; see `/tmp/scarab/evidence`.\n"),
                 ("f.md", b"The second is the maintained corpus under `.workspace/data/corpus`.\n"),
                 ("g.md", b"The complete reproduction package is `/tmp/scarab-247-bench`: sources.\n"),
                 ("h.md", b"The manifest is `binding.json` in `/tmp/scarab/issue-135`, SHA-256 `abc`.\n"),
                 ("k.md", b"A result file is /tmp/scarab/y.json.\n")]
        self.assertEqual(local_retention(files), ["a.md:1: /tmp/scarab/run-1", "b.md:3: /tmp/scarab-9/out",
                                                  "c.md:1: /tmp/scarab/run", "d.md:1: .workspace/data/2026-11-02-run",
                                                  "e.md:1: /tmp/scarab/evidence", "f.md:1: .workspace/data/corpus",
                                                  "g.md:1: /tmp/scarab-247-bench", "h.md:1: /tmp/scarab/issue-135",
                                                  "k.md:1: /tmp/scarab/y.json"])

    def test_an_allowlisted_instruction_file_may_name_local_directories(self):
        files = [("AGENTS.md", b"Data that must outlive a run goes in `.workspace/data/<date>`.\n")]
        self.assertEqual(local_retention(files), [])
        self.assertEqual(len(local_retention(files, {})), 1)

    def test_commands_unrelated_sentences_and_unpublished_wording_pass(self):
        files = [("e.md", b"```sh\nkept=1 run --out /tmp/scarab/x\n```\n"),
                 ("f.md", b"The map is preserved. A result file is /tmp/y.json.\n"),
                 ("g.md", b"Logs are private run artifacts, not published.\n"),
                 ("h.md", b"Logs are retained after running `cp /tmp/scarab/run/log out/`.\n"),
                 ("i.md", b"Hash `abc`; logs retained under `/tmp/scarab/z`.\n"),
                 ("j.md", b"Run data goes to `${SCARAB_DATA_DIR:-/tmp/scarab}`; use `SBT_GLOBAL_BASE=/tmp/sbt-global`.\n")]
        self.assertEqual(local_retention(files), ["i.md:1: /tmp/scarab/z"])

    def test_json_private_and_archive_fields_are_searched_at_any_depth_and_other_fields_are_not(self):
        manifest = (b'{"binary": {"privatePath": "/tmp/scarab/a/runner"}, "archive": {"dirs": [".workspace/data/b"]},'
                    b' "command": ["-v", "/tmp/scarab/c:/probe"], "log": "/tmp/scarab/c/1.log",'
                    b' "privateNote": "kept under /tmp/scarab/d"}')
        self.assertEqual(local_retention([("m.json", manifest)]),
                         ["m.json: binary.privatePath: /tmp/scarab/a/runner", "m.json: archive: .workspace/data/b",
                          "m.json: privateNote: /tmp/scarab/d"])
        described = b'{"archive": {"location": "kept in .workspace/data/run"}}'
        self.assertEqual(local_retention([("n.json", described)]), ["n.json: archive: .workspace/data/run"])
        self.assertEqual(local_retention([("bad.json", b"{not json")]), [])


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
