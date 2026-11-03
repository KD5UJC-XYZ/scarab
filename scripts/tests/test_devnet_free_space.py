# SPDX-License-Identifier: Apache-2.0
"""The devnet's free-space check at the start of a run and the run-directory size at its end (issue 610).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import io
import os
import pathlib
import subprocess
import sys
import tempfile
import types
import unittest
from collections import namedtuple
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cli, util  # noqa: E402

Usage = namedtuple("Usage", "total used free")
ENTRY = pathlib.Path(__file__).resolve().parents[1] / "devnet.py"


def usage_with(free, seen=None):
    def usage(path):
        if seen is not None:
            seen.append(path)
        return Usage(10 * free + 1, 0, free)
    return usage


class FreeSpaceTest(unittest.TestCase):
    def test_below_the_margin_is_a_typed_shortfall(self):
        with tempfile.TemporaryDirectory() as tmp:
            low = util.free_space_shortfall(pathlib.Path(tmp), 2 * util.GIB, usage_with(util.GIB))
        self.assertIsInstance(low, util.LowDiskSpace)
        self.assertEqual((low.free, low.required), (util.GIB, 2 * util.GIB))
        message = low.message()
        self.assertIn("1.0 GiB free", message)
        self.assertIn("the margin is 2.0 GiB", message)
        self.assertIn("--min-free-gib", message)

    def test_at_or_above_the_margin_is_none(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(util.free_space_shortfall(pathlib.Path(tmp), util.GIB, usage_with(util.GIB)))
            self.assertIsNone(util.free_space_shortfall(pathlib.Path(tmp), util.GIB, usage_with(3 * util.GIB)))

    def test_a_run_directory_not_yet_created_is_measured_on_its_nearest_existing_ancestor(self):
        seen = []
        with tempfile.TemporaryDirectory() as tmp:
            util.free_space_shortfall(pathlib.Path(tmp) / "data" / "run", util.GIB, usage_with(util.GIB, seen))
            self.assertEqual(seen, [pathlib.Path(tmp)], "disk_usage must be asked about a path that exists")


class TreeSizeTest(unittest.TestCase):
    def test_a_missing_directory_has_no_size(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(util.tree_size(pathlib.Path(tmp) / "gone"))

    def test_counts_nested_files_and_does_not_follow_symbolic_links(self):
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as outside:
            root = pathlib.Path(tmp) / "run"
            (root / "db").mkdir(parents=True)
            (root / "db" / "chunk").write_bytes(os.urandom(256 * 1024))
            empty = util.tree_size(root)
            big = pathlib.Path(outside) / "big"
            big.write_bytes(os.urandom(4 * 1024 * 1024))
            (root / "link").symlink_to(pathlib.Path(outside), target_is_directory=True)
            size = util.tree_size(root)
        self.assertGreaterEqual(empty, 256 * 1024, "the nested file is counted")
        self.assertLess(size, 1024 * 1024, f"the 4 MiB file behind the link must not be counted, got {size}")

    def test_human_bytes(self):
        self.assertEqual(util.human_bytes(512), "512 B")
        self.assertEqual(util.human_bytes(1536), "1.5 KiB")
        self.assertEqual(util.human_bytes(5 * util.GIB), "5.0 GiB")


class RunWrapperTest(unittest.TestCase):
    def args(self, command, min_free_gib=5.0):
        calls = []
        args = types.SimpleNamespace(command=command, name="free-space-test", min_free_gib=min_free_gib,
                                     run=lambda a: calls.append(a.command))
        return args, calls

    def test_a_run_below_the_margin_is_refused_before_it_starts(self):
        args, calls = self.args("soak")
        out = io.StringIO()
        refuse = lambda path, required: util.LowDiskSpace(path, 1, required)  # noqa: E731
        with self.assertRaises(SystemExit) as raised:
            cli.run(args, out=out, shortfall=refuse, size=lambda p: 0)
        self.assertEqual(calls, [], "the scenario must not start")
        self.assertIn("not enough free space", str(raised.exception.code))
        self.assertEqual(out.getvalue(), "", "no size line for a run that never started")

    def test_the_margin_is_passed_in_bytes_and_zero_skips_the_check(self):
        asked = []
        record = lambda path, required: asked.append(required)  # noqa: E731
        cli.run(self.args("pool", 2.5)[0], out=io.StringIO(), shortfall=record, size=lambda p: None)
        cli.run(self.args("pool", 0)[0], out=io.StringIO(), shortfall=record, size=lambda p: None)
        self.assertEqual(asked, [int(2.5 * util.GIB)])

    def test_the_size_is_printed_when_the_run_ends_and_when_it_fails(self):
        args, calls = self.args("relay")
        out = io.StringIO()
        cli.run(args, out=out, shortfall=lambda p, r: None, size=lambda p: 3 * util.GIB)
        self.assertEqual(calls, ["relay"])
        self.assertRegex(out.getvalue(), r"^run directory .*free-space-test: 3\.0 GiB\n$")

        def failing(_):
            raise SystemExit("scenario failed")
        args.run = failing
        out = io.StringIO()
        with self.assertRaises(SystemExit):
            cli.run(args, out=out, shortfall=lambda p, r: None, size=lambda p: None)
        self.assertRegex(out.getvalue(), r"free-space-test: removed\n$")

    def test_commands_that_start_no_run_are_neither_checked_nor_measured(self):
        for command in ("down", "tip", "compare", "load", "oracle"):
            args, calls = self.args(command)
            out = io.StringIO()
            cli.run(args, out=out, shortfall=lambda p, r: self.fail(f"{command} must not be checked"),
                    size=lambda p: self.fail(f"{command} must not be measured"))
            self.assertEqual((calls, out.getvalue()), ([command], ""))

    def test_every_run_command_is_a_subcommand(self):
        with patch.object(sys, "argv", ["devnet.py", "--help"]), \
                patch("sys.stdout", new_callable=io.StringIO) as help_text, self.assertRaises(SystemExit):
            cli.main()
        for command in cli.RUNS:
            self.assertIn(command, help_text.getvalue())


class HelpTest(unittest.TestCase):
    def test_help_documents_the_margin_flag(self):
        result = subprocess.run([sys.executable, str(ENTRY), "--help"], capture_output=True, text=True, check=True)
        self.assertIn("--min-free-gib", result.stdout)
        self.assertIn("0 skips the check", " ".join(result.stdout.split()))


if __name__ == "__main__":
    unittest.main()
