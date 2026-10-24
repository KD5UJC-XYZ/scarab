# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_notice import ROOT, DEPENDENCIES, findings, parse_dependencies

DECLARATIONS = """\
object Dependencies {
  object V {
    val cats = "2.13.0"
    val scalus = "1.3.0"
    val dnsjava = "3.6.5" // a comment
  }
  object Libraries {
    val cats = "org.typelevel" %% "cats-core" % V.cats
    val scalus = "org.scalus" %% "scalus" % V.scalus
    val dnsjava = "dnsjava" % "dnsjava" % V.dnsjava
    val munitTest = "org.scalameta" %% "munit" % "1.0.2" % Test
  }
}
"""


class ParseTest(unittest.TestCase):
    def test_versions_resolve_through_the_v_object_and_scope_is_read(self):
        parsed = parse_dependencies(DECLARATIONS)
        self.assertEqual(
            [(d.artifact, d.version, d.test_only) for d in parsed],
            [("cats-core", "2.13.0", False), ("scalus", "1.3.0", False), ("dnsjava", "3.6.5", False), ("munit", "1.0.2", True)],
        )


class FindingsTest(unittest.TestCase):
    def deps(self):
        return parse_dependencies(DECLARATIONS)

    def test_records_naming_every_artifact_at_the_declared_version_pass(self):
        notice = "cats-core, cats-kernel 2.13.0\nScalus (org.scalus:scalus_3 1.3.0)\ndnsjava 3.6.5\nmunit 1.0.2\n"
        inventory = "org.typelevel:cats-core_3 | 2.13.0\norg.scalus:scalus_3 | 1.3.0\ndnsjava:dnsjava | 3.6.5\n"
        self.assertEqual(findings(self.deps(), notice, inventory), [])

    def test_a_missing_runtime_record_is_reported_per_file(self):
        notice = "cats-core 2.13.0\nScalus 1.3.0 scalus_3\ndnsjava 3.6.5\nmunit 1.0.2\n"
        self.assertEqual(
            findings(self.deps(), notice, "org.typelevel:cats-core_3 | 2.13.0\n"),
            ["scalus: not named in docs/licenses.md (declared 1.3.0)",
             "dnsjava: not named in docs/licenses.md (declared 3.6.5)"],
        )

    def test_a_test_only_library_needs_only_the_notice(self):
        notice = "cats-core 2.13.0\nscalus 1.3.0\ndnsjava 3.6.5\n"
        rows = findings(self.deps(), notice, "cats-core 2.13.0\nscalus 1.3.0\ndnsjava 3.6.5\n")
        self.assertEqual([row for row in rows if "munit" in row], ["munit: not named in NOTICE (declared 1.0.2)"])

    def test_a_stale_version_next_to_the_artifact_is_a_mismatch(self):
        notice = "cats-core 2.13.0\nScalus (org.scalus:scalus_3 1.2.0)\ndnsjava 3.6.5\nmunit 1.0.2\n"
        rows = findings(self.deps(), notice, "org.typelevel:cats-core_3 | 2.13.0\norg.scalus:scalus_3 | 1.3.0\ndnsjava 3.6.5\n")
        self.assertEqual(rows, ["scalus: NOTICE names 1.2.0, Dependencies.scala declares 1.3.0"])

    def test_suffixed_neighbours_and_licence_names_do_not_count_as_versions_or_artifacts(self):
        notice = "cats-core 2.13.0\nscalus-secp256k1-jni 0.6.0 Apache-2.0\nscalus_3 1.3.0\ndnsjava 3.6.5\nmunit 1.0.2\n"
        self.assertEqual(findings(self.deps(), notice, "cats-core 2.13.0\nscalus_3 1.3.0\ndnsjava 3.6.5\n"), [])


class RepositoryTest(unittest.TestCase):
    def test_the_checked_in_records_agree_with_the_declarations(self):
        self.assertTrue((ROOT / DEPENDENCIES).is_file())
        source = (ROOT / DEPENDENCIES).read_text(encoding="utf-8")
        self.assertEqual(findings(parse_dependencies(source), (ROOT / "NOTICE").read_text(encoding="utf-8"),
                                  (ROOT / "docs/licenses.md").read_text(encoding="utf-8")), [])
