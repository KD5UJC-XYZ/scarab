# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the crypto guard (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from check_crypto_guard import guarded, secret_path_violations, violations  # noqa: E402

Vrf = "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/vrf"
Java = "crypto/src/main/java/xyz/kd5ujc/scarab/crypto/vrf"
PACKAGE = "package xyz.kd5ujc.scarab.crypto.vrf\n"
Witness = "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/witness"
WitnessJava = "crypto/src/main/java/xyz/kd5ujc/scarab/crypto/witness"
WITNESS = "package xyz.kd5ujc.scarab.crypto.witness\n"
WITNESS_JAVA = "package xyz.kd5ujc.scarab.crypto.witness;\n"


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class CryptoGuardTests(unittest.TestCase):
    def tree(self, files):
        tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(tmp.name)
        for rel, text in files.items():
            write(root / rel, text)
        return root, tmp

    def test_references_inside_the_vrf_package_are_allowed(self):
        root, tmp = self.tree(
            {
                f"{Vrf}/StrictDraft03.scala": PACKAGE + "object StrictDraft03 { Draft03Math.uniform(null) }",
                f"{Java}/Draft03Math.java": PACKAGE + "class Draft03Math {}",
            }
        )
        self.assertEqual(list(violations(root)), [])

    def test_a_reference_outside_the_vrf_package_is_flagged(self):
        root, tmp = self.tree(
            {
                "crypto/src/main/scala/xyz/kd5ujc/scarab/ledger/Hostile.scala":
                    "package xyz.kd5ujc.scarab.ledger\nobject Hostile { Ed25519Point.decode(null) }",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Hostile.scala"])

    def test_fixtures_are_not_scanned(self):
        root, tmp = self.tree({"fixtures/vrf/Draft03Math.java": "Draft03Math"})
        self.assertEqual(list(violations(root)), [])

    def test_java_references_outside_the_package_are_flagged(self):
        root, tmp = self.tree(
            {
                "crypto/src/main/java/xyz/kd5ujc/scarab/crypto/other/Uses.java":
                    "package xyz.kd5ujc.scarab.crypto.other;\nEd25519Point p;",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Uses.java"])

    def test_a_matching_directory_with_another_package_declaration_is_flagged(self):
        root, tmp = self.tree(
            {
                f"{Vrf}/Imposter.scala":
                    "package xyz.kd5ujc.scarab.crypto.other\nobject Imposter { Ed25519Point.decode(null) }",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Imposter.scala"])

    def test_a_missing_package_declaration_is_flagged(self):
        root, tmp = self.tree(
            {
                f"{Vrf}/NoPackage.scala": "object NoPackage { Draft03Math.uniform(null) }",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["NoPackage.scala"])

    def test_the_right_package_outside_the_source_tree_is_flagged(self):
        root, tmp = self.tree(
            {
                "elsewhere/scala/xyz/kd5ujc/scarab/crypto/vrf/Lost.scala":
                    PACKAGE + "object Lost { Ed25519Point.decode(null) }",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Lost.scala"])

    def test_a_misleading_directory_name_is_flagged(self):
        root, tmp = self.tree(
            {
                "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/vrf_shadow/Shadow.scala":
                    PACKAGE + "object Shadow { Draft03Math.uniform(null) }",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Shadow.scala"])

    def test_test_sources_under_the_package_are_allowed(self):
        root, tmp = self.tree(
            {
                "crypto/src/test/scala/xyz/kd5ujc/scarab/crypto/vrf/Suite.scala":
                    PACKAGE + "class Suite { def x = Ed25519Point.decode(null) }",
            }
        )
        self.assertEqual(list(violations(root)), [])

    # VartimeEd25519: only its own source, the two verifiers and the witness package's tests.

    def test_the_listed_callers_of_the_verification_arithmetic_are_allowed(self):
        root, tmp = self.tree(
            {
                f"{WitnessJava}/VartimeEd25519.java": WITNESS_JAVA + "final class VartimeEd25519 {}",
                f"{Witness}/StrictEd25519.scala": WITNESS + "object StrictEd25519 { VartimeEd25519.strict() }",
                f"{Witness}/ByronEd25519.scala": WITNESS + "object ByronEd25519 { VartimeEd25519.byron() }",
                "crypto/src/test/scala/xyz/kd5ujc/scarab/crypto/witness/VartimeEd25519Suite.scala":
                    WITNESS + "class VartimeEd25519Suite { VartimeEd25519.fe() }",
            }
        )
        self.assertEqual(list(violations(root)), [])

    def test_a_new_caller_inside_the_witness_package_is_flagged(self):
        root, tmp = self.tree(
            {f"{Witness}/Shortcut.scala": WITNESS + "object Shortcut { VartimeEd25519.strict() }"}
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Shortcut.scala"])

    def test_the_prover_package_may_not_name_the_verification_arithmetic(self):
        root, tmp = self.tree(
            {f"{Vrf}/Draft03Prover.scala": PACKAGE + "object Draft03Prover { VartimeEd25519.fe() }"}
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Draft03Prover.scala"])

    def test_a_tool_in_the_witness_package_is_flagged(self):
        root, tmp = self.tree(
            {
                "tools/src/main/scala/xyz/kd5ujc/scarab/crypto/witness/Bench.scala":
                    WITNESS + "object Bench { VartimeEd25519.strict() }",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Bench.scala"])

    def test_a_listed_name_in_another_module_is_flagged(self):
        root, tmp = self.tree(
            {
                "ledger/src/main/scala/xyz/kd5ujc/scarab/crypto/witness/StrictEd25519.scala":
                    WITNESS + "object StrictEd25519 { VartimeEd25519.strict() }",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["StrictEd25519.scala"])

    def test_a_listed_name_with_another_package_is_flagged(self):
        root, tmp = self.tree(
            {f"{Witness}/StrictEd25519.scala": PACKAGE + "object StrictEd25519 { VartimeEd25519.strict() }"}
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["StrictEd25519.scala"])

    def test_a_test_source_of_another_package_is_flagged(self):
        root, tmp = self.tree(
            {
                "crypto/src/test/scala/xyz/kd5ujc/scarab/crypto/vrf/Suite.scala":
                    PACKAGE + "class Suite { VartimeEd25519.fe() }",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Suite.scala"])


Secret = "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/vrf/secret"
SECRET = "package xyz.kd5ujc.scarab.crypto.vrf.secret\n"
Facade = f"{Vrf}/Draft03Prover.scala"


class SecretPathGuardTests(unittest.TestCase):
    """The secret path of VRF proving names no variable-time routine (#1418)."""

    def found(self, files):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        for rel, text in files.items():
            write(root / rel, text)
        return sorted((p.relative_to(root).as_posix(), what) for p, what in secret_path_violations(root))

    def test_constant_time_calls_are_allowed(self):
        self.assertEqual(
            self.found(
                {
                    f"{Secret}/Draft03Key.scala": SECRET
                    + "object K { table.multiplyRadix16(e); Scalar.reduceWide(n, k); MessageDigest.isEqual(a, b) }",
                    Facade: PACKAGE + "object P { Draft03Math.h2c(pk, alpha).encode() }",
                }
            ),
            [],
        )

    def test_each_variable_time_name_is_refused_in_the_secret_package(self):
        for call in (
            "EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(a, A, b)",
            "VartimeEd25519.verify(m)",
            "scalar.nonAdjacentForm()",
            "X25519Field.sqrtRatioVar(u, v, x)",
            "X25519Field.invVar(x, z)",
            "Ed25519Point.decode(b)",
            "Draft03Math.h2c(pk, alpha)",
            "StrictDraft03.verify(pk, proof, alpha)",
            "StrictEd25519.verify(pk, m, s)",
            "BigInt(1, k)",
            "new java.math.BigInteger(1, k)",
            "java.util.Arrays.equals(a, b)",
            "a.sameElements(b)",
            "Bytes.wrap(buffer)",
            "bytes.unsafeArray",
        ):
            with self.subTest(call=call):
                found = self.found({f"{Secret}/Uses.scala": SECRET + f"object U {{ {call} }}"})
                self.assertEqual([path for path, _ in found][:1], [f"{Secret}/Uses.scala"], call)

    def test_the_prover_file_may_call_only_the_hash_to_curve(self):
        for call in ("Draft03Math.output(g)", "Draft03Math.challenge(h, g, u, v)", "Ed25519Point.decode(g)", "Draft03Math"):
            with self.subTest(call=call):
                found = self.found({Facade: PACKAGE + f"object P {{ Draft03Math.h2c(pk, a); {call} }}"})
                self.assertEqual(len(found), 1, (call, found))

    def test_the_prover_as_it_was_is_refused(self):
        found = self.found(
            {
                Facade: PACKAGE
                + "object P { val c = Draft03Math.challenge(g.h, gs, Ed25519Point.decode(kB), Ed25519Point.decode(kH)) }"
            }
        )
        whats = {what for _, what in found}
        self.assertIn("the variable-time point arithmetic and its decoder", whats)
        self.assertTrue(any(w.startswith("Draft03Math.challenge") for w in whats), whats)

    def test_comments_and_strings_are_not_read(self):
        self.assertEqual(
            self.found(
                {
                    f"{Secret}/Doc.scala": SECRET
                    + "/** not vartimeDoubleScalarMultiplyBasepoint nor [[Ed25519Point]] /* nested BigInt */ */\n"
                    + "// Draft03Math.output, Arrays.equals\n"
                    + 'object D { val s = "VartimeEd25519 sqrtRatioVar"; val t = """Bytes.wrap""" }'
                }
            ),
            [],
        )

    def test_the_package_is_found_by_directory_by_declaration_and_by_chained_clauses(self):
        call = "object U { BigInt(1) }"
        self.assertEqual(len(self.found({f"{Secret}/NoPackage.scala": call})), 1, "by directory")
        self.assertEqual(
            len(self.found({"tools/src/main/scala/xyz/kd5ujc/scarab/elsewhere/T.scala": SECRET + call})),
            1,
            "by declaration, in tools",
        )
        self.assertEqual(
            len(
                self.found(
                    {f"{Vrf}/Chained.scala": "package xyz.kd5ujc.scarab.crypto.vrf\npackage secret\n" + call}
                )
            ),
            1,
            "by chained package clauses",
        )
        self.assertEqual(
            len(self.found({f"{Secret}/inner/Deeper.java": "package xyz.kd5ujc.scarab.crypto.vrf.secret.inner;\n" + call})),
            1,
            "a sub-package",
        )

    def test_test_sources_and_other_packages_are_not_covered(self):
        call = "object U { StrictDraft03.verify(pk, proof, alpha); BigInt(1) }"
        self.assertEqual(
            self.found(
                {
                    "crypto/src/test/scala/xyz/kd5ujc/scarab/crypto/vrf/secret/Suite.scala": SECRET + call,
                    f"{Vrf}/StrictDraft03.scala": PACKAGE + call,
                }
            ),
            [],
        )

    def test_the_guard_fails_on_a_secret_path_finding(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        write(root / f"{Secret}/Uses.scala", SECRET + "object U { X25519Field.sqrtRatioVar(u, v, x) }")
        self.assertEqual(guarded(["check_crypto_guard.py", str(root)]), 1)
        write(root / f"{Secret}/Uses.scala", SECRET + "object U { }")
        self.assertEqual(guarded(["check_crypto_guard.py", str(root)]), 0)


if __name__ == "__main__":
    unittest.main()
