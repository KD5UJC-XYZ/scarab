# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "reproducible_build", Path(__file__).resolve().parents[1] / "reproducible_build.py")
repro = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(repro)


class ReproducibleBuildTests(unittest.TestCase):
    def test_source_copies_have_no_shared_outputs_and_retain_identity(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            source = root / "source"
            source.mkdir()
            (source / "build.sbt").write_text("source", encoding="utf-8")
            (source / "target").mkdir()
            (source / "target/stale.jar").write_bytes(b"must not be copied")
            manifest = {"gitCommit": "a" * 40,
                        "files": {"build.sbt": repro.sha256(source / "build.sbt")}}
            for destination in [root / "first", root / "different/depth/second"]:
                repro.copy_source(source, destination, manifest)
                self.assertFalse((destination / "target").exists())
                self.assertEqual((destination / ".git/HEAD").read_text(), "a" * 40 + "\n")
                (destination / "build.sbt").write_text("local change", encoding="utf-8")
            self.assertEqual((source / "build.sbt").read_text(), "source")

    def test_modified_frozen_source_is_rejected_before_build(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            (root / "source").mkdir()
            item = root / "source/build.sbt"
            item.write_text("original", encoding="utf-8")
            manifest = {"gitCommit": "a" * 40, "files": {"build.sbt": repro.sha256(item)}}
            item.write_text("changed", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "frozen source changed"):
                repro.copy_source(root / "source", root / "copy", manifest)

    def test_comparison_rejects_byte_changes_and_reports_entry(self):
        with tempfile.TemporaryDirectory() as scratch:
            first, second = Path(scratch) / "first.zip", Path(scratch) / "second.zip"
            for file in [first, second]:
                with zipfile.ZipFile(file, "w") as archive:
                    archive.writestr("lib/node.jar", b"same")
            repro.compare(first, second)
            with zipfile.ZipFile(second, "w") as archive:
                archive.writestr("lib/node.jar", b"changed")
            with self.assertRaisesRegex(ValueError, "lib/node.jar"):
                repro.compare(first, second)

    def test_metadata_difference_fails_even_when_contents_match(self):
        with tempfile.TemporaryDirectory() as scratch:
            first, second = Path(scratch) / "first.zip", Path(scratch) / "second.zip"
            for file, year in [(first, 1980), (second, 2000)]:
                with zipfile.ZipFile(file, "w") as archive:
                    info = zipfile.ZipInfo("node", date_time=(year, 1, 1, 0, 0, 0))
                    archive.writestr(info, b"same")
            with self.assertRaisesRegex(ValueError, "metadata or entry ordering"):
                repro.compare(first, second)
