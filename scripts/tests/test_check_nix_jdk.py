# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_nix_jdk

ROOT = Path(__file__).resolve().parents[2]
TOOLCHAIN = """{
  perSystem = {pkgs, ...}: {
    _module.args.scalaToolchain = rec {
      jdk = pkgs.jdk%s;
      sbt = pkgs.sbt.override {jre = jdk;};
    };
  };
}
"""
DOCKERFILE = "# Temurin: tag eclipse-temurin:%s_1-jdk-resolute, index\nFROM eclipse-temurin@sha256:00\n"


class NixJdkTest(unittest.TestCase):
    def test_the_repository_passes(self):
        self.assertEqual(check_nix_jdk.problems((ROOT / check_nix_jdk.TOOLCHAIN).read_text(),
                                                (ROOT / check_nix_jdk.DOCKERFILE).read_text()), [])

    def test_another_update_of_the_same_major_passes(self):
        self.assertEqual(check_nix_jdk.problems(TOOLCHAIN % "25", DOCKERFILE % "25.0.9.1"), [])

    def test_another_major_fails_naming_both(self):
        found = check_nix_jdk.problems(TOOLCHAIN % "26", DOCKERFILE % "25.0.4.1")
        self.assertEqual(len(found), 1, found)
        self.assertIn("JDK 26", found[0])
        self.assertIn("Temurin 25.0.4.1+1", found[0])

    def test_a_major_that_only_shares_a_prefix_fails(self):
        self.assertEqual(len(check_nix_jdk.problems(TOOLCHAIN % "2", DOCKERFILE % "25.0.4.1")), 1)

    def test_a_flake_naming_no_jdk_or_two_fails(self):
        for toolchain in ("{ }\n", TOOLCHAIN % "25" + "jdk = pkgs.jdk21;\n"):
            with self.subTest(toolchain=toolchain):
                found = check_nix_jdk.problems(toolchain, DOCKERFILE % "25.0.4.1")
                self.assertEqual(len(found), 1, found)
                self.assertIn("not once", found[0])

    def test_a_dockerfile_naming_no_tag_or_two_fails(self):
        for dockerfile in ("FROM eclipse-temurin@sha256:00\n",
                           "# was eclipse-temurin:25.0.1_1-jdk-noble\n" + DOCKERFILE % "21.0.9"):
            with self.subTest(dockerfile=dockerfile):
                found = check_nix_jdk.problems(TOOLCHAIN % "21", dockerfile)
                self.assertEqual(len(found), 1, found)
                self.assertIn("eclipse-temurin tags, not one", found[0])

    def test_commented_bindings_are_not_read(self):
        for comment in ("/* jdk = pkgs.jdk25; */", "# jdk = pkgs.jdk25;"):
            with self.subTest(comment=comment):
                toolchain = (TOOLCHAIN % "21").replace("sbt =", comment + "\n      sbt =")
                found = check_nix_jdk.problems(toolchain, DOCKERFILE % "25.0.4.1")
                self.assertEqual(len(found), 1, found)
                self.assertIn("JDK 21", found[0])

    def test_a_parenthesised_binding_is_read(self):
        toolchain = (TOOLCHAIN % "21").replace("pkgs.jdk21", "(pkgs.jdk21)")
        self.assertEqual(len(check_nix_jdk.problems(toolchain, DOCKERFILE % "25.0.4.1")), 1)
        self.assertEqual(check_nix_jdk.problems(toolchain, DOCKERFILE % "21.0.9"), [])

    def test_a_release_naming_only_its_major(self):
        self.assertEqual(check_nix_jdk.problems(TOOLCHAIN % "25", DOCKERFILE % "25"), [])
        self.assertEqual(len(check_nix_jdk.problems(TOOLCHAIN % "21", DOCKERFILE % "25")), 1)

    def test_main_exits_one_on_a_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "flake").mkdir()
            (root / "docker").mkdir()
            (root / check_nix_jdk.TOOLCHAIN).write_text(TOOLCHAIN % "21")
            (root / check_nix_jdk.DOCKERFILE).write_text(DOCKERFILE % "25.0.4.1")
            self.assertEqual(check_nix_jdk.main(["--root", str(root)]), 1)
            (root / check_nix_jdk.TOOLCHAIN).write_text(TOOLCHAIN % "25")
            self.assertEqual(check_nix_jdk.main(["--root", str(root)]), 0)


if __name__ == "__main__":
    unittest.main()
