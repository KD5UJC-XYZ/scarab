# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import tempfile
import sys
import unittest
import zipfile


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

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
            identity = dict.fromkeys(repro.release_identity.KEYS, "")
            identity.update(schemaVersion="1", mode="development", gitCommit="a"*40,
                            version="0.0.0-dev.g" + "a"*40, channel="development")
            repro.release_identity.write(identity, source / repro.release_identity.IDENTITY_FILE)
            manifest = {"gitCommit": "a" * 40, "buildIdentity": identity,
                        "files": {name: repro.sha256(source / name)
                                  for name in ["build.sbt", repro.release_identity.IDENTITY_FILE]}}
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

    def test_fat_jar_resource_contract_rejects_lost_providers_and_overlay(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            source = root / "source"
            portable = "supranational/blst/Linux/amd64/libblst.so"
            native = source / "blst-native/src/main/resources" / portable
            native.parent.mkdir(parents=True)
            native.write_bytes(b"committed portable")
            (source / "LICENSE").write_bytes(b"project license")
            (source / "NOTICE").write_bytes(b"project notice")
            dependency = root / "dependency.jar"
            services = "META-INF/services/example.Provider"
            with zipfile.ZipFile(dependency, "w") as jar:
                jar.writestr(services, b"example.One\nexample.Two\n")
                jar.writestr("LICENSE", b"dependency terms")
                jar.writestr(portable, b"alternate native build")
            entries = {
                "META-INF/MANIFEST.MF": (
                    b"Manifest-Version: 1.0\r\nMain-Class: xyz.kd5ujc.scarab.node.Main\r\n"
                    b"Multi-Release: true\r\n\r\n"),
                "META-INF/scarab/build-info.properties": b"gitCommit=" + b"a" * 40 + b"\n",
                "META-INF/scarab/LICENSE": b"project license",
                "META-INF/scarab/NOTICE": b"project notice",
                portable: native.read_bytes(),
                services: b"example.One\nexample.Two\n",
                "LICENSE_dependency": b"dependency terms"
            }
            artifact = root / "node.jar"

            def write(candidate):
                with zipfile.ZipFile(artifact, "w") as jar:
                    for name, content in candidate.items():
                        jar.writestr(name, content)

            write(entries)
            repro.verify_resources(artifact, source, [dependency], "a" * 40)
            for changed, expected in [
                    ({services: b"example.One\n"}, "service providers"),
                    ({"META-INF/scarab/NOTICE": b"changed notice"}, "project legal metadata"),
                    ({portable: b"alternate native build"}, "portable blst overlay"),
                    ({"LICENSE_dependency": b"changed legal terms"}, "legal metadata"),
                    ({"META-INF/STALE.SF": b"old signature"}, "invalid dependency signatures")]:
                with self.subTest(expected=expected):
                    write(dict(entries, **changed))
                    with self.assertRaisesRegex(ValueError, expected):
                        repro.verify_resources(artifact, source, [dependency], "a" * 40)
