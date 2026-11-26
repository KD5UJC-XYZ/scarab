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
Shared = "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/secret"
SHARED = "package xyz.kd5ujc.scarab.crypto.secret\n"
Kes = "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/kes/secret"
KES = "package xyz.kd5ujc.scarab.crypto.kes.secret\n"
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

    def test_the_shared_working_storage_is_held_to_the_same_rules(self):
        for call in ("Arrays.equals(a, b)", "Bytes.wrap(buffer)", "BigInt(1, k)", "Draft03Math.h2c(pk, a)"):
            with self.subTest(call=call):
                by_directory = self.found({f"{Shared}/Scratch.scala": SHARED + f"object S {{ {call} }}"})
                self.assertEqual([path for path, _ in by_directory][:1], [f"{Shared}/Scratch.scala"], call)
                by_declaration = self.found(
                    {"node/src/main/scala/xyz/kd5ujc/scarab/elsewhere/S.scala": SHARED + f"object S {{ {call} }}"}
                )
                self.assertGreaterEqual(len(by_declaration), 1, call)
        self.assertEqual(
            self.found({f"{Shared}/Scratch.scala": SHARED + "object S { java.util.Arrays.fill(a, 0.toByte) }"}),
            [],
        )

    def test_the_kes_secret_package_is_held_to_the_same_rules(self):
        for call in (
            "Arrays.equals(vk, stored)",
            "a.sameElements(b)",
            "BigInt(target)",
            "Bytes.wrap(material)",
            "StrictEd25519.verify(pk, m, s)",
            "new org.bouncycastle.crypto.digests.Blake2bDigest(256)",
            "org.bouncycastle.math.ec.rfc8032.Ed25519.sign(sk, 0, m, 0, n, sig, 0)",
            "new Blake2bDigest(256)",
        ):
            with self.subTest(call=call):
                found = self.found({f"{Kes}/Sum6Key.scala": KES + f"object K {{ {call} }}"})
                self.assertEqual([path for path, _ in found][:1], [f"{Kes}/Sum6Key.scala"], call)
        self.assertEqual(
            self.found(
                {
                    f"{Kes}/Sum6Key.scala": KES
                    + "import org.bouncycastle.crypto.digests.Blake2bDigest\nobject K { }"
                }
            )[:1],
            [(f"{Kes}/Sum6Key.scala", "Bouncy Castle, whose working arrays are not erased")],
        )
        self.assertEqual(
            self.found(
                {
                    f"{Kes}/Sum6Key.scala": KES
                    + "object K { Scalar.multiplyAndAdd(k, x, r, s); MessageDigest.isEqual(a, b); Arrays.fill(m, 0.toByte) }"
                }
            ),
            [],
        )

    def test_the_kes_verifier_and_signing_facade_are_not_covered(self):
        call = "object K { BigInt(1); StrictEd25519.verify(pk, m, s) }"
        self.assertEqual(
            self.found(
                {
                    "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/kes/Sum6Kes.scala": "package xyz.kd5ujc.scarab.crypto.kes\n" + call,
                    "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/kes/Sum6KesSigning.scala": "package xyz.kd5ujc.scarab.crypto.kes\n" + call,
                }
            ),
            [],
        )

    def test_a_package_that_only_starts_like_a_secret_package_is_not_covered(self):
        self.assertEqual(
            self.found(
                {
                    "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/secretive/S.scala":
                        "package xyz.kd5ujc.scarab.crypto.secretive\nobject S { BigInt(1) }"
                }
            ),
            [],
        )

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

    def test_the_expressions_of_an_interpolated_string_are_read(self):
        for line in (
            's"${Ed25519Point.decode(b)}"',
            'f"$x%s ${BigInt(1)}"',
            's"""${VartimeEd25519.verify(m)} and more"""',
            'raw"${a.sameElements(b)}"',
            's"${java.util.Arrays.equals(a, b)}"',
            's"${{ val v = sqrtRatioVar(u, w, z); v }}"',
        ):
            with self.subTest(line=line):
                found = self.found({f"{Secret}/Uses.scala": SECRET + f"object U {{ val t = {line} }}"})
                self.assertEqual(len(found), 1, (line, found))

    def test_the_text_of_an_interpolated_string_is_not_read(self):
        self.assertEqual(
            self.found({f"{Secret}/Text.scala": SECRET + 'object T { val t = s"no vartime, no BigInt: $x and ${y.size}" }'}),
            [],
        )

    def test_every_form_of_a_package_clause_puts_a_source_in_the_package(self):
        call = "object U { BigInt(1) }"
        elsewhere = "crypto/src/main/scala/xyz/kd5ujc/scarab/elsewhere"
        for name, text in (
            ("colon", "package xyz.kd5ujc.scarab.crypto.vrf.secret:\n  " + call + "\n"),
            ("braces", "package xyz.kd5ujc.scarab.crypto.vrf.secret {\n  " + call + "\n}\n"),
            ("nested braces", "package xyz.kd5ujc.scarab.crypto.vrf {\npackage secret {\n" + call + "\n}\n}\n"),
            ("nested colons", "package xyz.kd5ujc.scarab.crypto:\n  package vrf.secret:\n    " + call + "\n"),
            ("after other code", "package xyz.kd5ujc.scarab.crypto.vrf\nobject A\npackage secret:\n  " + call + "\n"),
        ):
            with self.subTest(form=name):
                self.assertEqual(len(self.found({f"{elsewhere}/U.scala": text})), 1, name)
        self.assertEqual(
            self.found({f"{elsewhere}/O.scala": "package object secret { " + call + " }"}),
            [],
            "a package object named secret is not the package",
        )

    def test_an_import_may_not_rename_or_bring_in_members_of_java_util_arrays(self):
        for imported in (
            "import java.util.{Arrays as A}",
            "import java.util.{Arrays => A}",
            "import java.util.Arrays.{equals as same}",
            "import java.util.Arrays.equals",
            "import java.util.Arrays.*",
            "import java.util.{\n  Arrays as A\n}",
            "import com.weavechain.curve25519.{Scalar as S}",
        ):
            with self.subTest(imported=imported):
                found = self.found({f"{Secret}/Uses.scala": SECRET + imported + "\nobject U"})
                self.assertGreaterEqual(len(found), 1, imported)
        java = self.found(
            {f"{Secret}/Uses.java": "package xyz.kd5ujc.scarab.crypto.vrf.secret;\nimport static java.util.Arrays.mismatch;\nclass U {}"}
        )
        self.assertGreaterEqual(len(java), 1, "a static import of an Arrays member")

    def test_arrays_may_be_filled_and_nothing_else(self):
        self.assertEqual(
            self.found({f"{Secret}/Fill.scala": SECRET + "import java.util.Arrays\nobject F { Arrays.fill(a, 0.toByte) }"}),
            [],
        )
        for call in ("Arrays.mismatch(a, b)", "java.util.Arrays.compare(a, b)", "Arrays . equals(a, b)", "a corresponds b", "a sameElements b"):
            with self.subTest(call=call):
                found = self.found({f"{Secret}/Uses.scala": SECRET + f"object U {{ {call} }}"})
                self.assertEqual(len(found), 1, (call, found))


if __name__ == "__main__":
    unittest.main()
