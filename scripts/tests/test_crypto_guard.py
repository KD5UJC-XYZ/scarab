# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the crypto guard (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from check_crypto_guard import violations  # noqa: E402

Vrf = "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/vrf"
Ledger = "crypto/src/main/scala/xyz/kd5ujc/scarab/ledger"
Java = "crypto/src/main/java/xyz/kd5ujc/scarab/crypto/vrf"


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
                f"{Vrf}/StrictDraft03.scala": "object StrictDraft03 { Draft03Math.uniform(null) }",
                f"{Java}/Draft03Math.java": "class Draft03Math {}",
            }
        )
        self.assertEqual(list(violations(root)), [])

    def test_a_reference_outside_the_vrf_package_is_flagged(self):
        root, tmp = self.tree(
            {
                f"{Ledger}/Hostile.scala": "object Hostile { Ed25519Point.decode(null) }",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Hostile.scala"])

    def test_fixtures_are_not_scanned(self):
        root, tmp = self.tree(
            {
                "fixtures/vrf/Draft03Math.java": "Draft03Math",
            }
        )
        self.assertEqual(list(violations(root)), [])

    def test_java_references_outside_the_package_are_flagged(self):
        root, tmp = self.tree(
            {
                "crypto/src/main/java/xyz/kd5ujc/scarab/crypto/other/Uses.java": "Ed25519Point p;",
            }
        )
        found = [p.parts[-1] for p in violations(root)]
        self.assertEqual(found, ["Uses.java"])


if __name__ == "__main__":
    unittest.main()
