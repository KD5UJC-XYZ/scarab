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
      jdk = %s;
      sbt = pkgs.sbt.override {jre = jdk;};
    };
  };
}
"""
DOCKERFILE = "# Temurin: tag eclipse-temurin:%s_1-jdk-resolute, index\nFROM eclipse-temurin@sha256:00\n"


class NixJdkTest(unittest.TestCase):
    def check(self, jdk, release, expected=None):
        found = check_nix_jdk.problems(TOOLCHAIN % jdk, DOCKERFILE % release)
        if expected is None:
            self.assertEqual(found, [])
        else:
            self.assertEqual(len(found), 1, found)
            self.assertIn(expected, found[0])

    def test_the_repository_passes(self):
        self.assertEqual(check_nix_jdk.problems((ROOT / check_nix_jdk.TOOLCHAIN).read_text(),
                                                (ROOT / check_nix_jdk.DOCKERFILE).read_text()), [])

    def test_the_same_major_passes_whatever_its_update(self):
        self.check("pkgs.jdk25", "25.0.9.1")
        self.check("pkgs.jdk25", "25")
        self.check("(pkgs.jdk25.override { })", "25.0.4.1")

    def test_another_major_fails_naming_both(self):
        self.check("pkgs.jdk26", "25.0.4.1", "names JDK 26, the JDK image Temurin 25.0.4.1+1")
        self.check("pkgs.jdk2", "25.0.4.1", "names JDK 2,")
        self.check("pkgs.jdk21", "25", "Temurin 25+1")

    def test_every_spelling_of_a_jdk_counts(self):
        for jdk in ("pkgs.openjdk21", "pkgs.jdk21_headless", "pkgs.temurin-bin-21", "pkgs.temurin-jre-bin-21",
                    "pkgs.zulu21", "pkgs.jre21"):
            with self.subTest(jdk=jdk):
                self.check(jdk, "25.0.4.1", "names JDK 21")

    def test_any_other_jdk_the_file_names_fails_however_it_is_bound(self):
        for extra in ("test-jdk = pkgs.jdk21;", "other. jdk = pkgs.jdk21;", "other.\n jdk = pkgs.jdk21;"):
            with self.subTest(extra=extra):
                self.check("pkgs.jdk25;\n      " + extra[:-1], "25.0.4.1", "names JDK 21")
        self.check("(pkgs.jdk21.override { });\n      test-jdk = pkgs.jdk25", "25.0.4.1", "names JDK 21")

    def test_comments_are_not_read(self):
        for comment in ("/* jdk = pkgs.jdk21; */", "# jdk = pkgs.jdk21;", "/* a\n jdk21\n */"):
            with self.subTest(comment=comment):
                self.check(f"pkgs.jdk25 {comment}\n", "25.0.4.1")
        self.check("pkgs.jdk21 # was pkgs.jdk25", "25.0.4.1", "names JDK 21")

    def test_a_flake_naming_no_jdk_fails(self):
        self.check("pkgs.temurin", "25.0.4.1", "names no JDK")

    def test_a_dockerfile_naming_no_tag_or_two_fails(self):
        for dockerfile in ("FROM eclipse-temurin@sha256:00\n",
                           "# was eclipse-temurin:21.0.1_1-jdk-noble\n" + DOCKERFILE % "25.0.4.1"):
            with self.subTest(dockerfile=dockerfile):
                found = check_nix_jdk.problems(TOOLCHAIN % "pkgs.jdk25", dockerfile)
                self.assertEqual(len(found), 1, found)
                self.assertIn("eclipse-temurin tags, not one", found[0])

    def test_main_exits_one_on_a_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "flake").mkdir()
            (root / "docker").mkdir()
            (root / check_nix_jdk.TOOLCHAIN).write_text(TOOLCHAIN % "pkgs.jdk21")
            (root / check_nix_jdk.DOCKERFILE).write_text(DOCKERFILE % "25.0.4.1")
            self.assertEqual(check_nix_jdk.main(["--root", str(root)]), 1)
            (root / check_nix_jdk.TOOLCHAIN).write_text(TOOLCHAIN % "pkgs.jdk25")
            self.assertEqual(check_nix_jdk.main(["--root", str(root)]), 0)


if __name__ == "__main__":
    unittest.main()
